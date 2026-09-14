import { createHash, randomBytes, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, mkdtemp, open, realpath, rm, writeFile } from "node:fs/promises";
import https from "node:https";
import { hostname as systemHostname, tmpdir } from "node:os";
import path from "node:path";
import { loadBundleManifest } from "./config.mjs";
import { loadCurrentManifest, resolveActiveSlot } from "./deploy-command.mjs";
import { acquireDeploymentLock, releaseDeploymentLock } from "./deploy-state.mjs";
import { OpsError } from "./errors.mjs";
import { loadManagedBaselineConfig } from "./managed-baseline-config.mjs";
import { verifyPreservedLegacyStatus } from "./production-legacy-status.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderComponentRolloutEnvironment,
} from "./production-release-environment.mjs";
import { verifyPreservedEmailSurface } from "./production-release.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";
import { verifyDesktopComponentReleaseV2 } from "../../scripts/lib/desktop-managed-release.mjs";

const RUNTIME_CONTRACT = "hermes-serve-v1";

export async function executeProductionComponentRollout(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 120_000 });
  const now = options.now ?? (() => new Date());
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const fetchImpl = options.fetchImpl ?? fetch;
  const ownership = options.ownership ?? { host: { uid: 0, gid: 0 } };
  const runId = options.runId ?? randomUUID();
  authorize(config, options);

  const releaseConfig = await (options.loadReleaseConfig ?? loadManagedBaselineConfig)(config.productionReleaseConfig);
  if (releaseConfig.host.hostname !== config.host.hostname || releaseConfig.host.architecture !== config.host.architecture
      || releaseConfig.gateway.accountAuthEnabled !== false
      || releaseConfig.gateway.accountBindingEnabled !== false
      || releaseConfig.database !== null) {
    fail("component_rollout_release_config_invalid", "production_component_rollout_preflight");
  }
  if (config.gateway.origin !== productionOrigin(releaseConfig)) {
    fail("component_rollout_gateway_origin_mismatch", "production_component_rollout_preflight");
  }
  const currentManifest = await (options.loadCurrentManifest ?? loadCurrentManifest)(releaseConfig);
  const targetManifest = await (options.loadBundleManifest ?? loadBundleManifest)(releaseConfig.targetArtifactManifest);
  if (!sameRelease(currentManifest, targetManifest)
      || currentManifest.releaseContract?.databaseSchemaVersion !== 15
      || !currentManifest.releaseContract?.supportedPostgresqlMajors?.includes(18)) {
    fail("component_rollout_current_release_mismatch", "production_component_rollout_preflight");
  }
  const activeSlot = await (options.resolveActiveSlot ?? resolveActiveSlot)(releaseConfig, currentManifest);
  const selected = releaseConfig.slots[activeSlot];
  if (!selected || runner.run("systemctl", ["is-active", "--quiet", `${selected.serviceName}.service`], {
    allowFailure: true,
  }).status !== 0) {
    fail("component_rollout_active_service_invalid", "production_component_rollout_preflight");
  }

  let inspected;
  try {
    inspected = await (options.inspectEnvironment ?? inspectProductionReleaseEnvironment)(releaseConfig, activeSlot);
  } catch (error) {
    fail(`component_rollout_environment_invalid:${technical(error)}`, "production_component_rollout_preflight");
  }
  if (inspected.mode !== "email_sharing") {
    fail("component_rollout_requires_sharing_state", "production_component_rollout_preflight");
  }
  const environmentPath = path.join(releaseConfig.paths.configRoot, "slots", activeSlot, "gateway.env");
  const previousEnvironment = await safeManagedFile(environmentPath, 64 * 1024);
  const targets = { journal: path.join(releaseConfig.paths.stateRoot, "ops", "component-rollout.json") };
  await requireAbsent(targets.journal, "component_rollout_journal_exists");

  const verifyPublished = options.verifyPublished ?? verifyPublishedComponentRelease;
  await verifyPublished(config, fetchImpl);
  const material = {
    appToken: (await safeSecretFile(releaseConfig.secrets.appTokenSource)).toString("utf8").trim(),
    internalStatusToken: (await safeSecretFile(releaseConfig.secrets.internalStatusTokenSource)).toString("utf8").trim(),
  };
  if (material.appToken.length < 8 || material.internalStatusToken.length < 8) {
    fail("component_rollout_smoke_secret_invalid", "production_component_rollout_preflight");
  }
  const verifyPrevious = options.verifyPrevious ?? verifyPreviousComponentSurface;
  let legacyState;
  try {
    legacyState = await verifyPrevious({
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material,
      probeWebSocket: options.probeWebSocket ?? probeWebSocketUpgrade,
      probeDeviceWebSocket: options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus,
    });
  } catch (error) {
    fail(`component_rollout_sharing_preflight_failed:${technical(error)}`, "production_component_rollout_preflight");
  }

  let deploymentLock;
  try {
    deploymentLock = await acquireDeploymentLock(path.join(releaseConfig.paths.stateRoot, "ops", "deploy.lock"), runId);
  } catch (error) {
    fail(`component_rollout_lock_unavailable:${technical(error)}`, "production_component_rollout_preflight");
  }
  try {
    await ensureManagedDirectory(path.dirname(targets.journal), 0o700, ownership.host);
    await atomicWrite(targets.journal, journal({ runId, stage: "checkpointed", activeSlot, currentManifest, config, now }), 0o600, ownership.host);
    let liveMutationStarted = false;
    try {
      liveMutationStarted = true;
      await atomicWrite(
        environmentPath,
        renderComponentRolloutEnvironment(releaseConfig, activeSlot, inspected),
        0o600,
        ownership.host,
      );
      await atomicWrite(targets.journal, journal({ runId, stage: "environment_installed", activeSlot, currentManifest, config, now }), 0o600, ownership.host);
      runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
      await atomicWrite(targets.journal, journal({ runId, stage: "restarted", activeSlot, currentManifest, config, now }), 0o600, ownership.host);

      const verifyEnabled = options.verifyEnabled ?? verifyEnabledComponentSurface;
      const verification = {
        config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material,
        expectedLegacyState: legacyState,
        probeWebSocket: options.probeWebSocket ?? probeWebSocketUpgrade,
        probeDeviceWebSocket: options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus,
      };
      await verifyEnabled(verification);
      await verifyPublished(config, fetchImpl);
      await sleep(config.deployment.observationSeconds * 1000);
      await verifyEnabled(verification);
      await verifyPublished(config, fetchImpl);
      await atomicWrite(targets.journal, journal({ runId, stage: "committed", activeSlot, currentManifest, config, now }), 0o600, ownership.host);
      return {
        ok: true,
        command: "production-component-rollout",
        runId,
        activeSlot,
        serverVersion: currentManifest.serverVersion,
        sourceCommit: currentManifest.sourceCommit,
        componentManifestSchemaVersion: 2,
        componentReleaseVersion: config.componentRelease.releaseVersion,
        componentManifestSha256: config.componentRelease.manifestSha256,
        runtimeContract: RUNTIME_CONTRACT,
        stage: "committed",
      };
    } catch (error) {
      if (liveMutationStarted) {
        let rollbackError;
        try {
          await atomicWrite(environmentPath, previousEnvironment, 0o600, ownership.host);
          runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
          await verifyPrevious({
            config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material,
            expectedLegacyState: legacyState,
            probeWebSocket: options.probeWebSocket ?? probeWebSocketUpgrade,
            probeDeviceWebSocket: options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus,
          });
        } catch (rollbackFailure) {
          rollbackError = rollbackFailure;
        }
        await atomicWrite(targets.journal, journal({
          runId,
          stage: rollbackError ? "rollback_failed" : "rolled_back",
          activeSlot,
          currentManifest,
          config,
          now,
        }), 0o600, ownership.host).catch(() => {});
        if (rollbackError) fail("component_rollout_rollback_failed", "production_component_rollout_rollback");
      }
      if (error instanceof OpsError && error.kind === "productionComponentRollout") throw error;
      fail(error instanceof Error ? error.message : error, "production_component_rollout_execute");
    }
  } finally {
    await releaseDeploymentLock(deploymentLock).catch(() => {});
  }
}

