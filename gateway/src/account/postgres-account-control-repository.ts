import { randomUUID } from "node:crypto";
import { type Pool, type PoolClient, type QueryResultRow } from "pg";
import type { AccountPrincipal, IdempotencyMaterial } from "./model.js";
import { PROTOCOL_VERSION, type SessionLifecycleEvent } from "@hermes-remote/protocol";
import type {
  AccountControlRepository,
  ActiveBinding,
  BindingCandidate,
  BindingProofMaterial,
  BindingState,
  ConfirmBindingResult,
  ConfirmReplacementResult,
  CreateBindingResult,
  CreateReplacementResult,
  CurrentInstallationRevocationResult,
  ManagedInstallation,
  ReplacementRequest,
  RevokeInstallationResult,
  UnbindResult,
} from "./account-control-model.js";
import type { LifecycleEventPage } from "../lifecycle-event-store.js";

interface BindingRow extends QueryResultRow {
  id: string;
  account_id: string;
  desktop_installation_id: string;
  display_name: string;
  device_id: string;
  public_key: Buffer;
  public_key_fingerprint: string;
  generation: number;
  status: "pending" | "active" | "replaced" | "revoked";
  pending_expires_at: Date | null;
  key_proved_at: Date | null;
  health_checked_at: Date | null;
  connector_online: boolean;
  hermes_reachable: boolean | null;
  hermes_version: string | null;
  gateway_latency_ms: number | null;
  end_to_end_healthy: boolean | null;
  last_seen_at: Date | null;
}

interface IdempotencyRow extends QueryResultRow {
  session_id: string | null;
  connector_binding_id: string | null;
  connector_replacement_request_id: string | null;
  request_hash: string;
  expires_at: Date;
}

interface ReplacementRow extends QueryResultRow {
  id: string;
  account_id: string;
  requesting_installation_id: string;
  previous_binding_id: string;
  candidate_binding_id: string;
  reauthentication_grant_id: string;
  status: "pending" | "consumed" | "cancelled" | "expired";
  expires_at: Date;
}

interface GrantRow extends QueryResultRow {
  id: string;
  scope: string;
  expires_at: Date;
  used_at: Date | null;
  revoked_at: Date | null;
}

interface MutationAccessRow extends QueryResultRow {
  session_id: string;
  access_expires_at: Date;
  session_revoked_at: Date | null;
  account_id: string;
  account_status: "active" | "disabled";
  installation_id: string;
  installation_kind: "phone" | "desktop";
  installation_platform: "android" | "macos";
  installation_revoked_at: Date | null;
}

interface AccountLifecycleRow extends QueryResultRow {
  sequence: string;
  event_id: string;
  device_id: string;
  profile: string | null;
  runtime_session_id: string;
  stored_session_id: string;
  event_kind: SessionLifecycleEvent["event"];
  lifecycle_state: SessionLifecycleEvent["state"];
  occurred_at: Date;
  title: string | null;
  received_at: Date;
  delivered_at: Date | null;
  read_at: Date | null;
}

export class PostgresAccountControlRepository implements AccountControlRepository {
  constructor(
    private readonly pool: Pool,
    private readonly maxAccountLifecycleEvents = 10_000,
  ) {
    if (!Number.isSafeInteger(maxAccountLifecycleEvents) || maxAccountLifecycleEvents < 1) {
      throw new Error("maxAccountLifecycleEvents must be a positive safe integer");
    }
  }

  async listInstallations(principal: AccountPrincipal): Promise<ManagedInstallation[]> {
    const result = await this.pool.query<{
      id: string;
      kind: "phone" | "desktop";
      platform: "android" | "macos";
      display_name: string;
      last_seen_at: Date;
      revoked_at: Date | null;
    }>(
      `SELECT id, kind, platform, display_name, last_seen_at, revoked_at
         FROM installations
        WHERE account_id = $1 AND revoked_at IS NULL
        ORDER BY kind DESC, created_at ASC, id ASC`,
      [principal.account.id],
    );
    return result.rows.map((row) => ({
      id: row.id,
      kind: row.kind,
      platform: row.platform,
      displayName: row.display_name,
      lastSeenAt: row.last_seen_at.toISOString(),
      status: row.revoked_at ? "revoked" : "active",
      current: row.id === principal.installation.id,
    }));
  }

  async revokePhoneInstallation(
    principal: AccountPrincipal,
    targetInstallationId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeInstallationResult> {
    return this.transaction(async (client) => {
      await lockAccountControl(client, principal.account.id);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "installation.revoke",
        idempotency.key,
      );
      if (replay) {
        return replayMatches(replay, principal.sessionId, idempotency)
          ? { status: "replayed" }
          : { status: "idempotency_conflict" };
      }

      if (!await lockAuthorizedDesktop(client, principal)) return { status: "authorization_failed" };

      const target = await client.query<{
        id: string;
        kind: "phone" | "desktop";
        revoked_at: Date | null;
      }>(
        `SELECT id, kind, revoked_at
           FROM installations
          WHERE id = $1 AND account_id = $2
          FOR UPDATE`,
        [targetInstallationId, principal.account.id],
      );
      if ((target.rowCount ?? 0) === 0) return { status: "not_found" };
      if (target.rows[0].kind !== "phone") return { status: "invalid_target" };

      await client.query(
        `UPDATE refresh_tokens r
            SET revoked_at = COALESCE(r.revoked_at, now())
           FROM account_sessions s
          WHERE r.session_id = s.id AND s.installation_id = $1`,
        [targetInstallationId],
      );
      await client.query(
        `UPDATE account_sessions
            SET revoked_at = COALESCE(revoked_at, now())
          WHERE installation_id = $1`,
        [targetInstallationId],
      );
      await client.query(
        `UPDATE reauthentication_grants
            SET revoked_at = COALESCE(revoked_at, now())
          WHERE installation_id = $1 AND used_at IS NULL`,
        [targetInstallationId],
      );
      await client.query(
        "UPDATE installations SET revoked_at = COALESCE(revoked_at, now()) WHERE id = $1",
        [targetInstallationId],
      );
      await saveIdempotency(
        client,
        principal.account.id,
        principal.sessionId,
        null,
        null,
        "installation.revoke",
        idempotency,
      );
      await audit(client, principal.account.id, principal.installation.id, "installation.revoked", {
        targetInstallationId,
      });
      return { status: "completed" };
    });
  }

