import {
  GatewayNetworkError,
  GatewayHttpError,
  type GatewayClient,
} from "../api/gateway";
import { appError, type AppError } from "../errors";
import { isAppError, toAppError } from "../app/failures";
import { REASONING_VALUES, type ReasoningValue } from "../hermes/params";
import { PROFILE_SYNC_LOGOUT } from "./profileSync";

export interface ModelPreference {
  key: string;
  provider: string;
  model: string;
  favorite: boolean;
  reasoningPreset: ReasoningValue | null;
  revision: string;
  favoriteRevision: string;
  reasoningRevision: string;
}
export interface ModelOperation {
  opId: string;
  lease: string;
  provider: string;
  model: string;
  field: "favorite" | "reasoningPreset";
  baseRevision: string;
  value: boolean | ReasoningValue | null;
}
export interface ModelSnapshot {
  version: 1;
  revision: string;
  cursor: string;
  lease: string;
  rows: ModelPreference[];
  hasMore: boolean;
}
export interface PendingModelPreference {
  operation: ModelOperation;
  error?: AppError;
  migrationId?: string;
}
interface Stored {
  generation: string;
  revision: string;
  cursor: string;
  lease: string;
  rows: Record<string, ModelPreference>;
  pending: Record<string, PendingModelPreference>;
  completedOps?: string[];
}
export interface ModelSyncView {
  owner: string;
  enabled: boolean;
  ready: boolean;
  rows: ModelPreference[];
  pending: PendingModelPreference[];
  error: AppError | null;
}
const DATABASE = "hermes-go.account-model-sync.v1";
export const modelTuple = (provider: string, model: string): string =>
  JSON.stringify([provider, model]);
const fieldKey = (op: Pick<ModelOperation, "provider" | "model" | "field">) =>
  JSON.stringify([op.provider, op.model, op.field]);
