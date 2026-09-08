import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadProductionAccountRolloutConfig } from "../../ops/lib/production-account-rollout-config.mjs";
import {
  executeProductionAccountRollout,
  installEmailAccountNginxInclude,
  renderEmailAccountNginxRoutes,
  renderEmailRolloutEnvironment,
} from "../../ops/lib/production-account-rollout.mjs";
import { renderDeployGatewayEnvironment } from "../../ops/lib/deploy-system.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("production email-account rollout config is strict, protected, and production-only", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, fixture.rawConfig);
  const parsed = await loadProductionAccountRolloutConfig(fixture.configPath);
  assert.equal(parsed.environment, "production");
  assert.equal(parsed.gateway.origin, "https://gateway.example.com");
  assert.equal(parsed.database.migrationLockId, 48203175);

  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadProductionAccountRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, fixture.rawConfig);
  await chmod(fixture.configPath, 0o644);
  await assert.rejects(() => loadProductionAccountRolloutConfig(fixture.configPath), isCode);
});

test("email-only environment keeps every wider account surface off and contains no source secret", async (t) => {
  const fixture = await createFixture(t);
  const rendered = renderEmailRolloutEnvironment(fixture.releaseConfig, fixture.config, "green");
  assert.match(rendered, /^PORT=18788$/m);
  assert.match(rendered, /^HOST=127\.0\.0\.1$/m);
  for (const required of [
    "ACCOUNT_AUTH_ENABLED=1",
    "ACCOUNT_EMAIL_OTP_ENABLED=1",
    "ACCOUNT_RESEND_WEBHOOK_ENABLED=1",
    "ACCOUNT_GOOGLE_AUTH_ENABLED=0",
    "ACCOUNT_BINDING_ENABLED=0",
    "ACCOUNT_MULTI_DEVICE_ENABLED=0",
    "ACCOUNT_DEVICE_SHARING_ENABLED=0",
    "ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=0",
    "ACCOUNT_WEB_SESSION_ENABLED=0",
    "ACCOUNT_DELETION_ENABLED=0",
  ]) assert.match(rendered, new RegExp(`^${required}$`, "m"));
  for (const secret of Object.values(fixture.material)) assert.equal(rendered.includes(secret), false);
});

test("the public Nginx include exposes only the email-login session and Resend callback surface", async (t) => {
  const fixture = await createFixture(t);
  const routes = renderEmailAccountNginxRoutes();
  for (const route of ["/v2/capabilities", "email/challenges", "email/exchange", "refresh", "sign-out", "/v2/account", "/v2/webhooks/resend"]) {
    assert.equal(routes.includes(route), true);
  }
  for (const forbidden of ["/v2/devices", "/v2/installations", "/v2/auth/google", "/v2/web/"]) {
    assert.equal(routes.includes(forbidden), false);
  }
  const routesPath = path.join(fixture.releaseConfig.paths.configRoot, "account", "email-login-routes.conf");
  const installed = installEmailAccountNginxInclude(fixture.nginxConfig, fixture.releaseConfig, routesPath);
  assert.equal(installed.includes(`include ${routesPath};`), true);
  assert.equal(installed.includes("server_name gateway.example.com;"), true);
  assert.throws(() => installEmailAccountNginxInclude(installed, fixture.releaseConfig, routesPath), isCode);
  assert.equal(renderEmailAccountNginxRoutes({ includeCapabilities: false }).includes("/v2/capabilities"), false);
  assert.equal(renderEmailAccountNginxRoutes({ includeResendWebhook: false }).includes("/v2/webhooks/resend"), false);
});

test("production email-account rollout migrates, installs protected inputs, and commits only after two verifications", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let verifications = 0;
  const result = await executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyRollout: async () => { verifications += 1; },
    verifyEmailDelivery: async () => {},
    verifyDisabled: async () => assert.fail("rollback verification must not run"),
  });
  assert.equal(result.stage, "committed");
  assert.equal(result.emailOtpEnabled, true);
  assert.equal(result.googleAuthEnabled, false);
  assert.equal(result.bindingEnabled, false);
  assert.equal(result.deliveryAcceptance, "resend_delivered_test_address");
  assert.equal(verifications, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 1);
  assert.match(await readFile(fixture.environmentPath, "utf8"), /^ACCOUNT_AUTH_ENABLED=1$/m);
  assert.match(
    await readFile(path.join(fixture.releaseConfig.paths.configRoot, "account", "email-login-routes.conf"), "utf8"),
    /location = \/v2\/capabilities/,
  );
  const journal = JSON.parse(await readFile(fixture.journalPath, "utf8"));
  assert.equal(journal.stage, "committed");
  assert.equal(journal.databaseSchemaVersion, 15);
});

