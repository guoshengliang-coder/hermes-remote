import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createConnectorLogger,
  parseConnectorLogLevel,
  sanitizeLogFields,
  summarizeHermesFrame,
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
