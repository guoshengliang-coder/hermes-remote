import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadBundleManifest, loadOpsConfig } from "../../ops/lib/config.mjs";
import {
  createOpsError,
  OPS_ERROR_DEFINITIONS,
  OpsError,
  redactOpsValue,
} from "../../ops/lib/errors.mjs";
import {
  bootstrapStaging,
  createDoctorBundle,
  getStatus,
  preflight,
} from "../../ops/lib/hermesctl.mjs";
import {
  createCommandRunner,
  renderGatewayEnvironment,
  renderNginxConfig,
  renderSystemdUnit,
} from "../../ops/lib/system.mjs";

test("hermesctl config and OCI bundle parsing fail closed", async (t) => {
  const fixture = await createFixture(t);
  const example = await loadOpsConfig("ops/staging.example.json");
  const schema = JSON.parse(await readFile("ops/hermesctl-config.schema.json", "utf8"));
  assert.equal(example.environment, "staging");
  assert.equal(schema.additionalProperties, false);
  assert.equal(schema.properties.environment.const, "staging");
  const config = await loadOpsConfig(fixture.configPath);
  const manifest = await loadBundleManifest(config.artifactManifest);
  assert.equal(config.environment, "staging");
  assert.equal(manifest.archiveSha256, fixture.archiveSha256);

  await writeJson(fixture.configPath, { ...fixture.config, unexpected: true });
  await assert.rejects(() => loadOpsConfig(fixture.configPath), isOpsCode("HR-OPS-001"));

  await writeJson(fixture.configPath, { ...fixture.config, environment: "production" });
  await assert.rejects(() => loadOpsConfig(fixture.configPath), isOpsCode("HR-OPS-001"));

  await writeJson(fixture.configPath, fixture.config);
  await writeFile(fixture.archivePath, "tampered");
  await assert.rejects(() => loadBundleManifest(fixture.manifestPath), isOpsCode("HR-OPS-002"));
});

test("preflight verifies host, private inputs, image identity, and managed port", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadOpsConfig(fixture.configPath);
  const manifest = await loadBundleManifest(config.artifactManifest);
  const runner = createFakeRunner(manifest, { imageLoaded: false });

  const result = await preflight(config, manifest, {
    runner,
    platform: "linux",
    architecture: "x64",
  });
  assert.equal(result.ok, true);
  assert.equal(result.checks.find((entry) => entry.id === "image")?.status, "pending");
  const output = JSON.stringify(result);
  assert.equal(output.includes(fixture.tokens.app), false);
  assert.equal(output.includes(fixture.base), false);

  await assert.rejects(
    () => preflight(config, manifest, { runner, platform: "darwin", architecture: "arm64" }),
    isOpsCode("HR-OPS-001"),
  );

  const wrongImage = createFakeRunner(manifest, { imageLoaded: true, imageId: `sha256:${"c".repeat(64)}` });
  await assert.rejects(
    () => preflight(config, manifest, { runner: wrongImage, platform: "linux", architecture: "x64" }),
    isOpsCode("HR-OPS-002"),
  );

  await mkdir(config.paths.configRoot, { recursive: true });
  await writeFile(path.join(config.paths.configRoot, "gateway.env"), "unmanaged-content\n", { mode: 0o600 });
  await assert.rejects(
    () => preflight(config, manifest, { runner, platform: "linux", architecture: "x64" }),
    isOpsCode("HR-OPS-003"),
  );

  const symlinkPath = path.join(fixture.inputs, "linked-token");
  await symlink(fixture.config.secrets.appTokenSource, symlinkPath);
  await writeJson(fixture.configPath, {
    ...fixture.config,
    secrets: { ...fixture.config.secrets, appTokenSource: symlinkPath },
  });
  const linkedConfig = await loadOpsConfig(fixture.configPath);
  await assert.rejects(
    () => preflight(linkedConfig, manifest, { runner, platform: "linux", architecture: "x64" }),
    isOpsCode("HR-OPS-001"),
  );
});

