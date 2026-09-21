import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHash, generateKeyPairSync, randomBytes, randomUUID, sign } from "node:crypto";
import { mkdtemp, mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import { Pool } from "pg";
import { WebSocket } from "ws";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type WireMessage,
} from "@hermes-remote/protocol";
import { canonicalConnectorChallenge } from "./account/connector-proof-coordinator.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
const WEB_ORIGIN = "https://web.example.test";
const CSRF = `hgc_${"c".repeat(43)}`;

test("the Web app reaches the device API, WebSocket and inbox with its session cookie only", {
  skip: databaseUrl && process.env.RUN_NETWORK_TESTS === "1"
    ? false
    : "set ACCOUNT_TEST_DATABASE_URL and RUN_NETWORK_TESTS=1",
  timeout: 60_000,
}, async () => {
  assert(databaseUrl);
  const schema = `web_device_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const scopedDatabaseUrl = new URL(databaseUrl);
  scopedDatabaseUrl.searchParams.set("options", `-c search_path=${schema}`);
  const setup = new Pool({ connectionString: scopedDatabaseUrl.toString(), max: 1 });
  const webAppDir = await mkdtemp(join(tmpdir(), "hr-web-app-dist-"));
  let child: ChildProcessWithoutNullStreams | undefined;
  const sockets: WebSocket[] = [];

  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await setup.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    await mkdir(join(webAppDir, "assets"));
    await writeFile(join(webAppDir, "index.html"), "<!doctype html><title>Hermes GO Web</title>");

    const account = randomUUID();
    const otherAccount = randomUUID();
    const browser = randomUUID();
    const staleBrowser = randomUUID();
    const phone = randomUUID();
    const desktop = randomUUID();
    const otherBrowser = randomUUID();
    const browserSession = randomUUID();
    const staleBrowserSession = randomUUID();
    const phoneSession = randomUUID();
    const otherBrowserSession = randomUUID();
    const bindingId = randomUUID();
    const deviceId = "web-mac";
    const tokenKey = "web-device-integration-key-with-at-least-thirty-two-bytes";
    const codec = new TokenCodec(tokenKey);
    const browserAccess = codec.issueAccessToken();
    const staleBrowserAccess = codec.issueAccessToken();
    const phoneAccess = codec.issueAccessToken();
    const otherBrowserAccess = codec.issueAccessToken();
    const { publicKey, privateKey } = generateKeyPairSync("ed25519");
    const publicDer = publicKey.export({ format: "der", type: "spki" });
    const rawPublicKey = publicDer.subarray(publicDer.byteLength - 32);
    const fingerprint = createHash("sha256").update(rawPublicKey).digest("hex");

    await setup.query("INSERT INTO accounts (id) VALUES ($1), ($2)", [account, otherAccount]);
    await setup.query(
      `INSERT INTO installations
         (id, account_id, client_installation_id, kind, platform, display_name, app_version)
       VALUES ($1, $2, $1, 'browser', 'web', 'Safari', 'web'),
              ($3, $2, $3, 'browser', 'web', 'Old Safari', 'web'),
              ($4, $2, $4, 'phone', 'android', 'Phone', 'test'),
              ($5, $2, $5, 'desktop', 'macos', 'Mac mini', 'test'),
              ($6, $7, $6, 'browser', 'web', 'Other Safari', 'web')`,
      [browser, account, staleBrowser, phone, desktop, otherBrowser, otherAccount],
    );
    await setup.query(
      `INSERT INTO account_sessions
         (id, account_id, installation_id, refresh_family_id, access_token_hash, access_expires_at,
          revoked_at)
       VALUES ($1, $2, $3, $1, $4, now() + interval '1 hour', NULL),
              ($5, $2, $6, $5, $7, now() + interval '1 hour', now()),
              ($8, $2, $9, $8, $10, now() + interval '1 hour', NULL),
              ($11, $12, $13, $11, $14, now() + interval '1 hour', NULL)`,
      [
        browserSession, account, browser, codec.hashAccessToken(browserAccess),
        staleBrowserSession, staleBrowser, codec.hashAccessToken(staleBrowserAccess),
        phoneSession, phone, codec.hashAccessToken(phoneAccess),
        otherBrowserSession, otherAccount, otherBrowser, codec.hashAccessToken(otherBrowserAccess),
      ],
    );
    for (const session of [browserSession, phoneSession, otherBrowserSession]) {
      await setup.query(
        `INSERT INTO refresh_tokens (id, session_id, family_id, token_hash, expires_at)
         VALUES ($1, $2, $2, $3, now() + interval '30 days')`,
        [randomUUID(), session, randomBytes(32).toString("hex")],
      );
    }
    await setup.query(
      `INSERT INTO connector_bindings
         (id, account_id, desktop_installation_id, display_name, device_id, public_key,
          key_algorithm, public_key_fingerprint, generation, status, activated_at)
       VALUES ($1, $2, $3, 'Mac mini', $4, $5, 'Ed25519', $6, 1, 'active', now())`,
      [bindingId, account, desktop, deviceId, rawPublicKey, fingerprint],
    );

    const port = await unusedPort();
    const origin = `http://127.0.0.1:${port}`;
    child = spawn(process.execPath, ["dist/index.js"], {
      cwd: process.cwd(),
      env: {
        ...process.env,
        HOST: "127.0.0.1",
        PORT: String(port),
        APP_TOKEN: "web-device-legacy-app-token",
        CONNECTOR_TOKEN: "web-device-legacy-connector-token",
        DEFAULT_DEVICE_ID: deviceId,
        ACCOUNT_AUTH_ENABLED: "1",
        ACCOUNT_BINDING_ENABLED: "1",
        ACCOUNT_MULTI_DEVICE_ENABLED: "1",
        ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
        ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED: "1",
        ACCOUNT_EMAIL_OTP_ENABLED: "1",
        ACCOUNT_EMAIL_OTP_HASH_KEY: "web-device-email-otp-hash-key-32-bytes!",
        ACCOUNT_EMAIL_OTP_ISSUER: WEB_ORIGIN,
        ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
        ACCOUNT_RESEND_WEBHOOK_SECRET: `whsec_${Buffer.from("web-device-resend-webhook-secret-32b").toString("base64")}`,
        ACCOUNT_RESEND_API_KEY: "re_test_web_device_only",
        ACCOUNT_EMAIL_FROM: "Hermes GO <test@example.invalid>",
        ACCOUNT_WEB_SESSION_ENABLED: "1",
        ACCOUNT_WEB_ORIGIN: WEB_ORIGIN,
        ACCOUNT_WEB_DEVICE_ACCESS_ENABLED: "1",
        WEB_APP_ENABLED: "1",
        WEB_APP_DIR: webAppDir,
        ACCOUNT_DATABASE_URL: scopedDatabaseUrl.toString(),
        ACCOUNT_TOKEN_HASH_KEY: tokenKey,
        ACCOUNT_GATEWAY_ORIGIN: origin,
      },
      stdio: "pipe",
    });
    await waitForGateway(child);

    const connector = await openSocket(`ws://127.0.0.1:${port}/v2/connect`);
    sockets.push(connector);
    await authenticateConnector(connector, origin, bindingId, fingerprint, privateKey);
    attachMockConnector(connector);

    const cookie = (access: string): string => (
      `__Host-hermes_go_access=${access}; __Host-hermes_go_csrf=${CSRF}`
    );
    const read = (path: string, access: string, extra: Record<string, string> = {}) => fetch(
      `${origin}/v2/devices/${deviceId}/api${path}`,
      { headers: { cookie: cookie(access), "sec-fetch-site": "same-origin", ...extra } },
    );
    const write = (path: string, access: string, extra: Record<string, string> = {}) => fetch(
      `${origin}/v2/devices/${deviceId}/api${path}`,
      {
        method: "POST",
        headers: {
          cookie: cookie(access),
          origin: WEB_ORIGIN,
          "sec-fetch-site": "same-origin",
          "x-hermes-csrf": CSRF,
          "content-type": "application/octet-stream",
          ...extra,
        },
        body: "bytes",
      },
    );

    // Capability and the served shell.
    const capabilities = await (await fetch(`${origin}/v2/capabilities`)).json() as {
      accountAuth: { webDeviceAccess?: boolean };
    };
    assert.equal(capabilities.accountAuth.webDeviceAccess, true);
    const shell = await fetch(`${origin}/app/sessions/abc`);
    assert.equal(shell.status, 200);
    assert.match(await shell.text(), /Hermes GO Web/);

    // Cookie reads and writes.
    const sessions = await read("/sessions?limit=5", browserAccess);
    assert.equal(sessions.status, 200);
    assert.equal(await sessions.text(), "account:/api/sessions?limit=5");
    assert.equal(sessions.headers.get("x-content-type-options"), "nosniff");
    assert.equal(sessions.headers.get("content-security-policy"), "default-src 'none'; sandbox");
    const withoutFetchMetadata = await fetch(`${origin}/v2/devices/${deviceId}/api/sessions`, {
      headers: { cookie: cookie(browserAccess), origin: WEB_ORIGIN },
    });
    assert.equal(withoutFetchMetadata.status, 200);
    await withoutFetchMetadata.body?.cancel();
    await expectError(read("/sessions", browserAccess, { "sec-fetch-site": "cross-site" }), 403, "HR-AUTH-012");
    const upload = await write("/files/upload?name=a.png", browserAccess);
    assert.equal(upload.status, 200);
    assert.equal(await upload.text(), "account:/api/files/upload?name=a.png");
    await expectError(write("/files/upload?name=a", browserAccess, { "x-hermes-csrf": "" }), 403, "HR-AUTH-012");
    await expectError(
      write("/files/upload?name=a", browserAccess, { origin: "https://evil.example.test" }),
      403,
      "HR-AUTH-012",
    );

    // The Web app is not a Mac administration console.
    await expectError(read("/env", browserAccess), 403, "HR-WEB-001");
    await expectError(write("/env/reveal", browserAccess), 403, "HR-WEB-001");
    await expectError(write("/gateway/restart", browserAccess), 403, "HR-WEB-001");

    // Mac files are never rendered on the Gateway origin.
    const html = await read(`/files?path=${encodeURIComponent("/Users/test/page.html")}`, browserAccess);
    assert.equal(html.status, 200);
    assert.equal(html.headers.get("content-type"), "application/octet-stream");
    assert.equal(html.headers.get("content-disposition"), "attachment; filename=\"page.html\"");
    assert.equal(html.headers.get("content-security-policy"), "default-src 'none'; sandbox");
    const png = await read(`/files?path=${encodeURIComponent("/Users/test/a.png")}`, browserAccess);
    assert.equal(png.headers.get("content-type"), "image/png");
    assert.equal(png.headers.get("content-disposition"), "attachment; filename=\"a.png\"");

    // Credential confusion.
    await expectError(
      read("/sessions", browserAccess, { authorization: `Bearer ${phoneAccess}` }),
      400,
      "HR-ACCOUNT-004",
    );
    await expectError(read("/sessions", phoneAccess), 403, "HR-AUTH-012");
    await expectError(read("/sessions", otherBrowserAccess), 404, undefined);
    const bearer = await fetch(`${origin}/v2/devices/${deviceId}/api/env`, {
      headers: { authorization: `Bearer ${phoneAccess}` },
    });
    assert.equal(bearer.status, 200, "the Android bearer path is unchanged by the Web allowlist");

    // WebSocket: cookie plus the exact Origin.
    const wsUrl = `ws://127.0.0.1:${port}/v2/devices/${deviceId}/ws`;
    const web = await openSocket(wsUrl, { cookie: cookie(browserAccess), origin: WEB_ORIGIN });
    sockets.push(web);
    web.send(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "prompt.submit", params: {} }));
    assert.match(await nextRawMessage(web), /^account:.*prompt\.submit/);
    // Administration over the socket is refused in-band and never reaches the Mac.
    web.send(JSON.stringify({ jsonrpc: "2.0", id: 2, method: "config.set", params: {} }));
    const refused = JSON.parse(await nextRawMessage(web)) as { id: number; error: { data: { code: string } } };
    assert.equal(refused.id, 2);
    assert.equal(refused.error.data.code, "HR-WEB-001");
    assert.equal(await rejectedUpgradeStatus(wsUrl, { cookie: cookie(browserAccess) }), 403);
    assert.equal(
      await rejectedUpgradeStatus(wsUrl, { cookie: cookie(browserAccess), origin: "https://evil.example.test" }),
      403,
    );
    assert.equal(
      await rejectedUpgradeStatus(`${wsUrl}?token=anything`, { cookie: cookie(browserAccess), origin: WEB_ORIGIN }),
      400,
    );
    assert.equal(await rejectedUpgradeStatus(wsUrl, { cookie: cookie(phoneAccess), origin: WEB_ORIGIN }), 403);

    // Lifecycle inbox: active browser installations get receipts; a signed-out one does not.
    const lifecycleAck = nextMessage(connector, "session.lifecycle.ack");
    connector.send(encodeWireMessage({
      type: "session.lifecycle",
      version: PROTOCOL_VERSION,
      eventId: "web-lifecycle-1",
      deviceId,
      runtimeSessionId: "runtime-web-1",
      storedSessionId: "stored-web-1",
      event: "run.waiting",
      state: "waiting",
      occurredAt: "2026-09-21T08:00:00.000Z",
      title: "Needs approval",
    } as WireMessage));
    await lifecycleAck;
    const receipts = await setup.query<{ installation_id: string }>(
      "SELECT installation_id FROM account_lifecycle_receipts ORDER BY installation_id",
    );
    assert.deepEqual(receipts.rows.map((row) => row.installation_id), [browser, phone].sort());
    const inbox = await fetch(`${origin}/api/mobile/events?after=0&limit=20`, {
      headers: { cookie: cookie(browserAccess), "sec-fetch-site": "same-origin" },
    });
    assert.equal(inbox.status, 200);
    const page = await inbox.json() as { events: Array<{ event: { eventId: string }; readAt?: string }> };
    assert.deepEqual(page.events.map(({ event }) => event.eventId), ["web-lifecycle-1"]);
    const markRead = await fetch(`${origin}/api/mobile/events/read`, {
      method: "POST",
      headers: {
        cookie: cookie(browserAccess),
        origin: WEB_ORIGIN,
        "sec-fetch-site": "same-origin",
        "x-hermes-csrf": CSRF,
        "content-type": "application/json",
      },
      body: JSON.stringify({ event_ids: ["web-lifecycle-1"] }),
    });
    assert.equal(markRead.status, 200);
    assert.equal((await markRead.json() as { changed: number }).changed, 1);
    await expectError(fetch(`${origin}/api/mobile/events/read`, {
      method: "POST",
      headers: { cookie: cookie(browserAccess), origin: WEB_ORIGIN, "content-type": "application/json" },
      body: JSON.stringify({ event_ids: ["web-lifecycle-1"] }),
    }), 403, "HR-AUTH-012");

    // The access token rotating underneath (a refresh) does not drop the open socket: it is
    // revalidated by session, every five seconds.
    const rotatedAccess = codec.issueAccessToken();
    await setup.query(
      "UPDATE account_sessions SET access_token_hash = $2 WHERE id = $1",
      [browserSession, codec.hashAccessToken(rotatedAccess)],
    );
    await delay(6_000);
    assert.equal(web.readyState, WebSocket.OPEN);
    web.send(JSON.stringify({ jsonrpc: "2.0", id: 3, method: "session.resume", params: {} }));
    assert.match(await nextRawMessage(web), /^account:.*session\.resume/);

    // A session its browser stopped refreshing (tab gone, or signed out after the access token
    // expired) loses the socket a few minutes after its last access token, as a bearer socket does.
    const abandoned = nextClose(web, 8_000);
    await setup.query(
      "UPDATE account_sessions SET access_expires_at = now() - interval '6 minutes' WHERE id = $1",
      [browserSession],
    );
    assert.equal((await abandoned).code, 4403);

    // Ending the session closes a socket opened with the rotated token.
    await setup.query(
      "UPDATE account_sessions SET access_expires_at = now() + interval '1 hour' WHERE id = $1",
      [browserSession],
    );
    const second = await openSocket(wsUrl, { cookie: cookie(rotatedAccess), origin: WEB_ORIGIN });
    sockets.push(second);
    const closed = nextClose(second, 8_000);
    await setup.query("UPDATE account_sessions SET revoked_at = now() WHERE id = $1", [browserSession]);
    assert.equal((await closed).code, 4403);
  } finally {
    for (const socket of sockets) socket.close();
    if (child) {
      child.kill("SIGTERM");
      await new Promise<void>((resolveExit) => {
        if (child!.exitCode !== null) resolveExit();
        else child!.once("exit", () => resolveExit());
      });
    }
    await setup.end();
    await admin.query(`DROP SCHEMA "${schema}" CASCADE`);
    await admin.end();
    await rm(webAppDir, { recursive: true, force: true });
  }
});

