import { randomBytes, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir, readlink, realpath, unlink } from "node:fs/promises";
import https from "node:https";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { loadBundleManifest } from "./config.mjs";
import { loadCurrentManifest, resolveActiveSlot } from "./deploy-command.mjs";
import { acquireDeploymentLock, releaseDeploymentLock } from "./deploy-state.mjs";
import { satisfiesProductionNginxContract } from "./deploy-switch.mjs";
import { deployWebAppRoot } from "./deploy-system.mjs";
import { OpsError } from "./errors.mjs";
import { loadManagedBaselineConfig } from "./managed-baseline-config.mjs";
import { verifyPreservedLegacyStatus } from "./production-legacy-status.mjs";
import { renderIdentityWebNginxRoutes } from "./production-identity-web-rollout.mjs";
import { renderMultiDeviceNginxRoutes } from "./production-multi-device-rollout.mjs";
import {
  inspectProductionReleaseEnvironment,
  productionWebAppDir,
  renderWebAppRolloutEnvironment,
} from "./production-release-environment.mjs";
import { verifyPreservedEmailSurface } from "./production-release.mjs";
import { renderSharingNginxRoutes } from "./production-sharing-rollout.mjs";
import { compareVersions } from "./release-transition.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";

const RUNTIME_CONTRACT = "hermes-serve-v1";
/** The first Gateway release that contains the Web app code and the Web app mount. */
export const MINIMUM_WEB_APP_SERVER_VERSION = "0.4.17";

/**
 * R5-F7: turns on the Web app (/app/) on the active slot of a Gateway that already carries it.
 * Mirrors the sharing rollout (nginx include + environment + restart, verified twice, restored
 * byte for byte on failure) with component-style release pinning: the running release must be at
 * least MINIMUM_WEB_APP_SERVER_VERSION and match the operator bundle, but earlier rollout journals
 * are not required to name the current release, since routine releases have happened since.
 */
