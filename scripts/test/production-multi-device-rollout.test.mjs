import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { hostname, tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadProductionMultiDeviceRolloutConfig } from "../../ops/lib/production-multi-device-rollout-config.mjs";
import {
  executeProductionMultiDeviceRollout,
  renderMultiDeviceNginxRoutes,
  verifyMultiDeviceSurface,
  verifySingleDeviceSurface,
} from "../../ops/lib/production-multi-device-rollout.mjs";
import { renderBindingNginxRoutes } from "../../ops/lib/production-binding-rollout.mjs";
import { renderEmailRolloutEnvironment } from "../../ops/lib/production-account-rollout.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("production multi-device rollout config is strict and protected", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, fixture.rawConfig);
  const parsed = await loadProductionMultiDeviceRolloutConfig(fixture.configPath);
  assert.equal(parsed.gateway.origin, "https://gateway.example.com");
  assert.equal(parsed.gateway.runtimeContract, "hermes-serve-v1");
  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadProductionMultiDeviceRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, fixture.rawConfig);
  await chmod(fixture.configPath, 0o644);
  await assert.rejects(() => loadProductionMultiDeviceRolloutConfig(fixture.configPath), isCode);
});

test("multi-device Nginx routes expose selection and explicit traffic without sharing", () => {
  const routes = renderMultiDeviceNginxRoutes();
  assert.match(routes, /location = \/v2\/connector-binding/);
  assert.match(routes, /location \^~ \/v2\/connector-binding\//);
  assert.match(routes, /location = \/v2\/connect/);
  assert.match(routes, /location = \/v2\/devices/);
  assert.match(routes, /select-default\$/);
  assert.match(routes, /\/api\(\?:\/\|\$\)/);
  assert.match(routes, /\/ws\$/);
  assert.match(routes, /proxy_set_header Upgrade \$http_upgrade/);
  for (const forbidden of ["share-invitations", "/shares", "/leave", "/v2/installations", "/v2/web/"]) {
    assert.equal(routes.includes(forbidden), false);
  }
});

test("production multi-device rollout advances only the plural-device capability", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  let enabledChecks = 0;
  const result = await executeProductionMultiDeviceRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; return "device_offline"; },
    verifyEnabled: async ({ expectedLegacyState }) => {
      enabledChecks += 1;
      assert.equal(expectedLegacyState, "device_offline");
    },
  });
  assert.equal(result.stage, "committed");
  assert.equal(result.bindingEnabled, true);
  assert.equal(result.multiDeviceEnabled, true);
  assert.equal(result.desktopManagedInstallEnabled, true);
  assert.equal(previousChecks, 1);
  assert.equal(enabledChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 1);
  const environment = await readFile(fixture.environmentPath, "utf8");
  assert.match(environment, /^ACCOUNT_MULTI_DEVICE_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_DEVICE_SHARING_ENABLED=0$/m);
  assert.match(environment, /^ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=0$/m);
  assert.equal(await readFile(fixture.nginxConfigPath, "utf8"), fixture.nginxConfig);
  assert.equal(await readFile(fixture.bindingRoutesPath, "utf8"), renderMultiDeviceNginxRoutes());
  const journal = JSON.parse(await readFile(fixture.multiDeviceJournalPath, "utf8"));
  assert.equal(journal.stage, "committed");
  assert.equal(journal.multiDeviceEnabled, true);
  await assert.rejects(
    () => readFile(path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock")),
    (error) => error?.code === "ENOENT",
  );
});

