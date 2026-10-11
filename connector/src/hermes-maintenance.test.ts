import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { chmodSync, linkSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test, { type TestContext } from "node:test";
import { HermesMaintenanceAdmission, maintenanceError, MAINTENANCE_CODE, MAINTENANCE_EN, MAINTENANCE_ZH } from "./hermes-maintenance.js";

function fixture(t: TestContext) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "hg199-admission-")));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const runID = randomUUID(); const sourceIdentity = "a".repeat(64);
  const write = (mode: "pause" | "release", id = runID) => writeFileSync(join(root, "request.json"), JSON.stringify({ schema: 1, mode, runID: id, sourceIdentity }), { mode: 0o600 });
  const ack = () => JSON.parse(readFileSync(join(root, "ack.json"), "utf8"));
  return { root, runID, write, ack };
}

test("no configured mailbox keeps existing installations unchanged", () => {
  const gate = new HermesMaintenanceAdmission(); assert.equal(gate.blocked, false);
  const release = gate.acquire(); assert.ok(release); release(); release(); assert.equal(gate.blocked, false);
});
test("shared Desktop request fixture is accepted and acknowledged by exact bytes", (t) => {
  const f = fixture(t);
  const bytes = Buffer.from(readFileSync(new URL("../fixtures/maintenance/request.json", import.meta.url), "utf8").trim());
  writeFileSync(join(f.root, "request.json"), bytes, { mode: 0o600 });
  const gate = new HermesMaintenanceAdmission(f.root); assert.equal(gate.acquire(), undefined);
  assert.equal(f.ack().requestSHA256, createHash("sha256").update(bytes).digest("hex"));
  assert.equal(f.ack().runID, JSON.parse(bytes.toString()).runID);
});
test("pause refuses new admission and counts existing work until actual completion", (t) => {
  const f = fixture(t); const gate = new HermesMaintenanceAdmission(f.root);
  const release = gate.acquire(); assert.ok(release);
  f.write("pause"); assert.equal(gate.acquire(), undefined);
  assert.equal(f.ack().paused, true); assert.equal(f.ack().activeOperations, 1);
  release(); release(); assert.equal(f.ack().activeOperations, 0);
  assert.equal(f.ack().runID, f.runID); assert.equal(f.ack().pid, process.pid);
  f.write("release"); assert.equal(gate.blocked, false);
  f.write("pause", randomUUID()); assert.equal(gate.blocked, true);
});
test("restart retains a durable pause but gives a new process-instance nonce", (t) => {
  const f = fixture(t); f.write("pause"); const before = new HermesMaintenanceAdmission(f.root);
  const after = new HermesMaintenanceAdmission(f.root);
  assert.notEqual(before.instanceID, after.instanceID); assert.equal(after.acquire(), undefined);
  assert.equal(f.ack().runID, f.runID); assert.equal(f.ack().instanceID, after.instanceID);
});
test("deletion or a different release never clears a pause", (t) => {
  for (const replacement of ["delete", "foreign"] as const) {
    const f = fixture(t); f.write("pause"); const gate = new HermesMaintenanceAdmission(f.root);
    if (replacement === "delete") unlinkSync(join(f.root, "request.json")); else f.write("release", randomUUID());
    assert.equal(gate.acquire(), undefined);
    f.write("release"); assert.equal(gate.blocked, true, "a damaged mailbox requires explicit recovery, not expiry");
  }
});
test("duplicate fields, future schemas, unreadable files and link aliases fail closed", (t) => {
  for (const kind of ["duplicate", "future", "symlink", "hardlink", "permissions", "directory"] as const) {
    const f = fixture(t); f.write("pause"); const path = join(f.root, "request.json");
    switch (kind) {
      case "duplicate": writeFileSync(path, readFileSync(path, "utf8").replace('"schema":1', '"schema":2,"schema":1')); break;
      case "future": writeFileSync(path, readFileSync(path, "utf8").replace('"schema":1', '"schema":2')); break;
      case "symlink": unlinkSync(path); symlinkSync("/missing-fixture-file", path); break;
      case "hardlink": linkSync(path, join(f.root, "alias")); break;
      case "permissions": chmodSync(path, 0o644); break;
      case "directory": chmodSync(f.root, 0o755); break;
    }
    const gate = new HermesMaintenanceAdmission(f.root); assert.equal(gate.acquire(), undefined, kind);
  }
});
test("unsafe configured directory never falls back to the disabled mode", (t) => {
  const f = fixture(t); const alias = f.root + "-alias"; symlinkSync(f.root, alias);
  t.after(() => unlinkSync(alias));
  const gate = new HermesMaintenanceAdmission(alias); assert.equal(gate.acquire(), undefined);
});
test("maintenance envelope carries both languages, non-retryability and no private diagnostics", () => {
  const error = maintenanceError("synthetic-request");
  assert.equal(error.code, MAINTENANCE_CODE); assert.equal(error.retryable, false);
  assert.equal(error.message, MAINTENANCE_ZH); assert.equal(error.messageEn, MAINTENANCE_EN);
  assert.equal(error.recoveryAction, "details"); assert.equal(error.correlationId, "synthetic-request");
  assert.deepEqual(Object.keys(error).sort(), ["code", "correlationId", "message", "messageEn", "recoveryAction", "retryable"]);
});


test("paused existing RPC channels refuse new tasks but keep audited draining actions and responses", async () => {
  const { maintenanceFrameDecision, MAINTENANCE_RPC_CODE } = await import("./hermes-maintenance.js");
  const decide = (value: unknown) => maintenanceFrameDecision(Buffer.from(JSON.stringify(value)), false);
  for (const method of ["session.create", "session.prompt", "session.resume", "cron.run", "unknown.future.writer"]) {
    const result = decide({ jsonrpc: "2.0", id: "request-1", method, params: { secret: "fixture-private" } });
    assert.equal(result.forward, false);
    const error = JSON.parse(result.replacement!);
    assert.equal(error.id, "request-1"); assert.equal(error.error.code, MAINTENANCE_RPC_CODE);
    assert.equal(error.error.message, "HR-HERMESUPDATE-005"); assert.equal(error.error.data.retryable, false);
    assert.equal(result.replacement!.includes("fixture-private"), false);
  }
  for (const method of ["session.interrupt", "approval.respond", "clarify.respond", "clarify.lock", "request.answer"]) {
    assert.equal(decide({ jsonrpc: "2.0", id: 3, method, params: { id: "nested", result: { answer: "a\nb" } } }).forward, true);
  }
  assert.equal(decide({ jsonrpc: "2.0", id: 3, result: { id: "nested", answer: "a\nb" } }).forward, true);
  for (const input of ['{"jsonrpc":"2.0","id":1,"method":"session.prompt","method":"request.answer"}', '[]', 'broken']) {
    assert.deepEqual(maintenanceFrameDecision(Buffer.from(input), false), { forward: false, replacement: null });
  }
  assert.equal(decide({ jsonrpc: "2.0", method: "session.prompt" }).forward, false);
  assert.equal(maintenanceFrameDecision(Buffer.from("binary"), true).forward, false);
});
