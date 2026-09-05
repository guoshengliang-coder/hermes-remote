import { spawn } from "node:child_process";
import { lstat, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { OpsError } from "./errors.mjs";

const verifierPath = fileURLToPath(new URL("../../scripts/verify-gateway-image-candidate.mjs", import.meta.url));
const compatibilityVerifierPath = fileURLToPath(new URL("../../scripts/smoke-compat-client.mjs", import.meta.url));
const gatewaySmokeDiagnostics = new Set([
  "configuration",
  "liveness",
  "readiness",
  "capabilities",
  "release_identity",
  "connector_identity",
  "app_auth_rejection",
  "rest_forward_auth",
  "rest_forward_response",
  "rest_forward_contract",
  "rest_forward_ready_timeout",
  "websocket_forward",
  "unexpected",
]);

export async function createStagingSmokeCallbacks(config, options = {}) {
  const env = options.env ?? process.env;
  const connectorEntry = env.HERMES_SMOKE_CONNECTOR_ENTRY;
  const requiredEnvironment = [
    "HERMES_BASE_URL",
    "HERMES_BASIC_AUTH_USERNAME",
    "HERMES_BASIC_AUTH_PASSWORD",
    "FILES_ROOT",
    "UPLOAD_ROOT",
  ];
  if (!connectorEntry || !path.isAbsolute(connectorEntry)
      || requiredEnvironment.some((name) => !env[name])) {
    throw new OpsError("config", "staging_smoke_environment_incomplete", "deploy_smoke_authorize");
  }
  try {
    const info = await lstat(connectorEntry);
    if (info.isSymbolicLink() || !info.isFile()) throw new Error("connector_entry_unsafe");
  } catch {
    throw new OpsError("config", "staging_smoke_connector_entry_invalid", "deploy_smoke_authorize");
  }

  const appToken = await readPrivateToken(config.secrets.appTokenSource, "app_token");
  const connectorToken = await readPrivateToken(config.secrets.connectorTokenSource, "connector_token");
  const internalStatusToken = await readPrivateToken(config.secrets.internalStatusTokenSource, "internal_status_token");
  const spawnImpl = options.spawnImpl ?? spawn;
  const fetchImpl = options.fetchImpl ?? fetch;
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));

  return {
    candidateSmoke: async (request) => {
      const child = spawnImpl(process.execPath, [connectorEntry], {
        env: {
          ...env,
          GATEWAY_URL: `${request.gatewayUrl.replace(/^http:/, "ws:").replace(/^https:/, "wss:")}/v1/connect`,
          CONNECTOR_TOKEN: connectorToken,
          DEVICE_ID: config.gateway.defaultDeviceId,
          SESSION_OBSERVER_ENABLED: "0",
        },
        stdio: "ignore",
      });
      try {
        await waitForConnector(request.gatewayUrl, fetchImpl, sleep);
        await runVerifier(request, request.gatewayUrl, {
          appToken,
          internalStatusToken,
          env,
          spawnImpl,
        });
      } finally {
        await stopChild(child, sleep);
      }
    },
    publicSmoke: async (request) => {
      await waitForConnector(request.gatewayUrl, fetchImpl, sleep, "/relay-health");
      const internalPort = request.candidateSlot === null
        ? config.legacySource.gatewayPort
        : config.slots[request.candidateSlot].gatewayPort;
      await runVerifier(request, `http://127.0.0.1:${internalPort}`, {
        appToken,
        internalStatusToken,
        env,
        spawnImpl,
      });
    },
  };
}

export async function createProductionBaselineSmokeCallbacks(config, options = {}) {
  const smoke = await createStagingSmokeCallbacks(config, options);
  const env = options.env ?? process.env;
  const spawnImpl = options.spawnImpl ?? spawn;
  const appToken = await readPrivateToken(config.secrets.appTokenSource, "app_token");
  return {
    ...smoke,
    legacySmoke: async (request) => {
      await new Promise((resolve, reject) => {
        const child = spawnImpl(process.execPath, [compatibilityVerifierPath], {
          env: {
            ...env,
            PUBLIC_GATEWAY_URL: request.gatewayUrl,
            APP_TOKEN: appToken,
          },
          stdio: "ignore",
        });
        child.once("error", () => reject(new OpsError(
          "managedBaseline",
          "legacy_compatibility_smoke_process_failed",
          "managed_baseline_legacy_smoke",
        )));
        child.once("exit", (code, signal) => {
          if (code === 0) resolve();
          else reject(new OpsError(
            "managedBaseline",
            `legacy_compatibility_smoke_failed=${code ?? signal ?? "unknown"}`,
            "managed_baseline_legacy_smoke",
          ));
        });
      });
    },
  };
}