test("a failed post-restart verification restores the exact disabled environment", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let rollbackVerified = false;
  await assert.rejects(() => executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyRollout: async () => { throw new Error("synthetic_smoke_failure"); },
    verifyEmailDelivery: async () => assert.fail("delivery must not run after failed smoke"),
    verifyDisabled: async () => { rollbackVerified = true; },
  }), isCode);
  assert.equal(rollbackVerified, true);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 2);
  assert.equal(
    await readFile(fixture.environmentPath, "utf8"),
    renderDeployGatewayEnvironment(fixture.releaseConfig, "green"),
  );
  assert.equal(JSON.parse(await readFile(fixture.journalPath, "utf8")).stage, "rolled_back");
});

test("a matching protected database URL already installed by R5-E is safely adopted", async (t) => {
  const fixture = await createFixture(t);
  const existing = path.join(fixture.releaseConfig.paths.configRoot, "secrets", "account-database-url");
  await mkdir(path.dirname(existing), { recursive: true });
  await writeFile(existing, `${fixture.material.accountDatabaseUrlSource}\n`, { mode: 0o600 });
  const result = await executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyRollout: async () => {},
    verifyEmailDelivery: async () => {},
  });
  assert.equal(result.stage, "committed");
});

test("an existing public capabilities location is not duplicated by the rollout include", async (t) => {
  const fixture = await createFixture(t);
  await writeFile(
    fixture.releaseConfig.nginx.configFile,
    fixture.nginxConfig.replace(
      "    location /api/",
      "    location = /v2/capabilities { proxy_pass http://hermes_go_gateway_production; }\n    location /api/",
    ),
    { mode: 0o644 },
  );
  const result = await executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyRollout: async () => {},
    verifyEmailDelivery: async () => {},
  });
  assert.equal(result.stage, "committed");
  const routes = await readFile(
    path.join(fixture.releaseConfig.paths.configRoot, "account", "email-login-routes.conf"),
    "utf8",
  );
  assert.equal(routes.includes("/v2/capabilities"), false);
});

test("an existing public Resend webhook location is not duplicated by the rollout include", async (t) => {
  const fixture = await createFixture(t);
  await writeFile(
    fixture.releaseConfig.nginx.configFile,
    fixture.nginxConfig.replace(
      "    location /api/",
      "    location = /v2/webhooks/resend { proxy_pass http://hermes_go_gateway_production; }\n    location /api/",
    ),
    { mode: 0o644 },
  );
  const result = await executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyRollout: async () => {},
    verifyEmailDelivery: async () => {},
  });
  assert.equal(result.stage, "committed");
  const routes = await readFile(
    path.join(fixture.releaseConfig.paths.configRoot, "account", "email-login-routes.conf"),
    "utf8",
  );
  assert.equal(routes.includes("/v2/webhooks/resend"), false);
});

test("live verification sends the legacy app token through the legacy header", async (t) => {
  const fixture = await createFixture(t);
  const statusHeaders = [];
  let legacyHealthy = false;
  const fetchImpl = async (url, init = {}) => {
    const pathname = new URL(url).pathname;
    if (pathname === "/readyz") return jsonResponse({ status: "ready", checks: { migrations: "current" } });
    if (pathname === "/v2/capabilities") return jsonResponse(enabledCapabilities());
    if (pathname === "/relay-health") return jsonResponse({ ok: true, connectors: legacyHealthy ? 1 : 0 });
    if (pathname === "/api/status") {
      if (new Headers(init.headers).get("x-hermes-session-token") !== "legacy-app-token") {
        return new Response("{}", { status: 401 });
      }
      statusHeaders.push(new Headers(init.headers));
      legacyHealthy = true;
      return jsonResponse({ overall: "ok", gateway_running: true });
    }
    if (pathname === "/v2/webhooks/resend") return new Response("{}", { status: 401 });
    if (pathname === "/v2/auth/email/challenges") return new Response("{}", { status: 400 });
    if (pathname === "/internal/version") return jsonResponse({
      serverVersion: "0.4.2",
      sourceCommit: "c".repeat(40),
    });
    assert.fail(`unexpected URL ${url}`);
  };
  const result = await executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    fetchImpl,
    verifyEmailDelivery: async () => {},
  });
  assert.equal(result.stage, "committed");
  assert.equal(statusHeaders.length, 2);
  assert.equal(statusHeaders[0].get("x-hermes-session-token"), "legacy-app-token");
  assert.equal(statusHeaders[0].has("authorization"), false);
});

