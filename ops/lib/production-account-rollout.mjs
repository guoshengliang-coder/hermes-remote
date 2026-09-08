import { randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, unlink } from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { loadBundleManifest } from "./config.mjs";
import { verifyDatabaseMigration } from "./database-migration.mjs";
import { renderDeployGatewayEnvironment } from "./deploy-system.mjs";
import { satisfiesProductionNginxContract } from "./deploy-switch.mjs";
import { loadCurrentManifest, resolveActiveSlot } from "./deploy-command.mjs";
import { OpsError } from "./errors.mjs";
import { verifyLoadedImage } from "./hermesctl.mjs";
import { loadManagedBaselineConfig } from "./managed-baseline-config.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";

const SECRET_TARGETS = Object.freeze({
  accountDatabaseUrlSource: "account-database-url",
  accountTokenHashKeySource: "account-token-hash-key",
  accountEmailOtpHashKeySource: "account-email-otp-hash-key",
  resendApiKeySource: "resend-api-key",
  resendWebhookSecretSource: "resend-webhook-secret",
  emailFromSource: "account-email-from",
});

export async function executeProductionAccountRollout(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 120_000 });
  const now = options.now ?? (() => new Date());
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const fetchImpl = options.fetchImpl ?? fetch;
  const ownership = options.ownership ?? { host: { uid: 0, gid: 0 }, secret: { uid: 0, gid: 1000 } };
  const runId = options.runId ?? randomUUID();
  authorize(config, options);

  const releaseConfig = await (options.loadReleaseConfig ?? loadManagedBaselineConfig)(config.productionReleaseConfig);
  if (releaseConfig.host.hostname !== config.host.hostname || releaseConfig.host.architecture !== config.host.architecture) {
    fail("account_rollout_release_config_host_mismatch", "production_account_rollout_preflight");
  }
  if (releaseConfig.gateway.accountAuthEnabled !== false || releaseConfig.gateway.accountBindingEnabled !== false
      || releaseConfig.database !== null) {
    fail("account_rollout_requires_disabled_release_config", "production_account_rollout_preflight");
  }
  const currentManifest = await (options.loadCurrentManifest ?? loadCurrentManifest)(releaseConfig);
  const targetManifest = await (options.loadBundleManifest ?? loadBundleManifest)(releaseConfig.targetArtifactManifest);
  if (currentManifest.sourceCommit !== targetManifest.sourceCommit
      || currentManifest.serverVersion !== targetManifest.serverVersion
      || currentManifest.imageId !== targetManifest.imageId
      || currentManifest.containerdImageId !== targetManifest.containerdImageId
      || currentManifest.archiveSha256 !== targetManifest.archiveSha256
      || JSON.stringify(currentManifest.releaseContract) !== JSON.stringify(targetManifest.releaseContract)) {
    fail("account_rollout_current_release_mismatch", "production_account_rollout_preflight");
  }
  if (targetManifest.releaseContract?.databaseSchemaVersion !== 15
      || !targetManifest.releaseContract?.supportedPostgresqlMajors?.includes(18)) {
    fail("account_rollout_release_contract_invalid", "production_account_rollout_preflight");
  }
  const activeSlot = await (options.resolveActiveSlot ?? resolveActiveSlot)(releaseConfig, currentManifest);
  const service = releaseConfig.slots[activeSlot]?.serviceName;
  if (!service || runner.run("systemctl", ["is-active", "--quiet", `${service}.service`], { allowFailure: true }).status !== 0) {
    fail("account_rollout_active_service_invalid", "production_account_rollout_preflight");
  }

  const environmentPath = path.join(releaseConfig.paths.configRoot, "slots", activeSlot, "gateway.env");
  const previousEnvironment = await safeManagedFile(environmentPath, 64 * 1024);
  if (!previousEnvironment.equals(Buffer.from(renderDeployGatewayEnvironment(releaseConfig, activeSlot)))) {
    fail("account_rollout_disabled_environment_drift", "production_account_rollout_preflight");
  }
  const journalPath = path.join(releaseConfig.paths.stateRoot, "ops", "account-rollout.json");
  const previousNginxConfig = await safeManagedFile(releaseConfig.nginx.configFile, 1024 * 1024);
  await requireAbsent(journalPath, "account_rollout_journal_exists");
  const material = await inspectMaterial(config, releaseConfig);
  const targets = rolloutTargets(releaseConfig);
  for (const [sourceName, target] of Object.entries(targets.secrets)) {
    await requireAbsentOrExact(target, `${material[sourceName]}\n`, "account_rollout_secret_target_drift");
  }
  await requireAbsentOrExact(
    targets.databaseMigration,
    `${material.accountDatabaseUrlSource}\n`,
    "account_rollout_database_target_drift",
  );
  await requireAbsent(targets.nginxRoutes, "account_rollout_nginx_routes_target_exists");

  await ensureManagedDirectory(path.dirname(journalPath), 0o700, ownership.host);
  await atomicWrite(journalPath, journal({ runId, stage: "checkpointed", activeSlot, currentManifest, now }), 0o600, ownership.host);
  let liveMutationStarted = false;
  try {
    await ensureManagedDirectory(path.dirname(targets.databaseMigration), 0o750, ownership.secret);
    await ensureManagedDirectory(path.dirname(targets.secrets.accountDatabaseUrlSource), 0o750, ownership.secret);
    await atomicWrite(targets.databaseMigration, `${material.accountDatabaseUrlSource}\n`, 0o440, ownership.secret);
    const runtime = (options.verifyLoadedImage ?? verifyLoadedImage)(runner, currentManifest);
    const migration = (options.verifyDatabaseMigration ?? verifyDatabaseMigration)({
      ...releaseConfig,
      database: {
        urlSource: config.secrets.accountDatabaseUrlSource,
        ssl: config.database.ssl,
        migrationLockId: config.database.migrationLockId,
      },
    }, currentManifest, runner, runtime.imageId);
    await atomicWrite(journalPath, journal({ runId, stage: "migrated", activeSlot, currentManifest, now, migration }), 0o600, ownership.host);

    for (const [sourceName, target] of Object.entries(targets.secrets)) {
      await atomicWrite(target, `${material[sourceName]}\n`, 0o440, ownership.secret);
    }
    await atomicWrite(journalPath, journal({ runId, stage: "secrets_installed", activeSlot, currentManifest, now, migration }), 0o600, ownership.host);
    liveMutationStarted = true;
    const previousNginxText = previousNginxConfig.toString("utf8");
    await atomicWrite(targets.nginxRoutes, renderEmailAccountNginxRoutes({
      includeCapabilities: !hasCapabilitiesLocation(previousNginxText),
      includeResendWebhook: !hasResendWebhookLocation(previousNginxText),
    }), 0o644, ownership.host);
    await atomicWrite(
      releaseConfig.nginx.configFile,
      installEmailAccountNginxInclude(previousNginxText, releaseConfig, targets.nginxRoutes),
      0o644,
      ownership.host,
    );
    runner.run("nginx", ["-t"]);
    runner.run("systemctl", ["reload", "nginx.service"]);
    const nextEnvironment = renderEmailRolloutEnvironment(releaseConfig, config, activeSlot);
    await atomicWrite(environmentPath, nextEnvironment, 0o600, ownership.host);
    await atomicWrite(journalPath, journal({ runId, stage: "environment_installed", activeSlot, currentManifest, now, migration }), 0o600, ownership.host);
    runner.run("systemctl", ["restart", `${service}.service`], { timeout: 90_000 });
    await atomicWrite(journalPath, journal({ runId, stage: "restarted", activeSlot, currentManifest, now, migration }), 0o600, ownership.host);
    const verifyEnabled = options.verifyRollout ?? verifyRollout;
    await verifyEnabled({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material });
    await (options.verifyEmailDelivery ?? verifyEmailDelivery)({
      config,
      releaseConfig,
      activeSlot,
      fetchImpl,
      sleep,
      material,
      randomUUID: options.randomUUID ?? randomUUID,
    });
    await sleep(config.deployment.observationSeconds * 1000);
    await verifyEnabled({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material });
    await atomicWrite(journalPath, journal({ runId, stage: "committed", activeSlot, currentManifest, now, migration }), 0o600, ownership.host);
    return {
      ok: true,
      command: "production-account-email-rollout",
      runId,
      activeSlot,
      serverVersion: currentManifest.serverVersion,
      sourceCommit: currentManifest.sourceCommit,
      databaseSchemaVersion: migration.schemaVersion,
      accountAuthEnabled: true,
      emailOtpEnabled: true,
      googleAuthEnabled: false,
      bindingEnabled: false,
      deliveryAcceptance: "resend_delivered_test_address",
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
        runner.run("systemctl", ["restart", `${service}.service`], { timeout: 90_000 });
        await (options.verifyDisabled ?? verifyDisabled)({
          releaseConfig,
          activeSlot,
          fetchImpl,
          sleep,
          runner,
          material,
          capabilitiesExposedBefore: hasCapabilitiesLocation(previousNginxConfig.toString("utf8")),
          emailChallengeExposedBefore: hasEmailChallengeLocation(previousNginxConfig.toString("utf8")),
        });
      } catch (rollbackFailure) {
        rollbackError = rollbackFailure;
      }
      await atomicWrite(journalPath, journal({
        runId,
        stage: rollbackError ? "rollback_failed" : "rolled_back",
        activeSlot,
        currentManifest,
        now,
      }), 0o600, ownership.host).catch(() => {});
      if (rollbackError) fail("account_rollout_rollback_failed", "production_account_rollout_rollback");
    }
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error, "production_account_rollout_execute");
  }
}

