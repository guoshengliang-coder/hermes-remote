import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, readlink, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";
import { loadManagedBaselineConfig } from "../../ops/lib/managed-baseline-config.mjs";
import {
  executeManagedBaseline,
  legacyIdentityDigest,
  legacySourceManifest,
  seedLegacyBaseline,
  verifyManagedBaselineAdmission,
} from "../../ops/lib/managed-baseline.mjs";
import {
  createProductionBaselineBundleManifest,
  loadProductionBaselineBundleManifest,
} from "../../ops/lib/production-baseline-bundle.mjs";

test("R5-D config is production-only, account-disabled, and topologically strict", async (t) => {
  const fixture = await createFixture(t);
  const schema = JSON.parse(await readFile("ops/hermesctl-managed-baseline-config.schema.json", "utf8"));
  const example = JSON.parse(await readFile("ops/production.managed-baseline.example.json", "utf8"));
  assert.equal(example.environment, "production");
  assert.equal(example.database, null);
  assert.equal(schema.additionalProperties, false);
  assert.equal(schema.properties.gateway.properties.accountAuthEnabled.const, false);

  const parsed = await loadManagedBaselineConfig(fixture.configPath);
  assert.equal(parsed.managedBaseline, true);
  assert.equal(parsed.environment, "production");
  assert.equal(parsed.database, null);

  await writeJson(fixture.configPath, {
    ...fixture.rawConfig,
    nginx: {
      ...fixture.rawConfig.nginx,
      configFile: path.join(fixture.base, "nginx", "hermes-edge.conf"),
    },
  });
  assert.equal((await loadManagedBaselineConfig(fixture.configPath)).nginx.configFile.endsWith("hermes-edge.conf"), true);
  await writeJson(fixture.configPath, {
    ...fixture.rawConfig,
    nginx: {
      ...fixture.rawConfig.nginx,
      configFile: path.join(fixture.base, "nginx", "unmanaged-edge.conf"),
    },
  });
  await assert.rejects(() => loadManagedBaselineConfig(fixture.configPath), isCode("HR-OPS-001"));

  await writeJson(fixture.configPath, { ...fixture.rawConfig, environment: "staging" });
  await assert.rejects(() => loadManagedBaselineConfig(fixture.configPath), isCode("HR-OPS-001"));
  await writeJson(fixture.configPath, {
    ...fixture.rawConfig,
    gateway: { ...fixture.rawConfig.gateway, accountAuthEnabled: true },
  });
  await assert.rejects(() => loadManagedBaselineConfig(fixture.configPath), isCode("HR-OPS-001"));
  await writeJson(fixture.configPath, { ...fixture.rawConfig, unexpected: true });
  await assert.rejects(() => loadManagedBaselineConfig(fixture.configPath), isCode("HR-OPS-001"));
});

test("R5-D admission binds the exact host, legacy hashes, fresh off-host evidence, and target contract", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const calls = [];
  const runner = activeLegacyRunner(calls);
  let legacySmoke = 0;
  const source = await verifyManagedBaselineAdmission(config, fixture.targetManifest, {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    getUid: () => 0,
    runner,
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
    legacySmoke: async () => { legacySmoke += 1; },
    now: () => new Date("2026-09-04T12:00:00.000Z"),
  });
  assert.equal(source.serverVersion, "0.2.0");
  assert.equal(source.imageId, `sha256:${fixture.identityDigest}`);
  assert.equal(legacySmoke, 1);
  assert.deepEqual(calls, [{
    command: "systemctl",
    args: ["is-active", "--quiet", "hermes-remote-gateway.service"],
  }]);

  await assert.rejects(() => verifyManagedBaselineAdmission(config, fixture.targetManifest, {
    confirmation: "production:wrong-host",
  }), isCode("HR-OPS-014"));
  await writeFile(fixture.identityFiles[0].path, "tampered\n");
  await assert.rejects(() => verifyManagedBaselineAdmission(config, fixture.targetManifest, {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    getUid: () => 0,
    runner,
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
    legacySmoke: async () => {},
    now: () => new Date("2026-09-04T12:00:00.000Z"),
  }), isCode("HR-OPS-014"));
});

test("R5-D production execution creates its command runner before admission", async (t) => {
  const fixture = await createFixture(t);
  await writeJson(fixture.configPath, {
    ...fixture.rawConfig,
    legacySource: {
      ...fixture.rawConfig.legacySource,
      serviceName: "hermes-r5d-test-service-does-not-exist",
    },
  });
  const config = await loadManagedBaselineConfig(fixture.configPath);
  await assert.rejects(() => executeManagedBaseline(config, fixture.targetManifest, {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    getUid: () => 0,
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
    legacySmoke: async () => {},
  }), (error) => error?.technicalCause === "managed_baseline_legacy_service_inactive");
});