async function expectError(
  pending: Promise<Response>,
  status: number,
  code: string | undefined,
): Promise<void> {
  const response = await pending;
  assert.equal(response.status, status);
  if (code) assert.equal((await response.json() as { error: { code: string } }).error.code, code);
  else await response.body?.cancel();
}

async function authenticateConnector(
  socket: WebSocket,
  origin: string,
  bindingId: string,
  fingerprint: string,
  privateKey: import("node:crypto").KeyObject,
): Promise<void> {
  socket.send(encodeWireMessage({
    type: "connector.identify",
    version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
    bindingId,
    generation: 1,
    publicKeyFingerprint: fingerprint,
  }));
  const challenge = await nextMessage(socket, "connector.challenge");
  socket.send(encodeWireMessage({
    type: "connector.authenticate",
    version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
    bindingId,
    generation: 1,
    publicKeyFingerprint: fingerprint,
    connectionNonce: challenge.connectionNonce,
    signature: sign(null, canonicalConnectorChallenge(origin, challenge), privateKey)
      .toString("base64url"),
  }));
  const preflight = await nextMessage(socket, "connector.preflight.request");
  socket.send(encodeWireMessage({
    type: "connector.preflight.result",
    version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
    requestId: preflight.requestId,
    hermesReachable: true,
    hermesVersion: "integration-hermes",
  }));
  assert.equal((await nextMessage(socket, "connector.ready")).routingEnabled, true);
}

