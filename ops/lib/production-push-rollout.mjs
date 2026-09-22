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
import { verifyPreservedLegacyStatus } from "./production-legacy-status.mjs";
import { renderIdentityWebNginxRoutes } from "./production-identity-web-rollout.mjs";
import { renderMultiDeviceNginxRoutes } from "./production-multi-device-rollout.mjs";
import { FIREBASE_PROJECT_ID_PATTERN } from "./production-push-rollout-config.mjs";
import {
  FCM_SERVICE_ACCOUNT_FILE,
  inspectProductionReleaseEnvironment,
  productionWebAppDir,
  renderPushRolloutEnvironment,
} from "./production-release-environment.mjs";
import { verifyPreservedEmailSurface } from "./production-release.mjs";
import { renderSharingNginxRoutes } from "./production-sharing-rollout.mjs";
import { renderWebAppNginxRoutes } from "./production-web-app-rollout.mjs";
import { compareVersions } from "./release-transition.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";

const RUNTIME_CONTRACT = "hermes-serve-v1";
/** The first Gateway release that carries FCM push (ACCOUNT_PUSH_ENABLED) and schema 16. */
export const MINIMUM_PUSH_SERVER_VERSION = "0.4.18";
const PUSH_DATABASE_SCHEMA_VERSION = 16;
const PUSH_REGISTRATION_ROUTE = "/v2/installations/current/push-registration";
const MAX_SERVICE_ACCOUNT_BYTES = 16 * 1024;

/**
 * R5-F9: turns on FCM push on the active slot of a Gateway that already carries it (0.4.18+,
 * schema 16) and already runs the Web app state. Mirrors R5-F7: one Nginx include (the push
 * registration route), the service-account key, the environment and a restart, verified twice and
 * restored byte for byte on failure. The key is copied, never logged: every failure cause here is a
 * generic code, and the journal carries only the Firebase project id.
 */