  async revokeCurrentPhoneInstallation(
    accessTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<CurrentInstallationRevocationResult> {
    return this.transaction(async (client) => {
      const access = await loadMutationAccess(client, accessTokenHash);
      if (!access) return { status: "invalid" };
      await lockAccountControl(client, access.account_id);
      const replay = await loadIdempotency(
        client,
        access.account_id,
        "installation.revoke.current",
        idempotency.key,
      );
      if (replay) {
        return replayMatches(replay, access.session_id, idempotency)
          ? { status: "replayed" }
          : { status: "idempotency_conflict" };
      }
      if (access.account_status !== "active") return { status: "account_disabled" };
      if (access.session_revoked_at || access.installation_revoked_at) return { status: "revoked" };
      if (access.access_expires_at.getTime() <= Date.now()) return { status: "expired" };
      if (access.installation_kind !== "phone" || access.installation_platform !== "android") {
        return { status: "invalid_target" };
      }

      await revokeInstallationAccess(client, access.installation_id);
      await saveIdempotency(
        client,
        access.account_id,
        access.session_id,
        null,
        null,
        "installation.revoke.current",
        idempotency,
      );
      await audit(client, access.account_id, access.installation_id, "installation.revoked.current", {});
      return { status: "completed" };
    });
  }

  async getBinding(principal: AccountPrincipal): Promise<BindingState> {
    if (principal.installation.kind === "desktop") {
      const replacement = await this.pool.query<ReplacementRow>(
        `${replacementSelect}
          WHERE account_id = $1
            AND requesting_installation_id = $2
            AND status = 'pending'
            AND expires_at > now()
          ORDER BY created_at DESC
          LIMIT 1`,
        [principal.account.id, principal.installation.id],
      );
      if ((replacement.rowCount ?? 0) > 0) {
        const request = await loadReplacementView(this.pool, replacement.rows[0]);
        if (request) return request;
      }
    }
    const active = await this.pool.query<BindingRow>(
      `${bindingSelect}
        WHERE account_id = $1 AND status = 'active'
        ORDER BY generation DESC
        LIMIT 1`,
      [principal.account.id],
    );
    if ((active.rowCount ?? 0) > 0) {
      if (principal.installation.kind === "desktop"
          && active.rows[0].desktop_installation_id !== principal.installation.id) {
        const replaced = await this.pool.query<{ generation: number }>(
          `SELECT generation
             FROM connector_bindings
            WHERE account_id = $1
              AND desktop_installation_id = $2
              AND status IN ('replaced', 'revoked')
            ORDER BY generation DESC
            LIMIT 1`,
          [principal.account.id, principal.installation.id],
        );
        if ((replaced.rowCount ?? 0) > 0) {
          return { state: "revoked", generation: replaced.rows[0].generation };
        }
      }
      return { state: "bound", binding: activeBinding(active.rows[0]) };
    }
    if (principal.installation.kind !== "desktop") return { state: "no_binding" };
    const pending = await this.pool.query<BindingRow>(
      `${bindingSelect}
        WHERE account_id = $1
          AND desktop_installation_id = $2
          AND status = 'pending'
          AND pending_expires_at > now()
        ORDER BY generation DESC
        LIMIT 1`,
      [principal.account.id, principal.installation.id],
    );
    if ((pending.rowCount ?? 0) > 0) return bindingCandidate(pending.rows[0]);
    const revoked = await this.pool.query<{ generation: number }>(
      `SELECT generation
         FROM connector_bindings
        WHERE account_id = $1
          AND desktop_installation_id = $2
          AND status IN ('replaced', 'revoked')
        ORDER BY generation DESC
        LIMIT 1`,
      [principal.account.id, principal.installation.id],
    );
    return (revoked.rowCount ?? 0) > 0
      ? { state: "revoked", generation: revoked.rows[0].generation }
      : { state: "no_binding" };
  }

  async createPendingBinding(
    principal: AccountPrincipal,
    input: {
      bindingId: string;
      displayName: string;
      deviceId: string;
      publicKey: Buffer;
      publicKeyFingerprint: string;
      expiresAt: Date;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateBindingResult> {
    return this.transaction(async (client) => {
      await lockAccountControl(client, principal.account.id);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "connector.binding.create",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency) || !replay.connector_binding_id) {
          return { status: "idempotency_conflict" };
        }
        const saved = await loadBinding(client, replay.connector_binding_id);
        return saved ? { status: "replayed", binding: bindingCandidate(saved) }
          : { status: "idempotency_conflict" };
      }

      if (!await lockAuthorizedDesktop(client, principal)) return { status: "installation_invalid" };

      await client.query(
        `UPDATE connector_bindings
            SET status = 'revoked', revoked_at = now()
          WHERE account_id = $1 AND status = 'pending' AND pending_expires_at <= now()`,
        [principal.account.id],
      );
      const existing = await client.query(
        `SELECT 1 FROM connector_bindings
          WHERE account_id = $1 AND status IN ('pending', 'active')
          LIMIT 1`,
        [principal.account.id],
      );
      if ((existing.rowCount ?? 0) > 0) return { status: "conflict" };
      const nextGeneration = await client.query<{ generation: number }>(
        `SELECT COALESCE(MAX(generation), 0)::integer + 1 AS generation
           FROM connector_bindings
          WHERE account_id = $1`,
        [principal.account.id],
      );
      const generation = nextGeneration.rows[0].generation;
      const inserted = await client.query<BindingRow>(
        `${bindingInsert} RETURNING ${bindingColumns}`,
        [
          input.bindingId,
          principal.account.id,
          principal.installation.id,
          input.displayName,
          input.deviceId,
          input.publicKey,
          input.publicKeyFingerprint,
          generation,
          input.expiresAt,
        ],
      );
      await saveIdempotency(
        client,
        principal.account.id,
        principal.sessionId,
        input.bindingId,
        null,
        "connector.binding.create",
        idempotency,
      );
      await audit(client, principal.account.id, principal.installation.id, "connector.binding.pending", {
        bindingId: input.bindingId,
        generation: String(generation),
      });
      return { status: "created", binding: bindingCandidate(inserted.rows[0]) };
    });
  }

