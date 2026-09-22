import { describe, expect, it } from "vitest";
import { HermesSocket, type WebSocketLike } from "../hermes/client";
import type { GatewayClient } from "../api/gateway";
import { ChatSession } from "./session";
import type { ChatAction } from "./model";

class FakeWebSocket implements WebSocketLike {
  readyState = 1;
  sent: Array<Record<string, unknown>> = [];
  onopen: WebSocketLike["onopen"] = null;
  onmessage: WebSocketLike["onmessage"] = null;
  onclose: WebSocketLike["onclose"] = null;
  onerror: WebSocketLike["onerror"] = null;
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close() {
    this.readyState = 3;
  }
  receive(frame: unknown) {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
  last(method: string) {
    return [...this.sent].reverse().find((f) => f.method === method) as { id: number } | undefined;
  }
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe("ChatSession", () => {
  it("shows a pending approval again when the conversation is reopened (open_requests on resume)", async () => {
    let ws!: FakeWebSocket;
    const actions: ChatAction[] = [];
    const client = { messages: async () => ({ messages: [] }), settled: async () => undefined } as unknown as GatewayClient;
    const session = new ChatSession({
      client,
      deviceId: "dev-mac",
      storedSessionId: "stored-1",
      dispatch: (action) => actions.push(action),
      socketFactory: (url) => new HermesSocket({ url, factory: () => (ws = new FakeWebSocket()) }),
    });
    session.start();
    await tick();
    ws.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
    ws.receive({ jsonrpc: "2.0", id: ws.last("client.capabilities")!.id, result: { server_requests: ["approval", "clarify"] } });
    await tick();
    const resume = ws.last("session.resume")!;
    expect(resume).toBeDefined();
    ws.receive({
      jsonrpc: "2.0",
      id: resume.id,
      result: {
        session_id: "live-9",
        open_requests: [{ id: "srq-1", method: "approval", params: { session_id: "live-9", command: "rm -rf build", choices: ["once", "deny"] } }],
      },
    });
    await tick();
    const replayed = actions.filter((a) => a.type === "server-request");
    expect(replayed).toHaveLength(1);
    expect(actions.some((a) => a.type === "open-requests")).toBe(true);
    expect(session.liveSessionId).toBe("live-9");
    session.dispose();
  });

  async function readySession() {
    let ws!: FakeWebSocket;
    const actions: ChatAction[] = [];
    const client = { messages: async () => ({ messages: [] }), settled: async () => undefined } as unknown as GatewayClient;
    const session = new ChatSession({
      client,
      deviceId: "dev-mac",
      storedSessionId: "stored-1",
      dispatch: (action) => actions.push(action),
      socketFactory: (url) => new HermesSocket({ url, factory: () => (ws = new FakeWebSocket()) }),
    });
    session.start();
    await tick();
    ws.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
    ws.receive({ jsonrpc: "2.0", id: ws.last("client.capabilities")!.id, result: { server_requests: ["approval"] } });
    await tick();
    ws.receive({ jsonrpc: "2.0", id: ws.last("session.resume")!.id, result: { session_id: "live-1" } });
    await tick();
    return { session, ws: () => ws, actions };
  }

  it.each([
    [4090, "HR-SESS-013", false],
    [4007, "HR-SESS-001", true],
  ])("a submit failing with %i is shown once, on the bubble or as the page notice", async (code, hr, pageLevel) => {
    const { session, ws, actions } = await readySession();
    const sending = session.send("k1", "hi", []);
    await tick();
    ws().receive({ jsonrpc: "2.0", id: ws().last("prompt.submit")!.id, error: { code, message: "x" } });
    await sending;
    const failed = actions.find((a) => a.type === "user-failed") as Extract<ChatAction, { type: "user-failed" }>;
    const notices = actions.filter((a) => a.type === "notice" && a.error) as Array<Extract<ChatAction, { type: "notice" }>>;
    if (pageLevel) {
      expect(failed.error).toBeUndefined();
      expect(notices.map((n) => n.error?.code)).toEqual([hr]);
      expect(notices[0]!.terminal).toBe(true);
    } else {
      expect(failed.error?.code).toBe(hr);
      expect(notices).toHaveLength(0);
    }
    session.dispose();
  });

  it("a message sent while the socket drops before it is ready goes out on the next connection", async () => {
    const sockets: FakeWebSocket[] = [];
    const actions: ChatAction[] = [];
    const client = { messages: async () => ({ messages: [] }), settled: async () => undefined } as unknown as GatewayClient;
    const session = new ChatSession({
      client,
      deviceId: "dev-mac",
      storedSessionId: "stored-1",
      dispatch: (action) => actions.push(action),
      socketFactory: (url) => new HermesSocket({ url, factory: () => {
        const ws = new FakeWebSocket();
        sockets.push(ws);
        return ws;
      } }),
    });
    session.start();
    await tick();
    const sending = session.send("k1", "hello", []);
    await tick();
    // The first socket dies before gateway.ready: nothing was sent on it.
    sockets[0]!.readyState = 3;
    sockets[0]!.onclose?.({ code: 1006, reason: "" });
    window.dispatchEvent(new Event("online"));
    await tick();
    const ws = sockets[1]!;
    expect(ws).toBeDefined();
    ws.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
    ws.receive({ jsonrpc: "2.0", id: ws.last("client.capabilities")!.id, result: { server_requests: ["approval"] } });
    await tick();
    const resumes = ws.sent.filter((f) => f.method === "session.resume");
    expect(resumes).toHaveLength(1);
    ws.receive({ jsonrpc: "2.0", id: resumes[0]!.id, result: { session_id: "live-2" } });
    await tick();
    await tick();
    const submit = ws.last("prompt.submit")!;
    expect(submit).toBeDefined();
    ws.receive({ jsonrpc: "2.0", id: submit.id, result: { ok: true } });
    await sending;
    expect(actions.some((a) => a.type === "user-delivered")).toBe(true);
    expect(actions.some((a) => a.type === "user-failed")).toBe(false);
    session.dispose();
  });
});