export async function executeProductionPushRollout(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 120_000 });
  const now = options.now ?? (() => new Date());
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const fetchImpl = options.fetchImpl ?? fetch;
  const probeWebSocket = options.probeWebSocket ?? probeWebSocketUpgrade;
  const ownership = options.ownership ?? { host: { uid: 0, gid: 0 }, secret: { uid: 0, gid: 1000 } };
  // The key source must be owned by root; tests running unprivileged name their own uid.
  const secretSourceUid = options.secretSourceUid ?? 0;
  const runId = options.runId ?? randomUUID();
  authorize(config, options);

  const releaseConfig = await (options.loadReleaseConfig ?? loadManagedBaselineConfig)(config.productionReleaseConfig);
  if (releaseConfig.host.hostname !== config.host.hostname || releaseConfig.host.architecture !== config.host.architecture
      || releaseConfig.gateway.accountAuthEnabled !== false
      || releaseConfig.gateway.accountBindingEnabled !== false
      || releaseConfig.database !== null) {
    fail("push_rollout_release_config_invalid", "production_push_rollout_preflight");
  }
  if (config.gateway.origin !== productionOrigin(releaseConfig)) {
    fail("push_rollout_gateway_origin_mismatch", "production_push_rollout_preflight");
  }
  const currentManifest = await (options.loadCurrentManifest ?? loadCurrentManifest)(releaseConfig);
  const targetManifest = await (options.loadBundleManifest ?? loadBundleManifest)(releaseConfig.targetArtifactManifest);
  if (!sameRelease(currentManifest, targetManifest)
      || currentManifest.releaseContract?.databaseSchemaVersion !== PUSH_DATABASE_SCHEMA_VERSION
      || !currentManifest.releaseContract?.supportedPostgresqlMajors?.includes(18)) {
    fail("push_rollout_current_release_mismatch", "production_push_rollout_preflight");
  }
  if (!/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(currentManifest.serverVersion ?? "")
      || compareVersions(currentManifest.serverVersion, MINIMUM_PUSH_SERVER_VERSION) < 0) {
    fail("push_rollout_gateway_release_too_old", "production_push_rollout_preflight");
  }
  const activeSlot = await (options.resolveActiveSlot ?? resolveActiveSlot)(releaseConfig, currentManifest);
  const selected = releaseConfig.slots[activeSlot];
  if (!selected || runner.run("systemctl", ["is-active", "--quiet", `${selected.serviceName}.service`], {
    allowFailure: true,
  }).status !== 0) {
    fail("push_rollout_active_service_invalid", "production_push_rollout_preflight");
  }

  let inspected;
  try {
    inspected = await (options.inspectEnvironment ?? inspectProductionReleaseEnvironment)(releaseConfig, activeSlot);
  } catch (error) {
    fail(`push_rollout_environment_invalid:${technical(error)}`, "production_push_rollout_preflight");
  }
  if (inspected.mode !== "email_sharing_components_web") {
    fail("push_rollout_requires_web_state", "production_push_rollout_preflight");
  }
  const environmentPath = path.join(releaseConfig.paths.configRoot, "slots", activeSlot, "gateway.env");
  const previousEnvironment = await safeManagedFile(environmentPath, 64 * 1024);
  const nginxConfig = await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024);
  const previousNginxText = nginxConfig.toString("utf8");
  if (!satisfiesProductionNginxContract(releaseConfig, previousNginxText)) {
    fail("push_rollout_nginx_contract_invalid", "production_push_rollout_preflight");
  }
  const targets = rolloutTargets(releaseConfig);
  for (const [routes, render] of [
    [targets.bindingRoutes, renderMultiDeviceNginxRoutes],
    [targets.identityWebRoutes, renderIdentityWebNginxRoutes],
    [targets.sharingRoutes, renderSharingNginxRoutes],
    [targets.webAppRoutes, renderWebAppNginxRoutes],
  ]) {
    requireSingleInclude(previousNginxText, routes);
    if (!(await safeManagedFile(routes, 64 * 1024)).equals(Buffer.from(render()))) {
      fail("push_rollout_previous_routes_invalid", "production_push_rollout_preflight");
    }
  }
  requireNoInclude(previousNginxText, targets.pushRoutes);
  await requireAbsent(targets.pushRoutes, "push_rollout_routes_already_exist");
  await requireAbsent(targets.serviceAccount, "push_rollout_service_account_already_installed");

  const serviceAccount = await readServiceAccount(config, releaseConfig, secretSourceUid);
  const material = {
    appToken: (await safeSecretFile(releaseConfig.secrets.appTokenSource)).toString("utf8").trim(),
    internalStatusToken: (await safeSecretFile(releaseConfig.secrets.internalStatusTokenSource)).toString("utf8").trim(),
  };
  if (material.appToken.length < 8 || material.internalStatusToken.length < 8) {
    fail("push_rollout_smoke_secret_invalid", "production_push_rollout_preflight");
  }
  const verifyPrevious = options.verifyPrevious ?? verifyPreviousWebSurface;
  const probeDeviceWebSocket = options.probeDeviceWebSocket ?? probeDeviceWebSocketStatus;
  let legacyState;
  try {
    legacyState = await verifyPrevious({
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
      probeDeviceWebSocket,
    });
  } catch (error) {
    fail(`push_rollout_web_preflight_failed:${technical(error)}`, "production_push_rollout_preflight");
  }

  let deploymentLock;
  try {
    deploymentLock = await acquireDeploymentLock(
      path.join(releaseConfig.paths.stateRoot, "ops", "deploy.lock"),
      runId,
    );
  } catch (error) {
    fail(`push_rollout_lock_unavailable:${technical(error)}`, "production_push_rollout_preflight");
  }
  try {
  // Everything admitted above was read before the lock; a concurrent operator must not have changed
  // it since.
  if (!(await safeManagedFile(environmentPath, 64 * 1024)).equals(previousEnvironment)
      || (await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024)).toString("utf8") !== previousNginxText) {
    fail("push_rollout_state_changed_before_lock", "production_push_rollout_preflight");
  }
  await requireAbsent(targets.pushRoutes, "push_rollout_routes_already_exist");
  await requireAbsent(targets.serviceAccount, "push_rollout_service_account_already_installed");
  await requireAbsent(targets.journal, "push_rollout_journal_exists");
  await ensureManagedDirectory(path.dirname(targets.journal), 0o700, ownership.host);
  const record = { runId, activeSlot, currentManifest, now, firebaseProjectId: serviceAccount.projectId };
  await atomicWrite(targets.journal, journal({ ...record, stage: "checkpointed" }), 0o600, ownership.host);

  let liveMutationStarted = false;
  try {
    liveMutationStarted = true;
    // The Gateway (uid 1000 in the container) reads it through the read-only secrets mount.
    await ensureManagedDirectory(path.dirname(targets.serviceAccount), 0o750, ownership.secret);
    await atomicWrite(targets.serviceAccount, serviceAccount.bytes, 0o440, ownership.secret);
    await atomicWrite(targets.journal, journal({ ...record, stage: "key_installed" }), 0o600, ownership.host);
    await atomicWrite(targets.pushRoutes, renderPushNginxRoutes(), 0o644, ownership.host);
    await atomicWrite(
      releaseConfig.nginx.configFile,
      installInclude(previousNginxText, targets.webAppRoutes, targets.pushRoutes),
      0o644,
      ownership.host,
    );
    runner.run("nginx", ["-t"]);
    runner.run("systemctl", ["reload", "nginx.service"]);
    await atomicWrite(
      environmentPath,
      renderPushRolloutEnvironment(releaseConfig, activeSlot, inspected),
      0o600,
      ownership.host,
    );
    await atomicWrite(targets.journal, journal({ ...record, stage: "environment_installed" }), 0o600, ownership.host);
    runner.run("systemctl", ["restart", `${selected.serviceName}.service`], { timeout: 90_000 });
    await atomicWrite(targets.journal, journal({ ...record, stage: "restarted" }), 0o600, ownership.host);

    const verifyEnabled = options.verifyEnabled ?? verifyPushSurface;
    const verification = {
      config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, probeWebSocket,
      expectedLegacyState: legacyState,
      probeDeviceWebSocket,
    };
    await verifyEnabled(verification);
    await sleep(config.deployment.observationSeconds * 1000);
    await verifyEnabled(verification);
    await atomicWrite(targets.journal, journal({ ...record, stage: "committed" }), 0o600, ownership.host);
    return {
      ok: true,
      command: "production-push-rollout",
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
      pushEnabled: true,
      pushProviders: ["fcm"],
      firebaseProjectId: serviceAccount.projectId,
      runtimeContract: RUNTIME_CONTRACT,
      stage: "committed",
    };
  } catch (error) {
    if (liveMutationStarted) {
      let rollbackError;
      try {
        await atomicWrite(environmentPath, previousEnvironment, 0o600, ownership.host);
        await atomicWrite(releaseConfig.nginx.configFile, previousNginxText, 0o644, ownership.host);
        // Both were absent at admission and again under the lock, so whatever is there is ours.
        for (const created of [targets.pushRoutes, targets.serviceAccount]) {
          await unlink(created).catch((removeError) => {
            if (removeError?.code !== "ENOENT") throw removeError;
          });
        }
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
        ...record,
        stage: rollbackError ? "rollback_failed" : "rolled_back",
      }), 0o600, ownership.host).catch(() => {});
      if (rollbackError) fail("push_rollout_rollback_failed", "production_push_rollout_rollback");
    }
    if (error instanceof OpsError && error.kind === "productionPushRollout") throw error;
    fail(error instanceof Error ? error.message : error, "production_push_rollout_execute");
  }
  } finally {
    await releaseDeploymentLock(deploymentLock).catch(() => {});
  }
}