test("rendered staging service is content-addressed, hardened, and keeps TLS away from Gateway", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadOpsConfig(fixture.configPath);
  const manifest = await loadBundleManifest(config.artifactManifest);
  const environment = renderGatewayEnvironment(config);
  const unit = renderSystemdUnit(config, manifest);
  const nginx = renderNginxConfig(config);

  assert.match(environment, /APP_TOKEN_FILE=\/run\/hermes-go\/secrets\/app-token/);
  assert.match(environment, /ACCOUNT_AUTH_ENABLED=0/);
  assert.match(unit, new RegExp(manifest.imageId));
  for (const required of ["--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--memory=256m", "--pids-limit=128", "CapabilityBoundingSet="]) {
    assert.equal(unit.includes(required), true, `${required} missing from unit`);
  }
  assert.match(unit, /src=.*\/secrets,dst=\/run\/hermes-go\/secrets,readonly/);
  assert.equal(unit.includes("/tls"), false);
  assert.equal(unit.includes(manifest.archivePath), false);
  assert.equal(unit.includes(fixture.tokens.app), false);
  assert.match(nginx, /location = \/relay-health/);
  assert.match(nginx, /ssl_protocols TLSv1\.2 TLSv1\.3/);
  assert.match(nginx, /location = \/api\/ws/);
  assert.match(nginx, /proxy_set_header X-Forwarded-For \$remote_addr/);
  assert.match(nginx, /location \/api\/ \{[\s\S]*?proxy_set_header Connection "";/);
  assert.equal(nginx.includes("/internal/version"), false);
  assert.equal(nginx.includes("$proxy_add_x_forwarded_for"), false);
});

test("command runner passes arguments literally without a shell", async (t) => {
  const fixture = await createFixture(t);
  const sentinel = path.join(fixture.base, "shell-created");
  const value = `$(touch ${sentinel})`;
  const result = createCommandRunner().run("printf", ["%s", value]);
  assert.equal(result.stdout, value);
  await assert.rejects(() => lstat(sentinel), { code: "ENOENT" });
});

