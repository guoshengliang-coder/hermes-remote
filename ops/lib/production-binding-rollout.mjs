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
  inspectProductionReleaseEnvironment,
  renderBindingRolloutEnvironment,
} from "./production-release-environment.mjs";
import { verifyPreservedEmailSurface } from "./production-release.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";

const RUNTIME_CONTRACT = "hermes-serve-v1";

export async function executeProductionBindingRollout(config, options = {}) {
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
    fail("binding_rollout_release_config_invalid", "production_binding_rollout_preflight");
  }
  if (config.gateway.origin !== productionOrigin(releaseConfig)) {
    fail("binding_rollout_gateway_origin_mismatch", "production_binding_rollout_preflight");
  }
  const currentManifest = await (options.loadCurrentManifest ?? loadCurrentManifest)(releaseConfig);
  const targetManifest = await (options.loadBundleManifest ?? loadBundleManifest)(releaseConfig.targetArtifactManifest);
  if (!sameRelease(currentManifest, targetManifest)
      || currentManifest.releaseContract?.databaseSchemaVersion !== 15
      || !currentManifest.releaseContract?.supportedPostgresqlMajors?.includes(18)) {
    fail("binding_rollout_current_release_mismatch", "production_binding_rollout_preflight");
  }
  const activeSlot = await (options.resolveActiveSlot ?? resolveActiveSlot)(releaseConfig, currentManifest);
  const selected = releaseConfig.slots[activeSlot];
  if (!selected || runner.run("systemctl", ["is-active", "--quiet", `${selected.serviceName}.service`], {
    allowFailure: true,
  }).status !== 0) {
    fail("binding_rollout_active_service_invalid", "production_binding_rollout_preflight");
  }

  let inspected;
  try {
    inspected = await (options.inspectEnvironment ?? inspectProductionReleaseEnvironment)(releaseConfig, activeSlot);
  } catch (error) {
    fail(`binding_rollout_environment_invalid:${technical(error)}`, "production_binding_rollout_preflight");
  }
  if (inspected.mode !== "email_otp") {
    fail("binding_rollout_requires_email_only_state", "production_binding_rollout_preflight");
  }
  const environmentPath = path.join(releaseConfig.paths.configRoot, "slots", activeSlot, "gateway.env");
  const previousEnvironment = await safeManagedFile(environmentPath, 64 * 1024);
  const previousNginxConfig = await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024);
  const previousNginxText = previousNginxConfig.toString("utf8");
  if (!satisfiesProductionNginxContract(releaseConfig, previousNginxText)) {
    fail("binding_rollout_nginx_contract_invalid", "production_binding_rollout_preflight");
  }
  await requireCommittedEmailRollout(releaseConfig);

  const material = {
    appToken: (await safeSecretFile(releaseConfig.secrets.appTokenSource)).toString("utf8").trim(),
    internalStatusToken: (await safeSecretFile(releaseConfig.secrets.internalStatusTokenSource)).toString("utf8").trim(),
  };
  if (material.appToken.length < 8 || material.internalStatusToken.length < 8) {
    fail("binding_rollout_smoke_secret_invalid", "production_binding_rollout_preflight");
  }
  const verifyEmailOnly = options.verifyEmailOnly ?? verifyEmailOnlySurface;
  try {
    await verifyEmailOnly({
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
    });
  } catch (error) {
    fail(`binding_rollout_email_preflight_failed:${technical(error)}`, "production_binding_rollout_preflight");
  }

  let deploymentLock;
  try {
    deploymentLock = await acquireDeploymentLock(
      path.join(releaseConfig.paths.stateRoot, "ops", "deploy.lock"),
      runId,
    );
  } catch (error) {
    fail(`binding_rollout_lock_unavailable:${technical(error)}`, "production_binding_rollout_preflight");
  }
  try {
  const targets = rolloutTargets(releaseConfig);
  await requireAbsent(targets.journal, "binding_rollout_journal_exists");
  await requireAbsent(targets.nginxRoutes, "binding_rollout_nginx_routes_exist");
  await ensureManagedDirectory(path.dirname(targets.journal), 0o700, ownership.host);
  await ensureManagedDirectory(path.dirname(targets.nginxRoutes), 0o755, ownership.host);
  await atomicWrite(targets.journal, journal({ runId, stage: "checkpointed", activeSlot, currentManifest, now }), 0o600, ownership.host);

  let liveMutationStarted = false;
  try {
    liveMutationStarted = true;
    await atomicWrite(targets.nginxRoutes, renderBindingNginxRoutes(), 0o644, ownership.host);
    await atomicWrite(
      releaseConfig.nginx.configFile,
      installBindingNginxInclude(previousNginxText, releaseConfig, targets.nginxRoutes),
      0o644,
      ownership.host,
    );
    runner.run("nginx", ["-t"]);
    runner.run("systemctl", ["reload", "nginx.service"]);
    await atomicWrite(
      environmentPath,
      renderBindingRolloutEnvironment(releaseConfig, activeSlot, inspected),
      0o600,
      ownership.host,
    );
    await atomicWrite(targets.journal, journal({
      runId, stage: "environment_installed", activeSlot, currentManifest, now,
    }), 0o600, ownership.host);
    runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
    await atomicWrite(targets.journal, journal({ runId, stage: "restarted", activeSlot, currentManifest, now }), 0o600, ownership.host);

    const verifyEnabled = options.verifyEnabled ?? verifyBindingSurface;
    await verifyEnabled({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket });
    await sleep(config.deployment.observationSeconds * 1000);
    await verifyEnabled({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket });
    await atomicWrite(targets.journal, journal({ runId, stage: "committed", activeSlot, currentManifest, now }), 0o600, ownership.host);
    return {
      ok: true,
      command: "production-binding-rollout",
      runId,
      activeSlot,
      serverVersion: currentManifest.serverVersion,
      sourceCommit: currentManifest.sourceCommit,
      accountAuthEnabled: true,
      emailOtpEnabled: true,
      bindingEnabled: true,
      multiDeviceEnabled: false,
      desktopManagedInstallEnabled: true,
      runtimeContract: RUNTIME_CONTRACT,
      stage: "committed",
    };
  } catch (error) {
    if (liveMutationStarted) {
      let rollbackError;
      try {
        await atomicWrite(environmentPath, previousEnvironment, 0o600, ownership.host);
        await atomicWrite(releaseConfig.nginx.configFile, previousNginxConfig, 0o644, ownership.host);
        await unlink(targets.nginxRoutes).catch((failure) => {
          if (failure?.code !== "ENOENT") throw failure;
        });
        runner.run("nginx", ["-t"]);
        runner.run("systemctl", ["reload", "nginx.service"]);
        runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
        await verifyEmailOnly({
          config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
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
      if (rollbackError) fail("binding_rollout_rollback_failed", "production_binding_rollout_rollback");
    }
    if (error instanceof OpsError && error.kind === "productionBindingRollout") throw error;
    fail(error instanceof Error ? error.message : error, "production_binding_rollout_execute");
  }
  } finally {
    await releaseDeploymentLock(deploymentLock).catch(() => {});
  }
}

export function renderBindingNginxRoutes() {
  const httpProxy = `client_max_body_size 16k;
    proxy_pass http://hermes_go_gateway_production;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 15s;
    proxy_send_timeout 15s;`;
  return `location = /v2/connector-binding {
    ${httpProxy}
}

location ^~ /v2/connector-binding/ {
    ${httpProxy}
}

location = /v2/connect {
    proxy_pass http://hermes_go_gateway_production;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 75s;
    proxy_send_timeout 15s;
}
`;
}

export function installBindingNginxInclude(content, releaseConfig, routesPath) {
  if (!satisfiesProductionNginxContract(releaseConfig, content)
      || content.includes(routesPath)
      || !/^\/[A-Za-z0-9._/-]+$/.test(routesPath)
      || /^[\t ]*location[\t ]*(?:=|\^~)[\t ]*\/v2\/(?:connect|connector-binding)/m.test(content)) {
    fail("binding_rollout_nginx_contract_invalid", "production_binding_rollout_preflight");
  }
  const matches = [...content.matchAll(/^[\t ]*server_name[\t ]+([A-Za-z0-9.-]+)[\t ]*;[\t ]*$/gm)]
    .filter((match) => match[1] === releaseConfig.nginx.serverName);
  if (matches.length !== 1) fail("binding_rollout_nginx_server_ambiguous", "production_binding_rollout_preflight");
  const match = matches[0];
  return `${content.slice(0, match.index)}${match[0]}\n\n    include ${routesPath};${content.slice(match.index + match[0].length)}`;
}

export async function verifyBindingSurface(request) {
  await verifyCommon(request, true);
  if (!await request.probeWebSocket(request.config.gateway.origin)) {
    fail("binding_rollout_websocket_unavailable", "production_binding_rollout_verify");
  }
}

export async function verifyEmailOnlySurface(request) {
  await verifyCommon(request, false);
  if (await request.probeWebSocket(request.config.gateway.origin)) {
    fail("binding_rollout_websocket_exposed_while_disabled", "production_binding_rollout_verify");
  }
}

async function verifyCommon({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material }, bindingEnabled) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("binding_rollout_service_inactive", "production_binding_rollout_verify");
  }
  const loopback = `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
  const readiness = await fetchJsonRetry(fetchImpl, `${loopback}/readyz`, {}, sleep);
  if (readiness?.status !== "ready" || readiness?.checks?.database !== "ok"
      || readiness?.checks?.migrations !== "ok" || readiness?.checks?.postgresql !== "supported") {
    fail("binding_rollout_readiness_invalid", "production_binding_rollout_verify");
  }
  await verifyPreservedEmailSurfaceRetry(
    { gatewayUrl: config.gateway.origin, publicRoute: true }, fetchImpl, { bindingEnabled }, sleep,
  );
  await verifyPreservedEmailSurfaceRetry(
    { gatewayUrl: loopback, publicRoute: false }, fetchImpl, { bindingEnabled }, sleep,
  );
  const status = await fetchJsonRetry(fetchImpl, `${config.gateway.origin}/api/status`, {
    headers: { "x-hermes-session-token": material.appToken },
  }, sleep);
  if (!(status?.status === "ok" || (status?.overall === "ok" && status?.gateway_running === true))) {
    fail("binding_rollout_legacy_unhealthy", "production_binding_rollout_verify");
  }
  const version = await fetchJsonRetry(fetchImpl, `${loopback}/internal/version`, {
    headers: { authorization: `Bearer ${material.internalStatusToken}` },
  }, sleep);
  if (version?.serverVersion !== currentManifest.serverVersion || version?.sourceCommit !== currentManifest.sourceCommit) {
    fail("binding_rollout_release_identity_mismatch", "production_binding_rollout_verify");
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

async function requireCommittedEmailRollout(releaseConfig) {
  const value = JSON.parse((await safeManagedFile(
    path.join(releaseConfig.paths.stateRoot, "ops", "account-rollout.json"),
    64 * 1024,
  )).toString("utf8"));
  if (value?.kind !== "hermes-go-production-account-rollout-v1" || value?.stage !== "committed"
      || !/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value?.serverVersion ?? "")
      || !/^[0-9a-f]{40}$/.test(value?.sourceCommit ?? "") || value.databaseSchemaVersion !== 15) {
    fail("binding_rollout_email_checkpoint_invalid", "production_binding_rollout_preflight");
  }
}

function rolloutTargets(releaseConfig) {
  return {
    journal: path.join(releaseConfig.paths.stateRoot, "ops", "binding-rollout.json"),
    nginxRoutes: path.join(releaseConfig.paths.configRoot, "account", "binding-routes.conf"),
  };
}

function journal({ runId, stage, activeSlot, currentManifest, now }) {
  return `${JSON.stringify({
    schemaVersion: 1,
    kind: "hermes-go-production-binding-rollout-v1",
    runId,
    stage,
    activeSlot,
    serverVersion: currentManifest.serverVersion,
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: currentManifest.releaseContract.databaseSchemaVersion,
    runtimeContract: RUNTIME_CONTRACT,
    multiDeviceEnabled: false,
    updatedAt: now().toISOString(),
  }, null, 2)}\n`;
}

async function probeWebSocketUpgrade(origin) {
  return await new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };
    const url = new URL("/v2/connect", origin);
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
      finish(response.statusCode === 101);
    });
    request.once("response", (response) => {
      response.resume();
      finish(false);
    });
    request.once("timeout", () => { request.destroy(); finish(false); });
    request.once("error", () => finish(false));
    request.end();
  });
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
  return safeFile(filePath, 8 * 1024, 0o077, "binding_rollout_secret_file_unsafe");
}

async function safeManagedFile(filePath, maximumBytes) {
  return safeFile(filePath, maximumBytes, 0o022, "binding_rollout_file_unsafe");
}

async function safeFile(filePath, maximumBytes, forbiddenMode, cause) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath || filePath === "/") {
    fail("binding_rollout_file_path_invalid", "production_binding_rollout_preflight");
  }
  await assertNoSymlinkAncestors(path.dirname(filePath));
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & forbiddenMode) !== 0) {
      fail(cause, "production_binding_rollout_preflight");
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
      fail("binding_rollout_path_ancestor_unsafe", "production_binding_rollout_preflight");
    }
  }
}

async function requireAbsent(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "production_binding_rollout_preflight");
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
    fail("binding_rollout_authorization_failed", "production_binding_rollout_authorize");
  }
}

function technical(error) {
  if (error instanceof OpsError) return `${error.stage}:${error.technicalCause}`;
  return error instanceof Error ? error.message : String(error);
}

function fail(cause, stage) {
  throw new OpsError("productionBindingRollout", cause, stage);
}
