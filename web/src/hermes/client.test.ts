import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HermesSocket, HermesSocketError, type WebSocketLike } from "./client";
import type { OpenRequestsSnapshot, ServerEvent, ServerRequest } from "./types";

class FakeWebSocket implements WebSocketLike {
  readyState = 1;
  sent: Array<Record<string, unknown>> = [];
  closedWith: [number | undefined, string | undefined] | null = null;
  onopen: WebSocketLike["onopen"] = null;
  onmessage: WebSocketLike["onmessage"] = null;
  onclose: WebSocketLike["onclose"] = null;
  onerror: WebSocketLike["onerror"] = null;
  constructor(readonly url: string) {}
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close(code?: number, reason?: string) {
    this.closedWith = [code, reason];
    this.readyState = 3;
  }
  receive(...frames: unknown[]) {
    this.onmessage?.({ data: frames.map((f) => JSON.stringify(f)).join("\n") });
  }
  ready() {
    this.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
  }
  answer(id: unknown, result: unknown) {
    this.receive({ jsonrpc: "2.0", id, result });
  }
  fail(id: unknown, code: number, message = "err") {
    this.receive({ jsonrpc: "2.0", id, error: { code, message } });
  }
  remoteClose(code = 1006, reason = "") {
    this.readyState = 3;
    this.onclose?.({ code, reason });
  }
  lastRequest(method: string) {
    return [...this.sent].reverse().find((f) => f.method === method) as { id: number; params: Record<string, unknown> } | undefined;
  }
}

function setup(opts: Partial<ConstructorParameters<typeof HermesSocket>[0]> = {}) {
  let ws!: FakeWebSocket;
  const socket = new HermesSocket({
    url: "wss://gw.example/v2/devices/d/ws",
    factory: (url) => (ws = new FakeWebSocket(url)),
    ...opts,
  });
  const events: ServerEvent[] = [];
  const requests: ServerRequest[] = [];
  const snapshots: OpenRequestsSnapshot[] = [];
  const order: string[] = [];
  socket.on("event", (e) => { events.push(e); order.push(`event:${e.type}`); });
  socket.on("server-request", (r) => { requests.push(r); order.push(`request:${r.id}`); });
  socket.on("open-requests", (s) => { snapshots.push(s); order.push(`snapshot:${s.ids.join(",")}`); });
  socket.connect();
  return { socket, ws: () => ws, events, requests, snapshots, order };
}