  async confirmPendingBinding(
    principal: AccountPrincipal,
    bindingId: string,
    generation: number,
    idempotency: IdempotencyMaterial,
  ): Promise<ConfirmBindingResult> {
    return this.transaction(async (client) => {
      await lockAccountControl(client, principal.account.id);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "connector.binding.confirm",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency) || !replay.connector_binding_id) {
          return { status: "idempotency_conflict" };
        }
        const saved = await loadBinding(client, replay.connector_binding_id);
        return saved?.status === "active"
          ? { status: "replayed", binding: activeBinding(saved) }
          : { status: "idempotency_conflict" };
      }
      if (!await lockAuthorizedDesktop(client, principal)) return { status: "conflict" };

      const found = await client.query<BindingRow>(
        `${bindingSelect}
          WHERE id = $1
            AND account_id = $2
            AND desktop_installation_id = $3
            AND generation = $4
          FOR UPDATE`,
        [bindingId, principal.account.id, principal.installation.id, generation],
      );
      if ((found.rowCount ?? 0) === 0) return { status: "not_found" };
      const row = found.rows[0];
      if (row.status !== "pending") return { status: "conflict" };
      if (!row.pending_expires_at || row.pending_expires_at.getTime() <= Date.now()) {
        await client.query(
          "UPDATE connector_bindings SET status = 'revoked', revoked_at = now() WHERE id = $1",
          [bindingId],
        );
        return { status: "expired" };
      }
      if (!row.key_proved_at
          || !row.health_checked_at
          || row.hermes_reachable !== true
          || row.end_to_end_healthy !== true) {
        return { status: "proof_required" };
      }
      const active = await client.query(
        "SELECT 1 FROM connector_bindings WHERE account_id = $1 AND status = 'active' LIMIT 1",
        [principal.account.id],
      );
      if ((active.rowCount ?? 0) > 0) return { status: "conflict" };

