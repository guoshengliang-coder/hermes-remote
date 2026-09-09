import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { hostname, tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadProductionBindingRolloutConfig } from "../../ops/lib/production-binding-rollout-config.mjs";
import {
  executeProductionBindingRollout,
  installBindingNginxInclude,
  renderBindingNginxRoutes,
  verifyBindingSurface,
  verifyEmailOnlySurface,
} from "../../ops/lib/production-binding-rollout.mjs";
import { renderEmailRolloutEnvironment } from "../../ops/lib/production-account-rollout.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("production binding rollout config is strict, protected, and pins hermes-serve-v1", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, fixture.rawConfig);
  const parsed = await loadProductionBindingRolloutConfig(fixture.configPath);
  assert.equal(parsed.environment, "production");
  assert.equal(parsed.gateway.origin, "https://gateway.example.com");
  assert.equal(parsed.gateway.runtimeContract, "hermes-serve-v1");

  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadProductionBindingRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, fixture.rawConfig);
  await chmod(fixture.configPath, 0o644);
  await assert.rejects(() => loadProductionBindingRolloutConfig(fixture.configPath), isCode);
});

test("binding Nginx include exposes only singular binding HTTP and V2 Connector WebSocket", async (t) => {
  const fixture = await createFixture(t);
  const routes = renderBindingNginxRoutes();
  assert.match(routes, /location = \/v2\/connector-binding/);
  assert.match(routes, /location \^~ \/v2\/connector-binding\//);
  assert.match(routes, /location = \/v2\/connect/);
  assert.match(routes, /proxy_set_header Upgrade \$http_upgrade/);
  for (const forbidden of ["/v2/devices", "/v2/installations", "/v2/auth/google", "/v2/web/"]) {
    assert.equal(routes.includes(forbidden), false);
  }
  const routesPath = path.join(fixture.releaseConfig.paths.configRoot, "account", "binding-routes.conf");
  const installed = installBindingNginxInclude(fixture.nginxConfig, fixture.releaseConfig, routesPath);
  assert.equal(installed.includes(`include ${routesPath};`), true);
  assert.throws(() => installBindingNginxInclude(installed, fixture.releaseConfig, routesPath), isCode);
});

test("production binding rollout accepts the original email checkpoint after preserved routine releases", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let emailChecks = 0;
  let bindingChecks = 0;
  const result = await executeProductionBindingRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyEmailOnly: async () => { emailChecks += 1; },
    verifyEnabled: async () => { bindingChecks += 1; },
  });
  assert.equal(result.stage, "committed");
  assert.equal(result.bindingEnabled, true);
  assert.equal(result.multiDeviceEnabled, false);
  assert.equal(result.desktopManagedInstallEnabled, true);
  assert.equal(result.runtimeContract, "hermes-serve-v1");
  assert.equal(emailChecks, 1);
  assert.equal(bindingChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 1);
  const environment = await readFile(fixture.environmentPath, "utf8");
  assert.match(environment, /^ACCOUNT_BINDING_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_MULTI_DEVICE_ENABLED=0$/m);
  assert.match(environment, /^ACCOUNT_DEVICE_SHARING_ENABLED=0$/m);
  const journal = JSON.parse(await readFile(fixture.bindingJournalPath, "utf8"));
  assert.equal(journal.stage, "committed");
  assert.equal(journal.runtimeContract, "hermes-serve-v1");
  await assert.rejects(
    () => readFile(path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock")),
    (error) => error?.code === "ENOENT",
  );
});

test("live surface verification pins readiness, legacy continuity, routes, and WebSocket state", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let bindingEnabled = true;
  let ready = false;
  let publicCapabilityAttempts = 0;
  let transientPublicFailures = 1;
  const fetchImpl = async (url, init = {}) => {
    const parsed = new URL(url);
    if (parsed.pathname === "/v2/capabilities") {
      if (parsed.protocol === "https:") {
        publicCapabilityAttempts += 1;
        if (!ready || transientPublicFailures-- > 0) {
          return new Response("starting", { status: 503 });
        }
      }
      return ready ? jsonResponse(capabilities(bindingEnabled)) : new Response("starting", { status: 503 });
    }
    if (parsed.pathname === "/v2/account") return new Response("{}", { status: 401 });
    if (parsed.pathname === "/v2/connector-binding") {
      if (bindingEnabled) return new Response("{}", { status: 401 });
      return new Response("{}", { status: parsed.protocol === "https:" ? 404 : 503 });
    }
    if (parsed.pathname === "/readyz") {
      ready = true;
      return jsonResponse({
        status: "ready",
        checks: { database: "ok", migrations: "ok", postgresql: "supported" },
      });
    }
    if (parsed.pathname === "/api/status") {
      assert.equal(new Headers(init.headers).get("x-hermes-session-token"), "legacy-app-token");
      return jsonResponse({ overall: "ok", gateway_running: true });
    }
    if (parsed.pathname === "/internal/version") {
      assert.equal(new Headers(init.headers).get("authorization"), "Bearer internal-status-token");
      return jsonResponse({
        serverVersion: fixture.currentManifest.serverVersion,
        sourceCommit: fixture.currentManifest.sourceCommit,
      });
    }
    assert.fail(`unexpected URL ${url}`);
  };
  const request = {
    config: fixture.config,
    releaseConfig: fixture.releaseConfig,
    activeSlot: "green",
    currentManifest: fixture.currentManifest,
    fetchImpl,
    sleep: async () => {},
    runner: runner(calls),
    material: { appToken: "legacy-app-token", internalStatusToken: "internal-status-token" },
  };
  await verifyBindingSurface({ ...request, probeWebSocket: async () => true });
  assert.equal(publicCapabilityAttempts, 2);
  bindingEnabled = false;
  ready = false;
  transientPublicFailures = 1;
  await verifyEmailOnlySurface({ ...request, probeWebSocket: async () => false });
  assert.equal(publicCapabilityAttempts, 4);
});