/**
 * The push registration route is the only edge change: /v2/capabilities and every other account API
 * were forwarded by earlier rollouts, and the account routes forward /v2/installations/current by
 * exact match only. A registration body is a provider name and an FCM token (at most 4096 bytes).
 */
export function renderPushNginxRoutes() {
  return `location = ${PUSH_REGISTRATION_ROUTE} {
    client_max_body_size 8k;
    proxy_pass http://hermes_go_gateway_production;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 15s;
    proxy_send_timeout 15s;
}
`;
}

export async function verifyPushSurface(request) {
  const legacyState = await verifyCommon(request, true);
  await verifyWebApp(request);
  const origin = request.config.gateway.origin;
  const loopback = loopbackOrigin(request);
  // Configured: the Gateway authenticates before anything else, publicly (the new edge route) and
  // on loopback alike.
  for (const base of [origin, loopback]) {
    for (const method of ["PUT", "DELETE"]) {
      if (await fetchStatusRetry(request.fetchImpl, `${base}${PUSH_REGISTRATION_ROUTE}`, request.sleep,
        registrationProbe(method)) !== 401) {
        fail("push_rollout_registration_guard_invalid", "production_push_rollout_verify");
      }
    }
  }
  await verifyWebSockets(request);
  return legacyState;
}

