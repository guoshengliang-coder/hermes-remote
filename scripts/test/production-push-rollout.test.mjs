import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { chmod, mkdir, mkdtemp, readdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { hostname, tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadProductionPushRolloutConfig } from "../../ops/lib/production-push-rollout-config.mjs";
import {
  executeProductionPushRollout,
  MINIMUM_PUSH_SERVER_VERSION,
  renderPushNginxRoutes,
  verifyPreviousWebSurface,
  verifyPushSurface,
} from "../../ops/lib/production-push-rollout.mjs";
import { renderMultiDeviceNginxRoutes } from "../../ops/lib/production-multi-device-rollout.mjs";
import { renderIdentityWebNginxRoutes } from "../../ops/lib/production-identity-web-rollout.mjs";
import { renderSharingNginxRoutes } from "../../ops/lib/production-sharing-rollout.mjs";
import { renderWebAppNginxRoutes } from "../../ops/lib/production-web-app-rollout.mjs";
import { renderEmailRolloutEnvironment } from "../../ops/lib/production-account-rollout.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderBindingRolloutEnvironment,
  renderComponentRolloutEnvironment,
  renderIdentityWebRolloutEnvironment,
  renderMultiDeviceRolloutEnvironment,
  renderPushRolloutEnvironment,
  renderSharingRolloutEnvironment,
  renderWebAppRolloutEnvironment,
} from "../../ops/lib/production-release-environment.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

// A marker inside the fake private key: it must never surface in an error, a journal or a result.
// Assembled at runtime so the source carries no PEM armour for secret scanners to flag.
const PEM_BEGIN = ["-----BEGIN", "PRIVATE", "KEY-----"].join(" ");
const PEM_END = ["-----END", "PRIVATE", "KEY-----"].join(" ");
const KEY_SENTINEL = "not-a-real-key-test-sentinel";
const PROJECT_ID = "hermesgo-94bbc";

test("production push rollout config is strict and protected", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, fixture.rawConfig);
  const parsed = await loadProductionPushRolloutConfig(fixture.configPath);
  assert.equal(parsed.gateway.origin, "https://gateway.example.com");
  assert.equal(parsed.push.firebaseProjectId, PROJECT_ID);
  assert.equal(parsed.secrets.fcmServiceAccountSource, fixture.keySource);
  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadProductionPushRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, { ...fixture.rawConfig, push: { firebaseProjectId: "Bad_Project" } });
  await assert.rejects(() => loadProductionPushRolloutConfig(fixture.configPath), isCode);
  await writeJson(fixture.configPath, fixture.rawConfig);
  await chmod(fixture.configPath, 0o644);
  await assert.rejects(() => loadProductionPushRolloutConfig(fixture.configPath), isCode);
  const example = JSON.parse(await readFile("ops/production.push-rollout.example.json", "utf8"));
  assert.equal(example.push.firebaseProjectId, PROJECT_ID);
});

