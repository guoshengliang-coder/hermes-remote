import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, symlink, realpath } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { ArtifactDownloadSource } from "./artifact-download-source.js";

async function transferFixture(t: import("node:test").TestContext, bytes = 1024, rate = 1024 * 1024) {
  const root = await realpath(await mkdtemp(join(tmpdir(), "artifact-speed-")));
  const file = join(root, "fixture.bin"); await writeFile(file, Buffer.alloc(bytes, 7));
  const ports = new Set<number>(), statuses: number[] = [];
  const server = createServer(async (request, response) => {
    ports.add(request.socket.remotePort!); statuses.push(Number(request.headers["x-hermes-file-status"]));
    for await (const _chunk of request) { /* Consume the binary stream with real backpressure. */ }
    response.writeHead(204).end();
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); assert.ok(address && typeof address !== "string");
  const source = new ArtifactDownloadSource(root, `ws://127.0.0.1:${address.port}/v1/connect`, () => "video/mp4", bytes, rate);
  t.after(async () => {
    source.clear(); server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  });
  const descriptor = await source.prepare(file);
  const request = (reference = descriptor.artifactId, range = `bytes=0-${bytes - 1}`) => {
    const id = randomUUID();
    return { type: "tunnel.http.request" as const, version: 1 as const, id, targetDeviceId: "mac", method: "GET",
      path: `/api/files/downloads/${reference}`, headers: { "x-hermes-file-return": id,
        "x-hermes-file-ticket": "c".repeat(64), "if-match": descriptor.etag, range } };
  };
  return { source, descriptor, request, ports, statuses };
}

test("sequential ranges reuse one return connection and disconnect destroys that pool", async (t) => {
  const f = await transferFixture(t);
  for (let index = 0; index < 3; index++) await f.source.respond(f.request(), new AbortController().signal);
  assert.equal(f.ports.size, 1);
  assert.deepEqual(f.statuses, [206, 206, 206]);
  assert.equal(f.descriptor.maxConcurrentRanges, 4); assert.equal(f.descriptor.maxRangeBytes, 1024 * 1024);
  f.source.clear();
  await f.source.respond(f.request(), new AbortController().signal);
  assert.equal(f.statuses.at(-1), 410); assert.equal(f.ports.size, 2);
});

test("parallel ranges share the total upload rate and cannot admit a fifth range or a different file", async (t) => {
  const f = await transferFixture(t, 65536);
  const signal = new AbortController().signal;
  const started = performance.now();
  const streams = Array.from({ length: 4 }, () => f.source.respond(f.request(), signal));
  const refused = [f.source.respond(f.request(), signal), f.source.respond(f.request("d".repeat(64)), signal)];
  await Promise.all([...streams, ...refused]);
  assert.equal(f.statuses.filter(status => status === 206).length, 4);
  assert.equal(f.statuses.filter(status => status === 503).length, 2);
  assert.ok(performance.now() - started >= 220, "four 64 KiB ranges retain one 1 MiB/s budget");
  assert.ok(f.ports.size <= 4);
  await f.source.respond(f.request(), signal);
  assert.equal(f.statuses.at(-1), 206, "finishing concurrent streams releases the reservation");
});

test("independent source is outbound-only and retains the original filesystem grant", async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "artifact-source-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = join(root, "video.mp4");
  await writeFile(file, "abcdef");
  const source = new ArtifactDownloadSource(root, "ws://127.0.0.1:1234/v1/connect", () => "video/mp4", 1000);
  const first = await source.prepare(file), second = await source.prepare(file);
  assert.equal(first.sizeBytes, 6); assert.equal(first.etag, second.etag);
  assert.notEqual(first.artifactId, second.artifactId);
  assert.ok(!JSON.stringify(first).includes(root));
  await assert.rejects(() => source.prepare(`${root}/../forbidden`));
  await symlink("/etc/passwd", join(root, "escape"));
  await assert.rejects(() => source.prepare(join(root, "escape")));
  await writeFile(file, "ghijkl");
  assert.notEqual((await source.prepare(file)).etag, first.etag);
  // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket -- negative test: this origin must disable downloads; no connection is opened.
  const insecure = new ArtifactDownloadSource(root, "ws://public.example/v1/connect", () => "video/mp4");
  assert.equal(insecure.enabled, false);
  await assert.rejects(() => insecure.prepare(file));
  assert.throws(() => new ArtifactDownloadSource(root, "wss://user:secret@public.example/v1/connect", () => "video/mp4"));
});

test("source references expire and disconnect clearing cannot reopen an old reference", async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "artifact-expiry-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = join(root, "video.mp4"); await writeFile(file, "abcdef");
  let status = 0, body = "", now = 0;
  const server = createServer(async (request, response) => {
    status = Number(request.headers["x-hermes-file-status"]);
    body = ""; for await (const chunk of request) body += chunk.toString();
    response.writeHead(204).end();
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const address = server.address(); assert.ok(address && typeof address !== "string");
  const source = new ArtifactDownloadSource(root, `ws://127.0.0.1:${address.port}/v1/connect`, () => "video/mp4", 1000, 1024 * 1024, () => now);
  const descriptor = await source.prepare(file);
  const request = () => {
    const id = randomUUID();
    return { type: "tunnel.http.request" as const, version: 1 as const, id, targetDeviceId: "mac",
      method: "GET", path: `/api/files/downloads/${descriptor.artifactId}`,
      headers: { "x-hermes-file-return": id, "x-hermes-file-ticket": "c".repeat(64), "if-match": descriptor.etag, range: "bytes=0-5" } };
  };
  now = 600001;
  await source.respond(request(), new AbortController().signal);
  assert.equal(status, 410); assert.equal(JSON.parse(body).error.code, "HR-FILE-015");
  now = 0; source.clear();
  await source.respond(request(), new AbortController().signal);
  assert.equal(status, 410);
});