export async function executeProductionWebAppRollout(config, options = {}) {
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
    fail("web_app_rollout_release_config_invalid", "production_web_app_rollout_preflight");
  }
  if (config.gateway.origin !== productionOrigin(releaseConfig)) {
    fail("web_app_rollout_gateway_origin_mismatch", "production_web_app_rollout_preflight");
  }
  const currentManifest = await (options.loadCurrentManifest ?? loadCurrentManifest)(releaseConfig);
  const targetManifest = await (options.loadBundleManifest ?? loadBundleManifest)(releaseConfig.targetArtifactManifest);
  if (!sameRelease(currentManifest, targetManifest)
      || currentManifest.releaseContract?.databaseSchemaVersion !== 15
      || !currentManifest.releaseContract?.supportedPostgresqlMajors?.includes(18)) {
    fail("web_app_rollout_current_release_mismatch", "production_web_app_rollout_preflight");
  }
  if (!/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(currentManifest.serverVersion ?? "")
      || compareVersions(currentManifest.serverVersion, MINIMUM_WEB_APP_SERVER_VERSION) < 0) {
    fail("web_app_rollout_gateway_release_too_old", "production_web_app_rollout_preflight");
  }
  const activeSlot = await (options.resolveActiveSlot ?? resolveActiveSlot)(releaseConfig, currentManifest);
  const selected = releaseConfig.slots[activeSlot];
  if (!selected || runner.run("systemctl", ["is-active", "--quiet", `${selected.serviceName}.service`], {
    allowFailure: true,
  }).status !== 0) {
    fail("web_app_rollout_active_service_invalid", "production_web_app_rollout_preflight");
  }
  await requireWebAppMount(releaseConfig, selected);
  const publishedRelease = await requirePublishedWebApp(releaseConfig);

  let inspected;
  try {
    inspected = await (options.inspectEnvironment ?? inspectProductionReleaseEnvironment)(releaseConfig, activeSlot);
  } catch (error) {
    fail(`web_app_rollout_environment_invalid:${technical(error)}`, "production_web_app_rollout_preflight");
  }
  if (inspected.mode !== "email_sharing_components") {
    fail("web_app_rollout_requires_components_state", "production_web_app_rollout_preflight");
  }
  const environmentPath = path.join(releaseConfig.paths.configRoot, "slots", activeSlot, "gateway.env");
  const previousEnvironment = await safeManagedFile(environmentPath, 64 * 1024);
  const nginxConfig = await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024);
  const previousNginxText = nginxConfig.toString("utf8");
  if (!satisfiesProductionNginxContract(releaseConfig, previousNginxText)) {
    fail("web_app_rollout_nginx_contract_invalid", "production_web_app_rollout_preflight");
  }
  const targets = rolloutTargets(releaseConfig);
  for (const [routes, render] of [
    [targets.bindingRoutes, renderMultiDeviceNginxRoutes],
    [targets.identityWebRoutes, renderIdentityWebNginxRoutes],
    [targets.sharingRoutes, renderSharingNginxRoutes],
  ]) {
    requireSingleInclude(previousNginxText, routes);
    if (!(await safeManagedFile(routes, 64 * 1024)).equals(Buffer.from(render()))) {
      fail("web_app_rollout_previous_routes_invalid", "production_web_app_rollout_preflight");
    }
  }
  requireNoInclude(previousNginxText, targets.webAppRoutes);
  await requireAbsent(targets.webAppRoutes, "web_app_rollout_routes_already_exist");

  const material = {
    appToken: (await safeSecretFile(releaseConfig.secrets.appTokenSource)).toString("utf8").trim(),
    internalStatusToken: (await safeSecretFile(releaseConfig.secrets.internalStatusTokenSource)).toString("utf8").trim(),
  };
  if (material.appToken.length < 8 || material.internalStatusToken.length < 8) {
    fail("web_app_rollout_smoke_secret_invalid", "production_web_app_rollout_preflight");
  }
  const verifyPrevious = options.verifyPrevious ?? verifyPreviousComponentSurface;
  const probeDeviceWebSocket = options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus;
  let legacyState;
  try {
    legacyState = await verifyPrevious({
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
      probeDeviceWebSocket,
    });
  } catch (error) {
    fail(`web_app_rollout_components_preflight_failed:${technical(error)}`, "production_web_app_rollout_preflight");
  }

  let deploymentLock;
  try {
    deploymentLock = await acquireDeploymentLock(
      path.join(releaseConfig.paths.stateRoot, "ops", "deploy.lock"),
      runId,
    );
  } catch (error) {
    fail(`web_app_rollout_lock_unavailable:${technical(error)}`, "production_web_app_rollout_preflight");
  }
  try {
  // Everything admitted above was read before the lock; a concurrent operator (another rollout, or
  // a Web publish/rollback) must not have changed it since.
  if (!(await safeManagedFile(environmentPath, 64 * 1024)).equals(previousEnvironment)
      || (await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024)).toString("utf8") !== previousNginxText
      || await requirePublishedWebApp(releaseConfig) !== publishedRelease) {
    fail("web_app_rollout_state_changed_before_lock", "production_web_app_rollout_preflight");
  }
  await requireAbsent(targets.journal, "web_app_rollout_journal_exists");
  await ensureManagedDirectory(path.dirname(targets.journal), 0o700, ownership.host);
  await atomicWrite(targets.journal, journal({ runId, stage: "checkpointed", activeSlot, currentManifest, now }), 0o600, ownership.host);

  let liveMutationStarted = false;
  try {
    liveMutationStarted = true;
    await atomicWrite(targets.webAppRoutes, renderWebAppNginxRoutes(), 0o644, ownership.host);
    await atomicWrite(
      releaseConfig.nginx.configFile,
      installInclude(previousNginxText, targets.sharingRoutes, targets.webAppRoutes),
      0o644,
      ownership.host,
    );
    runner.run("nginx", ["-t"]);
    runner.run("systemctl", ["reload", "nginx.service"]);
    await atomicWrite(
      environmentPath,
      renderWebAppRolloutEnvironment(releaseConfig, activeSlot, inspected),
      0o600,
      ownership.host,
    );
    await atomicWrite(targets.journal, journal({
      runId, stage: "environment_installed", activeSlot, currentManifest, now,
    }), 0o600, ownership.host);
    runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
    await atomicWrite(targets.journal, journal({ runId, stage: "restarted", activeSlot, currentManifest, now }), 0o600, ownership.host);

    const verifyEnabled = options.verifyEnabled ?? verifyWebAppSurface;
    const verification = {
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
      expectedLegacyState: legacyState,
      probeDeviceWebSocket,
    };
    await verifyEnabled(verification);
    await sleep(config.deployment.observationSeconds * 1000);
    await verifyEnabled(verification);
    await atomicWrite(targets.journal, journal({ runId, stage: "committed", activeSlot, currentManifest, now }), 0o600, ownership.host);
    return {
      ok: true,
      command: "production-web-app-rollout",
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
      desktopComponentInstallEnabled: true,
      webDeviceAccessEnabled: true,
      webAppEnabled: true,
      webAppDir: productionWebAppDir(releaseConfig),
      runtimeContract: RUNTIME_CONTRACT,
      stage: "committed",
    };
  } catch (error) {
    if (liveMutationStarted) {
      let rollbackError;
      try {
        await atomicWrite(environmentPath, previousEnvironment, 0o600, ownership.host);
        await atomicWrite(releaseConfig.nginx.configFile, previousNginxText, 0o644, ownership.host);
        await unlink(targets.webAppRoutes).catch((removeError) => {
          if (removeError?.code !== "ENOENT") throw removeError;
        });
        runner.run("nginx", ["-t"]);
        runner.run("systemctl", ["reload", "nginx.service"]);
        runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
        await verifyPrevious({
          config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
          expectedLegacyState: legacyState,
          probeDeviceWebSocket,
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
      if (rollbackError) fail("web_app_rollout_rollback_failed", "production_web_app_rollout_rollback");
    }
    if (error instanceof OpsError && error.kind === "productionWebAppRollout") throw error;
    fail(error instanceof Error ? error.message : error, "production_web_app_rollout_execute");
  }
  } finally {
    await releaseDeploymentLock(deploymentLock).catch(() => {});
  }
}

/**
 * /app and /app/ are the only edge change: every API the Web app calls (Web session, devices,
 * device API/WebSocket, /api/*) was already forwarded by earlier rollouts. The Gateway serves /app/
 * read-only (GET/HEAD), so requests carry no body; `^~` keeps the regex locations from claiming it.
 */
export function renderWebAppNginxRoutes() {
  const proxy = (readTimeout) => `client_max_body_size 1k;
    proxy_pass http://hermes_go_gateway_production;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout ${readTimeout};
    proxy_send_timeout 15s;`;
  return `location = /app {
    ${proxy("15s")}
}

location ^~ /app/ {
    ${proxy("30s")}
}
`;
}

export async function verifyWebAppSurface(request) {
  const legacyState = await verifyCommon(request, true);
  const origin = request.config.gateway.origin;
  const redirect = await fetchResponseRetry(request.fetchImpl, `${origin}/app`, { redirect: "manual" }, request.sleep);
  if (redirect?.status !== 308 || redirect.headers.get("location") !== "/app/") {
    fail("web_app_rollout_app_redirect_invalid", "production_web_app_rollout_verify");
  }
  const shell = await fetchResponseRetry(request.fetchImpl, `${origin}/app/`, {}, request.sleep);
  const csp = shell?.headers.get("content-security-policy") ?? "";
  // headers.get() joins repeated headers with ", ": a second CSP (an edge add_header) or an appended
  // Cache-Control would otherwise pass as a substring match.
  if (shell?.status !== 200
      || csp.includes(",")
      || !(shell.headers.get("content-type") ?? "").startsWith("text/html")
      || shell.headers.get("cache-control") !== "no-store"
      || !csp.includes("default-src 'none'")
      || !csp.includes("script-src 'self'")
      || !csp.includes("worker-src 'self'")
      || !csp.includes("frame-ancestors 'none'")
      || /unsafe-inline|unsafe-eval/.test(csp)) {
    fail("web_app_rollout_app_shell_invalid", "production_web_app_rollout_verify");
  }
  const worker = await fetchResponseRetry(request.fetchImpl, `${origin}/app/sw.js`, {}, request.sleep);
  if (worker?.status !== 200 || worker.headers.get("service-worker-allowed") !== "/app/") {
    fail("web_app_rollout_service_worker_invalid", "production_web_app_rollout_verify");
  }
  // A cookie-less browser request to the device API is refused by the Gateway, not the edge.
  if (await fetchStatusRetry(request.fetchImpl, `${origin}/v2/devices/probe-device/api/sessions`, request.sleep) !== 401) {
    fail("web_app_rollout_device_api_guard_invalid", "production_web_app_rollout_verify");
  }
  await verifyWebSockets(request);
  return legacyState;
}

export async function verifyPreviousComponentSurface(request) {
  const legacyState = await verifyCommon(request, false);
  // Before enablement /app/ is not the Gateway's: the edge still sends it to the release server.
  const shell = await fetchResponseRetry(request.fetchImpl, `${request.config.gateway.origin}/app/`, {}, request.sleep);
  if (shell?.status === 200 && (shell.headers.get("content-type") ?? "").startsWith("text/html")) {
    fail("web_app_rollout_app_exposed_before_enablement", "production_web_app_rollout_verify");
  }
  await verifyWebSockets(request);
  return legacyState;
}

async function verifyWebSockets(request) {
  if (!await request.probeWebSocket(request.config.gateway.origin)) {
    fail("web_app_rollout_connector_websocket_unavailable", "production_web_app_rollout_verify");
  }
  if (await request.probeDeviceWebSocket(request.config.gateway.origin) !== 401) {
    fail("web_app_rollout_device_websocket_guard_invalid", "production_web_app_rollout_verify");
  }
}

async function verifyCommon({
  config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, expectedLegacyState = null,
}, webDeviceAccessEnabled) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("web_app_rollout_service_inactive", "production_web_app_rollout_verify");
  }
  const loopback = `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
  const readiness = await fetchJsonRetry(fetchImpl, `${loopback}/readyz`, {}, sleep);
  if (readiness?.status !== "ready" || readiness?.checks?.database !== "ok"
      || readiness?.checks?.migrations !== "ok" || readiness?.checks?.postgresql !== "supported") {
    fail("web_app_rollout_readiness_invalid", "production_web_app_rollout_verify");
  }
  const surface = {
    bindingEnabled: true,
    multiDeviceEnabled: true,
    identityWebEnabled: true,
    sharingEnabled: true,
    componentInstallEnabled: true,
    webDeviceAccessEnabled,
  };
  await verifyPreservedEmailSurfaceRetry({ gatewayUrl: config.gateway.origin, publicRoute: true }, fetchImpl, surface, sleep);
  await verifyPreservedEmailSurfaceRetry({ gatewayUrl: loopback, publicRoute: false }, fetchImpl, surface, sleep);
  if (await fetchStatusRetry(fetchImpl, `${config.gateway.origin}/v2/devices`, sleep) !== 401) {
    fail("web_app_rollout_device_route_guard_invalid", "production_web_app_rollout_verify");
  }
  for (const route of ["/v2/web/devices/probe-device/shares", "/v2/devices/probe-device/shares"]) {
    if (await fetchStatusRetry(fetchImpl, `${config.gateway.origin}${route}`, sleep) !== 401) {
      fail("web_app_rollout_sharing_route_guard_invalid", "production_web_app_rollout_verify");
    }
  }
  for (const [absent, method] of [["/v2/web/auth/google/exchange", "POST"], ["/v2/web/account", "DELETE"]]) {
    const status = await fetchStatusRetry(fetchImpl, `${config.gateway.origin}${absent}`, sleep, { method });
    if (status !== 404 && status !== 405) {
      fail("web_app_rollout_forbidden_route_exposed", "production_web_app_rollout_verify");
    }
  }
  const legacyState = await verifyPreservedLegacyStatus({
    fetchImpl,
    url: `${config.gateway.origin}/api/status`,
    appToken: material.appToken,
    sleep,
    expectedState: expectedLegacyState,
  });
  if (legacyState === null) {
    fail("web_app_rollout_legacy_unhealthy", "production_web_app_rollout_verify");
  }
  const version = await fetchJsonRetry(fetchImpl, `${loopback}/internal/version`, {
    headers: { authorization: `Bearer ${material.internalStatusToken}` },
  }, sleep);
  if (version?.serverVersion !== currentManifest.serverVersion || version?.sourceCommit !== currentManifest.sourceCommit) {
    fail("web_app_rollout_release_identity_mismatch", "production_web_app_rollout_verify");
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

/** The active slot's unit must be one written by a release that mounts the Web app root. */
async function requireWebAppMount(releaseConfig, selected) {
  const unit = (await safeManagedFile(
    path.join(releaseConfig.paths.systemdUnitDirectory, `${selected.serviceName}.service`),
    64 * 1024,
  )).toString("utf8");
  const root = deployWebAppRoot(releaseConfig);
  if (!unit.includes(`--mount type=bind,src=${root},dst=${root},readonly`)) {
    fail("web_app_rollout_unit_mount_missing", "production_web_app_rollout_preflight");
  }
}

/**
 * The Web app is published before it is enabled: `current` must be a link to a release directory
 * under web/releases/ holding an index.html (scripts/publish-web-app.sh).
 */
async function requirePublishedWebApp(releaseConfig) {
  const root = deployWebAppRoot(releaseConfig);
  const current = path.join(root, "current");
  try {
    await assertNoSymlinkAncestors(root);
    const link = await lstat(current);
    if (!link.isSymbolicLink() || path.isAbsolute(await readlink(current))) throw new Error("current_not_relative_link");
    const releasesRoot = await realpath(path.join(root, "releases"));
    const target = await realpath(current);
    if (path.dirname(target) !== releasesRoot) throw new Error("current_outside_releases");
    const index = await lstat(path.join(target, "index.html"));
    if (!index.isFile() || index.size < 1) throw new Error("index_missing");
    // The Gateway reads the release as uid 1000 through the mount: world-readable files, world-
    // searchable directories, and nothing but those two kinds.
    await requireReadableTree(target);
    return path.basename(target);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(`web_app_rollout_web_app_not_published:${technical(error)}`, "production_web_app_rollout_preflight");
  }
}

async function requireReadableTree(directory, budget = { entries: 0 }) {
  const info = await lstat(directory);
  if (!info.isDirectory() || (info.mode & 0o005) !== 0o005) throw new Error("release_directory_not_readable");
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (++budget.entries > 5000) throw new Error("release_too_large");
    const child = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await requireReadableTree(child, budget);
    } else {
      const file = await lstat(child);
      if (!file.isFile() || (file.mode & 0o004) === 0) throw new Error("release_file_not_readable");
    }
  }
}

function rolloutTargets(releaseConfig) {
  return {
    journal: path.join(releaseConfig.paths.stateRoot, "ops", "web-app-rollout.json"),
    bindingRoutes: path.join(releaseConfig.paths.configRoot, "account", "binding-routes.conf"),
    identityWebRoutes: path.join(releaseConfig.paths.configRoot, "account", "identity-web-routes.conf"),
    sharingRoutes: path.join(releaseConfig.paths.configRoot, "account", "sharing-routes.conf"),
    webAppRoutes: path.join(releaseConfig.paths.configRoot, "account", "web-app-routes.conf"),
  };
}

function requireSingleInclude(content, routesPath) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("web_app_rollout_nginx_routes_path_invalid", "production_web_app_rollout_preflight");
  }
  const directive = `include ${routesPath};`;
  if (content.split("\n").filter((line) => line.trim() === directive).length !== 1) {
    fail("web_app_rollout_previous_include_invalid", "production_web_app_rollout_preflight");
  }
}

function requireNoInclude(content, routesPath) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("web_app_rollout_nginx_routes_path_invalid", "production_web_app_rollout_preflight");
  }
  const directive = `include ${routesPath};`;
  if (content.split("\n").some((line) => line.trim() === directive)) {
    fail("web_app_rollout_include_already_exists", "production_web_app_rollout_preflight");
  }
}

function installInclude(content, sharingRoutes, webAppRoutes) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(webAppRoutes)) {
    fail("web_app_rollout_nginx_routes_path_invalid", "production_web_app_rollout_preflight");
  }
  const sharingDirective = `include ${sharingRoutes};`;
  const webAppDirective = `include ${webAppRoutes};`;
  if (content.split("\n").some((line) => line.trim() === webAppDirective)) {
    fail("web_app_rollout_include_already_exists", "production_web_app_rollout_preflight");
  }
  // By whole line, not substring, so a commented-out copy of the directive can never be the anchor.
  const lines = content.split("\n");
  const index = lines.findIndex((line) => line.trim() === sharingDirective);
  if (index < 0) fail("web_app_rollout_sharing_include_invalid", "production_web_app_rollout_preflight");
  const indent = /^\s*/.exec(lines[index])[0];
  lines.splice(index + 1, 0, `${indent}${webAppDirective}`);
  return lines.join("\n");
}

function journal({ runId, stage, activeSlot, currentManifest, now }) {
  return `${JSON.stringify({
    schemaVersion: 1,
    kind: "hermes-go-production-web-app-rollout-v1",
    runId,
    stage,
    activeSlot,
    serverVersion: currentManifest.serverVersion,
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: currentManifest.releaseContract.databaseSchemaVersion,
    runtimeContract: RUNTIME_CONTRACT,
    webAppEnabled: true,
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
  return safeFile(filePath, 8 * 1024, 0o077, "web_app_rollout_secret_file_unsafe");
}

async function safeManagedFile(filePath, maximumBytes) {
  return safeFile(filePath, maximumBytes, 0o022, "web_app_rollout_file_unsafe");
}

async function safeFile(filePath, maximumBytes, forbiddenMode, cause) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath || filePath === "/") {
    fail("web_app_rollout_file_path_invalid", "production_web_app_rollout_preflight");
  }
  await assertNoSymlinkAncestors(path.dirname(filePath));
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & forbiddenMode) !== 0) {
      fail(cause, "production_web_app_rollout_preflight");
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
      fail("web_app_rollout_path_ancestor_unsafe", "production_web_app_rollout_preflight");
    }
  }
}

async function requireAbsent(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "production_web_app_rollout_preflight");
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
    fail("web_app_rollout_authorization_failed", "production_web_app_rollout_authorize");
  }
}

function technical(error) {
  if (error instanceof OpsError) return `${error.stage}:${error.technicalCause}`;
  return error instanceof Error ? error.message : String(error);
}

function fail(cause, stage) {
  throw new OpsError("productionWebAppRollout", cause, stage);
}
