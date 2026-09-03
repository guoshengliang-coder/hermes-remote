import { randomUUID } from "node:crypto";
import { Pool, type PoolClient, type QueryResultRow } from "pg";
import {
  accountErrors,
  type AccessAuthenticationResult,
  type AccountPrincipal,
  type AccountRepository,
  type InstallationInput,
  type PublicAccount,
  type PublicInstallation,
  type IdempotencyMaterial,
  type ReauthenticationMaterial,
  type ReauthenticationResult,
  type RevokeAllResult,
  type RotationMaterial,
  type SessionCreationResult,
  type SessionMaterial,
  type SessionMutationResult,
  type SessionRotationResult,
  type VerifiedExternalIdentity,
} from "./model.js";

interface IdentityRow extends QueryResultRow {
  account_id: string;
  account_status: "active" | "disabled";
  email: string | null;
  display_name: string | null;
  avatar_url: string | null;
}

interface InstallationRow extends QueryResultRow {
  id: string;
  kind: "phone" | "desktop";
  platform: "android" | "macos";
  display_name: string;
}

interface RefreshRow extends QueryResultRow {
  refresh_id: string;
  family_id: string;
  refresh_expires_at: Date;
  refresh_used_at: Date | null;
  refresh_revoked_at: Date | null;
  session_id: string;
  session_revoked_at: Date | null;
  installation_id: string;
  client_installation_id: string;
  installation_revoked_at: Date | null;
  account_id: string;
  account_status: "active" | "disabled";
}

interface AccessRow extends QueryResultRow {
  session_id: string;
  refresh_family_id: string;
  access_expires_at: Date;
  session_revoked_at: Date | null;
  account_id: string;
  account_status: "active" | "disabled";
  account_email: string | null;
  account_display_name: string | null;
  account_avatar_url: string | null;
  installation_id: string;
  installation_kind: "phone" | "desktop";
  installation_platform: "android" | "macos";
  installation_display_name: string;
  installation_revoked_at: Date | null;
}

interface GrantRow extends QueryResultRow {
  grant_id: string;
  account_id: string;
  installation_id: string;
  scope: string;
  expires_at: Date;
  used_at: Date | null;
  revoked_at: Date | null;
  account_status: "active" | "disabled";
  current_session_revoked_at: Date | null;
}

interface IdempotencySessionRow extends QueryResultRow {
  request_hash: string;
  response_ciphertext: string;
  expires_at: Date;
  session_revoked_at: Date | null;
  installation_revoked_at: Date | null;
  installation_id: string;
  installation_kind: "phone" | "desktop";
  installation_platform: "android" | "macos";
  installation_display_name: string;
  response_refresh_used_at: Date | null;
  response_refresh_revoked_at: Date | null;
  response_refresh_expires_at: Date | null;
}

interface MutationAccessRow extends QueryResultRow {
  session_id: string;
  refresh_family_id: string;
  access_expires_at: Date;
  session_revoked_at: Date | null;
  account_id: string;
  account_status: "active" | "disabled";
  installation_id: string;
  installation_revoked_at: Date | null;
}

interface MutationIdempotencyRow extends QueryResultRow {
  session_id: string | null;
  request_hash: string;
  expires_at: Date;
}

export class PostgresAccountRepository implements AccountRepository {
  constructor(private readonly pool: Pool) {}

