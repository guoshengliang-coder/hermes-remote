import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { AccountModeError, accountErrors, type AccountPrincipal, type PublicAccount, type StoredAccountAvatar } from "./model.js";
import type { TokenCodec } from "./token-codec.js";

const MAX_REVISION = 18446744073709551615n;
const RETENTION_MS = 90 * 86_400_000;
const CURSOR_MS = 86_400_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const profileSyncErrors = {
  conflict: () => new AccountModeError(409, "HR-SYNC-006", "资料已在其他设备更新，请刷新后重新确认修改。", false, "none"),
  reset: () => new AccountModeError(409, "HR-SYNC-007", "同步记录已失效，请刷新资料后重试。", false, "none"),
  name: () => new AccountModeError(400, "HR-SYNC-008", "无法保存名字，请使用 1–40 个字符后重试。", false, "none"),
  avatar: () => new AccountModeError(400, "HR-SYNC-009", "无法保存头像，请选择不超过 4 MB 的 PNG、JPEG 或 WebP 图片。", false, "none"),
};
export interface ProfileSyncState {
  revision: string;
  nameRevision: string;
  avatarRevision: string;
  displayName: string;
}
export interface ProfileSyncOperation {
  opId: string;
  issuedAt: string;
  baseRevision: string;
  field: "displayName" | "avatar";
  displayName?: string;
  avatar?: StoredAccountAvatar;
}
export interface ProfileSyncResult {
  status: "accepted" | "conflict";
  revision: string;
  nameRevision: string;
  avatarRevision: string;
}
export interface ProfileSyncStore {
  read(principal: AccountPrincipal, initialName: string): Promise<ProfileSyncState>;
  avatar(principal: AccountPrincipal, initialName: string, revision: string): Promise<StoredAccountAvatar | null>;
  mutate(principal: AccountPrincipal, initialName: string, op: ProfileSyncOperation, hash: string): Promise<ProfileSyncResult>;
}
export function profileRevision(value: unknown): string {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,19})$/.test(value) || BigInt(value) > MAX_REVISION) {
    throw accountErrors.invalidRequest("Invalid profile revision.");
  }
  return value;
}

/** One entity, coalesced deltas. This cursor must never be reused for a multi-entity change log. */
export class AccountProfileSync {
  constructor(private readonly store: ProfileSyncStore, private readonly tokens: TokenCodec, private readonly now = Date.now) {}

  async snapshot(principal: AccountPrincipal, web: boolean, cursor?: string) {
    const since = cursor === undefined ? undefined : this.parseCursor(principal, cursor);
    const state = await this.store.read(principal, initialName(principal.account));
    if (since !== undefined && BigInt(since) > BigInt(state.revision)) throw profileSyncErrors.reset();
    return {
      version: 1 as const,
      revision: state.revision,
      cursor: this.cursor(principal, state.revision),
      // No pagination: there is exactly one account-owned entity in this capability.
      hasMore: false as const,
      profile: since === state.revision ? null : {
        nameRevision: state.nameRevision,
        avatarRevision: state.avatarRevision,
        account: {
          id: principal.account.id,
          displayName: state.displayName,
          ...(principal.account.email ? { email: principal.account.email } : {}),
          ...(state.avatarRevision !== "0" ? {
            avatarUrl: `/v2/${web ? "web/" : ""}account-data/avatar?rev=${state.avatarRevision}${web ? `&owner=${encodeURIComponent(principal.account.id)}` : ""}`,
          } : {}),
        },
      },
    };
  }

  avatar(principal: AccountPrincipal, revision: unknown): Promise<StoredAccountAvatar | null> {
    return this.store.avatar(principal, initialName(principal.account), profileRevision(revision));
  }

  async mutate(principal: AccountPrincipal, input: ProfileSyncOperation): Promise<ProfileSyncResult> {
    const { opId, issuedAt, field } = input;
    if (typeof opId !== "string" || !UUID.test(opId) || (field !== "displayName" && field !== "avatar")) {
      throw accountErrors.invalidRequest("Invalid profile operation.");
    }
    const timestamp = typeof issuedAt === "string" ? Date.parse(issuedAt) : NaN;
    if (!Number.isFinite(timestamp) || timestamp > this.now() + 300_000 || timestamp < this.now() - RETENTION_MS) {
      throw profileSyncErrors.reset();
    }
    const baseRevision = profileRevision(input.baseRevision);
    let displayName: string | undefined;
    if (field === "displayName") {
      displayName = input.displayName?.trim();
      if (!displayName || displayName.length > 40 || /[\u0000-\u001f\u007f]/.test(displayName)) throw profileSyncErrors.name();
    } else if (!input.avatar) throw profileSyncErrors.avatar();
    const op = { ...input, opId: opId.toLowerCase(), baseRevision, displayName };
    const hash = this.tokens.hashContext(JSON.stringify([
      "account-profile-sync-v1", field, baseRevision, issuedAt,
      field === "displayName" ? displayName : [input.avatar!.contentType, createHash("sha256").update(input.avatar!.data).digest("hex")],
    ]));
    const result = await this.store.mutate(principal, initialName(principal.account), op, hash);
    if (result.status === "conflict") throw profileSyncErrors.conflict();
    return result;
  }

