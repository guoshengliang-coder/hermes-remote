import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion", "environment", "operator", "productionReleaseConfig", "host", "gateway", "secrets", "push",
  "deployment",
]);

/** Firebase project ids: 6-30 characters, lower-case letter first (the Gateway's own pattern). */
export const FIREBASE_PROJECT_ID_PATTERN = /^[a-z][a-z0-9-]{4,29}$/;

export async function loadProductionPushRolloutConfig(filePath) {
  let handle;
  try {
    if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath) fail("push_rollout_config_path_invalid");
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 2 || info.size > 32 * 1024 || (info.mode & 0o077) !== 0) {
      fail("push_rollout_config_file_unsafe");
    }
    let raw;
    try {
      raw = JSON.parse(await handle.readFile("utf8"));
    } catch {
      fail("push_rollout_config_json_invalid");
    }
    exactKeys(raw, TOP_LEVEL_KEYS, "config");
    exactKeys(raw.host, ["hostname", "architecture"], "host");
    exactKeys(raw.gateway, ["origin", "runtimeContract"], "gateway");
    exactKeys(raw.secrets, ["fcmServiceAccountSource"], "secrets");
    exactKeys(raw.push, ["firebaseProjectId"], "push");
    exactKeys(raw.deployment, ["observationSeconds"], "deployment");
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("push_rollout_config_invalid");
    if (raw.host.architecture !== "amd64") fail("push_rollout_architecture_invalid");
    const origin = httpsOrigin(raw.gateway.origin);
    if (raw.gateway.runtimeContract !== "hermes-serve-v1") fail("push_rollout_runtime_contract_invalid");
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
      secrets: {
        fcmServiceAccountSource: absolutePath(raw.secrets.fcmServiceAccountSource, "secrets.fcmServiceAccountSource"),
      },
      push: {
        firebaseProjectId: token(raw.push.firebaseProjectId, FIREBASE_PROJECT_ID_PATTERN, "push.firebaseProjectId"),
      },
      deployment: {
        observationSeconds: integer(raw.deployment.observationSeconds, 1, 300, "observationSeconds"),
      },
    });
  } catch (error) {
    if (error instanceof OpsError) throw error;
    // File-system failures only: parse errors are mapped above so no config bytes reach a message.
    fail(error?.code ? `push_rollout_config_unreadable:${error.code}` : "push_rollout_config_unreadable");
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
  throw new OpsError("productionPushRollout", cause, "production_push_rollout_config");
}
