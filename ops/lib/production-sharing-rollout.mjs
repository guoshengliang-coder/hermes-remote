import { randomBytes, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, unlink } from "node:fs/promises";
import https from "node:https";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { loadBundleManifest } from "./config.mjs";
import { loadCurrentManifest, resolveActiveSlot } from "./deploy-command.mjs";
import { acquireDeploymentLock, releaseDeploymentLock } from "./deploy-state.mjs";
import { satisfiesProductionNginxContract } from "./deploy-switch.mjs";
import { OpsError } from "./errors.mjs";
import { loadManagedBaselineConfig } from "./managed-baseline-config.mjs";
import {
  renderIdentityWebNginxRoutes,
} from "./production-identity-web-rollout.mjs";
import { renderMultiDeviceNginxRoutes } from "./production-multi-device-rollout.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderSharingRolloutEnvironment,
} from "./production-release-environment.mjs";
import { verifyPreservedEmailSurface } from "./production-release.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";

const RUNTIME_CONTRACT = "hermes-serve-v1";

export async function executeProductionSharingRollout(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 120_000 });
  const now = options.now ?? (() => new Date());
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const fetchImpl = options.fetchImpl ?? fetch;
  const probeWebSocket = options.probeWebSocket ?? probeWebSocketUpgrade;
  const ownership = options.ownership ?? { host: { uid: 0, gid: 0 } };
  const runId = options.runId ?? randomUUID();
  authorize(config, options);

  const releaseConfig = await (options.loadReleaseConfig ?? loadManagedBaselineConfig)(config.productionReleaseConfig);
  if (releaseConfig.host.hostname !== config.host.hostname || releaseConfig.host.architecture !== config.host.architecture
      || releaseConfig.gateway.accountAuthEnabled !== false
      || releaseConfig.gateway.accountBindingEnabled !== false
      || releaseConfig.database !== null) {
    fail("sharing_rollout_release_config_invalid", "production_sharing_rollout_preflight");
  }
  if (config.gateway.origin !== productionOrigin(releaseConfig)) {
    fail("sharing_rollout_gateway_origin_mismatch", "production_sharing_rollout_preflight");
  }
  const currentManifest = await (options.loadCurrentManifest ?? loadCurrentManifest)(releaseConfig);
  const targetManifest = await (options.loadBundleManifest ?? loadBundleManifest)(releaseConfig.targetArtifactManifest);
  if (!sameRelease(currentManifest, targetManifest)
      || currentManifest.releaseContract?.databaseSchemaVersion !== 15
      || !currentManifest.releaseContract?.supportedPostgresqlMajors?.includes(18)) {
    fail("sharing_rollout_current_release_mismatch", "production_sharing_rollout_preflight");
  }
  const activeSlot = await (options.resolveActiveSlot ?? resolveActiveSlot)(releaseConfig, currentManifest);
  const selected = releaseConfig.slots[activeSlot];
  if (!selected || runner.run("systemctl", ["is-active", "--quiet", `${selected.serviceName}.service`], {
    allowFailure: true,
  }).status !== 0) {
    fail("sharing_rollout_active_service_invalid", "production_sharing_rollout_preflight");
  }

  let inspected;
  try {
    inspected = await (options.inspectEnvironment ?? inspectProductionReleaseEnvironment)(releaseConfig, activeSlot);
  } catch (error) {
    fail(`sharing_rollout_environment_invalid:${technical(error)}`, "production_sharing_rollout_preflight");
  }
  if (inspected.mode !== "email_identity_web") {
    fail("sharing_rollout_requires_identity_web_state", "production_sharing_rollout_preflight");
  }
  const environmentPath = path.join(releaseConfig.paths.configRoot, "slots", activeSlot, "gateway.env");
  const previousEnvironment = await safeManagedFile(environmentPath, 64 * 1024);
  const nginxConfig = await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024);
  const previousNginxText = nginxConfig.toString("utf8");
  if (!satisfiesProductionNginxContract(releaseConfig, previousNginxText)) {
    fail("sharing_rollout_nginx_contract_invalid", "production_sharing_rollout_preflight");
  }
  const targets = rolloutTargets(releaseConfig);
  requireSingleInclude(previousNginxText, targets.bindingRoutes);
  requireSingleInclude(previousNginxText, targets.identityWebRoutes);
  requireNoInclude(previousNginxText, targets.sharingRoutes);
  await requireAbsent(targets.sharingRoutes, "sharing_rollout_routes_already_exist");
  const previousBindingRoutes = await safeManagedFile(targets.bindingRoutes, 64 * 1024);
  if (!previousBindingRoutes.equals(Buffer.from(renderMultiDeviceNginxRoutes()))) {
    fail("sharing_rollout_multi_device_routes_invalid", "production_sharing_rollout_preflight");
  }
  const previousIdentityWebRoutes = await safeManagedFile(targets.identityWebRoutes, 64 * 1024);
  if (!previousIdentityWebRoutes.equals(Buffer.from(renderIdentityWebNginxRoutes()))) {
    fail("sharing_rollout_identity_web_routes_invalid", "production_sharing_rollout_preflight");
  }
  await requireCommittedMultiDeviceRollout(releaseConfig, currentManifest);
  await requireCommittedIdentityWebRollout(releaseConfig, currentManifest);

  const material = {
    appToken: (await safeSecretFile(releaseConfig.secrets.appTokenSource)).toString("utf8").trim(),
    internalStatusToken: (await safeSecretFile(releaseConfig.secrets.internalStatusTokenSource)).toString("utf8").trim(),
  };
  if (material.appToken.length < 8 || material.internalStatusToken.length < 8) {
    fail("sharing_rollout_smoke_secret_invalid", "production_sharing_rollout_preflight");
  }
  const verifyPrevious = options.verifyPrevious ?? verifyPreviousIdentityWebSurface;
  try {
    await verifyPrevious({
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
      probeDeviceWebSocket: options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus,
    });
  } catch (error) {
    fail(`sharing_rollout_identity_web_preflight_failed:${technical(error)}`, "production_sharing_rollout_preflight");
  }

  let deploymentLock;
  try {
    deploymentLock = await acquireDeploymentLock(
      path.join(releaseConfig.paths.stateRoot, "ops", "deploy.lock"),
      runId,
    );
  } catch (error) {
    fail(`sharing_rollout_lock_unavailable:${technical(error)}`, "production_sharing_rollout_preflight");
  }
  try {
  await requireAbsent(targets.journal, "sharing_rollout_journal_exists");
  await ensureManagedDirectory(path.dirname(targets.journal), 0o700, ownership.host);
  await atomicWrite(targets.journal, journal({ runId, stage: "checkpointed", activeSlot, currentManifest, now }), 0o600, ownership.host);

  let liveMutationStarted = false;
  try {
    liveMutationStarted = true;
    await atomicWrite(targets.sharingRoutes, renderSharingNginxRoutes(), 0o644, ownership.host);
    await atomicWrite(
      releaseConfig.nginx.configFile,
      installInclude(previousNginxText, targets.identityWebRoutes, targets.sharingRoutes),
      0o644,
      ownership.host,
    );
    runner.run("nginx", ["-t"]);
    runner.run("systemctl", ["reload", "nginx.service"]);
    await atomicWrite(
      environmentPath,
      renderSharingRolloutEnvironment(releaseConfig, activeSlot, inspected),
      0o600,
      ownership.host,
    );
    await atomicWrite(targets.journal, journal({
      runId, stage: "environment_installed", activeSlot, currentManifest, now,
    }), 0o600, ownership.host);
    runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
    await atomicWrite(targets.journal, journal({ runId, stage: "restarted", activeSlot, currentManifest, now }), 0o600, ownership.host);

    const verifyEnabled = options.verifyEnabled ?? verifySharingSurface;
    const verification = {
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
      probeDeviceWebSocket: options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus,
    };
    await verifyEnabled(verification);
    await sleep(config.deployment.observationSeconds * 1000);
    await verifyEnabled(verification);
    await atomicWrite(targets.journal, journal({ runId, stage: "committed", activeSlot, currentManifest, now }), 0o600, ownership.host);
    return {
      ok: true,
      command: "production-sharing-rollout",
      runId,
      activeSlot,
      serverVersion: currentManifest.serverVersion,
      sourceCommit: currentManifest.sourceCommit,
      accountAuthEnabled: true,
      emailOtpEnabled: true,
      bindingEnabled: true,
      multiDeviceEnabled: true,
      identityManagementEnabled: true,
      webAccountCenterEnabled: true,
      webSessionEnabled: true,
      sharingEnabled: true,
      desktopManagedInstallEnabled: true,
      runtimeContract: RUNTIME_CONTRACT,
      stage: "committed",
    };
  } catch (error) {
    if (liveMutationStarted) {
      let rollbackError;
      try {
        await atomicWrite(environmentPath, previousEnvironment, 0o600, ownership.host);
        await atomicWrite(releaseConfig.nginx.configFile, previousNginxText, 0o644, ownership.host);
        await unlink(targets.sharingRoutes).catch((removeError) => {
          if (removeError?.code !== "ENOENT") throw removeError;
        });
        runner.run("nginx", ["-t"]);
        runner.run("systemctl", ["reload", "nginx.service"]);
        runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
        await verifyPrevious({
          config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
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
        now,
      }), 0o600, ownership.host).catch(() => {});
      if (rollbackError) fail("sharing_rollout_rollback_failed", "production_sharing_rollout_rollback");
    }
    if (error instanceof OpsError && error.kind === "productionSharingRollout") throw error;
    fail(error instanceof Error ? error.message : error, "production_sharing_rollout_execute");
  }
  } finally {
    await releaseDeploymentLock(deploymentLock).catch(() => {});
  }
}