export function renderEmailRolloutEnvironment(releaseConfig, config, activeSlot) {
  const selected = releaseConfig.slots[activeSlot];
  if (!selected) fail("account_rollout_active_slot_invalid", "production_account_rollout_preflight");
  return [
    `PORT=${selected.gatewayPort}`,
    "HOST=127.0.0.1",
    "APP_TOKEN_FILE=/run/hermes-go/secrets/app-token",
    "CONNECTOR_TOKEN_FILE=/run/hermes-go/secrets/connector-token",
    "INTERNAL_STATUS_TOKEN_FILE=/run/hermes-go/secrets/internal-status-token",
    `DEFAULT_DEVICE_ID=${releaseConfig.gateway.defaultDeviceId}`,
    "LIFECYCLE_EVENT_STORE_FILE=/var/lib/hermes-go/lifecycle-events.json",
    "GATEWAY_LOG_LEVEL=info",
    "ACCOUNT_AUTH_ENABLED=1",
    "ACCOUNT_BINDING_ENABLED=0",
    "ACCOUNT_MULTI_DEVICE_ENABLED=0",
    "ACCOUNT_DEVICE_SHARING_ENABLED=0",
    "ACCOUNT_EMAIL_OTP_ENABLED=1",
    "ACCOUNT_RESEND_WEBHOOK_ENABLED=1",
    "ACCOUNT_GOOGLE_AUTH_ENABLED=0",
    "ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=0",
    "ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=0",
    "ACCOUNT_WEB_SESSION_ENABLED=0",
    "ACCOUNT_DELETION_ENABLED=0",
    "ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=0",
    "ACCOUNT_DATABASE_URL_FILE=/run/hermes-go/secrets/account-database-url",
    "ACCOUNT_TOKEN_HASH_KEY_FILE=/run/hermes-go/secrets/account-token-hash-key",
    "ACCOUNT_EMAIL_OTP_HASH_KEY_FILE=/run/hermes-go/secrets/account-email-otp-hash-key",
    "ACCOUNT_RESEND_API_KEY_FILE=/run/hermes-go/secrets/resend-api-key",
    "ACCOUNT_RESEND_WEBHOOK_SECRET_FILE=/run/hermes-go/secrets/resend-webhook-secret",
    "ACCOUNT_EMAIL_FROM_FILE=/run/hermes-go/secrets/account-email-from",
    `ACCOUNT_EMAIL_OTP_ISSUER=${config.gateway.emailIssuer}`,
    `ACCOUNT_DATABASE_SSL=${config.database.ssl ? "1" : "0"}`,
    "ACCOUNT_DATABASE_POOL_SIZE=10",
    "ACCOUNT_DATABASE_CONNECT_TIMEOUT_MS=3000",
    `ACCOUNT_TRUST_LOOPBACK_PROXY=${config.gateway.trustLoopbackProxy ? "1" : "0"}`,
    `ACCOUNT_GATEWAY_ORIGIN=${config.gateway.origin}`,
    "ACCOUNT_MAX_PENDING_CONNECTOR_PROOFS=256",
    "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS=16",
    "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP=4",
    "MAX_ACCOUNT_LIFECYCLE_EVENTS=10000",
    "ACCOUNT_LIFECYCLE_RETENTION_DAYS=30",
    "ACCOUNT_AUDIT_RETENTION_DAYS=180",
    "MAX_LIFECYCLE_EVENTS=10000",
    "",
  ].join("\n");
}

