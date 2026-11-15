import test from "node:test";
import assert from "node:assert/strict";
import { WebSocketServer } from "ws";
import { sessionMutationResponse } from "./session-mutations.js";

async function fixture(options: { authority?: unknown; deletion?: object; consoleStatus?: string; initialStatus?: string; output?: string | null; exists?: boolean; dropProbeBeforeConsoleReady?: boolean } = {}) {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const port = (server.address() as { port: number }).port;
  const calls: { path: string; value: Record<string, unknown> }[] = [];
  let probe: import("ws").WebSocket | undefined;
  server.on("connection", (socket, request) => {
    const path = request.url!;
    if (path.startsWith("/api/console")) {
      const ready = () => { if (socket.readyState === 1) socket.send(JSON.stringify({ type: "ready" })); };
      if (options.dropProbeBeforeConsoleReady && probe) { probe.once("close", () => setImmediate(ready)); probe.close(); }
      else ready();
    } else { probe = socket; socket.send(JSON.stringify({ method: "event", params: { type: "gateway.ready", payload: options.authority === undefined ? {} : { session_authority: options.authority } } })); }
    socket.on("message", (raw) => {
      const value = JSON.parse(raw.toString()); calls.push({ path, value });
      if (path.startsWith("/api/console")) {
        const command = value.line || value.command;
        if (value.type === "confirm" && options.output !== null) socket.send(JSON.stringify({ type: "output", id: 2, stream: "stdout", command,
          data: options.output ?? "Session 's1' renamed to: new title\n" }));
        socket.send(JSON.stringify({ type: "complete", id: value.type === "command" ? 1 : 2, command,
          status: value.type === "command" ? options.initialStatus || "confirm_required" : options.consoleStatus || "ok" }));
      } else socket.send(JSON.stringify({ id: value.id, ...(options.deletion || { result: { deleted: "s1" } }) }));
    });
  });
  return { calls, auth: { websocketUrl: async (path: string) => `ws://127.0.0.1:${port}${path}`,
      consoleWebsocketUrl: async () => `ws://127.0.0.1:${port}/api/console`,
      request: async (path: string, init: RequestInit) => {
        calls.push({ path, value: { method: init.method } });
        return Response.json(path.includes("mutation-snapshot") ? { session_id: "s1", exists: options.exists ?? true, runtime_revision: 3, runtime_generation: 2 } : { ok: true });
      } },
    close: async () => { for (const socket of server.clients) socket.terminate(); await new Promise<void>((resolve) => server.close(() => resolve())); } };
}
const signal = () => new AbortController().signal;
const rename = (title = "new title") => Buffer.from(JSON.stringify({ title, profile: "work", request_id: "fence", expected_revision: 3 }));
test("delete uses only durable ID and original profile, never resume", async () => {
  const f = await fixture(); try {
    const response = await sessionMutationResponse("DELETE", "/api/sessions/s1?profile=work&request_id=fence&expected_revision=3", undefined, f.auth, signal());
    assert.equal(response?.status, 200); assert.deepEqual(f.calls[0]?.value, { jsonrpc: "2.0", id: 1, method: "session.delete", params: { session_id: "s1", profile: "work" } });
    assert.equal(f.calls.length, 1);
  } finally { await f.close(); }
});
test("rename uses a dedicated profile-scoped curated console and exact confirmation", async () => {
  const title = `汉字 ' 和双引号 " 标题`;
  const f = await fixture({ output: `Session 's1' renamed to: ${title}\n` }); try {
    const response = await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(title), f.auth, signal());
    assert.equal(response?.status, 200); assert.equal((await response!.json()).title, title);
    const consoleCalls = f.calls.filter((c) => c.path.startsWith("/api/console"));
    assert.equal(consoleCalls.length, 2); assert.equal(consoleCalls[0]?.path, "/api/console?profile=work");
    assert.equal(consoleCalls[0]?.value.type, "command"); assert.equal(consoleCalls[1]?.value.type, "confirm");
    assert.equal(consoleCalls[1]?.value.command, consoleCalls[0]?.value.line);
  } finally { await f.close(); }
});
test("authority preserves the original REST path and sends no legacy mutation", async () => {
  const f = await fixture({ authority: true }); try {
    assert.equal(await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, signal()), undefined);
    assert.equal(await sessionMutationResponse("DELETE", "/api/sessions/s1", undefined, f.auth, signal()), undefined);
    assert.deepEqual(f.calls, []);
  } finally { await f.close(); }
});
for (const code of [4023, 4007, 5036, -32601]) test(`delete RPC ${code} never falls back or retries`, async () => {
  const f = await fixture({ deletion: { error: { code } } }); try {
    const response = await sessionMutationResponse("DELETE", "/api/sessions/s1", undefined, f.auth, signal());
    assert.equal(response?.status, code >= 4000 && code < 5000 ? 409 : 502); assert.equal(f.calls.length, 1);
  } finally { await f.close(); }
});
test("refused console completion does not report rename success", async () => {
  const f = await fixture({ consoleStatus: "error" }); try {
    assert.equal((await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, signal()))?.status, 502);
  } finally { await f.close(); }
});
test("unknown mode does not write; malformed/combined PATCH is not adapted", async () => {
  const f = await fixture({ authority: "future" }); try {
    assert.equal((await sessionMutationResponse("DELETE", "/api/sessions/s1", undefined, f.auth, signal()))?.status, 409);
    assert.equal(await sessionMutationResponse("PATCH", "/api/sessions/s1", Buffer.from('{"title":"x","archived":true}'), f.auth, signal()), undefined);
    assert.deepEqual(f.calls, []);
  } finally { await f.close(); }
});
test("aborted work cannot open a mutation socket", async () => {
  const f = await fixture(); try {
    const controller = new AbortController(); controller.abort();
    await assert.rejects(sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, controller.signal));
    assert.deepEqual(f.calls, []);
  } finally { await f.close(); }
});

