import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
} from "@hermes-remote/protocol";
import {
  loadServerReleaseManifest,
  ServerReleaseController,
  type GatewayReadiness,
  type ServerReleaseManifest,
} from "./server-release.js";

test("generated release manifest matches protocol constants and verifies every build file", () => {
  const manifest = loadServerReleaseManifest();
  assert.equal(manifest.manifestVersion, 1);
  assert.equal(manifest.protocolVersions.legacy, PROTOCOL_VERSION);
  assert.equal(manifest.protocolVersions.accountConnector, ACCOUNT_CONNECTOR_PROTOCOL_VERSION);
  assert.equal(manifest.databaseSchemaVersion, 7);
  assert.equal(manifest.minimumSourceVersion, "0.2.0");
  assert.equal(manifest.maintenanceRequired, true);
  assert.equal(manifest.rollbackSupported, true);
  assert(Object.keys(manifest.files).length > 0);
});

test("release manifest loader rejects a modified artifact file", async () => {
  const root = await mkdtemp(join(tmpdir(), "hermes-gateway-release-"));
  try {
    const payloadPath = join(root, "payload.js");
    await writeFile(payloadPath, "known payload\n", "utf8");
    const manifest: ServerReleaseManifest = {
      manifestVersion: 1,
      serverVersion: "0.2.0",
      configSchemaVersion: 1,
      databaseSchemaVersion: 7,
      supportedPostgresqlMajors: [18],
      protocolVersions: { legacy: 1, accountConnector: 2 },
      minimumClients: { android: "0.1.0", desktop: "0.2.0", connector: "0.1.1" },
      minimumSourceVersion: "0.2.0",
      maintenanceRequired: true,
      rollbackSupported: true,
      sourceCommit: "development",
      sourceDirty: true,
      builtAt: "2026-09-03T00:00:00.000Z",
      files: {
        "payload.js": createHash("sha256").update("known payload\n").digest("hex"),
      },
    };
    const manifestPath = join(root, "release-manifest.json");
    await writeFile(manifestPath, JSON.stringify(manifest), "utf8");
    assert.equal(loadServerReleaseManifest(new URL(`file://${manifestPath}`)).serverVersion, "0.2.0");
    await writeFile(payloadPath, "tampered\n", "utf8");
    assert.throws(
      () => loadServerReleaseManifest(new URL(`file://${manifestPath}`)),
      /integrity check failed/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("release endpoints separate liveness, readiness, and protected build metadata", async () => {
  const manifest = loadServerReleaseManifest();
  let readiness: GatewayReadiness = {
    ready: false,
    checks: {
      config: "ok",
      database: "unavailable",
      migrations: "unknown",
      postgresql: "unknown",
    },
  };
  const controller = new ServerReleaseController({
    manifest,
    internalStatusToken: "internal-status-token",
    capabilities: {
      accountAuth: true,
      connectorBinding: false,
      installationSessions: true,
      lifecycleInbox: true,
      legacyAuth: true,
    },
    readiness: async () => readiness,
    tokensEqual: (actual, expected) => actual === expected,
  });

  const live = new MemoryResponse();
  assert.equal(await controller.handle(request("GET"), live.asResponse(), url("/healthz")), true);
  assert.equal(live.status, 200);
  assert.deepEqual(live.json(), { status: "alive" });

  const notReady = new MemoryResponse();
  await controller.handle(request("GET"), notReady.asResponse(), url("/readyz"));
  assert.equal(notReady.status, 503);
  assert.equal((notReady.json() as { status: string }).status, "not_ready");

  readiness = {
    ready: true,
    checks: {
      config: "ok",
      database: "ok",
      migrations: "ok",
      postgresql: "supported",
    },
  };
  const ready = new MemoryResponse();
  await controller.handle(request("GET"), ready.asResponse(), url("/readyz"));
  assert.equal(ready.status, 200);

  const unauthorized = new MemoryResponse();
  await controller.handle(request("GET"), unauthorized.asResponse(), url("/internal/version"));
  assert.equal(unauthorized.status, 401);

  const version = new MemoryResponse();
  await controller.handle(
    request("GET", "Bearer internal-status-token"),
    version.asResponse(),
    url("/internal/version"),
  );
  assert.equal(version.status, 200);
  const body = version.json() as Record<string, unknown>;
  assert.equal(body.serverVersion, manifest.serverVersion);
  assert.equal(body.sourceCommit, manifest.sourceCommit);
  assert.equal(body.files, undefined);
  assert.equal(body.artifactFileCount, Object.keys(manifest.files).length);
});

function request(method: string, authorization?: string): IncomingMessage {
  return {
    method,
    headers: authorization ? { authorization } : {},
  } as IncomingMessage;
}

function url(path: string): URL {
  return new URL(path, "http://localhost");
}

class MemoryResponse {
  status = 0;
  private body = "";

  asResponse(): ServerResponse {
    return {
      writeHead: (status: number) => { this.status = status; },
      end: (body?: string) => { this.body = body ?? ""; },
    } as unknown as ServerResponse;
  }

  json(): unknown {
    return JSON.parse(this.body);
  }
}
