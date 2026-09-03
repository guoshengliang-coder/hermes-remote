import { readFileSync } from "node:fs";
import { resolve } from "node:path";

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
  maxUnauthenticatedAccountConnectors: number;
  maxUnauthenticatedAccountConnectorsPerIp: number;
  maxWirePayloadBytes: number;
  maxAppPayloadBytes: number;
  maxSocketBufferedBytes: number;
  lifecycleEventStoreFile: string;
  maxLifecycleEvents: number;
}

export function loadGatewayConfig(env: NodeJS.ProcessEnv): GatewayConfig {
  const tlsCertFile = env.TLS_CERT_FILE;
  const tlsKeyFile = env.TLS_KEY_FILE;
  if (Boolean(tlsCertFile) !== Boolean(tlsKeyFile)) {
    throw new Error("TLS_CERT_FILE and TLS_KEY_FILE must be configured together");
  }

  const internalStatusToken = optionalSecret(env, "INTERNAL_STATUS_TOKEN", 16);
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
  };
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