export async function verifyPreviousWebSurface(request) {
  const legacyState = await verifyCommon(request, false);
  await verifyWebApp(request);
  // Before enablement the edge does not forward the route, and the Gateway itself answers 404
  // (HR-ACCOUNT-006) before authenticating, so neither side may ask for credentials.
  const publicStatus = await fetchStatusRetry(request.fetchImpl, `${request.config.gateway.origin}${PUSH_REGISTRATION_ROUTE}`,
    request.sleep, registrationProbe("PUT"));
  if (publicStatus !== 404 && publicStatus !== 405) {
    fail("push_rollout_registration_exposed_before_enablement", "production_push_rollout_verify");
  }
  if (await fetchStatusRetry(request.fetchImpl, `${loopbackOrigin(request)}${PUSH_REGISTRATION_ROUTE}`,
    request.sleep, registrationProbe("PUT")) !== 404) {
    fail("push_rollout_registration_configured_before_enablement", "production_push_rollout_verify");
  }
  await verifyWebSockets(request);
  return legacyState;
}

function registrationProbe(method) {
  return method === "PUT"
    ? { method, headers: { "content-type": "application/json" }, body: "{}" }
    : { method };
}

/** The Web app state R5-F7 left behind must survive: /app redirect, shell, service worker, API guard. */
async function verifyWebApp(request) {
  const origin = request.config.gateway.origin;
  const redirect = await fetchResponseRetry(request.fetchImpl, `${origin}/app`, { redirect: "manual" }, request.sleep);
  if (redirect?.status !== 308 || redirect.headers.get("location") !== "/app/") {
    fail("push_rollout_app_redirect_invalid", "production_push_rollout_verify");
  }
  const shell = await fetchResponseRetry(request.fetchImpl, `${origin}/app/`, {}, request.sleep);
  const csp = shell?.headers.get("content-security-policy") ?? "";
  if (shell?.status !== 200
      || csp.includes(",")
      || !(shell.headers.get("content-type") ?? "").startsWith("text/html")
      || shell.headers.get("cache-control") !== "no-store"
      || !csp.includes("default-src 'none'")
      || !csp.includes("script-src 'self'")
      || !csp.includes("worker-src 'self'")
      || !csp.includes("frame-ancestors 'none'")
      || /unsafe-inline|unsafe-eval/.test(csp)) {
    fail("push_rollout_app_shell_invalid", "production_push_rollout_verify");
  }
  const worker = await fetchResponseRetry(request.fetchImpl, `${origin}/app/sw.js`, {}, request.sleep);
  if (worker?.status !== 200 || worker.headers.get("service-worker-allowed") !== "/app/") {
    fail("push_rollout_service_worker_invalid", "production_push_rollout_verify");
  }
  if (await fetchStatusRetry(request.fetchImpl, `${origin}/v2/devices/probe-device/api/sessions`, request.sleep) !== 401) {
    fail("push_rollout_device_api_guard_invalid", "production_push_rollout_verify");
  }
}

async function verifyWebSockets(request) {
  if (!await request.probeWebSocket(request.config.gateway.origin)) {
    fail("push_rollout_connector_websocket_unavailable", "production_push_rollout_verify");
  }
  if (await request.probeDeviceWebSocket(request.config.gateway.origin) !== 401) {
    fail("push_rollout_device_websocket_guard_invalid", "production_push_rollout_verify");
  }
}

