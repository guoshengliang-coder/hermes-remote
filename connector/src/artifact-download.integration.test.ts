import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, mkdir, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { WebSocketServer, type WebSocket } from "ws";

test("HG-217: existing temporary attachments stream through the real Connector", { timeout: 20_000 }, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "artifact-download-")));
  const shared = await mkdtemp("/tmp/artifact-download-");
  t.after(() => Promise.all([rm(root, { recursive: true, force: true }), rm(shared, { recursive: true, force: true })]));
  const filesRoot = join(root, "files");
  const report = join(shared, "report.html");
  const content = Buffer.from("<!doctype html><title>历史附件</title>".repeat(10_000));
  await mkdir(filesRoot);
  await writeFile(report, content);
  await writeFile(join(root, "report.txt"), "system temporary report");
  await writeFile(join(filesRoot, "existing.txt"), "existing attachment");
  await writeFile(join(shared, "large.bin"), Buffer.alloc(1024 * 1024 + 1));
  await symlink(report, join(filesRoot, "escape.html"));

  const server = createServer((_request, response) => response.writeHead(404).end());
  const wss = new WebSocketServer({ server });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  let output = "";
  let socket: WebSocket;
  type Reply = { status: number; headers: Record<string, string>; body: Buffer; chunks: number };
  const pending = new Map<string, { reply: Reply; resolve: (reply: Reply) => void }>();
  const connected = new Promise<void>((resolve) => {
    wss.on("connection", (current) => {
      socket = current;
      current.on("message", (raw) => {
        const message = JSON.parse(raw.toString());
        if (message.type === "hello") {
          current.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "test-mac" }));
          resolve();
          return;
        }
        const request = pending.get(message.requestId);
        if (!request) return;
        if (message.type === "tunnel.http.response" || message.type === "tunnel.http.response.start") {
          request.reply.status = message.status;
          request.reply.headers = message.headers;
        }
        if (message.type === "tunnel.http.response") {
          request.reply.body = Buffer.from(message.bodyBase64 ?? "", "base64");
          request.resolve(request.reply);
          pending.delete(message.requestId);
        }
        if (message.type === "tunnel.http.response.chunk") {
          request.reply.body = Buffer.concat([request.reply.body, Buffer.from(message.dataBase64, "base64")]);
          request.reply.chunks++;
          current.send(JSON.stringify({
            type: "tunnel.http.response.ack", version: 1,
            requestId: message.requestId, sequence: message.sequence,
          }));
        }
        if (message.type === "tunnel.http.response.end") {
          request.resolve(request.reply);
          pending.delete(message.requestId);
        }
      });
    });
  });
  const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
    env: {
      PATH: process.env.PATH, HOME: root, TMPDIR: root,
      CONNECTOR_MODE: "legacy", HERMES_MODE: "mock", SESSION_OBSERVER_ENABLED: "0",
      DEVICE_ID: "test-mac", GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`,
      CONNECTOR_TOKEN: "fixture-token", HERMES_SESSION_TOKEN: "fixture-session-token",
      HERMES_BASE_URL: `http://127.0.0.1:${address.port}`, FILES_ROOT: filesRoot,
      MAX_FILE_BYTES: String(1024 * 1024),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (data) => { output += data.toString(); });
  child.stderr.on("data", (data) => { output += data.toString(); });
  t.after(async () => {
    if (child.exitCode === null) {
      const exited = once(child, "exit");
      child.kill("SIGTERM");
      const force = setTimeout(() => child.kill("SIGKILL"), 2_000);
      await exited;
      clearTimeout(force);
    }
    for (const client of wss.clients) client.terminate();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  await Promise.race([connected, once(child, "exit").then(() => { throw new Error(output); })]);
  const download = (path: string, method = "GET"): Promise<Reply> => new Promise((resolve) => {
    const id = randomUUID();
    pending.set(id, { reply: { status: 0, headers: {}, body: Buffer.alloc(0), chunks: 0 }, resolve });
    socket.send(JSON.stringify({
      type: "tunnel.http.request", version: 1, id, targetDeviceId: "test-mac",
      method, path: `/api/files?path=${encodeURIComponent(path)}`, headers: {},
    }));
  });

  const response = await download(report);
  assert.equal(response.status, 200, output);
  assert.deepEqual(response.body, content);
  assert.ok(response.chunks > 1, "download keeps acknowledged streaming");
  assert.equal(response.headers["content-type"], "application/octet-stream");
  assert.match(response.headers["content-disposition"], /report\.html/);
  assert.deepEqual((await download(await realpath(report))).body, content);
  assert.equal((await download(join(root, "report.txt"))).body.toString(), "system temporary report");
  assert.equal((await download(join(filesRoot, "existing.txt"))).body.toString(), "existing attachment");
  // HG-222: inspection must not download even one chunk, including files above the limit.
  for (const [path, size] of [[report, content.length], [join(shared, "large.bin"), 1024 * 1024 + 1]] as const) {
    const info = await download(path, "HEAD");
    assert.equal(info.status, 200);
    assert.equal(info.headers["content-length"], String(size));
    assert.equal(info.headers["x-hermes-max-file-bytes"], String(1024 * 1024));
    assert.equal(info.body.length, 0);
    assert.equal(info.chunks, 0);
  }
  for (const [path, status, error] of [
    [join(filesRoot, "escape.html"), 403, "forbidden"],
    [join(shared, "missing.html"), 404, "not_found"],
    [join(shared, "large.bin"), 413, "file_too_large"],
    [shared, 400, "invalid_file"],
    [`${shared}/../outside.html`, 400, "invalid_path"],
  ] as const) {
    const refused = await download(path);
    assert.equal(refused.status, status, path);
    assert.deepEqual(JSON.parse(refused.body.toString()), { error });
    if (status !== 413) {
      const inspected = await download(path, "HEAD");
      assert.equal(inspected.status, status, `HEAD preserves filesystem policy: ${path}`);
      assert.equal(inspected.body.length, 0);
      assert.equal(inspected.chunks, 0);
    }
  }
  assert.ok(!output.includes(report), "refusal logs must not expose the requested path");
});