export function renderEmailAccountNginxRoutes({
  includeCapabilities = true,
  includeResendWebhook = true,
} = {}) {
  const paths = [
    ...(includeCapabilities ? ["/v2/capabilities"] : []),
    "/v2/auth/email/challenges",
    "/v2/auth/email/exchange",
    "/v2/auth/refresh",
    "/v2/auth/sign-out",
    "/v2/account",
  ];
  const accountRoutes = paths.map((accountPath) => `location = ${accountPath} {
    client_max_body_size 16k;
    proxy_pass http://hermes_go_gateway_production;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 15s;
    proxy_send_timeout 15s;
}`).join("\n\n");
  const webhookRoute = includeResendWebhook ? `

location = /v2/webhooks/resend {
    client_max_body_size 64k;
    proxy_pass http://hermes_go_gateway_production;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
    proxy_connect_timeout 5s;
    proxy_read_timeout 15s;
    proxy_send_timeout 15s;
}` : "";
  return `${accountRoutes}${webhookRoute}\n`;
}

function hasCapabilitiesLocation(content) {
  return /^[\t ]*location[\t ]*=[\t ]*\/v2\/capabilities[\t ]*\{/m.test(content);
}

function hasResendWebhookLocation(content) {
  return /^[\t ]*location[\t ]*=[\t ]*\/v2\/webhooks\/resend[\t ]*\{/m.test(content);
}

function hasEmailChallengeLocation(content) {
  return /^[\t ]*location[\t ]*=[\t ]*\/v2\/auth\/email\/challenges[\t ]*\{/m.test(content);
}

export function installEmailAccountNginxInclude(content, releaseConfig, routesPath) {
  if (!satisfiesProductionNginxContract(releaseConfig, content)
      || content.includes(routesPath)
      || !/^\/[A-Za-z0-9._/-]+$/.test(routesPath)) {
    fail("account_rollout_nginx_contract_invalid", "production_account_rollout_preflight");
  }
  const matches = [...content.matchAll(/^[\t ]*server_name[\t ]+([A-Za-z0-9.-]+)[\t ]*;[\t ]*$/gm)]
    .filter((match) => match[1] === releaseConfig.nginx.serverName);
  if (matches.length !== 1) fail("account_rollout_nginx_server_ambiguous", "production_account_rollout_preflight");
  const match = matches[0];
  return `${content.slice(0, match.index)}${match[0]}\n\n    include ${routesPath};${content.slice(match.index + match[0].length)}`;
}

async function inspectMaterial(config, releaseConfig) {
  const roots = [releaseConfig.paths.installRoot, releaseConfig.paths.configRoot, releaseConfig.paths.stateRoot];
  const sources = Object.values(config.secrets);
  if (sources.some((source) => roots.some((root) => source === root || source.startsWith(`${root}/`)))) {
    fail("account_rollout_secret_source_inside_managed_root", "production_account_rollout_preflight");
  }
  const material = {};
  for (const [name, source] of Object.entries(config.secrets)) {
    material[name] = (await safeSecretFile(source)).toString("utf8").trim();
  }
  let database;
  try {
    database = new URL(material.accountDatabaseUrlSource);
  } catch {
    fail("account_rollout_database_url_invalid", "production_account_rollout_preflight");
  }
  if (database.protocol !== "postgresql:" || !new Set(["127.0.0.1", "localhost", "[::1]"]).has(database.hostname)
      || (database.port && database.port !== "5432") || !database.username || !database.password
      || database.pathname.length < 2 || database.search || database.hash) {
    fail("account_rollout_database_url_invalid", "production_account_rollout_preflight");
  }
  if (Buffer.byteLength(material.accountTokenHashKeySource) < 32
      || Buffer.byteLength(material.accountEmailOtpHashKeySource) < 32
      || !/^re_[A-Za-z0-9_+/-]{8,}$/.test(material.resendApiKeySource)
      || !/^whsec_[A-Za-z0-9_+/-]{16,}$/.test(material.resendWebhookSecretSource)
      || /[\r\n]/.test(material.emailFromSource)
      || !material.emailFromSource.endsWith(`@${config.gateway.emailFromDomain}>`)) {
    fail("account_rollout_secret_material_invalid", "production_account_rollout_preflight");
  }
  material.appToken = (await safeSecretFile(releaseConfig.secrets.appTokenSource)).toString("utf8").trim();
  material.internalStatusToken = (await safeSecretFile(releaseConfig.secrets.internalStatusTokenSource)).toString("utf8").trim();
  return material;
}

async function verifyRollout({ config, releaseConfig, activeSlot, currentManifest, fetchImpl, sleep, runner, material }) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("account_rollout_service_inactive", "production_account_rollout_verify");
  }
  const loopback = `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
  const ready = await fetchJsonRetry(fetchImpl, `${loopback}/readyz`, {}, sleep);
  const capabilities = await fetchJsonRetry(fetchImpl, `${config.gateway.origin}/v2/capabilities`, {}, sleep);
  const relay = await fetchJsonRetry(fetchImpl, `${config.gateway.origin}/relay-health`, {}, sleep);
  const status = await fetchJsonRetry(fetchImpl, `${config.gateway.origin}/api/status`, {
    headers: { "x-hermes-session-token": material.appToken },
  }, sleep);
  const rejected = await fetchImpl(`${config.gateway.origin}/api/status`, {
    headers: { "x-hermes-session-token": "intentionally-invalid-production-rollout-token" },
    signal: AbortSignal.timeout(3_000),
  }).catch(() => null);
  const webhook = await fetchImpl(`${config.gateway.origin}/v2/webhooks/resend`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
    signal: AbortSignal.timeout(3_000),
  }).catch(() => null);
  const emailRoute = await fetchImpl(`${config.gateway.origin}/v2/auth/email/challenges`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
    signal: AbortSignal.timeout(3_000),
  }).catch(() => null);
  const legacyStatusHealthy = status?.status === "ok"
    || (status?.overall === "ok" && status?.gateway_running === true);
  if (ready?.status !== "ready" || ready?.checks?.migrations !== "current"
      || capabilities?.accountAuth?.enabled !== true
      || JSON.stringify(capabilities.accountAuth.providers) !== JSON.stringify(["email_otp"])
      || capabilities.accountAuth.identityManagement !== false
      || capabilities.accountAuth.webAccountCenter !== false
      || capabilities.accountAuth.webSessions !== undefined
      || capabilities.accountAuth.accountDeletion !== undefined
      || capabilities?.binding?.enabled !== false
      || capabilities?.binding?.supportsDeviceSelection !== undefined
      || capabilities?.binding?.supportsDeviceSharing !== undefined
      || capabilities?.desktopBootstrap !== undefined
      || capabilities?.legacy?.appTokenAccepted !== true
      || capabilities?.legacy?.connectorTokenAccepted !== true
      || relay?.connectors < 1 || !legacyStatusHealthy || rejected?.status !== 401
      || !webhook || !new Set([400, 401]).has(webhook.status)
      || !emailRoute || emailRoute.status !== 400) {
    fail("account_rollout_smoke_failed", "production_account_rollout_verify");
  }
  const version = await fetchJsonRetry(fetchImpl, `${loopback}/internal/version`, {
    headers: { authorization: `Bearer ${material.internalStatusToken}` },
  }, sleep);
  if (version?.serverVersion !== currentManifest.serverVersion || version?.sourceCommit !== currentManifest.sourceCommit) {
    fail("account_rollout_release_identity_mismatch", "production_account_rollout_verify");
  }
}

async function verifyEmailDelivery({ config, releaseConfig, activeSlot, fetchImpl, sleep, material, randomUUID: uuid }) {
  const loopback = `http://127.0.0.1:${releaseConfig.slots[activeSlot].gatewayPort}`;
  const metricsUrl = `${loopback}/internal/account-email-metrics`;
  const headers = { authorization: `Bearer ${material.internalStatusToken}` };
  const before = emailCounters(await fetchJsonRetry(fetchImpl, metricsUrl, { headers }, sleep));
  const response = await fetchImpl(`${config.gateway.origin}/v2/auth/email/challenges`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      email: "delivered@resend.dev",
      platform: "macos",
      clientInstallationId: uuid(),
    }),
    signal: AbortSignal.timeout(8_000),
  }).catch(() => null);
  if (!response || response.status !== 202) {
    fail("account_rollout_delivery_submission_failed", "production_account_rollout_delivery");
  }
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const current = emailCounters(await fetchJsonRetry(fetchImpl, metricsUrl, { headers }, sleep));
    const delta = Object.fromEntries(Object.keys(before).map((key) => [key, current[key] - before[key]]));
    const accepted = Object.entries(delta).every(([key, value]) => {
      const expected = new Set(["requested", "providerAccepted", "finalDelivered"]).has(key) ? 1 : 0;
      return value === expected;
    });
    if (accepted) return;
    if (Object.values(delta).some((value) => value < 0 || value > 1)) {
      fail("account_rollout_delivery_not_isolated", "production_account_rollout_delivery");
    }
    if (attempt < 59) await sleep(2_000);
  }
  fail("account_rollout_delivery_timeout", "production_account_rollout_delivery");
}