test("push Nginx routes forward only the push registration path, with a small body", () => {
  const routes = renderPushNginxRoutes();
  assert.match(routes, /^location = \/v2\/installations\/current\/push-registration \{/m);
  assert.equal((routes.match(/^location /gm) ?? []).length, 1);
  assert.match(routes, /client_max_body_size 8k;/);
  assert.equal((routes.match(/proxy_pass http:\/\/hermes_go_gateway_production;/g) ?? []).length, 1);
  for (const forbidden of ["/api/", "Upgrade", "/app", "~"]) assert.equal(routes.includes(forbidden), false);
  assert.equal(MINIMUM_PUSH_SERVER_VERSION, "0.4.18");
});

test("production push rollout installs the key, the route and push=1 on top of the Web runtime", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  let enabledChecks = 0;
  const stages = [];
  const result = await executeProductionPushRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => {
      enabledChecks += 1;
      stages.push(JSON.parse(await readFile(fixture.journalPath, "utf8")).stage);
    },
  });
  assert.equal(result.command, "production-push-rollout");
  assert.equal(result.stage, "committed");
  assert.equal(result.pushEnabled, true);
  assert.equal(result.webAppEnabled, true);
  assert.equal(result.firebaseProjectId, PROJECT_ID);
  assert.equal(JSON.stringify(result).includes(KEY_SENTINEL), false);
  assert.equal(previousChecks, 1);
  assert.equal(enabledChecks, 2);
  assert.deepEqual(stages, ["restarted", "restarted"]);
  assert.deepEqual(calls.map((call) => `${call.command} ${call.args.join(" ")}`), [
    "systemctl is-active --quiet gateway-green.service",
    "nginx -t",
    "systemctl reload nginx.service",
    "systemctl restart gateway-green.service",
  ]);

  const environment = await readFile(fixture.environmentPath, "utf8");
  assert.equal(environment, fixture.webEnvironment.replace("ACCOUNT_PUSH_ENABLED=0", "ACCOUNT_PUSH_ENABLED=1"));
  assert.equal(environment.split("\n").length - 1, 47);
  assert.match(environment, /^ACCOUNT_FCM_SERVICE_ACCOUNT_FILE=\/run\/hermes-go\/secrets\/fcm-service-account$/m);
  assert.equal((await inspectProductionReleaseEnvironment(fixture.releaseConfig, "green")).mode, "email_sharing_components_web_push");

  const key = await stat(fixture.keyTarget);
  assert.equal(key.mode & 0o777, 0o440);
  assert.equal(await readFile(fixture.keyTarget, "utf8"), fixture.keyContent);

  assert.equal(await readFile(fixture.pushRoutesPath, "utf8"), renderPushNginxRoutes());
  assert.equal(
    await readFile(fixture.nginxConfigPath, "utf8"),
    fixture.nginxConfig.replace(
      `    include ${fixture.webAppRoutesPath};\n`,
      `    include ${fixture.webAppRoutesPath};\n    include ${fixture.pushRoutesPath};\n`,
    ),
  );
  const journalText = await readFile(fixture.journalPath, "utf8");
  assert.equal(journalText.includes(KEY_SENTINEL), false);
  assert.equal(journalText.includes("PRIVATE KEY"), false);
  const journal = JSON.parse(journalText);
  assert.equal(journal.stage, "committed");
  assert.equal(journal.pushEnabled, true);
  assert.equal(journal.firebaseProjectId, PROJECT_ID);
  assert.equal(journal.serverVersion, "0.4.18");
  assert.equal(journal.databaseSchemaVersion, 16);
  assert.equal((await stat(fixture.journalPath)).mode & 0o777, 0o600);
  await assert.rejects(
    () => readFile(path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock")),
    (error) => error?.code === "ENOENT",
  );
});

test("after the checkpoint the journal walks key_installed, environment_installed, restarted, committed", async (t) => {
  const fixture = await createFixture(t);
  const seen = [];
  const note = () => {
    try {
      const stage = JSON.parse(readFileSync(fixture.journalPath, "utf8")).stage;
      if (seen.at(-1) !== stage) seen.push(stage);
    } catch {}
  };
  const base = runner([]);
  await executeProductionPushRollout(fixture.config, {
    ...fixture.dependencies,
    // Commands run between journal writes: nginx -t after key_installed, the restart after
    // environment_installed, verification after restarted.
    runner: { run(command, args, options) { note(); return base.run(command, args, options); } },
    verifyPrevious: async () => {},
    verifyEnabled: async () => { note(); },
  });
  note();
  assert.deepEqual(seen, ["key_installed", "environment_installed", "restarted", "committed"]);
});