export function renderSharingNginxRoutes() {
  const httpProxy = `client_max_body_size 16k;
    proxy_pass http://hermes_go_gateway_production;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 15s;
    proxy_send_timeout 15s;`;
  const dynamicPaths = [
    "^/v2/web/devices/[^/]+/shares$",
    "^/v2/web/devices/[^/]+/share-invitations$",
    "^/v2/web/devices/[^/]+/share-invitations/[0-9a-f-]{36}$",
    "^/v2/web/devices/[^/]+/shares/[0-9a-f-]{36}$",
    "^/v2/web/share-invitations/hsi_[A-Za-z0-9_-]{43}/accept$",
    "^/v2/web/devices/[^/]+/leave$",
    "^/v2/devices/[^/]+/shares$",
    "^/v2/devices/[^/]+/share-invitations$",
    "^/v2/devices/[^/]+/share-invitations/[0-9a-f-]{36}$",
    "^/v2/devices/[^/]+/shares/[0-9a-f-]{36}$",
    "^/v2/share-invitations/hsi_[A-Za-z0-9_-]{43}/accept$",
    "^/v2/devices/[^/]+/leave$",
  ];
  const dynamicRoutes = dynamicPaths.map((route) => `location ~ "${route}" {
    ${httpProxy}
}`).join("\n\n");
  return `${dynamicRoutes}\n`;
}

