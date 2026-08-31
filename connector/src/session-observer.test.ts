import assert from "node:assert/strict";
import { mkdtemp, readFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  LifecycleOutbox,
  ObserverStateStore,
  SessionLifecycleTracker,
  nextPollDelayMs,
  parseActiveList,
  type LiveSessionSnapshot,
} from "./session-observer.js";

const working = (overrides: Partial<LiveSessionSnapshot> = {}): LiveSessionSnapshot => ({
  id: "runtime-1",
  sessionKey: "stored-1",
  status: "working",
  lastActive: 100,
  title: "Research",
  ...overrides,
});

test("baselines sessions already running at connector startup without a false start notification", () => {
  const tracker = new SessionLifecycleTracker("mac-mini", "default");
  assert.deepEqual(tracker.reduce([working()]), []);
  const completed = tracker.reduce([], new Date("2026-08-31T08:30:00Z"));
  assert.equal(completed.length, 1);
  assert.equal(completed[0].event, "run.completed");
  assert.equal(completed[0].storedSessionId, "stored-1");
});

test("emits one event per meaningful lifecycle transition", () => {
  const tracker = new SessionLifecycleTracker("mac-mini", "default");
  tracker.reduce([]);

  const started = tracker.reduce([working()]);
  assert.deepEqual(started.map((event) => event.event), ["run.started"]);
  assert.deepEqual(tracker.reduce([working({ lastActive: 101 })]), []);

  const waiting = tracker.reduce([working({ status: "waiting", lastActive: 102 })]);
  assert.deepEqual(waiting.map((event) => event.event), ["run.waiting"]);

  const resumed = tracker.reduce([working({ status: "working", lastActive: 103 })]);
  assert.deepEqual(resumed.map((event) => event.event), ["run.resumed"]);

  const completed = tracker.reduce([working({ status: "idle", lastActive: 104 })]);
  assert.deepEqual(completed.map((event) => event.event), ["run.completed"]);
  assert.deepEqual(tracker.reduce([working({ status: "idle", lastActive: 105 })]), []);
});

test("treats a vanished active runtime as completion exactly once", () => {
  const tracker = new SessionLifecycleTracker("mac-mini");
  tracker.reduce([working()]);
  const first = tracker.reduce([]);
  const second = tracker.reduce([]);
  assert.equal(first[0].event, "run.completed");
  assert.deepEqual(second, []);
});

test("tracks concurrent sessions independently", () => {
  const tracker = new SessionLifecycleTracker("mac-mini");
  tracker.reduce([]);
  const events = tracker.reduce([
    working({ id: "runtime-a", sessionKey: "stored-a" }),
    working({ id: "runtime-b", sessionKey: "stored-b", status: "waiting" }),
  ]);
  assert.deepEqual(events.map((event) => [event.storedSessionId, event.event]), [
    ["stored-a", "run.started"],
    ["stored-b", "run.waiting"],
  ]);
});

test("restored tracker state preserves transition identity and avoids restart duplicates", () => {
  const before = new SessionLifecycleTracker("mac-mini", "default");
  before.reduce([]);
  const [started] = before.reduce([working()]);
  const restored = new SessionLifecycleTracker("mac-mini", "default", before.exportState());
  assert.deepEqual(restored.reduce([working()]), []);
  const [waiting] = restored.reduce([working({ status: "waiting" })]);
  assert.notEqual(waiting.eventId, started.eventId);
});

test("outbox deduplicates and only removes acknowledged events", () => {
  const tracker = new SessionLifecycleTracker("mac-mini");
  tracker.reduce([]);
  const events = tracker.reduce([working()]);
  const outbox = new LifecycleOutbox();
  outbox.add(events);
  outbox.add(events);
  assert.equal(outbox.pending().length, 1);
  assert.equal(outbox.acknowledge("missing"), false);
  assert.equal(outbox.acknowledge(events[0].eventId), true);
  assert.deepEqual(outbox.pending(), []);
});

test("parses only the safe active-list projection", () => {
  assert.deepEqual(parseActiveList({
    sessions: [{
      id: "runtime-1",
      session_key: "stored-1",
      status: "working",
      last_active: 123.5,
      title: "  Safe title  ",
      preview: "must not leave the connector",
    }],
  }), [{
    id: "runtime-1",
    sessionKey: "stored-1",
    status: "working",
    lastActive: 123.5,
    title: "Safe title",
  }]);
  assert.throws(() => parseActiveList({ sessions: [{
    id: "runtime-1",
    session_key: "stored-1",
    status: "compromised",
  }] }), /invalid_active_session_status/);
});

test("uses an active cadence only while at least one session is live", () => {
  assert.equal(nextPollDelayMs([]), 20_000);
  assert.equal(nextPollDelayMs([working({ status: "idle" })]), 20_000);
  assert.equal(nextPollDelayMs([working({ status: "waiting" })]), 2_000);
});

test("observer state round-trips through an atomic private file", async () => {
  const root = await mkdtemp(join(tmpdir(), "hermes-observer-test-"));
  const path = join(root, "observer.json");
  const store = new ObserverStateStore(path);
  const tracker = new SessionLifecycleTracker("mac-mini");
  tracker.reduce([]);
  const outbox = tracker.reduce([working()]);
  const state = { version: 1 as const, tracker: tracker.exportState(), outbox };
  await store.save(state);
  assert.deepEqual(await store.load(), state);
  assert.equal((await stat(path)).mode & 0o777, 0o600);
  assert.doesNotMatch(await readFile(path, "utf8"), /preview|tool|prompt/i);
});