test("live verification pins capabilities.push, the registration route and the Web app state", async (t) => {
  const fixture = await createFixture(t);
  let pushEnabled = true;
  const seen = [];
  const fetchImpl = async (url, init = {}) => {
    const parsed = new URL(url);
    const loopback = parsed.hostname === "127.0.0.1";
    seen.push(`${init.method ?? "GET"} ${loopback ? "loopback" : "public"} ${parsed.pathname}`);
    switch (parsed.pathname) {
      case "/v2/capabilities": return jsonResponse(capabilities(pushEnabled));
      case "/v2/installations/current/push-registration":
        assert.ok(init.method === "PUT" || init.method === "DELETE");
        // Before enablement: the edge does not forward it, and the Gateway answers 404 unconfigured.
        return new Response("{}", { status: pushEnabled ? 401 : 404 });
      case "/v2/account":
      case "/v2/connector-binding":
      case "/v2/devices":
      case "/v2/web/identities":
      case "/v2/web/installations":
      case "/v2/web/devices/probe-device/shares":
      case "/v2/devices/probe-device/shares":
      case "/v2/devices/probe-device/api/sessions":
        return new Response("{}", { status: 401 });
      case "/v2/web/session":
        return jsonResponse({ session: { authenticated: false }, csrfToken: `hgc_${"A".repeat(43)}` }, {
          "set-cookie": "__Host-hermes_go_installation=id; Secure; HttpOnly; SameSite=Strict, __Host-hermes_go_csrf=token; Secure; SameSite=Strict",
        });
      case "/v2/web/auth/google/exchange":
      case "/v2/web/account":
        return new Response("no", { status: 405 });
      case "/readyz":
        return jsonResponse({ status: "ready", checks: { database: "ok", migrations: "ok", postgresql: "supported" } });
      case "/api/status":
        return jsonResponse({ overall: "ok", gateway_running: true });
      case "/internal/version":
        return jsonResponse({ serverVersion: "0.4.18", sourceCommit: fixture.currentManifest.sourceCommit });
      case "/account":
        return new Response("<!doctype html>", { status: 200, headers: shellHeaders("default-src 'none'; script-src 'self'; frame-ancestors 'none'") });
      case "/app":
        assert.equal(init.redirect, "manual");
        return new Response(null, { status: 308, headers: { location: "/app/" } });
      case "/app/":
        return new Response("<!doctype html>", { status: 200, headers: shellHeaders(
          "default-src 'none'; script-src 'self'; style-src 'self'; worker-src 'self'; frame-ancestors 'none'",
        ) });
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
  await verifyPushSurface(request);
  for (const probe of [
    "PUT public /v2/installations/current/push-registration",
    "DELETE public /v2/installations/current/push-registration",
    "PUT loopback /v2/installations/current/push-registration",
    "GET loopback /v2/capabilities",
    "GET public /app/",
  ]) assert.equal(seen.includes(probe), true, probe);

  pushEnabled = false;
  await verifyPreviousWebSurface(request);

  const override = (pathname, respond) => async (url, init) => (
    new URL(url).pathname === pathname ? respond(url, init) : fetchImpl(url, init)
  );
  // Push advertised before enablement is refused.
  await assert.rejects(() => verifyPreviousWebSurface({
    ...request, fetchImpl: override("/v2/capabilities", () => jsonResponse(capabilities(true))),
  }), (error) => error?.technicalCause === "production_release_email_capabilities_invalid");
  // The public route already asking for credentials before enablement is refused.
  await assert.rejects(() => verifyPreviousWebSurface({
    ...request, fetchImpl: override("/v2/installations/current/push-registration", () => new Response("{}", { status: 401 })),
  }), (error) => isCode(error) && error.technicalCause === "push_rollout_registration_exposed_before_enablement");

  pushEnabled = true;
  // A capability with extra providers is not the pinned one.
  await assert.rejects(() => verifyPushSurface({
    ...request, fetchImpl: override("/v2/capabilities", () => jsonResponse({
      ...capabilities(true), push: { providers: ["fcm", "apns"] },
    })),
  }), (error) => error?.technicalCause === "production_release_email_capabilities_invalid");
  // The edge not forwarding the route after enablement (still 404) is refused.
  await assert.rejects(() => verifyPushSurface({
    ...request, fetchImpl: async (url, init) => {
      const parsed = new URL(url);
      if (parsed.pathname === "/v2/installations/current/push-registration" && parsed.hostname !== "127.0.0.1") {
        return new Response("not found", { status: 404 });
      }
      return fetchImpl(url, init);
    },
  }), (error) => isCode(error) && error.technicalCause === "push_rollout_registration_guard_invalid");
  // The Web app shell must still be served.
  await assert.rejects(() => verifyPushSurface({
    ...request, fetchImpl: override("/app/", () => new Response("nope", { status: 404 })),
  }), (error) => [
    "production_release_web_app_shell_invalid",
    "push_rollout_app_shell_invalid",
  ].includes(error?.technicalCause));
});

test("the push include is anchored on the real Web app include, never on a commented copy", async (t) => {
  const fixture = await createFixture(t);
  const commented = fixture.nginxConfig.replace(
    `    include ${fixture.sharingRoutesPath};\n`,
    `    include ${fixture.sharingRoutesPath};\n    # include ${fixture.webAppRoutesPath};\n`,
  );
  await writeFile(fixture.nginxConfigPath, commented, { mode: 0o644 });
  await executeProductionPushRollout(fixture.config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {},
  });
  const site = (await readFile(fixture.nginxConfigPath, "utf8")).split("\n");
  const real = site.indexOf(`    include ${fixture.webAppRoutesPath};`);
  assert.equal(site[real + 1], `    include ${fixture.pushRoutesPath};`);
  assert.equal(site.filter((line) => line.includes("push-routes.conf")).length, 1);
});

test("a failed verification restores env and site, removes the route and the key, and journals rolled_back", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  let previousChecks = 0;
  let caught;
  await assert.rejects(() => executeProductionPushRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner(calls),
    verifyPrevious: async () => { previousChecks += 1; },
    verifyEnabled: async () => { throw new Error("synthetic_push_smoke_failure"); },
  }), (error) => { caught = error; return isCode(error); });
  assert.equal(caught.stage, "production_push_rollout_execute");
  assert.equal(caught.technicalCause, "synthetic_push_smoke_failure");
  assert.equal(previousChecks, 2);
  assert.equal(calls.filter((call) => call.args[0] === "restart").length, 2);
  assert.equal(calls.filter((call) => call.command === "nginx").length, 2);
  assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.webEnvironment);
  assert.equal(await readFile(fixture.nginxConfigPath, "utf8"), fixture.nginxConfig);
  await assert.rejects(() => readFile(fixture.pushRoutesPath), (error) => error?.code === "ENOENT");
  await assert.rejects(() => readFile(fixture.keyTarget), (error) => error?.code === "ENOENT");
  const journalText = await readFile(fixture.journalPath, "utf8");
  assert.equal(JSON.parse(journalText).stage, "rolled_back");
  assert.equal(journalText.includes(KEY_SENTINEL), false);
  // No temporary copy of the key is left behind in the secrets directory.
  assert.deepEqual(await readdir(path.dirname(fixture.keyTarget)), []);
});