test("bootstrap is resumable and idempotent but rejects a different deployment digest", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadOpsConfig(fixture.configPath);
  const manifest = await loadBundleManifest(config.artifactManifest);
  const runner = createFakeRunner(manifest, { imageLoaded: false });
  const ownership = currentOwnership();
  const options = {
    runner,
    platform: "linux",
    architecture: "x64",
    confirmation: "staging",
    getUid: () => 0,
    ownership,
    fetchImpl: healthyFetch,
    sleep: async () => {},
    now: incrementingClock(),
  };

  await assert.rejects(
    () => bootstrapStaging(config, manifest, { ...options, confirmation: undefined, runId: "run-unconfirmed" }),
    isOpsCode("HR-OPS-001"),
  );
  await assert.rejects(() => lstat(config.paths.stateRoot), { code: "ENOENT" });

  const first = await bootstrapStaging(config, manifest, { ...options, runId: "run-first" });
  const second = await bootstrapStaging(config, manifest, { ...options, runId: "run-second" });
  assert.equal(first.stage, "complete");
  assert.equal(first.resumedFrom, "none");
  assert.equal(second.resumedFrom, "complete");
  assert.equal(runner.calls.filter((call) => call.command === "docker" && call.args[0] === "load").length, 1);

  const journal = JSON.parse(await readFile(path.join(config.paths.stateRoot, "ops", "bootstrap-state.json"), "utf8"));
  assert.equal(journal.stage, "complete");
  const audit = (await readFile(path.join(config.paths.stateRoot, "ops", "operations.jsonl"), "utf8"))
    .trim()
    .split("\n")
    .map(JSON.parse);
  assert.deepEqual(audit.map((entry) => entry.result), ["started", "success", "started", "success"]);
  assert(audit.every((entry) => entry.operator === config.operator && !JSON.stringify(entry).includes(fixture.tokens.app)));

  const currentTarget = await readFile(path.join(config.paths.configRoot, "gateway.env"), "utf8");
  assert.equal(currentTarget.includes(fixture.tokens.app), false);
  assert.equal((await lstat(path.join(config.paths.configRoot, "secrets", "app-token"))).mode & 0o777, 0o440);
  const releaseName = `${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
  const installedManifest = JSON.parse(await readFile(
    path.join(config.paths.installRoot, "releases", releaseName, "bundle.manifest.json"),
    "utf8",
  ));
  assert.equal(installedManifest.imageId, manifest.imageId);
  assert.equal("archivePath" in installedManifest, false);

  const changed = { ...config, gateway: { ...config.gateway, defaultDeviceId: "another-staging-mac" } };
  await assert.rejects(
    () => bootstrapStaging(changed, manifest, { ...options, runId: "run-conflict" }),
    isOpsCode("HR-OPS-003"),
  );
});

test("bootstrap resumes from the recorded stage after an isolated smoke failure", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadOpsConfig(fixture.configPath);
  const manifest = await loadBundleManifest(config.artifactManifest);
  const ownership = currentOwnership();
  const common = {
    platform: "linux",
    architecture: "x64",
    confirmation: "staging",
    getUid: () => 0,
    ownership,
    fetchImpl: healthyFetch,
    sleep: async () => {},
    now: incrementingClock(),
  };

  const failingRunner = createFakeRunner(manifest, { imageLoaded: false, publicSmoke: false });
  await assert.rejects(
    () => bootstrapStaging(config, manifest, { ...common, runner: failingRunner, runId: "run-failed-smoke" }),
    isOpsCode("HR-OPS-003"),
  );
  const interrupted = JSON.parse(await readFile(path.join(config.paths.stateRoot, "ops", "bootstrap-state.json"), "utf8"));
  assert.equal(interrupted.stage, "services_started");

  const recoveryRunner = createFakeRunner(manifest, { imageLoaded: true, servicesActive: true });
  const recovered = await bootstrapStaging(config, manifest, {
    ...common,
    runner: recoveryRunner,
    runId: "run-recovered",
  });
  assert.equal(recovered.stage, "complete");
  assert.equal(recovered.resumedFrom, "services_started");
});

test("status is layered and doctor writes an exclusive allowlist-only private bundle", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadOpsConfig(fixture.configPath);
  const manifest = await loadBundleManifest(config.artifactManifest);
  const runner = createFakeRunner(manifest, {
    imageLoaded: true,
    servicesActive: true,
    versionOutput: "tool 1.0 token=raw-secret person@example.com /home/person/private",
  });
  const status = await getStatus(config, manifest, { runner, fetchImpl: healthyFetch });
  assert.equal(status.ok, true);
  assert.equal(status.layers.container.imageIdentity, "match");
  const degraded = await getStatus(config, manifest, {
    runner: createFakeRunner(manifest, { imageLoaded: true, servicesActive: false }),
    fetchImpl: async () => { throw new Error("offline token=must-not-leak"); },
  });
  assert.equal(degraded.ok, false);
  assert.equal(degraded.error.code, "HR-OPS-004");
  assert.equal(JSON.stringify(degraded).includes("must-not-leak"), false);

  const outputPath = path.join(fixture.base, "diagnostic.json");
  const created = await createDoctorBundle(config, manifest, outputPath, {
    runner,
    fetchImpl: healthyFetch,
    now: () => new Date("2026-09-03T00:00:00.000Z"),
  });
  assert.equal(created.outputCreated, true);
  assert.match(created.sha256, /^[0-9a-f]{64}$/);
  assert.equal((await lstat(outputPath)).mode & 0o777, 0o600);

  const reportText = await readFile(outputPath, "utf8");
  const report = JSON.parse(reportText);
  assert.equal(report.collectionPolicy.journalIncluded, false);
  assert.equal(report.collectionPolicy.requestBodiesIncluded, false);
  for (const forbidden of [
    fixture.tokens.app,
    "raw-secret",
    "person@example.com",
    "/home/person/private",
    fixture.config.secrets.appTokenSource,
    fixture.config.nginx.privateKeySource,
    "PRIVATE KEY",
  ]) {
    assert.equal(reportText.includes(forbidden), false, `doctor leaked ${forbidden}`);
  }
  assert.match(reportText, /\[REDACTED\]/);
  assert.match(reportText, /\[REDACTED_EMAIL\]/);
  assert.match(reportText, /\[REDACTED_USER_PATH\]/);

  await assert.rejects(
    () => createDoctorBundle(config, manifest, outputPath, { runner, fetchImpl: healthyFetch }),
    isOpsCode("HR-OPS-005"),
  );
  assert.deepEqual(JSON.parse(await readFile(outputPath, "utf8")), report);
});

test("Cloud Ops failures keep stable bilingual codes and redact diagnostic values", async () => {
  const codes = Object.values(OPS_ERROR_DEFINITIONS).map((definition) => definition.code);
  assert.deepEqual(codes, ["HR-OPS-001", "HR-OPS-002", "HR-OPS-003", "HR-OPS-004", "HR-OPS-005"]);
  for (const definition of Object.values(OPS_ERROR_DEFINITIONS)) {
    assert.match(definition.summaryZh, /[\u3400-\u9fff]/);
    assert.match(definition.summaryEn, /^[A-Z]/);
    assert.equal(typeof definition.retryable, "boolean");
    assert.equal(typeof definition.recoveryAction, "string");
  }
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  for (const code of codes) assert.equal(registry.includes(`| \`${code}\` |`), true);

  const privateKey = "-----BEGIN PRIVATE KEY-----\nsecret-key-material\n-----END PRIVATE KEY-----";
  const redacted = redactOpsValue(`authorization: Bearer bearer-secret cookie=a=b;c=d token=token-secret user@example.com /Users/person/private ${privateKey}`);
  for (const forbidden of ["bearer-secret", "a=b", "c=d", "token-secret", "user@example.com", "/Users/person/private", "secret-key-material"]) {
    assert.equal(redacted.includes(forbidden), false);
  }
  assert.equal(createOpsError("artifact", "token=nope").code, "HR-OPS-002");
});

