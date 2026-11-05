import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { WebSocket, WebSocketServer } from "ws";
import { DoubaoVoiceProxy } from "./doubao-voice-proxy.js";

// MockTimers replaces global timer functions for this process. Keep this case in a separate
// node:test file: a previous real WebSocket may still be emitting close after its server's close
// callback, and a mocked clearTimeout cannot cancel that socket's native 30-second close timer.
// Node's default per-file process isolation keeps both timer lifetimes intact.
test("the bounded relay survives 90 seconds and allows five minutes plus final-result headroom", async (t) => {
  const { VOICE_MAX_SESSION_MS } = await import("./doubao-voice-proxy.js");
  assert.equal(VOICE_MAX_SESSION_MS, 330_000);
  const upstream = new WebSocketServer({ port: 0 }), clients = new WebSocketServer({ port: 0 });
  await Promise.all([once(upstream, "listening"), once(clients, "listening")]);
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const proxy = new DoubaoVoiceProxy("test-key", `ws://127.0.0.1:${(upstream.address() as { port: number }).port}`);
  clients.on("connection", (socket) => proxy.open(socket));
  const connected = once(upstream, "connection");
  const phone = new WebSocket(`ws://127.0.0.1:${(clients.address() as { port: number }).port}`);
  try {
    await once(phone, "open");
    const [provider] = await connected as [WebSocket];
    provider.on("message", (data) => provider.send(data, { binary: true }));
    // A relayed reply confirms the upstream client finished its handshake too.
    const reply = once(phone, "message"); provider.send(Buffer.from([1])); await reply;
    const echo = async () => {
      const message = once(phone, "message");
      const closed = once(phone, "close").then(() => { throw new Error("relay closed before its deadline"); });
      phone.send(Buffer.from([2]), { binary: true });
      const [data] = await Promise.race([message, closed]);
      assert.deepEqual(Buffer.from(data as Buffer), Buffer.from([2]));
    };
    t.mock.timers.tick(90_000); await echo(); assert.equal(phone.readyState, WebSocket.OPEN);
    t.mock.timers.tick(240_000 - 1); await echo(); assert.equal(phone.readyState, WebSocket.OPEN);
    const closed = once(phone, "close"), upstreamClosed = once(provider, "close");
    t.mock.timers.tick(1);
    assert.equal((await closed)[0], 1000); await upstreamClosed;
    assert.equal(proxy.atCapacity, false);
  } finally {
    t.mock.timers.reset(); phone.terminate(); for (const socket of upstream.clients) socket.terminate();
    await new Promise<void>((resolve) => clients.close(() => resolve()));
    await new Promise<void>((resolve) => upstream.close(() => resolve()));
  }
});