test("authority delete fills only a missing generation from a matching snapshot", async () => {
  const f = await fixture({ authority: true }); try {
    assert.equal((await sessionMutationResponse("DELETE", "/api/sessions/s1?profile=work&request_id=f&expected_revision=3", undefined, f.auth, signal()))?.status, 200);
    assert.equal(f.calls.length, 2);
    assert.match(f.calls[1]!.path, /expected_generation=2/);
    assert.match(f.calls[1]!.path, /expected_revision=3/);
    f.calls.length = 0;
    assert.equal((await sessionMutationResponse("DELETE", "/api/sessions/s1?profile=work&request_id=f&expected_revision=1", undefined, f.auth, signal()))?.status, 409);
    assert.equal(f.calls.length, 1); assert.equal(f.calls[0]!.value.method, "GET");
  } finally { await f.close(); }
});

test("console validation failure before confirmation completes promptly without a second command", async () => {
  const f = await fixture({ initialStatus: "error" }); try {
    assert.equal((await sessionMutationResponse("PATCH", "/api/sessions/s1", rename("unsupported; title"), f.auth, signal()))?.status, 502);
    assert.equal(f.calls.filter((c) => c.value.type).length, 1);
  } finally { await f.close(); }
});

test("missing exact stored target is refused before Console can resolve an alias", async () => {
  const f = await fixture({ exists: false }); try {
    assert.equal((await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, signal()))?.status, 409);
    assert.equal(f.calls.filter((c) => c.value.type).length, 0);
  } finally { await f.close(); }
});
for (const output of [null, "Session 'other' renamed to: new title\n", "Session 's1' renamed to: different\n"]) test(`Console completion without matching target/title is unconfirmed: ${output}`, async () => {
  const f = await fixture({ output }); try {
    assert.equal((await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, signal()))?.status, 502);
  } finally { await f.close(); }
});