function emailCounters(value) {
  const keys = [
    "requested", "providerAccepted", "providerFailed", "pending", "finalDelivered",
    "finalHardFailed", "finalDelayed", "verified",
  ];
  const counters = value?.emailOtp;
  if (!counters || typeof counters !== "object" || Array.isArray(counters)
      || !keys.every((key) => Number.isSafeInteger(counters[key]) && counters[key] >= 0)) {
    fail("account_rollout_delivery_metrics_invalid", "production_account_rollout_delivery");
  }
  return Object.fromEntries(keys.map((key) => [key, counters[key]]));
}

async function verifyDisabled({
  releaseConfig,
  activeSlot,
  fetchImpl,
  sleep,
  runner,
  material,
  capabilitiesExposedBefore,
  emailChallengeExposedBefore,
}) {
  const service = `${releaseConfig.slots[activeSlot].serviceName}.service`;
  if (runner.run("systemctl", ["is-active", "--quiet", service], { allowFailure: true }).status !== 0) {
    fail("account_rollout_rollback_service_inactive", "production_account_rollout_rollback");
  }
  const publicOrigin = `https://${releaseConfig.nginx.serverName}`;
  const capabilitiesResponse = await fetchResponseRetry(
    fetchImpl,
    `${publicOrigin}/v2/capabilities`,
    {},
    sleep,
  );
  let capabilities;
  if (capabilitiesResponse?.ok) {
    try {
      capabilities = await capabilitiesResponse.json();
    } catch {}
  }
  const status = await fetchJsonRetry(fetchImpl, `https://${releaseConfig.nginx.serverName}/api/status`, {
    headers: { "x-hermes-session-token": material.appToken },
  }, sleep);
  const emailRoute = await fetchImpl(`${publicOrigin}/v2/auth/email/challenges`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
    signal: AbortSignal.timeout(3_000),
  }).catch(() => null);
  const capabilitiesDisabled = capabilitiesExposedBefore
    ? capabilities?.accountAuth?.enabled === false
    : capabilitiesResponse?.status === 404;
  const emailRouteDisabled = emailChallengeExposedBefore
    ? emailRoute?.status === 503
    : new Set([404, 405]).has(emailRoute?.status);
  const legacyStatusHealthy = status?.status === "ok"
    || (status?.overall === "ok" && status?.gateway_running === true);
  if (!capabilitiesDisabled || !legacyStatusHealthy || !emailRouteDisabled) {
    fail("account_rollout_rollback_smoke_failed", "production_account_rollout_rollback");
  }
}

