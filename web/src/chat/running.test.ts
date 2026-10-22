import { afterEach, describe, expect, it } from "vitest";
import { HermesSocket, type WebSocketLike } from "../hermes/client";
import type { GatewayClient } from "../api/gateway";
import { ChatSession } from "./session";
import { initialChatState, reduceChat } from "./model";

class Socket implements WebSocketLike {
  readyState = 1;
  onopen: WebSocketLike["onopen"] = null;
  onmessage: WebSocketLike["onmessage"] = null;
  onclose: WebSocketLike["onclose"] = null;
  onerror: WebSocketLike["onerror"] = null;
  sent: Array<{ id: number; method: string }> = [];
  send(data: string) { this.sent.push(JSON.parse(data)); }
  close() { this.readyState = 3; }
  receive(...frames: unknown[]) { this.onmessage?.({ data: frames.map(f => JSON.stringify(f)).join("\n") }); }
  result(method: string, result: unknown) {
    this.receive({ jsonrpc: "2.0", id: [...this.sent].reverse().find(f => f.method === method)!.id, result });
  }
  event(type: string, payload = {}) { this.receive({ jsonrpc: "2.0", method: "event", params: { type, session_id: "live", payload } }); }
}
const tick = () => new Promise(r => setTimeout(r, 0));
const sessions: ChatSession[] = [];
afterEach(() => sessions.splice(0).forEach(s => s.dispose()));
async function setup(result: Record<string, unknown> = {}) {
  let ws!: Socket;
  let state = initialChatState;
  const session = new ChatSession({
    client: { settled: async () => {}, messages: async () => ({ messages: [] }) } as unknown as GatewayClient,
    deviceId: "mac", storedSessionId: "stored",
    dispatch: a => { state = reduceChat(state, a); },
    socketFactory: url => new HermesSocket({ url, factory: () => (ws = new Socket()) }),
  });
  sessions.push(session); session.start(); await tick();
  ws.event("gateway.ready");
  ws.result("client.capabilities", { server_requests: ["approval", "clarify"] });
  await tick(); ws.result("session.resume", { session_id: "live", ...result }); await tick();
  return { session, ws, latestSocket: () => ws, state: () => state };
}

