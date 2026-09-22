import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, symlink, unlink, writeFile } from "node:fs/promises";
import { hostname, tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadProductionWebAppRolloutConfig } from "../../ops/lib/production-web-app-rollout-config.mjs";
import {
  executeProductionWebAppRollout,
  renderWebAppNginxRoutes,
  verifyPreviousComponentSurface,
  verifyWebAppSurface,
} from "../../ops/lib/production-web-app-rollout.mjs";
import { renderMultiDeviceNginxRoutes } from "../../ops/lib/production-multi-device-rollout.mjs";
import { renderIdentityWebNginxRoutes } from "../../ops/lib/production-identity-web-rollout.mjs";
import { renderSharingNginxRoutes } from "../../ops/lib/production-sharing-rollout.mjs";
import { renderEmailRolloutEnvironment } from "../../ops/lib/production-account-rollout.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderBindingRolloutEnvironment,
  renderComponentRolloutEnvironment,
  renderIdentityWebRolloutEnvironment,
  renderMultiDeviceRolloutEnvironment,
  renderSharingRolloutEnvironment,
} from "../../ops/lib/production-release-environment.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("production Web app rollout config is strict and protected", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, fixture.rawConfig);
  const parsed = await loadProductionWebAppRolloutConfig(fixture.configPath);
  assert.equal(parsed.gateway.origin, "https://gateway.example.com");
  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadProductionWebAppRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, fixture.rawConfig);
  await chmod(fixture.configPath, 0o644);
  await assert.rejects(() => loadProductionWebAppRolloutConfig(fixture.configPath), isCode);
});

test("Web app Nginx routes add only /app, read-only and bodiless", () => {
  const routes = renderWebAppNginxRoutes();
  assert.match(routes, /^location = \/app \{/m);
  assert.match(routes, /^location \^~ \/app\/ \{/m);
  assert.equal((routes.match(/^location /gm) ?? []).length, 2);
  assert.match(routes, /client_max_body_size 1k;/);
  assert.equal((routes.match(/proxy_pass http:\/\/hermes_go_gateway_production;/g) ?? []).length, 2);
  for (const forbidden of ["/v2/", "/api/", "Upgrade", "/account"]) assert.equal(routes.includes(forbidden), false);
});

test("production Web app rollout turns on only the Web app on top of the component runtime", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  let enabledChecks = 0;
  const result = await executeProductionWebAppRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => { enabledChecks += 1; },
  });
  assert.equal(result.stage, "committed");
  assert.equal(result.webDeviceAccessEnabled, true);
  assert.equal(result.webAppEnabled, true);
  assert.equal(result.webAppDir, path.join(fixture.releaseConfig.paths.installRoot, "web", "current"));
  assert.equal(previousChecks, 1);
  assert.equal(enabledChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 1);
  assert.equal(
    await readFile(fixture.environmentPath, "utf8"),
    fixture.componentsEnvironment
      .replace("ACCOUNT_WEB_DEVICE_ACCESS_ENABLED=0", "ACCOUNT_WEB_DEVICE_ACCESS_ENABLED=1")
      .replace("WEB_APP_ENABLED=0", "WEB_APP_ENABLED=1"),
  );
  assert.equal((await inspectProductionReleaseEnvironment(fixture.releaseConfig, "green")).mode, "email_sharing_components_web");
  assert.equal(await readFile(fixture.webAppRoutesPath, "utf8"), renderWebAppNginxRoutes());
  assert.equal(
    await readFile(fixture.nginxConfigPath, "utf8"),
    fixture.nginxConfig.replace(
      `    include ${fixture.sharingRoutesPath};\n`,
      `    include ${fixture.sharingRoutesPath};\n    include ${fixture.webAppRoutesPath};\n`,
    ),
  );
  const journal = JSON.parse(await readFile(fixture.journalPath, "utf8"));
  assert.equal(journal.stage, "committed");
  assert.equal(journal.webAppEnabled, true);
  assert.equal(journal.serverVersion, "0.4.17");
  await assert.rejects(
    () => readFile(path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock")),
    (error) => error?.code === "ENOENT",
  );
});