async function fetchResponseRetry(fetchImpl, url, init, sleep) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      const response = await fetchImpl(url, { ...init, signal: AbortSignal.timeout(3_000) });
      if (response.ok || response.status === 404) return response;
    } catch {}
    if (attempt < 19) await sleep(250);
  }
  return null;
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

function rolloutTargets(releaseConfig) {
  const secretRoot = path.join(releaseConfig.paths.configRoot, "secrets");
  return {
    databaseMigration: path.join(releaseConfig.paths.configRoot, "database-secrets", "account-database-url"),
    nginxRoutes: path.join(releaseConfig.paths.configRoot, "account", "email-login-routes.conf"),
    secrets: Object.fromEntries(Object.entries(SECRET_TARGETS).map(([source, name]) => [source, path.join(secretRoot, name)])),
  };
}

async function safeSecretFile(filePath) {
  return safeFile(filePath, 8 * 1024, 0o077, "account_rollout_secret_file_unsafe");
}

async function safeManagedFile(filePath, maximumBytes) {
  return safeFile(filePath, maximumBytes, 0o022, "account_rollout_file_unsafe");
}

async function safeFile(filePath, maximumBytes, forbiddenMode, unsafeCause) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath || filePath === "/") {
    fail("account_rollout_file_path_invalid", "production_account_rollout_preflight");
  }
  await assertNoSymlinkAncestors(path.dirname(filePath));
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & forbiddenMode) !== 0) {
      fail(unsafeCause, "production_account_rollout_preflight");
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
      fail("account_rollout_path_ancestor_unsafe", "production_account_rollout_preflight");
    }
  }
}