test("Gateway bundle packaging and hermesctl CLI remain wired to clean immutable inputs", async () => {
  const packageScript = await readFile("scripts/package-gateway-bundle.sh", "utf8");
  assert.match(packageScript, /package-gateway-image\.sh/);
  assert.match(packageScript, /docker image save/);
  assert.match(packageScript, /GATEWAY_BUNDLE_RELEASE_OK/);
  assert.match(packageScript, /bundle_output_already_exists/);
  assert.match(packageScript, /bundle_output_relative_path_must_use_outputs/);
  assert.match(packageScript, /external_bundle_output_must_be_existing_directory/);
  assert.match(packageScript, /report_failure prerequisite/);
  assert.equal(/docker\s+(?:push|login)/.test(packageScript), false);

  const cli = await readFile("scripts/hermesctl.mjs", "utf8");
  for (const command of ["preflight", "bootstrap", "status", "doctor"]) {
    assert.equal(cli.includes(`\"${command}\"`), true);
  }
  assert.match(cli, /confirmation: args\.confirm/);
});

async function createFixture(t) {
  const createdBase = await mkdtemp(path.join(tmpdir(), "hermesctl-test-"));
  const base = await realpath(createdBase);
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const artifactDir = path.join(base, "artifacts");
  await mkdir(inputs, { recursive: true });
  await mkdir(artifactDir, { recursive: true });

  const tokens = {
    app: "a".repeat(64),
    connector: "b".repeat(64),
    internal: "c".repeat(64),
  };
  const appTokenSource = path.join(inputs, "app-token");
  const connectorTokenSource = path.join(inputs, "connector-token");
  const internalStatusTokenSource = path.join(inputs, "internal-status-token");
  const certificateSource = path.join(inputs, "fullchain.pem");
  const privateKeySource = path.join(inputs, "privkey.pem");
  await writePrivate(appTokenSource, `${tokens.app}\n`);
  await writePrivate(connectorTokenSource, `${tokens.connector}\n`);
  await writePrivate(internalStatusTokenSource, `${tokens.internal}\n`);
  await writeFile(certificateSource, `-----BEGIN CERTIFICATE-----\n${"Z".repeat(96)}\n-----END CERTIFICATE-----\n`, { mode: 0o644 });
  await writePrivate(privateKeySource, `-----BEGIN PRIVATE KEY-----\n${"K".repeat(96)}\n-----END PRIVATE KEY-----\n`);

  const sourceCommit = "a".repeat(40);
  const serverVersion = "0.2.0";
  const stem = `Hermes-Gateway-${serverVersion}-${sourceCommit.slice(0, 12)}-linux-amd64`;
  const archivePath = path.join(artifactDir, `${stem}.tar`);
  await writeFile(archivePath, "immutable-oci-archive");
  const archiveSha256 = createHash("sha256").update("immutable-oci-archive").digest("hex");
  const manifestPath = path.join(artifactDir, `${stem}.manifest.json`);
  const manifest = {
    schemaVersion: 1,
    kind: "hermes-go-gateway-oci",
    serverVersion,
    sourceCommit,
    imageReference: `hermes-remote-gateway:${serverVersion}-${sourceCommit.slice(0, 12)}`,
    imageId: `sha256:${"b".repeat(64)}`,
    architecture: "amd64",
    archiveFile: path.basename(archivePath),
    archiveSha256,
    createdAt: "2026-09-03T00:00:00.000Z",
  };
  await writeJson(manifestPath, manifest);

  const configPath = path.join(inputs, "staging.json");
  const config = {
    schemaVersion: 1,
    environment: "staging",
    operator: "ci-operator",
    artifactManifest: manifestPath,
    paths: {
      installRoot: path.join(base, "hermes-go-install"),
      configRoot: path.join(base, "hermes-go-config"),
      stateRoot: path.join(base, "hermes-go-state"),
      systemdUnitDirectory: path.join(base, "systemd"),
    },
    service: {
      name: "hermes-go-gateway-staging",
      containerName: "hermes-go-gateway-staging",
      gatewayPort: 18787,
    },
    gateway: {
      defaultDeviceId: "staging-mac",
      accountAuthEnabled: false,
      accountBindingEnabled: false,
    },
    secrets: { appTokenSource, connectorTokenSource, internalStatusTokenSource },
    nginx: {
      serverName: "staging.example.invalid",
      listenPort: 443,
      certificateSource,
      privateKeySource,
      configFile: path.join(base, "nginx", "hermes-go-staging.conf"),
    },
  };
  await writeJson(configPath, config);
  return {
    base,
    inputs,
    configPath,
    config,
    manifestPath,
    manifest,
    archivePath,
    archiveSha256,
    tokens,
  };
}