// Echoes requests; /api/files answers with the type the file name implies and an inline
// disposition, the worst case the Gateway has to neutralise for a browser.
function attachMockConnector(socket: WebSocket): void {
  socket.on("message", (raw) => {
    const message = parseWireMessage(raw.toString());
    if (message.type === "tunnel.http.request") {
      const files = message.path.startsWith("/api/files?");
      const name = files ? decodeURIComponent(message.path.split("path=")[1] ?? "").split("/").pop() : "";
      socket.send(encodeWireMessage({
        type: "tunnel.http.response",
        version: PROTOCOL_VERSION,
        requestId: message.id,
        status: 200,
        headers: files
          ? {
              "content-type": name?.endsWith(".png") ? "image/png" : "text/html",
              "content-disposition": `inline; filename="${name}"`,
            }
          : { "content-type": "text/plain" },
        bodyBase64: Buffer.from(`account:${message.path}`).toString("base64"),
      }));
    } else if (message.type === "tunnel.ws.frame") {
      socket.send(encodeWireMessage({
        ...message,
        dataBase64: Buffer.from(`account:${Buffer.from(message.dataBase64, "base64").toString()}`)
          .toString("base64"),
      }));
    }
  });
}

function nextMessage<T extends WireMessage["type"]>(
  socket: WebSocket,
  expectedType: T,
): Promise<Extract<WireMessage, { type: T }>> {
  return new Promise((resolveMessage, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for ${expectedType}`)), 2_000);
    const listener = (raw: WebSocket.RawData): void => {
      const message = parseWireMessage(raw.toString());
      if (message.type !== expectedType) return;
      clearTimeout(timer);
      socket.off("message", listener);
      resolveMessage(message as Extract<WireMessage, { type: T }>);
    };
    socket.on("message", listener);
  });
}

function openSocket(url: string, headers?: Record<string, string>): Promise<WebSocket> {
  return new Promise((resolveSocket, reject) => {
    const socket = new WebSocket(url, { headers });
    socket.once("open", () => resolveSocket(socket));
    socket.once("error", reject);
  });
}

function nextRawMessage(socket: WebSocket): Promise<string> {
  return new Promise((resolveMessage, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for WebSocket message")), 2_000);
    socket.once("message", (raw) => {
      clearTimeout(timer);
      resolveMessage(raw.toString());
    });
  });
}

function nextClose(socket: WebSocket, timeoutMs: number): Promise<{ code: number; reason: string }> {
  return new Promise((resolveClose, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for WebSocket close")), timeoutMs);
    socket.once("close", (code, reason) => {
      clearTimeout(timer);
      resolveClose({ code, reason: reason.toString() });
    });
  });
}

function rejectedUpgradeStatus(url: string, headers: Record<string, string>): Promise<number> {
  return new Promise((resolveStatus, reject) => {
    const socket = new WebSocket(url, { headers });
    socket.once("unexpected-response", (_request, response) => {
      response.resume();
      resolveStatus(response.statusCode ?? 0);
    });
    socket.once("open", () => {
      socket.close();
      reject(new Error("WebSocket upgrade unexpectedly succeeded"));
    });
    socket.once("error", (error) => {
      if ((error as Error & { code?: string }).code !== "ECONNRESET") reject(error);
    });
  });
}

function unusedPort(): Promise<number> {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("unable to allocate port"));
        return;
      }
      server.close((error) => error ? reject(error) : resolvePort(address.port));
    });
  });
}

function waitForGateway(child: ChildProcessWithoutNullStreams): Promise<void> {
  return new Promise((resolveReady, reject) => {
    const timer = setTimeout(() => reject(new Error("gateway did not start")), 5_000);
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`gateway exited early with ${code}: ${child.stderr.read()?.toString() ?? ""}`));
    });
    child.stdout.on("data", (chunk: Buffer) => {
      if (!chunk.toString().includes("Hermes Remote Gateway listening")) return;
      clearTimeout(timer);
      resolveReady();
    });
  });
}

function delay(ms: number): Promise<void> {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, ms));
}
