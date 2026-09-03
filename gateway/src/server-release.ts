import { createHash, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import type { IncomingMessage, ServerResponse } from "node:http";
import { sendJson } from "./http-utils.js";

export interface ServerReleaseManifest {
  manifestVersion: 1;
  serverVersion: string;
  configSchemaVersion: number;
  databaseSchemaVersion: number;
  supportedPostgresqlMajors: number[];
  protocolVersions: {
    legacy: number;
    accountConnector: number;
  };
  minimumClients: {
    android: string;
    desktop: string;
    connector: string;
  };
  sourceCommit: string;
  sourceDirty: boolean;
  builtAt: string;
  files: Record<string, string>;
}

export interface GatewayReadiness {
  ready: boolean;
  checks: {
    config: "ok";
    database: "disabled" | "ok" | "unavailable";
    migrations: "not_required" | "ok" | "mismatch" | "unknown";
    postgresql: "not_required" | "supported" | "unsupported" | "unknown";
  };
}

export interface ServerCapabilities {
  accountAuth: boolean;
  connectorBinding: boolean;
  installationSessions: boolean;
  lifecycleInbox: boolean;
  legacyAuth: boolean;
}

export class ServerReleaseController {
  constructor(private readonly options: {
    manifest: ServerReleaseManifest;
    internalStatusToken?: string;
    capabilities: ServerCapabilities;
    readiness(): Promise<GatewayReadiness>;
    tokensEqual(actual: string, expected: string): boolean;
  }) {}

  async handle(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
  ): Promise<boolean> {
    if (url.pathname === "/healthz" && request.method === "GET") {
      sendJson(response, 200, { status: "alive" });
      return true;
    }
    if (url.pathname === "/readyz" && request.method === "GET") {
      const readiness = await this.options.readiness();
      sendJson(
        response,
        readiness.ready ? 200 : 503,
        { status: readiness.ready ? "ready" : "not_ready", checks: readiness.checks },
      );
      return true;
    }
    if (url.pathname !== "/internal/version") return false;
    if (request.method !== "GET" || !this.options.internalStatusToken) {
      sendJson(response, 404, { error: "not_found" });
      return true;
    }
    const authorization = request.headers.authorization;
    const token = typeof authorization === "string" && authorization.startsWith("Bearer ")
      ? authorization.slice("Bearer ".length)
      : "";
    if (!token || !this.options.tokensEqual(token, this.options.internalStatusToken)) {
      sendJson(response, 401, { error: "unauthorized" });
      return true;
    }
    const { files, manifestVersion, ...release } = this.options.manifest;
    sendJson(response, 200, {
      manifestVersion,
      ...release,
      artifactFileCount: Object.keys(files).length,
      capabilities: this.options.capabilities,
    });
    return true;
  }
}

export function loadServerReleaseManifest(
  manifestUrl: URL = new URL("./release-manifest.json", import.meta.url),
): ServerReleaseManifest {
  const manifestPath = fileURLToPath(manifestUrl);
  const distDirectory = dirname(manifestPath);
  const parsed: unknown = JSON.parse(readFileSync(manifestPath, "utf8"));
  const manifest = parseManifest(parsed);
  for (const [relativePath, expectedHash] of Object.entries(manifest.files)) {
    if (!relativePath || relativePath.startsWith("/") || relativePath.includes("..")) {
      throw new Error("Gateway release manifest contains an unsafe file path");
    }
    const filePath = resolve(distDirectory, relativePath);
    if (!filePath.startsWith(`${distDirectory}${sep}`)) {
      throw new Error("Gateway release manifest file escaped the release directory");
    }
    const actualHash = createHash("sha256").update(readFileSync(filePath)).digest("hex");
    if (!safeEqual(actualHash, expectedHash)) {
      throw new Error(`Gateway release integrity check failed: ${relativePath}`);
    }
  }
  return manifest;
}

function parseManifest(value: unknown): ServerReleaseManifest {
  if (!isObject(value)
      || value.manifestVersion !== 1
      || !isVersion(value.serverVersion)
      || !isPositiveInteger(value.configSchemaVersion)
      || !isPositiveInteger(value.databaseSchemaVersion)
      || !Array.isArray(value.supportedPostgresqlMajors)
      || value.supportedPostgresqlMajors.length === 0
      || !value.supportedPostgresqlMajors.every(isPositiveInteger)
      || !isObject(value.protocolVersions)
      || !isPositiveInteger(value.protocolVersions.legacy)
      || !isPositiveInteger(value.protocolVersions.accountConnector)
      || !isObject(value.minimumClients)
      || !isVersion(value.minimumClients.android)
      || !isVersion(value.minimumClients.desktop)
      || !isVersion(value.minimumClients.connector)
      || typeof value.sourceCommit !== "string"
      || !(value.sourceCommit === "development" || /^[0-9a-f]{40}$/.test(value.sourceCommit))
      || typeof value.sourceDirty !== "boolean"
      || typeof value.builtAt !== "string"
      || !Number.isFinite(Date.parse(value.builtAt))
      || !isObject(value.files)
      || !Object.values(value.files).every((hash) => typeof hash === "string" && /^[0-9a-f]{64}$/.test(hash))) {
    throw new Error("Gateway release manifest is invalid");
  }
  return value as unknown as ServerReleaseManifest;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function isVersion(value: unknown): value is string {
  return typeof value === "string" && /^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/.test(value);
}

function safeEqual(actual: string, expected: string): boolean {
  const left = Buffer.from(actual);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}
