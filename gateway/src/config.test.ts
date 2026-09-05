import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import { loadGatewayConfig } from "./config.js";

const required = {
  APP_TOKEN: "test-app-token",
  CONNECTOR_TOKEN: "test-connector-token",
};

test("Gateway config retains the legacy defaults", () => {
  const config = loadGatewayConfig(required);
  assert.equal(config.port, 8787);
  assert.equal(config.host, "0.0.0.0");
  assert.equal(config.defaultDeviceId, "mac-mini");
  assert.equal(config.maxBodyBytes, 10 * 1024 * 1024);
  assert.equal(config.requestTimeoutMs, 60_000);
  assert.equal(config.maxPendingRequests, 128);
  assert.equal(config.logLevel, "info");
  assert.equal(config.maxWebSocketTunnels, 32);
  assert.equal(config.maxControlConnections, 32);
  assert.equal(config.maxUnauthenticatedAccountConnectors, 16);
  assert.equal(config.maxUnauthenticatedAccountConnectorsPerIp, 4);
  assert.equal(config.maxLifecycleEvents, 10_000);
  assert.equal(config.lifecycleEventStoreFile, resolve(".data/lifecycle-events.json"));
});

test("Gateway config loads file-backed secrets without exposing file whitespace", async () => {
  const root = await mkdtemp(join(tmpdir(), "hermes-gateway-config-"));
  try {
    const appTokenFile = join(root, "app-token");
    const connectorTokenFile = join(root, "connector-token");
    await writeFile(appTokenFile, "file-app-token\n", { mode: 0o600 });
    await writeFile(connectorTokenFile, "file-connector-token\n", { mode: 0o600 });
    const config = loadGatewayConfig({
      APP_TOKEN_FILE: appTokenFile,
      CONNECTOR_TOKEN_FILE: connectorTokenFile,
    });
    assert.equal(config.appToken, "file-app-token");
    assert.equal(config.connectorToken, "file-connector-token");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("Gateway config rejects invalid numeric, secret, and TLS settings", () => {
  assert.throws(
    () => loadGatewayConfig({ ...required, PORT: "0" }),
    /PORT must be an integer between 1 and 65535/,
  );
  assert.throws(
    () => loadGatewayConfig({ ...required, APP_TOKEN: "short" }),
    /APP_TOKEN must contain at least 8 characters/,
  );
  assert.throws(
    () => loadGatewayConfig({ ...required, TLS_CERT_FILE: "/tmp/cert.pem" }),
    /TLS_CERT_FILE and TLS_KEY_FILE must be configured together/,
  );
  assert.throws(
    () => loadGatewayConfig({ ...required, INTERNAL_STATUS_TOKEN: "too-short" }),
    /INTERNAL_STATUS_TOKEN must contain at least 16 characters/,
  );
});

test("Gateway config schema identifies the complete R2 environment contract", async () => {
  const schema = JSON.parse(await readFile(resolve("config.schema.json"), "utf8")) as {
    $id: string;
    properties: Record<string, unknown>;
  };
  assert.equal(schema.$id.endsWith("gateway-environment-v1.json"), true);
  for (const name of [
    "APP_TOKEN_FILE",
    "CONNECTOR_TOKEN_FILE",
    "INTERNAL_STATUS_TOKEN_FILE",
    "ACCOUNT_AUTH_ENABLED",
    "ACCOUNT_BINDING_ENABLED",
    "ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS",
  ]) {
    assert.equal(name in schema.properties, true, `${name} is absent from config schema`);
  }
});

test("GATEWAY_LOG_LEVEL is validated", () => {
  const base = { APP_TOKEN: "app-token-value", CONNECTOR_TOKEN: "connector-token-value" };
  assert.equal(loadGatewayConfig({ ...base, GATEWAY_LOG_LEVEL: "debug" }).logLevel, "debug");
  assert.throws(() => loadGatewayConfig({ ...base, GATEWAY_LOG_LEVEL: "loud" }), /GATEWAY_LOG_LEVEL/);
});
