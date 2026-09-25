import { describe, expect, it } from "vitest";
import { HermesSocket, type WebSocketLike } from "../hermes/client";
import { GatewayHttpError, type GatewayClient } from "../api/gateway";
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
    // One stored row: a conversation with history, so a reclaimed session (4007) stays terminal
    // instead of being silently recreated (that path is covered below).
    const client = { messages: async () => ({ messages: [{ id: 1, role: "user", content: "earlier", timestamp: 1 }] }), settled: async () => undefined } as unknown as GatewayClient;
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

  describe("new chat folder (project filter)", () => {
    async function createParams(cwd: string | null | undefined) {
      let ws!: FakeWebSocket;
      const client = { messages: async () => ({ messages: [] }), settled: async () => undefined } as unknown as GatewayClient;
      const session = new ChatSession({
        client,
        deviceId: "dev-mac",
        storedSessionId: null,
        cwd,
        dispatch: () => undefined,
        socketFactory: (url) => new HermesSocket({ url, factory: () => (ws = new FakeWebSocket()) }),
      });
      session.start();
      await tick();
      ws.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
      ws.receive({ jsonrpc: "2.0", id: ws.last("client.capabilities")!.id, result: { server_requests: ["approval"] } });
      await tick();
      void session.send("l-1", "hello", []);
      await tick();
      const create = ws.last("session.create") as { params?: Record<string, unknown> } | undefined;
      session.dispose();
      return create?.params;
    }

    it("creates the chat in the filtered project's folder", async () => {
      expect(await createParams("/u/hermes-remote")).toEqual({ source: "hermes_remote", cwd: "/u/hermes-remote" });
    });

    it("sends no cwd without a filter, so Hermes uses its launch folder", async () => {
      expect(await createParams(null)).toEqual({ source: "hermes_remote" });
      expect(await createParams(undefined)).toEqual({ source: "hermes_remote" });
    });
  });

  it("names a non-default profile on resume and history (the list spans profiles)", async () => {
    let ws!: FakeWebSocket;
    const historyCalls: unknown[][] = [];
    const client = {
      messages: async (...args: unknown[]) => {
        historyCalls.push(args);
        return { messages: [] };
      },
      settled: async () => undefined,
    } as unknown as GatewayClient;
    const session = new ChatSession({
      client,
      deviceId: "dev-mac",
      storedSessionId: "stored-9",
      profile: "work",
      dispatch: () => undefined,
      socketFactory: (url) => new HermesSocket({ url, factory: () => (ws = new FakeWebSocket()) }),
    });
    session.start();
    await tick();
    ws.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
    ws.receive({ jsonrpc: "2.0", id: ws.last("client.capabilities")!.id, result: { server_requests: ["approval"] } });
    await tick();
    expect((ws.last("session.resume") as { params?: Record<string, unknown> }).params).toMatchObject({ session_id: "stored-9", profile: "work" });
    expect(historyCalls[0]).toEqual(["dev-mac", "stored-9", "work", { order: "latest", limit: 100, offset: 0 }, true]);
    session.dispose();
  });

  describe("a session Hermes reclaimed (4007)", () => {
    async function open(historyRows: unknown[]) {
      let ws!: FakeWebSocket;
      const actions: ChatAction[] = [];
      const stored: string[] = [];
      const client = { messages: async () => ({ messages: historyRows }), settled: async () => undefined } as unknown as GatewayClient;
      const session = new ChatSession({
        client,
        deviceId: "dev-mac",
        storedSessionId: "gone-1",
        dispatch: (a) => actions.push(a),
        onStored: (id) => stored.push(id),
        socketFactory: (url) => new HermesSocket({ url, factory: () => (ws = new FakeWebSocket()) }),
      });
      session.start();
      await tick();
      ws.receive({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } });
      ws.receive({ jsonrpc: "2.0", id: ws.last("client.capabilities")!.id, result: { server_requests: ["approval"] } });
      await tick();
      ws.receive({ jsonrpc: "2.0", id: ws.last("session.resume")!.id, error: { code: 4007, message: "session not found" } });
      await tick();
      return { ws, actions, stored, session };
    }

    it("recreates an empty conversation silently and replaces its id", async () => {
      const { ws, actions, stored, session } = await open([]);
      const create = ws.last("session.create");
      expect(create).toBeDefined();
      ws.receive({ jsonrpc: "2.0", id: create!.id, result: { session_id: "live-new", stored_session_id: "fresh-1" } });
      await tick();
      expect(stored).toEqual(["fresh-1"]);
      expect(actions.some((a) => a.type === "notice" && (a as { terminal?: boolean }).terminal)).toBe(false);
      session.dispose();
    });

    it("keeps a conversation with history terminal (HR-SESS-001), never recreating it", async () => {
      const { ws, actions, stored, session } = await open([{ id: 1, role: "user", content: "hi", timestamp: 1 }]);
      expect(ws.last("session.create")).toBeUndefined();
      expect(stored).toEqual([]);
      expect(actions.some((a) => a.type === "notice" && (a as { terminal?: boolean }).terminal)).toBe(true);
      session.dispose();
    });
  });

  describe("older history pages (HG-104)", () => {
    const turn = (id: number) => ({ id, role: id % 2 ? "user" : "assistant", content: `m${id}`, timestamp: id });
    const range = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => turn(from + i));

    function make(answer: (page: { order: string; limit: number; offset: number }) => unknown) {
      const pages: Array<{ order: string; limit: number; offset: number }> = [];
      const actions: ChatAction[] = [];
      const client = {
        messages: async (_d: string, _s: string, _p: unknown, page: { order: string; limit: number; offset: number }) => {
          pages.push(page);
          return answer(page);
        },
        settled: async () => undefined,
      } as unknown as GatewayClient;
      const session = new ChatSession({ client, deviceId: "dev-mac", storedSessionId: "s-1", dispatch: (a) => actions.push(a) });
      return { session, pages, actions };
    }
    const paged = (messages: unknown[], page: { limit: number }) => ({ messages, pagination: { ...page, returned: messages.length } });

    it("opens on the newest 100 rows and reports that older ones may exist", async () => {
      const { session, pages, actions } = make((page) => paged(range(101, 200), page));
      expect(await session.loadHistory()).toBe(true);
      expect(pages).toEqual([{ order: "latest", limit: 100, offset: 0 }]);
      expect(actions).toContainEqual({ type: "history", rows: range(101, 200), hasOlder: true });
    });

    it("asks for the next page at the count of stored rows loaded", async () => {
      const { session, pages, actions } = make((page) => paged(range(1, 60), page));
      await session.loadOlder({ rows: range(61, 260), epoch: 3 });
      expect(pages).toEqual([{ order: "latest", limit: 100, offset: 200 }]);
      expect(actions).toEqual([{ type: "older-loading" }, { type: "older-loaded", rows: range(1, 60), hasMore: false, epoch: 3 }]);
    });

    it("skips ahead when a page only overlaps what is loaded (a page or more arrived since)", async () => {
      const { session, pages, actions } = make((page) => paged(page.offset === 100 ? range(301, 400) : range(1, 100), page));
      await session.loadOlder({ rows: range(101, 200), epoch: 1 });
      expect(pages.map((p) => p.offset)).toEqual([100, 200]);
      expect(actions.at(-1)).toEqual({ type: "older-loaded", rows: range(1, 100), hasMore: true, epoch: 1 });
    });

    it("one request at a time", async () => {
      const { session, pages } = make((page) => paged(range(1, 10), page));
      await Promise.all([session.loadOlder({ rows: range(11, 20), epoch: 1 }), session.loadOlder({ rows: range(11, 20), epoch: 1 })]);
      expect(pages).toHaveLength(1);
    });

    it("the full transcript pages oldest-first through every row", async () => {
      const all = range(1, 1100);
      const { session, pages } = make((page) => paged(all.slice(page.offset, page.offset + page.limit), page));
      expect((await session.loadFullHistory()).map((r) => r.id)).toEqual(all.map((r) => r.id));
      expect(pages.map((p) => [p.order, p.limit, p.offset])).toEqual([["oldest", 500, 0], ["oldest", 500, 500], ["oldest", 500, 1000]]);
    });

    it("a failure becomes a retryable HR-SYNC-001 carrying the cause", async () => {
      const { session, actions } = make(() => {
        throw new GatewayHttpError(502, null, "x");
      });
      await session.loadOlder({ rows: range(101, 200), epoch: 2 });
      const failed = actions.at(-1) as Extract<ChatAction, { type: "older-failed" }>;
      expect(failed.type).toBe("older-failed");
      expect(failed.epoch).toBe(2);
      expect(failed.error).toMatchObject({ code: "HR-SYNC-001", retryable: true });
      expect(failed.error.details).toContain("HR-SYNC-003");
    });
  });
});
