import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { WebSocket } from "ws";
import type { TunnelHttpRequest, WireMessage } from "@hermes-remote/protocol";
import { ArtifactDownloadBroker } from "./artifact-download-broker.js";

async function fixture(t: import("node:test").TestContext, maxConcurrentRanges = 1) {
  const socket = Object.assign(new EventEmitter(), { readyState: WebSocket.OPEN }) as unknown as WebSocket;
  const messages: WireMessage[] = [];
  let waiting: ((message: WireMessage) => void) | undefined;
  let permitted = true;
  const broker = new ArtifactDownloadBroker((_socket, message) => {
    if (waiting) { const resolve = waiting; waiting = undefined; resolve(message); }
    else messages.push(message);
  }, 1000, 64, maxConcurrentRanges);
  const server = createServer((request, response) => {
    const url = new URL(request.url!, "http://127.0.0.1");
    void (async () => {
      if (await broker.accept(request, response, url)) return;
      broker.forward(request, response, url, { socket, deviceId: "mac-a", routingKey: "owned-mac-a" }, async () => {
        if (!permitted) throw new Error("access_revoked");
      });
    })().catch(() => response.destroy());
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); assert.ok(address && typeof address !== "string");
  const origin = `http://127.0.0.1:${address.port}`;
  t.after(async () => {
    broker.close(); server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  const next = () => messages.length ? Promise.resolve(messages.shift()!) : new Promise<WireMessage>((resolve) => { waiting = resolve; });
  const start = (signal?: AbortSignal, reference = "a".repeat(64)) => fetch(`${origin}/api/files/downloads/${reference}`, {
    headers: { "if-match": `"${"b".repeat(64)}"`, range: "bytes=0-5" }, signal,
  });
  const respond = (message: TunnelHttpRequest, ticket = message.headers["x-hermes-file-ticket"]) => fetch(
    `${origin}/api/file-channel/${message.id}`, { method: "PUT", body: "abcdef",
      headers: { authorization: `Bearer ${ticket}`, "x-hermes-file-status": "206", "content-type": "application/octet-stream",
        etag: `"${"b".repeat(64)}"`, "content-range": "bytes 0-5/6" } });
  return { socket, broker, origin, next, start, respond, revoke: () => { permitted = false; broker.revalidateAll(); } };
}

test("independent return capabilities are single-use and Mac capacity does not close control", async (t) => {
  const f = await fixture(t);
  const download = f.start();
  const message = await f.next() as TunnelHttpRequest;
  assert.equal(message.type, "tunnel.http.request");
  assert.equal(message.targetDeviceId, "mac-a");
  assert.equal((await f.respond(message, "forged")).status, 403);
  const busy = await f.start();
  assert.equal(busy.status, 503);
  assert.equal((await busy.json() as { error: { code: string } }).error.code, "HR-FILE-013");
  const returned = f.respond(message);
  const response = await download;
  assert.equal(response.status, 206);
  assert.equal(await response.text(), "abcdef");
  assert.equal((await returned).status, 204);
  assert.equal((await f.respond(message)).status, 403);
  assert.equal(f.socket.readyState, WebSocket.OPEN);
});

test("four ranges share one reference, capacity is released per range, and other files stay isolated", async (t) => {
  const f = await fixture(t, 4);
  const downloads: Promise<Response>[] = [], messages: TunnelHttpRequest[] = [];
  for (let index = 0; index < 4; index++) {
    downloads.push(f.start()); messages.push(await f.next() as TunnelHttpRequest);
  }
  assert.equal((await f.start()).status, 503);
  assert.equal((await f.start(undefined, "c".repeat(64))).status, 503);
  await f.respond(messages[0]); assert.equal(await (await downloads[0]).text(), "abcdef");
  // Releasing one lane cannot remove the other three lanes' reference reservation.
  assert.equal((await f.start(undefined, "c".repeat(64))).status, 503);
  const replacement = f.start(); const message = await f.next() as TunnelHttpRequest;
  for (let index = 1; index < 4; index++) {
    await f.respond(messages[index]); assert.equal(await (await downloads[index]).text(), "abcdef");
  }
  await f.respond(message); assert.equal(await (await replacement).text(), "abcdef");
  const other = f.start(undefined, "c".repeat(64)); const otherMessage = await f.next() as TunnelHttpRequest;
  await f.respond(otherMessage); assert.equal(await (await other).text(), "abcdef");
});

test("phone cancellation consumes the return capability and releases the Mac", async (t) => {
  const f = await fixture(t);
  const controller = new AbortController();
  const download = f.start(controller.signal);
  const message = await f.next() as TunnelHttpRequest;
  controller.abort();
  await assert.rejects(download);
  const cancel = await f.next();
  assert.equal(cancel.type, "tunnel.http.cancel");
  assert.equal((await f.respond(message)).status, 403);
  const fresh = f.start();
  const replacement = await f.next() as TunnelHttpRequest;
  const returned = f.respond(replacement);
  assert.equal(await (await fresh).text(), "abcdef"); await returned;
});

test("revocation cannot leave a downloadable pending ticket", async (t) => {
  const f = await fixture(t);
  const download = f.start();
  const message = await f.next() as TunnelHttpRequest;
  f.revoke();
  const denied = await download;
  assert.equal(denied.status, 502); await denied.text();
  assert.equal((await f.respond(message)).status, 403);
  assert.equal(f.socket.readyState, WebSocket.OPEN);
});

test("Connector disconnect invalidates its pending return capability", async (t) => {
  const f = await fixture(t);
  const download = f.start();
  const message = await f.next() as TunnelHttpRequest;
  f.socket.emit("close");
  await assert.rejects(download);
  assert.equal((await f.respond(message)).status, 403);
});