const generation = () => {
  try {
    return localStorage.getItem(PROFILE_SYNC_LOGOUT) ?? "initial";
  } catch {
    return "initial";
  }
};
function revision(v: unknown): v is string {
  return (
    typeof v === "string" &&
    /^(0|[1-9][0-9]{0,19})$/.test(v) &&
    BigInt(v) <= 18446744073709551615n
  );
}
function validRow(row: ModelPreference, upper: string): boolean {
  return (
    !!row &&
    typeof row === "object" &&
    typeof row.key === "string" &&
    /^[a-f0-9]{64}$/.test(row.key) &&
    typeof row.provider === "string" &&
    row.provider.length > 0 &&
    row.provider.length <= 256 &&
    !/[\u0000-\u001f\u007f]/.test(row.provider) &&
    typeof row.model === "string" &&
    row.model.length > 0 &&
    row.model.length <= 1024 &&
    !/[\u0000-\u001f\u007f]/.test(row.model) &&
    typeof row.favorite === "boolean" &&
    (row.reasoningPreset === null ||
      REASONING_VALUES.includes(row.reasoningPreset)) &&
    revision(row.revision) &&
    revision(row.favoriteRevision) &&
    revision(row.reasoningRevision) &&
    BigInt(row.revision) <= BigInt(upper) &&
    BigInt(row.favoriteRevision) <= BigInt(row.revision) &&
    BigInt(row.reasoningRevision) <= BigInt(row.revision)
  );
}
function validate(snapshot: ModelSnapshot, full: boolean) {
  if (
    !snapshot ||
    snapshot.version !== 1 ||
    !revision(snapshot.revision) ||
    typeof snapshot.cursor !== "string" ||
    !snapshot.cursor.length ||
    snapshot.cursor.length > 512 ||
    typeof snapshot.lease !== "string" ||
    !snapshot.lease.length ||
    snapshot.lease.length > 512 ||
    typeof snapshot.hasMore !== "boolean" ||
    (full && snapshot.hasMore) ||
    !Array.isArray(snapshot.rows) ||
    snapshot.rows.length > (full ? 500 : 200) ||
    snapshot.rows.some((r) => !validRow(r, snapshot.revision))
  )
    throw appError("HR-SYNC-013");
  if (
    full &&
    new Set(snapshot.rows.map((r) => modelTuple(r.provider, r.model))).size !==
      snapshot.rows.length
  )
    throw appError("HR-SYNC-013");
  if (
    !full &&
    snapshot.rows.some(
      (r, i) =>
        i > 0 && BigInt(r.revision) <= BigInt(snapshot.rows[i - 1]!.revision),
    )
  )
    throw appError("HR-SYNC-013");
}
function validateStored(s: Stored) {
  if (
    !s ||
    !revision(s.revision) ||
    typeof s.generation !== "string" ||
    !s.generation ||
    typeof s.cursor !== "string" ||
    s.cursor.length > 512 ||
    typeof s.lease !== "string" ||
    s.lease.length > 512 ||
    !s.rows ||
    !s.pending ||
    Object.keys(s.rows).length > 500 ||
    Object.keys(s.pending).length > 1000 ||
    (s.completedOps &&
      (!Array.isArray(s.completedOps) || s.completedOps.length > 10_000))
  )
    throw appError("HR-SYNC-017");
  if (
    Object.entries(s.rows).some(
      ([k, r]) =>
        !validRow(r, s.revision) || k !== modelTuple(r.provider, r.model),
    )
  )
    throw appError("HR-SYNC-017");
  for (const [k, p] of Object.entries(s.pending)) {
    const op = p?.operation;
    if (
      !op ||
      typeof op.opId !== "string" ||
      !/^[a-f0-9-]{36}$/i.test(op.opId) ||
      !revision(op.baseRevision) ||
      typeof op.lease !== "string" ||
      !op.lease ||
      op.lease.length > 512 ||
      typeof op.provider !== "string" ||
      !op.provider.trim() ||
      op.provider.length > 256 ||
      typeof op.model !== "string" ||
      !op.model.trim() ||
      op.model.length > 1024 ||
      /[\u0000-\u001f\u007f]/.test(op.provider + op.model) ||
      (op.field === "favorite"
        ? typeof op.value !== "boolean"
        : op.field !== "reasoningPreset" ||
          (op.value !== null &&
            !REASONING_VALUES.includes(op.value as ReasoningValue))) ||
      k !== fieldKey(op) ||
      (p.error && !isAppError(p.error))
    )
      throw appError("HR-SYNC-017");
  }
}
function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("accounts");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("storage blocked"));
  });
}
/** Account snapshot + outbox changes commit atomically, including across browser tabs. */
async function transaction<T>(
  key: string,
  active: () => boolean,
  run: (record: Stored | undefined) => { record?: Stored; value: T },
): Promise<T> {
  const db = await open().catch(() => {
    throw appError("HR-SYNC-017");
  });
  try {
    return await new Promise<T>((resolve, reject) => {
      const tx = db.transaction("accounts", "readwrite");
      const store = tx.objectStore("accounts");
      let output: T;
      const get = store.get(key);
      get.onsuccess = () => {
        try {
          if (!active()) {
            tx.abort();
            return;
          }
          const saved = get.result as Stored | undefined;
          if (saved) validateStored(saved);
          const result = run(saved);
          output = result.value;
          if (result.record) store.put(result.record, key);
        } catch (e) {
          reject(e);
          tx.abort();
        }
      };
      tx.oncomplete = () => resolve(output);
      tx.onabort = () => reject(tx.error ?? appError("HR-SYNC-017"));
    });
  } catch (e) {
    if (
      e &&
      typeof e === "object" &&
      "code" in e &&
      String(e.code).startsWith("HR-")
    )
      throw e;
    throw appError("HR-SYNC-017");
  } finally {
    db.close();
  }
}
export async function clearModelPreferences(broadcast = true): Promise<void> {
  if (broadcast)
    try {
      localStorage.setItem(PROFILE_SYNC_LOGOUT, crypto.randomUUID());
    } catch {
      /* generation checked again before every commit */
    }
  const db = await open();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("accounts", "readwrite");
      tx.objectStore("accounts").clear();
      tx.oncomplete = () => resolve();
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

export class AccountModelPreferences {
  private active = true;
  private readonly epoch = generation();
  private readonly key: string;
  private flight: Promise<void> | null = null;
  private error: AppError | null = null;
  private record?: Stored;
  private readonly timer: ReturnType<typeof setInterval>;
  private failures = 0;
  private nextAttempt = 0;
  private readonly wake = () => {
    if (document.visibilityState !== "hidden" && Date.now() >= this.nextAttempt)
      void this.refresh();
  };
  private readonly logout = (event: StorageEvent) => {
    if (event.key === PROFILE_SYNC_LOGOUT) this.stop();
  };
  private readonly changed = () => {
    void this.load().then(
      () => this.publish(),
      () => this.storageFailure(),
    );
  };
  private readonly channel?: BroadcastChannel;
  constructor(
    private readonly client: GatewayClient,
    private readonly owner: string,
    readonly enabled: boolean,
    private readonly publishView: (view: ModelSyncView | undefined) => void,
  ) {
    this.key = JSON.stringify([location.origin, owner]);
    if (typeof BroadcastChannel !== "undefined") {
      this.channel = new BroadcastChannel("hermes-model-preferences");
      this.channel.onmessage = this.changed;
    }
    window.addEventListener("online", this.wake);
    window.addEventListener("storage", this.logout);
    document.addEventListener("visibilitychange", this.wake);
    this.timer = setInterval(this.wake, 5000);
  }
  private isActive = () => this.active && generation() === this.epoch;
  stop() {
    this.active = false;
    clearInterval(this.timer);
    window.removeEventListener("online", this.wake);
    window.removeEventListener("storage", this.logout);
    document.removeEventListener("visibilitychange", this.wake);
    this.channel?.close();
  }
  async start() {
    try {
      await this.load();
      this.publish();
      if (this.enabled) await this.refresh();
    } catch {
      this.storageFailure();
    }
  }
  private async load() {
    this.record = await transaction(this.key, this.isActive, (record) => ({
      value: record?.generation === this.epoch ? record : undefined,
    }));
  }
  private async change(run: (record: Stored) => void) {
    this.record = await transaction(this.key, this.isActive, (stored) => {
      const record = stored?.generation === this.epoch ? stored : undefined;
      if (!record) throw appError("HR-SYNC-013");
      run(record);
      return { record, value: record };
    });
    this.publish();
    this.channel?.postMessage("changed");
  }
  private publish() {
    if (!this.isActive()) return;
    const s = this.record;
    this.publishView(
      !this.enabled && !s && !this.error
        ? undefined
        : {
            owner: this.owner,
            enabled: this.enabled,
            ready: !!s?.cursor,
            rows: s ? Object.values(s.rows) : [],
            pending: s ? Object.values(s.pending) : [],
            error: this.error,
          },
    );
  }
  private storageFailure() {
    if (!this.isActive()) return;
    this.error = appError("HR-SYNC-017");
    this.publish();
  }
  async set(
    provider: string,
    model: string,
    field: ModelOperation["field"],
    value: ModelOperation["value"],
  ): Promise<boolean> {
    if (!this.isActive() || !this.enabled) return false;
    try {
      let resolved: ModelPreference | undefined;
      try {
        resolved = (
          await this.client.resolveModelPreference(this.owner, provider, model)
        ).row;
        if (
          !validRow(resolved, "18446744073709551615") ||
          resolved.provider !== provider ||
          resolved.model !== model
        )
          throw appError("HR-SYNC-013");
      } catch (e) {
        if (!(e instanceof GatewayNetworkError) && !(e instanceof TypeError))
          throw e; /* offline uses the last durable base, never an invented rebase */
      }
      const opId = crypto.randomUUID();
      await this.change((s) => {
        if (!s.lease) throw appError("HR-SYNC-013");
        const k = fieldKey({ provider, model, field });
        if (s.pending[k]) throw appError("HR-SYNC-016");
        if (Object.keys(s.pending).length >= 1000)
          throw appError("HR-SYNC-015");
        const before = resolved ?? s.rows[modelTuple(provider, model)];
        const operation: ModelOperation = {
          opId,
          lease: s.lease,
          provider,
          model,
          field,
          value,
          baseRevision:
            field === "favorite"
              ? (before?.favoriteRevision ?? "0")
              : (before?.reasoningRevision ?? "0"),
        };
        s.pending[k] = { operation };
      });
      this.error = null;
      this.publish();
      void this.refresh();
      return true;
    } catch (e) {
      if (this.isActive()) {
        this.error = toAppError(e, "account");
        this.publish();
      }
      return false;
    }
  }
  async discard(opId: string) {
    try {
      await this.change((s) => {
        for (const [k, p] of Object.entries(s.pending))
          if (p.operation.opId === opId) delete s.pending[k];
      });
      this.error = null;
      await this.refresh();
    } catch {
      this.storageFailure();
    }
  }
  async reapply(opId: string) {
    const p =
      this.record &&
      Object.values(this.record.pending).find((p) => p.operation.opId === opId);
    if (!p) return;
    const { provider, model, field, value } = p.operation;
    // Persist a new intent atomically; if storage or resolve fails the old conflict survives.
    try {
      const { row } = await this.client.resolveModelPreference(
        this.owner,
        provider,
        model,
      );
      if (
        !validRow(row, "18446744073709551615") ||
        row.provider !== provider ||
        row.model !== model
      )
        throw appError("HR-SYNC-013");
      await this.change((s) => {
        const k = fieldKey(p.operation);
        if (s.pending[k]?.operation.opId !== opId) return;
        s.pending[k] = {
          operation: {
            ...p.operation,
            opId: crypto.randomUUID(),
            lease: s.lease,
            baseRevision:
              field === "favorite"
                ? row.favoriteRevision
                : row.reasoningRevision,
            value,
          },
        };
      });
      this.error = null;
      await this.refresh();
    } catch (e) {
      if (this.isActive()) {
        this.error = toAppError(e, "account");
        this.publish();
      }
    }
  }
  async importLegacy(
    candidates: Array<{
      provider: string;
      model: string;
      favorite?: true;
      reasoningPreset?: ReasoningValue;
    }>,
    source: string,
  ) {
    if (!this.enabled || !this.isActive()) return;
    try {
      const canonical = JSON.stringify(
        [...candidates].sort((a, b) =>
          modelTuple(a.provider, a.model).localeCompare(
            modelTuple(b.provider, b.model),
          ),
        ),
      );
      const digest = Array.from(
        new Uint8Array(
          await crypto.subtle.digest(
            "SHA-256",
            new TextEncoder().encode(
              JSON.stringify([this.key, source, 1, canonical]),
            ),
          ),
        ),
      )
        .map((n) => n.toString(16).padStart(2, "0"))
        .join("");
      const migrationId = `models-v1-${digest}`;
      const operations: Array<{
        operation: ModelOperation;
        migrationId: string;
      }> = [];
      for (const candidate of candidates)
        for (const field of ["favorite", "reasoningPreset"] as const) {
          const value =
            field === "favorite"
              ? candidate.favorite
              : candidate.reasoningPreset;
          if (value === undefined) continue;
          const bytes = new Uint8Array(
            await crypto.subtle.digest(
              "SHA-256",
              new TextEncoder().encode(
                JSON.stringify([
                  migrationId,
                  candidate.provider,
                  candidate.model,
                  field,
                ]),
              ),
            ),
          );
          bytes[6] = (bytes[6]! & 15) | 80;
          bytes[8] = (bytes[8]! & 63) | 128;
          const hex = Array.from(bytes.slice(0, 16))
            .map((n) => n.toString(16).padStart(2, "0"))
            .join("");
          operations.push({
            migrationId,
            operation: {
              opId: `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`,
              lease: "",
              provider: candidate.provider,
              model: candidate.model,
              field,
              value,
              baseRevision: "0",
            },
          });
        }
      await this.change((s) => {
        if (!s.lease) throw appError("HR-SYNC-013");
        for (const p of operations) {
          const k = fieldKey(p.operation);
          if (s.pending[k]) continue;
          if (Object.keys(s.pending).length >= 1000)
            throw appError("HR-SYNC-015");
          if (s.completedOps?.includes(p.operation.opId)) continue;
          s.pending[k] = {
            ...p,
            operation: { ...p.operation, lease: s.lease },
          };
        }
      });
      this.error = null;
      void this.refresh();
    } catch (e) {
      if (this.isActive()) {
        this.error = toAppError(e, "account");
        this.publish();
      }
    }
  }
  async refresh(): Promise<void> {
    if (this.flight) return this.flight;
    if (!this.isActive() || !this.enabled) return;
    const work = this.run();
    this.flight = work;
    try {
      await work;
    } finally {
      if (this.flight === work) this.flight = null;
    }
  }
  private async pull() {
    for (let page = 0; page < 64; page++) {
      await this.load();
      if (!this.isActive()) return;
      const cursor = this.record?.cursor || undefined;
      let snapshot: ModelSnapshot;
      try {
        snapshot = await this.client.modelPreferences(this.owner, cursor);
      } catch (e) {
        if (
          e instanceof GatewayHttpError &&
          e.code === "HR-SYNC-013" &&
          cursor
        ) {
          snapshot = await this.client.modelPreferences(this.owner);
          validate(snapshot, true);
          await this.install(snapshot);
          return;
        }
        throw e;
      }
      validate(snapshot, !cursor);
      if (!this.isActive()) return;
      if (!cursor) {
        await this.install(snapshot);
        return;
      }
      await this.change((s) => {
        if (s.cursor !== cursor) return;
        for (const row of snapshot.rows) {
          const key = modelTuple(row.provider, row.model);
          if (
            s.rows[key] &&
            BigInt(s.rows[key].revision) > BigInt(row.revision)
          )
            continue;
          if (row.favorite || row.reasoningPreset !== null) s.rows[key] = row;
          else delete s.rows[key];
        }
        s.revision = snapshot.hasMore
          ? (snapshot.rows.at(-1)?.revision ?? s.revision)
          : snapshot.revision;
        s.cursor = snapshot.cursor;
        s.lease = snapshot.lease;
      });
      if (!snapshot.hasMore) return;
    }
    throw appError("HR-SYNC-013");
  }
  private async install(snapshot: ModelSnapshot) {
    this.record = await transaction(this.key, this.isActive, (saved) => {
      const before = saved?.generation === this.epoch ? saved : undefined;
      if (before && BigInt(before.revision) > BigInt(snapshot.revision))
        return { value: before };
      const record: Stored = {
        generation: this.epoch,
        revision: snapshot.revision,
        cursor: snapshot.cursor,
        lease: snapshot.lease,
        rows: Object.fromEntries(
          snapshot.rows.map((r) => [modelTuple(r.provider, r.model), r]),
        ),
        pending: before?.pending ?? {},
        completedOps: before?.completedOps ?? [],
      };
      return { record, value: record };
    });
    this.publish();
    this.channel?.postMessage("changed");
  }
  private async run() {
    try {
      await this.pull();
      for (let i = 0; i < 1000 && this.isActive(); i++) {
        await this.load();
        const pending =
          this.record &&
          Object.values(this.record.pending).find((p) => !p.error);
        if (!pending) break;
        const op = pending.operation;
        try {
          const result = await this.client.setModelPreference(this.owner, op);
          if (
            !result ||
            !["accepted", "conflict"].includes(result.status) ||
            !validRow(result.row, "18446744073709551615") ||
            result.row.provider !== op.provider ||
            result.row.model !== op.model
          )
            throw appError("HR-SYNC-013");
          await this.change((s) => {
            const k = fieldKey(op);
            if (s.pending[k]?.operation.opId !== op.opId) return;
            if (result.status === "accepted") {
              if (s.pending[k].migrationId)
                s.completedOps = [...(s.completedOps ?? []), op.opId].slice(
                  -10_000,
                );
              delete s.pending[k];
            } else s.pending[k].error = appError("HR-SYNC-012");
          });
        } catch (e) {
          if (!this.isActive()) return;
          const error = toAppError(e, "account");
          if (
            e instanceof GatewayHttpError &&
            (e.status === 401 || e.status === 403)
          )
            throw e;
          if (!error.retryable) {
            await this.change((s) => {
              const k = fieldKey(op);
              if (s.pending[k]?.operation.opId === op.opId)
                s.pending[k].error = error;
            });
            continue;
          }
          throw e;
        }
      }
      await this.pull();
      this.error = null;
      this.failures = 0;
      this.nextAttempt = 0;
      this.publish();
    } catch (e) {
      if (!this.isActive()) return;
      this.error = toAppError(e, "account");
      this.nextAttempt =
        Date.now() + Math.min(60_000, 5000 * 2 ** Math.min(this.failures++, 4));
      this.publish();
    }
  }
}

export function projectedModelPrefs(view: ModelSyncView) {
  const rows = new Map(
    view.rows.map((r) => [
      modelTuple(r.provider, r.model),
      {
        provider: r.provider,
        model: r.model,
        favorite: r.favorite,
        reasoningPreset: r.reasoningPreset,
      },
    ]),
  );
  for (const p of view.pending) {
    if (p.error) continue;
    const op = p.operation,
      key = modelTuple(op.provider, op.model);
    const row = rows.get(key) ?? {
      provider: op.provider,
      model: op.model,
      favorite: false,
      reasoningPreset: null,
    };
    if (op.field === "favorite") row.favorite = op.value as boolean;
    else row.reasoningPreset = op.value as ReasoningValue | null;
    rows.set(key, row);
  }
  return [...rows.values()];
}

/** Legacy slash keys are accepted only when exactly one actual catalog tuple matches. */
export function legacyModelCandidates(
  prefs: { favorites: string[]; presets: Record<string, string> },
  catalog: Array<{ provider: string; model: string }>,
) {
  const byLegacy = new Map<
    string,
    Array<{ provider: string; model: string }>
  >();
  for (const pair of catalog) {
    const key = `${pair.provider}/${pair.model}`;
    const matches = byLegacy.get(key) ?? [];
    if (
      !matches.some(
        (p) =>
          modelTuple(p.provider, p.model) ===
          modelTuple(pair.provider, pair.model),
      )
    )
      matches.push(pair);
    byLegacy.set(key, matches);
  }
  const keys = new Set([...prefs.favorites, ...Object.keys(prefs.presets)]);
  let unresolved = 0;
  const candidates: Array<{
    provider: string;
    model: string;
    favorite?: true;
    reasoningPreset?: ReasoningValue;
  }> = [];
  for (const key of keys) {
    const matches = byLegacy.get(key);
    if (matches?.length !== 1) {
      unresolved++;
      continue;
    }
    const preset = prefs.presets[key];
    const reasoning = REASONING_VALUES.includes(preset as ReasoningValue)
      ? (preset as ReasoningValue)
      : undefined;
    if (prefs.favorites.includes(key) || reasoning)
      candidates.push({
        ...matches[0]!,
        ...(prefs.favorites.includes(key) ? { favorite: true as const } : {}),
        ...(reasoning ? { reasoningPreset: reasoning } : {}),
      });
    else unresolved++;
  }
  return { candidates, unresolved };
}