test("a failed binding verification restores exact email-only environment and Nginx", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let emailChecks = 0;
  await assert.rejects(() => executeProductionBindingRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyEmailOnly: async () => { emailChecks += 1; },
    verifyEnabled: async () => { throw new Error("synthetic_binding_smoke_failure"); },
  }), isCode);
  assert.equal(emailChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 2);
  assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.emailEnvironment);
  assert.equal(await readFile(fixture.releaseConfig.nginx.configFile, "utf8"), fixture.nginxConfig);
  await assert.rejects(() => readFile(fixture.bindingRoutesPath), (error) => error?.code === "ENOENT");
  assert.equal(JSON.parse(await readFile(fixture.bindingJournalPath, "utf8")).stage, "rolled_back");
});

test("binding rollout refuses an uncommitted email checkpoint", async (t) => {
  const fixture = await createFixture(t);
  const checkpoint = JSON.parse(await readFile(fixture.accountJournalPath, "utf8"));
  await writeJson(fixture.accountJournalPath, { ...checkpoint, stage: "rolled_back" });
  await assert.rejects(() => executeProductionBindingRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyEmailOnly: async () => {},
    verifyEnabled: async () => {},
  }), isCode);
  await assert.rejects(() => readFile(fixture.bindingJournalPath), (error) => error?.code === "ENOENT");
});

test("binding rollout refuses a public origin different from the production Nginx endpoint", async (t) => {
  const fixture = await createFixture(t);
  await assert.rejects(() => executeProductionBindingRollout({
    ...fixture.config,
    gateway: { ...fixture.config.gateway, origin: "https://other.example.com" },
  }, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyEmailOnly: async () => {},
    verifyEnabled: async () => {},
  }), isCode);
  await assert.rejects(() => readFile(fixture.bindingJournalPath), (error) => error?.code === "ENOENT");
});

test("binding rollout refuses a concurrent production deployment before creating its journal", async (t) => {
  const fixture = await createFixture(t);
  const lockPath = path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock");
  await writeJson(lockPath, {
    schemaVersion: 2,
    runId: "competing-production-operation",
    nonce: "competing-lock",
    pid: process.pid,
    hostname: hostname(),
  });
  await assert.rejects(() => executeProductionBindingRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyEmailOnly: async () => {},
    verifyEnabled: async () => {},
  }), (error) => isCode(error) && /lock_unavailable/.test(error.technicalCause));
  await assert.rejects(() => readFile(fixture.bindingJournalPath), (error) => error?.code === "ENOENT");
});

