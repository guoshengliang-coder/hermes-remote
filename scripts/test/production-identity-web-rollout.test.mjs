import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { hostname, tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadProductionIdentityWebRolloutConfig } from "../../ops/lib/production-identity-web-rollout-config.mjs";
import {
  executeProductionIdentityWebRollout,
  renderIdentityWebNginxRoutes,
  verifyIdentityWebSurface,
  verifyPreviousMultiDeviceSurface,
} from "../../ops/lib/production-identity-web-rollout.mjs";
import { renderMultiDeviceNginxRoutes } from "../../ops/lib/production-multi-device-rollout.mjs";
import { renderEmailRolloutEnvironment } from "../../ops/lib/production-account-rollout.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderBindingRolloutEnvironment,
  renderMultiDeviceRolloutEnvironment,
} from "../../ops/lib/production-release-environment.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("production identity-web rollout config is strict and protected", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, fixture.rawConfig);
  const parsed = await loadProductionIdentityWebRolloutConfig(fixture.configPath);
  assert.equal(parsed.gateway.origin, "https://gateway.example.com");
  assert.equal(parsed.gateway.runtimeContract, "hermes-serve-v1");
  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadProductionIdentityWebRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, fixture.rawConfig);
  await chmod(fixture.configPath, 0o644);
  await assert.rejects(() => loadProductionIdentityWebRolloutConfig(fixture.configPath), isCode);
});

test("identity-Web Nginx routes expose only the reviewed email identity and account-center surface", () => {
  const routes = renderIdentityWebNginxRoutes();
  assert.match(routes, /location = \/account/);
  assert.match(routes, /location = \/v2\/web\/session/);
  assert.match(routes, /location = \/v2\/web\/identities/);
  assert.match(routes, /location = \/v2\/account\/identities/);
  assert.match(routes, /location = \/v2\/installations/);
  assert.match(routes, /select-default\$/);
  for (const forbidden of ["share-invitations", "/shares", "/leave", "/google/"]) {
    assert.equal(routes.includes(forbidden), false);
  }
});

test("production identity-Web rollout enables only identity management and the Web account center", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  let enabledChecks = 0;
  const result = await executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => { enabledChecks += 1; },
  });
  assert.equal(result.stage, "committed");
  assert.equal(result.bindingEnabled, true);
  assert.equal(result.multiDeviceEnabled, true);
  assert.equal(result.identityWebEnabled, true);
  assert.equal(result.desktopManagedInstallEnabled, true);
  assert.equal(previousChecks, 1);
  assert.equal(enabledChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 1);
  const environment = await readFile(fixture.environmentPath, "utf8");
  assert.match(environment, /^ACCOUNT_MULTI_DEVICE_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_DEVICE_SHARING_ENABLED=0$/m);
  assert.match(environment, /^ACCOUNT_IDENTITY_MANAGEMENT_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_WEB_SESSION_ENABLED=1$/m);
  assert.match(environment, /^ACCOUNT_GOOGLE_AUTH_ENABLED=0$/m);
  assert.match(environment, /^ACCOUNT_DELETION_ENABLED=0$/m);
  assert.equal(await readFile(fixture.bindingRoutesPath, "utf8"), fixture.bindingRoutes);
  assert.equal(await readFile(fixture.identityWebRoutesPath, "utf8"), renderIdentityWebNginxRoutes());
  assert.match(await readFile(fixture.nginxConfigPath, "utf8"), /identity-web-routes\.conf/);
  const journal = JSON.parse(await readFile(fixture.identityWebJournalPath, "utf8"));
  assert.equal(journal.stage, "committed");
  assert.equal(journal.identityWebEnabled, true);
  await assert.rejects(
    () => readFile(path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock")),
    (error) => error?.code === "ENOENT",
  );
});

