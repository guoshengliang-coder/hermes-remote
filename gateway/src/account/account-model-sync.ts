import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import {
  AccountModeError,
  accountErrors,
  type AccountPrincipal,
} from "./model.js";
import type { TokenCodec } from "./token-codec.js";

export const MODEL_SYNC_RETENTION_MS = 90 * 86_400_000;
export const MODEL_SYNC_MAX_MODELS = 500;
// Separate abuse budgets: cancelled tuples retain only HMAC + revisions, not model identifiers.
const MAX_MARKERS = 10_000;
const MAX_OPERATIONS = 10_000;
const PAGE = 200;
const MAX_REVISION = 18446744073709551615n;
export const REASONING_PRESETS = [
  "none",
  "minimal",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
  "ultra",
] as const;
export type ReasoningPreset = (typeof REASONING_PRESETS)[number];
export const modelSyncErrors = {
  conflict: () =>
    new AccountModeError(
      409,
      "HR-SYNC-012",
      "模型偏好已在其他设备更新，请选择保留云端值或重新应用本机选择。",
      false,
      "none",
    ),
  reset: () =>
    new AccountModeError(
      409,
      "HR-SYNC-013",
      "模型同步记录已失效，请刷新并确认待同步选择。",
      false,
      "none",
    ),
  invalid: () =>
    new AccountModeError(
      400,
      "HR-SYNC-014",
      "无法保存此模型偏好，请检查选择后重试。",
      false,
      "none",
    ),
  quota: () =>
    new AccountModeError(
      409,
      "HR-SYNC-015",
      "模型偏好存储已达上限，本机待同步选择仍保留。",
      false,
      "none",
    ),
};
export interface ModelPreference {
  key: string;
  provider: string;
  model: string;
  favorite: boolean;
  reasoningPreset: ReasoningPreset | null;
  revision: string;
  favoriteRevision: string;
  reasoningRevision: string;
}
export interface ModelMutation {
  opId: string;
  lease: string;
  provider: string;
  model: string;
  field: "favorite" | "reasoningPreset";
  baseRevision: string;
  value: boolean | ReasoningPreset | null;
}
interface State {
  revision: string;
  floor: string;
  secret: Buffer;
}
export function modelRevision(value: unknown): string {
  if (
    typeof value !== "string" ||
    !/^(0|[1-9][0-9]{0,19})$/.test(value) ||
    BigInt(value) > MAX_REVISION
  )
    throw modelSyncErrors.invalid();
  return value;
}
function identifier(value: unknown, limit: number): string {
  if (
    typeof value !== "string" ||
    !value.trim() ||
    value.length > limit ||
    /[\u0000-\u001f\u007f]/.test(value)
  )
    throw modelSyncErrors.invalid();
  return value;
}
export function parseModelMutation(value: unknown): ModelMutation {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw modelSyncErrors.invalid();
  const input = value as Record<string, unknown>;
  if (
    Object.keys(input).some(
      (key) =>
        ![
          "opId",
          "lease",
          "provider",
          "model",
          "field",
          "baseRevision",
          "value",
        ].includes(key),
    ) ||
    typeof input.opId !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      input.opId,
    ) ||
    typeof input.lease !== "string" ||
    input.lease.length > 512
  )
    throw modelSyncErrors.invalid();
  if (
    input.field === "favorite"
      ? typeof input.value !== "boolean"
      : input.field !== "reasoningPreset" ||
        (input.value !== null &&
          !REASONING_PRESETS.includes(input.value as ReasoningPreset))
  )
    throw modelSyncErrors.invalid();
  return {
    opId: input.opId.toLowerCase(),
    lease: input.lease,
    provider: identifier(input.provider, 256),
    model: identifier(input.model, 1024),
    field: input.field as ModelMutation["field"],
    value: input.value as ModelMutation["value"],
    baseRevision: modelRevision(input.baseRevision),
  };
}

