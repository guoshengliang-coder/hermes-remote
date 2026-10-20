import assert from "node:assert/strict";
import test from "node:test";
import { ControlHeartbeatDiagnostics } from "./control-heartbeat-diagnostics.js";
import { createConnectorLogger } from "./connector-log.js";

test("distinguishes pending local ping write from missing remote pong with recent inbound traffic", () => {
  let now = 0;
  const diagnostics = new ControlHeartbeatDiagnostics(15_000, () => now);
  diagnostics.start();
  now = 15_000;
  diagnostics.tick(0);
  const written = diagnostics.queuedPing();
  now = 28_000;
  diagnostics.message();
  diagnostics.receivedPing();
  now = 30_000;
  const pending = diagnostics.tick(900_000);
  assert.equal(pending.pingWriteState, "pending");
  assert.equal(pending.lastMessageAgoMs, 2_000);
  assert.equal(pending.lastPingReceivedAgoMs, 2_000);
  assert.equal(pending.bufferedBytes, 900_000);
  assert.equal(pending.lastPongAgoMs, undefined, "no pong is not zero milliseconds ago");
  written();
  now = 35_000;
  const sent = diagnostics.tick(0);
  assert.equal(sent.pingWriteState, "written");
  assert.equal(sent.lastPingWrittenAgoMs, 5_000);
  assert.equal(sent.lastPongAgoMs, undefined, "local write does not prove peer delivery");
});

test("records timer delay and keeps connection observations isolated", () => {
  let now = 0;
  const first = new ControlHeartbeatDiagnostics(15_000, () => now);
  first.start();
  now = 15_000;
  first.queuedPing()();
  first.pong();
  now = 50_000;
  const snapshot = first.tick(0);
  assert.equal(snapshot.tickDelayMs, 35_000);
  assert.equal(snapshot.lastPongAgoMs, 35_000);
  const second = new ControlHeartbeatDiagnostics(15_000, () => now);
  second.start();
  assert.equal(second.tick(0).pingWriteState, "none");
  assert.equal(second.tick(0).lastPongAgoMs, undefined);
});

test("late callbacks cannot overwrite a newer ping and raw write errors are never logged", () => {
  const diagnostics = new ControlHeartbeatDiagnostics(15_000, () => 0);
  const oldWrite = diagnostics.queuedPing();
  const currentWrite = diagnostics.queuedPing();
  oldWrite(new Error("Bearer private-token https://private.invalid/?secret=x"));
  assert.equal(diagnostics.tick(0).pingWriteState, "pending");
  currentWrite(new Error("Cookie private-message"));
  const lines: string[] = [];
  createConnectorLogger("info", (line) => lines.push(line))
    .error("relay.heartbeat_timeout", diagnostics.tick(0));
  assert.equal(JSON.parse(lines[0]).pingWriteState, "failed");
  assert.doesNotMatch(lines[0], /private|Bearer|Cookie|secret|https/);
});
