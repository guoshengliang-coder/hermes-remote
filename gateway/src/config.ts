import { readFileSync } from "node:fs";
import { isAbsolute, resolve } from "node:path";
import { parseGatewayLogLevel, type GatewayLogLevel } from "./gateway-log.js";

export interface GatewayConfig {
  port: number;
  host: string;
  defaultDeviceId: string;
  appToken: string;
  connectorToken: string;
  internalStatusToken?: string;
  tlsCertFile?: string;
  tlsKeyFile?: string;
  maxBodyBytes: number;
  requestTimeoutMs: number;
  maxPendingRequests: number;
  maxWebSocketTunnels: number;
  maxControlConnections: number;
  controlHeartbeatIntervalMs: number;
  controlHeartbeatTimeoutMs: number;
  maxUnauthenticatedAccountConnectors: number;
  maxUnauthenticatedAccountConnectorsPerIp: number;
  maxWirePayloadBytes: number;
  maxAppPayloadBytes: number;
  maxSocketBufferedBytes: number;
  lifecycleEventStoreFile: string;
  maxLifecycleEvents: number;
  /** GATEWAY_LOG_LEVEL: off | error | info (default) | debug. */
  logLevel: GatewayLogLevel;
  /** Directory of the built Web app served at /app/, present only when WEB_APP_ENABLED=1. */
  webAppDir?: string;
}

export function loadGatewayConfig(env: NodeJS.ProcessEnv): GatewayConfig {
  const tlsCertFile = env.TLS_CERT_FILE;
  const tlsKeyFile = env.TLS_KEY_FILE;
  if (Boolean(tlsCertFile) !== Boolean(tlsKeyFile)) {
    throw new Error("TLS_CERT_FILE and TLS_KEY_FILE must be configured together");
  }

  const internalStatusToken = optionalSecret(env, "INTERNAL_STATUS_TOKEN", 16);
  const webAppDir = webAppDirectory(env);
  const controlHeartbeatIntervalMs = positiveIntEnv(env, "CONTROL_HEARTBEAT_INTERVAL_MS", 5_000, 300_000);
  const controlHeartbeatTimeoutMs = positiveIntEnv(env, "CONTROL_HEARTBEAT_TIMEOUT_MS", 15_000, 300_000);
  if (controlHeartbeatTimeoutMs <= controlHeartbeatIntervalMs) {
    throw new Error("CONTROL_HEARTBEAT_TIMEOUT_MS must be greater than CONTROL_HEARTBEAT_INTERVAL_MS");
  }
  return {
    port: positiveIntEnv(env, "PORT", 8787, 65_535),
    host: env.HOST ?? "0.0.0.0",
    defaultDeviceId: env.DEFAULT_DEVICE_ID ?? "mac-mini",
    appToken: requireSecret(env, "APP_TOKEN"),
    connectorToken: requireSecret(env, "CONNECTOR_TOKEN"),
    ...(internalStatusToken ? { internalStatusToken } : {}),
    ...(tlsCertFile ? { tlsCertFile } : {}),
    ...(tlsKeyFile ? { tlsKeyFile } : {}),
    maxBodyBytes: positiveIntEnv(env, "MAX_BODY_BYTES", 10 * 1024 * 1024),
    requestTimeoutMs: positiveIntEnv(env, "REQUEST_TIMEOUT_MS", 60_000),
    maxPendingRequests: positiveIntEnv(env, "MAX_PENDING_REQUESTS", 128),
    maxWebSocketTunnels: positiveIntEnv(env, "MAX_WS_TUNNELS", 32),
    maxControlConnections: positiveIntEnv(env, "MAX_CONTROL_CONNECTIONS", 32),
    controlHeartbeatIntervalMs,
    controlHeartbeatTimeoutMs,
    maxUnauthenticatedAccountConnectors: positiveIntEnv(
      env,
      "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS",
      16,
      1024,
    ),
    maxUnauthenticatedAccountConnectorsPerIp: positiveIntEnv(
      env,
      "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP",
      4,
      128,
    ),
    maxWirePayloadBytes: positiveIntEnv(env, "MAX_WIRE_PAYLOAD_BYTES", 20 * 1024 * 1024),
    maxAppPayloadBytes: positiveIntEnv(env, "MAX_APP_WS_PAYLOAD_BYTES", 12 * 1024 * 1024),
    maxSocketBufferedBytes: positiveIntEnv(env, "MAX_SOCKET_BUFFERED_BYTES", 24 * 1024 * 1024),
    lifecycleEventStoreFile: resolve(
      env.LIFECYCLE_EVENT_STORE_FILE
        ?? (env.NODE_ENV === "production"
          ? "/var/lib/hermes-remote/lifecycle-events.json"
          : ".data/lifecycle-events.json"),
    ),
    maxLifecycleEvents: positiveIntEnv(env, "MAX_LIFECYCLE_EVENTS", 10_000, 1_000_000),
    logLevel: parseGatewayLogLevel(env.GATEWAY_LOG_LEVEL),
    ...(webAppDir ? { webAppDir } : {}),
  };
}

function webAppDirectory(env: NodeJS.ProcessEnv): string | undefined {
  const enabled = env.WEB_APP_ENABLED;
  if (enabled === undefined || enabled === "0") return undefined;
  if (enabled !== "1") throw new Error("WEB_APP_ENABLED must be 0 or 1");
  const dir = env.WEB_APP_DIR;
  if (!dir || !isAbsolute(dir)) {
    throw new Error("WEB_APP_DIR must be an absolute directory when WEB_APP_ENABLED=1");
  }
  return dir;
}

function optionalSecret(env: NodeJS.ProcessEnv, name: string, minimumLength: number): string | undefined {
  const file = env[`${name}_FILE`];
  const value = env[name] ?? (file ? readFileSync(file, "utf8").trim() : undefined);
  if (value === undefined) return undefined;
  if (value.length < minimumLength) {
    throw new Error(`${name} must contain at least ${minimumLength} characters`);
  }
  return value;
}

function requireSecret(env: NodeJS.ProcessEnv, name: string): string {
  const file = env[`${name}_FILE`];
  const value = env[name] ?? (file ? readFileSync(file, "utf8").trim() : undefined);
  if (!value || value.length < 8) throw new Error(`${name} must contain at least 8 characters`);
  return value;
}

function positiveIntEnv(
  env: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
  max = 1024 * 1024 * 1024,
): number {
  const raw = env[name];
  if (raw === undefined) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0 || value > max) {
    throw new Error(`${name} must be an integer between 1 and ${max}`);
  }
  return value;
}