test("live verification pins Web security, route guards, multi-device, Legacy, and release identity", async (t) => {
  const fixture = await createFixture(t);
  let identityWebEnabled = true;
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
      return ready ? jsonResponse(capabilities(identityWebEnabled)) : new Response("starting", { status: 503 });
    }
    if (parsed.pathname === "/v2/account" || parsed.pathname === "/v2/connector-binding") {
      return new Response("{}", { status: 401 });
    }
    if (parsed.pathname === "/v2/devices") {
      return new Response("{}", { status: 401 });
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
    if (parsed.pathname === "/account") {
      return identityWebEnabled
        ? new Response("<!doctype html>", { status: 200, headers: {
            "content-type": "text/html; charset=utf-8",
            "cache-control": "no-store",
            "content-security-policy": "default-src 'none'; script-src 'self'; frame-ancestors 'none'",
          } })
        : new Response("not found", { status: 404 });
    }
    if (parsed.pathname === "/account/assets/account.css" || parsed.pathname === "/account/assets/account.js") {
      return new Response("asset", { status: identityWebEnabled ? 200 : 404, headers: { "cache-control": "no-store" } });
    }
    if (parsed.pathname === "/v2/web/session") {
      return identityWebEnabled ? jsonResponse({ session: { authenticated: false }, csrfToken: `hgc_${"A".repeat(43)}` }, {
        "set-cookie": "__Host-hermes_go_installation=id; Secure; HttpOnly; SameSite=Strict, __Host-hermes_go_csrf=token; Secure; SameSite=Strict",
      }) : new Response("not found", { status: 404 });
    }
    if (parsed.pathname === "/v2/web/auth/email/challenges") return new Response("{}", { status: 403 });
    if (["/v2/web/identities", "/v2/web/installations", "/v2/account/identities", "/v2/installations"].includes(parsed.pathname)) {
      return new Response("{}", { status: identityWebEnabled ? 401 : 404 });
    }
    if (parsed.pathname === "/v2/web/devices/probe-device/shares") {
      return new Response("not found", { status: parsed.protocol === "https:" ? 404 : 503 });
    }
    if (["/v2/web/auth/google/exchange"].includes(parsed.pathname)
        || parsed.pathname.includes("share-invitations")
        || (parsed.pathname === "/v2/web/account" && init.method === "DELETE")) {
      return new Response("method not allowed", { status: 405 });
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
  await verifyIdentityWebSurface({ ...request, probeDeviceWebSocket: async () => 401 });
  assert.equal(publicCapabilityAttempts, 2);
  identityWebEnabled = false;
  ready = false;
  transientPublicFailures = 1;
  await verifyPreviousMultiDeviceSurface({ ...request, probeDeviceWebSocket: async () => 401 });
  assert.equal(publicCapabilityAttempts, 4);
});

test("a failed verification restores exact multi-device environment, routes, and site", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  await assert.rejects(() => executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => { throw new Error("synthetic_identity_web_smoke_failure"); },
  }), isCode);
  assert.equal(previousChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 2);
  assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.multiDeviceEnvironment);
  assert.equal(await readFile(fixture.bindingRoutesPath, "utf8"), fixture.bindingRoutes);
  assert.equal(await readFile(fixture.nginxConfigPath, "utf8"), fixture.nginxConfig);
  await assert.rejects(() => readFile(fixture.identityWebRoutesPath), (error) => error?.code === "ENOENT");
  assert.equal(JSON.parse(await readFile(fixture.identityWebJournalPath, "utf8")).stage, "rolled_back");
});

test("identity-web rollout refuses checkpoint, route/include, origin, and lock drift", async (t) => {
  const fixture = await createFixture(t);
  const checkpoint = JSON.parse(await readFile(fixture.multiDeviceJournalPath, "utf8"));
  await writeJson(fixture.multiDeviceJournalPath, { ...checkpoint, stage: "rolled_back" });
  await assert.rejects(() => executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  }), isCode);
  await writeJson(fixture.multiDeviceJournalPath, checkpoint);

  await writeFile(fixture.bindingRoutesPath, `${fixture.bindingRoutes}# drift\n`);
  await assert.rejects(() => executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]),
  }), isCode);
  await writeFile(fixture.bindingRoutesPath, fixture.bindingRoutes);

  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig.replace(`    include ${fixture.bindingRoutesPath};\n`, ""));
  await assert.rejects(() => executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]),
  }), isCode);
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig);

  await writeFile(
    fixture.nginxConfigPath,
    fixture.nginxConfig.replace(
      `    include ${fixture.bindingRoutesPath};`,
      `    include ${fixture.bindingRoutesPath};\n    include ${fixture.identityWebRoutesPath};`,
    ),
  );
  await assert.rejects(() => executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  }), isCode);
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig);

  await assert.rejects(() => executeProductionIdentityWebRollout({
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
  await assert.rejects(() => executeProductionIdentityWebRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  }), (error) => isCode(error) && /lock_unavailable/.test(error.technicalCause));
  await assert.rejects(() => readFile(fixture.identityWebJournalPath), (error) => error?.code === "ENOENT");
});

