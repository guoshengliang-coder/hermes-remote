import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion", "environment", "operator", "productionReleaseConfig", "host", "gateway", "deployment",
]);

export async function loadProductionIdentityWebRolloutConfig(filePath) {
  let handle;
  try {
    if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath) fail("identity_web_rollout_config_path_invalid");
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 2 || info.size > 32 * 1024 || (info.mode & 0o077) !== 0) {
      fail("identity_web_rollout_config_file_unsafe");
    }
    const raw = JSON.parse(await handle.readFile("utf8"));
    exactKeys(raw, TOP_LEVEL_KEYS, "config");
    exactKeys(raw.host, ["hostname", "architecture"], "host");
    exactKeys(raw.gateway, ["origin", "runtimeContract"], "gateway");
    exactKeys(raw.deployment, ["observationSeconds"], "deployment");
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("identity_web_rollout_config_invalid");
    if (raw.host.architecture !== "amd64") fail("identity_web_rollout_architecture_invalid");
    const origin = httpsOrigin(raw.gateway.origin);
    if (raw.gateway.runtimeContract !== "hermes-serve-v1") fail("identity_web_rollout_runtime_contract_invalid");
    return Object.freeze({
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      productionReleaseConfig: absolutePath(raw.productionReleaseConfig, "productionReleaseConfig"),
      host: {
        hostname: token(raw.host.hostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "host.hostname"),
        architecture: "amd64",
      },
      gateway: { origin, runtimeContract: "hermes-serve-v1" },
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

function httpsOrigin(value) {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.pathname !== "/"
        || parsed.search || parsed.hash || parsed.origin !== value) fail("gateway_origin_invalid");
    return parsed.origin;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("gateway_origin_invalid");
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
  throw new OpsError("productionIdentityWebRollout", cause, "production_identity_web_rollout_config");
}
