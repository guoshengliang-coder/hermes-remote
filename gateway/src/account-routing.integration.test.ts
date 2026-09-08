import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import {
  createHash,
  generateKeyPairSync,
  randomUUID,
  sign,
} from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { createServer } from "node:net";
import { resolve } from "node:path";
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

test("account V2 Connector isolates routing, health, and per-phone lifecycle receipts", {
  skip: databaseUrl && process.env.RUN_NETWORK_TESTS === "1"
    ? false
    : "set ACCOUNT_TEST_DATABASE_URL and RUN_NETWORK_TESTS=1",
}, async () => {
  assert(databaseUrl);
  const schema = `account_route_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const scopedDatabaseUrl = new URL(databaseUrl);
  scopedDatabaseUrl.searchParams.set("options", `-c search_path=${schema}`);
  const setup = new Pool({ connectionString: scopedDatabaseUrl.toString(), max: 1 });
  let child: ChildProcessWithoutNullStreams | undefined;
  const sockets: WebSocket[] = [];

  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await setup.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }

    const accountA = randomUUID();
    const accountB = randomUUID();
    const phoneA = randomUUID();
    const phoneA2 = randomUUID();
    const phoneB = randomUUID();
    const desktopA = randomUUID();
    const desktopA2 = randomUUID();
    const sessionA = randomUUID();
    const sessionA2 = randomUUID();
    const sessionB = randomUUID();
    const bindingId = randomUUID();
    const bindingId2 = randomUUID();
    const shareGrantId = randomUUID();
    const sharedDeviceId = "shared-device";
    const secondDeviceId = "second-account-device";
    const tokenKey = "routing-integration-key-with-at-least-thirty-two-bytes";
    const codec = new TokenCodec(tokenKey);
    const accessA = codec.issueAccessToken();
    const accessA2 = codec.issueAccessToken();
    const accessB = codec.issueAccessToken();
    const { publicKey, privateKey } = generateKeyPairSync("ed25519");
    const publicDer = publicKey.export({ format: "der", type: "spki" });
    const rawPublicKey = publicDer.subarray(publicDer.byteLength - 32);
    const fingerprint = createHash("sha256").update(rawPublicKey).digest("hex");
    const secondKeys = generateKeyPairSync("ed25519");
    const secondPublicDer = secondKeys.publicKey.export({ format: "der", type: "spki" });
    const secondRawPublicKey = secondPublicDer.subarray(secondPublicDer.byteLength - 32);
    const secondFingerprint = createHash("sha256").update(secondRawPublicKey).digest("hex");

    await setup.query(
      "INSERT INTO accounts (id) VALUES ($1), ($2)",
      [accountA, accountB],
    );
    await setup.query(
      `INSERT INTO installations
         (id, account_id, client_installation_id, kind, platform, display_name, app_version)
       VALUES
         ($1, $2, $1, 'phone', 'android', 'Phone A', 'test'),
         ($3, $2, $3, 'phone', 'android', 'Phone A2', 'test'),
         ($4, $5, $4, 'phone', 'android', 'Phone B', 'test'),
         ($6, $2, $6, 'desktop', 'macos', 'Mac mini', 'test'),
         ($7, $2, $7, 'desktop', 'macos', 'Second Mac mini', 'test')`,
      [phoneA, accountA, phoneA2, phoneB, accountB, desktopA, desktopA2],
    );
    await setup.query(
      `INSERT INTO account_sessions
         (id, account_id, installation_id, refresh_family_id, access_token_hash, access_expires_at)
       VALUES ($1, $2, $3, $1, $4, now() + interval '1 hour'),
              ($5, $2, $6, $5, $7, now() + interval '1 hour'),
              ($8, $9, $10, $8, $11, now() + interval '1 hour')`,
      [
        sessionA, accountA, phoneA, codec.hashAccessToken(accessA),
        sessionA2, phoneA2, codec.hashAccessToken(accessA2),
        sessionB, accountB, phoneB, codec.hashAccessToken(accessB),
      ],
    );
    await setup.query(
      `INSERT INTO connector_bindings
         (id, account_id, desktop_installation_id, display_name, device_id, public_key,
          key_algorithm, public_key_fingerprint, generation, status, activated_at)
       VALUES ($1, $2, $3, 'Mac mini', $4, $5, 'Ed25519', $6, 1, 'active', now())`,
      [bindingId, accountA, desktopA, sharedDeviceId, rawPublicKey, fingerprint],
    );

    const port = await unusedPort();
    const origin = `http://127.0.0.1:${port}`;
    const appToken = "routing-legacy-app-token";
    const connectorToken = "routing-legacy-connector-token";
    child = spawn(process.execPath, ["dist/index.js"], {
      cwd: process.cwd(),
      env: {
        ...process.env,
        HOST: "127.0.0.1",
        PORT: String(port),
        APP_TOKEN: appToken,
        CONNECTOR_TOKEN: connectorToken,
        DEFAULT_DEVICE_ID: sharedDeviceId,
        ACCOUNT_AUTH_ENABLED: "1",
        ACCOUNT_BINDING_ENABLED: "1",
        ACCOUNT_MULTI_DEVICE_ENABLED: "1",
        ACCOUNT_DEVICE_SHARING_ENABLED: "1",
        ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
        ACCOUNT_RESEND_WEBHOOK_SECRET: `whsec_${Buffer.from("routing-resend-webhook-secret-32bytes").toString("base64")}`,
        ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
        ACCOUNT_RESEND_API_KEY: "re_test_routing_only",
        ACCOUNT_EMAIL_FROM: "Hermes GO <test@example.invalid>",
        ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN: "https://accounts.example.invalid",
        ACCOUNT_DATABASE_URL: scopedDatabaseUrl.toString(),
        ACCOUNT_TOKEN_HASH_KEY: tokenKey,
        ACCOUNT_GOOGLE_ANDROID_CLIENT_ID: "routing-android-client",
        ACCOUNT_GOOGLE_MACOS_CLIENT_ID: "routing-macos-client",
        ACCOUNT_GATEWAY_ORIGIN: origin,
        ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS: "1",
        ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP: "1",
      },
      stdio: "pipe",
    });
    await waitForGateway(child);

    const readinessResponse = await fetch(`${origin}/readyz`);
    assert.equal(readinessResponse.status, 200);
    assert.deepEqual(await readinessResponse.json(), {
      status: "ready",
      checks: {
        config: "ok",
        database: "ok",
        migrations: "ok",
        postgresql: "supported",
      },
    });

    const accountConnector = await openSocket(`ws://127.0.0.1:${port}/v2/connect`);
    sockets.push(accountConnector);
    accountConnector.send(encodeWireMessage({
      type: "connector.identify",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId,
      generation: 1,
      publicKeyFingerprint: fingerprint,
    }));
    const challenge = await nextMessage(accountConnector, "connector.challenge");
    accountConnector.send(encodeWireMessage({
      type: "connector.authenticate",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId,
      generation: 1,
      publicKeyFingerprint: fingerprint,
      connectionNonce: challenge.connectionNonce,
      signature: sign(
        null,
        canonicalConnectorChallenge(origin, challenge),
        privateKey,
      ).toString("base64url"),
    }));
    const preflight = await nextMessage(accountConnector, "connector.preflight.request");
    accountConnector.send(encodeWireMessage({
      type: "connector.preflight.result",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      requestId: preflight.requestId,
      hermesReachable: true,
      hermesVersion: "integration-hermes",
    }));
    const ready = await nextMessage(accountConnector, "connector.ready");
    assert.equal(ready.routingEnabled, true);

    const unauthenticated = await openSocket(`ws://127.0.0.1:${port}/v2/connect`);
    sockets.push(unauthenticated);
    assert.equal(
      await rejectedUpgradeStatus(`ws://127.0.0.1:${port}/v2/connect`),
      503,
    );
    unauthenticated.close();

    const legacyConnector = await openSocket(`ws://127.0.0.1:${port}/v1/connect`);
    sockets.push(legacyConnector);
    legacyConnector.send(encodeWireMessage({
      type: "hello",
      version: PROTOCOL_VERSION,
      role: "connector",
      deviceId: sharedDeviceId,
      token: connectorToken,
    }));
    await nextMessage(legacyConnector, "hello_ack");

    attachMockConnector(accountConnector, "account");
    attachMockConnector(legacyConnector, "legacy");

    const accountResponse = await fetch(`${origin}/api/status`, {
      headers: { authorization: `Bearer ${accessA}` },
    });
    assert.equal(accountResponse.status, 200);
    assert.equal(await accountResponse.text(), "account:/api/status");

    const legacyResponse = await fetch(`${origin}/api/status`, {
      headers: { "x-hermes-session-token": appToken },
    });
    assert.equal(legacyResponse.status, 200);
    assert.equal(await legacyResponse.text(), "legacy:/api/status");

    const otherAccountResponse = await fetch(`${origin}/api/status`, {
      headers: { authorization: `Bearer ${accessB}` },
    });
    assert.equal(otherAccountResponse.status, 409);
    assert.equal((await otherAccountResponse.json() as { error: { code: string } }).error.code, "HR-BIND-001");

    const ambiguousResponse = await fetch(`${origin}/api/status`, {
      headers: {
        authorization: `Bearer ${accessA}`,
        "x-hermes-session-token": appToken,
      },
    });
    assert.equal(ambiguousResponse.status, 400);
    assert.equal((await ambiguousResponse.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-004");

    const appSocket = await openSocket(`ws://127.0.0.1:${port}/api/ws`, {
      authorization: `Bearer ${accessA}`,
    });
    sockets.push(appSocket);
    const echoed = new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("timed out waiting for account WebSocket echo")), 2_000);
      appSocket.once("message", (data) => {
        clearTimeout(timer);
        resolve(data.toString());
      });
    });
    appSocket.send("hello");
    assert.equal(await echoed, "account:hello");

    const bindingResponse = await fetch(`${origin}/v2/connector-binding`, {
      headers: { authorization: `Bearer ${accessA}` },
    });
    const binding = await bindingResponse.json() as {
      state: string;
      binding: { connector: { online: boolean }; hermes: { reachable: boolean; version: string } };
    };
    assert.equal(binding.state, "bound");
    assert.equal(binding.binding.connector.online, true);
    assert.deepEqual(binding.binding.hermes, { reachable: true, version: "integration-hermes" });

    await setup.query(
      `INSERT INTO connector_bindings
         (id, account_id, desktop_installation_id, display_name, device_id, public_key,
          key_algorithm, public_key_fingerprint, generation, status, activated_at)
       VALUES ($1, $2, $3, 'Second Mac mini', $4, $5, 'Ed25519', $6, 2, 'active', now())`,
      [bindingId2, accountA, desktopA2, secondDeviceId, secondRawPublicKey, secondFingerprint],
    );
    const secondConnector = await openSocket(`ws://127.0.0.1:${port}/v2/connect`);
    sockets.push(secondConnector);
    secondConnector.send(encodeWireMessage({
      type: "connector.identify",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId: bindingId2,
      generation: 2,
      publicKeyFingerprint: secondFingerprint,
    }));
    const secondChallenge = await nextMessage(secondConnector, "connector.challenge");
    secondConnector.send(encodeWireMessage({
      type: "connector.authenticate",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId: bindingId2,
      generation: 2,
      publicKeyFingerprint: secondFingerprint,
      connectionNonce: secondChallenge.connectionNonce,
      signature: sign(
        null,
        canonicalConnectorChallenge(origin, secondChallenge),
        secondKeys.privateKey,
      ).toString("base64url"),
    }));
    const secondPreflight = await nextMessage(secondConnector, "connector.preflight.request");
    secondConnector.send(encodeWireMessage({
      type: "connector.preflight.result",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      requestId: secondPreflight.requestId,
      hermesReachable: true,
      hermesVersion: "integration-hermes-second",
    }));
    await nextMessage(secondConnector, "connector.ready");
    attachMockConnector(secondConnector, "account-second");

    const devicesResponse = await fetch(`${origin}/v2/devices`, {
      headers: { authorization: `Bearer ${accessA}` },
    });
    assert.equal(devicesResponse.status, 200);
    assert.equal((await devicesResponse.json() as { items: unknown[] }).items.length, 2);

    const singularBindingResponse = await fetch(`${origin}/v2/connector-binding`, {
      headers: { authorization: `Bearer ${accessA}` },
    });
    assert.equal(singularBindingResponse.status, 409);
    assert.equal(
      (await singularBindingResponse.json() as { error: { code: string } }).error.code,
      "HR-BIND-009",
    );
    const implicitDeviceResponse = await fetch(`${origin}/api/status`, {
      headers: { authorization: `Bearer ${accessA}` },
    });
    assert.equal(implicitDeviceResponse.status, 409);
    assert.equal(
      (await implicitDeviceResponse.json() as { error: { code: string } }).error.code,
      "HR-BIND-009",
    );

    const firstExplicitResponse = await fetch(
      `${origin}/v2/devices/${sharedDeviceId}/api/status?source=explicit`,
      { headers: { authorization: `Bearer ${accessA}` } },
    );
    assert.equal(firstExplicitResponse.status, 200);
    assert.equal(await firstExplicitResponse.text(), "account:/api/status?source=explicit");
    const secondExplicitResponse = await fetch(
      `${origin}/v2/devices/${secondDeviceId}/api/status`,
      { headers: { authorization: `Bearer ${accessA}` } },
    );
    assert.equal(secondExplicitResponse.status, 200);
    assert.equal(await secondExplicitResponse.text(), "account-second:/api/status");

    const crossAccountDeviceResponse = await fetch(
      `${origin}/v2/devices/${secondDeviceId}/api/status`,
      { headers: { authorization: `Bearer ${accessB}` } },
    );
    assert.equal(crossAccountDeviceResponse.status, 404);
    assert.equal(
      (await crossAccountDeviceResponse.json() as { error: { code: string } }).error.code,
      "HR-BIND-011",
    );

    await setup.query(
      `INSERT INTO device_access_grants
         (id, binding_id, owner_account_id, grantee_account_id, grantee_email_hint)
       VALUES ($1, $2, $3, $4, 'g***@example.invalid')`,
      [shareGrantId, bindingId2, accountA, accountB],
    );
    const granteeDevicesResponse = await fetch(`${origin}/v2/devices`, {
      headers: { authorization: `Bearer ${accessB}` },
    });
    assert.equal(granteeDevicesResponse.status, 200);
    const granteeDevices = await granteeDevicesResponse.json() as {
      items: Array<{ deviceId: string; access: string }>;
    };
    assert.deepEqual(granteeDevices.items.map(({ deviceId, access }) => ({ deviceId, access })), [
      { deviceId: secondDeviceId, access: "operator" },
    ]);
    const sharedRestResponse = await fetch(
      `${origin}/v2/devices/${secondDeviceId}/api/status?source=grantee`,
      { headers: { authorization: `Bearer ${accessB}` } },
    );
    assert.equal(sharedRestResponse.status, 200);
    assert.equal(await sharedRestResponse.text(), "account-second:/api/status?source=grantee");

    const granteeSocket = await openSocket(
      `ws://127.0.0.1:${port}/v2/devices/${secondDeviceId}/ws`,
      { authorization: `Bearer ${accessB}` },
    );
    sockets.push(granteeSocket);
    const granteeEcho = nextRawMessage(granteeSocket);
    granteeSocket.send("hello-shared");
    assert.equal(await granteeEcho, "account-second:hello-shared");
    const granteeClosed = nextClose(granteeSocket);
    const revokeResponse = await fetch(
      `${origin}/v2/devices/${secondDeviceId}/shares/${shareGrantId}`,
      {
        method: "DELETE",
        headers: {
          authorization: `Bearer ${accessA}`,
          "idempotency-key": randomUUID(),
        },
      },
    );
    assert.equal(revokeResponse.status, 204);
    assert.deepEqual(await granteeClosed, { code: 4403, reason: "device access revoked" });
    const revokedRestResponse = await fetch(
      `${origin}/v2/devices/${secondDeviceId}/api/status`,
      { headers: { authorization: `Bearer ${accessB}` } },
    );
    assert.equal(revokedRestResponse.status, 404);
    assert.equal(
      (await revokedRestResponse.json() as { error: { code: string } }).error.code,
      "HR-BIND-011",
    );

    const explicitSocket = await openSocket(
      `ws://127.0.0.1:${port}/v2/devices/${secondDeviceId}/ws`,
      { authorization: `Bearer ${accessA}` },
    );
    sockets.push(explicitSocket);
    const explicitEcho = new Promise<string>((resolveEcho, reject) => {
      const timer = setTimeout(() => reject(new Error("timed out waiting for explicit WebSocket echo")), 2_000);
      explicitSocket.once("message", (data) => {
        clearTimeout(timer);
        resolveEcho(data.toString());
      });
    });
    explicitSocket.send("hello-second");
    assert.equal(await explicitEcho, "account-second:hello-second");

    const lifecycleEvent = {
      type: "session.lifecycle" as const,
      version: PROTOCOL_VERSION,
      eventId: "account-lifecycle-1",
      deviceId: sharedDeviceId,
      runtimeSessionId: "runtime-account-1",
      storedSessionId: "stored-account-1",
      event: "run.completed" as const,
      state: "idle" as const,
      occurredAt: "2026-09-02T08:00:00.000Z",
      title: "Account background task",
    };
    const firstAck = nextMessage(accountConnector, "session.lifecycle.ack");
    accountConnector.send(encodeWireMessage(lifecycleEvent));
    assert.equal((await firstAck).eventId, lifecycleEvent.eventId);
    const duplicateAck = nextMessage(accountConnector, "session.lifecycle.ack");
    accountConnector.send(encodeWireMessage(lifecycleEvent));
    assert.equal((await duplicateAck).eventId, lifecycleEvent.eventId);

    const pageA = await accountLifecyclePage(origin, accessA);
    const pageA2 = await accountLifecyclePage(origin, accessA2);
    const pageB = await accountLifecyclePage(origin, accessB);
    assert.deepEqual(pageA.events.map(({ event }) => event.eventId), [lifecycleEvent.eventId]);
    assert.deepEqual(pageA2.events.map(({ event }) => event.eventId), [lifecycleEvent.eventId]);
    assert.deepEqual(pageB.events, []);
    assert.equal(pageA.events[0].deliveredAt, undefined);
    assert.equal(pageA2.events[0].deliveredAt, undefined);

    assert.equal(await markAccountLifecycle(origin, accessA, "ack", [lifecycleEvent.eventId]), 1);
    assert.equal(await markAccountLifecycle(origin, accessA, "ack", [lifecycleEvent.eventId]), 0);
    assert.equal((await accountLifecyclePage(origin, accessA)).events[0].deliveredAt !== undefined, true);
    assert.equal((await accountLifecyclePage(origin, accessA2)).events[0].deliveredAt, undefined);
    assert.equal(await markAccountLifecycle(origin, accessB, "read", [lifecycleEvent.eventId]), 0);
    assert.equal(await markAccountLifecycle(origin, accessA2, "read", [lifecycleEvent.eventId]), 1);
    assert.equal((await accountLifecyclePage(origin, accessA)).events[0].readAt, undefined);
    assert.equal((await accountLifecyclePage(origin, accessA2)).events[0].readAt !== undefined, true);

    const conflictError = nextMessage(accountConnector, "error");
    accountConnector.send(encodeWireMessage({ ...lifecycleEvent, title: "Conflicting event" }));
    assert.equal((await conflictError).code, "event_id_conflict");
    await waitUntil(async () => {
      const result = await setup.query<{ connector_online: boolean }>(
        "SELECT connector_online FROM connector_bindings WHERE id = $1",
        [bindingId],
      );
      return result.rows[0]?.connector_online === false;
    });
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
  }
});