function loopbackOrigin({ releaseConfig, activeSlot }) {
  return `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
}

async function verifyCommon({
  config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material, expectedLegacyState = null,
}, pushEnabled) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("push_rollout_service_inactive", "production_push_rollout_verify");
  }
  const loopback = loopbackOrigin({ releaseConfig, activeSlot });
  const readiness = await fetchJsonRetry(fetchImpl, `${loopback}/readyz`, {}, sleep);
  if (readiness?.status !== "ready" || readiness?.checks?.database !== "ok"
      || readiness?.checks?.migrations !== "ok" || readiness?.checks?.postgresql !== "supported") {
    fail("push_rollout_readiness_invalid", "production_push_rollout_verify");
  }
  // capabilities.push is pinned here on both origins: exactly {providers:["fcm"]} when enabled,
  // absent before.
  const surface = {
    bindingEnabled: true,
    multiDeviceEnabled: true,
    identityWebEnabled: true,
    sharingEnabled: true,
    componentInstallEnabled: true,
    webDeviceAccessEnabled: true,
    pushEnabled,
  };
  await verifyPreservedEmailSurfaceRetry({ gatewayUrl: config.gateway.origin, publicRoute: true }, fetchImpl, surface, sleep);
  await verifyPreservedEmailSurfaceRetry({ gatewayUrl: loopback, publicRoute: false }, fetchImpl, surface, sleep);
  if (await fetchStatusRetry(fetchImpl, `${config.gateway.origin}/v2/devices`, sleep) !== 401) {
    fail("push_rollout_device_route_guard_invalid", "production_push_rollout_verify");
  }
  for (const route of ["/v2/web/devices/probe-device/shares", "/v2/devices/probe-device/shares"]) {
    if (await fetchStatusRetry(fetchImpl, `${config.gateway.origin}${route}`, sleep) !== 401) {
      fail("push_rollout_sharing_route_guard_invalid", "production_push_rollout_verify");
    }
  }
  for (const [absent, method] of [["/v2/web/auth/google/exchange", "POST"], ["/v2/web/account", "DELETE"]]) {
    const status = await fetchStatusRetry(fetchImpl, `${config.gateway.origin}${absent}`, sleep, { method });
    if (status !== 404 && status !== 405) {
      fail("push_rollout_forbidden_route_exposed", "production_push_rollout_verify");
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
    fail("push_rollout_legacy_unhealthy", "production_push_rollout_verify");
  }
  const version = await fetchJsonRetry(fetchImpl, `${loopback}/internal/version`, {
    headers: { authorization: `Bearer ${material.internalStatusToken}` },
  }, sleep);
  if (version?.serverVersion !== currentManifest.serverVersion || version?.sourceCommit !== currentManifest.sourceCommit) {
    fail("push_rollout_release_identity_mismatch", "production_push_rollout_verify");
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

/**
 * Reads and validates the Firebase service-account key. Every refusal is a fixed code: neither the
 * key, nor a JSON parser message quoting it, nor any field value may reach an error or a log.
 */
async function readServiceAccount(config, releaseConfig, secretSourceUid) {
  const source = config.secrets.fcmServiceAccountSource;
  const roots = [releaseConfig.paths.installRoot, releaseConfig.paths.configRoot, releaseConfig.paths.stateRoot];
  if (roots.some((root) => source === root || source.startsWith(`${root}/`))) {
    fail("push_rollout_service_account_source_inside_managed_root", "production_push_rollout_preflight");
  }
  const bytes = await safeFile(
    source,
    MAX_SERVICE_ACCOUNT_BYTES,
    0o077,
    "push_rollout_service_account_source_unsafe",
    secretSourceUid,
  );
  let record;
  try {
    record = JSON.parse(bytes.toString("utf8"));
  } catch {
    fail("push_rollout_service_account_invalid", "production_push_rollout_preflight");
  }
  if (!record || typeof record !== "object" || Array.isArray(record)
      || record.type !== "service_account"
      || typeof record.project_id !== "string" || !FIREBASE_PROJECT_ID_PATTERN.test(record.project_id)
      || typeof record.client_email !== "string" || !record.client_email.includes("@")
      || typeof record.private_key !== "string" || !record.private_key.includes("PRIVATE KEY")) {
    fail("push_rollout_service_account_invalid", "production_push_rollout_preflight");
  }
  if (record.project_id !== config.push.firebaseProjectId) {
    fail("push_rollout_service_account_project_mismatch", "production_push_rollout_preflight");
  }
  return { bytes, projectId: record.project_id };
}

function rolloutTargets(releaseConfig) {
  return {
    journal: path.join(releaseConfig.paths.stateRoot, "ops", "push-rollout.json"),
    // <configRoot>/secrets is bind-mounted read-only at /run/hermes-go/secrets, where the environment points.
    serviceAccount: path.join(releaseConfig.paths.configRoot, "secrets", path.posix.basename(FCM_SERVICE_ACCOUNT_FILE)),
    bindingRoutes: path.join(releaseConfig.paths.configRoot, "account", "binding-routes.conf"),
    identityWebRoutes: path.join(releaseConfig.paths.configRoot, "account", "identity-web-routes.conf"),
    sharingRoutes: path.join(releaseConfig.paths.configRoot, "account", "sharing-routes.conf"),
    webAppRoutes: path.join(releaseConfig.paths.configRoot, "account", "web-app-routes.conf"),
    pushRoutes: path.join(releaseConfig.paths.configRoot, "account", "push-routes.conf"),
  };
}

function requireSingleInclude(content, routesPath) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("push_rollout_nginx_routes_path_invalid", "production_push_rollout_preflight");
  }
  const directive = `include ${routesPath};`;
  if (content.split("\n").filter((line) => line.trim() === directive).length !== 1) {
    fail("push_rollout_previous_include_invalid", "production_push_rollout_preflight");
  }
}

function requireNoInclude(content, routesPath) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("push_rollout_nginx_routes_path_invalid", "production_push_rollout_preflight");
  }
  const directive = `include ${routesPath};`;
  if (content.split("\n").some((line) => line.trim() === directive)) {
    fail("push_rollout_include_already_exists", "production_push_rollout_preflight");
  }
}

function installInclude(content, webAppRoutes, pushRoutes) {
  if (!/^\/[A-Za-z0-9._/-]+$/.test(pushRoutes)) {
    fail("push_rollout_nginx_routes_path_invalid", "production_push_rollout_preflight");
  }
  const webAppDirective = `include ${webAppRoutes};`;
  const pushDirective = `include ${pushRoutes};`;
  if (content.split("\n").some((line) => line.trim() === pushDirective)) {
    fail("push_rollout_include_already_exists", "production_push_rollout_preflight");
  }
  // By whole line, not substring, so a commented-out copy of the directive can never be the anchor.
  const lines = content.split("\n");
  const index = lines.findIndex((line) => line.trim() === webAppDirective);
  if (index < 0) fail("push_rollout_web_app_include_invalid", "production_push_rollout_preflight");
  const indent = /^\s*/.exec(lines[index])[0];
  lines.splice(index + 1, 0, `${indent}${pushDirective}`);
  return lines.join("\n");
}

function journal({ runId, stage, activeSlot, currentManifest, now, firebaseProjectId }) {
  return `${JSON.stringify({
    schemaVersion: 1,
    kind: "hermes-go-production-push-rollout-v1",
    runId,
    stage,
    activeSlot,
    serverVersion: currentManifest.serverVersion,
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: currentManifest.releaseContract.databaseSchemaVersion,
    runtimeContract: RUNTIME_CONTRACT,
    pushEnabled: true,
    pushProviders: ["fcm"],
    firebaseProjectId,
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
  return safeFile(filePath, 8 * 1024, 0o077, "push_rollout_secret_file_unsafe");
}

async function safeManagedFile(filePath, maximumBytes) {
  return safeFile(filePath, maximumBytes, 0o022, "push_rollout_file_unsafe");
}

async function safeFile(filePath, maximumBytes, forbiddenMode, cause, requiredUid = null) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath || filePath === "/") {
    fail("push_rollout_file_path_invalid", "production_push_rollout_preflight");
  }
  await assertNoSymlinkAncestors(path.dirname(filePath));
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & forbiddenMode) !== 0
        || (requiredUid !== null && info.uid !== requiredUid)) {
      fail(cause, "production_push_rollout_preflight");
    }
    return await handle.readFile();
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(`${cause}:${error?.code ?? "unreadable"}`, "production_push_rollout_preflight");
  } finally {
    await handle?.close().catch(() => {});
  }
}

async function assertNoSymlinkAncestors(directory) {
  const parts = directory.split(path.sep).filter(Boolean);
  let current = path.parse(directory).root;
  for (const part of parts) {
    current = path.join(current, part);
    let info;
    try {
      info = await lstat(current);
    } catch {
      fail("push_rollout_path_ancestor_unsafe", "production_push_rollout_preflight");
    }
    if (info.isSymbolicLink() || !info.isDirectory()) {
      fail("push_rollout_path_ancestor_unsafe", "production_push_rollout_preflight");
    }
  }
}

async function requireAbsent(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "production_push_rollout_preflight");
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
    fail("push_rollout_authorization_failed", "production_push_rollout_authorize");
  }
}

function technical(error) {
  if (error instanceof OpsError) return `${error.stage}:${error.technicalCause}`;
  return error instanceof Error ? error.message : String(error);
}

function fail(cause, stage) {
  throw new OpsError("productionPushRollout", cause, stage);
}