test("live verification pins the Web app shell and guards before and after enablement", async (t) => {
  const fixture = await createFixture(t);
  let webEnabled = true;
  const fetchImpl = async (url, init = {}) => {
    const parsed = new URL(url);
    switch (parsed.pathname) {
      case "/v2/capabilities": return jsonResponse(capabilities(webEnabled));
      case "/v2/account":
      case "/v2/connector-binding":
      case "/v2/devices":
      case "/v2/web/identities":
      case "/v2/web/installations":
      case "/v2/web/devices/probe-device/shares":
      case "/v2/devices/probe-device/shares":
      case "/v2/devices/probe-device/api/sessions":
        return new Response("{}", { status: 401 });
      case "/v2/account/identities":
      case "/v2/installations":
        return new Response("{}", { status: 401 });
      case "/v2/web/auth/email/challenges":
        return new Response("{}", { status: 403 });
      case "/v2/web/session":
        return jsonResponse({ session: { authenticated: false }, csrfToken: `hgc_${"A".repeat(43)}` }, {
          "set-cookie": "__Host-hermes_go_installation=id; Secure; HttpOnly; SameSite=Strict, __Host-hermes_go_csrf=token; Secure; SameSite=Strict",
        });
      case "/account/assets/account.css":
      case "/account/assets/account.js":
        return new Response("asset", { status: 200, headers: { "cache-control": "no-store" } });
      case "/v2/web/auth/google/exchange":
      case "/v2/web/account":
        return new Response("no", { status: 405 });
      case "/readyz":
        return jsonResponse({ status: "ready", checks: { database: "ok", migrations: "ok", postgresql: "supported" } });
      case "/api/status":
        return jsonResponse({ overall: "ok", gateway_running: true });
      case "/internal/version":
        return jsonResponse({ serverVersion: "0.4.17", sourceCommit: fixture.currentManifest.sourceCommit });
      case "/account":
        return new Response("<!doctype html>", { status: 200, headers: shellHeaders("default-src 'none'; script-src 'self'; frame-ancestors 'none'") });
      case "/app":
        assert.equal(init.redirect, "manual");
        return new Response(null, { status: 308, headers: { location: "/app/" } });
      case "/app/":
        return webEnabled
          ? new Response("<!doctype html>", { status: 200, headers: shellHeaders(
            "default-src 'none'; script-src 'self'; style-src 'self'; worker-src 'self'; frame-ancestors 'none'",
          ) })
          : new Response("not found", { status: 404 });
      case "/app/sw.js":
        return new Response("self", { status: 200, headers: { "service-worker-allowed": "/app/" } });
      default:
        assert.fail(`unexpected URL ${url}`);
    }
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
    probeDeviceWebSocket: async () => 401,
  };
  await verifyWebAppSurface(request);
  webEnabled = false;
  await verifyPreviousComponentSurface(request);
  // The capability showing up before enablement, or the shell served early, are both refused.
  await assert.rejects(() => verifyPreviousComponentSurface({ ...request, fetchImpl: async (url, init) => {
    if (new URL(url).pathname === "/v2/capabilities") return jsonResponse(capabilities(true));
    return fetchImpl(url, init);
  } }));
  await assert.rejects(() => verifyPreviousComponentSurface({ ...request, fetchImpl: async (url, init) => {
    if (new URL(url).pathname === "/app/") {
      return new Response("<!doctype html>", { status: 200, headers: shellHeaders("default-src 'none'") });
    }
    return fetchImpl(url, init);
  } }), (error) => isCode(error) && error.technicalCause === "web_app_rollout_app_exposed_before_enablement");
  webEnabled = true;
  await assert.rejects(() => verifyWebAppSurface({ ...request, fetchImpl: async (url, init) => {
    if (new URL(url).pathname === "/app/") {
      return new Response("<!doctype html>", { status: 200, headers: shellHeaders("default-src 'none'; script-src 'self' 'unsafe-inline'; worker-src 'self'; frame-ancestors 'none'") });
    }
    return fetchImpl(url, init);
  } }), (error) => [
    // The shared release smoke refuses it first; the rollout's own shell check is the second line.
    "production_release_web_app_shell_invalid",
    "web_app_rollout_app_shell_invalid",
  ].includes(error?.technicalCause));
});

test("the Web app include is anchored on the real sharing include, never on a commented copy", async (t) => {
  const fixture = await createFixture(t);
  const commented = fixture.nginxConfig.replace(
    `    include ${fixture.identityWebRoutesPath};\n`,
    `    include ${fixture.identityWebRoutesPath};\n    # include ${fixture.sharingRoutesPath};\n`,
  );
  await writeFile(fixture.nginxConfigPath, commented, { mode: 0o644 });
  await executeProductionWebAppRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  });
  const site = (await readFile(fixture.nginxConfigPath, "utf8")).split("\n");
  const real = site.indexOf(`    include ${fixture.sharingRoutesPath};`);
  assert.equal(site[real + 1], `    include ${fixture.webAppRoutesPath};`);
  assert.equal(site.filter((line) => line.includes("web-app-routes.conf")).length, 1);
});