async function runVerifier(request, internalGatewayUrl, { appToken, internalStatusToken, env, spawnImpl }) {
  await new Promise((resolve, reject) => {
    const child = spawnImpl(process.execPath, [verifierPath], {
      env: {
        ...env,
        PUBLIC_GATEWAY_URL: request.gatewayUrl,
        INTERNAL_GATEWAY_URL: internalGatewayUrl,
        RELAY_HEALTH_PATH: request.publicRoute ? "/relay-health" : "/health",
        APP_TOKEN: appToken,
        INTERNAL_STATUS_TOKEN: internalStatusToken,
        EXPECTED_SOURCE_COMMIT: request.expectedSourceCommit,
        EXPECTED_SERVER_VERSION: request.expectedServerVersion,
        EXPECTED_DEVICE_ID: request.expectedDeviceId,
        HERMES_STATUS_MODE: request.publicRoute ? "live" : "mock",
      },
      stdio: ["ignore", "ignore", "pipe"],
    });
    let stderr = "";
    child.stderr?.setEncoding?.("utf8");
    child.stderr?.on?.("data", (chunk) => {
      stderr = `${stderr}${String(chunk)}`.slice(-16 * 1024);
    });
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      if (error) reject(error);
      else resolve();
    };
    child.once("error", () => finish(new OpsError(
      "deployment",
      "gateway_smoke_process_failed",
      "deploy_smoke_execute",
    )));
    let exitResult;
    const finishResult = (code, signal) => {
      if (code === 0) finish();
      else finish(new OpsError(
        "deployment",
        `gateway_smoke_failed=${safeExit(code, signal)};diagnostic=${parseGatewaySmokeDiagnostic(stderr)}`,
        "deploy_smoke_execute",
      ));
    };
    child.once("exit", (code, signal) => {
      exitResult = { code, signal };
      if (!child.stderr || child.stderr.readableEnded) finishResult(code, signal);
    });
    child.once("close", (code, signal) => {
      finishResult(code ?? exitResult?.code, signal ?? exitResult?.signal);
    });
  });
}

export function parseGatewaySmokeDiagnostic(value) {
  const lines = String(value ?? "").slice(-16 * 1024).split(/\r?\n/).filter(Boolean).reverse();
  for (const line of lines) {
    try {
      const parsed = JSON.parse(line);
      if (parsed?.code === "HR-RELEASE-003"
          && parsed?.stage === "gateway_oci_smoke"
          && isAllowedGatewaySmokeDiagnostic(parsed?.technicalCause)) {
        return `${parsed.code}:${parsed.technicalCause}`;
      }
    } catch {}
  }
  return "unavailable";
}

function isAllowedGatewaySmokeDiagnostic(value) {
  if (typeof value !== "string" || !value.startsWith("smoke_check=")) return false;
  const check = value.slice("smoke_check=".length);
  return gatewaySmokeDiagnostics.has(check)
    || /^rest_forward_http_(?:unknown|[1-5][0-9]{2})$/.test(check);
}

function safeExit(code, signal) {
  if (Number.isSafeInteger(code) && code >= 0 && code <= 255) return String(code);
  return typeof signal === "string" && /^SIG[A-Z0-9]+$/.test(signal) ? signal : "unknown";
}

async function waitForConnector(gatewayUrl, fetchImpl, sleep, healthPath = "/health") {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      const response = await fetchImpl(`${gatewayUrl}${healthPath}`, { signal: AbortSignal.timeout(1_000) });
      if (response.ok && (await response.json()).connectors === 1) return;
    } catch {}
    if (attempt < 79) await sleep(250);
  }
  throw new OpsError("deployment", "candidate_connector_attach_timeout", "deploy_smoke_connector");
}

async function stopChild(child, sleep) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (child.exitCode !== null || child.signalCode !== null) return;
    await sleep(50);
  }
  child.kill("SIGKILL");
}

async function readPrivateToken(filePath, label) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile() || (info.mode & 0o077) !== 0 || info.size > 4096) throw new Error("unsafe");
    const value = (await readFile(filePath, "utf8")).trim();
    if (!/^[A-Za-z0-9._~-]{32,512}$/.test(value)) throw new Error("invalid");
    return value;
  } catch {
    throw new OpsError("config", `${label}_source_invalid`, "deploy_smoke_authorize");
  }
}