async function requireAbsent(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "production_account_rollout_preflight");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

async function requireAbsentOrExact(filePath, expected, cause) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile()) fail(cause, "production_account_rollout_preflight");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code === "ENOENT") return;
    throw error;
  }
  const existing = await safeManagedFile(filePath, 8 * 1024);
  if (!existing.equals(Buffer.from(expected))) fail(cause, "production_account_rollout_preflight");
}

function journal({ runId, stage, activeSlot, currentManifest, now, migration = null }) {
  return `${JSON.stringify({
    schemaVersion: 1,
    kind: "hermes-go-production-account-rollout-v1",
    runId,
    stage,
    activeSlot,
    serverVersion: currentManifest.serverVersion,
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: currentManifest.releaseContract.databaseSchemaVersion,
    migration,
    updatedAt: now().toISOString(),
  }, null, 2)}\n`;
}

function authorize(config, options) {
  if (options.confirmation !== `production:${config.host.hostname}`
      || (options.getUid ?? (() => process.getuid?.()))() !== 0
      || (options.platform ?? process.platform) !== "linux"
      || (options.architecture ?? process.arch) !== "x64"
      || (options.hostname ?? systemHostname()) !== config.host.hostname) {
    fail("account_rollout_authorization_failed", "production_account_rollout_authorize");
  }
}

function fail(cause, stage) {
  throw new OpsError("productionAccountRollout", cause, stage);
}