/** Fixed bounded snapshots; real ordered log pages with a frozen upper boundary. Not the profile cursor. */
export class AccountModelSync {
  constructor(
    private readonly pool: Pool,
    private readonly tokens: TokenCodec,
    private readonly now = Date.now,
  ) {}

  async snapshot(actor: AccountPrincipal) {
    return this.transaction(actor, async (client, state) => {
      const rows = await client.query(
        "SELECT * FROM account_model_preferences WHERE account_id=$1 AND provider IS NOT NULL ORDER BY key_hash LIMIT 501",
        [actor.account.id],
      );
      return {
        version: 1 as const,
        revision: state.revision,
        rows: rows.rows.map((r) => publicRow(r)),
        hasMore: false,
        cursor: this.cursor(actor, state.revision),
        lease: this.lease(actor),
      };
    });
  }
  async changes(actor: AccountPrincipal, cursor: string) {
    const parsed = this.verify(cursor, "cursor", actor);
    const since = modelRevision(parsed[2]);
    const until = parsed[3] === null ? null : modelRevision(parsed[3]);
    return this.transaction(actor, async (client, state) => {
      const boundary = until ?? state.revision;
      if (
        BigInt(since) < BigInt(state.floor) ||
        BigInt(since) > BigInt(boundary) ||
        BigInt(boundary) > BigInt(state.revision)
      )
        throw modelSyncErrors.reset();
      const found = await client.query<{ payload: ModelPreference }>(
        `SELECT payload FROM account_model_sync_changes WHERE account_id=$1 AND revision>$2 AND revision<=$3 ORDER BY revision LIMIT $4`,
        [actor.account.id, since, boundary, PAGE + 1],
      );
      const hasMore = found.rows.length > PAGE;
      const rows = found.rows.slice(0, PAGE).map((r) => r.payload);
      const next = hasMore ? rows.at(-1)!.revision : boundary;
      return {
        version: 1 as const,
        revision: boundary,
        rows,
        hasMore,
        cursor: this.cursor(actor, next, hasMore ? boundary : null),
        lease: this.lease(actor),
      };
    });
  }
  async resolve(actor: AccountPrincipal, input: unknown) {
    if (
      !input ||
      typeof input !== "object" ||
      Array.isArray(input) ||
      Object.keys(input).some((k) => !["provider", "model"].includes(k))
    )
      throw modelSyncErrors.invalid();
    const { provider, model } = input as Record<string, unknown>;
    const pair = {
      provider: identifier(provider, 256),
      model: identifier(model, 1024),
    };
    return this.transaction(actor, async (client, state) => {
      const key = keyFor(state, pair.provider, pair.model);
      const found = await client.query(
        "SELECT * FROM account_model_preferences WHERE account_id=$1 AND key_hash=$2",
        [actor.account.id, key],
      );
      return { row: publicRow(found.rows[0], pair, key) };
    });
  }
  async mutate(actor: AccountPrincipal, input: unknown) {
    const op = parseModelMutation(input);
    this.verify(op.lease, "lease", actor);
    return this.transaction(actor, async (client, state) => {
      // Live session/account locks precede even a cached success.
      this.verify(op.lease, "lease", actor);
      const hash = createHmac("sha256", state.secret)
        .update(
          JSON.stringify([
            "model-sync-v1",
            op.provider,
            op.model,
            op.field,
            op.baseRevision,
            op.value,
          ]),
        )
        .digest("hex");
      const replay = await client.query(
        "SELECT request_hash,result FROM account_model_sync_operations WHERE account_id=$1 AND op_id=$2",
        [actor.account.id, op.opId],
      );
      if (replay.rows[0]) {
        if (replay.rows[0].request_hash !== hash)
          throw accountErrors.idempotencyConflict();
        return replay.rows[0].result as {
          status: "accepted" | "conflict";
          row: ModelPreference;
        };
      }
      await client.query(
        "DELETE FROM account_model_sync_operations WHERE account_id=$1 AND expires_at<=clock_timestamp()",
        [actor.account.id],
      );
      const quota = await client.query(
        `SELECT (SELECT count(*) FROM account_model_preferences WHERE account_id=$1 AND provider IS NOT NULL)::int AS active,
        (SELECT count(*) FROM account_model_preferences WHERE account_id=$1)::int AS markers,
        (SELECT count(*) FROM account_model_sync_operations WHERE account_id=$1)::int AS operations,
        (SELECT count(*) FROM account_model_sync_changes WHERE account_id=$1)::int AS logs`,
        [actor.account.id],
      );
      if (quota.rows[0].operations >= MAX_OPERATIONS)
        throw modelSyncErrors.quota();
      const key = keyFor(state, op.provider, op.model);
      const found = await client.query(
        "SELECT * FROM account_model_preferences WHERE account_id=$1 AND key_hash=$2",
        [actor.account.id, key],
      );
      const before = publicRow(found.rows[0], op, key);
      const current =
        op.field === "favorite"
          ? before.favoriteRevision
          : before.reasoningRevision;
      let row = before;
      let status: "accepted" | "conflict" = "conflict";
      if (current === op.baseRevision) {
        if (BigInt(state.revision) === MAX_REVISION)
          throw modelSyncErrors.reset();
        row = {
          ...before,
          revision: String(BigInt(state.revision) + 1n),
          ...(op.field === "favorite"
            ? {
                favorite: op.value as boolean,
                favoriteRevision: String(BigInt(state.revision) + 1n),
              }
            : {
                reasoningPreset: op.value as ReasoningPreset | null,
                reasoningRevision: String(BigInt(state.revision) + 1n),
              }),
        };
        const active = row.favorite || row.reasoningPreset !== null;
        if (
          (!found.rows[0] && quota.rows[0].markers >= MAX_MARKERS) ||
          (active &&
            !found.rows[0]?.provider &&
            quota.rows[0].active >= MODEL_SYNC_MAX_MODELS) ||
          quota.rows[0].logs >= MAX_OPERATIONS
        )
          throw modelSyncErrors.quota();
        await client.query(
          `INSERT INTO account_model_preferences(account_id,key_hash,provider,model,favorite,reasoning,revision,favorite_revision,reasoning_revision)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) ON CONFLICT(account_id,key_hash) DO UPDATE SET
          provider=excluded.provider,model=excluded.model,favorite=excluded.favorite,reasoning=excluded.reasoning,
          revision=excluded.revision,favorite_revision=excluded.favorite_revision,reasoning_revision=excluded.reasoning_revision`,
          [
            actor.account.id,
            key,
            active ? op.provider : null,
            active ? op.model : null,
            row.favorite,
            row.reasoningPreset,
            row.revision,
            row.favoriteRevision,
            row.reasoningRevision,
          ],
        );
        await client.query(
          "UPDATE account_model_sync_state SET revision=$2 WHERE account_id=$1",
          [actor.account.id, row.revision],
        );
        await client.query(
          "INSERT INTO account_model_sync_changes(account_id,revision,payload,expires_at) VALUES($1,$2,$3,clock_timestamp()+interval '90 days')",
          [actor.account.id, row.revision, row],
        );
        status = "accepted";
      }
      const result = { status, row };
      // No tuple/private payload in replay storage: retain only terminal revisions/value.
      const { provider: _provider, model: _model, ...metadata } = row;
      const stored = { status, row: metadata };
      await client.query(
        `INSERT INTO account_model_sync_operations(account_id,op_id,request_hash,result,expires_at)
        VALUES($1,$2,$3,$4,clock_timestamp()+interval '90 days')`,
        [actor.account.id, op.opId, hash, stored],
      );
      return result;
    }).then((result) => ({
      ...result,
      row: { ...result.row, provider: op.provider, model: op.model },
    }));
  }

