import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, stat, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import {
  handoffLifecycleSnapshot,
  readLifecycleSnapshot,
} from "../../ops/lib/lifecycle-handoff.mjs";
import { createOpsError, OpsError } from "../../ops/lib/errors.mjs";

test("lifecycle handoff validates, canonicalizes, and preserves the final stopped-writer snapshot", async (t) => {
  const fixture = await createFixture(t);
  await writeSnapshot(fixture.source, snapshot([
    lifecycleRecord(2, "life-2"),
    lifecycleRecord(1, "life-1", { deliveredAt: "2026-09-03T10:00:02.000Z" }),
  ], 3));

  const result = await handoffLifecycleSnapshot(fixture.source, fixture.destination, { owner: currentOwner() });
  assert.deepEqual(result, { sourcePresent: true, eventCount: 2, nextSequence: 3 });
  const copied = await readLifecycleSnapshot(path.join(fixture.destination, "lifecycle-events.json"));
  assert.deepEqual(copied.snapshot.events.map((record) => record.sequence), [1, 2]);
  assert.equal(copied.snapshot.events[0].deliveredAt, "2026-09-03T10:00:02.000Z");
  assert.equal((await stat(path.join(fixture.destination, "lifecycle-events.json"))).mode & 0o777, 0o600);
});

test("an empty source replaces candidate smoke state instead of retaining synthetic events", async (t) => {
  const fixture = await createFixture(t);
  await writeSnapshot(fixture.destination, snapshot([lifecycleRecord(1, "candidate-smoke")], 2));

  const result = await handoffLifecycleSnapshot(fixture.source, fixture.destination, { owner: currentOwner() });
  assert.deepEqual(result, { sourcePresent: false, eventCount: 0, nextSequence: 1 });
  assert.deepEqual((await readLifecycleSnapshot(path.join(fixture.destination, "lifecycle-events.json"))).snapshot, {
    version: 1,
    nextSequence: 1,
    events: [],
  });
});

test("corrupt or symlinked lifecycle sources fail closed without changing the destination", async (t) => {
  const fixture = await createFixture(t);
  const destinationFile = path.join(fixture.destination, "lifecycle-events.json");
  await writeSnapshot(fixture.destination, snapshot([lifecycleRecord(1, "keep-me")], 2));
  await writeSnapshot(fixture.source, snapshot([
    lifecycleRecord(1, "duplicate"),
    lifecycleRecord(1, "duplicate"),
  ], 2));
  await assert.rejects(
    () => handoffLifecycleSnapshot(fixture.source, fixture.destination, { owner: currentOwner() }),
    isOpsCode("HR-OPS-008"),
  );
  assert.equal((await readLifecycleSnapshot(destinationFile)).snapshot.events[0].event.eventId, "keep-me");

  const sourceFile = path.join(fixture.source, "lifecycle-events.json");
  await rm(sourceFile);
  const outside = path.join(fixture.base, "outside.json");
  await writeFile(outside, `${JSON.stringify(snapshot([], 1))}\n`, { mode: 0o600 });
  await symlink(outside, sourceFile);
  await assert.rejects(
    () => handoffLifecycleSnapshot(fixture.source, fixture.destination, { owner: currentOwner() }),
    isOpsCode("HR-OPS-008"),
  );
  assert.equal((await readLifecycleSnapshot(destinationFile)).snapshot.events[0].event.eventId, "keep-me");
});

test("reverse handoff carries candidate events back before the old service restarts", async (t) => {
  const fixture = await createFixture(t);
  await writeSnapshot(fixture.source, snapshot([lifecycleRecord(1, "before-switch")], 2));
  await handoffLifecycleSnapshot(fixture.source, fixture.destination, { owner: currentOwner() });

  await writeSnapshot(fixture.destination, snapshot([
    lifecycleRecord(1, "before-switch"),
    lifecycleRecord(2, "during-observation"),
  ], 3));
  await handoffLifecycleSnapshot(fixture.destination, fixture.source, { owner: currentOwner() });

  const restored = await readLifecycleSnapshot(path.join(fixture.source, "lifecycle-events.json"));
  assert.deepEqual(restored.snapshot.events.map((record) => record.event.eventId), [
    "before-switch",
    "during-observation",
  ]);
  assert.equal(restored.snapshot.nextSequence, 3);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-lifecycle-handoff-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const source = path.join(base, "source");
  const destination = path.join(base, "destination");
  await mkdir(source, { recursive: true, mode: 0o700 });
  await mkdir(destination, { recursive: true, mode: 0o700 });
  return { base, source, destination };
}

async function writeSnapshot(directory, value) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const filePath = path.join(directory, "lifecycle-events.json");
  await writeFile(filePath, `${JSON.stringify(value)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

function snapshot(events, nextSequence) {
  return { version: 1, nextSequence, events };
}

function lifecycleRecord(sequence, eventId, timestamps = {}) {
  return {
    sequence,
    event: {
      type: "session.lifecycle",
      version: 1,
      eventId,
      deviceId: "staging-mac",
      runtimeSessionId: `runtime-${eventId}`,
      storedSessionId: `stored-${eventId}`,
      event: "run.completed",
      state: "idle",
      occurredAt: "2026-09-03T10:00:00.000Z",
    },
    receivedAt: "2026-09-03T10:00:01.000Z",
    ...timestamps,
  };
}

function currentOwner() {
  return { uid: process.getuid?.() ?? 0, gid: process.getgid?.() ?? 0 };
}

function isOpsCode(code) {
  return (error) => error instanceof OpsError
    && createOpsError(error.kind, error.technicalCause, error.stage).code === code;
}
