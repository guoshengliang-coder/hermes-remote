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

test("foreground RPC receipt logs only method and id, never slash command contents", () => {
  const { lines, connector, broker } = harness();
  const app = new FakeSocket();
  broker.open(app as unknown as WebSocket, connector);
  const tunnelId = lines.find((line) => line.kind === "app.tunnel.open")?.tunnel as string;
  app.emit("message", Buffer.from(JSON.stringify({ id: 9, method: "slash.exec", params: { command: "/model private-model --session" } })), false);
  app.emit("message", Buffer.from(JSON.stringify({ id: 10, method: "session.create", params: { cwd: "/private/workspace" } })), false);
  app.emit("message", Buffer.from(JSON.stringify({ id: 11, method: "prompt.submit", params: { text: "private prompt" } })), false);
  const received = lines.filter((line) => line.kind === "app.rpc.received");
  assert.deepEqual(received.map((line) => [line.rpcId, line.method]), [[9, "slash.exec"], [10, "session.create"]]);
  broker.handleConnectorMessage(connector, {
    type: "tunnel.ws.frame", version: 1, id: tunnelId,
    dataBase64: Buffer.from(JSON.stringify({ id: 9, result: { output: "private result" } })).toString("base64"),
    binary: false,
  } as WireMessage);
  const returned = lines.filter((line) => line.kind === "app.rpc.returned");
  assert.deepEqual(returned.map((line) => [line.rpcId, line.method, line.ok]), [[9, "slash.exec", true]]);
  assert.equal(JSON.stringify([...received, ...returned]).includes("private"), false);
  app.close();
  assert.equal(lines.find((line) => line.kind === "app.tunnel.close")?.unansweredForegroundRpcs, 1);
  assert.deepEqual(lines.filter((line) => line.kind === "app.rpc.unanswered").map((line) => [line.rpcId, line.method]),
    [[10, "session.create"]]);
});

test("app tunnel correlates a valid client connection id without logging arbitrary header text", () => {
  const { lines, connector, broker } = harness();
  broker.open(new FakeSocket() as unknown as WebSocket, connector, undefined, undefined, undefined,
    "123e4567-e89b-42d3-a456-426614174000");
  assert.equal(lines.find((line) => line.kind === "app.tunnel.open")?.clientConnectionId,
    "123e4567-e89b-42d3-a456-426614174000");
  broker.open(new FakeSocket() as unknown as WebSocket, connector, undefined, undefined, undefined,
    "private-token=do-not-log");
  assert.equal(lines.filter((line) => line.kind === "app.tunnel.open")[1]?.clientConnectionId, undefined);
  assert.equal(JSON.stringify(lines).includes("do-not-log"), false);
});

test("backpressure never claims an RPC reply was forwarded to the app", () => {
  const { lines, connector, broker } = harness();
  const app = new FakeSocket();
  broker.open(app as unknown as WebSocket, connector);
  const tunnelId = lines.find((line) => line.kind === "app.tunnel.open")?.tunnel as string;
  app.emit("message", Buffer.from(JSON.stringify({ id: 6, method: "session.create", params: {} })), false);
  app.bufferedAmount = 1024 * 1024;
  broker.handleConnectorMessage(connector, {
    type: "tunnel.ws.frame", version: 1, id: tunnelId,
    dataBase64: Buffer.from(JSON.stringify({ id: 6, result: {} })).toString("base64"), binary: false,
  } as WireMessage);
  assert.equal(lines.some((line) => line.kind === "app.rpc.returned"), false);
  assert.equal(lines.find((line) => line.kind === "app.tunnel.close")?.unansweredForegroundRpcs, 1);
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