test("production identity-web rollout error is bilingual, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionIdentityWebRollout;
  assert.equal(definition.code, "HR-OPS-023");
  assert.match(definition.summaryZh, /身份与 Web 账号中心灰度/);
  assert.match(definition.summaryEn, /identity and Web account-center rollout/);
  assert.equal(definition.retryable, true);
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-023`/);
  const entrypoint = spawnSync(process.execPath, ["scripts/production-identity-web-rollout.mjs"], {
    encoding: "utf8",
    env: {},
    shell: false,
  });
  assert.equal(entrypoint.status, 1);
  assert.equal(entrypoint.stdout, "");
  const payload = JSON.parse(entrypoint.stderr);
  assert.equal(payload.code, "HR-OPS-023");
  assert.equal(payload.stage, "production_identity_web_rollout_arguments");
  assert.equal(payload.retryable, true);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-identity-web-rollout-test-")));
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
  const bindingRoutes = renderMultiDeviceNginxRoutes();
  await writeFile(bindingRoutesPath, bindingRoutes, { mode: 0o644 });
  const accountConfig = {
    gateway: { emailIssuer: "https://gateway.example.com", origin: "https://gateway.example.com", trustLoopbackProxy: true },
    database: { ssl: false },
  };
  const emailEnvironment = renderEmailRolloutEnvironment(releaseConfig, accountConfig, "green");
  await writeFile(path.join(configRoot, "slots", "green", "gateway.env"), emailEnvironment, { mode: 0o600 });
  const email = await inspectProductionReleaseEnvironment(releaseConfig, "green");
  const bindingEnvironment = renderBindingRolloutEnvironment(releaseConfig, "green", email);
  await writeFile(path.join(configRoot, "slots", "green", "gateway.env"), bindingEnvironment, { mode: 0o600 });
  const binding = await inspectProductionReleaseEnvironment(releaseConfig, "green");
  const multiDeviceEnvironment = renderMultiDeviceRolloutEnvironment(releaseConfig, "green", binding);
  const environmentPath = path.join(configRoot, "slots", "green", "gateway.env");
  await writeFile(environmentPath, multiDeviceEnvironment, { mode: 0o600 });
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
  const multiDeviceJournalPath = path.join(stateRoot, "ops", "multi-device-rollout.json");
  await writeJson(multiDeviceJournalPath, {
    schemaVersion: 1,
    kind: "hermes-go-production-multi-device-rollout-v1",
    runId: "multi-device-run",
    stage: "committed",
    activeSlot: "green",
    serverVersion: "0.4.14",
    sourceCommit: currentManifest.sourceCommit,
    databaseSchemaVersion: 15,
    runtimeContract: "hermes-serve-v1",
    multiDeviceEnabled: true,
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
    configPath: path.join(inputs, "identity-web-rollout.json"),
    releaseConfig,
    currentManifest,
    nginxConfig,
    nginxConfigPath: releaseConfig.nginx.configFile,
    multiDeviceEnvironment,
    environmentPath,
    multiDeviceJournalPath,
    identityWebJournalPath: path.join(stateRoot, "ops", "identity-web-rollout.json"),
    bindingRoutes,
    bindingRoutesPath,
    identityWebRoutesPath: path.join(configRoot, "account", "identity-web-routes.conf"),
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
      probeDeviceWebSocket: async () => 401,
    },
  };
}

function runner(calls) {
  return { run(command, args) { calls.push({ command, args: [...args] }); return { status: 0, stdout: "", stderr: "" }; } };
}

function capabilities(identityWebEnabled) {
  return {
    accountAuth: {
      enabled: true,
      providers: ["email_otp"],
      android: true,
      macos: true,
      identityManagement: identityWebEnabled,
      webAccountCenter: identityWebEnabled,
      ...(identityWebEnabled ? { webSessions: true } : {}),
    },
    binding: {
      enabled: true,
      replacement: true,
      maxActiveConnectorsPerAccount: 3,
      supportsDeviceSelection: true,
    },
    legacy: { appTokenAccepted: true, connectorTokenAccepted: true },
    desktopBootstrap: { runtimeContract: "hermes-serve-v1" },
  };
}

function jsonResponse(value, headers = {}) {
  return new Response(JSON.stringify(value), { headers: { "content-type": "application/json", ...headers } });
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

function isCode(error) {
  return error && OPS_ERROR_DEFINITIONS[error.kind]?.code === "HR-OPS-023";
}