test("a failed verification restores the exact component environment, site and routes", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  await assert.rejects(() => executeProductionWebAppRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => { throw new Error("synthetic_web_app_smoke_failure"); },
  }), isCode);
  assert.equal(previousChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 2);
  assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.componentsEnvironment);
  assert.equal(await readFile(fixture.nginxConfigPath, "utf8"), fixture.nginxConfig);
  await assert.rejects(() => readFile(fixture.webAppRoutesPath), (error) => error?.code === "ENOENT");
  assert.equal(JSON.parse(await readFile(fixture.journalPath, "utf8")).stage, "rolled_back");
});

test("Web app rollout refuses an old Gateway, a wrong state, a missing mount or Web app, and drift", async (t) => {
  const fixture = await createFixture(t);
  const attempt = (overrides = {}, config = fixture.config) => executeProductionWebAppRollout(config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {}, ...overrides,
  });
  const expectCause = async (pending, pattern) => {
    await assert.rejects(pending, (error) => isCode(error) && pattern.test(error.technicalCause));
  };

  const old = { ...fixture.currentManifest, serverVersion: "0.4.16" };
  await expectCause(attempt({ loadCurrentManifest: async () => old, loadBundleManifest: async () => old }), /gateway_release_too_old/);

  await writeFile(fixture.unitPath, "ExecStart=/usr/bin/docker run --read-only image\n", { mode: 0o644 });
  await expectCause(attempt(), /unit_mount_missing/);
  await writeFile(fixture.unitPath, fixture.unit, { mode: 0o644 });

  const current = path.join(fixture.webRoot, "current");
  await unlink(current);
  await expectCause(attempt(), /web_app_not_published/);
  await symlink(path.join(fixture.webRoot, "releases", "0.1.0-abcdef012345"), current);
  await expectCause(attempt(), /web_app_not_published/);
  await unlink(current);
  await mkdir(path.join(fixture.webRoot, "elsewhere"), { recursive: true });
  await writeFile(path.join(fixture.webRoot, "elsewhere", "index.html"), "<!doctype html>");
  await symlink("elsewhere", current);
  await expectCause(attempt(), /web_app_not_published/);
  await unlink(current);
  await symlink("releases/0.1.0-abcdef012345", current);
  // Published but unreadable for the Gateway's uid (a publisher under a hardened umask).
  const index = path.join(fixture.webRoot, "releases", "0.1.0-abcdef012345", "index.html");
  await chmod(index, 0o600);
  await expectCause(attempt(), /web_app_not_published:.*release_file_not_readable/);
  await chmod(index, 0o644);

  // Admitted state changed by someone else between admission and the lock.
  await expectCause(attempt({
    verifyPrevious: async () => {
      await writeFile(fixture.environmentPath, `${fixture.componentsEnvironment}`.replace("GATEWAY_LOG_LEVEL=info", "GATEWAY_LOG_LEVEL=debug"), { mode: 0o600 });
    },
  }), /state_changed_before_lock/);
  await writeFile(fixture.environmentPath, fixture.componentsEnvironment, { mode: 0o600 });
  await assert.rejects(() => readFile(fixture.journalPath), (error) => error?.code === "ENOENT");

  await writeFile(fixture.environmentPath, fixture.sharingEnvironment, { mode: 0o600 });
  await expectCause(attempt(), /requires_components_state/);
  await writeFile(fixture.environmentPath, fixture.componentsEnvironment, { mode: 0o600 });

  await writeFile(fixture.sharingRoutesPath, `${renderSharingNginxRoutes()}# drift\n`);
  await expectCause(attempt(), /previous_routes_invalid/);
  await writeFile(fixture.sharingRoutesPath, renderSharingNginxRoutes());

  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig.replace(`    include ${fixture.sharingRoutesPath};\n`, ""));
  await expectCause(attempt(), /previous_include_invalid/);
  await writeFile(
    fixture.nginxConfigPath,
    fixture.nginxConfig.replace(
      `    include ${fixture.sharingRoutesPath};`,
      `    include ${fixture.sharingRoutesPath};\n    include ${fixture.webAppRoutesPath};`,
    ),
  );
  await expectCause(attempt(), /include_already_exists/);
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig);

  await expectCause(
    attempt({}, { ...fixture.config, gateway: { ...fixture.config.gateway, origin: "https://other.example.com" } }),
    /gateway_origin_mismatch/,
  );

  const lockPath = path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock");
  await writeJson(lockPath, {
    schemaVersion: 2,
    runId: "competing-production-operation",
    nonce: "competing-lock",
    pid: process.pid,
    hostname: hostname(),
  });
  await expectCause(attempt(), /lock_unavailable/);
  await assert.rejects(() => readFile(fixture.journalPath), (error) => error?.code === "ENOENT");
});

