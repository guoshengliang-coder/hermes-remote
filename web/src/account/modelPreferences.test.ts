import { createHash, webcrypto } from "node:crypto";
import { IDBFactory, IDBObjectStore } from "fake-indexeddb";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { GatewayClient } from "../api/gateway";
import {
  AccountModelPreferences,
  clearModelPreferences,
  legacyModelCandidates,
  modelTuple,
  projectedModelPrefs,
  type ModelOperation,
  type ModelPreference,
  type ModelSnapshot,
  type ModelSyncView,
} from "./modelPreferences";

const workers: AccountModelPreferences[] = [];
beforeEach(() => {
  vi.stubGlobal("indexedDB", new IDBFactory());
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal("BroadcastChannel", undefined);
  localStorage.clear();
});
afterEach(() => {
  workers.splice(0).forEach((w) => w.stop());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
function fake() {
  let seq = 0n;
  let offline = false;
  let loseAck = false;
  const rows = new Map<string, ModelPreference>(),
    log: ModelPreference[] = [],
    ops = new Map<
      string,
      { status: "accepted" | "conflict"; row: ModelPreference }
    >();
  const key = (p: string, m: string) =>
    createHash("sha256").update(modelTuple(p, m)).digest("hex");
  const row = (p: string, m: string): ModelPreference =>
    rows.get(modelTuple(p, m)) ?? {
      key: key(p, m),
      provider: p,
      model: m,
      favorite: false,
      reasoningPreset: null,
      revision: "0",
      favoriteRevision: "0",
      reasoningRevision: "0",
    };
  const api = {
    modelPreferences: vi.fn(
      async (_owner: string, cursor?: string): Promise<ModelSnapshot> => {
        if (offline) throw new TypeError("offline");
        return {
          version: 1,
          revision: String(seq),
          cursor: `cursor:${seq}`,
          lease: "lease",
          hasMore: false,
          rows: cursor
            ? log.filter(
                (r) => BigInt(r.revision) > BigInt(cursor.split(":")[1]!),
              )
            : Array.from(rows.values()).filter(
                (r) => r.favorite || r.reasoningPreset !== null,
              ),
        };
      },
    ),
    resolveModelPreference: vi.fn(
      async (_owner: string, p: string, m: string) => {
        if (offline) throw new TypeError("offline");
        return { row: row(p, m) };
      },
    ),
    setModelPreference: vi.fn(async (_owner: string, op: ModelOperation) => {
      if (offline) throw new TypeError("offline");
      if (ops.has(op.opId)) return ops.get(op.opId)!;
      const before = row(op.provider, op.model),
        field =
          op.field === "favorite" ? "favoriteRevision" : "reasoningRevision";
      const next =
        before[field] === op.baseRevision
          ? {
              ...before,
              [op.field]: op.value,
              revision: String(++seq),
              [field]: String(seq),
            }
          : before;
      const result = {
        status: (next === before ? "conflict" : "accepted") as
          | "accepted"
          | "conflict",
        row: next,
      };
      if (next !== before) {
        rows.set(modelTuple(op.provider, op.model), next);
        log.push(next);
      }
      ops.set(op.opId, result);
      if (loseAck) throw new TypeError("lost ACK");
      return result;
    }),
  };
  return {
    api,
    rows,
    ops,
    row,
    set offline(v: boolean) {
      offline = v;
    },
    set loseAck(v: boolean) {
      loseAck = v;
    },
  };
}
function setup(
  api: ReturnType<typeof fake>["api"],
  owner = "a",
  enabled = true,
) {
  const publish = vi.fn<(view: ModelSyncView | undefined) => void>();
  const worker = new AccountModelPreferences(
    api as unknown as GatewayClient,
    owner,
    enabled,
    publish,
  );
  workers.push(worker);
  return { worker, publish, view: () => publish.mock.calls.at(-1)?.[0] };
}
const favorite = (view: ModelSyncView | undefined) =>
  view && projectedModelPrefs(view).some((r) => r.favorite);

it("persists offline intent, restarts with the original opId/base and exposes a conflict without rebasing", async () => {
  const f = fake(),
    one = setup(f.api);
  await one.worker.start();
  f.offline = true;
  expect(await one.worker.set("p", "m", "favorite", true)).toBe(true);
  await one.worker.refresh();
  expect(favorite(one.view())).toBe(true);
  const original = one.view()!.pending[0]!.operation;
  one.worker.stop();
  f.offline = false;
  await f.api.setModelPreference("a", {
    ...original,
    opId: crypto.randomUUID(),
    value: false,
  });
  const two = setup(f.api);
  await two.worker.start();
  expect(f.api.setModelPreference.mock.calls.at(-1)?.[1]).toEqual(original);
  expect(two.view()!.pending[0]!.error?.code).toBe("HR-SYNC-012");
  expect(favorite(two.view())).toBe(false);
  const count = f.api.setModelPreference.mock.calls.length;
  await two.worker.refresh();
  expect(f.api.setModelPreference).toHaveBeenCalledTimes(count);
  await two.worker.reapply(original.opId);
  expect(favorite(two.view())).toBe(true);
  expect(f.api.setModelPreference.mock.calls.at(-1)?.[1].opId).not.toBe(
    original.opId,
  );
  expect(two.view()!.pending).toHaveLength(0);
});
it("a lost ACK replays the same operation and a stale successful ACK never overwrites a later cloud cancel", async () => {
  const f = fake(),
    one = setup(f.api);
  await one.worker.start();
  f.loseAck = true;
  await one.worker.set("p", "m", "favorite", true);
  await one.worker.refresh();
  const original = one.view()!.pending[0]!.operation;
  one.worker.stop();
  f.loseAck = false;
  await f.api.setModelPreference("a", {
    ...original,
    opId: crypto.randomUUID(),
    value: false,
    baseRevision: "1",
  });
  const two = setup(f.api);
  await two.worker.start();
  expect(two.view()!.pending).toHaveLength(0);
  expect(favorite(two.view())).toBe(false);
  expect(f.api.setModelPreference.mock.calls.at(-1)?.[1]).toEqual(original);
});
it("failed outbox commits send no mutation and show no optimistic preference", async () => {
  const f = fake(),
    one = setup(f.api);
  await one.worker.start();
  const put = IDBObjectStore.prototype.put;
  vi.spyOn(IDBObjectStore.prototype, "put").mockImplementation(function (
    this: IDBObjectStore,
    value,
    key,
  ) {
    if (Object.keys(value.pending ?? {}).length)
      throw new DOMException("full", "QuotaExceededError");
    return put.call(this, value, key);
  });
  expect(await one.worker.set("p", "m", "favorite", true)).toBe(false);
  expect(f.api.setModelPreference).not.toHaveBeenCalled();
  expect(favorite(one.view())).toBe(false);
  expect(one.view()!.error?.code).toBe("HR-SYNC-017");
});
it("account keys, logout generation and late responses cannot repopulate a cleared store", async () => {
  const f = fake(),
    one = setup(f.api);
  await one.worker.start();
  await one.worker.set("p", "m", "favorite", true);
  await one.worker.refresh();
  const other = setup(fake().api, "b");
  await other.worker.start();
  expect(favorite(other.view())).toBe(false);
  let finish!: (v: ModelSnapshot) => void;
  f.api.modelPreferences.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const flight = one.worker.refresh();
  await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
  const previous = one.publish.mock.calls.length;
  await clearModelPreferences();
  finish({
    version: 1,
    revision: "100",
    lease: "late",
    cursor: "late",
    rows: [{ ...f.row("p", "m"), revision: "100" }],
    hasMore: false,
  });
  await flight;
  expect(one.publish.mock.calls.length).toBe(previous);
  const fresh = setup(fake().api);
  await fresh.worker.start();
  expect(favorite(fresh.view())).toBe(false);
});
it("two tabs durably claim a single field; another field stays editable and duplicate sends keep opIds", async () => {
  const f = fake(),
    one = setup(f.api),
    two = setup(f.api);
  await one.worker.start();
  await two.worker.start();
  f.offline = true;
  const result = await Promise.all([
    one.worker.set("p", "m", "favorite", true),
    two.worker.set("p", "m", "favorite", false),
  ]);
  expect(result.filter(Boolean)).toHaveLength(1);
  expect(await two.worker.set("p", "m", "reasoningPreset", "high")).toBe(true);
  f.offline = false;
  await Promise.all([one.worker.refresh(), two.worker.refresh()]);
  await one.worker.refresh();
  expect(one.view()!.pending).toHaveLength(0);
  expect(f.ops.size).toBe(2);
  expect(f.row("p", "m").reasoningPreset).toBe("high");
});
it("legacy slash ambiguity is quarantined; same model under distinct provider tuples never collides", () => {
  expect(modelTuple("a/b", "c")).not.toBe(modelTuple("a", "b/c"));
  const result = legacyModelCandidates(
    { favorites: ["a/b/c", "p/m", "missing"], presets: { "p/m": "ultra" } },
    [
      { provider: "a/b", model: "c" },
      { provider: "a", model: "b/c" },
      { provider: "p", model: "m" },
    ],
  );
  expect(result.unresolved).toBe(2);
  expect(result.candidates).toEqual([
    { provider: "p", model: "m", favorite: true, reasoningPreset: "ultra" },
  ]);
});
it("explicit imports use stable per-item IDs, base zero and durable completion markers", async () => {
  const f = fake(),
    one = setup(f.api);
  await one.worker.start();
  f.offline = true;
  const legacy = [
    {
      provider: "p",
      model: "m",
      favorite: true as const,
      reasoningPreset: "high" as const,
    },
  ];
  await one.worker.importLegacy(legacy, "same-install");
  await one.worker.refresh();
  const operations = one.view()!.pending.map((p) => p.operation);
  expect(operations).toHaveLength(2);
  expect(operations.every((p) => p.baseRevision === "0")).toBe(true);
  one.worker.stop();
  f.offline = false;
  const two = setup(f.api);
  await two.worker.start();
  expect(two.view()!.pending).toHaveLength(0);
  const before = f.api.setModelPreference.mock.calls.length;
  await two.worker.importLegacy(legacy, "same-install");
  await two.worker.refresh();
  expect(f.api.setModelPreference.mock.calls.length).toBe(before);
});
it("closing the capability retains account-private state and queued intents without legacy fallback or sends", async () => {
  const f = fake(),
    one = setup(f.api);
  await one.worker.start();
  f.offline = true;
  await one.worker.set("p", "m", "favorite", true);
  await one.worker.refresh();
  one.worker.stop();
  const paused = setup(f.api, "a", false);
  await paused.worker.start();
  const before = f.api.setModelPreference.mock.calls.length;
  expect(paused.view()!.enabled).toBe(false);
  expect(favorite(paused.view())).toBe(true);
  expect(await paused.worker.set("p", "m", "favorite", false)).toBe(false);
  await paused.worker.refresh();
  expect(f.api.setModelPreference.mock.calls.length).toBe(before);
});
it("unknown or imprecise wire versions do not publish preferences", async () => {
  const f = fake();
  f.api.modelPreferences.mockResolvedValueOnce({
    version: 1,
    revision: 9007199254740993,
    rows: [],
    cursor: "bad",
    lease: "lease",
    hasMore: false,
  } as unknown as ModelSnapshot);
  const one = setup(f.api);
  await one.worker.start();
  expect(one.view()!.error?.code).toBe("HR-SYNC-013");
  expect(one.view()!.ready).toBe(false);
});

it("does not fall back to local preferences when a disabled capability cannot inspect its private cache", async () => {
  vi.stubGlobal("indexedDB", {
    open() {
      throw new Error("storage denied");
    },
  });
  const f = fake(),
    s = setup(f.api, "a", false);
  await s.worker.start();
  expect(s.view()?.enabled).toBe(false);
  expect(s.view()?.error?.code).toBe("HR-SYNC-017");
  expect(f.api.modelPreferences).not.toHaveBeenCalled();
  expect(f.api.setModelPreference).not.toHaveBeenCalled();
});
