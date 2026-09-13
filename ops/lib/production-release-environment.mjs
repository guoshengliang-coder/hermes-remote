import { createHash } from "node:crypto";
import { lstat, readFile } from "node:fs/promises";
import path from "node:path";
import { renderDeployGatewayEnvironment } from "./deploy-system.mjs";
import { OpsError } from "./errors.mjs";

const EMAIL_KEYS = Object.freeze([
  "PORT",
  "HOST",
  "APP_TOKEN_FILE",
  "CONNECTOR_TOKEN_FILE",
  "INTERNAL_STATUS_TOKEN_FILE",
  "DEFAULT_DEVICE_ID",
  "LIFECYCLE_EVENT_STORE_FILE",
  "GATEWAY_LOG_LEVEL",
  "ACCOUNT_AUTH_ENABLED",
  "ACCOUNT_BINDING_ENABLED",
  "ACCOUNT_MULTI_DEVICE_ENABLED",
  "ACCOUNT_DEVICE_SHARING_ENABLED",
  "ACCOUNT_EMAIL_OTP_ENABLED",
  "ACCOUNT_RESEND_WEBHOOK_ENABLED",
  "ACCOUNT_GOOGLE_AUTH_ENABLED",
  "ACCOUNT_IDENTITY_MANAGEMENT_ENABLED",
  "ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED",
  "ACCOUNT_WEB_SESSION_ENABLED",
  "ACCOUNT_DELETION_ENABLED",
  "ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED",
  "ACCOUNT_DATABASE_URL_FILE",
  "ACCOUNT_TOKEN_HASH_KEY_FILE",
  "ACCOUNT_EMAIL_OTP_HASH_KEY_FILE",
  "ACCOUNT_RESEND_API_KEY_FILE",
  "ACCOUNT_RESEND_WEBHOOK_SECRET_FILE",
  "ACCOUNT_EMAIL_FROM_FILE",
  "ACCOUNT_EMAIL_OTP_ISSUER",
  "ACCOUNT_DATABASE_SSL",
  "ACCOUNT_DATABASE_POOL_SIZE",
  "ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS",
  "ACCOUNT_TRUST_LOOPBACK_PROXY",
  "ACCOUNT_GATEWAY_ORIGIN",
  "ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS",
  "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS",
  "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP",
  "MAX_ACCOUNT_LIFECYCLE_EVENTS",
  "ACCOUNT_LIFECYCLE_RETENTION_DAYS",
  "ACCOUNT_AUDIT_RETENTION_DAYS",
  "MAX_LIFECYCLE_EVENTS",
]);

const EMAIL_EXACT = Object.freeze({
  HOST: "127.0.0.1",
  APP_TOKEN_FILE: "/run/hermes-go/secrets/app-token",
  CONNECTOR_TOKEN_FILE: "/run/hermes-go/secrets/connector-token",
  INTERNAL_STATUS_TOKEN_FILE: "/run/hermes-go/secrets/internal-status-token",
  LIFECYCLE_EVENT_STORE_FILE: "/var/lib/hermes-go/lifecycle-events.json",
  GATEWAY_LOG_LEVEL: "info",
  ACCOUNT_AUTH_ENABLED: "1",
  ACCOUNT_BINDING_ENABLED: "0",
  ACCOUNT_MULTI_DEVICE_ENABLED: "0",
  ACCOUNT_DEVICE_SHARING_ENABLED: "0",
  ACCOUNT_EMAIL_OTP_ENABLED: "1",
  ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
  ACCOUNT_GOOGLE_AUTH_ENABLED: "0",
  ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "0",
  ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED: "0",
  ACCOUNT_WEB_SESSION_ENABLED: "0",
  ACCOUNT_DELETION_ENABLED: "0",
  ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED: "0",
  ACCOUNT_DATABASE_URL_FILE: "/run/hermes-go/secrets/account-database-url",
  ACCOUNT_TOKEN_HASH_KEY_FILE: "/run/hermes-go/secrets/account-token-hash-key",
  ACCOUNT_EMAIL_OTP_HASH_KEY_FILE: "/run/hermes-go/secrets/account-email-otp-hash-key",
  ACCOUNT_RESEND_API_KEY_FILE: "/run/hermes-go/secrets/resend-api-key",
  ACCOUNT_RESEND_WEBHOOK_SECRET_FILE: "/run/hermes-go/secrets/resend-webhook-secret",
  ACCOUNT_EMAIL_FROM_FILE: "/run/hermes-go/secrets/account-email-from",
  ACCOUNT_DATABASE_POOL_SIZE: "10",
  ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS: "3000",
  ACCOUNT_TRUST_LOOPBACK_PROXY: "1",
  ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS: "256",
  ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS: "16",
  ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP: "4",
  MAX_ACCOUNT_LIFECYCLE_EVENTS: "10000",
  ACCOUNT_LIFECYCLE_RETENTION_DAYS: "30",
  ACCOUNT_AUDIT_RETENTION_DAYS: "180",
  MAX_LIFECYCLE_EVENTS: "10000",
});