test("R5-D seeds an immutable legacy rollback descriptor and delegates only through the production capability", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const source = legacySourceManifest(config);
  await seedLegacyBaseline(config, source, { ownership: currentOwnership() });
  const release = `releases/${source.serverVersion}-${source.sourceCommit.slice(0, 12)}`;
  assert.equal(await readlink(path.join(config.paths.installRoot, "current")), release);
  const descriptor = JSON.parse(await readFile(
    path.join(config.paths.installRoot, release, "legacy.manifest.json"),
    "utf8",
  ));
  assert.equal(descriptor.kind, "hermes-go-managed-legacy-v1");
  assert.equal(descriptor.identityDigest, fixture.identityDigest);
  await seedLegacyBaseline(config, source, { ownership: currentOwnership() });

  let delegated;
  const result = await executeManagedBaseline(config, fixture.targetManifest, {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    getUid: () => 0,
    runner: activeLegacyRunner([]),
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
    legacySmoke: async () => {},
    now: () => new Date("2026-09-04T12:00:00.000Z"),
    ownership: currentOwnership(),
    seedLegacyBaseline: async () => {},
    executeDeployment: async (_config, _target, options) => {
      delegated = options;
      return { ok: true, stage: "committed", activeSlot: "blue" };
    },
  });
  assert.equal(delegated.authorization, "production-managed-baseline");
  assert.equal(delegated.operation, "deploy");
  assert.equal(delegated.sourceManifest.imageId, `sha256:${fixture.identityDigest}`);
  assert.equal(result.command, "managed-baseline");
  assert.equal(result.legacyRollbackPoint, release);
});