  private cursor(principal: AccountPrincipal, revision: string): string {
    const body = Buffer.from(JSON.stringify([principal.account.id, revision, this.now() + CURSOR_MS])).toString("base64url");
    return `${body}.${this.sign(body)}`;
  }
  private sign(body: string): string {
    return createHmac("sha256", this.tokens.deriveSubkey("account-profile-sync-cursor-v1")).update(body).digest("base64url");
  }
  private parseCursor(principal: AccountPrincipal, value: string): string {
    try {
      if (value.length > 512) throw new Error();
      const parts = value.split(".");
      if (parts.length !== 2) throw new Error();
      const expected = Buffer.from(this.sign(parts[0]));
      const supplied = Buffer.from(parts[1]);
      if (supplied.length !== expected.length || !timingSafeEqual(expected, supplied)) throw new Error();
      const [accountId, revision, expires] = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
      if (accountId !== principal.account.id || typeof expires !== "number" || expires <= this.now()) throw new Error();
      return profileRevision(revision);
    } catch { throw profileSyncErrors.reset(); }
  }
}
function initialName(account: PublicAccount): string {
  return (account.displayName?.trim() || account.email?.split("@")[0] || "Hermes GO").replace(/[\u0000-\u001f\u007f]/g, "").slice(0, 40) || "Hermes GO";
}

export class PostgresProfileSyncStore implements ProfileSyncStore {
  constructor(private readonly pool: Pool) {}
  read(principal: AccountPrincipal, name: string): Promise<ProfileSyncState> {
    return this.transaction(principal, name, async (_client, state) => state);
  }
  avatar(principal: AccountPrincipal, name: string, revision: string): Promise<StoredAccountAvatar | null> {
    return this.transaction(principal, name, async (client, state) => {
      if (state.avatarRevision !== revision) throw profileSyncErrors.reset();
      const result = await client.query("SELECT avatar_data, avatar_content_type FROM account_profiles WHERE account_id=$1", [principal.account.id]);
      const row = result.rows[0];
      return row?.avatar_data ? { data: row.avatar_data as Buffer, contentType: row.avatar_content_type as StoredAccountAvatar["contentType"] } : null;
    });
  }
  mutate(principal: AccountPrincipal, name: string, op: ProfileSyncOperation, hash: string): Promise<ProfileSyncResult> {
    return this.transaction(principal, name, async (client, state) => {
      // Account/session locks and live checks happen before any replay, including a stale success.
      const replay = await client.query<{ request_hash: string; result: ProfileSyncResult }>(
        "SELECT request_hash, result FROM account_profile_sync_operations WHERE account_id=$1 AND op_id=$2",
        [principal.account.id, op.opId],
      );
      if (replay.rows[0]) {
        if (replay.rows[0].request_hash !== hash) throw accountErrors.idempotencyConflict();
        return replay.rows[0].result;
      }
      const revision = op.field === "displayName" ? state.nameRevision : state.avatarRevision;
      let status: ProfileSyncResult["status"] = "conflict";
      if (revision === op.baseRevision) {
        if (op.field === "displayName") {
          await client.query("UPDATE account_profiles SET display_name=$2, updated_at=now() WHERE account_id=$1", [principal.account.id, op.displayName]);
        } else {
          await client.query(`UPDATE account_profiles SET avatar_data=$2, avatar_content_type=$3,
            avatar_updated_at=now(), updated_at=now() WHERE account_id=$1`, [principal.account.id, op.avatar!.data, op.avatar!.contentType]);
        }
        state = await readState(client, principal.account.id);
        status = "accepted";
      }
      const result: ProfileSyncResult = { status, revision: state.revision, nameRevision: state.nameRevision, avatarRevision: state.avatarRevision };
      await client.query("DELETE FROM account_profile_sync_operations WHERE account_id=$1 AND expires_at<=now()", [principal.account.id]);
      const count = await client.query<{ count: string }>("SELECT count(*) FROM account_profile_sync_operations WHERE account_id=$1", [principal.account.id]);
      if (Number(count.rows[0].count) >= 10_000) throw accountErrors.rateLimited();
      await client.query(`INSERT INTO account_profile_sync_operations(account_id,op_id,request_hash,result,expires_at)
        VALUES($1,$2,$3,$4,$5)`, [principal.account.id, op.opId, hash, result, new Date(Date.parse(op.issuedAt) + RETENTION_MS)]);
      return result;
    });
  }
  private async transaction<T>(principal: AccountPrincipal, name: string, run: (client: PoolClient, state: ProfileSyncState) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const access = await client.query(`SELECT a.status, s.revoked_at AS session_revoked, i.revoked_at AS installation_revoked,
          s.access_expires_at > clock_timestamp() AS live
        FROM account_sessions s JOIN accounts a ON a.id=s.account_id JOIN installations i ON i.id=s.installation_id
        WHERE s.id=$1 AND a.id=$2 AND i.id=$3 FOR UPDATE OF s,a,i`,
      [principal.sessionId, principal.account.id, principal.installation.id]);
      const row = access.rows[0];
      if (!row) throw accountErrors.sessionExpired();
      if (row.status !== "active") throw accountErrors.accountDisabled();
      if (row.session_revoked || row.installation_revoked) throw accountErrors.sessionRevoked();
      if (!row.live) throw accountErrors.sessionExpired();
      await client.query("INSERT INTO account_profiles(account_id,display_name) VALUES($1,$2) ON CONFLICT DO NOTHING", [principal.account.id, name]);
      const state = await readState(client, principal.account.id);
      const result = await run(client, state);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally { client.release(); }
  }
}
async function readState(client: PoolClient, id: string): Promise<ProfileSyncState> {
  const found = await client.query(`SELECT display_name AS "displayName", sync_revision::text AS revision,
    name_revision::text AS "nameRevision", avatar_revision::text AS "avatarRevision"
    FROM account_profiles WHERE account_id=$1 FOR UPDATE`, [id]);
  return found.rows[0] as ProfileSyncState;
}
