import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import { request as httpRequest, createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { WebAppHost } from "./web-app-host.js";

interface RawResponse {
  status: number;
  headers: Record<string, string | string[] | undefined>;
  body: string;
}

async function withHost(
  run: (get: (path: string, method?: string) => Promise<RawResponse>, root: string) => Promise<void>,
): Promise<void> {
  const root = await mkdtemp(join(tmpdir(), "hr-web-app-"));
  const dist = join(root, "dist");
  await mkdir(join(dist, "assets"), { recursive: true });
  await writeFile(join(dist, "index.html"), "<!doctype html><title>Hermes GO</title>");
  await writeFile(join(dist, "assets", "index-abc123.js"), "console.log('app')");
  await writeFile(join(dist, "sw.js"), "self.addEventListener('fetch', () => {})");
  await writeFile(join(dist, "manifest.webmanifest"), "{}");
  await writeFile(join(dist, "icon-192.png"), "png");
  await writeFile(join(dist, "notes.unknown"), "?");
  await writeFile(join(root, "secret.txt"), "outside the build");
  await symlink(join(root, "secret.txt"), join(dist, "assets", "leak.txt"));
  const host = new WebAppHost({ dir: dist, webOrigin: "https://web.example.test" });
  const server: Server = createServer((request, response) => {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (!host.handles(url)) {
      response.writeHead(404);
      response.end("not app");
      return;
    }
    void host.handle(request, response, url);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const get = (path: string, method = "GET"): Promise<RawResponse> => new Promise((resolve, reject) => {
    // node:http sends the path verbatim, so traversal attempts reach the host unnormalised.
    const outgoing = httpRequest({ host: "127.0.0.1", port, path, method }, (response) => {
      const chunks: Buffer[] = [];
      response.on("data", (chunk: Buffer) => chunks.push(chunk));
      response.on("end", () => resolve({
        status: response.statusCode ?? 0,
        headers: response.headers,
        body: Buffer.concat(chunks).toString(),
      }));
    });
    outgoing.on("error", reject);
    outgoing.end();
  });
  try {
    await run(get, root);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  }
}

test("the app shell is never cached and carries a strict CSP without inline or external sources", async () => {
  await withHost(async (get) => {
    for (const path of ["/app/", "/app/sessions", "/app/sessions/abc-123"]) {
      const response = await get(path);
      assert.equal(response.status, 200, path);
      assert.match(response.body, /Hermes GO/);
      assert.equal(response.headers["cache-control"], "no-store");
      const csp = String(response.headers["content-security-policy"]);
      assert.match(csp, /default-src 'none'/);
      assert.match(csp, /script-src 'self';/);
      assert.match(csp, /connect-src 'self' wss:\/\/web\.example\.test;/);
      assert.match(csp, /frame-ancestors 'none'/);
      assert.doesNotMatch(csp, /unsafe-inline|unsafe-eval|https:\/\/|\*/);
      assert.equal(response.headers["x-frame-options"], "DENY");
      assert.equal(response.headers["x-content-type-options"], "nosniff");
    }
  });
});

test("hashed assets are immutable; sw.js and the manifest revalidate", async () => {
  await withHost(async (get) => {
    const asset = await get("/app/assets/index-abc123.js");
    assert.equal(asset.status, 200);
    assert.equal(asset.headers["cache-control"], "public, max-age=31536000, immutable");
    assert.equal(asset.headers["content-type"], "text/javascript; charset=utf-8");

    const worker = await get("/app/sw.js");
    assert.equal(worker.headers["cache-control"], "no-cache");
    assert.equal(worker.headers["service-worker-allowed"], "/app/");
    const manifest = await get("/app/manifest.webmanifest");
    assert.equal(manifest.headers["cache-control"], "no-cache");
    assert.equal(manifest.headers["content-type"], "application/manifest+json");
    assert.equal((await get("/app/icon-192.png")).headers["content-type"], "image/png");
  });
});

test("the bare /app path redirects, other methods are refused, missing files are 404", async () => {
  await withHost(async (get) => {
    const redirect = await get("/app");
    assert.equal(redirect.status, 308);
    assert.equal(redirect.headers.location, "/app/");
    assert.equal((await get("/app/", "POST")).status, 405);
    assert.equal((await get("/app/assets/missing.js")).status, 404);
    assert.equal((await get("/app/notes.unknown")).status, 404);
    const head = await get("/app/", "HEAD");
    assert.equal(head.status, 200);
    assert.equal(head.body, "");
  });
});

test("path traversal, dotfiles, encoded separators and symlinks out of the build are refused", async () => {
  await withHost(async (get) => {
    for (const path of [
      "/app/../secret.txt",
      "/app/assets/../../secret.txt",
      "/app/%2e%2e/secret.txt",
      "/app/assets/%2e%2e%2f%2e%2e%2fsecret.txt",
      "/app/assets/..%5c..%5csecret.txt",
      "/app/.env",
      "/app/assets//index-abc123.js",
      "/app/assets/%00.js",
      "/app/assets/leak.txt",
    ]) {
      const response = await get(path);
      assert.notEqual(response.body, "outside the build", path);
      assert.equal(response.status, 404, path);
    }
  });
});
