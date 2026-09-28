import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { EventEmitter } from "node:events";
import { PassThrough, Readable } from "node:stream";
import test from "node:test";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { WebSocket } from "ws";
import { MAX_PHONE_UPLOAD_BYTES, PHONE_UPLOAD_CHUNK_BYTES, encodeWireMessage, type WireMessage } from "@hermes-remote/protocol";
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

function uploadRequest(body: Buffer, declared = body.length): IncomingMessage {
  const stream = new PassThrough() as PassThrough & Partial<IncomingMessage>;
  stream.method = "POST";
  stream.headers = { "content-length": String(declared), "content-type": "application/pdf" };
  stream.end(body);
  return stream as IncomingMessage;
}

function acknowledgingBroker(maxBodyBytes: number, sent: WireMessage[]): HttpTunnelBroker {
  const broker = new HttpTunnelBroker(maxBodyBytes, 4, 5_000, (_socket, message) => {
    sent.push(message);
    if (message.type === "tunnel.http.request.start" || message.type === "tunnel.http.request.chunk") {
      queueMicrotask(() => broker.handleConnectorMessage(connector, {
        type: "tunnel.http.request.ack", version: 1,
        requestId: message.type === "tunnel.http.request.start" ? message.id : message.requestId,
        sequence: message.type === "tunnel.http.request.start" ? -1 : message.sequence,
      }));
    }
  });
  return broker;
}

test("only the authenticated upload route accepts bodies above the normal REST limit", async () => {
  const sent: WireMessage[] = [];
  const broker = acknowledgingBroker(1_024, sent);
  const body = Buffer.alloc(1_025, 7);
  const upload = new FakeResponse();
  await broker.forward(uploadRequest(body), upload as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload?name=report.pdf"), connector);
  const forwarded = sent[0];
  assert.equal(forwarded?.type, "tunnel.http.request.start");
  assert.deepEqual(Buffer.from(sent.find((message) => message.type === "tunnel.http.request.chunk")?.dataBase64 ?? "", "base64"), body);
  assert.equal(sent.at(-1)?.type, "tunnel.http.request.end");
  if (forwarded?.type === "tunnel.http.request.start") {
    broker.handleConnectorMessage(connector, {
      type: "tunnel.http.response", version: 1, requestId: forwarded.id,
      status: 201, headers: {},
    });
  }
  const ordinary = new FakeResponse();
  await broker.forward(uploadRequest(body), ordinary as unknown as ServerResponse,
    new URL("http://gateway.local/api/other"), connector);
  assert.equal(ordinary.status, 413);
});

test("a large file remains intact across bounded acknowledged frames", async () => {
  const size = Number(process.env.HR_TEST_UPLOAD_BYTES ?? 7 * 1024 * 1024);
  const body = Buffer.alloc(size, 0x7f);
  const digest = createHash("sha256");
  let chunks = 0;
  let largestFrame = 0;
  let requestId = "";
  const broker = new HttpTunnelBroker(10 * 1024 * 1024, 4, 5_000, (_socket, message) => {
    largestFrame = Math.max(largestFrame, Buffer.byteLength(encodeWireMessage(message)));
    if (message.type === "tunnel.http.request.start") requestId = message.id;
    if (message.type === "tunnel.http.request.chunk") {
      digest.update(Buffer.from(message.dataBase64, "base64"));
      chunks++;
    }
    if (message.type === "tunnel.http.request.start" || message.type === "tunnel.http.request.chunk") {
      queueMicrotask(() => broker.handleConnectorMessage(connector, {
        type: "tunnel.http.request.ack", version: 1,
        requestId: message.type === "tunnel.http.request.start" ? message.id : message.requestId,
        sequence: message.type === "tunnel.http.request.start" ? -1 : message.sequence,
      }));
    }
  });
  const response = new FakeResponse();
  await broker.forward(uploadRequest(body), response as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(chunks, Math.ceil(size / PHONE_UPLOAD_CHUNK_BYTES));
  assert.ok(largestFrame < 512 * 1024);
  assert.equal(digest.digest("hex"), createHash("sha256").update(body).digest("hex"));
  broker.handleConnectorMessage(connector, {
    type: "tunnel.http.response", version: 1, requestId,
    status: 201, headers: {},
  });
});

test("50 MiB upload cap rejects a declared oversized file before a tunnel frame", async () => {
  const sent: WireMessage[] = [];
  const broker = acknowledgingBroker(1_024, sent);
  const response = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.alloc(0), MAX_PHONE_UPLOAD_BYTES + 1),
    response as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(response.status, 413);
  assert.match(response.body, /HR-FILE-008/);
  assert.equal(sent.length, 0);
});

test("an unknown-length upload stops at 50 MiB and cancels its partial Connector file", async () => {
  let cancellations = 0;
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, (_socket, message) => {
    if (message.type === "tunnel.http.cancel") cancellations++;
    if (message.type === "tunnel.http.request.start" || message.type === "tunnel.http.request.chunk") {
      queueMicrotask(() => broker.handleConnectorMessage(connector, {
        type: "tunnel.http.request.ack", version: 1,
        requestId: message.type === "tunnel.http.request.start" ? message.id : message.requestId,
        sequence: message.type === "tunnel.http.request.start" ? -1 : message.sequence,
      }));
    }
  });
  const stream = Readable.from((async function* () {
    for (let i = 0; i < 50; i++) yield Buffer.alloc(1024 * 1024);
    yield Buffer.from([1]);
  })()) as Readable & Partial<IncomingMessage>;
  stream.method = "POST";
  stream.headers = {};
  const response = new FakeResponse();
  await broker.forward(stream as IncomingMessage, response as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(response.status, 413);
  assert.match(response.body, /HR-FILE-008/);
  assert.equal(cancellations, 1);
});

test("a second upload to the same Mac is refused without closing its control tunnel", async () => {
  const sent: WireMessage[] = [];
  const broker = acknowledgingBroker(1_024, sent);
  const first = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.from("first")), first as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  const second = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.from("second")), second as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(second.status, 503);
  assert.match(second.body, /HR-FILE-009/);
  assert.equal(sent.filter((message) => message.type === "tunnel.http.request.start").length, 1);
  const opened = sent.find((message) => message.type === "tunnel.http.request.start");
  if (opened?.type === "tunnel.http.request.start") {
    broker.handleConnectorMessage(connector, {
      type: "tunnel.http.response", version: 1, requestId: opened.id,
      status: 201, headers: {},
    });
  }
  const third = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.from("third")), third as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(sent.filter((message) => message.type === "tunnel.http.request.start").length, 2);
  const reopened = sent.filter((message) => message.type === "tunnel.http.request.start")[1];
  if (reopened?.type === "tunnel.http.request.start") {
    broker.handleConnectorMessage(connector, {
      type: "tunnel.http.response", version: 1, requestId: reopened.id,
      status: 201, headers: {},
    });
  }
});

