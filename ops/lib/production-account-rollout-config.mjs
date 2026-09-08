import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion",
  "environment",
  "operator",
  "productionReleaseConfig",
  "host",
  "database",
  "gateway",
  "secrets",
  "deployment",
]);

export async function loadProductionAccountRolloutConfig(filePath) {
  let handle;
  try {
    if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath) fail("account_rollout_config_path_invalid");
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 2 || info.size > 64 * 1024 || (info.mode & 0o077) !== 0) {
      fail("account_rollout_config_file_unsafe");
    }
    const raw = JSON.parse(await handle.readFile("utf8"));
    exactKeys(raw, TOP_LEVEL_KEYS, "config");
    exactKeys(raw.host, ["hostname", "architecture"], "host");
    exactKeys(raw.database, ["ssl", "migrationLockId"], "database");
    exactKeys(raw.gateway, ["origin", "emailIssuer", "emailFromDomain", "trustLoopbackProxy"], "gateway");
    exactKeys(raw.secrets, [
      "accountDatabaseUrlSource",
      "accountTokenHashKeySource",
      "accountEmailOtpHashKeySource",
      "resendApiKeySource",
      "resendWebhookSecretSource",
      "emailFromSource",
    ], "secrets");
    exactKeys(raw.deployment, ["observationSeconds"], "deployment");
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("account_rollout_config_invalid");
    if (raw.host.architecture !== "amd64") fail("account_rollout_architecture_invalid");
    if (typeof raw.database.ssl !== "boolean") fail("account_rollout_database_ssl_invalid");
    if (typeof raw.gateway.trustLoopbackProxy !== "boolean") fail("account_rollout_proxy_flag_invalid");
    const origin = httpsOrigin(raw.gateway.origin, "gateway.origin");
    const issuer = httpsOrigin(raw.gateway.emailIssuer, "gateway.emailIssuer");
    if (issuer !== origin) fail("account_rollout_issuer_origin_mismatch");
    const secrets = Object.fromEntries(Object.entries(raw.secrets).map(([name, value]) => [name, absolutePath(value, name)]));
    if (new Set(Object.values(secrets)).size !== Object.keys(secrets).length) fail("account_rollout_secret_sources_must_be_distinct");
    return Object.freeze({
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      productionReleaseConfig: absolutePath(raw.productionReleaseConfig, "productionReleaseConfig"),
      host: {
        hostname: token(raw.host.hostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "host.hostname"),
        architecture: "amd64",
      },
      database: {
        ssl: raw.database.ssl,
        migrationLockId: integer(raw.database.migrationLockId, 1, Number.MAX_SAFE_INTEGER, "migrationLockId"),
      },
      gateway: {
        origin,
        emailIssuer: issuer,
        emailFromDomain: token(raw.gateway.emailFromDomain, /^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/, "emailFromDomain"),
        trustLoopbackProxy: raw.gateway.trustLoopbackProxy,
      },
      secrets,
      deployment: {
        observationSeconds: integer(raw.deployment.observationSeconds, 1, 300, "observationSeconds"),
      },
    });
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  } finally {
    await handle?.close().catch(() => {});
  }
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label}_invalid`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label}_fields_invalid`);
  }
}

function absolutePath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/"
      || !/^\/[A-Za-z0-9._/-]+$/.test(value) || value.includes("//")) {
    fail(`${label}_path_invalid`);
  }
  return value;
}

function httpsOrigin(value, label) {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.pathname !== "/"
        || parsed.search || parsed.hash || parsed.origin !== value) fail(`${label}_invalid`);
    return parsed.origin;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(`${label}_invalid`);
  }
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value) || value.includes("..")) fail(`${label}_invalid`);
  return value;
}

function integer(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) fail(`${label}_invalid`);
  return value;
}

function fail(cause) {
  throw new OpsError("productionAccountRollout", cause, "production_account_rollout_config");
}
