import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile, symlink, realpath } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { ArtifactDownloadSource } from "./artifact-download-source.js";

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
