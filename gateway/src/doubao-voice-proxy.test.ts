import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { WebSocket, WebSocketServer } from "ws";
import { DoubaoVoiceProxy } from "./doubao-voice-proxy.js";

test("voice proxy authenticates upstream, relays binary frames, and keeps the key off the phone", async () => {
  const upstream = new WebSocketServer({ port: 0 });
  const phoneServer = new WebSocketServer({ port: 0 });
  await Promise.all([once(upstream, "listening"), once(phoneServer, "listening")]);
  const upstreamPort = (upstream.address() as { port: number }).port;
  const phonePort = (phoneServer.address() as { port: number }).port;
  const credential = "test-secret-credential";
  const proxy = new DoubaoVoiceProxy(credential, `ws://127.0.0.1:${upstreamPort}`);
  let seenKey: string | undefined;
  upstream.on("connection", (socket, request) => {
    seenKey = request.headers["x-api-key"] as string | undefined;
    socket.on("message", (data, binary) => {
      assert.equal(binary, true);
      socket.send(data, { binary: true });
    });
  });
  phoneServer.on("connection", (socket) => proxy.open(socket));
  const phone = new WebSocket(`ws://127.0.0.1:${phonePort}`);
  try {
    await once(phone, "open");
    const response = once(phone, "message");
    phone.send(Buffer.from([0x11, 0x22]), { binary: true });
    const [data, binary] = await response;
    assert.deepEqual(Buffer.from(data as Buffer), Buffer.from([0x11, 0x22]));
    assert.equal(binary, true);
    assert.equal(seenKey, credential);
    assert.equal(phone.protocol, "");
  } finally {
    phone.close();
    await new Promise<void>((resolve) => phoneServer.close(() => resolve()));
    await new Promise<void>((resolve) => upstream.close(() => resolve()));
  }
});

test("voice revocation closes only the matching account session and tears down the provider", async () => {
  const upstream = new WebSocketServer({ port: 0 }), clients = new WebSocketServer({ port: 0 });
  await Promise.all([once(upstream, "listening"), once(clients, "listening")]);
  const proxy = new DoubaoVoiceProxy("test-speech-key", `ws://127.0.0.1:${(upstream.address() as { port: number }).port}`);
  const access = (sessionId: string) => ({ accountId: "account", bindingId: "binding", installationId: "install", sessionId,
    principal: { account: { id: "account" }, installation: { id: "install", kind: "browser", platform: "web", displayName: "Browser" }, sessionId, refreshFamilyId: "family" }, web: true as const });
  let index = 0;
  clients.on("connection", (socket) => proxy.open(socket, access(String(++index)) as import("./app-websocket-authorizer.js").AccountWebSocketAccess));
  const url = `ws://127.0.0.1:${(clients.address() as { port: number }).port}`;
  const first = new WebSocket(url), second = new WebSocket(url);
  try {
    await Promise.all([once(first, "open"), once(second, "open")]);
    const closed = once(first, "close");
    proxy.revoke({ kind: "session", accountId: "account", sessionId: "1" });
    assert.equal((await closed)[0], 4403);
    assert.equal(second.readyState, WebSocket.OPEN);
  } finally {
    first.close(); second.close();
    for (const socket of upstream.clients) socket.terminate();
    await new Promise<void>((resolve) => clients.close(() => resolve()));
    await new Promise<void>((resolve) => upstream.close(() => resolve()));
  }
});

test("speech sockets recheck session liveness immediately and close a definitive revocation", async () => {
  const { accountErrors } = await import("./account/model.js");
  const upstream = new WebSocketServer({ port: 0 }), clients = new WebSocketServer({ port: 0 });
  await Promise.all([once(upstream, "listening"), once(clients, "listening")]);
  const proxy = new DoubaoVoiceProxy("test-key", `ws://127.0.0.1:${(upstream.address() as { port: number }).port}`);
  let rechecks = 0;
  clients.on("connection", (socket) => proxy.open(socket, undefined, async () => {
    rechecks++; throw accountErrors.sessionRevoked();
  }));
  const phone = new WebSocket(`ws://127.0.0.1:${(clients.address() as { port: number }).port}`);
  try {
    const closed = once(phone, "close");
    assert.equal((await closed)[0], 4403); assert.equal(rechecks, 1);
  } finally {
    phone.terminate(); for (const socket of upstream.clients) socket.terminate();
    await new Promise<void>((resolve) => clients.close(() => resolve()));
    await new Promise<void>((resolve) => upstream.close(() => resolve()));
  }
});