test("rollback accepts original absent account routes and uses the legacy header", async (t) => {
  const fixture = await createFixture(t);
  let statusHeaders;
  let capabilityCalls = 0;
  const fetchImpl = async (url, init = {}) => {
    const pathname = new URL(url).pathname;
    if (pathname === "/v2/capabilities") {
      capabilityCalls += 1;
      return new Response(capabilityCalls === 1 ? "bad gateway" : "not found", {
        status: capabilityCalls === 1 ? 502 : 404,
      });
    }
    if (pathname === "/api/status") {
      statusHeaders = new Headers(init.headers);
      return jsonResponse({ overall: "ok", gateway_running: true });
    }
    if (pathname === "/v2/auth/email/challenges") return new Response("method not allowed", { status: 405 });
    assert.fail(`unexpected URL ${url}`);
  };
  await assert.rejects(() => executeProductionAccountRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    fetchImpl,
    verifyRollout: async () => { throw new Error("synthetic_smoke_failure"); },
    verifyEmailDelivery: async () => assert.fail("delivery must not run after failed smoke"),
  }), isCode);
  assert.equal(statusHeaders.get("x-hermes-session-token"), "legacy-app-token");
  assert.equal(statusHeaders.has("authorization"), false);
  assert.equal(capabilityCalls, 2);
  assert.equal(JSON.parse(await readFile(fixture.journalPath, "utf8")).stage, "rolled_back");
});

