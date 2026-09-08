import { randomUUID } from "node:crypto";
import type { Pool, PoolClient, QueryResultRow } from "pg";
import type { AccountDevice, ActiveBinding } from "./account-control-model.js";
import {
  MAX_GRANTEES_PER_DEVICE,
  MAX_SHARED_DEVICES_PER_ACCOUNT,
  type AcceptShareInvitationResult,
  type AccountSharingRepository,
  type CreateShareInvitationResult,
  type DeviceAccessGrant,
  type DeviceShareInvitation,
  type DeviceShareManagement,
  type ShareMutationResult,
} from "./account-sharing-model.js";
import type { EmailProviderSubmission } from "./email-delivery.js";
import type { AccountPrincipal, IdempotencyMaterial } from "./model.js";
import { publishAccountAccessRevocation } from "./postgres-access-revocation-bus.js";

interface BindingRow extends QueryResultRow {
  id: string;
  account_id: string;
  display_name: string;
  device_id: string;
  public_key_fingerprint: string;
  generation: number;
  status: "pending" | "active" | "replaced" | "revoked";
  connector_online: boolean;
  hermes_reachable: boolean | null;
  hermes_version: string | null;
  gateway_latency_ms: number | null;
  end_to_end_healthy: boolean | null;
  health_checked_at: Date | null;
  last_seen_at: Date | null;
}

interface InvitationRow extends QueryResultRow {
  id: string;
  binding_id: string;
  owner_account_id: string;
  target_email_lookup_hash: string;
  target_email_hint: string;
  token_hash: string;
  status: "pending" | "accepted" | "cancelled" | "expired" | "delivery_failed";
  delivery_status: "pending" | "sent" | "failed";
  expires_at: Date;
  created_at: Date;
  device_id?: string;
  display_name?: string;
}

interface GrantRow extends QueryResultRow {
  id: string;
  binding_id: string;
  owner_account_id: string;
  grantee_account_id: string;
  grantee_email_hint: string;
  role: "operator";
  status: "active" | "revoked" | "left";
  authorization_generation: number;
  granted_at: Date;
  device_id?: string;
}

interface IdempotencyRow extends QueryResultRow {
  session_id: string | null;
  connector_binding_id: string | null;
  device_share_invitation_id: string | null;
  device_access_grant_id: string | null;
  request_hash: string;
  expires_at: Date;
}

export class PostgresAccountSharingRepository implements AccountSharingRepository {
  constructor(
    private readonly pool: Pool,
    private readonly maxGranteesPerDevice = MAX_GRANTEES_PER_DEVICE,
    private readonly maxSharedDevicesPerAccount = MAX_SHARED_DEVICES_PER_ACCOUNT,
  ) {
    if (!Number.isSafeInteger(maxGranteesPerDevice) || maxGranteesPerDevice < 1 || maxGranteesPerDevice > 5) {
      throw new Error("maxGranteesPerDevice must be an integer between 1 and 5");
    }
    if (!Number.isSafeInteger(maxSharedDevicesPerAccount)
        || maxSharedDevicesPerAccount < 1 || maxSharedDevicesPerAccount > 10) {
      throw new Error("maxSharedDevicesPerAccount must be an integer between 1 and 10");
    }
  }

  async listSharedDevices(principal: AccountPrincipal): Promise<AccountDevice[]> {
    const result = await this.pool.query<BindingRow & { is_default: boolean }>(
      `SELECT ${bindingColumns},
              (b.id = (SELECT p.default_binding_id FROM account_device_preferences p
                        WHERE p.account_id = $1)) AS is_default
         FROM device_access_grants g
         JOIN connector_bindings b ON b.id = g.binding_id
        WHERE g.grantee_account_id = $1
          AND g.status = 'active'
          AND b.status = 'active'
        ORDER BY is_default DESC, g.granted_at ASC, b.id ASC`,
      [principal.account.id],
    );
    return result.rows.map((row) => accountDevice(row, "operator", row.is_default));
  }

  async getSharedDevice(
    principal: AccountPrincipal,
    deviceId: string,
  ): Promise<AccountDevice | undefined> {
    const result = await this.pool.query<BindingRow & { is_default: boolean }>(
      `SELECT ${bindingColumns},
              (b.id = (SELECT p.default_binding_id FROM account_device_preferences p
                        WHERE p.account_id = $1)) AS is_default
         FROM device_access_grants g
         JOIN connector_bindings b ON b.id = g.binding_id
        WHERE g.grantee_account_id = $1
          AND g.status = 'active'
          AND b.status = 'active'
          AND b.device_id = $2`,
      [principal.account.id, deviceId],
    );
    const row = result.rows[0];
    return row ? accountDevice(row, "operator", row.is_default) : undefined;
  }

