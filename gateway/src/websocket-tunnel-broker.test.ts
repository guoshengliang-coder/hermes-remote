import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { test } from "node:test";
import { WebSocket } from "ws";
import type { WireMessage } from "@hermes-remote/protocol";
import { createGatewayLogger } from "./gateway-log.js";
import { WebSocketTunnelBroker } from "./websocket-tunnel-broker.js";

class FakeSocket extends EventEmitter {
  readyState: number = WebSocket.OPEN;
  bufferedAmount = 0;
  sent: unknown[] = [];
  closed?: { code?: number; reason?: string };
  send(data: unknown): void { this.sent.push(data); }
  close(code?: number, reason?: string): void {
    this.closed = { code, reason };
    this.readyState = WebSocket.CLOSED;
    this.emit("close", code ?? 1005, Buffer.from(reason ?? ""));
  }
}

function harness() {
  const lines: Record<string, unknown>[] = [];
  const log = createGatewayLogger("debug", (line) => lines.push(JSON.parse(line) as Record<string, unknown>));
  const wire: WireMessage[] = [];
  const connector = { socket: new FakeSocket() as unknown as WebSocket, deviceId: "mac-mini", routingKey: "legacy:mac-mini" };
  const broker = new WebSocketTunnelBroker(4, 1024 * 1024, (_socket, message) => { wire.push(message); }, () => connector, log);
  return { lines, wire, connector, broker };
}

test("an app tunnel logs its open and a close summary with frame counts both ways", () => {
  const { lines, wire, connector, broker } = harness();
  const app = new FakeSocket();
  broker.open(app as unknown as WebSocket, connector);

  const open = lines.find((l) => l.kind === "app.tunnel.open");
  assert.ok(open);
  assert.equal(open.device, "mac-mini");
  assert.equal(open.tunnels, 1);
  const tunnelId = open.tunnel as string;
  assert.equal((wire[0] as { type: string }).type, "tunnel.ws.open");

  app.emit("message", Buffer.from("{\"method\":\"prompt.submit\"}"), false);
  app.emit("message", Buffer.from("{\"method\":\"session.resume\"}"), false);
  const frame = { type: "tunnel.ws.frame", version: 1, id: tunnelId, dataBase64: Buffer.from("{\"type\":\"event\",\"event\":{\"type\":\"message.delta\"}}").toString("base64"), binary: false };
  broker.handleConnectorMessage(connector, frame as unknown as WireMessage);
  broker.handleConnectorMessage(connector, frame as unknown as WireMessage);
  broker.handleConnectorMessage(connector, frame as unknown as WireMessage);

  app.close(1001, "going away");
  const close = lines.find((l) => l.kind === "app.tunnel.close");
  assert.ok(close);
  assert.equal(close.tunnel, tunnelId);
  assert.equal(close.code, 1001);
  assert.equal(close.reason, "going away");
  assert.equal(close.framesFromApp, 2);
  assert.equal(close.framesToApp, 3);
  assert.equal(close.connectorOnline, true);
  assert.equal(close.tunnels, 0);
  assert.equal(typeof close.durationMs, "number");
  // The relayed payloads themselves are never quoted.
  assert.equal(JSON.stringify(lines).includes("prompt.submit"), false);
});

test("a connector going away closes every tunnel on its route and says how many", () => {
  const { lines, connector, broker } = harness();
  const a = new FakeSocket();
  const b = new FakeSocket();
  broker.open(a as unknown as WebSocket, connector);
  broker.open(b as unknown as WebSocket, connector);

  broker.failRouting("legacy:mac-mini");

  const fail = lines.find((l) => l.kind === "app.tunnel.fail_routing");
  assert.ok(fail);
  assert.equal(fail.closed, 2);
  assert.equal(a.closed?.code, 1013);
  assert.equal(b.closed?.code, 1013);
});