function createFakeRunner(manifest, options = {}) {
  let imageLoaded = options.imageLoaded ?? false;
  let servicesActive = options.servicesActive ?? false;
  const imageId = options.imageId ?? manifest.imageId;
  const calls = [];
  return {
    calls,
    run(command, args = []) {
      calls.push({ command, args: [...args] });
      if (command === "which") return success(`/usr/bin/${args[0]}\n`);
      if (command === "docker" && args[0] === "info") return success("linux/x86_64\n");
      if (command === "docker" && args[0] === "image" && args[1] === "inspect") {
        return imageLoaded ? success(`${imageId}|amd64\n`) : failure();
      }
      if (command === "docker" && args[0] === "load") {
        imageLoaded = true;
        return success();
      }
      if (command === "docker" && args[0] === "container") {
        return servicesActive ? success(`running|${manifest.imageId}\n`) : failure();
      }
      if (command === "docker" && args[0] === "--version") return success(`${options.versionOutput || "Docker 27"}\n`);
      if (command === "systemctl" && args[0] === "is-system-running") return success("running\n");
      if (command === "systemctl" && args[0] === "is-active") return servicesActive ? success() : failure();
      if (command === "systemctl" && args[0] === "enable") {
        servicesActive = true;
        return success();
      }
      if (command === "systemctl" && args[0] === "--version") return success(`${options.versionOutput || "systemd 257"}\n`);
      if (command === "nginx" && args[0] === "-v") return success("", `${options.versionOutput || "nginx/1.26"}\n`);
      if (command === "ss") return success();
      if (command === "curl") return options.publicSmoke === false ? failure() : success();
      return success();
    },
  };
}

async function healthyFetch(url) {
  const status = String(url).endsWith("/readyz") ? "ready" : "alive";
  return new Response(JSON.stringify({ status }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function currentOwnership() {
  const uid = process.getuid?.() ?? 0;
  const gid = process.getgid?.() ?? 0;
  return {
    host: { uid, gid },
    container: { uid, gid },
    secret: { uid, gid },
  };
}

function incrementingClock() {
  let value = Date.parse("2026-09-03T00:00:00.000Z");
  return () => {
    const date = new Date(value);
    value += 1000;
    return date;
  };
}

async function writePrivate(filePath, content) {
  await writeFile(filePath, content, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}

function success(stdout = "", stderr = "") {
  return { status: 0, stdout, stderr };
}

function failure(stdout = "", stderr = "") {
  return { status: 1, stdout, stderr };
}

function isOpsCode(code) {
  return (error) => error instanceof OpsError && createOpsError(error.kind, error.technicalCause, error.stage).code === code;
}