  async selectAccessibleDefaultDevice(
    principal: AccountPrincipal,
    deviceId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<{ status: "completed" | "replayed"; device: AccountDevice }
    | { status: "not_found" | "idempotency_conflict" }> {
    return this.transaction(async (client) => {
      await lockKeys(client, [`account:${principal.account.id}`]);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "device.default.select",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency) || !replay.connector_binding_id) {
          return { status: "idempotency_conflict" };
        }
        const access = await loadAccessibleBinding(client, principal.account.id, replay.connector_binding_id);
        return access
          ? { status: "replayed", device: accountDevice(access.row, access.access, true) }
          : { status: "not_found" };
      }
      const access = await findAccessibleBindingByDevice(client, principal.account.id, deviceId, true);
      if (!access) return { status: "not_found" };
      await client.query(
        `INSERT INTO account_device_preferences (account_id, default_binding_id)
         VALUES ($1, $2)
         ON CONFLICT (account_id) DO UPDATE
           SET default_binding_id = EXCLUDED.default_binding_id, updated_at = now()`,
        [principal.account.id, access.row.id],
      );
      await saveIdempotency(client, principal, "device.default.select", idempotency, {
        bindingId: access.row.id,
      });
      await audit(client, principal, "device.default.selected", {
        bindingId: access.row.id,
        deviceId: access.row.device_id,
        access: access.access,
      });
      return { status: "completed", device: accountDevice(access.row, access.access, true) };
    });
  }

  async listShares(
    principal: AccountPrincipal,
    deviceId: string,
  ): Promise<DeviceShareManagement | undefined> {
    return this.transaction(async (client) => {
      const binding = await findOwnedBinding(client, principal.account.id, deviceId, false);
      if (!binding) return undefined;
      await client.query(
        `UPDATE device_share_invitations
            SET status = 'expired'
          WHERE binding_id = $1 AND status = 'pending' AND expires_at <= now()`,
        [binding.id],
      );
      const [invitations, grants] = await Promise.all([
        client.query<InvitationRow>(
          `SELECT i.*, b.device_id
             FROM device_share_invitations i
             JOIN connector_bindings b ON b.id = i.binding_id
            WHERE i.owner_account_id = $1 AND i.binding_id = $2 AND i.status = 'pending'
            ORDER BY i.created_at DESC, i.id`,
          [principal.account.id, binding.id],
        ),
        client.query<GrantRow>(
          `SELECT g.*, b.device_id
             FROM device_access_grants g
             JOIN connector_bindings b ON b.id = g.binding_id
            WHERE g.owner_account_id = $1 AND g.binding_id = $2 AND g.status = 'active'
            ORDER BY g.granted_at ASC, g.id`,
          [principal.account.id, binding.id],
        ),
      ]);
      return {
        invitations: invitations.rows.map(publicInvitation),
        grants: grants.rows.map(publicGrant),
        maxGranteesPerDevice: MAX_GRANTEES_PER_DEVICE,
      };
    });
  }

  async createInvitation(
    principal: AccountPrincipal,
    input: {
      invitationId: string;
      deviceId: string;
      targetEmailLookupHash: string;
      targetEmailHint: string;
      tokenHash: string;
      grantTokenHash: string;
      expiresAt: Date;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateShareInvitationResult> {
    return this.transaction(async (client) => {
      await lockKeys(client, [`account:${principal.account.id}`]);
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
        `device-share-email:${input.targetEmailLookupHash}`,
      ]);
      const targetDeletion = await client.query(
        `SELECT 1 FROM account_deletion_email_hashes
          WHERE hash_kind = 'device_share'
            AND trim(email_lookup_hash) = $1
          LIMIT 1`,
        [input.targetEmailLookupHash],
      );
      if ((targetDeletion.rowCount ?? 0) > 0) return { status: "invitation_conflict" };
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "device.share.invitation.create",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency)
            || !replay.device_share_invitation_id) return { status: "idempotency_conflict" };
        const saved = await loadInvitation(client, replay.device_share_invitation_id);
        if (!saved || saved.status !== "pending" || !saved.device_id || !saved.display_name) {
          return { status: "invitation_conflict" };
        }
        return {
          status: "replayed",
          invitation: publicInvitation(saved),
          deviceDisplayName: saved.display_name,
          needsDelivery: saved.delivery_status !== "sent",
        };
      }
      const binding = await findOwnedBinding(client, principal.account.id, input.deviceId, true);
      if (!binding) return { status: "not_found" };
      await lockKeys(client, [`binding:${binding.id}`]);
      await client.query(
        `UPDATE device_share_invitations SET status = 'expired'
          WHERE binding_id = $1 AND status = 'pending' AND expires_at <= now()`,
        [binding.id],
      );
      const duplicate = await client.query(
        `SELECT 1 FROM device_share_invitations
          WHERE binding_id = $1 AND target_email_lookup_hash = $2 AND status = 'pending'`,
        [binding.id, input.targetEmailLookupHash],
      );
      if ((duplicate.rowCount ?? 0) > 0) return { status: "invitation_conflict" };
      const grant = await loadReauthenticationGrant(
        client,
        principal,
        input.grantTokenHash,
        "device.share",
      );
      if (!grant) return { status: "reauthentication_failed" };
      const inserted = await client.query<InvitationRow>(
        `INSERT INTO device_share_invitations
           (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
            token_hash, expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING *`,
        [
          input.invitationId,
          binding.id,
          principal.account.id,
          input.targetEmailLookupHash,
          input.targetEmailHint,
          input.tokenHash,
          input.expiresAt,
        ],
      );
      await client.query("UPDATE reauthentication_grants SET used_at = now() WHERE id = $1", [grant.id]);
      await saveIdempotency(client, principal, "device.share.invitation.create", idempotency, {
        bindingId: binding.id,
        invitationId: input.invitationId,
      });
      await audit(client, principal, "device.share.invitation.created", {
        bindingId: binding.id,
        invitationId: input.invitationId,
        targetEmailHint: input.targetEmailHint,
      });
      return {
        status: "created",
        invitation: publicInvitation({ ...inserted.rows[0], device_id: binding.device_id }),
        deviceDisplayName: binding.display_name,
        needsDelivery: true,
      };
    });
  }

  async markInvitationDelivery(
    invitationId: string,
    submission: EmailProviderSubmission,
    at: Date,
  ): Promise<boolean> {
    const accepted = submission.status === "accepted";
    const result = await this.pool.query(
      accepted
        ? `UPDATE device_share_invitations
              SET delivery_status = 'sent', delivered_at = $2, provider_message_id = $3
            WHERE id = $1 AND status = 'pending' AND delivery_status <> 'sent'`
        : `UPDATE device_share_invitations
              SET delivery_status = 'failed'
            WHERE id = $1 AND status = 'pending' AND delivery_status <> 'sent'`,
      accepted ? [invitationId, at, submission.providerMessageId] : [invitationId],
    );
    if ((result.rowCount ?? 0) === 1) return true;
    const current = await this.pool.query<{ delivery_status: string }>(
      "SELECT delivery_status FROM device_share_invitations WHERE id = $1 AND status = 'pending'",
      [invitationId],
    );
    return current.rows[0]?.delivery_status === (accepted ? "sent" : "failed");
  }

  async cancelInvitation(
    principal: AccountPrincipal,
    deviceId: string,
    invitationId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ShareMutationResult> {
    return this.ownerMutation(
      principal,
      deviceId,
      "device.share.invitation.cancel",
      idempotency,
      async (client, binding) => {
        const changed = await client.query(
          `UPDATE device_share_invitations
              SET status = 'cancelled', cancelled_at = now()
            WHERE id = $1 AND binding_id = $2 AND owner_account_id = $3
              AND status = 'pending' AND expires_at > now()`,
          [invitationId, binding.id, principal.account.id],
        );
        if ((changed.rowCount ?? 0) !== 1) return { status: "not_found" };
        await saveIdempotency(client, principal, "device.share.invitation.cancel", idempotency, {
          bindingId: binding.id,
          invitationId,
        });
        await audit(client, principal, "device.share.invitation.cancelled", {
          bindingId: binding.id,
          invitationId,
        });
        return { status: "completed" };
      },
    );
  }

  async acceptInvitation(
    principal: AccountPrincipal,
    tokenHash: string,
    acceptedEmailLookupHashes: string[],
    idempotency: IdempotencyMaterial,
  ): Promise<AcceptShareInvitationResult> {
    return this.transaction(async (client) => {
      await lockKeys(client, [`account:${principal.account.id}`]);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "device.share.invitation.accept",
        idempotency.key,
      );
      if (replay) {
        if (!replayMatches(replay, principal.sessionId, idempotency)
            || !replay.device_access_grant_id) return { status: "idempotency_conflict" };
        const access = await loadGrantDevice(client, replay.device_access_grant_id, principal.account.id);
        return access
          ? { status: "replayed", device: accountDevice(access, "operator", access.is_default) }
          : { status: "conflict" };
      }
      const invitationResult = await client.query<InvitationRow>(
        `SELECT i.*, b.device_id, b.display_name
           FROM device_share_invitations i
           JOIN connector_bindings b ON b.id = i.binding_id
          WHERE i.token_hash = $1
          FOR UPDATE OF i`,
        [tokenHash],
      );
      const invitation = invitationResult.rows[0];
      if (!invitation || invitation.status !== "pending" || invitation.delivery_status !== "sent") {
        return { status: "not_found" };
      }
      if (invitation.expires_at.getTime() <= Date.now()) {
        await client.query(
          "UPDATE device_share_invitations SET status = 'expired' WHERE id = $1",
          [invitation.id],
        );
        return { status: "not_found" };
      }
      await lockKeys(client, [
        `binding:${invitation.binding_id}`,
        `account:${invitation.owner_account_id}`,
        `account:${principal.account.id}`,
      ]);
      if (!acceptedEmailLookupHashes.includes(invitation.target_email_lookup_hash.trim())) {
        return { status: "email_mismatch" };
      }
      if (invitation.owner_account_id === principal.account.id) return { status: "conflict" };
      const binding = await loadActiveBinding(client, invitation.binding_id, true);
      if (!binding || binding.account_id !== invitation.owner_account_id) return { status: "not_found" };
      const duplicate = await client.query(
        `SELECT 1 FROM device_access_grants
          WHERE binding_id = $1 AND grantee_account_id = $2 AND status = 'active'`,
        [binding.id, principal.account.id],
      );
      if ((duplicate.rowCount ?? 0) > 0) return { status: "conflict" };
      const deviceCount = await client.query<{ count: string }>(
        `SELECT COUNT(*)::text AS count FROM device_access_grants
          WHERE binding_id = $1 AND status = 'active'`,
        [binding.id],
      );
      if (Number(deviceCount.rows[0].count) >= this.maxGranteesPerDevice) {
        return { status: "device_capacity_reached" };
      }
      const accountCount = await client.query<{ count: string }>(
        `SELECT COUNT(*)::text AS count FROM device_access_grants
          WHERE grantee_account_id = $1 AND status = 'active'`,
        [principal.account.id],
      );
      if (Number(accountCount.rows[0].count) >= this.maxSharedDevicesPerAccount) {
        return { status: "account_capacity_reached" };
      }
      const grantId = randomUUID();
      await client.query(
        `INSERT INTO device_access_grants
           (id, binding_id, owner_account_id, grantee_account_id, grantee_email_hint)
         VALUES ($1, $2, $3, $4, $5)`,
        [
          grantId,
          binding.id,
          invitation.owner_account_id,
          principal.account.id,
          invitation.target_email_hint,
        ],
      );
      await client.query(
        `UPDATE device_share_invitations
            SET status = 'accepted', accepted_at = now(), accepted_by_account_id = $2
          WHERE id = $1`,
        [invitation.id, principal.account.id],
      );
      await saveIdempotency(client, principal, "device.share.invitation.accept", idempotency, {
        bindingId: binding.id,
        invitationId: invitation.id,
        grantId,
      });
      await audit(client, principal, "device.share.invitation.accepted", {
        bindingId: binding.id,
        invitationId: invitation.id,
        grantId,
      });
      await auditForAccount(client, invitation.owner_account_id, null, "device.share.grant.activated", {
        bindingId: binding.id,
        invitationId: invitation.id,
        grantId,
        granteeEmailHint: invitation.target_email_hint,
      });
      return { status: "accepted", device: accountDevice(binding, "operator", false) };
    });
  }

  async revokeGrant(
    principal: AccountPrincipal,
    deviceId: string,
    grantId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ShareMutationResult> {
    return this.ownerMutation(
      principal,
      deviceId,
      "device.share.grant.revoke",
      idempotency,
      async (client, binding) => {
        const found = await client.query<GrantRow>(
          `SELECT * FROM device_access_grants
            WHERE id = $1 AND binding_id = $2 AND owner_account_id = $3 AND status = 'active'
            FOR UPDATE`,
          [grantId, binding.id, principal.account.id],
        );
        const grant = found.rows[0];
        if (!grant) return { status: "not_found" };
        const generation = grant.authorization_generation + 1;
        await client.query(
          `UPDATE device_access_grants
              SET status = 'revoked', revoked_at = now(), authorization_generation = $2
            WHERE id = $1`,
          [grant.id, generation],
        );
        await clearDefault(client, grant.grantee_account_id, binding.id);
        await saveIdempotency(client, principal, "device.share.grant.revoke", idempotency, {
          bindingId: binding.id,
          grantId: grant.id,
        });
        await audit(client, principal, "device.share.grant.revoked", {
          bindingId: binding.id,
          grantId: grant.id,
          granteeEmailHint: grant.grantee_email_hint,
        });
        await publishAccountAccessRevocation(client, {
          kind: "binding",
          accountId: grant.grantee_account_id,
          bindingId: binding.id,
        });
        return {
          status: "completed",
          revokedAccess: {
            bindingId: binding.id,
            deviceId: binding.device_id,
            granteeAccountId: grant.grantee_account_id,
            authorizationGeneration: generation,
          },
        };
      },
    );
  }

  async leaveDevice(
    principal: AccountPrincipal,
    deviceId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ShareMutationResult> {
    return this.transaction(async (client) => {
      await lockKeys(client, [`account:${principal.account.id}`]);
      const replay = await loadIdempotency(
        client,
        principal.account.id,
        "device.share.grant.leave",
        idempotency.key,
      );
      if (replay) {
        return replayMatches(replay, principal.sessionId, idempotency)
          ? { status: "replayed" }
          : { status: "idempotency_conflict" };
      }
      const found = await client.query<GrantRow & { device_id: string }>(
        `SELECT g.*, b.device_id
           FROM device_access_grants g
           JOIN connector_bindings b ON b.id = g.binding_id
          WHERE g.grantee_account_id = $1 AND g.status = 'active'
            AND b.device_id = $2 AND b.status = 'active'
          `,
        [principal.account.id, deviceId],
      );
      const candidate = found.rows[0];
      if (!candidate) return { status: "not_found" };
      await lockKeys(client, [`binding:${candidate.binding_id}`, `account:${candidate.owner_account_id}`]);
      const locked = await client.query<GrantRow & { device_id: string }>(
        `SELECT g.*, b.device_id
           FROM device_access_grants g
           JOIN connector_bindings b ON b.id = g.binding_id
          WHERE g.id = $1 AND g.grantee_account_id = $2 AND g.status = 'active'
            AND b.status = 'active'
          FOR UPDATE OF g`,
        [candidate.id, principal.account.id],
      );
      const grant = locked.rows[0];
      if (!grant) return { status: "not_found" };
      const generation = grant.authorization_generation + 1;
      await client.query(
        `UPDATE device_access_grants
            SET status = 'left', left_at = now(), authorization_generation = $2
          WHERE id = $1`,
        [grant.id, generation],
      );
      await clearDefault(client, principal.account.id, grant.binding_id);
      await saveIdempotency(client, principal, "device.share.grant.leave", idempotency, {
        bindingId: grant.binding_id,
        grantId: grant.id,
      });
      await audit(client, principal, "device.share.grant.left", {
        bindingId: grant.binding_id,
        grantId: grant.id,
      });
      await auditForAccount(client, grant.owner_account_id, null, "device.share.grantee.left", {
        bindingId: grant.binding_id,
        grantId: grant.id,
        granteeEmailHint: grant.grantee_email_hint,
      });
      await publishAccountAccessRevocation(client, {
        kind: "binding",
        accountId: principal.account.id,
        bindingId: grant.binding_id,
      });
      return {
        status: "completed",
        revokedAccess: {
          bindingId: grant.binding_id,
          deviceId: grant.device_id,
          granteeAccountId: principal.account.id,
          authorizationGeneration: generation,
        },
      };
    });
  }

  private async ownerMutation(
    principal: AccountPrincipal,
    deviceId: string,
    operation: string,
    idempotency: IdempotencyMaterial,
    mutate: (client: PoolClient, binding: BindingRow) => Promise<ShareMutationResult>,
  ): Promise<ShareMutationResult> {
    return this.transaction(async (client) => {
      await lockKeys(client, [`account:${principal.account.id}`]);
      const replay = await loadIdempotency(client, principal.account.id, operation, idempotency.key);
      if (replay) {
        return replayMatches(replay, principal.sessionId, idempotency)
          ? { status: "replayed" }
          : { status: "idempotency_conflict" };
      }
      const binding = await findOwnedBinding(client, principal.account.id, deviceId, true);
      if (!binding) return { status: "not_found" };
      await lockKeys(client, [`binding:${binding.id}`]);
      return mutate(client, binding);
    });
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

const bindingColumns = `b.id, b.account_id, b.display_name, b.device_id,
  b.public_key_fingerprint, b.generation, b.status, b.connector_online, b.hermes_reachable,
  b.hermes_version, b.gateway_latency_ms, b.end_to_end_healthy, b.health_checked_at, b.last_seen_at`;

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
    gateway: row.gateway_latency_ms === null ? {} : { latencyMs: row.gateway_latency_ms },
    endToEnd: {
      healthy: row.end_to_end_healthy,
      ...(row.health_checked_at ? { checkedAt: row.health_checked_at.toISOString() } : {}),
    },
  };
}

function accountDevice(
  row: BindingRow,
  access: AccountDevice["access"],
  isDefault: boolean,
): AccountDevice {
  return { ...activeBinding(row), access, isDefault: Boolean(isDefault) };
}

function publicInvitation(row: InvitationRow): DeviceShareInvitation {
  if (!row.device_id) throw new Error("sharing invitation has no device view");
  return {
    id: row.id,
    deviceId: row.device_id,
    targetEmailHint: row.target_email_hint,
    status: "pending",
    expiresAt: row.expires_at.toISOString(),
    createdAt: row.created_at.toISOString(),
  };
}

function publicGrant(row: GrantRow): DeviceAccessGrant {
  if (!row.device_id) throw new Error("device access grant has no device view");
  return {
    id: row.id,
    deviceId: row.device_id,
    granteeEmailHint: row.grantee_email_hint,
    role: "operator",
    status: "active",
    grantedAt: row.granted_at.toISOString(),
  };
}

async function findOwnedBinding(
  client: PoolClient,
  accountId: string,
  deviceId: string,
  lock: boolean,
): Promise<BindingRow | undefined> {
  const result = await client.query<BindingRow>(
    `SELECT ${bindingColumns}
       FROM connector_bindings b
      WHERE b.account_id = $1 AND b.device_id = $2 AND b.status = 'active'
      ${lock ? "FOR UPDATE OF b" : ""}`,
    [accountId, deviceId],
  );
  return result.rows[0];
}

async function loadActiveBinding(
  client: PoolClient,
  bindingId: string,
  lock: boolean,
): Promise<BindingRow | undefined> {
  const result = await client.query<BindingRow>(
    `SELECT ${bindingColumns}
       FROM connector_bindings b
      WHERE b.id = $1 AND b.status = 'active'
      ${lock ? "FOR UPDATE OF b" : ""}`,
    [bindingId],
  );
  return result.rows[0];
}

async function findAccessibleBindingByDevice(
  client: PoolClient,
  accountId: string,
  deviceId: string,
  lock: boolean,
): Promise<{ row: BindingRow; access: AccountDevice["access"] } | undefined> {
  const result = await client.query<BindingRow & { access: AccountDevice["access"] }>(
    `SELECT ${bindingColumns},
            CASE WHEN b.account_id = $1 THEN 'owner'::text ELSE 'operator'::text END AS access
       FROM connector_bindings b
      WHERE b.device_id = $2 AND b.status = 'active'
        AND (b.account_id = $1 OR EXISTS (
          SELECT 1 FROM device_access_grants g
           WHERE g.binding_id = b.id AND g.grantee_account_id = $1 AND g.status = 'active'
        ))
      ${lock ? "FOR UPDATE OF b" : ""}`,
    [accountId, deviceId],
  );
  const row = result.rows[0];
  return row ? { row, access: row.access } : undefined;
}

async function loadAccessibleBinding(
  client: PoolClient,
  accountId: string,
  bindingId: string,
): Promise<{ row: BindingRow; access: AccountDevice["access"] } | undefined> {
  const result = await client.query<BindingRow & { access: AccountDevice["access"] }>(
    `SELECT ${bindingColumns},
            CASE WHEN b.account_id = $1 THEN 'owner'::text ELSE 'operator'::text END AS access
       FROM connector_bindings b
      WHERE b.id = $2 AND b.status = 'active'
        AND (b.account_id = $1 OR EXISTS (
          SELECT 1 FROM device_access_grants g
           WHERE g.binding_id = b.id AND g.grantee_account_id = $1 AND g.status = 'active'
        ))`,
    [accountId, bindingId],
  );
  const row = result.rows[0];
  return row ? { row, access: row.access } : undefined;
}

async function loadInvitation(client: PoolClient, id: string): Promise<InvitationRow | undefined> {
  const result = await client.query<InvitationRow>(
    `SELECT i.*, b.device_id, b.display_name
       FROM device_share_invitations i
       JOIN connector_bindings b ON b.id = i.binding_id
      WHERE i.id = $1`,
    [id],
  );
  return result.rows[0];
}

async function loadGrantDevice(
  client: PoolClient,
  grantId: string,
  granteeAccountId: string,
): Promise<BindingRow & { is_default: boolean } | undefined> {
  const result = await client.query<BindingRow & { is_default: boolean }>(
    `SELECT ${bindingColumns},
            (b.id = (SELECT p.default_binding_id FROM account_device_preferences p
                      WHERE p.account_id = $2)) AS is_default
       FROM device_access_grants g
       JOIN connector_bindings b ON b.id = g.binding_id
      WHERE g.id = $1 AND g.grantee_account_id = $2 AND g.status = 'active' AND b.status = 'active'`,
    [grantId, granteeAccountId],
  );
  return result.rows[0];
}

async function loadReauthenticationGrant(
  client: PoolClient,
  principal: AccountPrincipal,
  tokenHash: string,
  scope: "device.share",
): Promise<{ id: string } | undefined> {
  const result = await client.query<{
    id: string;
    scope: string;
    expires_at: Date;
    used_at: Date | null;
    revoked_at: Date | null;
  }>(
    `SELECT id, scope, expires_at, used_at, revoked_at
       FROM reauthentication_grants
      WHERE token_hash = $1 AND account_id = $2 AND installation_id = $3 AND session_id = $4
      FOR UPDATE`,
    [tokenHash, principal.account.id, principal.installation.id, principal.sessionId],
  );
  const row = result.rows[0];
  if (!row || row.scope !== scope || row.used_at || row.revoked_at) return undefined;
  if (row.expires_at.getTime() <= Date.now()) {
    await client.query("UPDATE reauthentication_grants SET revoked_at = now() WHERE id = $1", [row.id]);
    return undefined;
  }
  return { id: row.id };
}

async function lockKeys(client: PoolClient, keys: string[]): Promise<void> {
  for (const key of [...new Set(keys)].sort()) {
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [`account-sharing:${key}`]);
  }
}

