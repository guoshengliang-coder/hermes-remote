import { Blob as NodeBlob } from "node:buffer";
import { IDBFactory, IDBObjectStore } from "fake-indexeddb";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { GatewayHttpError, type AccountProfileSnapshot, type GatewayClient } from "../api/gateway";
import { AccountProfileSyncClient, clearAccountProfileSync, type ProfileSyncStatus } from "./profileSync";
import { appError, diagnostics, localized } from "../errors";

const workers: AccountProfileSyncClient[] = [];
beforeEach(() => { vi.stubGlobal("indexedDB", new IDBFactory()); vi.stubGlobal("Blob", NodeBlob); localStorage.clear(); });
afterEach(() => { workers.splice(0).forEach((worker) => worker.stop()); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
function snapshot(name = "Original", revision = "1", owner = "account-a"): AccountProfileSnapshot {
  return { version: 1, revision, cursor: `cursor-${revision}`, hasMore: false,
    profile: { nameRevision: revision, avatarRevision: "0", account: { id: owner, displayName: name } } };
}
function setup(client: Partial<GatewayClient>, owner = "account-a") {
  const publish = vi.fn(); const statuses: ProfileSyncStatus[] = [];
  const worker = new AccountProfileSyncClient(client as GatewayClient, owner, publish, (s) => statuses.push(s));
  workers.push(worker);
  return { worker, publish, statuses };
}
const conflict = () => new GatewayHttpError(409, { error: { code: "HR-SYNC-006", retryable: false, message: "not a primary UI string" } }, "/sync");

it("persists before sending, restarts with the same opId/base, and never rebases an offline edit", async () => {
  let cloud = snapshot(); let offline = false;
  const operations: unknown[] = [];
  const api = {
    accountProfile: vi.fn(async () => { if (offline) throw new TypeError("offline"); return cloud; }),
    setAccountProfile: vi.fn(async (_owner: string, operation: unknown) => { operations.push(operation); throw new TypeError("lost response"); }),
  };
  const first = setup(api as unknown as Partial<GatewayClient>);
  await first.worker.start(); offline = true;
  expect(await first.worker.save("Offline name", undefined, "1")).toBe(false);
  first.worker.stop();
  offline = false; cloud = snapshot("Another device", "2");
  api.setAccountProfile.mockImplementation(async (_owner, operation) => { operations.push(operation); throw conflict(); });
  const second = setup(api as unknown as Partial<GatewayClient>);
  await second.worker.start();
  expect(operations[1]).toEqual(operations[0]);
  expect(operations[1]).toMatchObject({ baseRevision: "1", displayName: "Offline name" });
  expect(second.statuses.at(-1)?.error?.code).toBe("HR-SYNC-006");
  const calls = api.setAccountProfile.mock.calls.length;
  await second.worker.refresh();
  expect(api.setAccountProfile.mock.calls.length).toBe(calls); // Terminal conflict is never replayed.
  expect(second.publish.mock.calls.at(-1)?.[0].profile.account.displayName).toBe("Another device");
});

it("lost ACK after a committed edit replays the original operation instead of creating another", async () => {
  let cloud = snapshot(); let lose = true;
  const setAccountProfile = vi.fn(async (_owner, op) => {
    cloud = snapshot(op.displayName, "2");
    if (lose) throw new TypeError("connection closed");
    return { status: "accepted", revision: "2", nameRevision: "2", avatarRevision: "0" };
  });
  const client = { accountProfile: vi.fn(async () => cloud), setAccountProfile };
  const { worker, publish } = setup(client as unknown as Partial<GatewayClient>);
  await worker.start(); expect(await worker.save("New", undefined, "1")).toBe(false);
  lose = false; await worker.refresh();
  expect(setAccountProfile.mock.calls[0]![1]).toEqual(setAccountProfile.mock.calls[1]![1]);
  expect(publish.mock.calls.at(-1)?.[0].profile.account.displayName).toBe("New");
});

it("stored avatar Blob and operation survive a worker restart", async () => {
  const avatar = new Blob([new Uint8Array([137, 80, 78, 71])], { type: "image/png" });
  const setAccountProfile = vi.fn(async () => { throw new TypeError("offline"); });
  const api = { accountProfile: vi.fn(async () => snapshot()), setAccountProfile };
  const first = setup(api as unknown as Partial<GatewayClient>); await first.worker.start();
  expect(await first.worker.save(undefined, avatar, "0")).toBe(false); first.worker.stop();
  const second = setup(api as unknown as Partial<GatewayClient>); await second.worker.start();
  expect(setAccountProfile.mock.calls.length).toBe(2);
  const calls = setAccountProfile.mock.calls as unknown[][];
  expect(calls[1]![2]).toBeInstanceOf(Blob);
  expect((calls[1]![2] as Blob).size).toBe(4);
});

it("persists and restores avatar bytes when IndexedDB rejects Blob values", async () => {
  const add = IDBObjectStore.prototype.add;
  vi.spyOn(IDBObjectStore.prototype, "add").mockImplementation(function (this: IDBObjectStore, value, key) {
    if (value.avatar instanceof Blob) throw new DOMException("Blob storage unavailable", "DataCloneError");
    return add.call(this, value, key);
  });
  const image = new Blob([new Uint8Array([137, 80, 78, 71])], { type: "image/png" });
  const calls: Blob[] = [];
  const api = { accountProfile: vi.fn(async () => snapshot()), setAccountProfile: vi.fn(async (_owner, _op, avatar: Blob) => {
    calls.push(avatar); throw new TypeError("offline");
  }) };
  const first = setup(api as unknown as Partial<GatewayClient>); await first.worker.start();
  expect(await first.worker.save(undefined, image, "0")).toBe(false);
  first.worker.stop();
  const second = setup(api as unknown as Partial<GatewayClient>); await second.worker.start();
  expect(calls).toHaveLength(2);
  expect(calls[1]!.type).toBe("image/png");
  expect(new Uint8Array(await calls[1]!.arrayBuffer())).toEqual(new Uint8Array([137, 80, 78, 71]));
});

it("upgrades the previous database without deleting its queued avatar or changing the operation", async () => {
  const operation = { opId: "legacy-avatar-operation", issuedAt: "2026-10-15T00:00:00Z", baseRevision: "0" };
  await new Promise<void>((resolve, reject) => {
    const open = indexedDB.open("hermes-go.account-profile-sync.v1", 1);
    open.onupgradeneeded = () => { open.result.createObjectStore("profiles"); open.result.createObjectStore("pending"); };
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction("pending", "readwrite");
      tx.objectStore("pending").put({ generation: "initial", operation, avatar: new Blob(["old bytes"], { type: "image/png" }) }, JSON.stringify([location.origin, "account-a"]));
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onabort = () => { db.close(); reject(tx.error); };
    };
  });
  const api = { accountProfile: vi.fn(async () => snapshot()), setAccountProfile: vi.fn(async () => {
    throw new TypeError("offline");
  }) };
  const { worker } = setup(api as unknown as Partial<GatewayClient>); await worker.start();
  const calls = api.setAccountProfile.mock.calls as unknown[][];
  expect(calls[0]![1]).toEqual(operation);
  expect(await (calls[0]![2] as Blob).text()).toBe("old bytes");
});

it("storage denial prevents mutation and reports a bilingual redacted actionable error", async () => {
  const setAccountProfile = vi.fn();
  const { worker } = setup({ accountProfile: vi.fn(async () => snapshot()), setAccountProfile });
  await worker.start(); vi.stubGlobal("indexedDB", { open: () => { throw new Error("quota"); } });
  await expect(worker.save("Never sent")).rejects.toMatchObject({ code: "HR-SYNC-011" });
  expect(setAccountProfile).not.toHaveBeenCalled();
  for (const code of ["HR-SYNC-006", "HR-SYNC-007", "HR-SYNC-008", "HR-SYNC-009", "HR-SYNC-010", "HR-SYNC-011"] as const) {
    const error = appError(code, "Authorization: Bearer private\nCookie: private");
    expect(localized(error, "zh")).not.toBe(localized(error, "en"));
    expect(diagnostics(error)).not.toContain("private");
    expect(error.retryable).toBe(code === "HR-SYNC-011");
  }
});

it("sign-out invalidates late writers and a later login cannot revive a pending edit", async () => {
  const api = { accountProfile: vi.fn(async () => snapshot()), setAccountProfile: vi.fn(async () => { throw new TypeError("offline"); }) };
  const first = setup(api as unknown as Partial<GatewayClient>); await first.worker.start();
  await first.worker.save("Old pending"); first.worker.stop();
  await clearAccountProfileSync();
  const calls = api.setAccountProfile.mock.calls.length;
  const next = setup(api as unknown as Partial<GatewayClient>); await next.worker.start();
  expect(api.setAccountProfile.mock.calls.length).toBe(calls);
  await expect(first.worker.save("Late")).rejects.toMatchObject({ code: "HR-SYNC-007" });
  expect(next.statuses.at(-1)?.pending).not.toBe(true);
});

it("rejects foreign-account snapshots and rolls an expired cursor back to a full snapshot", async () => {
  const api = { accountProfile: vi.fn(async () => snapshot()), setAccountProfile: vi.fn() };
  const { worker, publish, statuses } = setup(api); await worker.start();
  api.accountProfile.mockImplementationOnce(async () => { throw new GatewayHttpError(409, { error: { code: "HR-SYNC-007" } }, "/changes"); });
  await worker.refresh(); expect(api.accountProfile.mock.calls.at(-1)).toEqual(["account-a"]);
  api.accountProfile.mockImplementationOnce(async () => snapshot("Other", "2", "account-b"));
  await worker.refresh(); expect(publish.mock.calls.at(-1)?.[0].profile.account.id).toBe("account-a");
  expect(statuses.at(-1)?.error?.code).toBe("HR-WEB-005");
});

it("an unrecognized 2xx ACK cannot silently discard a durable edit", async () => {
  const api = { accountProfile: vi.fn(async () => snapshot()), setAccountProfile: vi.fn(async () => ({})) };
  const { worker, statuses } = setup(api as unknown as Partial<GatewayClient>); await worker.start();
  expect(await worker.save("Pending")).toBe(false);
  expect(statuses.some((status) => status.pending && status.error?.code === "HR-WEB-005")).toBe(true);
  await worker.refresh();
  const calls = api.setAccountProfile.mock.calls as unknown[][];
  expect(calls[1]![1]).toEqual(calls[0]![1]);
});
