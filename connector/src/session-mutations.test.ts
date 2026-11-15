import test from "node:test";
import assert from "node:assert/strict";
import { WebSocketServer } from "ws";
import { sessionMutationResponse } from "./session-mutations.js";

async function fixture(options: { authority?: unknown; deletion?: object; consoleStatus?: string; initialStatus?: string } = {}) {
  const server = new WebSocketServer({ port: 0, host: "127.0.0.1" });
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const port = (server.address() as { port: number }).port;
  const calls: { path: string; value: Record<string, unknown> }[] = [];
  server.on("connection", (socket, request) => {
    const path = request.url!;
    if (path.startsWith("/api/console")) socket.send(JSON.stringify({ type: "ready" }));
    else socket.send(JSON.stringify({ method: "event", params: { type: "gateway.ready", payload: options.authority === undefined ? {} : { session_authority: options.authority } } }));
    socket.on("message", (raw) => {
      const value = JSON.parse(raw.toString()); calls.push({ path, value });
      if (path.startsWith("/api/console")) {
        const command = value.line || value.command;
        socket.send(JSON.stringify({ type: "complete", id: 1, command,
          status: value.type === "command" ? options.initialStatus || "confirm_required" : options.consoleStatus || "ok" }));
      } else socket.send(JSON.stringify({ id: value.id, ...(options.deletion || { result: { deleted: "s1" } }) }));
    });
  });
  return { calls, auth: { websocketUrl: async (path: string) => `ws://127.0.0.1:${port}${path}`,
      consoleWebsocketUrl: async () => `ws://127.0.0.1:${port}/api/console`,
      request: async (path: string, init: RequestInit) => {
        calls.push({ path, value: { method: init.method } });
        return Response.json(path.includes("mutation-snapshot") ? { session_id: "s1", exists: true, runtime_revision: 3, runtime_generation: 2 } : { ok: true });
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
  const f = await fixture(); try {
    const title = `汉字 ' 和双引号 " 标题`;
    const response = await sessionMutationResponse("PATCH", "/api/sessions/s1", rename(title), f.auth, signal());
    assert.equal(response?.status, 200); assert.equal((await response!.json()).title, title);
    assert.equal(f.calls.length, 2); assert.equal(f.calls[0]?.path, "/api/console?profile=work");
    assert.equal(f.calls[0]?.value.type, "command"); assert.equal(f.calls[1]?.value.type, "confirm");
    assert.equal(f.calls[1]?.value.command, f.calls[0]?.value.line);
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
    assert.equal(f.calls.length, 1);
  } finally { await f.close(); }
});