test("a failed rollback verification journals rollback_failed", async (t) => {
  const fixture = await createFixture(t);
  let previousChecks = 0;
  await assert.rejects(() => executeProductionPushRollout(fixture.config, {
    ...fixture.dependencies,
    runner: runner([]),
    verifyPrevious: async () => {
      previousChecks += 1;
      if (previousChecks > 1) throw new Error("synthetic_previous_failure");
    },
    verifyEnabled: async () => { throw new Error("synthetic_push_smoke_failure"); },
  }), (error) => isCode(error) && error.technicalCause === "push_rollout_rollback_failed");
  assert.equal(JSON.parse(await readFile(fixture.journalPath, "utf8")).stage, "rollback_failed");
  assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.webEnvironment);
  await assert.rejects(() => readFile(fixture.keyTarget), (error) => error?.code === "ENOENT");
});

test("push rollout refuses the wrong state, an old or schema-15 Gateway, leftovers, drift and a bad key", async (t) => {
  const fixture = await createFixture(t);
  const attempt = (overrides = {}, config = fixture.config) => executeProductionPushRollout(config, {
    ...fixture.dependencies, runner: runner([]), verifyPrevious: async () => {}, verifyEnabled: async () => {}, ...overrides,
  });
  const expectCause = async (pending, pattern) => {
    await assert.rejects(pending, (error) => {
      assert.equal(isCode(error), true, String(error?.technicalCause ?? error));
      assert.match(error.technicalCause, pattern);
      assert.equal(JSON.stringify({ ...error, message: error.message }).includes(KEY_SENTINEL), false);
      assert.equal(`${error.message} ${error.technicalCause}`.includes("PRIVATE KEY"), false);
      return true;
    });
  };
  const untouched = async () => {
    assert.equal(await readFile(fixture.environmentPath, "utf8"), fixture.webEnvironment);
    assert.equal(await readFile(fixture.nginxConfigPath, "utf8"), fixture.nginxConfig);
    await assert.rejects(() => readFile(fixture.journalPath), (error) => error?.code === "ENOENT");
    await assert.rejects(() => readFile(fixture.keyTarget), (error) => error?.code === "ENOENT");
  };

  // Mode: only email_sharing_components_web is admitted.
  await writeFile(fixture.environmentPath, fixture.componentsEnvironment, { mode: 0o600 });
  await expectCause(attempt(), /requires_web_state/);
  await writeFile(fixture.environmentPath, fixture.pushEnvironment, { mode: 0o600 });
  await expectCause(attempt(), /requires_web_state/);
  await writeFile(fixture.environmentPath, fixture.webEnvironment, { mode: 0o600 });

  // Release: schema 16 and >= 0.4.18, equal to the operator bundle.
  const schema15 = { ...fixture.currentManifest, releaseContract: { databaseSchemaVersion: 15, supportedPostgresqlMajors: [18] } };
  await expectCause(attempt({ loadCurrentManifest: async () => schema15, loadBundleManifest: async () => schema15 }), /current_release_mismatch/);
  const old = { ...fixture.currentManifest, serverVersion: "0.4.17" };
  await expectCause(attempt({ loadCurrentManifest: async () => old, loadBundleManifest: async () => old }), /gateway_release_too_old/);
  await expectCause(attempt({ loadBundleManifest: async () => ({ ...fixture.currentManifest, sourceCommit: "a".repeat(40) }) }), /current_release_mismatch/);

  // Leftovers from an earlier attempt.
  await writeFile(fixture.pushRoutesPath, renderPushNginxRoutes(), { mode: 0o644 });
  await expectCause(attempt(), /routes_already_exist/);
  await rm(fixture.pushRoutesPath);
  await writeFile(
    fixture.nginxConfigPath,
    fixture.nginxConfig.replace(
      `    include ${fixture.webAppRoutesPath};`,
      `    include ${fixture.webAppRoutesPath};\n    include ${fixture.pushRoutesPath};`,
    ),
  );
  await expectCause(attempt(), /include_already_exists/);
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig);
  await writeFile(fixture.keyTarget, "{}", { mode: 0o440 });
  await expectCause(attempt(), /service_account_already_installed/);
  await rm(fixture.keyTarget, { force: true });

  // Previous routes: the Web app route must be there, once, byte-exact.
  await writeFile(fixture.webAppRoutesPath, `${renderWebAppNginxRoutes()}# drift\n`);
  await expectCause(attempt(), /previous_routes_invalid/);
  await writeFile(fixture.webAppRoutesPath, renderWebAppNginxRoutes());
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig.replace(`    include ${fixture.webAppRoutesPath};\n`, ""));
  await expectCause(attempt(), /previous_include_invalid/);
  await writeFile(fixture.nginxConfigPath, fixture.nginxConfig);

  // Key source: root-only, bounded, a service-account JSON of the configured project.
  await chmod(fixture.keySource, 0o640);
  await expectCause(attempt(), /service_account_source_unsafe/);
  await chmod(fixture.keySource, 0o604);
  await expectCause(attempt(), /service_account_source_unsafe/);
  await chmod(fixture.keySource, 0o600);
  await expectCause(attempt({ secretSourceUid: process.getuid() + 1 }), /service_account_source_unsafe/);
  await writeFile(fixture.keySource, `{"type":"service_account","private_key":"${PEM_BEGIN}${KEY_SENTINEL}`, { mode: 0o600 });
  await expectCause(attempt(), /^push_rollout_service_account_invalid$/);
  await writeFile(fixture.keySource, JSON.stringify({ ...fixture.keyRecord, type: "authorized_user" }), { mode: 0o600 });
  await expectCause(attempt(), /^push_rollout_service_account_invalid$/);
  await writeFile(fixture.keySource, JSON.stringify({ ...fixture.keyRecord, private_key: KEY_SENTINEL }), { mode: 0o600 });
  await expectCause(attempt(), /^push_rollout_service_account_invalid$/);
  await writeFile(fixture.keySource, JSON.stringify({ ...fixture.keyRecord, project_id: "other-project" }), { mode: 0o600 });
  await expectCause(attempt(), /^push_rollout_service_account_project_mismatch$/);
  await writeFile(fixture.keySource, `${fixture.keyContent}${" ".repeat(17 * 1024)}`, { mode: 0o600 });
  await expectCause(attempt(), /service_account_source_unsafe/);
  await writeFile(fixture.keySource, fixture.keyContent, { mode: 0o600 });
  await expectCause(attempt({}, {
    ...fixture.config,
    secrets: { fcmServiceAccountSource: path.join(fixture.releaseConfig.paths.configRoot, "fcm.json") },
  }), /source_inside_managed_root/);

  // Admitted state changed by someone else between admission and the lock.
  await expectCause(attempt({
    verifyPrevious: async () => {
      await writeFile(fixture.environmentPath, fixture.webEnvironment.replace("GATEWAY_LOG_LEVEL=info", "GATEWAY_LOG_LEVEL=debug"), { mode: 0o600 });
    },
  }), /state_changed_before_lock/);
  await writeFile(fixture.environmentPath, fixture.webEnvironment, { mode: 0o600 });

  await expectCause(attempt({ verifyPrevious: async () => { throw new Error("synthetic_web_state_failure"); } }), /web_preflight_failed/);
  await expectCause(
    attempt({}, { ...fixture.config, gateway: { ...fixture.config.gateway, origin: "https://other.example.com" } }),
    /gateway_origin_mismatch/,
  );
  await expectCause(attempt({ confirmation: "production:other-host" }), /authorization_failed/);

  const lockPath = path.join(fixture.releaseConfig.paths.stateRoot, "ops", "deploy.lock");
  await writeJson(lockPath, {
    schemaVersion: 2,
    runId: "competing-production-operation",
    nonce: "competing-lock",
    pid: process.pid,
    hostname: hostname(),
  });
  await expectCause(attempt(), /lock_unavailable/);
  await rm(lockPath);
  await untouched();
});