async function loadIdempotency(
  client: PoolClient,
  accountId: string,
  operation: string,
  key: string,
): Promise<IdempotencyRow | undefined> {
  const result = await client.query<IdempotencyRow>(
    `SELECT session_id, connector_binding_id, device_share_invitation_id, device_access_grant_id,
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
  principal: AccountPrincipal,
  operation: string,
  idempotency: IdempotencyMaterial,
  references: { bindingId?: string; invitationId?: string; grantId?: string },
): Promise<void> {
  await client.query(
    `INSERT INTO account_idempotency_records
       (id, account_id, session_id, connector_binding_id, device_share_invitation_id,
        device_access_grant_id, operation, idempotency_key, request_hash,
        response_ciphertext, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
    [
      randomUUID(),
      principal.account.id,
      principal.sessionId,
      references.bindingId ?? null,
      references.invitationId ?? null,
      references.grantId ?? null,
      operation,
      idempotency.key,
      idempotency.requestHash,
      idempotency.responseCiphertext,
      idempotency.expiresAt,
    ],
  );
}

async function clearDefault(client: PoolClient, accountId: string, bindingId: string): Promise<void> {
  await client.query(
    "DELETE FROM account_device_preferences WHERE account_id = $1 AND default_binding_id = $2",
    [accountId, bindingId],
  );
}

async function audit(
  client: PoolClient,
  principal: AccountPrincipal,
  eventType: string,
  metadata: Record<string, string>,
): Promise<void> {
  await auditForAccount(client, principal.account.id, principal.installation.id, eventType, metadata);
}

async function auditForAccount(
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