test("production rollout error is bilingual, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionAccountRollout;
  assert.equal(definition.code, "HR-OPS-020");
  assert.match(definition.summaryZh, /邮箱登录/);
  assert.match(definition.summaryEn, /email-login rollout/);
  assert.equal(definition.retryable, true);
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-020`/);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-account-rollout-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const configRoot = path.join(base, "hermes-go-config");
  const stateRoot = path.join(base, "hermes-go-state");
  const installRoot = path.join(base, "hermes-go-install");
  await mkdir(inputs, { recursive: true, mode: 0o700 });
  await mkdir(path.join(configRoot, "slots", "green"), { recursive: true });
  await mkdir(stateRoot, { recursive: true });
  await mkdir(installRoot, { recursive: true });
  const sources = {
    accountDatabaseUrlSource: path.join(inputs, "database-url"),
    accountTokenHashKeySource: path.join(inputs, "token-hash-key"),
    accountEmailOtpHashKeySource: path.join(inputs, "otp-hash-key"),
    resendApiKeySource: path.join(inputs, "resend-api-key"),
    resendWebhookSecretSource: path.join(inputs, "resend-webhook-secret"),
    emailFromSource: path.join(inputs, "email-from"),
  };
  const material = {
    accountDatabaseUrlSource: "postgresql://gateway:password@127.0.0.1:5432/hermes_go_account",
    accountTokenHashKeySource: "a".repeat(48),
    accountEmailOtpHashKeySource: "b".repeat(48),
    resendApiKeySource: "re_test_rollout_key_123456789",
    resendWebhookSecretSource: "whsec_test_rollout_secret_123456789",
    emailFromSource: "Hermes GO <login@auth.example.com>",
  };
  for (const [name, filePath] of Object.entries(sources)) {
    await writeFile(filePath, `${material[name]}\n`, { mode: 0o600 });
    await chmod(filePath, 0o600);
  }
  const appTokenSource = path.join(inputs, "app-token");
  const internalStatusTokenSource = path.join(inputs, "internal-token");
  await writeFile(appTokenSource, "legacy-app-token\n", { mode: 0o600 });
  await writeFile(internalStatusTokenSource, "internal-status-token\n", { mode: 0o600 });
  const releaseConfig = {
    environment: "production",
    managedBaseline: true,
    targetArtifactManifest: path.join(inputs, "target.manifest.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    paths: { installRoot, configRoot, stateRoot, systemdUnitDirectory: path.join(base, "systemd") },
    legacySource: { gatewayPort: 18444 },
    slots: {
      blue: { serviceName: "hermes-go-gateway-blue", containerName: "blue", gatewayPort: 18787 },
      green: { serviceName: "hermes-go-gateway-green", containerName: "green", gatewayPort: 18788 },
    },
    gateway: { defaultDeviceId: "production-mac", accountAuthEnabled: false, accountBindingEnabled: false },
    secrets: { appTokenSource, connectorTokenSource: path.join(inputs, "connector-token"), internalStatusTokenSource },
    database: null,
    nginx: {
      serverName: "gateway.example.com",
      configFile: path.join(base, "nginx", "hermes-go-production.conf"),
      upstreamConfigFile: path.join(base, "nginx", "hermes-go-production-upstream.conf"),
    },
  };
  const nginxConfig = `include ${releaseConfig.nginx.upstreamConfigFile};\nserver {\n    listen 443 ssl;\n    server_name gateway.example.com;\n    location /api/ { proxy_pass http://hermes_go_gateway_production; }\n    location / { return 404; }\n}\n`;
  await mkdir(path.dirname(releaseConfig.nginx.configFile), { recursive: true });
  await writeFile(releaseConfig.nginx.configFile, nginxConfig, { mode: 0o644 });
  const config = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    productionReleaseConfig: path.join(inputs, "production-release.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    database: { ssl: false, migrationLockId: 48203175 },
    gateway: { origin: "https://gateway.example.com", emailIssuer: "https://gateway.example.com", emailFromDomain: "auth.example.com", trustLoopbackProxy: true },
    secrets: sources,
    deployment: { observationSeconds: 1 },
  };
  const rawConfig = structuredClone(config);
  const currentManifest = {
    schemaVersion: 3,
    serverVersion: "0.4.2",
    sourceCommit: "c".repeat(40),
    imageId: `sha256:${"d".repeat(64)}`,
    containerdImageId: `sha256:${"e".repeat(64)}`,
    releaseContract: { databaseSchemaVersion: 15, supportedPostgresqlMajors: [18] },
  };
  const environmentPath = path.join(configRoot, "slots", "green", "gateway.env");
  await writeFile(environmentPath, renderDeployGatewayEnvironment(releaseConfig, "green"), { mode: 0o600 });
  const ownership = { host: currentOwnership(), secret: currentOwnership() };
  return {
    base, config, rawConfig, configPath: path.join(inputs, "account-rollout.json"), releaseConfig, nginxConfig,
    environmentPath, journalPath: path.join(stateRoot, "ops", "account-rollout.json"), material,
    dependencies: {
      confirmation: "production:prod-host", getUid: () => 0, platform: "linux", architecture: "x64", hostname: "prod-host",
      ownership, sleep: async () => {}, loadReleaseConfig: async () => releaseConfig,
      loadCurrentManifest: async () => currentManifest, loadBundleManifest: async () => currentManifest,
      resolveActiveSlot: async () => "green", verifyLoadedImage: () => ({ imageId: currentManifest.containerdImageId }),
      verifyDatabaseMigration: () => ({ required: true, schemaVersion: 15, postgresqlMajor: 18, appliedMigrations: [1, 15] }),
    },
  };
}

function runner(calls) {
  return { run(command, args) { calls.push({ command, args: [...args] }); return { status: 0, stdout: "", stderr: "" }; } };
}

function currentOwnership() { return { uid: process.getuid(), gid: process.getgid() }; }

function jsonResponse(value, init = {}) {
  return new Response(JSON.stringify(value), {
    ...init,
    headers: { "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

function enabledCapabilities() {
  return {
    accountAuth: { enabled: true, providers: ["email_otp"], identityManagement: false, webAccountCenter: false },
    binding: { enabled: false },
    legacy: { appTokenAccepted: true, connectorTokenAccepted: true },
  };
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

function isCode(error) { return error && OPS_ERROR_DEFINITIONS[error.kind]?.code === "HR-OPS-020"; }