export async function verifySharingSurface(request) {
  await verifyCommon(request, true);
  if (!await request.probeWebSocket(request.config.gateway.origin)) {
    fail("sharing_rollout_websocket_unavailable", "production_sharing_rollout_verify");
  }
  if (await request.probeDeviceWebSocket(request.config.gateway.origin) !== 401) {
    fail("sharing_rollout_device_websocket_guard_invalid", "production_sharing_rollout_verify");
  }
}

export async function verifyPreviousIdentityWebSurface(request) {
  await verifyCommon(request, false);
  if (!await request.probeWebSocket(request.config.gateway.origin)) {
    fail("sharing_rollout_connector_websocket_unavailable", "production_sharing_rollout_verify");
  }
  if (await request.probeDeviceWebSocket(request.config.gateway.origin) !== 401) {
    fail("sharing_rollout_device_websocket_guard_invalid", "production_sharing_rollout_verify");
  }
}

async function verifyCommon({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material }, sharingEnabled) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("sharing_rollout_service_inactive", "production_sharing_rollout_verify");
  }
  const loopback = `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
  const readiness = await fetchJsonRetry(fetchImpl, `${loopback}/readyz`, {}, sleep);
  if (readiness?.status !== "ready" || readiness?.checks?.database !== "ok"
      || readiness?.checks?.migrations !== "ok" || readiness?.checks?.postgresql !== "supported") {
    fail("sharing_rollout_readiness_invalid", "production_sharing_rollout_verify");
  }
  await verifyPreservedEmailSurfaceRetry(
    { gatewayUrl: config.gateway.origin, publicRoute: true }, fetchImpl,
    { bindingEnabled: true, multiDeviceEnabled: true, identityWebEnabled: true, sharingEnabled }, sleep,
  );
  await verifyPreservedEmailSurfaceRetry(
    { gatewayUrl: loopback, publicRoute: false }, fetchImpl,
    { bindingEnabled: true, multiDeviceEnabled: true, identityWebEnabled: true, sharingEnabled }, sleep,
  );
  const devices = await fetchStatusRetry(fetchImpl, `${config.gateway.origin}/v2/devices`, sleep);
  if (devices !== 401) {
    fail("sharing_rollout_device_route_guard_invalid", "production_sharing_rollout_verify");
  }
  await verifySharingRoutes(config.gateway.origin, fetchImpl, sharingEnabled, sleep);
  const status = await fetchJsonRetry(fetchImpl, `${config.gateway.origin}/api/status`, {
    headers: { "x-hermes-session-token": material.appToken },
  }, sleep);
  if (!(status?.status === "ok" || (status?.overall === "ok" && status?.gateway_running === true))) {
    fail("sharing_rollout_legacy_unhealthy", "production_sharing_rollout_verify");
  }
  const version = await fetchJsonRetry(fetchImpl, `${loopback}/internal/version`, {
    headers: { authorization: `Bearer ${material.internalStatusToken}` },
  }, sleep);
  if (version?.serverVersion !== currentManifest.serverVersion || version?.sourceCommit !== currentManifest.sourceCommit) {
    fail("sharing_rollout_release_identity_mismatch", "production_sharing_rollout_verify");
  }
}

async function verifySharingRoutes(origin, fetchImpl, enabled, sleep) {
  const shell = await fetchResponseRetry(fetchImpl, `${origin}/account`, {}, sleep);
  if (shell?.status !== 200) {
    fail("sharing_rollout_account_shell_guard_invalid", "production_sharing_rollout_verify");
  }
  const contentType = shell.headers.get("content-type") ?? "";
  const csp = shell.headers.get("content-security-policy") ?? "";
  if (!contentType.startsWith("text/html")
      || !csp.includes("default-src 'none'")
      || !csp.includes("script-src 'self'")
      || !csp.includes("frame-ancestors 'none'")
      || shell.headers.get("cache-control") !== "no-store") {
    fail("sharing_rollout_account_shell_security_invalid", "production_sharing_rollout_verify");
  }
  for (const guarded of ["/v2/web/identities", "/v2/web/installations", "/v2/account/identities", "/v2/installations"]) {
    if (await fetchStatusRetry(fetchImpl, `${origin}${guarded}`, sleep) !== 401) {
      fail("sharing_rollout_identity_route_guard_invalid", "production_sharing_rollout_verify");
    }
  }
  for (const route of [
    "/v2/web/devices/probe-device/shares",
    "/v2/devices/probe-device/shares",
  ]) {
    if (await fetchStatusRetry(fetchImpl, `${origin}${route}`, sleep) !== (enabled ? 401 : 404)) {
      fail("sharing_rollout_route_guard_invalid", "production_sharing_rollout_verify");
    }
  }
  if (enabled) {
    const rejectedMutation = await fetchResponseRetry(
      fetchImpl,
      `${origin}/v2/web/devices/probe-device/share-invitations`,
      { method: "POST", headers: { "content-type": "application/json" }, body: "{}" },
      sleep,
    );
    if (rejectedMutation?.status !== 403) {
      fail("sharing_rollout_web_mutation_guard_invalid", "production_sharing_rollout_verify");
    }
  }
  for (const absent of [
    "/v2/web/auth/google/exchange",
    "/v2/web/account",
  ]) {
    const method = absent === "/v2/web/account" ? "DELETE" : "POST";
    if (await fetchStatusRetry(fetchImpl, `${origin}${absent}`, sleep, { method }) !== 404) {
      fail("sharing_rollout_forbidden_route_exposed", "production_sharing_rollout_verify");
    }
  }
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

async function requireCommittedMultiDeviceRollout(releaseConfig, currentManifest) {
  const value = JSON.parse((await safeManagedFile(
    path.join(releaseConfig.paths.stateRoot, "ops", "multi-device-rollout.json"),
    64 * 1024,
  )).toString("utf8"));
  if (value?.kind !== "hermes-go-production-multi-device-rollout-v1" || value?.stage !== "committed"
      || !/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value?.serverVersion ?? "")
      || !/^[0-9a-f]{40}$/.test(value?.sourceCommit ?? "") || value.databaseSchemaVersion !== 15
      || value.runtimeContract !== RUNTIME_CONTRACT || value.multiDeviceEnabled !== true
      || value.serverVersion !== currentManifest.serverVersion
      || value.sourceCommit !== currentManifest.sourceCommit) {
    fail("sharing_rollout_multi_device_checkpoint_invalid", "production_sharing_rollout_preflight");
  }
}

async function requireCommittedIdentityWebRollout(releaseConfig, currentManifest) {
  const value = JSON.parse((await safeManagedFile(
    path.join(releaseConfig.paths.stateRoot, "ops", "identity-web-rollout.json"),
    64 * 1024,
  )).toString("utf8"));
  if (value?.kind !== "hermes-go-production-identity-web-rollout-v1" || value?.stage !== "committed"
      || !/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value?.serverVersion ?? "")
      || !/^[0-9a-f]{40}$/.test(value?.sourceCommit ?? "") || value.databaseSchemaVersion !== 15
      || value.runtimeContract !== RUNTIME_CONTRACT || value.identityWebEnabled !== true
      || value.sharingEnabled !== false
      || value.serverVersion !== currentManifest.serverVersion
      || value.sourceCommit !== currentManifest.sourceCommit) {
    fail("sharing_rollout_identity_web_checkpoint_invalid", "production_sharing_rollout_preflight");
  }
}

function rolloutTargets(releaseConfig) {
  return {
    journal: path.join(releaseConfig.paths.stateRoot, "ops", "sharing-rollout.json"),
    bindingRoutes: path.join(releaseConfig.paths.configRoot, "account", "binding-routes.conf"),
    identityWebRoutes: path.join(releaseConfig.paths.configRoot, "account", "identity-web-routes.conf"),
    sharingRoutes: path.join(releaseConfig.paths.configRoot, "account", "sharing-routes.conf"),
  };
}

function requireSingleInclude(content, routesPath) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("sharing_rollout_nginx_routes_path_invalid", "production_sharing_rollout_preflight");
  }
  const directive = `include ${routesPath};`;
  const matches = content.split("\n").filter((line) => line.trim() === directive);
  if (matches.length !== 1) {
    fail("sharing_rollout_binding_include_invalid", "production_sharing_rollout_preflight");
  }
}

function requireNoInclude(content, routesPath) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("sharing_rollout_nginx_routes_path_invalid", "production_sharing_rollout_preflight");
  }
  const directive = `include ${routesPath};`;
  if (content.split("\n").some((line) => line.trim() === directive)) {
    fail("sharing_rollout_include_already_exists", "production_sharing_rollout_preflight");
  }
}

function installInclude(content, identityWebRoutes, sharingRoutes) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(sharingRoutes)) {
    fail("sharing_rollout_nginx_routes_path_invalid", "production_sharing_rollout_preflight");
  }
  const identityWebDirective = `include ${identityWebRoutes};`;
  const sharingDirective = `include ${sharingRoutes};`;
  if (content.split("\n").some((line) => line.trim() === sharingDirective)) {
    fail("sharing_rollout_include_already_exists", "production_sharing_rollout_preflight");
  }
  const installed = content.replace(identityWebDirective, `${identityWebDirective}\n    ${sharingDirective}`);
  if (installed === content) {
    fail("sharing_rollout_identity_web_include_invalid", "production_sharing_rollout_preflight");
  }
  return installed;
}

function journal({ runId, stage, activeSlot, currentManifest, now }) {
  return `${JSON.stringify({
    schemaVersion: 1,
    kind: "hermes-go-production-sharing-rollout-v1",
    runId,
    stage,
    activeSlot,
    serverVersion: currentManifest.serverVersion,
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: currentManifest.releaseContract.databaseSchemaVersion,
    runtimeContract: RUNTIME_CONTRACT,
    sharingEnabled: true,
    updatedAt: now().toISOString(),
  }, null, 2)}\n`;
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
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    const url = new URL(pathname, origin);
    const request = https.request(url, {
      method: "GET",
      headers: {
        Connection: "Upgrade",
        Upgrade: "websocket",
        "Sec-WebSocket-Key": randomBytes(16).toString("base64"),
        "Sec-WebSocket-Version": "13",
      },
      timeout: 5_000,
    });
    request.once("upgrade", (response, socket) => {
      socket.destroy();
      finish(response.statusCode ?? 0);
    });
    request.once("response", (response) => {
      const status = response.statusCode ?? 0;
      response.resume();
      finish(status);
    });
    request.once("timeout", () => { request.destroy(); finish(0); });
    request.once("error", () => finish(0));
    request.end();
  });
}

async function fetchStatusRetry(fetchImpl, url, sleep, init = {}) {
  let status = 0;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      status = (await fetchImpl(url, { ...init, signal: AbortSignal.timeout(3_000) })).status;
      if (status !== 502 && status !== 503) return status;
    } catch {
      status = 0;
    }
    if (attempt < 19) await sleep(250);
  }
  return status;
}

async function fetchResponseRetry(fetchImpl, url, init, sleep) {
  let response = null;
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      response = await fetchImpl(url, { ...init, signal: AbortSignal.timeout(3_000) });
      if (response.status !== 502 && response.status !== 503) return response;
    } catch {
      response = null;
    }
    if (attempt < 19) await sleep(250);
  }
  return response;
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

async function safeSecretFile(filePath) {
  return safeFile(filePath, 8 * 1024, 0o077, "sharing_rollout_secret_file_unsafe");
}

async function safeManagedFile(filePath, maximumBytes) {
  return safeFile(filePath, maximumBytes, 0o022, "sharing_rollout_file_unsafe");
}

async function safeFile(filePath, maximumBytes, forbiddenMode, cause) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath || filePath === "/") {
    fail("sharing_rollout_file_path_invalid", "production_sharing_rollout_preflight");
  }
  await assertNoSymlinkAncestors(path.dirname(filePath));
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & forbiddenMode) !== 0) {
      fail(cause, "production_sharing_rollout_preflight");
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
      fail("sharing_rollout_path_ancestor_unsafe", "production_sharing_rollout_preflight");
    }
  }
}

async function requireAbsent(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "production_sharing_rollout_preflight");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
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
    fail("sharing_rollout_authorization_failed", "production_sharing_rollout_authorize");
  }
}

function technical(error) {
  if (error instanceof OpsError) return `${error.stage}:${error.technicalCause}`;
  return error instanceof Error ? error.message : String(error);
}

function fail(cause, stage) {
  throw new OpsError("productionSharingRollout", cause, stage);
}
