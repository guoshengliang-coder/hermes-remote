import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import path from "node:path";
import { parseWireMessage } from "@hermes-remote/protocol";
import { OpsError } from "./errors.mjs";
import { assertNoSymlinkAncestors, atomicWrite, ensureManagedDirectory } from "./system.mjs";

const SNAPSHOT_NAME = "lifecycle-events.json";
const MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;

export async function handoffLifecycleSnapshot(sourceDirectory, destinationDirectory, { owner } = {}) {
  try {
    await assertSafeDirectory(sourceDirectory, { missing: true });
    await ensureManagedDirectory(destinationDirectory, 0o700, owner);
    const source = await readLifecycleSnapshot(path.join(sourceDirectory, SNAPSHOT_NAME));
    const destination = path.join(destinationDirectory, SNAPSHOT_NAME);
    await atomicWrite(destination, `${JSON.stringify(source.snapshot)}\n`, 0o600, owner);
    return {
      sourcePresent: source.present,
      eventCount: source.snapshot.events.length,
      nextSequence: source.snapshot.nextSequence,
    };
  } catch (error) {
    if (error instanceof OpsError && error.kind === "switch") throw error;
    fail(error instanceof Error ? error.technicalCause || error.message : error, "lifecycle_handoff");
  }
}

export async function readLifecycleSnapshot(filePath) {
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (error) {
    if (error?.code === "ENOENT") return { present: false, snapshot: emptySnapshot() };
    if (error?.code === "ELOOP") fail("lifecycle_snapshot_symlink_rejected", "lifecycle_snapshot_read");
    fail(error instanceof Error ? error.message : error, "lifecycle_snapshot_read");
  }

  try {
    const info = await handle.stat();
    if (!info.isFile() || (info.mode & 0o022) !== 0 || info.size < 2 || info.size > MAX_SNAPSHOT_BYTES) {
      fail("lifecycle_snapshot_unsafe", "lifecycle_snapshot_read");
    }
    const value = JSON.parse(await handle.readFile("utf8"));
    return { present: true, snapshot: validateLifecycleSnapshot(value) };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("lifecycle_snapshot_invalid", "lifecycle_snapshot_read");
  } finally {
    await handle?.close().catch(() => {});
  }
}

export function validateLifecycleSnapshot(value) {
  exactKeys(value, ["version", "nextSequence", "events"], "lifecycle_snapshot");
  if (value.version !== 1 || !Number.isSafeInteger(value.nextSequence) || value.nextSequence < 1
      || !Array.isArray(value.events) || value.events.length > 10_000) {
    fail("lifecycle_snapshot_invalid", "lifecycle_snapshot_validate");
  }

  const events = value.events.map((record) => {
    const allowed = ["sequence", "event", "receivedAt"];
    if (record?.deliveredAt !== undefined) allowed.push("deliveredAt");
    if (record?.readAt !== undefined) allowed.push("readAt");
    exactKeys(record, allowed, "lifecycle_record");
    if (!Number.isSafeInteger(record.sequence) || record.sequence < 1) {
      fail("lifecycle_record_sequence_invalid", "lifecycle_snapshot_validate");
    }
    let event;
    try {
      event = parseWireMessage(JSON.stringify(record.event));
    } catch {
      fail("lifecycle_record_event_invalid", "lifecycle_snapshot_validate");
    }
    if (event.type !== "session.lifecycle") {
      fail("lifecycle_record_event_invalid", "lifecycle_snapshot_validate");
    }
    return {
      sequence: record.sequence,
      event,
      receivedAt: validTimestamp(record.receivedAt),
      ...(record.deliveredAt === undefined ? {} : { deliveredAt: validTimestamp(record.deliveredAt) }),
      ...(record.readAt === undefined ? {} : { readAt: validTimestamp(record.readAt) }),
    };
  }).sort((left, right) => left.sequence - right.sequence);

  const eventIds = new Set(events.map((record) => record.event.eventId));
  const sequences = new Set(events.map((record) => record.sequence));
  const highestSequence = events.at(-1)?.sequence ?? 0;
  if (eventIds.size !== events.length || sequences.size !== events.length || value.nextSequence <= highestSequence) {
    fail("lifecycle_snapshot_identity_invalid", "lifecycle_snapshot_validate");
  }
  return { version: 1, nextSequence: value.nextSequence, events };
}

async function assertSafeDirectory(directory, { missing }) {
  await assertNoSymlinkAncestors(directory);
  try {
    const info = await lstat(directory);
    if (info.isSymbolicLink() || !info.isDirectory()) fail("lifecycle_directory_unsafe", "lifecycle_snapshot_read");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (missing && error?.code === "ENOENT") return;
    throw error;
  }
}

function emptySnapshot() {
  return { version: 1, nextSequence: 1, events: [] };
}

function validTimestamp(value) {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) {
    fail("lifecycle_record_timestamp_invalid", "lifecycle_snapshot_validate");
  }
  return value;
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    fail(`${label}_must_be_object`, "lifecycle_snapshot_validate");
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label}_fields_invalid`, "lifecycle_snapshot_validate");
  }
}

function fail(cause, stage) {
  throw new OpsError("switch", cause, stage);
}