describe("HG-228 run recovery", () => {
  it("restores running from the resume result without a session.info push", async () => {
    const { state } = await setup({ running: true, info: { cwd: "/project", model: "test" } });
    expect(state()).toMatchObject({ generating: true, workspace: { cwd: "/project" }, liveModel: "test" });
  });
  it("shows the run while prompt.submit is awaiting its reply, and failure clears it", async () => {
    const { session, ws, state } = await setup({ running: false });
    const pending = session.send("local", "hello", []); await tick();
    expect(state().generating).toBe(true);
    ws.receive({ jsonrpc: "2.0", id: [...ws.sent].reverse().find(f => f.method === "prompt.submit")!.id, error: { code: 4090, message: "busy" } });
    await pending;
    expect(state().generating).toBe(false);
  });
  it("a late submit reply cannot restart a completed run", async () => {
    const { session, ws, state } = await setup();
    const pending = session.send("local", "hello", []); await tick();
    ws.event("message.complete", { text: "done" });
    ws.result("prompt.submit", { status: "streaming" }); await pending;
    expect(state().generating).toBe(false);
    expect(state().items.filter(i => i.role === "assistant")).toHaveLength(1);
  });
  it("applies a resume snapshot before a newer event in the same frame batch", async () => {
    const { session, ws, state } = await setup();
    const pending = session.resume(true); await tick();
    ws.receive(
      { jsonrpc: "2.0", id: [...ws.sent].reverse().find(f => f.method === "session.resume")!.id, result: { session_id: "live", running: false } },
      { jsonrpc: "2.0", method: "event", params: { type: "message.delta", session_id: "live", payload: { text: "new run" } } },
    );
    await pending;
    expect(state().generating).toBe(true);
    expect(state().items[0]!.text).toBe("new run");
  });
  it("re-checks delayed lifecycle notifications against current Hermes state and reloads a completed tail", async () => {
    const { session, ws, state } = await setup({ running: true });
    session.reconcileLifecycle({ sequence: 9 }); await tick();
    ws.result("session.resume", { session_id: "live", running: false }); await tick();
    expect(state().generating).toBe(false);
    // A delayed run.started is only an invalidation: the fresh idle result wins.
    session.reconcileLifecycle({ sequence: 10 }); await tick();
    ws.result("session.resume", { session_id: "live", running: false }); await tick();
    expect(state().generating).toBe(false);
    const count = ws.sent.length;
    session.reconcileLifecycle({ sequence: 9 }); await tick();
    expect(ws.sent).toHaveLength(count);
  });
  it("requests a fresh snapshot if lifecycle invalidates a resume already in flight", async () => {
    const { session, ws } = await setup();
    const previous = session.resume(true); await tick();
    const count = ws.sent.filter(f => f.method === "session.resume").length;
    session.reconcileLifecycle({ sequence: 10 });
    ws.result("session.resume", { session_id: "live", running: true }); await previous; await tick();
    expect(ws.sent.filter(f => f.method === "session.resume")).toHaveLength(count + 1);
    ws.result("session.resume", { session_id: "live", running: false }); await tick();
  });
  it("does not lose lifecycle events arriving while a submit is in flight", async () => {
    const { session, ws, state } = await setup();
    const sending = session.send("local", "hi", []); await tick();
    const before = ws.sent.filter(f => f.method === "session.resume").length;
    session.reconcileLifecycle({ sequence: 10 }); await tick();
    expect(ws.sent.filter(f => f.method === "session.resume")).toHaveLength(before);
    ws.result("prompt.submit", { status: "streaming" }); await sending; await tick();
    expect(ws.sent.filter(f => f.method === "session.resume")).toHaveLength(before + 1);
    ws.result("session.resume", { session_id: "live", running: false }); await tick();
    expect(state().generating).toBe(false);
  });
  it("serializes a submit behind an already in-flight lifecycle snapshot", async () => {
    const { session, ws, state } = await setup();
    session.reconcileLifecycle({ sequence: 10 }); await tick();
    const sending = session.send("local", "hi", []); await tick();
    expect(ws.sent.some(f => f.method === "prompt.submit")).toBe(false);
    ws.result("session.resume", { session_id: "live", running: false }); await tick();
    expect(ws.sent.some(f => f.method === "prompt.submit")).toBe(true);
    expect(state().generating).toBe(true);
    ws.result("prompt.submit", { status: "streaming" }); await sending;
  });
  it("a refused second submit cannot clear a run already known to be running", async () => {
    const { session, ws, state } = await setup({ running: true });
    const sending = session.send("local", "hi", []); await tick();
    ws.receive({ jsonrpc: "2.0", id: [...ws.sent].reverse().find(f => f.method === "prompt.submit")!.id, error: { code: 4090, message: "busy" } });
    await sending;
    expect(state().generating).toBe(true);
  });
  it("reconnects to an idle snapshot after missing the terminal stream event", async () => {
    const { session, ws, latestSocket, state } = await setup({ running: true });
    ws.readyState = 3; ws.onclose?.({ code: 1006, reason: "network" });
    session.reconnectNow(); await tick();
    const next = latestSocket(); expect(next).not.toBe(ws);
    next.event("gateway.ready");
    next.result("client.capabilities", { server_requests: ["approval"] }); await tick();
    next.result("session.resume", { session_id: "live", running: false }); await tick();
    expect(state().generating).toBe(false);
  });
  it("Stop during reconnect resumes the handle and actually sends session.interrupt", async () => {
    const { session, ws, latestSocket, state } = await setup({ running: true });
    ws.readyState = 3; ws.onclose?.({ code: 1006, reason: "network" });
    const stopping = session.interrupt();
    session.reconnectNow(); await tick();
    const next = latestSocket();
    next.event("gateway.ready");
    next.result("client.capabilities", { server_requests: ["approval"] }); await tick();
    next.result("session.resume", { session_id: "live", running: true }); await tick();
    expect(next.sent.some(f => f.method === "session.interrupt")).toBe(true);
    next.result("session.interrupt", {}); await stopping;
    expect(state().generating).toBe(false);
    expect(state().stopped).toBe(true);
  });
  it("an old Hermes without running information does not invent an idle snapshot", async () => {
    const { session, ws, state } = await setup();
    ws.event("message.delta", { text: "still running" });
    const pending = session.resume(true); await tick();
    ws.result("session.resume", { session_id: "live", info: { model: "old" } }); await pending;
    expect(state().generating).toBe(true);
  });
});
