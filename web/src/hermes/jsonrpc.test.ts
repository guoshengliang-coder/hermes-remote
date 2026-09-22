import { describe, expect, it } from "vitest";
import { encodeRequest, encodeServerError, parseInbound, parseMessage, serverEventFrom } from "./jsonrpc";

describe("parseInbound", () => {
  it("classifies an event and prefers the durable session id", () => {
    const msg = parseInbound(JSON.stringify({
      jsonrpc: "2.0",
      method: "event",
      params: { type: "message.delta", session_id: "live1", payload: { text: "hi", stored_session_id: "stored1" } },
    }));
    expect(msg).toEqual({ kind: "event", event: { type: "message.delta", sessionId: "stored1", payload: { text: "hi", stored_session_id: "stored1" } } });
  });

  it("keeps a server request id as the exact primitive", () => {
    const msg = parseInbound('{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"approval","params":{"session_id":"s"}}');
    expect(msg).toEqual({ kind: "request", id: "srq-0123456789ab", method: "approval", params: { session_id: "s" } });
    const numeric = parseInbound('{"jsonrpc":"2.0","id":7,"method":"sudo","params":{}}');
    expect(numeric).toMatchObject({ kind: "request", id: 7 });
  });

  it("drops a notification without id instead of throwing", () => {
    expect(parseInbound('{"jsonrpc":"2.0","method":"approval","params":{}}')).toEqual({ kind: "unreadable", reason: "notification approval" });
    expect(parseInbound('{"method":"x","id":null}')).toMatchObject({ kind: "unreadable" });
  });

  it("correlates responses by numeric id and reads error codes", () => {
    expect(parseInbound('{"jsonrpc":"2.0","id":3,"result":{"ok":true}}')).toEqual({ kind: "result", id: 3, result: { ok: true } });
    expect(parseInbound('{"jsonrpc":"2.0","id":4,"error":{"code":4090,"message":"owned"}}')).toEqual({
      kind: "error", id: 4, error: { code: 4090, message: "owned" },
    });
    expect(parseInbound('{"id":"srq-1","result":{}}')).toMatchObject({ kind: "result", id: -1 });
    expect(parseInbound('{"id":5,"error":{"code":"x"}}')).toEqual({ kind: "error", id: 5, error: { code: 0, message: "error" } });
    expect(parseInbound('{"id":6}')).toEqual({ kind: "result", id: 6, result: null });
  });

  it("rejects non-objects", () => {
    expect(parseInbound("not json")).toMatchObject({ kind: "unreadable" });
    expect(parseInbound("[1,2]")).toMatchObject({ kind: "unreadable" });
  });
});

describe("parseMessage", () => {
  it("splits several newline-separated frames and skips blank lines", () => {
    const text = [
      '{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}',
      "",
      '{"jsonrpc":"2.0","id":1,"result":{}}\r',
      '{"jsonrpc":"2.0","id":"srq-a","method":"clarify","params":{}}',
    ].join("\n");
    expect(parseMessage(text).map((m) => m.kind)).toEqual(["event", "result", "request"]);
  });
});

describe("serverEventFrom", () => {
  it("falls back through camelCase and params, never throws on structured ids", () => {
    expect(serverEventFrom({ type: "x", sessionId: "p", payload: { sessionId: "q" } }).sessionId).toBe("q");
    expect(serverEventFrom({ type: "x", session_id: "p", payload: { session_id: { bad: 1 } } }).sessionId).toBe("p");
    expect(serverEventFrom({ payload: [] as never }).type).toBe("unknown");
    expect(serverEventFrom({ type: "sessions.changed" }).sessionId).toBeNull();
  });
});

describe("encoders", () => {
  it("builds request and server-error frames", () => {
    expect(JSON.parse(encodeRequest(9, "prompt.submit", { session_id: "s", text: "t" }))).toEqual({
      jsonrpc: "2.0", id: 9, method: "prompt.submit", params: { session_id: "s", text: "t" },
    });
    expect(JSON.parse(encodeServerError("srq-x", -32601, "no handler"))).toEqual({
      jsonrpc: "2.0", id: "srq-x", error: { code: -32601, message: "no handler" },
    });
  });
});