      const activated = await client.query<BindingRow>(
        `UPDATE connector_bindings
            SET status = 'active', activated_at = now(), connector_online = true, last_seen_at = now()
          WHERE id = $1
          RETURNING ${bindingColumns}`,
        [bindingId],
      );
      await saveIdempotency(
        client,
        principal.account.id,
        principal.sessionId,
        bindingId,
        null,
        "connector.binding.confirm",
        idempotency,
      );
      await audit(client, principal.account.id, principal.installation.id, "connector.binding.activated", {
        bindingId,
        generation: String(generation),
      });
      return { status: "activated", binding: activeBinding(activated.rows[0]) };
    });
  }

  async createReplacementRequest(
    principal: AccountPrincipal,
    input: {
      requestId: string;
      bindingId: string;
      displayName: string;
      deviceId: string;
      publicKey: Buffer;
      publicKeyFingerprint: string;
      expiresAt: Date;
      grantTokenHash: string;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateReplacementResult> {
    return this.transaction(async (client) => {
      await lockAccountControl(client, principal.account.id);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "connector.binding.replace.create",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency)
            || !replay.connector_replacement_request_id) {
          return { status: "idempotency_conflict" };
        }
        const request = await loadReplacement(client, replay.connector_replacement_request_id);
        const view = request ? await loadReplacementView(client, request) : undefined;
        return view
          ? { status: "replayed", request: view }
          : { status: "idempotency_conflict" };
      }
      if (!await lockAuthorizedDesktop(client, principal)) {
        return { status: "installation_invalid" };
      }

      await expireReplacementRequests(client, principal.account.id);
      const existingRequest = await client.query(
        `SELECT 1
           FROM connector_replacement_requests
          WHERE account_id = $1 AND status = 'pending'
          LIMIT 1`,
        [principal.account.id],
      );
      if ((existingRequest.rowCount ?? 0) > 0) return { status: "conflict" };

      const active = await client.query<BindingRow>(
        `${bindingSelect}
          WHERE account_id = $1 AND status = 'active'
          LIMIT 1
          FOR UPDATE`,
        [principal.account.id],
      );
      if ((active.rowCount ?? 0) === 0) return { status: "not_found" };
      const grant = await loadGrant(
        client,
        principal,
        input.grantTokenHash,
        "connector.replace",
      );
      if (!grant) return { status: "reauthentication_failed" };

      const nextGeneration = await client.query<{ generation: number }>(
        `SELECT COALESCE(MAX(generation), 0)::integer + 1 AS generation
           FROM connector_bindings
          WHERE account_id = $1`,
        [principal.account.id],
      );
      const generation = nextGeneration.rows[0].generation;
      const candidate = await client.query<BindingRow>(
        `${bindingInsert} RETURNING ${bindingColumns}`,
        [
          input.bindingId,
          principal.account.id,
          principal.installation.id,
          input.displayName,
          input.deviceId,
          input.publicKey,
          input.publicKeyFingerprint,
          generation,
          input.expiresAt,
        ],
      );
      const insertedRequest = await client.query<ReplacementRow>(
        `INSERT INTO connector_replacement_requests
           (id, account_id, requesting_installation_id, previous_binding_id,
            candidate_binding_id, reauthentication_grant_id, status, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, 'pending', $7)
         RETURNING ${replacementColumns}`,
        [
          input.requestId,
          principal.account.id,
          principal.installation.id,
          active.rows[0].id,
          input.bindingId,
          grant.id,
          input.expiresAt,
        ],
      );
      await client.query("UPDATE reauthentication_grants SET used_at = now() WHERE id = $1", [grant.id]);
      await saveIdempotency(
        client,
        principal.account.id,
        principal.sessionId,
        input.bindingId,
        input.requestId,
        "connector.binding.replace.create",
        idempotency,
      );
      await audit(client, principal.account.id, principal.installation.id, "connector.replacement.pending", {
        requestId: input.requestId,
        previousBindingId: active.rows[0].id,
        candidateBindingId: input.bindingId,
        generation: String(generation),
      });
      return {
        status: "created",
        request: replacementView(insertedRequest.rows[0], active.rows[0], candidate.rows[0]),
      };
    });
  }

  async confirmReplacementRequest(
    principal: AccountPrincipal,
    requestId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ConfirmReplacementResult> {
    return this.transaction(async (client) => {
      await lockAccountControl(client, principal.account.id);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "connector.binding.replace.confirm",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency)
            || replay.connector_replacement_request_id !== requestId
            || !replay.connector_binding_id) {
          return { status: "idempotency_conflict" };
        }
        const saved = await loadBinding(client, replay.connector_binding_id);
        return saved?.status === "active"
          ? { status: "replayed", binding: activeBinding(saved) }
          : { status: "idempotency_conflict" };
      }
      if (!await lockAuthorizedDesktop(client, principal)) {
        return { status: "installation_invalid" };
      }

      const found = await client.query<ReplacementRow>(
        `${replacementSelect}
          WHERE id = $1
            AND account_id = $2
            AND requesting_installation_id = $3
          FOR UPDATE`,
        [requestId, principal.account.id, principal.installation.id],
      );
      if ((found.rowCount ?? 0) === 0) return { status: "not_found" };
      const request = found.rows[0];
      if (request.status !== "pending") return { status: "expired" };
      if (request.expires_at.getTime() <= Date.now()) {
        await expireReplacementRequest(client, request);
        return { status: "expired" };
      }

      const bindings = await client.query<BindingRow>(
        `${bindingSelect}
          WHERE account_id = $1 AND id IN ($2, $3)
          ORDER BY generation ASC
          FOR UPDATE`,
        [principal.account.id, request.previous_binding_id, request.candidate_binding_id],
      );
      const previous = bindings.rows.find(({ id }) => id === request.previous_binding_id);
      const candidate = bindings.rows.find(({ id }) => id === request.candidate_binding_id);
      if (!previous || !candidate) return { status: "not_found" };
      if (previous.status !== "active" || candidate.status !== "pending") return { status: "conflict" };
      if (!candidate.pending_expires_at
          || candidate.pending_expires_at.getTime() <= Date.now()) {
        await expireReplacementRequest(client, request);
        return { status: "expired" };
      }
      if (!candidate.key_proved_at
          || !candidate.health_checked_at
          || candidate.hermes_reachable !== true
          || candidate.end_to_end_healthy !== true) {
        return { status: "proof_required" };
      }

      await client.query(
        `UPDATE connector_bindings
            SET status = 'replaced', replaced_at = now(), connector_online = false
          WHERE id = $1 AND status = 'active'`,
        [previous.id],
      );
      const activated = await client.query<BindingRow>(
        `UPDATE connector_bindings
            SET status = 'active', activated_at = now(), connector_online = true, last_seen_at = now()
          WHERE id = $1 AND status = 'pending'
          RETURNING ${bindingColumns}`,
        [candidate.id],
      );
      if ((activated.rowCount ?? 0) !== 1) throw new Error("replacement candidate activation failed");
      await client.query(
        `UPDATE connector_replacement_requests
            SET status = 'consumed', consumed_at = now()
          WHERE id = $1`,
        [request.id],
      );
      await saveIdempotency(
        client,
        principal.account.id,
        principal.sessionId,
        candidate.id,
        request.id,
        "connector.binding.replace.confirm",
        idempotency,
      );
      await audit(client, principal.account.id, principal.installation.id, "connector.replacement.activated", {
        requestId: request.id,
        previousBindingId: previous.id,
        candidateBindingId: candidate.id,
        generation: String(candidate.generation),
      });
      return { status: "activated", binding: activeBinding(activated.rows[0]) };
    });
  }

  async unbindConnector(
    principal: AccountPrincipal,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<UnbindResult> {
    return this.transaction(async (client) => {
      await lockAccountControl(client, principal.account.id);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "connector.binding.unbind",
        idempotency.key,
      );
      if (replay) {
        return replayMatches(replay, principal.sessionId, idempotency)
          ? { status: "replayed" }
          : { status: "idempotency_conflict" };
      }
      if (!await lockAuthorizedDesktop(client, principal)) {
        return { status: "installation_invalid" };
      }
      const active = await client.query<BindingRow>(
        `${bindingSelect}
          WHERE account_id = $1
            AND desktop_installation_id = $2
            AND status = 'active'
          LIMIT 1
          FOR UPDATE`,
        [principal.account.id, principal.installation.id],
      );
      if ((active.rowCount ?? 0) === 0) return { status: "not_found" };
      const grant = await loadGrant(client, principal, grantTokenHash, "connector.unbind");
      if (!grant) return { status: "reauthentication_failed" };

      const pendingRequests = await client.query<ReplacementRow>(
        `${replacementSelect}
          WHERE account_id = $1 AND status = 'pending'
          FOR UPDATE`,
        [principal.account.id],
      );
      for (const request of pendingRequests.rows) {
        await client.query(
          `UPDATE connector_replacement_requests
              SET status = 'cancelled', cancelled_at = now()
            WHERE id = $1`,
          [request.id],
        );
        await client.query(
          `UPDATE connector_bindings
              SET status = 'revoked', revoked_at = now(), connector_online = false
            WHERE id = $1 AND status = 'pending'`,
          [request.candidate_binding_id],
        );
      }
      await client.query(
        `UPDATE connector_bindings
            SET status = 'revoked', revoked_at = now(), connector_online = false
          WHERE id = $1`,
        [active.rows[0].id],
      );
      await client.query("UPDATE reauthentication_grants SET used_at = now() WHERE id = $1", [grant.id]);
      await saveIdempotency(
        client,
        principal.account.id,
        principal.sessionId,
        active.rows[0].id,
        null,
        "connector.binding.unbind",
        idempotency,
      );
      await audit(client, principal.account.id, principal.installation.id, "connector.binding.revoked", {
        bindingId: active.rows[0].id,
        generation: String(active.rows[0].generation),
      });
      return { status: "completed" };
    });
  }

  async loadBindingProofMaterial(
    bindingId: string,
    generation: number,
    fingerprint: string,
  ): Promise<BindingProofMaterial | undefined> {
    const found = await this.pool.query<BindingRow>(
      `${bindingSelect}
        WHERE id = $1
          AND generation = $2
          AND public_key_fingerprint = $3
          AND status IN ('pending', 'active')
          AND (status = 'active' OR pending_expires_at > now())`,
      [bindingId, generation, fingerprint],
    );
    const row = found.rows[0];
    if (!row) return undefined;
    return {
      id: row.id,
      accountId: row.account_id,
      deviceId: row.device_id,
      generation: row.generation,
      publicKey: row.public_key,
      publicKeyFingerprint: row.public_key_fingerprint,
      status: row.status as "pending" | "active",
      ...(row.pending_expires_at ? { expiresAt: row.pending_expires_at } : {}),
    };
  }

  async recordBindingKeyProof(bindingId: string, generation: number, fingerprint: string): Promise<boolean> {
    const result = await this.pool.query(
      `UPDATE connector_bindings
          SET key_proved_at = COALESCE(key_proved_at, now()),
              connector_online = true,
              last_seen_at = now()
        WHERE id = $1
          AND generation = $2
          AND public_key_fingerprint = $3
          AND status IN ('pending', 'active')
          AND (status = 'active' OR pending_expires_at > now())`,
      [bindingId, generation, fingerprint],
    );
    return (result.rowCount ?? 0) === 1;
  }

  async recordBindingHealth(
    bindingId: string,
    generation: number,
    health: {
      hermesReachable: boolean;
      hermesVersion?: string;
      gatewayLatencyMs: number;
      endToEndHealthy: boolean;
    },
  ): Promise<boolean> {
    if (!Number.isSafeInteger(health.gatewayLatencyMs)
        || health.gatewayLatencyMs < 0
        || health.gatewayLatencyMs > 60_000
        || (health.hermesVersion !== undefined
          && (health.hermesVersion.length < 1
            || health.hermesVersion.length > 64
            || /[\u0000-\u001f\u007f]/.test(health.hermesVersion)))) {
      return false;
    }
    const result = await this.pool.query(
      `UPDATE connector_bindings
          SET health_checked_at = now(),
              connector_online = true,
              hermes_reachable = $3,
              hermes_version = $4,
              gateway_latency_ms = $5,
              end_to_end_healthy = $6,
              last_seen_at = now()
        WHERE id = $1
          AND generation = $2
          AND status IN ('pending', 'active')
          AND (status = 'active' OR pending_expires_at > now())
          AND key_proved_at IS NOT NULL`,
      [
        bindingId,
        generation,
        health.hermesReachable,
        health.hermesVersion ?? null,
        health.gatewayLatencyMs,
        health.endToEndHealthy,
      ],
    );
    return (result.rowCount ?? 0) === 1;
  }

  async recordBindingDisconnected(
    bindingId: string,
    generation: number,
    fingerprint: string,
  ): Promise<boolean> {
    const result = await this.pool.query(
      `UPDATE connector_bindings
          SET connector_online = false
        WHERE id = $1
          AND generation = $2
          AND public_key_fingerprint = $3
          AND status IN ('pending', 'active')`,
      [bindingId, generation, fingerprint],
    );
    return (result.rowCount ?? 0) === 1;
  }

  async ingestAccountLifecycleEvent(
    material: BindingProofMaterial,
    event: SessionLifecycleEvent,
  ): Promise<{ status: "stored" | "duplicate" | "binding_invalid" | "event_id_conflict" }> {
    return this.transaction(async (client) => {
      const binding = await client.query<{ account_id: string; device_id: string }>(
        `SELECT account_id, device_id
           FROM connector_bindings
          WHERE id = $1
            AND account_id = $2
            AND generation = $3
            AND public_key_fingerprint = $4
            AND status = 'active'
          FOR SHARE`,
        [material.id, material.accountId, material.generation, material.publicKeyFingerprint],
      );
      if ((binding.rowCount ?? 0) !== 1 || binding.rows[0].device_id !== event.deviceId) {
        return { status: "binding_invalid" };
      }

      const inserted = await client.query<{ sequence: string }>(
        `INSERT INTO account_lifecycle_events
           (account_id, connector_binding_id, event_id, device_id, profile,
            runtime_session_id, stored_session_id, event_kind, lifecycle_state, occurred_at, title)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
         ON CONFLICT (account_id, event_id) DO NOTHING
         RETURNING sequence`,
        [
          material.accountId,
          material.id,
          event.eventId,
          event.deviceId,
          event.profile ?? null,
          event.runtimeSessionId,
          event.storedSessionId,
          event.event,
          event.state,
          event.occurredAt,
          event.title ?? null,
        ],
      );
      if ((inserted.rowCount ?? 0) === 0) {
        const existing = await client.query<AccountLifecycleRow>(
          `${accountLifecycleStoredSelect}
            WHERE e.account_id = $1 AND e.event_id = $2`,
          [material.accountId, event.eventId],
        );
        return existing.rows[0] && accountLifecycleMatches(existing.rows[0], event)
          ? { status: "duplicate" }
          : { status: "event_id_conflict" };
      }

      await client.query(
        `INSERT INTO account_lifecycle_receipts (event_sequence, account_id, installation_id)
         SELECT $1, $2, i.id
           FROM installations i
          WHERE i.account_id = $2
            AND i.kind = 'phone'
            AND i.platform = 'android'
            AND i.revoked_at IS NULL`,
        [inserted.rows[0].sequence, material.accountId],
      );
      await client.query(
        `DELETE FROM account_lifecycle_events
          WHERE account_id = $1
            AND sequence IN (
              SELECT sequence
                FROM account_lifecycle_events
               WHERE account_id = $1
               ORDER BY sequence DESC
               OFFSET $2
            )`,
        [material.accountId, this.maxAccountLifecycleEvents],
      );
      return { status: "stored" };
    });
  }

  async listAccountLifecycleEvents(
    principal: AccountPrincipal,
    after: number,
    limit: number,
  ): Promise<LifecycleEventPage> {
    const result = await this.pool.query<AccountLifecycleRow>(
      `${accountLifecycleSelect}
        JOIN account_lifecycle_receipts r
          ON r.event_sequence = e.sequence
         AND r.account_id = e.account_id
       WHERE e.account_id = $1
         AND r.installation_id = $2
         AND e.sequence > $3
       ORDER BY e.sequence ASC
       LIMIT $4`,
      [principal.account.id, principal.installation.id, after, limit + 1],
    );
    const hasMore = result.rows.length > limit;
    const events = result.rows.slice(0, limit).map(accountLifecycleView);
    return {
      events,
      nextCursor: events.at(-1)?.sequence ?? after,
      hasMore,
    };
  }

  async markAccountLifecycleEvents(
    principal: AccountPrincipal,
    eventIds: string[],
    field: "delivered" | "read",
  ): Promise<number> {
    const uniqueIds = [...new Set(eventIds)];
    if (uniqueIds.length === 0) return 0;
    const column = field === "delivered" ? "delivered_at" : "read_at";
    const result = await this.pool.query(
      `UPDATE account_lifecycle_receipts r
          SET ${column} = now()
         FROM account_lifecycle_events e
        WHERE r.event_sequence = e.sequence
          AND r.account_id = $1
          AND r.installation_id = $2
          AND e.account_id = $1
          AND e.event_id = ANY($3::text[])
          AND r.${column} IS NULL`,
      [principal.account.id, principal.installation.id, uniqueIds],
    );
    return result.rowCount ?? 0;
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

const bindingColumns = `id, account_id, desktop_installation_id, display_name, device_id, public_key,
  public_key_fingerprint, generation, status, pending_expires_at, key_proved_at, health_checked_at,
  connector_online, hermes_reachable, hermes_version, gateway_latency_ms, end_to_end_healthy,
  last_seen_at`;
const bindingSelect = `SELECT ${bindingColumns} FROM connector_bindings`;
const bindingInsert = `INSERT INTO connector_bindings
  (id, account_id, desktop_installation_id, display_name, device_id, public_key, key_algorithm,
   public_key_fingerprint, generation, status, pending_expires_at)
  VALUES ($1, $2, $3, $4, $5, $6, 'Ed25519', $7, $8, 'pending', $9)`;
const replacementColumns = `id, account_id, requesting_installation_id, previous_binding_id,
  candidate_binding_id, reauthentication_grant_id, status, expires_at`;
const replacementSelect = `SELECT ${replacementColumns} FROM connector_replacement_requests`;
const accountLifecycleSelect = `SELECT e.sequence, e.event_id, e.device_id, e.profile,
  e.runtime_session_id, e.stored_session_id, e.event_kind, e.lifecycle_state, e.occurred_at,
  e.title, e.received_at, r.delivered_at, r.read_at
  FROM account_lifecycle_events e`;
const accountLifecycleStoredSelect = `SELECT e.sequence, e.event_id, e.device_id, e.profile,
  e.runtime_session_id, e.stored_session_id, e.event_kind, e.lifecycle_state, e.occurred_at,
  e.title, e.received_at, NULL::timestamptz AS delivered_at, NULL::timestamptz AS read_at
  FROM account_lifecycle_events e`;

function accountLifecycleView(row: AccountLifecycleRow): LifecycleEventPage["events"][number] {
  return {
    sequence: safeSequence(row.sequence),
    event: {
      type: "session.lifecycle",
      version: PROTOCOL_VERSION,
      eventId: row.event_id,
      deviceId: row.device_id,
      ...(row.profile === null ? {} : { profile: row.profile }),
      runtimeSessionId: row.runtime_session_id,
      storedSessionId: row.stored_session_id,
      event: row.event_kind,
      state: row.lifecycle_state,
      occurredAt: row.occurred_at.toISOString(),
      ...(row.title === null ? {} : { title: row.title }),
    },
    receivedAt: row.received_at.toISOString(),
    ...(row.delivered_at ? { deliveredAt: row.delivered_at.toISOString() } : {}),
    ...(row.read_at ? { readAt: row.read_at.toISOString() } : {}),
  };
}

function accountLifecycleMatches(row: AccountLifecycleRow, event: SessionLifecycleEvent): boolean {
  return row.device_id === event.deviceId
    && row.profile === (event.profile ?? null)
    && row.runtime_session_id === event.runtimeSessionId
    && row.stored_session_id === event.storedSessionId
    && row.event_kind === event.event
    && row.lifecycle_state === event.state
    && row.occurred_at.getTime() === Date.parse(event.occurredAt)
    && row.title === (event.title ?? null);
}

function safeSequence(value: string): number {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error("invalid lifecycle sequence");
  return parsed;
}

function bindingCandidate(row: BindingRow): BindingCandidate {
  if (!row.pending_expires_at) throw new Error("pending binding has no expiry");
  return {
    id: row.id,
    generation: row.generation,
    deviceId: row.device_id,
    displayName: row.display_name,
    publicKeyFingerprint: row.public_key_fingerprint,
    state: "binding_pending",
    expiresAt: row.pending_expires_at.toISOString(),
    keyProved: Boolean(row.key_proved_at),
    healthVerified: Boolean(row.health_checked_at)
      && row.hermes_reachable === true
      && row.end_to_end_healthy === true,
  };
}

function activeBinding(row: BindingRow): ActiveBinding {
  return {
    id: row.id,
    generation: row.generation,
    deviceId: row.device_id,
    desktopDisplayName: row.display_name,
    publicKeyFingerprint: row.public_key_fingerprint,
    connector: {
      online: row.connector_online,
      ...(row.last_seen_at ? { lastSeenAt: row.last_seen_at.toISOString() } : {}),
    },
    hermes: {
      reachable: row.hermes_reachable,
      ...(row.hermes_version ? { version: row.hermes_version } : {}),
    },
    gateway: {
      ...(row.gateway_latency_ms === null ? {} : { latencyMs: row.gateway_latency_ms }),
    },
    endToEnd: {
      healthy: row.end_to_end_healthy,
      ...(row.health_checked_at ? { checkedAt: row.health_checked_at.toISOString() } : {}),
    },
  };
}

function replacementView(
  row: ReplacementRow,
  previous: BindingRow,
  candidate: BindingRow,
): ReplacementRequest {
  return {
    id: row.id,
    state: "replacement_pending",
    expiresAt: row.expires_at.toISOString(),
    previousBinding: activeBinding(previous),
    candidate: bindingCandidate(candidate),
  };
}

async function loadBinding(client: PoolClient, bindingId: string): Promise<BindingRow | undefined> {
  const result = await client.query<BindingRow>(
    `${bindingSelect} WHERE id = $1`,
    [bindingId],
  );
  return result.rows[0];
}

async function loadReplacement(
  client: PoolClient,
  requestId: string,
): Promise<ReplacementRow | undefined> {
  const result = await client.query<ReplacementRow>(
    `${replacementSelect} WHERE id = $1`,
    [requestId],
  );
  return result.rows[0];
}

async function loadReplacementView(
  client: Pool | PoolClient,
  row: ReplacementRow,
): Promise<ReplacementRequest | undefined> {
  const bindings = await client.query<BindingRow>(
    `${bindingSelect} WHERE account_id = $1 AND id IN ($2, $3)`,
    [row.account_id, row.previous_binding_id, row.candidate_binding_id],
  );
  const previous = bindings.rows.find(({ id }) => id === row.previous_binding_id);
  const candidate = bindings.rows.find(({ id }) => id === row.candidate_binding_id);
  if (!previous || !candidate || candidate.status !== "pending") return undefined;
  return replacementView(row, previous, candidate);
}

async function expireReplacementRequests(client: PoolClient, accountId: string): Promise<void> {
  const expired = await client.query<ReplacementRow>(
    `${replacementSelect}
      WHERE account_id = $1 AND status = 'pending' AND expires_at <= now()
      FOR UPDATE`,
    [accountId],
  );
  for (const request of expired.rows) await expireReplacementRequest(client, request);
}

async function expireReplacementRequest(client: PoolClient, request: ReplacementRow): Promise<void> {
  await client.query(
    `UPDATE connector_replacement_requests
        SET status = 'expired'
      WHERE id = $1 AND status = 'pending'`,
    [request.id],
  );
  await client.query(
    `UPDATE connector_bindings
        SET status = 'revoked', revoked_at = now(), connector_online = false
      WHERE id = $1 AND status = 'pending'`,
    [request.candidate_binding_id],
  );
}

async function loadGrant(
  client: PoolClient,
  principal: AccountPrincipal,
  tokenHash: string,
  scope: "connector.replace" | "connector.unbind",
): Promise<GrantRow | undefined> {
  const found = await client.query<GrantRow>(
    `SELECT id, scope, expires_at, used_at, revoked_at
       FROM reauthentication_grants
      WHERE token_hash = $1
        AND account_id = $2
        AND installation_id = $3
        AND session_id = $4
      FOR UPDATE`,
    [tokenHash, principal.account.id, principal.installation.id, principal.sessionId],
  );
  const grant = found.rows[0];
  if (!grant || grant.scope !== scope || grant.used_at || grant.revoked_at) return undefined;
  if (grant.expires_at.getTime() <= Date.now()) {
    await client.query("UPDATE reauthentication_grants SET revoked_at = now() WHERE id = $1", [grant.id]);
    return undefined;
  }
  return grant;
}

async function loadMutationAccess(
  client: PoolClient,
  accessTokenHash: string,
): Promise<MutationAccessRow | undefined> {
  const found = await client.query<MutationAccessRow>(
    `SELECT s.id AS session_id,
            s.access_expires_at,
            s.revoked_at AS session_revoked_at,
            a.id AS account_id,
            a.status AS account_status,
            i.id AS installation_id,
            i.kind AS installation_kind,
            i.platform AS installation_platform,
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

async function revokeInstallationAccess(client: PoolClient, installationId: string): Promise<void> {
  await client.query(
    `UPDATE refresh_tokens r
        SET revoked_at = COALESCE(r.revoked_at, now())
       FROM account_sessions s
      WHERE r.session_id = s.id AND s.installation_id = $1`,
    [installationId],
  );
  await client.query(
    `UPDATE account_sessions
        SET revoked_at = COALESCE(revoked_at, now())
      WHERE installation_id = $1`,
    [installationId],
  );
  await client.query(
    `UPDATE reauthentication_grants
        SET revoked_at = COALESCE(revoked_at, now())
      WHERE installation_id = $1 AND used_at IS NULL`,
    [installationId],
  );
  await client.query(
    "UPDATE installations SET revoked_at = COALESCE(revoked_at, now()) WHERE id = $1",
    [installationId],
  );
}

async function lockAccountControl(client: PoolClient, accountId: string): Promise<void> {
  await client.query(
    "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))",
    [JSON.stringify(["account-control", accountId])],
  );
}

async function lockAuthorizedDesktop(
  client: PoolClient,
  principal: AccountPrincipal,
): Promise<boolean> {
  const authorized = await client.query(
    `SELECT 1
       FROM accounts a
       JOIN installations i ON i.account_id = a.id
       JOIN account_sessions s ON s.installation_id = i.id AND s.account_id = a.id
      WHERE a.id = $1
        AND a.status = 'active'
        AND i.id = $2
        AND i.kind = 'desktop'
        AND i.platform = 'macos'
        AND i.revoked_at IS NULL
        AND s.id = $3
        AND s.revoked_at IS NULL
      FOR UPDATE OF a, i, s`,
    [principal.account.id, principal.installation.id, principal.sessionId],
  );
  return (authorized.rowCount ?? 0) === 1;
}

async function loadIdempotency(
  client: PoolClient,
  accountId: string,
  operation: string,
  key: string,
): Promise<IdempotencyRow | undefined> {
  const result = await client.query<IdempotencyRow>(
    `SELECT session_id, connector_binding_id, connector_replacement_request_id,
            request_hash, expires_at
       FROM account_idempotency_records
      WHERE account_id = $1 AND operation = $2 AND idempotency_key = $3`,
    [accountId, operation, key],
  );
  return result.rows[0];
}

function replayMatches(
  saved: IdempotencyRow,
  sessionId: string,
  idempotency: IdempotencyMaterial,
): boolean {
  return saved.session_id === sessionId
    && saved.request_hash === idempotency.requestHash
    && saved.expires_at.getTime() > Date.now();
}

async function saveIdempotency(
  client: PoolClient,
  accountId: string,
  sessionId: string,
  bindingId: string | null,
  replacementRequestId: string | null,
  operation: string,
  idempotency: IdempotencyMaterial,
): Promise<void> {
  await client.query(
    `INSERT INTO account_idempotency_records
       (id, account_id, session_id, connector_binding_id, connector_replacement_request_id,
        operation, idempotency_key, request_hash, response_ciphertext, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
    [
      randomUUID(), accountId, sessionId, bindingId, replacementRequestId, operation, idempotency.key,
      idempotency.requestHash, idempotency.responseCiphertext, idempotency.expiresAt,
    ],
  );
}

async function audit(
  client: PoolClient,
  accountId: string,
  installationId: string,
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