test("a busy control socket rejects an upload and frees its admission slot", async () => {
  const broker = new HttpTunnelBroker(1_024, 4, 5_000, () => false);
  for (const name of ["first", "retry"]) {
    const response = new FakeResponse();
    await broker.forward(uploadRequest(Buffer.from(name)), response as unknown as ServerResponse,
      new URL("http://gateway.local/api/files/upload"), connector);
    assert.equal(response.status, 503);
    assert.match(response.body, /HR-FILE-009/);
  }
});

test("an older Connector keeps accepting legacy-size uploads without a control disconnect", async () => {
  const sent: WireMessage[] = [];
  const broker = new HttpTunnelBroker(1_024, 4, 5_000,
    (_socket, message) => sent.push(message), capturingLogger().logger, 10);
  const first = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.from("old-phone")), first as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(sent[0]?.type, "tunnel.http.request.start");
  const legacy = sent.find((message) => message.type === "tunnel.http.request");
  assert.equal(legacy?.type, "tunnel.http.request");
  if (legacy?.type === "tunnel.http.request") {
    assert.equal(Buffer.from(legacy.bodyBase64 ?? "", "base64").toString(), "old-phone");
    broker.handleConnectorMessage(connector, {
      type: "tunnel.http.response", version: 1, requestId: legacy.id, status: 201, headers: {},
    });
  }
  const second = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.from("again")), second as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(sent.filter((message) => message.type === "tunnel.http.request.start").length, 1);
  const last = sent.at(-1);
  if (last?.type === "tunnel.http.request") {
    broker.handleConnectorMessage(connector, {
      type: "tunnel.http.response", version: 1, requestId: last.id, status: 201, headers: {},
    });
  }
  const oversized = new FakeResponse();
  await broker.forward(uploadRequest(Buffer.alloc(0), LEGACY_UPLOAD_BYTES_FOR_TEST + 1),
    oversized as unknown as ServerResponse,
    new URL("http://gateway.local/api/files/upload"), connector);
  assert.equal(oversized.status, 413);
  assert.match(oversized.body, /HR-FILE-010/);
});

const LEGACY_UPLOAD_BYTES_FOR_TEST = 6 * 1024 * 1024;

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