  private lease(actor: AccountPrincipal): string {
    return this.sign([
      "lease",
      actor.account.id,
      actor.installation.id,
      null,
      this.now() + MODEL_SYNC_RETENTION_MS,
    ]);
  }
  private cursor(
    actor: AccountPrincipal,
    since: string,
    boundary: string | null = null,
  ): string {
    return this.sign([
      "cursor",
      actor.account.id,
      since,
      boundary,
      this.now() + 86_400_000,
    ]);
  }
  private sign(payload: unknown[]): string {
    const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
    const signature = createHmac(
      "sha256",
      this.tokens.deriveSubkey("account-model-sync-v1"),
    )
      .update(body)
      .digest("base64url");
    return `${body}.${signature}`;
  }
  private verify(
    value: string,
    kind: "lease" | "cursor",
    actor: AccountPrincipal,
  ): unknown[] {
    try {
      if (typeof value !== "string" || value.length > 512) throw new Error();
      const [body, signature, extra] = value.split(".");
      const expected = createHmac(
        "sha256",
        this.tokens.deriveSubkey("account-model-sync-v1"),
      )
        .update(body)
        .digest("base64url");
      if (
        extra ||
        !signature ||
        signature.length !== expected.length ||
        !timingSafeEqual(Buffer.from(signature), Buffer.from(expected))
      )
        throw new Error();
      const data = JSON.parse(
        Buffer.from(body, "base64url").toString("utf8"),
      ) as unknown[];
      if (
        !Array.isArray(data) ||
        data.length !== 5 ||
        data[0] !== kind ||
        data[1] !== actor.account.id ||
        typeof data[4] !== "number" ||
        data[4] <= this.now() ||
        (kind === "lease" && data[2] !== actor.installation.id)
      )
        throw new Error();
      return data;
    } catch {
      throw modelSyncErrors.reset();
    }
  }
  private async transaction<T>(
    actor: AccountPrincipal,
    run: (client: PoolClient, state: State) => Promise<T>,
  ): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const access = await client.query(
        `SELECT a.status,s.revoked_at AS session_revoked,i.revoked_at AS installation_revoked,
        s.access_expires_at>clock_timestamp() AS live FROM account_sessions s JOIN accounts a ON a.id=s.account_id
        JOIN installations i ON i.id=s.installation_id WHERE s.id=$1 AND a.id=$2 AND i.id=$3 FOR UPDATE OF s,a,i`,
        [actor.sessionId, actor.account.id, actor.installation.id],
      );
      const row = access.rows[0];
      if (!row || !row.live) throw accountErrors.sessionExpired();
      if (row.status !== "active") throw accountErrors.accountDisabled();
      if (row.session_revoked || row.installation_revoked)
        throw accountErrors.sessionRevoked();
      await client.query(
        "INSERT INTO account_model_sync_state(account_id,key_secret) VALUES($1,$2) ON CONFLICT DO NOTHING",
        [actor.account.id, randomBytes(32)],
      );
      const stored = await client.query(
        "SELECT revision::text,log_floor::text AS floor,key_secret AS secret FROM account_model_sync_state WHERE account_id=$1 FOR UPDATE",
        [actor.account.id],
      );
      const result = await run(client, stored.rows[0] as State);
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
function keyFor(state: State, provider: string, model: string): string {
  return createHmac("sha256", state.secret)
    .update(JSON.stringify([provider, model]))
    .digest("hex");
}
function publicRow(
  row?: Record<string, unknown>,
  pair?: { provider: string; model: string },
  key?: string,
): ModelPreference {
  return {
    key: String(row?.key_hash ?? key),
    provider: String(row?.provider ?? pair?.provider),
    model: String(row?.model ?? pair?.model),
    favorite: Boolean(row?.favorite),
    reasoningPreset: (row?.reasoning ?? null) as ReasoningPreset | null,
    revision: String(row?.revision ?? "0"),
    favoriteRevision: String(row?.favorite_revision ?? "0"),
    reasoningRevision: String(row?.reasoning_revision ?? "0"),
  };
}