test("live verification pins capabilities, device guards, Legacy, and both WebSocket surfaces", async (t) => {
  const fixture = await createFixture(t);
  let multiDeviceEnabled = true;
  let ready = false;
  let transientPublicFailures = 1;
  let publicCapabilityAttempts = 0;
  const fetchImpl = async (url, init = {}) => {
    const parsed = new URL(url);
    if (parsed.pathname === "/v2/capabilities") {
      if (parsed.protocol === "https:") {
        publicCapabilityAttempts += 1;
        if (!ready || transientPublicFailures-- > 0) return new Response("starting", { status: 503 });
      }
      return ready ? jsonResponse(capabilities(multiDeviceEnabled)) : new Response("starting", { status: 503 });
    }
    if (parsed.pathname === "/v2/account" || parsed.pathname === "/v2/connector-binding") {
      return new Response("{}", { status: 401 });
    }
    if (parsed.pathname === "/v2/devices") {
      return new Response("{}", { status: multiDeviceEnabled ? 401 : 404 });
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
    runner: runner([]),
    material: { appToken: "legacy-app-token", internalStatusToken: "internal-status-token" },
    probeWebSocket: async () => true,
  };
  await verifyMultiDeviceSurface({ ...request, probeDeviceWebSocket: async () => 401 });
  assert.equal(publicCapabilityAttempts, 2);
  multiDeviceEnabled = false;
  ready = false;
  transientPublicFailures = 1;
  await verifySingleDeviceSurface({ ...request, probeDeviceWebSocket: async () => 404 });
  assert.equal(publicCapabilityAttempts, 4);
});

test("a failed verification restores exact single-device environment and routes", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  await assert.rejects(() => executeProductionMultiDeviceRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => { throw new Error("synthetic_multi_device_smoke_failure"); },
  }), isCode);
  assert.equal(previousChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 2);
  assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.bindingEnvironment);
  assert.equal(await readFile(fixture.bindingRoutesPath, "utf8"), fixture.bindingRoutes);
  assert.equal(await readFile(fixture.nginxConfigPath, "utf8"), fixture.nginxConfig);
  assert.equal(JSON.parse(await readFile(fixture.multiDeviceJournalPath, "utf8")).stage, "rolled_back");
});

test("multi-device rollout refuses checkpoint, route/include, origin, and lock drift", async (t) => {
  const fixture = await createFixture(t);
  const checkpoint = JSON.parse(await readFile(fixture.bindingJournalPath, "utf8"));
  await writeJson(fixture.bindingJournalPath, { ...checkpoint, stage: "rolled_back" });
  await assert.rejects(() => executeProductionMultiDeviceRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  }), isCode);
  await writeJson(fixture.bindingJournalPath, checkpoint);

  await writeFile(fixture.bindingRoutesPath, `${fixture.bindingRoutes}# drift\n`);
  await assert.rejects(() => executeProductionMultiDeviceRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]),
  }), isCode);
  await writeFile(fixture.bindingRoutesPath, fixture.bindingRoutes);

  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig.replace(`    include ${fixture.bindingRoutesPath};\n`, ""));
  await assert.rejects(() => executeProductionMultiDeviceRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]),
  }), isCode);
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig);

  await assert.rejects(() => executeProductionMultiDeviceRollout({
    ...fixture.config,
    gateway: { ...fixture.config.gateway, origin: "https://other.example.com" },
  }, { ...fixture.dependencies, runner: runner([]) }), isCode);

  const lockPath = path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock");
  await writeJson(lockPath, {
    schemaVersion: 2,
    runId: "competing-production-operation",
    nonce: "competing-lock",
    pid: process.pid,
    hostname: hostname(),
  });
  await assert.rejects(() => executeProductionMultiDeviceRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  }), (error) => isCode(error) && /lock_unavailable/.test(error.technicalCause));
  await assert.rejects(() => readFile(fixture.multiDeviceJournalPath), (error) => error?.code === "ENOENT");
});