test("R5-D error is bilingual, retryable, registered, and redacted", async () => {
  const definition = OPS_ERROR_DEFINITIONS.managedBaseline;
  assert.equal(definition.code, "HR-OPS-014");
  assert.match(definition.summaryZh, /受管基线/);
  assert.match(definition.summaryEn, /managed production Gateway baseline/);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "inspect_managed_baseline_stage_and_retry");
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-014`/);
});

test("R5-D operator bundle manifest binds one safe archive to the exact source commit", async (t) => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "managed-baseline-bundle-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const sourceCommit = "a".repeat(40);
  const archiveFile = `Hermes-R5D-Ops-${sourceCommit.slice(0, 12)}.tar.gz`;
  const archivePath = path.join(base, archiveFile);
  const manifestPath = path.join(base, `Hermes-R5D-Ops-${sourceCommit.slice(0, 12)}.manifest.json`);
  await writeFile(archivePath, "operator bundle\n", { mode: 0o644 });
  const archiveSha256 = await sha256(archivePath);
  const manifest = createProductionBaselineBundleManifest({
    sourceCommit,
    createdAt: "2026-09-04T12:00:00.000Z",
    archiveFile,
    archiveSha256,
  });
  await writeJson(manifestPath, manifest);
  const parsed = await loadProductionBaselineBundleManifest(manifestPath);
  assert.equal(parsed.schemaVersion, 2);
  assert.equal(parsed.kind, "hermes-go-production-baseline-bundle-v2");
  assert.equal(parsed.sourceCommit, sourceCommit);
  assert.equal(parsed.entrypoint, "scripts/production-baseline.mjs");
  assert.equal(parsed.connectorEntry, "connector/dist/index.js");
  assert.equal(parsed.smokeRuntimeEntry, "ops/lib/production-smoke-runtime.mjs");

  const legacyManifest = { ...manifest };
  delete legacyManifest.smokeRuntimeEntry;
  legacyManifest.schemaVersion = 1;
  legacyManifest.kind = "hermes-go-production-baseline-bundle-v1";
  await writeJson(manifestPath, legacyManifest);
  await assert.rejects(() => loadProductionBaselineBundleManifest(manifestPath), isCode("HR-OPS-014"));
  assert.equal((await loadProductionBaselineBundleManifest(manifestPath, { allowLegacySchema: true })).schemaVersion, 1);
  await writeJson(manifestPath, manifest);
  const incompleteManifest = { ...manifest };
  delete incompleteManifest.smokeRuntimeEntry;
  await writeJson(manifestPath, incompleteManifest);
  await assert.rejects(() => loadProductionBaselineBundleManifest(manifestPath), isCode("HR-OPS-014"));
  await writeJson(manifestPath, manifest);

  await writeFile(archivePath, "tampered\n", { mode: 0o644 });
  await assert.rejects(() => loadProductionBaselineBundleManifest(manifestPath), isCode("HR-OPS-014"));
  await writeFile(archivePath, "operator bundle\n", { mode: 0o644 });
  await writeJson(manifestPath, { ...manifest, unexpected: true });
  await assert.rejects(() => loadProductionBaselineBundleManifest(manifestPath), isCode("HR-OPS-014"));
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "managed-baseline-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const legacy = path.join(base, "legacy");
  await mkdir(inputs, { recursive: true });
  await mkdir(legacy, { recursive: true });
  const identityFiles = [
    { path: path.join(legacy, "package.json") },
    { path: path.join(legacy, "index.js") },
  ];
  await writeFile(identityFiles[0].path, "{\"version\":\"legacy\"}\n");
  await writeFile(identityFiles[1].path, "export {};\n");
  for (const entry of identityFiles) entry.sha256 = await sha256(entry.path);
  const identityDigest = legacyIdentityDigest(identityFiles);
  const evidencePath = path.join(inputs, "legacy-recovery.json");
  await writeJson(evidencePath, {
    schemaVersion: 1,
    kind: "hermes-go-legacy-recovery-v1",
    sourceHostname: "prod-host",
    createdAt: "2026-09-04T10:00:00.000Z",
    artifactSha256: "e".repeat(64),
    subject: { identityDigest },
    restoreHostname: "restore-host",
    restoredAt: "2026-09-04T11:00:00.000Z",
    verifiedChecks: ["archive_hash", "files_restored", "service_start"],
  });
  const targetManifest = {
    schemaVersion: 2,
    serverVersion: "0.4.0",
    sourceCommit: "c".repeat(40),
    imageId: `sha256:${"d".repeat(64)}`,
    releaseContract: {
      manifestVersion: 2,
      minimumSourceVersion: "0.2.0",
      maintenanceRequired: true,
      rollbackSupported: true,
    },
  };
  const rawConfig = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    targetArtifactManifest: path.join(inputs, "target.manifest.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    paths: {
      installRoot: path.join(base, "install"),
      configRoot: path.join(base, "config"),
      stateRoot: path.join(base, "state"),
      systemdUnitDirectory: path.join(base, "systemd"),
    },
    legacySource: {
      serviceName: "hermes-remote-gateway",
      containerName: "hermes-remote-gateway-legacy",
      gatewayPort: 18444,
      stateDirectory: path.join(legacy, "state"),
      compatibilityVersion: "0.2.0",
      identityFiles,
      recoveryEvidence: evidencePath,
    },
    slots: {
      blue: { serviceName: "hermes-go-gateway-blue", containerName: "hermes-go-gateway-blue", gatewayPort: 18787 },
      green: { serviceName: "hermes-go-gateway-green", containerName: "hermes-go-gateway-green", gatewayPort: 18788 },
    },
    gateway: { defaultDeviceId: "production-mac", accountAuthEnabled: false, accountBindingEnabled: false },
    secrets: {
      appTokenSource: path.join(inputs, "app-token"),
      connectorTokenSource: path.join(inputs, "connector-token"),
      internalStatusTokenSource: path.join(inputs, "internal-token"),
    },
    database: null,
    nginx: {
      serverName: "gateway.example.com",
      listenPort: 443,
      certificateSource: path.join(inputs, "fullchain.pem"),
      privateKeySource: path.join(inputs, "privkey.pem"),
      candidateConfigSource: path.join(inputs, "hermes-remote-gateway.candidate.conf"),
      configFile: path.join(base, "nginx", "hermes-go-production.conf"),
      upstreamConfigFile: path.join(base, "nginx", "hermes-go-production-upstream.conf"),
    },
    deployment: { drainTimeoutSeconds: 60, observationSeconds: 30 },
  };
  await writeFile(rawConfig.nginx.candidateConfigSource, [
    `include ${rawConfig.nginx.upstreamConfigFile};`,
    `server { listen 443 ssl; server_name ${rawConfig.nginx.serverName};`,
    "location /api/ { proxy_pass http://hermes_go_gateway_production; }",
    "}",
    "",
  ].join("\n"), { mode: 0o600 });
  rawConfig.nginx.candidateConfigSha256 = await sha256(rawConfig.nginx.candidateConfigSource);
  const configPath = path.join(inputs, "managed-baseline.json");
  await writeJson(configPath, rawConfig);
  return { base, configPath, rawConfig, identityFiles, identityDigest, evidencePath, targetManifest };
}

function activeLegacyRunner(calls) {
  return {
    run(command, args) {
      calls.push({ command, args: [...args] });
      return { status: command === "systemctl" ? 0 : 1, stdout: "", stderr: "" };
    },
  };
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

async function sha256(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

function currentOwnership() {
  return { host: { uid: process.getuid(), gid: process.getgid() } };
}

function isCode(code) {
  return (error) => error && OPS_ERROR_DEFINITIONS[error.kind]?.code === code;
}
