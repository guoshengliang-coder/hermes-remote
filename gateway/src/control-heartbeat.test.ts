import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { WebSocket } from "ws";
import { ControlHeartbeat } from "./control-heartbeat.js";
import { createGatewayLogger } from "./gateway-log.js";

class FakeSocket extends EventEmitter {
  readyState: number = WebSocket.OPEN;
  bufferedAmount = 0;
  pings = 0;
  terminations = 0;
  writes: Array<(error?: Error) => void> = [];
  ping(_data?: unknown, _mask?: unknown, callback?: (error?: Error) => void): void {
    this.pings += 1;
    if (callback) this.writes.push(callback);
  }
  terminate(): void { this.terminations += 1; this.readyState = WebSocket.CLOSED; }
}

test("Gateway heartbeat terminates a control socket that stops answering pings", () => {
  let now = 1_000;
  const lines: string[] = [];
  const heartbeat = new ControlHeartbeat(
    5_000,
    15_000,
    createGatewayLogger("debug", (line) => lines.push(line)),
    () => now,
  );
  const socket = new FakeSocket();
  heartbeat.track(socket as never, "account");

  now += 5_000;
  heartbeat.sweep();
  assert.equal(socket.pings, 1);
  assert.equal(socket.terminations, 0);

  now += 10_000;
  heartbeat.sweep();
  assert.equal(socket.terminations, 1);
  assert.match(lines.join("\n"), /connector\.heartbeat_timeout/);
  assert.match(lines.join("\n"), /"mode":"account"/);
});

test("a pong refreshes the Gateway heartbeat deadline", () => {
  let now = 1_000;
  const heartbeat = new ControlHeartbeat(
    5_000,
    15_000,
    createGatewayLogger("off"),
    () => now,
  );
  const socket = new FakeSocket();
  heartbeat.track(socket as never, "legacy");

  now += 10_000;
  socket.emit("pong");
  now += 10_000;
  heartbeat.sweep();

  assert.equal(socket.terminations, 0);
  assert.equal(socket.pings, 1);
});

test("timeout distinguishes queued ping, recent inbound traffic and a late sweep without payloads", () => {
  let now = 0;
  const lines: string[] = [];
  const heartbeat = new ControlHeartbeat(5_000, 15_000,
    createGatewayLogger("info", (line) => lines.push(line)), () => now);
  const socket = new FakeSocket();
  heartbeat.track(socket as never, "account");
  now = 5_000;
  heartbeat.sweep();
  now = 14_000;
  socket.emit("message", Buffer.from("private-message bearer=secret"));
  socket.emit("ping", Buffer.from("private-ping"));
  socket.bufferedAmount = 900_000;
  now = 20_000;
  heartbeat.sweep();
  const failure = JSON.parse(lines[0]);
  assert.equal(failure.pingWriteState, "pending");
  assert.equal(failure.lastMessageAgoMs, 6_000);
  assert.equal(failure.lastPingReceivedAgoMs, 6_000);
  assert.equal(failure.lastPingQueuedAgoMs, 15_000);
  assert.equal(failure.bufferedBytes, 900_000);
  assert.equal(failure.sweepDelayMs, 10_000);
  assert.equal(socket.terminations, 1, "inbound traffic does not forgive a missing pong");
  assert.doesNotMatch(lines.join("\n"), /private|bearer|secret/);
});

test("timeout distinguishes a completed local ping write from its missing pong", () => {
  let now = 0;
  const lines: string[] = [];
  const heartbeat = new ControlHeartbeat(5_000, 15_000,
    createGatewayLogger("info", (line) => lines.push(line)), () => now);
  const socket = new FakeSocket();
  heartbeat.track(socket as never, "legacy");
  now = 5_000;
  heartbeat.sweep();
  now = 5_100;
  socket.writes[0]();
  now = 15_000;
  heartbeat.sweep();
  const failure = JSON.parse(lines[0]);
  assert.equal(failure.pingWriteState, "written");
  assert.equal(failure.lastPongAgoMs, undefined);
  assert.equal(failure.lastPingWrittenAgoMs, 9_900);
  assert.equal(failure.bufferedBytes, 0);
  assert.equal(socket.terminations, 1);
});

test("late or failed ping writes do not overwrite a newer probe or leak their errors", () => {
  let now = 0;
  const lines: string[] = [];
  const heartbeat = new ControlHeartbeat(5_000, 15_000,
    createGatewayLogger("info", (line) => lines.push(line)), () => now);
  const socket = new FakeSocket();
  heartbeat.track(socket as never, "account");
  now = 5_000;
  heartbeat.sweep();
  now = 6_000;
  socket.emit("pong", "private-pong");
  now = 10_000;
  heartbeat.sweep();
  socket.writes[0](new Error("Bearer private-token"));
  socket.writes[1](new Error("https://private.invalid/?secret=x"));
  now = 21_000;
  heartbeat.sweep();
  const failure = JSON.parse(lines[0]);
  assert.equal(failure.pingWriteState, "failed");
  assert.equal(failure.lastPingQueuedAgoMs, 11_000);
  assert.equal(failure.lastPingWrittenAgoMs, undefined);
  assert.equal(failure.lastPongAgoMs, 15_000);
  assert.doesNotMatch(lines[0], /private|Bearer|secret|https/);
  // Close removes tracking; callbacks from a closing socket cannot revive it.
  socket.emit("close");
  socket.writes[1]();
  now += 20_000;
  heartbeat.sweep();
  assert.equal(lines.length, 1);
});
