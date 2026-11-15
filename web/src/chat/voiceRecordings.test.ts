import "fake-indexeddb/auto";
import { afterEach, expect, it } from "vitest";
import { clearVoiceRecordings, VoiceJournal, VoiceRecordingStore, VOICE_RETENTION_MS, VOICE_WRITER_LEASE_MS } from "./voiceRecordings";
afterEach(clearVoiceRecordings);
it("recovers complete and interrupted audio in its exact owner scope, never another account", async () => {
  const store = new VoiceRecordingStore();
  const record = await store.begin("account/mac/profile/session");
  await store.append(record.id, 0, new Uint8Array([1, 2]));
  await store.append(record.id, 1, new Uint8Array([3, 4]));
  expect(await new VoiceRecordingStore().list("other-account/mac/profile/session")).toEqual([]);
  expect((await new VoiceRecordingStore().list(record.scope))[0]).toMatchObject({ complete: false, bytes: 4 });
  await store.seal(record.id, true, true);
  expect((await new VoiceRecordingStore().list(record.scope))[0]).toMatchObject({ complete: true, limited: true });
  expect(await store.audio(record.id)).toEqual([new Uint8Array([1, 2]), new Uint8Array([3, 4])]);
});
it("rejects an overwrite and duplicate chunks without modifying the original recording", async () => {
  const store = new VoiceRecordingStore(), r = await store.begin("one");
  await store.append(r.id, 0, new Uint8Array([9, 2]));
  await expect(store.begin("one")).rejects.toThrow();
  await expect(store.append(r.id, 0, new Uint8Array([3, 4]))).rejects.toThrow();
  expect((await store.list("one"))[0]!.bytes).toBe(2);
  await store.seal(r.id, false);
  expect(await store.audio(r.id)).toEqual([new Uint8Array([9, 2])]);
});
it("expires recordings with their chunks and does not silently remove other pending audio", async () => {
  let now = 10;
  const store = new VoiceRecordingStore(() => now), r = await store.begin("one");
  await store.append(r.id, 0, new Uint8Array([1, 2]));
  now += VOICE_RETENTION_MS;
  expect(await store.list("one")).toEqual([]);
  await expect(store.audio(r.id)).rejects.toThrow();
});
it("logout invalidates queued writers before clearing every record", async () => {
  const store = new VoiceRecordingStore(), r = await store.begin("one");
  await store.append(r.id, 0, new Uint8Array([1, 2]));
  const journal = new VoiceJournal(store, "two", () => {});
  journal.append(new Uint8Array([3, 4]));
  await clearVoiceRecordings();
  await journal.seal(true).catch(() => {});
  await expect(store.append(r.id, 1, new Uint8Array([3, 4]))).rejects.toThrow();
  expect(await new VoiceRecordingStore().list("one")).toEqual([]);
  expect(await new VoiceRecordingStore().list("two")).toEqual([]);
});
it("durably hands off final text before deleting audio, and permits the next recording", async () => {
  const store = new VoiceRecordingStore(), r = await store.begin("one");
  await store.append(r.id, 0, new Uint8Array([1, 2])); await store.seal(r.id, true);
  await store.handoff(r.id, "full final");
  expect(await store.audio(r.id)).toEqual([]);
  expect((await new VoiceRecordingStore().list("one"))[0]!.text).toBe("full final");
  expect((await store.begin("one")).id).not.toBe(r.id);
});
it("reserves capacity for simultaneous writers and relocates a lazy new-chat handoff", async () => {
  const store = new VoiceRecordingStore(), r = await store.begin("one");
  await store.begin("two"); await store.begin("three");
  await expect(store.begin("four")).rejects.toThrow();
  await store.append(r.id, 0, new Uint8Array([1, 2])); await store.seal(r.id, true);
  await store.handoff(r.id, "unsure delivery"); await store.relocate(r.id, "durable-session");
  expect(await store.list("one")).toEqual([]);
  expect((await store.list("durable-session"))[0]!.text).toBe("unsure delivery");
});
it("another view cannot transcribe or hand off an actively written recording", async () => {
  const writer = new VoiceRecordingStore(), r = await writer.begin("live");
  await writer.append(r.id, 0, new Uint8Array([1, 2]));
  const other = new VoiceRecordingStore();
  await expect(other.audio(r.id)).rejects.toThrow();
  await expect(other.handoff(r.id, "prefix only")).rejects.toThrow();
  await writer.append(r.id, 1, new Uint8Array([3, 4])); await writer.seal(r.id, true);
  expect(await other.audio(r.id)).toEqual([new Uint8Array([1, 2]), new Uint8Array([3, 4])]);
});
it("a stopped interrupted journal cannot later append after another view recovers it", async () => {
  const store = new VoiceRecordingStore(), r = await store.begin("one");
  await store.append(r.id, 0, new Uint8Array([1, 2])); await store.seal(r.id, false);
  await expect(store.append(r.id, 1, new Uint8Array([3, 4]))).rejects.toThrow();
});
it("claims an expired crashed-writer prefix atomically and rejects that writer's late append", async () => {
  let now = 1;
  const writer = new VoiceRecordingStore(() => now), r = await writer.begin("one");
  await writer.append(r.id, 0, new Uint8Array([1, 2]));
  now += VOICE_WRITER_LEASE_MS + 1;
  const restored = new VoiceRecordingStore(() => now);
  expect(await restored.audio(r.id)).toEqual([new Uint8Array([1, 2])]);
  await expect(writer.append(r.id, 1, new Uint8Array([3, 4]))).rejects.toThrow();
  expect((await restored.list("one"))[0]).toMatchObject({ complete: false, recording: false });
});
it("removes a crashed empty journal after its lease instead of blocking the conversation", async () => {
  let now = 1;
  const store = new VoiceRecordingStore(() => now), r = await store.begin("one");
  now += VOICE_WRITER_LEASE_MS + 1;
  expect(await store.list("one")).toEqual([]);
  expect((await store.begin("one")).id).not.toBe(r.id);
});
it("reports an aborted IDB request once without an unhandled transaction rejection", async () => {
  const { vi } = await import("vitest");
  const store = new VoiceRecordingStore(), r = await store.begin("one");
  const original = IDBDatabase.prototype.transaction;
  const spy = vi.spyOn(IDBDatabase.prototype, "transaction").mockImplementationOnce(function(this: IDBDatabase, ...args: Parameters<typeof original>) {
    const transaction = original.apply(this, args);
    queueMicrotask(() => transaction.abort());
    return transaction;
  });
  await expect(store.append(r.id, 0, new Uint8Array([1, 2]))).rejects.toThrow();
  spy.mockRestore();
  await new Promise(resolve => setTimeout(resolve, 20));
  await store.append(r.id, 0, new Uint8Array([1, 2])); await store.seal(r.id, true);
  expect(await store.audio(r.id)).toEqual([new Uint8Array([1, 2])]);
});