test("loss of mode connection cancels the child Console before a command", async () => {
  const f = await fixture({ dropProbeBeforeConsoleReady: true }); try {
    assert.equal((await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, signal()))?.status, 502);
    assert.equal(f.calls.filter((c) => c.value.type).length, 0);
  } finally { await f.close(); }
});
test("cancellation after snapshot cannot send a Console command", async () => {
  const f = await fixture(); const controller = new AbortController(); try {
    const request = f.auth.request;
    f.auth.request = async (path, init) => { const response = await request(path, init); controller.abort(); return response; };
    await assert.rejects(sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, controller.signal));
    assert.equal(f.calls.filter((c) => c.value.type).length, 0);
  } finally { await f.close(); }
});
test("same-target delete cannot interleave with rename and the guard is released afterwards", async () => {
  const f = await fixture(); try {
    let entered!: () => void, release!: () => void;
    const snapshotEntered = new Promise<void>((resolve) => { entered = resolve; });
    const blocked = new Promise<void>((resolve) => { release = resolve; });
    const request = f.auth.request;
    f.auth.request = async (path, init) => { entered(); await blocked; return request(path, init); };
    const first = sessionMutationResponse("PATCH", "/api/sessions/s1", rename(), f.auth, signal());
    await snapshotEntered;
    assert.equal((await sessionMutationResponse("DELETE", "/api/sessions/s1?profile=work", undefined, f.auth, signal()))?.status, 409);
    release(); assert.equal((await first)?.status, 200);
    assert.equal((await sessionMutationResponse("DELETE", "/api/sessions/s1?profile=work", undefined, f.auth, signal()))?.status, 200);
  } finally { await f.close(); }
});
test("authority response body is completed before its connection scope is disposed", async () => {
  const f = await fixture({ authority: true }); try {
    const request = f.auth.request;
    f.auth.request = async (path, init) => {
      if (init.method !== "DELETE") return request(path, init);
      return new Response(new ReadableStream({ start(controller) {
        const abort = () => controller.error(new Error("response aborted"));
        init.signal?.addEventListener("abort", abort, { once: true });
        controller.enqueue(new TextEncoder().encode('{"ok":'));
        setTimeout(() => {
          if (init.signal?.aborted) return;
          controller.enqueue(new TextEncoder().encode('true}')); controller.close();
          init.signal?.removeEventListener("abort", abort);
        }, 10);
      } }), { headers: { "content-type": "application/json" } });
    };
    const result = await sessionMutationResponse("DELETE", "/api/sessions/s1?profile=work&request_id=f&expected_revision=3", undefined, f.auth, signal());
    assert.equal(await result!.text(), '{"ok":true}');
  } finally { await f.close(); }
});
for (const query of ["profile=work&profile=default", "expected_revision=-1", "expected_revision=1e3", "expected_revision=", "expected_generation=true", "request_id="]) test(`ambiguous or invalid DELETE contract cannot reach a socket: ${query}`, async () => {
  const f = await fixture(); try {
    assert.equal((await sessionMutationResponse("DELETE", `/api/sessions/s1?${query}`, undefined, f.auth, signal()))?.status, 409);
    assert.equal(f.calls.length, 0);
  } finally { await f.close(); }
});
for (const fence of [{ expected_revision: "3" }, { expected_generation: -1 }, { request_id: {} }]) test(`invalid PATCH fence is refused before any read: ${JSON.stringify(fence)}`, async () => {
  const f = await fixture(); try {
    const body = Buffer.from(JSON.stringify({ title: "new title", ...fence }));
    assert.equal((await sessionMutationResponse("PATCH", "/api/sessions/s1", body, f.auth, signal()))?.status, 409);
    assert.equal(f.calls.length, 0);
  } finally { await f.close(); }
});
for (const path of ["https://other.invalid/api/sessions/s1", "//other.invalid/api/sessions/s1", "/api/sessions/../sessions/s1"]) test(`noncanonical tunnel path is never adapted: ${path}`, async () => {
  const f = await fixture(); try {
    assert.equal(await sessionMutationResponse("DELETE", path, undefined, f.auth, signal()), undefined);
    assert.equal(f.calls.length, 0);
  } finally { await f.close(); }
});