test("production multi-device rollout error is bilingual, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionMultiDeviceRollout;
  assert.equal(definition.code, "HR-OPS-022");
  assert.match(definition.summaryZh, /多终端灰度/);
  assert.match(definition.summaryEn, /multi-device rollout/);
  assert.equal(definition.retryable, true);
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-022`/);
  const entrypoint = spawnSync(process.execPath, ["scripts/production-multi-device-rollout.mjs"], {
    encoding: "utf8",
    env: {},
    shell: false,
  });
  assert.equal(entrypoint.status, 1);
  assert.equal(entrypoint.stdout, "");
  const payload = JSON.parse(entrypoint.stderr);
  assert.equal(payload.code, "HR-OPS-022");
  assert.equal(payload.stage, "production_multi_device_rollout_arguments");
  assert.equal(payload.retryable, true);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-multi-device-rollout-test-")));
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
  const bindingRoutesPath = path.join(configRoot, "account", "binding-routes.conf");
  const nginxConfig = `include ${releaseConfig.nginx.upstreamConfigFile};\nserver {\n    listen 443 ssl;\n    server_name gateway.example.com;\n\n    include ${emailRoutesPath};\n    include ${bindingRoutesPath};\n    location /api/ { proxy_pass http://hermes_go_gateway_production; }\n    location / { return 404; }\n}\n`;
  await mkdir(path.dirname(releaseConfig.nginx.configFile), { recursive: true });
  await mkdir(path.dirname(emailRoutesPath), { recursive: true });
  await writeFile(releaseConfig.nginx.configFile, nginxConfig, { mode: 0o644 });
  await writeFile(emailRoutesPath, "# email routes\n", { mode: 0o644 });
  const bindingRoutes = renderBindingNginxRoutes();
  await writeFile(bindingRoutesPath, bindingRoutes, { mode: 0o644 });
  const accountConfig = {
    gateway: { emailIssuer: "https://gateway.example.com", origin: "https://gateway.example.com", trustLoopbackProxy: true },
    database: { ssl: false },
  };
  const emailEnvironment = renderEmailRolloutEnvironment(releaseConfig, accountConfig, "green");
  const bindingEnvironment = emailEnvironment
    .replace("ACCOUNT_BINDING_ENABLED=0", "ACCOUNT_BINDING_ENABLED=1")
    .replace("ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=0", "ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED=1");
  const environmentPath = path.join(configRoot, "slots", "green", "gateway.env");
  await writeFile(environmentPath, bindingEnvironment, { mode: 0o600 });
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
  const bindingJournalPath = path.join(stateRoot, "ops", "binding-rollout.json");
  await writeJson(bindingJournalPath, {
    schemaVersion: 1,
    kind: "hermes-go-production-binding-rollout-v1",
    runId: "binding-run",
    stage: "committed",
    activeSlot: "green",
    serverVersion: "0.4.14",
    sourceCommit: "a".repeat(40),
    databaseSchemaVersion: 15,
    runtimeContract: "hermes-serve-v1",
    multiDeviceEnabled: false,
    updatedAt: "2026-09-10T00:00:00.000Z",
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
    config,
    rawConfig: structuredClone(config),
    configPath: path.join(inputs, "multi-device-rollout.json"),
    releaseConfig,
    currentManifest,
    nginxConfig,
    nginxConfigPath: releaseConfig.nginx.configFile,
    bindingEnvironment,
    environmentPath,
    bindingJournalPath,
    multiDeviceJournalPath: path.join(stateRoot, "ops", "multi-device-rollout.json"),
    bindingRoutes,
    bindingRoutesPath,
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
      probeWebSocket: async () => true,
      probeDeviceWebSocket: async () => 404,
    },
  };
}

function runner(calls) {
  return { run(command, args) { calls.push({ command, args: [...args] }); return { status: 0, stdout: "", stderr: "" }; } };
}

function capabilities(multiDeviceEnabled) {
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
      enabled: true,
      replacement: true,
      maxActiveConnectorsPerAccount: multiDeviceEnabled ? 3 : 1,
      ...(multiDeviceEnabled ? { supportsDeviceSelection: true } : {}),
    },
    legacy: { appTokenAccepted: true, connectorTokenAccepted: true },
    desktopBootstrap: { runtimeContract: "hermes-serve-v1" },
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
  return error && OPS_ERROR_DEFINITIONS[error.kind]?.code === "HR-OPS-022";
}
