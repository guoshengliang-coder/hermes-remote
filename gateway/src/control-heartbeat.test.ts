import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { WebSocket } from "ws";
import { ControlHeartbeat } from "./control-heartbeat.js";
import { createGatewayLogger } from "./gateway-log.js";

class FakeSocket extends EventEmitter {
  readyState: number = WebSocket.OPEN;
  pings = 0;
  terminations = 0;
  ping(): void { this.pings += 1; }
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