test("production Web app rollout error is bilingual, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionWebAppRollout;
  assert.equal(definition.code, "HR-OPS-026");
  assert.match(definition.summaryZh, /Web 版灰度/);
  assert.match(definition.summaryEn, /Web app rollout/);
  assert.equal(definition.retryable, true);
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.equal(registry.includes(definition.summaryZh), true);
  assert.equal(registry.includes(definition.summaryEn), true);
  const entrypoint = spawnSync(process.execPath, ["scripts/production-web-app-rollout.mjs"], {
    encoding: "utf8",
    env: {},
    shell: false,
  });
  assert.equal(entrypoint.status, 1);
  assert.equal(entrypoint.stdout, "");
  const payload = JSON.parse(entrypoint.stderr);
  assert.equal(payload.code, "HR-OPS-026");
  assert.equal(payload.stage, "production_web_app_rollout_arguments");
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-web-app-rollout-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const configRoot = path.join(base, "config");
  const stateRoot = path.join(base, "state");
  const installRoot = path.join(base, "install");
  const systemdUnitDirectory = path.join(base, "systemd");
  await mkdir(inputs, { recursive: true, mode: 0o700 });
  await mkdir(path.join(configRoot, "slots", "green"), { recursive: true });
  await mkdir(path.join(configRoot, "account"), { recursive: true });
  await mkdir(path.join(stateRoot, "ops"), { recursive: true });
  await mkdir(systemdUnitDirectory, { recursive: true });
  const appTokenSource = path.join(inputs, "app-token");
  const internalStatusTokenSource = path.join(inputs, "internal-token");
  await writeFile(appTokenSource, "legacy-app-token\n", { mode: 0o600 });
  await writeFile(internalStatusTokenSource, "internal-status-token\n", { mode: 0o600 });
  const releaseConfig = {
    environment: "production",
    managedBaseline: true,
    targetArtifactManifest: path.join(inputs, "target.manifest.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    paths: { installRoot, configRoot, stateRoot, systemdUnitDirectory },
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

  // The unit a 0.4.17 release writes, with the Web app root mounted read-only.
  const webRoot = path.join(installRoot, "web");
  const unit = `ExecStart=/usr/bin/docker run --read-only --mount type=bind,src=${webRoot},dst=${webRoot},readonly image\n`;
  const unitPath = path.join(systemdUnitDirectory, "gateway-green.service");
  await writeFile(unitPath, unit, { mode: 0o644 });
  // A published Web app: releases/<id>/index.html and a relative `current` link to it.
  await mkdir(path.join(webRoot, "releases", "0.1.0-abcdef012345"), { recursive: true });
  await writeFile(path.join(webRoot, "releases", "0.1.0-abcdef012345", "index.html"), "<!doctype html>\n");
  await symlink("releases/0.1.0-abcdef012345", path.join(webRoot, "current"));

  const emailRoutesPath = path.join(configRoot, "account", "email-login-routes.conf");
  const bindingRoutesPath = path.join(configRoot, "account", "binding-routes.conf");
  const identityWebRoutesPath = path.join(configRoot, "account", "identity-web-routes.conf");
  const sharingRoutesPath = path.join(configRoot, "account", "sharing-routes.conf");
  const nginxConfig = `include ${releaseConfig.nginx.upstreamConfigFile};\nserver {\n    listen 443 ssl;\n    server_name gateway.example.com;\n\n    include ${emailRoutesPath};\n    include ${bindingRoutesPath};\n    include ${identityWebRoutesPath};\n    include ${sharingRoutesPath};\n    location /api/ { proxy_pass http://hermes_go_gateway_production; }\n    location / { return 404; }\n}\n`;
  await mkdir(path.dirname(releaseConfig.nginx.configFile), { recursive: true });
  await writeFile(releaseConfig.nginx.configFile, nginxConfig, { mode: 0o644 });
  await writeFile(emailRoutesPath, "# email routes\n", { mode: 0o644 });
  await writeFile(bindingRoutesPath, renderMultiDeviceNginxRoutes(), { mode: 0o644 });
  await writeFile(identityWebRoutesPath, renderIdentityWebNginxRoutes(), { mode: 0o644 });
  await writeFile(sharingRoutesPath, renderSharingNginxRoutes(), { mode: 0o644 });

  const environmentPath = path.join(configRoot, "slots", "green", "gateway.env");
  await writeFile(environmentPath, renderEmailRolloutEnvironment(releaseConfig, {
    gateway: { emailIssuer: "https://gateway.example.com", origin: "https://gateway.example.com", trustLoopbackProxy: true },
    database: { ssl: false },
  }, "green"), { mode: 0o600 });
  let sharingEnvironment;
  for (const render of [
    renderBindingRolloutEnvironment,
    renderMultiDeviceRolloutEnvironment,
    renderIdentityWebRolloutEnvironment,
    renderSharingRolloutEnvironment,
    renderComponentRolloutEnvironment,
  ]) {
    const inspected = await inspectProductionReleaseEnvironment(releaseConfig, "green");
    const rendered = render(releaseConfig, "green", inspected);
    if (render === renderSharingRolloutEnvironment) sharingEnvironment = rendered;
    await writeFile(environmentPath, rendered, { mode: 0o600 });
  }
  await chmod(environmentPath, 0o600);
  const componentsEnvironment = await readFile(environmentPath, "utf8");

  const currentManifest = {
    schemaVersion: 3,
    serverVersion: "0.4.17",
    sourceCommit: "c".repeat(40),
    imageId: `sha256:${"d".repeat(64)}`,
    containerdImageId: `sha256:${"e".repeat(64)}`,
    archiveSha256: "f".repeat(64),
    releaseContract: { databaseSchemaVersion: 15, supportedPostgresqlMajors: [18] },
  };
  const config = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    productionReleaseConfig: path.join(inputs, "production-release.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    gateway: { origin: "https://gateway.example.com", runtimeContract: "hermes-serve-v1" },
    deployment: { observationSeconds: 1 },
  };
  return {
    config,
    rawConfig: structuredClone(config),
    configPath: path.join(inputs, "web-app-rollout.json"),
    releaseConfig,
    currentManifest,
    nginxConfig,
    nginxConfigPath: releaseConfig.nginx.configFile,
    environmentPath,
    componentsEnvironment,
    sharingEnvironment,
    sharingRoutesPath,
    identityWebRoutesPath,
    webAppRoutesPath: path.join(configRoot, "account", "web-app-routes.conf"),
    journalPath: path.join(stateRoot, "ops", "web-app-rollout.json"),
    webRoot,
    unit,
    unitPath,
    dependencies: {
      confirmation: "production:prod-host",
      getUid: () => 0,
      platform: "linux",
      architecture: "x64",
      hostname: "prod-host",
      ownership: { host: { uid: process.getuid(), gid: process.getgid() } },
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

function capabilities(webDeviceAccess) {
  return {
    accountAuth: {
      enabled: true,
      providers: ["email_otp"],
      android: true,
      macos: true,
      identityManagement: true,
      webAccountCenter: true,
      webSessions: true,
      ...(webDeviceAccess ? { webDeviceAccess: true } : {}),
    },
    binding: {
      enabled: true,
      replacement: true,
      maxActiveConnectorsPerAccount: 3,
      supportsDeviceSelection: true,
      supportsDeviceSharing: true,
      maxSharedDevices: 10,
      maxGranteesPerDevice: 5,
    },
    legacy: { appTokenAccepted: true, connectorTokenAccepted: true },
    desktopBootstrap: { runtimeContract: "hermes-serve-v1", componentManifestSchemaVersion: 2 },
  };
}

function shellHeaders(csp) {
  return { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "content-security-policy": csp };
}

function jsonResponse(value, headers = {}) {
  return new Response(JSON.stringify(value), { headers: { "content-type": "application/json", ...headers } });
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

function isCode(error) {
  return error && OPS_ERROR_DEFINITIONS[error.kind]?.code === "HR-OPS-026";
}