/** Ready the socket and accept capabilities. */
function readyAdvertised(ws: FakeWebSocket) {
  ws.ready();
  const caps = ws.lastRequest("client.capabilities")!;
  ws.answer(caps.id, { server_requests: ["approval", "clarify"] });
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("HermesSocket handshake", () => {
  it("sends client.capabilities as the first frame after gateway.ready, before gated RPCs", async () => {
    const { socket, ws } = setup();
    const call = socket.call("session.resume", { session_id: "s" });
    await Promise.resolve();
    expect(ws().sent).toEqual([]); // gated until ready
    ws().ready();
    await vi.advanceTimersByTimeAsync(0);
    expect(ws().sent.map((f) => f.method)).toEqual(["client.capabilities", "session.resume"]);
    expect(ws().sent[0]).toMatchObject({ jsonrpc: "2.0", params: { server_requests: true } });
    ws().answer(ws().lastRequest("session.resume")!.id, { session_id: "live" });
    await expect(call).resolves.toEqual({ session_id: "live" });
    expect(socket.isReady).toBe(true);
  });

  it("fails a gated call with handshake-timeout after 15 s without gateway.ready", async () => {
    const { socket } = setup({ handshakeTimeoutMs: 0 });
    const call = socket.call("prompt.submit", { session_id: "s", text: "x" });
    const assertion = expect(call).rejects.toMatchObject({ kind: "handshake-timeout", method: "prompt.submit" });
    await vi.advanceTimersByTimeAsync(15_000);
    await assertion;
  });

  it("closes a socket that never becomes ready (watchdog)", async () => {
    const { socket, ws } = setup({ handshakeTimeoutMs: 20_000 });
    const closed = vi.fn();
    socket.on("closed", closed);
    await vi.advanceTimersByTimeAsync(20_000);
    expect(closed).toHaveBeenCalledWith({ code: 4000, reason: "gateway handshake timeout", cause: "handshake-timeout" });
    expect(ws().closedWith?.[0]).toBe(4000);
  });

  it("marks server requests unsupported when an older Hermes answers -32601, and keeps working", async () => {
    const { socket, ws } = setup();
    ws().ready();
    ws().fail(ws().lastRequest("client.capabilities")!.id, -32601, "method not found");
    expect(socket.serverRequests).toBe("unsupported");
    const call = socket.call("prompt.submit", { session_id: "s", text: "x" });
    await vi.advanceTimersByTimeAsync(0);
    ws().answer(ws().lastRequest("prompt.submit")!.id, {});
    await expect(call).resolves.toEqual({});
  });

  it("marks server requests advertised on a capabilities result", () => {
    const { socket, ws } = setup();
    readyAdvertised(ws());
    expect(socket.serverRequests).toBe("advertised");
  });
});

describe("HermesSocket RPC", () => {
  it("surfaces RPC error objects with their numeric code", async () => {
    const { socket, ws } = setup();
    readyAdvertised(ws());
    for (const code of [4000, 4001, 4007, 4009, 4090, -32001, -32601, 5028]) {
      const call = socket.call("prompt.submit", { session_id: "s", text: "x" });
      await vi.advanceTimersByTimeAsync(0);
      ws().fail(ws().lastRequest("prompt.submit")!.id, code, `m${code}`);
      const error = await call.catch((e: unknown) => e);
      expect(error).toBeInstanceOf(HermesSocketError);
      expect(error).toMatchObject({ kind: "rpc", code, message: `m${code}`, method: "prompt.submit" });
    }
  });

  it("times out an unanswered call", async () => {
    const { socket, ws } = setup({ rpcTimeoutMs: 1_000 });
    readyAdvertised(ws());
    const call = socket.call("prompt.submit", { session_id: "s", text: "x" });
    const assertion = expect(call).rejects.toMatchObject({ kind: "timeout" });
    await vi.advanceTimersByTimeAsync(1_000);
    await assertion;
  });

  it("uses increasing numeric ids and ignores answers to unknown ids", async () => {
    const { socket, ws } = setup();
    readyAdvertised(ws());
    const a = socket.call("session.interrupt", { session_id: "a" });
    const b = socket.call("session.interrupt", { session_id: "b" });
    await vi.advanceTimersByTimeAsync(0);
    const ids = ws().sent.filter((f) => f.method === "session.interrupt").map((f) => f.id as number);
    expect(ids[1]).toBeGreaterThan(ids[0]!);
    ws().answer(999, {});
    ws().answer(ids[1], "b");
    ws().answer(ids[0], "a");
    await expect(a).resolves.toBe("a");
    await expect(b).resolves.toBe("b");
  });

  it("fails pending and gated calls on close and notifies closed", async () => {
    const { socket, ws } = setup();
    const gated = socket.call("prompt.submit", { session_id: "s", text: "x" });
    const closed = vi.fn();
    socket.on("closed", closed);
    ws().remoteClose(1013, "mac offline");
    await expect(gated).rejects.toMatchObject({ kind: "closed" });
    expect(closed).toHaveBeenCalledWith({ code: 1013, reason: "mac offline", cause: "remote" });
    await expect(socket.call("prompt.submit", {})).rejects.toMatchObject({ kind: "not-connected" });
  });

  it("close() rejects in-flight calls and closes the socket once", async () => {
    const { socket, ws } = setup();
    readyAdvertised(ws());
    const call = socket.call("prompt.submit", { session_id: "s", text: "x" });
    await vi.advanceTimersByTimeAsync(0);
    const closed = vi.fn();
    socket.on("closed", closed);
    socket.close();
    socket.close();
    await expect(call).rejects.toMatchObject({ kind: "closed" });
    expect(closed).toHaveBeenCalledTimes(1);
    expect(closed).toHaveBeenCalledWith({ code: 1000, reason: "client closing", cause: "client" });
    expect(ws().closedWith).toEqual([1000, "client closing"]);
  });

  it("refuses methods the Gateway does not forward to browsers, without sending", async () => {
    const { socket, ws } = setup();
    readyAdvertised(ws());
    const sentBefore = ws().sent.length;
    await expect(socket.call("projects.create", { name: "x" })).rejects.toMatchObject({ kind: "rpc", code: 4403, data: { code: "HR-WEB-001" } });
    expect(ws().sent.length).toBe(sentBefore);
    const open = new HermesSocket({ url: "wss://x", factory: () => new FakeWebSocket("wss://x"), allowedMethods: null });
    open.connect();
    const pending = open.call("config.set", {});
    open.close();
    await expect(pending).rejects.toMatchObject({ kind: "closed" });
  });

  it("reports an unsupported browser", () => {
    const socket = new HermesSocket({
      url: "wss://x",
      factory: () => {
        throw new HermesSocketError("unsupported", "no WebSocket");
      },
    });
    expect(() => socket.connect()).toThrow(HermesSocketError);
  });
});

describe("HermesSocket server requests", () => {
  it.each(["sudo", "secret", "vault.unlock", "terminal.read", "preview.open", "window.read", "tour", "mcp.setup"])(
    "answers %s immediately with -32601",
    (method) => {
      const { ws, requests } = setup();
      readyAdvertised(ws());
      ws().receive({ jsonrpc: "2.0", id: "srq-000000000001", method, params: { session_id: "s" } });
      expect(ws().sent.at(-1)).toEqual({ jsonrpc: "2.0", id: "srq-000000000001", error: { code: -32601, message: `Hermes Remote has no handler for ${method}` } });
      expect(requests).toEqual([]);
    },
  );

  it("emits approval and clarify requests; clarify gets request_id = the request id", () => {
    const { ws, requests } = setup();
    readyAdvertised(ws());
    ws().receive(
      { jsonrpc: "2.0", id: "srq-a", method: "approval", params: { session_id: "s", command: "rm -rf x", request_id: "q7" } },
      { jsonrpc: "2.0", id: "srq-c", method: "clarify", params: { session_id: "s", question: "?" } },
    );
    expect(requests).toEqual([
      { id: "srq-a", method: "approval", params: { session_id: "s", command: "rm -rf x", request_id: "q7" }, sessionId: "s" },
      { id: "srq-c", method: "clarify", params: { session_id: "s", question: "?", request_id: "srq-c" }, sessionId: "s" },
    ]);
  });

  it("emits events to listeners", () => {
    const { ws, events } = setup();
    readyAdvertised(ws());
    ws().receive({ jsonrpc: "2.0", method: "event", params: { type: "request.cancel", payload: { id: "srq-a" } } });
    expect(events.map((e) => e.type)).toEqual(["gateway.ready", "request.cancel"]);
  });

  it("after a resume answer re-delivers open_requests then emits the snapshot, including ids that arrived during the resume", async () => {
    const { socket, ws, requests, order } = setup();
    readyAdvertised(ws());
    const resume = socket.call("session.resume", { session_id: "stored" });
    await vi.advanceTimersByTimeAsync(0);
    const id = ws().lastRequest("session.resume")!.id;
    // Raised during the resume, before the answer that does not list it.
    ws().receive({ jsonrpc: "2.0", id: "srq-during", method: "approval", params: { session_id: "live" } });
    ws().answer(id, {
      session_id: "live",
      open_requests: [
        { id: "srq-open", method: "clarify", params: { session_id: "live", questions: [{ qid: "q1", question: "a?" }], answers: {} } },
        { id: "srq-sudo", method: "sudo", params: {} },
      ],
    });
    await expect(resume).resolves.toMatchObject({ session_id: "live" });
    expect(order).toEqual(["event:gateway.ready", "request:srq-during", "request:srq-open", "snapshot:srq-open,srq-sudo,srq-during"]);
    expect(requests[1]).toMatchObject({ id: "srq-open", replayed: true, params: { request_id: "srq-open" } });
  });

  it("emits no snapshot when capabilities were not accepted", async () => {
    const { socket, ws, snapshots } = setup();
    ws().ready();
    ws().fail(ws().lastRequest("client.capabilities")!.id, -32601);
    const resume = socket.call("session.resume", { session_id: "stored" });
    await vi.advanceTimersByTimeAsync(0);
    ws().answer(ws().lastRequest("session.resume")!.id, { session_id: "live", open_requests: [{ id: "srq-x", method: "approval", params: {} }] });
    await resume;
    expect(snapshots).toEqual([]);
  });

  it("an absent open_requests is an empty snapshot; a request after the answer is not in it", async () => {
    const { socket, ws, snapshots } = setup();
    readyAdvertised(ws());
    const resume = socket.call("session.resume", { session_id: "stored" });
    await vi.advanceTimersByTimeAsync(0);
    ws().answer(ws().lastRequest("session.resume")!.id, { session_id: "live" });
    await resume;
    ws().receive({ jsonrpc: "2.0", id: "srq-after", method: "approval", params: {} });
    expect(snapshots).toEqual([{ sessionId: "live", ids: [] }]);
  });

  it("a listener that throws does not break the reader", () => {
    const { socket, ws, events } = setup();
    socket.on("event", () => {
      throw new Error("boom");
    });
    const spy = vi.spyOn(globalThis, "queueMicrotask").mockImplementation(() => {});
    readyAdvertised(ws());
    ws().receive({ jsonrpc: "2.0", method: "event", params: { type: "message.delta" } });
    expect(events.map((e) => e.type)).toEqual(["gateway.ready", "message.delta"]);
    spy.mockRestore();
  });
});
