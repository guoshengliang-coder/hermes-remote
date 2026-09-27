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
