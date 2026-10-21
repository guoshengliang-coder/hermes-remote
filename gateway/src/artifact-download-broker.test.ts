import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { WebSocket } from "ws";
import type { TunnelHttpRequest, WireMessage } from "@hermes-remote/protocol";
import { ArtifactDownloadBroker } from "./artifact-download-broker.js";

async function fixture(t: import("node:test").TestContext) {
  const socket = Object.assign(new EventEmitter(), { readyState: WebSocket.OPEN }) as unknown as WebSocket;
  const messages: WireMessage[] = [];
  let waiting: ((message: WireMessage) => void) | undefined;
  let permitted = true;
  const broker = new ArtifactDownloadBroker((_socket, message) => {
    if (waiting) { const resolve = waiting; waiting = undefined; resolve(message); }
    else messages.push(message);
  }, 1000);
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
  const start = (signal?: AbortSignal) => fetch(`${origin}/api/files/downloads/${"a".repeat(64)}`, {
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