interface AccountLifecyclePage {
  events: Array<{
    sequence: number;
    event: { eventId: string };
    receivedAt: string;
    deliveredAt?: string;
    readAt?: string;
  }>;
  nextCursor: number;
  hasMore: boolean;
}

async function accountLifecyclePage(origin: string, accessToken: string): Promise<AccountLifecyclePage> {
  const response = await fetch(`${origin}/api/mobile/events?after=0&limit=20`, {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  assert.equal(response.status, 200);
  return response.json() as Promise<AccountLifecyclePage>;
}

async function markAccountLifecycle(
  origin: string,
  accessToken: string,
  action: "ack" | "read",
  eventIds: string[],
): Promise<number> {
  const response = await fetch(`${origin}/api/mobile/events/${action}`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ event_ids: eventIds }),
  });
  assert.equal(response.status, 200);
  return (await response.json() as { changed: number }).changed;
}

function attachMockConnector(socket: WebSocket, marker: string): void {
  socket.on("message", (raw) => {
    const message = parseWireMessage(raw.toString());
    if (message.type === "tunnel.http.request") {
      socket.send(encodeWireMessage({
        type: "tunnel.http.response",
        version: PROTOCOL_VERSION,
        requestId: message.id,
        status: 200,
        headers: { "content-type": "text/plain" },
        bodyBase64: Buffer.from(`${marker}:${message.path}`).toString("base64"),
      }));
    } else if (message.type === "tunnel.ws.frame") {
      socket.send(encodeWireMessage({
        ...message,
        dataBase64: Buffer.from(`${marker}:${Buffer.from(message.dataBase64, "base64").toString()}`)
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

function nextClose(socket: WebSocket): Promise<{ code: number; reason: string }> {
  return new Promise((resolveClose, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for WebSocket close")), 2_000);
    socket.once("close", (code, reason) => {
      clearTimeout(timer);
      resolveClose({ code, reason: reason.toString() });
    });
  });
}

function rejectedUpgradeStatus(url: string): Promise<number> {
  return new Promise((resolveStatus, reject) => {
    const socket = new WebSocket(url);
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
    const timer = setTimeout(() => reject(new Error("gateway did not start")), 3_000);
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

async function waitUntil(predicate: () => Promise<boolean>): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 25));
  }
  throw new Error("condition was not met before timeout");
}