export async function inspectProductionReleaseEnvironment(config, activeSlot) {
  const selected = config.slots[activeSlot];
  if (!selected) fail("production_release_active_slot_unknown");
  const filePath = path.join(config.paths.configRoot, "slots", activeSlot, "gateway.env");
  const content = await readPrivateEnvironment(filePath);
  const disabled = renderDeployGatewayEnvironment(config, activeSlot);
  if (content === disabled) {
    return Object.freeze({
      mode: "disabled",
      digest: digest(content),
      values: null,
    });
  }

  const values = parseCanonicalEnvironment(content);
  const origin = publicOrigin(config);
  const bindingEnabled = values.ACCOUNT_BINDING_ENABLED === "1"
    && values.ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED === "1";
  const emailOnly = values.ACCOUNT_BINDING_ENABLED === "0"
    && values.ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED === "0";
  const multiDeviceEnabled = values.ACCOUNT_MULTI_DEVICE_ENABLED === "1";
  if ((!bindingEnabled && !emailOnly) || (multiDeviceEnabled && !bindingEnabled)) {
    fail("production_release_email_environment_invalid");
  }
  const expected = {
    ...EMAIL_EXACT,
    PORT: String(selected.gatewayPort),
    DEFAULT_DEVICE_ID: config.gateway.defaultDeviceId,
    ACCOUNT_EMAIL_OTP_ISSUER: origin,
    ACCOUNT_GATEWAY_ORIGIN: origin,
    ACCOUNT_BINDING_ENABLED: bindingEnabled ? "1" : "0",
    ACCOUNT_MULTI_DEVICE_ENABLED: multiDeviceEnabled ? "1" : "0",
    ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED: bindingEnabled ? "1" : "0",
  };
  for (const key of EMAIL_KEYS) {
    if (key === "ACCOUNT_DATABASE_SSL") {
      if (!new Set(["0", "1"]).has(values[key])) fail("production_release_email_environment_invalid");
    } else if (values[key] !== expected[key]) {
      fail("production_release_email_environment_invalid");
    }
  }
  return Object.freeze({
    mode: multiDeviceEnabled ? "email_multi_device" : (bindingEnabled ? "email_binding" : "email_otp"),
    digest: digest(content),
    values: Object.freeze({ ...values }),
  });
}

export function renderProductionReleaseEnvironment(config, slot, inspected) {
  const selected = config.slots[slot];
  if (!selected) fail("production_release_candidate_slot_unknown");
  if (inspected?.mode === "disabled") return renderDeployGatewayEnvironment(config, slot);
  if (!new Set(["email_otp", "email_binding", "email_multi_device"]).has(inspected?.mode) || !inspected.values) {
    fail("production_release_environment_mode_invalid");
  }
  return EMAIL_KEYS.map((key) => {
    const value = key === "PORT" ? String(selected.gatewayPort) : inspected.values[key];
    if (typeof value !== "string" || /[\r\n\0]/.test(value)) {
      fail("production_release_email_environment_invalid");
    }
    return `${key}=${value}`;
  }).join("\n") + "\n";
}

export function renderBindingRolloutEnvironment(config, slot, inspected) {
  const selected = config.slots[slot];
  if (!selected) fail("production_release_candidate_slot_unknown");
  if (inspected?.mode !== "email_otp" || !inspected.values) {
    fail("production_release_binding_requires_email_environment");
  }
  return EMAIL_KEYS.map((key) => {
    let value = inspected.values[key];
    if (key === "PORT") value = String(selected.gatewayPort);
    if (key === "ACCOUNT_BINDING_ENABLED" || key === "ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED") {
      value = "1";
    }
    if (typeof value !== "string" || /[\r\n\0]/.test(value)) {
      fail("production_release_email_environment_invalid");
    }
    return `${key}=${value}`;
  }).join("\n") + "\n";
}

export function renderMultiDeviceRolloutEnvironment(config, slot, inspected) {
  const selected = config.slots[slot];
  if (!selected) fail("production_release_candidate_slot_unknown");
  if (inspected?.mode !== "email_binding" || !inspected.values) {
    fail("production_release_multi_device_requires_binding_environment");
  }
  return EMAIL_KEYS.map((key) => {
    let value = inspected.values[key];
    if (key === "PORT") value = String(selected.gatewayPort);
    if (key === "ACCOUNT_MULTI_DEVICE_ENABLED") value = "1";
    if (typeof value !== "string" || /[\r\n\0]/.test(value)) {
      fail("production_release_email_environment_invalid");
    }
    return `${key}=${value}`;
  }).join("\n") + "\n";
}

export function sameProductionReleaseEnvironment(left, right) {
  return left?.mode === right?.mode && left?.digest === right?.digest;
}

function parseCanonicalEnvironment(content) {
  if (!content.endsWith("\n") || content.includes("\r") || content.includes("\0")) {
    fail("production_release_environment_format_invalid");
  }
  const lines = content.slice(0, -1).split("\n");
  if (lines.length !== EMAIL_KEYS.length) fail("production_release_environment_fields_invalid");
  const values = {};
  lines.forEach((line, index) => {
    const separator = line.indexOf("=");
    const key = line.slice(0, separator);
    const value = line.slice(separator + 1);
    if (separator < 1 || key !== EMAIL_KEYS[index] || key in values || !value) {
      fail("production_release_environment_fields_invalid");
    }
    values[key] = value;
  });
  return values;
}

async function readPrivateEnvironment(filePath) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile() || info.size < 2 || info.size > 64 * 1024
        || (info.mode & 0o077) !== 0) {
      fail("production_release_environment_file_unsafe");
    }
    return await readFile(filePath, "utf8");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("production_release_environment_file_unreadable");
  }
}

function publicOrigin(config) {
  const port = config.nginx.listenPort === 443 ? "" : `:${config.nginx.listenPort}`;
  return `https://${config.nginx.serverName}${port}`;
}

function digest(content) {
  return createHash("sha256").update(content).digest("hex");
}

function fail(cause) {
  throw new OpsError("productionRelease", cause, "production_release_environment");
}
