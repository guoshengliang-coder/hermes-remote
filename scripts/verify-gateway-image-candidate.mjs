import assert from "node:assert/strict";
import { WebSocket } from "ws";
import { serializeReleaseError } from "./lib/release-errors.mjs";

let baseUrl;
let internalBaseUrl;
let relayHealthPath;
let appToken;
let internalStatusToken;
let expectedCommit;
let expectedVersion;
let expectedDeviceId;

try {
  baseUrl = required("PUBLIC_GATEWAY_URL").replace(/\/$/, "");
  internalBaseUrl = (process.env.INTERNAL_GATEWAY_URL || baseUrl).replace(/\/$/, "");
  relayHealthPath = process.env.RELAY_HEALTH_PATH || "/health";
  appToken = required("APP_TOKEN");
  internalStatusToken = required("INTERNAL_STATUS_TOKEN");
  expectedCommit = required("EXPECTED_SOURCE_COMMIT");
  expectedVersion = required("EXPECTED_SERVER_VERSION");
  expectedDeviceId = process.env.EXPECTED_DEVICE_ID || "oci-staging";
  await verify();
  console.log(`GATEWAY_OCI_SMOKE_OK version=${expectedVersion} commit=${expectedCommit}`);
} catch (error) {
  console.error(serializeReleaseError("smoke", error instanceof Error ? error.message : error));
  process.exitCode = 1;
}

async function verify() {
  assert.deepEqual(await fetchJson("/healthz"), { status: "alive" });
  assert.deepEqual(await fetchJson("/readyz"), {
    status: "ready",
    checks: {
      config: "ok",
      database: "disabled",
      migrations: "not_required",
      postgresql: "not_required",
    },
  });

  const capabilities = await fetchJson("/v2/capabilities");
  assert.equal(capabilities.accountAuth?.enabled, false);
  assert.equal(capabilities.binding?.enabled, false);
  assert.equal(capabilities.legacy?.appTokenAccepted, true);
  assert.equal(capabilities.legacy?.connectorTokenAccepted, true);
  assert.equal(capabilities.server?.version, expectedVersion);

  const version = await fetchJsonFrom(internalBaseUrl, "/internal/version", {
    headers: { authorization: `Bearer ${internalStatusToken}` },
  });
  assert.equal(version.serverVersion, expectedVersion);
  assert.equal(version.sourceCommit, expectedCommit);
  assert.equal(version.sourceDirty, false);
  assert.ok(Number.isSafeInteger(version.artifactFileCount) && version.artifactFileCount > 0);

  const relayHealth = await fetchJson(relayHealthPath);
  assert.equal(relayHealth.ok, true);
  assert.equal(relayHealth.connectors, 1);
  assert.deepEqual(relayHealth.devices, [{ deviceId: expectedDeviceId, online: true }]);

  const unauthorized = await fetch(`${baseUrl}/api/status`, {
    headers: { "x-hermes-session-token": "deliberately-wrong-token" },
    signal: AbortSignal.timeout(5_000),
  });
  assert.equal(unauthorized.status, 401);

  const status = await fetchJson("/api/status", {
    headers: { "x-hermes-session-token": appToken },
  });
  assert.deepEqual(status, { status: "ok", version: "mock-hermes" });

  await verifyWebSocket();
}

async function fetchJson(path, init = {}) {
  return fetchJsonFrom(baseUrl, path, init);
}

async function fetchJsonFrom(origin, path, init = {}) {
  const response = await fetch(`${origin}${path}`, {
    ...init,
    signal: AbortSignal.timeout(5_000),
  });
  assert.equal(response.ok, true, `${path} returned HTTP ${response.status}`);
  return response.json();
}

function verifyWebSocket() {
  return new Promise((resolve, reject) => {
    const wsUrl = `${baseUrl.replace(/^http:/, "ws:").replace(/^https:/, "wss:")}/api/ws`;
    const socket = new WebSocket(wsUrl, {
      headers: { "x-hermes-session-token": appToken },
    });
    const timer = setTimeout(() => finish(new Error("gateway_ready_or_session_create_timeout")), 10_000);
    let ready = false;
    let settled = false;

    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (socket.readyState === WebSocket.OPEN) socket.close();
      else if (socket.readyState === WebSocket.CONNECTING) socket.terminate();
      if (error) reject(error);
      else resolve();
    };

    socket.on("message", (raw) => {
      try {
        const message = JSON.parse(raw.toString());
        if (message.method === "event" && message.params?.type === "gateway.ready" && !ready) {
          ready = true;
          socket.send(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "session.create", params: {} }));
        } else if (message.id === 1) {
          if (message.error || !message.result?.ok) finish(new Error("session_create_failed"));
          else finish();
        }
      } catch {
        finish(new Error("gateway_websocket_message_invalid"));
      }
    });
    socket.on("error", finish);
    socket.on("close", () => finish(new Error("gateway_websocket_closed_early")));
  });
}

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name}=missing`);
  return value;
}
