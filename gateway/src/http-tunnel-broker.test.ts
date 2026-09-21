import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import test from "node:test";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { WebSocket } from "ws";
import type { WireMessage } from "@hermes-remote/protocol";
import { HttpTunnelBroker } from "./http-tunnel-broker.js";

class FakeResponse extends EventEmitter {
  headersSent = false;
  status?: number;
  body = "";
  destroyed = false;
  writeHead(status: number): this { this.status = status; this.headersSent = true; return this; }
  write(chunk: Buffer, callback?: () => void): boolean {
    this.body += chunk.toString();
    callback?.();
    return true;
  }
  end(chunk?: Buffer | string): this {
    if (chunk) this.body += chunk.toString();
    this.emit("close");
    return this;
  }
  destroy(): this { this.destroyed = true; this.emit("close"); return this; }
}

function request(): IncomingMessage {
  const stream = new PassThrough() as PassThrough & Partial<IncomingMessage>;
  stream.method = "GET";
  stream.headers = {};
  stream.end();
  return stream as IncomingMessage;
}

test("an aborted phone request sends exactly one Connector cancellation", async () => {
  const sent: WireMessage[] = [];
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, (_socket, message) => sent.push(message));
  const incoming = request();
  const response = new FakeResponse();
  await broker.forward(
    incoming,
    response as unknown as ServerResponse,
    new URL("http://gateway.local/api/status"),
    { socket: {} as WebSocket, deviceId: "mac", routingKey: "legacy:mac" },
  );
  const opened = sent[0];
  assert.equal(opened?.type, "tunnel.http.request");

  incoming.emit("aborted");
  response.emit("close");

  const cancellations = sent.filter((message) => message.type === "tunnel.http.cancel");
  assert.equal(cancellations.length, 1);
  assert.deepEqual(cancellations[0], {
    type: "tunnel.http.cancel",
    version: 1,
    requestId: opened.type === "tunnel.http.request" ? opened.id : "",
    reason: "client_aborted",
  });
});

test("a Gateway timeout cancels Connector work before returning 504", async () => {
  const sent: WireMessage[] = [];
  const broker = new HttpTunnelBroker(1_024, 4, 10, (_socket, message) => sent.push(message));
  const response = new FakeResponse();
  await broker.forward(
    request(),
    response as unknown as ServerResponse,
    new URL("http://gateway.local/api/slow"),
    { socket: {} as WebSocket, deviceId: "mac", routingKey: "legacy:mac" },
  );
  await new Promise((resolve) => setTimeout(resolve, 30));

  assert.equal(response.status, 504);
  assert.equal(sent.filter((message) => message.type === "tunnel.http.cancel").length, 1);
  const cancel = sent.find((message) => message.type === "tunnel.http.cancel");
  assert.equal(cancel?.type === "tunnel.http.cancel" && cancel.reason, "gateway_timeout");
});