export async function verifyPublishedComponentRelease(config, fetchImpl = fetch) {
  let staging;
  try {
    staging = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-component-rollout-")));
    const response = await boundedFetch(fetchImpl, config.componentRelease.manifestUrl);
    const bytes = Buffer.from(await response?.arrayBuffer?.() ?? []);
    if (response?.status !== 200 || bytes.length < 2 || bytes.length > 64 * 1024
        || sha256(bytes) !== config.componentRelease.manifestSha256
        || response.headers.get("cache-control") !== "public, max-age=31536000, immutable"
        || response.headers.get("x-content-type-options") !== "nosniff") {
      fail("component_rollout_manifest_publication_invalid", "production_component_rollout_manifest");
    }
    const envelope = JSON.parse(bytes.toString("utf8"));
    const payload = JSON.parse(Buffer.from(envelope.payload ?? "", "base64url").toString("utf8"));
    if (envelope.keyId !== config.componentRelease.keyId || payload.schemaVersion !== 2
        || payload.releaseVersion !== config.componentRelease.releaseVersion
        || payload.channel !== "internal" || payload.platform !== "macos" || payload.architecture !== "arm64"
        || !Array.isArray(payload.components) || payload.components.length < 2 || payload.components.length > 5) {
      fail("component_rollout_manifest_identity_invalid", "production_component_rollout_manifest");
    }
    const manifestPath = path.join(staging, path.basename(new URL(config.componentRelease.manifestUrl).pathname));
    await writeFile(manifestPath, bytes, { flag: "wx", mode: 0o600 });
    for (const component of payload.components) {
      const url = new URL(component.downloadURL);
      const expectedPrefix = `/desktop/components/${config.componentRelease.releaseVersion}/`;
      if (url.origin !== config.gateway.origin || path.posix.dirname(url.pathname) + "/" !== expectedPrefix
          || path.posix.basename(url.pathname) !== component.fileName
          || !Number.isSafeInteger(component.sizeBytes) || component.sizeBytes < 1 || component.sizeBytes > 512 * 1024 * 1024) {
        fail("component_rollout_artifact_url_invalid", "production_component_rollout_manifest");
      }
      const artifactResponse = await boundedFetch(fetchImpl, url.toString(), 30_000);
      const artifact = Buffer.from(await artifactResponse?.arrayBuffer?.() ?? []);
      if (artifactResponse?.status !== 200 || artifact.length !== component.sizeBytes
          || artifactResponse.headers.get("cache-control") !== "public, max-age=31536000, immutable"
          || artifactResponse.headers.get("x-content-type-options") !== "nosniff") {
        fail("component_rollout_artifact_publication_invalid", "production_component_rollout_manifest");
      }
      await writeFile(path.join(staging, component.fileName), artifact, { flag: "wx", mode: 0o600 });
    }
    await verifyDesktopComponentReleaseV2({
      manifestPath,
      artifactDirectory: staging,
      expectedKeyId: config.componentRelease.keyId,
      publicKey: config.componentRelease.publicKey,
      expectedOrigin: config.gateway.origin,
      expectedChannel: "internal",
      expectedArchitecture: "arm64",
    });
    return true;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(`component_rollout_manifest_verification_failed:${technical(error)}`, "production_component_rollout_manifest");
  } finally {
    if (staging) await rm(staging, { recursive: true, force: true }).catch(() => {});
  }
}