  async createSession(
    identity: VerifiedExternalIdentity,
    installation: InstallationInput,
    material: SessionMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionCreationResult> {
    return this.transaction(async (client) => {
      const lockKey = advisoryLockKey(identity.provider, identity.issuer, identity.subject);
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [lockKey]);

      const existing = await client.query<IdentityRow>(
        `SELECT i.account_id, a.status AS account_status, i.email, i.display_name, i.avatar_url
           FROM external_identities i
           JOIN accounts a ON a.id = i.account_id
          WHERE i.provider = $1 AND i.issuer = $2 AND i.subject = $3
          FOR UPDATE OF i, a`,
        [identity.provider, identity.issuer, identity.subject],
      );

      let accountId: string;
      if (existing.rowCount === 0) {
        accountId = randomUUID();
        await client.query("INSERT INTO accounts (id) VALUES ($1)", [accountId]);
        await client.query(
          `INSERT INTO external_identities
             (id, account_id, provider, issuer, subject, email, display_name, avatar_url)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
          [
            randomUUID(), accountId, identity.provider, identity.issuer, identity.subject,
            identity.email ?? null, identity.displayName ?? null, identity.avatarUrl ?? null,
          ],
        );
      } else {
        const row = existing.rows[0];
        if (row.account_status !== "active") throw accountErrors.accountDisabled();
        accountId = row.account_id;
        await client.query(
          `UPDATE external_identities
              SET email = COALESCE($4, email),
                  display_name = COALESCE($5, display_name),
                  avatar_url = COALESCE($6, avatar_url),
                  last_verified_at = now()
            WHERE provider = $1 AND issuer = $2 AND subject = $3`,
          [
            identity.provider, identity.issuer, identity.subject, identity.email ?? null,
            identity.displayName ?? null, identity.avatarUrl ?? null,
          ],
        );
      }

      await client.query(
        "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))",
        [advisoryLockKey(accountId, "auth.google.exchange", idempotency.key)],
      );
      const replay = await client.query<IdempotencySessionRow>(
        `SELECT d.request_hash,
                d.response_ciphertext,
                d.expires_at,
                s.revoked_at AS session_revoked_at,
                i.revoked_at AS installation_revoked_at,
                i.id AS installation_id,
                i.kind AS installation_kind,
                i.platform AS installation_platform,
                i.display_name AS installation_display_name,
                r.used_at AS response_refresh_used_at,
                r.revoked_at AS response_refresh_revoked_at,
                r.expires_at AS response_refresh_expires_at
           FROM account_idempotency_records d
           JOIN account_sessions s ON s.id = d.session_id
           JOIN installations i ON i.id = s.installation_id
           LEFT JOIN refresh_tokens r ON r.id = d.refresh_token_id
          WHERE d.account_id = $1
            AND d.operation = 'auth.google.exchange'
            AND d.idempotency_key = $2`,
        [accountId, idempotency.key],
      );
      if ((replay.rowCount ?? 0) > 0) {
        const saved = replay.rows[0];
        if (saved.request_hash !== idempotency.requestHash || saved.expires_at.getTime() <= Date.now()) {
          return { status: "idempotency_conflict" };
        }
        if (saved.session_revoked_at || saved.installation_revoked_at) return { status: "revoked" };
        if (!saved.response_refresh_expires_at
            || saved.response_refresh_used_at
            || saved.response_refresh_revoked_at
            || saved.response_refresh_expires_at.getTime() <= Date.now()) {
          return { status: "idempotency_conflict" };
        }
        return {
          status: "replayed",
          account: await loadPublicAccount(client, accountId),
          installation: {
            id: saved.installation_id,
            kind: saved.installation_kind,
            platform: saved.installation_platform,
            displayName: saved.installation_display_name,
          },
          responseCiphertext: saved.response_ciphertext,
        };
      }

      const installed = await client.query<InstallationRow>(
        `INSERT INTO installations
           (id, account_id, client_installation_id, kind, platform, display_name, app_version)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         ON CONFLICT (account_id, client_installation_id) DO UPDATE
           SET kind = EXCLUDED.kind,
               platform = EXCLUDED.platform,
               display_name = EXCLUDED.display_name,
               app_version = EXCLUDED.app_version,
               last_seen_at = now(),
               revoked_at = NULL
         RETURNING id, kind, platform, display_name`,
        [
          randomUUID(), accountId, installation.clientInstallationId, installation.kind,
          installation.platform, installation.displayName, installation.appVersion,
        ],
      );
      const installationRow = installed.rows[0];

      await client.query(
        `INSERT INTO account_sessions
           (id, account_id, installation_id, refresh_family_id, access_token_hash, access_expires_at)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [
          material.sessionId, accountId, installationRow.id, material.refreshFamilyId,
          material.accessTokenHash, material.accessExpiresAt,
        ],
      );
      await client.query(
        `INSERT INTO refresh_tokens
           (id, session_id, family_id, token_hash, expires_at)
         VALUES ($1, $2, $3, $4, $5)`,
        [
          material.refreshTokenId, material.sessionId, material.refreshFamilyId,
          material.refreshTokenHash, material.refreshExpiresAt,
        ],
      );
      await client.query(
        `INSERT INTO account_idempotency_records
           (id, account_id, session_id, refresh_token_id, operation, idempotency_key, request_hash,
            response_ciphertext, expires_at)
         VALUES ($1, $2, $3, $4, 'auth.google.exchange', $5, $6, $7, $8)`,
        [
          randomUUID(), accountId, material.sessionId, material.refreshTokenId,
          idempotency.key, idempotency.requestHash,
          idempotency.responseCiphertext, idempotency.expiresAt,
        ],
      );
      await audit(client, accountId, installationRow.id, "auth.google.exchange", {
        platform: installation.platform,
      });

      return {
        status: "created",
        account: await loadPublicAccount(client, accountId),
        installation: publicInstallation(installationRow),
      };
    });
  }

  async rotateSession(
    refreshTokenHash: string,
    clientInstallationId: string,
    material: RotationMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionRotationResult> {
    return this.transaction(async (client) => {
      const found = await client.query<RefreshRow>(
        `SELECT r.id AS refresh_id,
                r.family_id,
                r.expires_at AS refresh_expires_at,
                r.used_at AS refresh_used_at,
                r.revoked_at AS refresh_revoked_at,
                s.id AS session_id,
                s.revoked_at AS session_revoked_at,
                i.id AS installation_id,
                i.client_installation_id::text,
                i.revoked_at AS installation_revoked_at,
                a.id AS account_id,
                a.status AS account_status
           FROM refresh_tokens r
           JOIN account_sessions s ON s.id = r.session_id
           JOIN installations i ON i.id = s.installation_id
           JOIN accounts a ON a.id = s.account_id
          WHERE r.token_hash = $1
          FOR UPDATE OF r, s, i, a`,
        [refreshTokenHash],
      );
      if (found.rowCount === 0) return { status: "invalid" };
      const row = found.rows[0];
      await client.query(
        "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))",
        [advisoryLockKey(row.account_id, "auth.refresh", idempotency.key)],
      );

      if (row.account_status !== "active") {
        await revokeFamily(client, row.family_id);
        return { status: "account_disabled" };
      }
      if (row.client_installation_id !== clientInstallationId) return { status: "invalid" };
      if (row.refresh_revoked_at || row.session_revoked_at || row.installation_revoked_at) {
        return { status: "revoked" };
      }
      const replay = await client.query<{
        request_hash: string;
        response_ciphertext: string;
        expires_at: Date;
        response_refresh_used_at: Date | null;
        response_refresh_revoked_at: Date | null;
        response_refresh_expires_at: Date | null;
      }>(
        `SELECT d.request_hash,
                d.response_ciphertext,
                d.expires_at,
                r.used_at AS response_refresh_used_at,
                r.revoked_at AS response_refresh_revoked_at,
                r.expires_at AS response_refresh_expires_at
           FROM account_idempotency_records d
           LEFT JOIN refresh_tokens r ON r.id = d.refresh_token_id
          WHERE d.account_id = $1 AND d.operation = 'auth.refresh' AND d.idempotency_key = $2`,
        [row.account_id, idempotency.key],
      );
      if ((replay.rowCount ?? 0) > 0) {
        const saved = replay.rows[0];
        if (saved.request_hash !== idempotency.requestHash) {
          return { status: "idempotency_conflict" };
        }
        if (saved.expires_at.getTime() > Date.now()
            && saved.response_refresh_expires_at
            && !saved.response_refresh_used_at
            && !saved.response_refresh_revoked_at
            && saved.response_refresh_expires_at.getTime() > Date.now()) {
          return { status: "replayed", responseCiphertext: saved.response_ciphertext };
        }
        return { status: "idempotency_conflict" };
      }
      if (row.refresh_used_at) {
        await revokeFamily(client, row.family_id);
        await audit(client, row.account_id, row.installation_id, "auth.refresh.reuse_detected", {});
        return { status: "reused" };
      }
      if (row.refresh_expires_at.getTime() <= Date.now()) {
        await revokeFamily(client, row.family_id);
        return { status: "expired" };
      }

      await client.query("UPDATE refresh_tokens SET used_at = now() WHERE id = $1", [row.refresh_id]);
      await client.query(
        `UPDATE account_sessions
            SET access_token_hash = $2, access_expires_at = $3, last_used_at = now()
          WHERE id = $1`,
        [row.session_id, material.accessTokenHash, material.accessExpiresAt],
      );
      await client.query(
        `INSERT INTO refresh_tokens
           (id, session_id, family_id, parent_id, token_hash, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [
          material.refreshTokenId, row.session_id, row.family_id, row.refresh_id,
          material.refreshTokenHash, material.refreshExpiresAt,
        ],
      );
      await client.query("UPDATE installations SET last_seen_at = now() WHERE id = $1", [row.installation_id]);
      await client.query(
        `INSERT INTO account_idempotency_records
           (id, account_id, session_id, refresh_token_id, operation, idempotency_key, request_hash,
            response_ciphertext, expires_at)
         VALUES ($1, $2, $3, $4, 'auth.refresh', $5, $6, $7, $8)`,
        [
          randomUUID(), row.account_id, row.session_id, material.refreshTokenId, idempotency.key,
          idempotency.requestHash, idempotency.responseCiphertext, idempotency.expiresAt,
        ],
      );
      return { status: "rotated" };
    });
  }

  async authenticateAccessToken(accessTokenHash: string): Promise<AccessAuthenticationResult> {
    return this.transaction(async (client) => {
      const found = await client.query<AccessRow>(
        `SELECT s.id AS session_id,
                s.refresh_family_id,
                s.access_expires_at,
                s.revoked_at AS session_revoked_at,
                a.id AS account_id,
                a.status AS account_status,
                x.email AS account_email,
                x.display_name AS account_display_name,
                x.avatar_url AS account_avatar_url,
                i.id AS installation_id,
                i.kind AS installation_kind,
                i.platform AS installation_platform,
                i.display_name AS installation_display_name,
                i.revoked_at AS installation_revoked_at
           FROM account_sessions s
           JOIN accounts a ON a.id = s.account_id
           JOIN installations i ON i.id = s.installation_id
           LEFT JOIN LATERAL (
             SELECT email, display_name, avatar_url
               FROM external_identities
              WHERE account_id = a.id
              ORDER BY last_verified_at DESC
              LIMIT 1
           ) x ON true
          WHERE s.access_token_hash = $1
          FOR UPDATE OF s, a, i`,
        [accessTokenHash],
      );
      if (found.rowCount === 0) return { status: "invalid" };
      const row = found.rows[0];
      if (row.account_status !== "active") return { status: "account_disabled" };
      if (row.session_revoked_at || row.installation_revoked_at) return { status: "revoked" };
      if (row.access_expires_at.getTime() <= Date.now()) return { status: "expired" };

      await client.query("UPDATE account_sessions SET last_used_at = now() WHERE id = $1", [row.session_id]);
      await client.query("UPDATE installations SET last_seen_at = now() WHERE id = $1", [row.installation_id]);
      return {
        status: "active",
        principal: principalFromAccessRow(row),
      };
    });
  }

  async createReauthenticationGrant(
    accountId: string,
    installationId: string,
    currentSessionId: string,
    identity: VerifiedExternalIdentity,
    material: ReauthenticationMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<ReauthenticationResult> {
    return this.transaction(async (client) => {
      const account = await client.query<{ status: "active" | "disabled" }>(
        "SELECT status FROM accounts WHERE id = $1 FOR UPDATE",
        [accountId],
      );
      if (account.rowCount === 0) return { status: "identity_mismatch" };
      if (account.rows[0].status !== "active") return { status: "account_disabled" };

      const matched = await client.query(
        `SELECT 1
           FROM external_identities
          WHERE account_id = $1 AND provider = $2 AND issuer = $3 AND subject = $4`,
        [accountId, identity.provider, identity.issuer, identity.subject],
      );
      if (matched.rowCount === 0) return { status: "identity_mismatch" };

      const installation = await client.query(
        `SELECT 1
           FROM installations i
           JOIN account_sessions s ON s.installation_id = i.id
          WHERE i.id = $1
            AND i.account_id = $2
            AND i.revoked_at IS NULL
            AND s.id = $3
            AND s.account_id = $2
            AND s.revoked_at IS NULL
          FOR UPDATE OF i, s`,
        [installationId, accountId, currentSessionId],
      );
      if (installation.rowCount === 0) return { status: "session_revoked" };

      const operation = `auth.reauth.google:${material.scope}`;
      await client.query(
        "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))",
        [advisoryLockKey(accountId, operation, idempotency.key)],
      );
      const replay = await client.query<{
        request_hash: string;
        response_ciphertext: string;
        expires_at: Date;
        grant_used_at: Date | null;
        grant_revoked_at: Date | null;
        grant_expires_at: Date | null;
      }>(
        `SELECT d.request_hash,
                d.response_ciphertext,
                d.expires_at,
                g.used_at AS grant_used_at,
                g.revoked_at AS grant_revoked_at,
                g.expires_at AS grant_expires_at
           FROM account_idempotency_records d
           LEFT JOIN reauthentication_grants g ON g.id = d.reauthentication_grant_id
          WHERE d.account_id = $1 AND d.operation = $2 AND d.idempotency_key = $3`,
        [accountId, operation, idempotency.key],
      );
      if ((replay.rowCount ?? 0) > 0) {
        const saved = replay.rows[0];
        if (saved.request_hash !== idempotency.requestHash
            || saved.expires_at.getTime() <= Date.now()
            || !saved.grant_expires_at
            || saved.grant_used_at
            || saved.grant_revoked_at
            || saved.grant_expires_at.getTime() <= Date.now()) {
          return { status: "idempotency_conflict" };
        }
        return { status: "replayed", responseCiphertext: saved.response_ciphertext };
      }

      await client.query(
        `UPDATE reauthentication_grants
            SET revoked_at = COALESCE(revoked_at, now())
          WHERE account_id = $1
            AND installation_id = $2
            AND scope = $3
            AND used_at IS NULL
            AND revoked_at IS NULL`,
        [accountId, installationId, material.scope],
      );
      await client.query(
        `INSERT INTO reauthentication_grants
           (id, account_id, installation_id, session_id, scope, token_hash, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [
          material.grantId, accountId, installationId, currentSessionId, material.scope,
          material.grantTokenHash, material.expiresAt,
        ],
      );
      await client.query(
        `INSERT INTO account_idempotency_records
           (id, account_id, session_id, reauthentication_grant_id, operation,
            idempotency_key, request_hash,
            response_ciphertext, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
        [
          randomUUID(), accountId, currentSessionId, material.grantId, operation, idempotency.key,
          idempotency.requestHash, idempotency.responseCiphertext, idempotency.expiresAt,
        ],
      );
      await audit(client, accountId, installationId, "auth.reauthenticated", {
        scope: material.scope,
      });
      return { status: "created" };
    });
  }

  async revokeAllSessions(
    accessTokenHash: string,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeAllResult> {
    return this.transaction(async (client) => {
      const access = await loadMutationAccess(client, accessTokenHash);
      if (!access) return { status: "invalid" };
      await lockMutation(client, access.account_id, "auth.revoke_all", idempotency.key);
      const replay = await loadMutationReplay(
        client,
        access.account_id,
        access.session_id,
        "auth.revoke_all",
        idempotency,
      );
      if (replay) return replay;
      if (access.account_status !== "active") return { status: "account_disabled" };
      if (access.session_revoked_at || access.installation_revoked_at) return { status: "revoked" };
      if (access.access_expires_at.getTime() <= Date.now()) return { status: "expired" };

      const found = await client.query<GrantRow>(
        `SELECT g.id AS grant_id,
                g.account_id,
                g.installation_id,
                g.scope,
                g.expires_at,
                g.used_at,
                g.revoked_at,
                a.status AS account_status,
                s.revoked_at AS current_session_revoked_at
           FROM reauthentication_grants g
           JOIN accounts a ON a.id = g.account_id
           JOIN account_sessions s
             ON s.id = g.session_id
            AND s.id = $4
            AND s.account_id = g.account_id
            AND s.installation_id = g.installation_id
          WHERE g.token_hash = $1
            AND g.account_id = $2
            AND g.installation_id = $3
          FOR UPDATE OF g, a, s`,
        [grantTokenHash, access.account_id, access.installation_id, access.session_id],
      );
      if (found.rowCount === 0) return { status: "invalid" };
      const row = found.rows[0];
      if (row.account_id !== access.account_id || row.installation_id !== access.installation_id) {
        return { status: "invalid" };
      }
      if (row.account_status !== "active") return { status: "account_disabled" };
      if (row.scope !== "account.revoke_all" || row.revoked_at || row.current_session_revoked_at) {
        return { status: "invalid" };
      }
      if (row.used_at) return { status: "used" };
      if (row.expires_at.getTime() <= Date.now()) {
        await client.query(
          "UPDATE reauthentication_grants SET revoked_at = now() WHERE id = $1",
          [row.grant_id],
        );
        return { status: "expired" };
      }

      await client.query("UPDATE reauthentication_grants SET used_at = now() WHERE id = $1", [row.grant_id]);
      await client.query(
        `UPDATE refresh_tokens r
            SET revoked_at = COALESCE(r.revoked_at, now())
           FROM account_sessions s
          WHERE r.session_id = s.id AND s.account_id = $1`,
        [access.account_id],
      );
      await client.query(
        `UPDATE account_sessions
            SET revoked_at = COALESCE(revoked_at, now())
          WHERE account_id = $1`,
        [access.account_id],
      );
      await client.query(
        `UPDATE reauthentication_grants
            SET revoked_at = COALESCE(revoked_at, now())
          WHERE account_id = $1 AND id <> $2 AND used_at IS NULL`,
        [access.account_id, row.grant_id],
      );
      await saveMutationReplay(
        client,
        access.account_id,
        access.session_id,
        "auth.revoke_all",
        idempotency,
      );
      await audit(client, access.account_id, access.installation_id, "auth.revoke_all", {});
      return { status: "completed" };
    });
  }

  async revokeSession(
    accessTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionMutationResult> {
    return this.transaction(async (client) => {
      const access = await loadMutationAccess(client, accessTokenHash);
      if (!access) return { status: "invalid" };
      await lockMutation(client, access.account_id, "auth.sign_out", idempotency.key);
      const replay = await loadMutationReplay(
        client,
        access.account_id,
        access.session_id,
        "auth.sign_out",
        idempotency,
      );
      if (replay) return replay;
      if (access.account_status !== "active") return { status: "account_disabled" };
      if (access.session_revoked_at || access.installation_revoked_at) return { status: "revoked" };
      if (access.access_expires_at.getTime() <= Date.now()) return { status: "expired" };

      await revokeFamily(client, access.refresh_family_id);
      await saveMutationReplay(
        client,
        access.account_id,
        access.session_id,
        "auth.sign_out",
        idempotency,
      );
      await audit(client, access.account_id, access.installation_id, "auth.sign_out", {});
      return { status: "completed" };
    });
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  private async transaction<T>(operation: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const result = await operation(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }
}

async function loadMutationAccess(
  client: PoolClient,
  accessTokenHash: string,
): Promise<MutationAccessRow | undefined> {
  const found = await client.query<MutationAccessRow>(
    `SELECT s.id AS session_id,
            s.refresh_family_id,
            s.access_expires_at,
            s.revoked_at AS session_revoked_at,
            a.id AS account_id,
            a.status AS account_status,
            i.id AS installation_id,
            i.revoked_at AS installation_revoked_at
       FROM account_sessions s
       JOIN accounts a ON a.id = s.account_id
       JOIN installations i ON i.id = s.installation_id
      WHERE s.access_token_hash = $1
      FOR UPDATE OF s, a, i`,
    [accessTokenHash],
  );
  return found.rows[0];
}

async function lockMutation(
  client: PoolClient,
  accountId: string,
  operation: string,
  key: string,
): Promise<void> {
  await client.query(
    "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))",
    [advisoryLockKey(accountId, operation, key)],
  );
}

function advisoryLockKey(...parts: string[]): string {
  return JSON.stringify(parts);
}

async function loadMutationReplay(
  client: PoolClient,
  accountId: string,
  sessionId: string,
  operation: string,
  idempotency: IdempotencyMaterial,
): Promise<{ status: "replayed" | "idempotency_conflict" } | undefined> {
  const replay = await client.query<MutationIdempotencyRow>(
    `SELECT session_id, request_hash, expires_at
       FROM account_idempotency_records
      WHERE account_id = $1 AND operation = $2 AND idempotency_key = $3`,
    [accountId, operation, idempotency.key],
  );
  if ((replay.rowCount ?? 0) === 0) return undefined;
  const saved = replay.rows[0];
  if (saved.session_id !== sessionId
      || saved.request_hash !== idempotency.requestHash
      || saved.expires_at.getTime() <= Date.now()) {
    return { status: "idempotency_conflict" };
  }
  return { status: "replayed" };
}

async function saveMutationReplay(
  client: PoolClient,
  accountId: string,
  sessionId: string,
  operation: string,
  idempotency: IdempotencyMaterial,
): Promise<void> {
  await client.query(
    `INSERT INTO account_idempotency_records
       (id, account_id, session_id, operation, idempotency_key, request_hash,
        response_ciphertext, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
    [
      randomUUID(), accountId, sessionId, operation, idempotency.key,
      idempotency.requestHash, idempotency.responseCiphertext, idempotency.expiresAt,
    ],
  );
}

async function loadPublicAccount(client: PoolClient, accountId: string): Promise<PublicAccount> {
  const result = await client.query<{
    id: string;
    email: string | null;
    display_name: string | null;
    avatar_url: string | null;
  }>(
    `SELECT a.id, i.email, i.display_name, i.avatar_url
       FROM accounts a
       LEFT JOIN LATERAL (
         SELECT email, display_name, avatar_url
           FROM external_identities
          WHERE account_id = a.id
          ORDER BY last_verified_at DESC
          LIMIT 1
       ) i ON true
      WHERE a.id = $1`,
    [accountId],
  );
  const row = result.rows[0];
  return {
    id: row.id,
    ...(row.display_name ? { displayName: row.display_name } : {}),
    ...(row.email ? { email: row.email } : {}),
    ...(row.avatar_url ? { avatarUrl: row.avatar_url } : {}),
  };
}

function publicInstallation(row: InstallationRow): PublicInstallation {
  return {
    id: row.id,
    kind: row.kind,
    platform: row.platform,
    displayName: row.display_name,
  };
}

function principalFromAccessRow(row: AccessRow): AccountPrincipal {
  return {
    account: {
      id: row.account_id,
      ...(row.account_display_name ? { displayName: row.account_display_name } : {}),
      ...(row.account_email ? { email: row.account_email } : {}),
      ...(row.account_avatar_url ? { avatarUrl: row.account_avatar_url } : {}),
    },
    installation: {
      id: row.installation_id,
      kind: row.installation_kind,
      platform: row.installation_platform,
      displayName: row.installation_display_name,
    },
    sessionId: row.session_id,
    refreshFamilyId: row.refresh_family_id,
  };
}

async function revokeFamily(client: PoolClient, familyId: string): Promise<void> {
  await client.query(
    "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, now()) WHERE family_id = $1",
    [familyId],
  );
  await client.query(
    "UPDATE account_sessions SET revoked_at = COALESCE(revoked_at, now()) WHERE refresh_family_id = $1",
    [familyId],
  );
}

async function audit(
  client: PoolClient,
  accountId: string,
  installationId: string | null,
  eventType: string,
  metadata: Record<string, string>,
): Promise<void> {
  await client.query(
    `INSERT INTO account_audit_events
       (id, account_id, installation_id, event_type, metadata)
     VALUES ($1, $2, $3, $4, $5::jsonb)`,
    [randomUUID(), accountId, installationId, eventType, JSON.stringify(metadata)],
  );
}
