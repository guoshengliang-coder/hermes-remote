import assert from "node:assert/strict";
import { mkdtemp, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { PROTOCOL_VERSION, type SessionLifecycleEvent } from "@hermes-remote/protocol";
import { LifecycleEventStore } from "./lifecycle-event-store.js";

const lifecycle = (overrides: Partial<SessionLifecycleEvent> = {}): SessionLifecycleEvent => ({
  type: "session.lifecycle",
  version: PROTOCOL_VERSION,
  eventId: "life-1",
  deviceId: "mac-mini",
  runtimeSessionId: "runtime-1",
  storedSessionId: "stored-1",
  event: "run.completed",
  state: "idle",
  occurredAt: "2026-08-31T08:30:00.000Z",
  ...overrides,
});

test("durably ingests and deduplicates lifecycle events", async () => {
  const path = await storePath();
  const store = new LifecycleEventStore(path);
  const first = await store.ingest(lifecycle());
  const duplicate = await store.ingest(lifecycle());
  assert.equal(first.sequence, 1);
  assert.equal(duplicate.sequence, 1);
  assert.equal((await store.list()).events.length, 1);
  assert.equal((await stat(path)).mode & 0o777, 0o600);

  const restored = new LifecycleEventStore(path);
  assert.deepEqual(await restored.list(), await store.list());
});

test("rejects one event id reused for different content", async () => {
  const store = new LifecycleEventStore(await storePath());
  await store.ingest(lifecycle());
  await assert.rejects(
    store.ingest(lifecycle({ storedSessionId: "different" })),
    /event_id_conflict/,
  );
});

test("paginates by stable sequence cursor", async () => {
  const store = new LifecycleEventStore(await storePath());
  await store.ingest(lifecycle({ eventId: "life-1" }));
  await store.ingest(lifecycle({ eventId: "life-2", runtimeSessionId: "runtime-2" }));
  await store.ingest(lifecycle({ eventId: "life-3", runtimeSessionId: "runtime-3" }));
  const first = await store.list(0, 2);
  assert.deepEqual(first.events.map((record) => record.sequence), [1, 2]);
  assert.equal(first.nextCursor, 2);
  assert.equal(first.hasMore, true);
  const second = await store.list(first.nextCursor, 2);
  assert.deepEqual(second.events.map((record) => record.sequence), [3]);
  assert.equal(second.hasMore, false);
});

test("marks delivery and read idempotently", async () => {
  const store = new LifecycleEventStore(await storePath());
  await store.ingest(lifecycle());
  assert.equal(await store.markDelivered(["life-1", "missing"]), 1);
  assert.equal(await store.markDelivered(["life-1"]), 0);
  assert.equal(await store.markRead(["life-1"]), 1);
  const [record] = (await store.list()).events;
  assert.ok(record.deliveredAt);
  assert.ok(record.readAt);
});

test("bounds retained events", async () => {
  const store = new LifecycleEventStore(await storePath(), 2);
  await store.ingest(lifecycle({ eventId: "life-1" }));
  await store.ingest(lifecycle({ eventId: "life-2" }));
  await store.ingest(lifecycle({ eventId: "life-3" }));
  assert.deepEqual((await store.list()).events.map((record) => record.event.eventId), ["life-2", "life-3"]);
});

test("rejects a corrupt snapshot with duplicate identities or cursors", async () => {
  const path = await storePath();
  const record = {
    sequence: 1,
    event: lifecycle(),
    receivedAt: "2026-08-31T08:30:01.000Z",
  };
  await writeFile(path, JSON.stringify({
    version: 1,
    nextSequence: 2,
    events: [record, record],
  }), "utf8");
  await assert.rejects(new LifecycleEventStore(path).list(), /invalid_lifecycle_event_store/);
});

async function storePath(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "hermes-relay-events-"));
  return join(root, "events.json");
}