test("production binding rollout error is bilingual, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionBindingRollout;
  assert.equal(definition.code, "HR-OPS-021");
  assert.match(definition.summaryZh, /绑定灰度/);
  assert.match(definition.summaryEn, /Desktop-binding rollout/);
  assert.equal(definition.retryable, true);
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-021`/);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-binding-rollout-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const configRoot = path.join(base, "config");
  const stateRoot = path.join(base, "state");
  const installRoot = path.join(base, "install");
  await mkdir(inputs, { recursive: true, mode: 0o700 });
  await mkdir(path.join(configRoot, "slots", "green"), { recursive: true });
  await mkdir(path.join(stateRoot, "ops"), { recursive: true });
  await mkdir(installRoot, { recursive: true });
  const appTokenSource = path.join(inputs, "app-token");
  const internalStatusTokenSource = path.join(inputs, "internal-token");
  await writeFile(appTokenSource, "legacy-app-token\n", { mode: 0o600 });
  await writeFile(internalStatusTokenSource, "internal-status-token\n", { mode: 0o600 });
  await chmod(appTokenSource, 0o600);
  await chmod(internalStatusTokenSource, 0o600);
  const releaseConfig = {
    environment: "production",
    managedBaseline: true,
    targetArtifactManifest: path.join(inputs, "target.manifest.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    paths: { installRoot, configRoot, stateRoot, systemdUnitDirectory: path.join(base, "systemd") },
    legacySource: { serviceName: "legacy", gatewayPort: 18444 },
    slots: {
      blue: { serviceName: "gateway-blue", containerName: "blue", gatewayPort: 18787 },
      green: { serviceName: "gateway-green", containerName: "green", gatewayPort: 18788 },
    },
    gateway: { defaultDeviceId: "production-mac", accountAuthEnabled: false, accountBindingEnabled: false },
    secrets: { appTokenSource, connectorTokenSource: path.join(inputs, "connector-token"), internalStatusTokenSource },
    database: null,
    nginx: {
      serverName: "gateway.example.com",
      listenPort: 443,
      configFile: path.join(base, "nginx", "production.conf"),
      upstreamConfigFile: path.join(base, "nginx", "upstream.conf"),
    },
  };
  const emailRoutesPath = path.join(configRoot, "account", "email-login-routes.conf");
  const nginxConfig = `include ${releaseConfig.nginx.upstreamConfigFile};\nserver {\n    listen 443 ssl;\n    server_name gateway.example.com;\n\n    include ${emailRoutesPath};\n    location /api/ { proxy_pass http://hermes_go_gateway_production; }\n    location / { return 404; }\n}\n`;
  await mkdir(path.dirname(releaseConfig.nginx.configFile), { recursive: true });
  await mkdir(path.dirname(emailRoutesPath), { recursive: true });
  await writeFile(releaseConfig.nginx.configFile, nginxConfig, { mode: 0o644 });
  await writeFile(emailRoutesPath, "# email routes\n", { mode: 0o644 });
  const accountConfig = {
    gateway: { emailIssuer: "https://gateway.example.com", origin: "https://gateway.example.com", trustLoopbackProxy: true },
    database: { ssl: false },
  };
  const emailEnvironment = renderEmailRolloutEnvironment(releaseConfig, accountConfig, "green");
  const environmentPath = path.join(configRoot, "slots", "green", "gateway.env");
  await writeFile(environmentPath, emailEnvironment, { mode: 0o600 });
  await chmod(environmentPath, 0o600);
  const currentManifest = {
    schemaVersion: 3,
    serverVersion: "0.4.14",
    sourceCommit: "c".repeat(40),
    imageId: `sha256:${"d".repeat(64)}`,
    containerdImageId: `sha256:${"e".repeat(64)}`,
    archiveSha256: "f".repeat(64),
    releaseContract: { databaseSchemaVersion: 15, supportedPostgresqlMajors: [18] },
  };
  const accountJournalPath = path.join(stateRoot, "ops", "account-rollout.json");
  await writeJson(accountJournalPath, {
    schemaVersion: 1,
    kind: "hermes-go-production-account-rollout-v1",
    runId: "email-run",
    stage: "committed",
    activeSlot: "green",
    serverVersion: "0.4.9",
    sourceCommit: "a".repeat(40),
    databaseSchemaVersion: 15,
    migration: {},
    updatedAt: "2026-09-09T00:00:00.000Z",
  });
  const config = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    productionReleaseConfig: path.join(inputs, "production-release.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    gateway: { origin: "https://gateway.example.com", runtimeContract: "hermes-serve-v1" },
    deployment: { observationSeconds: 1 },
  };
  const ownership = { host: { uid: process.getuid(), gid: process.getgid() } };
  return {
    base,
    config,
    rawConfig: structuredClone(config),
    configPath: path.join(inputs, "binding-rollout.json"),
    releaseConfig,
    currentManifest,
    nginxConfig,
    emailEnvironment,
    environmentPath,
    accountJournalPath,
    bindingJournalPath: path.join(stateRoot, "ops", "binding-rollout.json"),
    bindingRoutesPath: path.join(configRoot, "account", "binding-routes.conf"),
    dependencies: {
      confirmation: "production:prod-host",
      getUid: () => 0,
      platform: "linux",
      architecture: "x64",
      hostname: "prod-host",
      ownership,
      sleep: async () => {},
      loadReleaseConfig: async () => releaseConfig,
      loadCurrentManifest: async () => currentManifest,
      loadBundleManifest: async () => currentManifest,
      resolveActiveSlot: async () => "green",
      probeWebSocket: async () => false,
    },
  };
}

function runner(calls) {
  return { run(command, args) { calls.push({ command, args: [...args] }); return { status: 0, stdout: "", stderr: "" }; } };
}

function capabilities(bindingEnabled) {
  return {
    accountAuth: {
      enabled: true,
      providers: ["email_otp"],
      android: true,
      macos: true,
      identityManagement: false,
      webAccountCenter: false,
    },
    binding: {
      enabled: bindingEnabled,
      replacement: bindingEnabled,
      maxActiveConnectorsPerAccount: 1,
    },
    legacy: { appTokenAccepted: true, connectorTokenAccepted: true },
    ...(bindingEnabled ? { desktopBootstrap: { runtimeContract: "hermes-serve-v1" } } : {}),
  };
}

function jsonResponse(value) {
  return new Response(JSON.stringify(value), { headers: { "content-type": "application/json" } });
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

function isCode(error) {
  return error && OPS_ERROR_DEFINITIONS[error.kind]?.code === "HR-OPS-021";
}