export async function verifyEnabledComponentSurface(request) {
  return verifyCommon(request, true);
}

export async function verifyPreviousComponentSurface(request) {
  return verifyCommon(request, false);
}

async function verifyCommon({
  config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material,
  expectedLegacyState = null, probeWebSocket, probeDeviceWebSocket,
}, componentInstallEnabled) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("component_rollout_service_inactive", "production_component_rollout_verify");
  }
  const loopback = `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
  const readiness = await fetchJsonRetry(fetchImpl, `${loopback}/readyz`, {}, sleep);
  if (readiness?.status !== "ready" || readiness?.checks?.database !== "ok"
      || readiness?.checks?.migrations !== "ok" || readiness?.checks?.postgresql !== "supported") {
    fail("component_rollout_readiness_invalid", "production_component_rollout_verify");
  }
  const surface = { bindingEnabled: true, multiDeviceEnabled: true, identityWebEnabled: true, sharingEnabled: true, componentInstallEnabled };
  await verifyPreservedEmailSurfaceRetry({ gatewayUrl: config.gateway.origin, publicRoute: true }, fetchImpl, surface, sleep);
  await verifyPreservedEmailSurfaceRetry({ gatewayUrl: loopback, publicRoute: false }, fetchImpl, surface, sleep);
  if (!await probeWebSocket(config.gateway.origin) || await probeDeviceWebSocket(config.gateway.origin) !== 401) {
    fail("component_rollout_websocket_guard_invalid", "production_component_rollout_verify");
  }
  const legacyState = await verifyPreservedLegacyStatus({
    fetchImpl,
    url: `${config.gateway.origin}/api/status`,
    appToken: material.appToken,
    sleep,
    expectedState: expectedLegacyState,
  });
  if (legacyState === null) fail("component_rollout_legacy_unhealthy", "production_component_rollout_verify");
  const version = await fetchJsonRetry(fetchImpl, `${loopback}/internal/version`, {
    headers: { authorization: `Bearer ${material.internalStatusToken}` },
  }, sleep);
  if (version?.serverVersion !== currentManifest.serverVersion || version?.sourceCommit !== currentManifest.sourceCommit) {
    fail("component_rollout_release_identity_mismatch", "production_component_rollout_verify");
  }
  return legacyState;
}

async function verifyPreservedEmailSurfaceRetry(request, fetchImpl, options, sleep) {
  let lastError;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      await verifyPreservedEmailSurface(request, fetchImpl, options);
      return;
    } catch (error) {
      lastError = error;
    }
    if (attempt < 19) await sleep(250);
  }
  throw lastError;
}

async function boundedFetch(fetchImpl, url, timeout = 5_000) {
  try {
    return await fetchImpl(url, { signal: AbortSignal.timeout(timeout) });
  } catch {
    return null;
  }
}

async function fetchJsonRetry(fetchImpl, url, init, sleep) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      const response = await fetchImpl(url, { ...init, signal: AbortSignal.timeout(3_000) });
      if (response.ok) return await response.json();
    } catch {}
    if (attempt < 19) await sleep(250);
  }
  return null;
}

async function probeWebSocketUpgrade(origin) {
  return await probeWebSocketStatus(origin, "/v2/connect") === 101;
}

async function probeDeviceWebSocketStatus(origin) {
  return await probeWebSocketStatus(origin, "/v2/devices/probe-device/ws");
}

async function probeWebSocketStatus(origin, pathname) {
  return await new Promise((resolve) => {
    let settled = false;
    const finish = (value) => { if (!settled) { settled = true; resolve(value); } };
    const request = https.request(new URL(pathname, origin), {
      method: "GET",
      headers: {
        Connection: "Upgrade",
        Upgrade: "websocket",
        "Sec-WebSocket-Key": randomBytes(16).toString("base64"),
        "Sec-WebSocket-Version": "13",
      },
      timeout: 5_000,
    });
    request.once("upgrade", (response, socket) => { socket.destroy(); finish(response.statusCode ?? 0); });
    request.once("response", (response) => { const status = response.statusCode ?? 0; response.resume(); finish(status); });
    request.once("timeout", () => { request.destroy(); finish(0); });
    request.once("error", () => finish(0));
    request.end();
  });
}

async function safeSecretFile(filePath) {
  return safeFile(filePath, 8 * 1024, 0o077, "component_rollout_secret_file_unsafe");
}

async function safeManagedFile(filePath, maximumBytes) {
  return safeFile(filePath, maximumBytes, 0o022, "component_rollout_file_unsafe");
}

async function safeFile(filePath, maximumBytes, forbiddenMode, cause) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath || filePath === "/") {
    fail("component_rollout_file_path_invalid", "production_component_rollout_preflight");
  }
  await assertNoSymlinkAncestors(path.dirname(filePath));
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & forbiddenMode) !== 0) {
      fail(cause, "production_component_rollout_preflight");
    }
    return await handle.readFile();
  } finally {
    await handle?.close().catch(() => {});
  }
}

async function assertNoSymlinkAncestors(directory) {
  const parts = directory.split(path.sep).filter(Boolean);
  let current = path.parse(directory).root;
  for (const part of parts) {
    current = path.join(current, part);
    const info = await lstat(current);
    if (info.isSymbolicLink() || !info.isDirectory()) {
      fail("component_rollout_path_ancestor_unsafe", "production_component_rollout_preflight");
    }
  }
}

async function requireAbsent(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "production_component_rollout_preflight");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

function journal({ runId, stage, activeSlot, currentManifest, config, now }) {
  return `${JSON.stringify({
    schemaVersion: 1,
    kind: "hermes-go-production-component-rollout-v1",
    runId,
    stage,
    activeSlot,
    serverVersion: currentManifest.serverVersion,
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: currentManifest.releaseContract.databaseSchemaVersion,
    runtimeContract: RUNTIME_CONTRACT,
    componentManifestSchemaVersion: 2,
    componentReleaseVersion: config.componentRelease.releaseVersion,
    componentManifestSha256: config.componentRelease.manifestSha256,
    updatedAt: now().toISOString(),
  }, null, 2)}\n`;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function sameRelease(left, right) {
  return left?.sourceCommit === right?.sourceCommit
    && left?.serverVersion === right?.serverVersion
    && left?.imageId === right?.imageId
    && left?.containerdImageId === right?.containerdImageId
    && left?.archiveSha256 === right?.archiveSha256
    && JSON.stringify(left?.releaseContract) === JSON.stringify(right?.releaseContract);
}

function productionOrigin(releaseConfig) {
  const port = releaseConfig.nginx.listenPort === 443 ? "" : `:${releaseConfig.nginx.listenPort}`;
  return `https://${releaseConfig.nginx.serverName}${port}`;
}

function authorize(config, options) {
  if (options.confirmation !== `production:${config.host.hostname}`
      || (options.getUid ?? (() => process.getuid?.()))() !== 0
      || (options.platform ?? process.platform) !== "linux"
      || (options.architecture ?? process.arch) !== "x64"
      || (options.hostname ?? systemHostname()) !== config.host.hostname) {
    fail("component_rollout_authorization_failed", "production_component_rollout_authorize");
  }
}

function technical(error) {
  return error?.technicalCause ?? (error instanceof Error ? error.message : String(error));
}

function fail(cause, stage = "production_component_rollout") {
  throw new OpsError("productionComponentRollout", cause, stage);
}