test("production push rollout error is bilingual, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionPushRollout;
  assert.equal(definition.code, "HR-OPS-028");
  assert.equal(definition.retryable, true);
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.equal(registry.includes(definition.summaryZh), true);
  assert.equal(registry.includes(definition.summaryEn), true);
  const entrypoint = spawnSync(process.execPath, ["scripts/production-push-rollout.mjs"], {
    encoding: "utf8",
    env: {},
    shell: false,
  });
  assert.equal(entrypoint.status, 1);
  assert.equal(entrypoint.stdout, "");
  const payload = JSON.parse(entrypoint.stderr);
  assert.equal(payload.code, "HR-OPS-028");
  assert.equal(payload.stage, "production_push_rollout_arguments");
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-push-rollout-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const configRoot = path.join(base, "config");
  const stateRoot = path.join(base, "state");
  const installRoot = path.join(base, "install");
  const systemdUnitDirectory = path.join(base, "systemd");
  await mkdir(inputs, { recursive: true, mode: 0o700 });
  await mkdir(path.join(configRoot, "slots", "green"), { recursive: true });
  await mkdir(path.join(configRoot, "account"), { recursive: true });
  await mkdir(path.join(configRoot, "secrets"), { recursive: true, mode: 0o750 });
  await mkdir(path.join(stateRoot, "ops"), { recursive: true });
  await mkdir(systemdUnitDirectory, { recursive: true });
  const appTokenSource = path.join(inputs, "app-token");
  const internalStatusTokenSource = path.join(inputs, "internal-token");
  await writeFile(appTokenSource, "legacy-app-token\n", { mode: 0o600 });
  await writeFile(internalStatusTokenSource, "internal-status-token\n", { mode: 0o600 });
  const keyRecord = {
    type: "service_account",
    project_id: PROJECT_ID,
    private_key_id: "0123456789abcdef",
    private_key: `${PEM_BEGIN}\n${KEY_SENTINEL}\n${PEM_END}\n`,
    client_email: `firebase-adminsdk@${PROJECT_ID}.iam.gserviceaccount.com`,
  };
  const keyContent = `${JSON.stringify(keyRecord, null, 2)}\n`;
  const keySource = path.join(inputs, "fcm-service-account.json");
  await writeFile(keySource, keyContent, { mode: 0o600 });
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

  const emailRoutesPath = path.join(configRoot, "account", "email-login-routes.conf");
  const bindingRoutesPath = path.join(configRoot, "account", "binding-routes.conf");
  const identityWebRoutesPath = path.join(configRoot, "account", "identity-web-routes.conf");
  const sharingRoutesPath = path.join(configRoot, "account", "sharing-routes.conf");
  const webAppRoutesPath = path.join(configRoot, "account", "web-app-routes.conf");
  const nginxConfig = `include ${releaseConfig.nginx.upstreamConfigFile};\nserver {\n    listen 443 ssl;\n    server_name gateway.example.com;\n\n    include ${emailRoutesPath};\n    include ${bindingRoutesPath};\n    include ${identityWebRoutesPath};\n    include ${sharingRoutesPath};\n    include ${webAppRoutesPath};\n    location /api/ { proxy_pass http://hermes_go_gateway_production; }\n    location / { return 404; }\n}\n`;
  await mkdir(path.dirname(releaseConfig.nginx.configFile), { recursive: true });
  await writeFile(releaseConfig.nginx.configFile, nginxConfig, { mode: 0o644 });
  await writeFile(emailRoutesPath, "# email routes\n", { mode: 0o644 });
  await writeFile(bindingRoutesPath, renderMultiDeviceNginxRoutes(), { mode: 0o644 });
  await writeFile(identityWebRoutesPath, renderIdentityWebNginxRoutes(), { mode: 0o644 });
  await writeFile(sharingRoutesPath, renderSharingNginxRoutes(), { mode: 0o644 });
  await writeFile(webAppRoutesPath, renderWebAppNginxRoutes(), { mode: 0o644 });

  // Walk the real environment state machine: email → … → components → Web app → (push, kept aside).
  const environmentPath = path.join(configRoot, "slots", "green", "gateway.env");
  await writeFile(environmentPath, renderEmailRolloutEnvironment(releaseConfig, {
    gateway: { emailIssuer: "https://gateway.example.com", origin: "https://gateway.example.com", trustLoopbackProxy: true },
    database: { ssl: false },
  }, "green"), { mode: 0o600 });
  const rendered = {};
  for (const [name, render] of [
    ["binding", renderBindingRolloutEnvironment],
    ["multiDevice", renderMultiDeviceRolloutEnvironment],
    ["identityWeb", renderIdentityWebRolloutEnvironment],
    ["sharing", renderSharingRolloutEnvironment],
    ["components", renderComponentRolloutEnvironment],
    ["web", renderWebAppRolloutEnvironment],
  ]) {
    const inspected = await inspectProductionReleaseEnvironment(releaseConfig, "green");
    rendered[name] = render(releaseConfig, "green", inspected);
    await writeFile(environmentPath, rendered[name], { mode: 0o600 });
  }
  await chmod(environmentPath, 0o600);
  const webEnvironment = await readFile(environmentPath, "utf8");
  const pushEnvironment = renderPushRolloutEnvironment(
    releaseConfig, "green", await inspectProductionReleaseEnvironment(releaseConfig, "green"),
  );

  const currentManifest = {
    schemaVersion: 3,
    serverVersion: "0.4.18",
    sourceCommit: "c".repeat(40),
    imageId: `sha256:${"d".repeat(64)}`,
    containerdImageId: `sha256:${"e".repeat(64)}`,
    archiveSha256: "f".repeat(64),
    releaseContract: { databaseSchemaVersion: 16, supportedPostgresqlMajors: [18] },
  };
  const config = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    productionReleaseConfig: path.join(inputs, "production-release.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    gateway: { origin: "https://gateway.example.com", runtimeContract: "hermes-serve-v1" },
    secrets: { fcmServiceAccountSource: keySource },
    push: { firebaseProjectId: PROJECT_ID },
    deployment: { observationSeconds: 1 },
  };
  return {
    config,
    rawConfig: structuredClone(config),
    configPath: path.join(inputs, "push-rollout.json"),
    releaseConfig,
    currentManifest,
    nginxConfig,
    nginxConfigPath: releaseConfig.nginx.configFile,
    environmentPath,
    componentsEnvironment: rendered.components,
    webEnvironment,
    pushEnvironment,
    sharingRoutesPath,
    webAppRoutesPath,
    pushRoutesPath: path.join(configRoot, "account", "push-routes.conf"),
    keySource,
    keyRecord,
    keyContent,
    keyTarget: path.join(configRoot, "secrets", "fcm-service-account"),
    journalPath: path.join(stateRoot, "ops", "push-rollout.json"),
    dependencies: {
      confirmation: "production:prod-host",
      getUid: () => 0,
      platform: "linux",
      architecture: "x64",
      hostname: "prod-host",
      secretSourceUid: process.getuid(),
      ownership: {
        host: { uid: process.getuid(), gid: process.getgid() },
        secret: { uid: process.getuid(), gid: process.getgid() },
      },
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

function capabilities(push) {
  return {
    accountAuth: {
      enabled: true,
      providers: ["email_otp"],
      android: true,
      macos: true,
      identityManagement: true,
      webAccountCenter: true,
      webSessions: true,
      webDeviceAccess: true,
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
    ...(push ? { push: { providers: ["fcm"] } } : {}),
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
  return error && OPS_ERROR_DEFINITIONS[error.kind]?.code === "HR-OPS-028";
}
