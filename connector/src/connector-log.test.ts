import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createConnectorLogger,
  parseConnectorLogLevel,
  sanitizeLogFields,
  rpcResponseIdentity,
  summarizeHermesFrame,
  trackedForegroundRpcRequest,
} from "./connector-log.js";

test("log level defaults to info and rejects unknown values", () => {
  assert.equal(parseConnectorLogLevel(undefined), "info");
  assert.equal(parseConnectorLogLevel("Debug"), "debug");
  assert.throws(() => parseConnectorLogLevel("loud"), /CONNECTOR_LOG_LEVEL/);
});

test("lines are single JSON objects and credentials never reach them", () => {
  const lines: string[] = [];
  const log = createConnectorLogger("info", (line) => lines.push(line), () => new Date(0));
  log.info("tunnel.open", { tunnel: "t1", wsTicket: "secret", path: "/api/ws" });
  log.debug("frame", { type: "message.delta" });
  assert.equal(lines.length, 1);
  assert.deepEqual(JSON.parse(lines[0] ?? ""), { ts: "1970-01-01T00:00:00.000Z", level: "info", kind: "tunnel.open", tunnel: "t1", path: "/api/ws" });
  assert.equal((sanitizeLogFields({ reason: "x".repeat(300) }).reason as string).length, 201);
});

test("a Hermes frame is described by its event type and session, never quoted", () => {
  const complete = summarizeHermesFrame(JSON.stringify({
    type: "event",
    event: { type: "message.complete", session_id: "20260905_102612_6d5fd4", payload: { text: "最终回答" } },
  }));
  assert.deepEqual(complete, { kind: "event", type: "message.complete", sessionId: "20260905_102612_6d5fd4", terminal: true });

  const delta = summarizeHermesFrame(JSON.stringify({
    type: "event",
    event: { type: "message.delta", payload: { session_id: "s1", text: "…" } },
  }));
  assert.deepEqual(delta, { kind: "event", type: "message.delta", sessionId: "s1", terminal: false });

  assert.deepEqual(summarizeHermesFrame(JSON.stringify({ id: "3", result: { ok: true } })), { kind: "rpc", type: "rpc.result", terminal: false });
  assert.deepEqual(summarizeHermesFrame("not json"), { kind: "other", terminal: false });
});

test("foreground RPC tracing recognizes only bounded top-level metadata", () => {
  const request = Buffer.from(JSON.stringify({ id: 7, method: "slash.exec", params: { command: "/model private --session" } }));
  assert.deepEqual(trackedForegroundRpcRequest(request, false), { id: 7, method: "slash.exec" });
  assert.equal(trackedForegroundRpcRequest(request, true), null);
  assert.equal(trackedForegroundRpcRequest(Buffer.from(JSON.stringify({ id: 8, method: "prompt.submit" })), false), null);
  assert.equal(trackedForegroundRpcRequest(Buffer.from("{"), false), null);
  assert.equal(trackedForegroundRpcRequest(Buffer.alloc(4097), false), null);
  assert.deepEqual(rpcResponseIdentity(Buffer.from(JSON.stringify({ id: 7, result: { output: "private" } })), false), { id: 7, ok: true });
  assert.deepEqual(rpcResponseIdentity(Buffer.from(JSON.stringify({ id: 7, error: { message: "private" } })), false), { id: 7, ok: false });
  assert.equal(rpcResponseIdentity(Buffer.from(JSON.stringify({ event: { id: 7, result: "private" } })), false), null);
});
