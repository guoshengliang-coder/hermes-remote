import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";
import test from "node:test";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { WebSocket } from "ws";
import type { WireMessage } from "@hermes-remote/protocol";
import type { GatewayLogger, LogFields } from "./gateway-log.js";
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

function capturingLogger(): { logger: GatewayLogger; lines: Array<{ kind: string; fields: LogFields }> } {
  const lines: Array<{ kind: string; fields: LogFields }> = [];
  const record = (kind: string, fields: LogFields = {}) => { lines.push({ kind, fields }); };
  return {
    lines,
    logger: { level: "info", enabled: () => true, error: record, info: record, debug: record },
  };
}

const connector = { socket: {} as WebSocket, deviceId: "mac", routingKey: "legacy:mac" };

async function open(
  broker: HttpTunnelBroker,
  sent: WireMessage[],
  path: string,
): Promise<{ response: FakeResponse; id: string }> {
  const response = new FakeResponse();
  await broker.forward(request(), response as unknown as ServerResponse, new URL(`http://gateway.local${path}`), connector);
  const opened = sent.at(-1);
  assert.equal(opened?.type, "tunnel.http.request");
  return { response, id: opened.type === "tunnel.http.request" ? opened.id : "" };
}

test("a streamed response logs its status, decoded bytes, chunk count and time to first byte", async () => {
  const sent: WireMessage[] = [];
  const { logger, lines } = capturingLogger();
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, (_socket, message) => sent.push(message), logger);
  const { response, id } = await open(broker, sent, "/api/sessions/s1/messages?order=latest&limit=100");
  await new Promise((resolve) => setTimeout(resolve, 15));
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response.start", version: 1, requestId: id, status: 200,
    headers: { "content-type": "application/json" },
  });
  for (const [sequence, text] of ["{\"messages\":[", "1,2,3", "]}"].entries()) {
    broker.handleConnectorMessage(connector, {
      type: "tunnel.http.response.chunk", version: 1, requestId: id, sequence,
      dataBase64: Buffer.from(text).toString("base64"),
    });
  }
  broker.handleConnectorMessage(connector, { type: "tunnel.http.response.end", version: 1, requestId: id });

  assert.equal(response.body, "{\"messages\":[1,2,3]}");
  assert.equal(lines.length, 1);
  const { kind, fields } = lines[0];
  assert.equal(kind, "http.tunnel");
  assert.equal(fields.outcome, "streamed");
  assert.equal(fields.status, 200);
  assert.equal(fields.bytes, Buffer.byteLength("{\"messages\":[1,2,3]}"));
  assert.equal(fields.chunks, 3);
  assert.equal(fields.path, "/api/sessions/s1/messages?order=latest&limit=100");
  assert.equal(typeof fields.ttfbMs, "number");
  assert.ok((fields.ttfbMs as number) >= 10);
  assert.ok((fields.durationMs as number) >= (fields.ttfbMs as number));
});

test("a buffered response logs status, bytes, zero chunks and time to first byte", async () => {
  const sent: WireMessage[] = [];
  const { logger, lines } = capturingLogger();
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, (_socket, message) => sent.push(message), logger);
  const { id } = await open(broker, sent, "/api/sessions?limit=100");
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response", version: 1, requestId: id, status: 200,
    headers: { "content-type": "application/json" },
    bodyBase64: Buffer.from("{\"sessions\":[]}").toString("base64"),
  });
  assert.equal(lines.length, 1);
  assert.equal(lines[0].fields.outcome, "response");
  assert.equal(lines[0].fields.status, 200);
  assert.equal(lines[0].fields.bytes, 15);
  assert.equal(lines[0].fields.chunks, 0);
  assert.equal(typeof lines[0].fields.ttfbMs, "number");
});

test("a stream that fails after start keeps its start status and partial counts", async () => {
  const sent: WireMessage[] = [];
  const { logger, lines } = capturingLogger();
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, (_socket, message) => sent.push(message), logger);
  const { response, id } = await open(broker, sent, "/api/files/big");
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response.start", version: 1, requestId: id, status: 200, headers: {},
  });
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response.chunk", version: 1, requestId: id, sequence: 0,
    dataBase64: Buffer.from("abcd").toString("base64"),
  });
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response.end", version: 1, requestId: id, error: "local_read_failed",
  });
  assert.equal(response.destroyed, true);
  assert.equal(lines.length, 1);
  assert.equal(lines[0].fields.outcome, "error:local_read_failed");
  assert.equal(lines[0].fields.status, 200);
  assert.equal(lines[0].fields.bytes, 4);
  assert.equal(lines[0].fields.chunks, 1);
});

test("an out-of-order chunk and a client abort are each logged once", async () => {
  const sent: WireMessage[] = [];
  const { logger, lines } = capturingLogger();
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, (_socket, message) => sent.push(message), logger);
  const first = await open(broker, sent, "/api/a");
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response.start", version: 1, requestId: first.id, status: 200, headers: {},
  });
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response.chunk", version: 1, requestId: first.id, sequence: 1,
    dataBase64: Buffer.from("x").toString("base64"),
  });
  const second = await open(broker, sent, "/api/b");
  second.response.emit("close");
  second.response.emit("close");

  assert.deepEqual(lines.map((line) => line.fields.outcome), [
    "error:invalid_response_chunk_sequence",
    "client_aborted",
  ]);
  assert.equal(lines[0].fields.status, 200);
  assert.equal(lines[0].fields.chunks, 0);
  assert.equal(lines[1].fields.status, undefined);
  assert.equal(lines[1].fields.ttfbMs, undefined);
});
