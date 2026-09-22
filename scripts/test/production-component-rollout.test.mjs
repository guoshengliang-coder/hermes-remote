import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";
import { renderEmailRolloutEnvironment } from "../../ops/lib/production-account-rollout.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderBindingRolloutEnvironment,
  renderIdentityWebRolloutEnvironment,
  renderMultiDeviceRolloutEnvironment,
  renderSharingRolloutEnvironment,
} from "../../ops/lib/production-release-environment.mjs";
import { loadProductionComponentRolloutConfig } from "../../ops/lib/production-component-rollout-config.mjs";
import { executeProductionComponentRollout } from "../../ops/lib/production-component-rollout.mjs";

const COMMIT = "c".repeat(40);
const MANIFEST_SHA = "a".repeat(64);
const PUBLIC_KEY = "A".repeat(43);

test("production component rollout config pins the exact same-origin immutable manifest", async (t) => {
  const fixture = await createFixture(t);
  const parsed = await loadProductionComponentRolloutConfig(fixture.componentConfigPath);
  assert.equal(parsed.componentRelease.schemaVersion, 2);
  assert.equal(parsed.componentRelease.releaseVersion, "0.4.0");
  assert.equal(parsed.componentRelease.manifestSha256, MANIFEST_SHA);
  assert.equal(parsed.componentRelease.publicKey, PUBLIC_KEY);

  const raw = JSON.parse(await readFile(fixture.componentConfigPath, "utf8"));
  raw.componentRelease.manifestUrl = "https://other.example/desktop/components/0.4.0/Hermes-Desktop-Components-0.4.0-arm64.manifest.json";
  await writeJson(fixture.componentConfigPath, raw);
  await assert.rejects(
    () => loadProductionComponentRolloutConfig(fixture.componentConfigPath),
    (error) => error?.kind === "productionComponentRollout"
      && error?.technicalCause === "component_rollout_manifest_url_invalid",
  );
});

test("production component rollout changes only the component flag and commits after two checks", async (t) => {
  const fixture = await createFixture(t);
  const before = await readFile(fixture.environmentPath);
  let publishedChecks = 0;
  let enabledChecks = 0;
  const commands = [];
  const result = await executeProductionComponentRollout(fixture.componentConfig, {
    ...authorizedOptions(fixture),
    runner: runner(commands),
    verifyPublished: async () => { publishedChecks += 1; },
    verifyPrevious: async () => "offline",
    verifyEnabled: async () => {
      enabledChecks += 1;
      assert.match(await readFile(fixture.environmentPath, "utf8"), /^ACCOUNT_DESKTOP_COMPONENT_INSTALL_ENABLED=1$/m);
      return "offline";
    },
  });

  assert.equal(result.stage, "committed");
  assert.equal(result.componentManifestSchemaVersion, 2);
  assert.equal(publishedChecks, 3);
  assert.equal(enabledChecks, 2);
  const after = await readFile(fixture.environmentPath, "utf8");
  assert.equal(after, before.toString("utf8").replace(
    "ACCOUNT_DESKTOP_COMPONENT_INSTALL_ENABLED=0",
    "ACCOUNT_DESKTOP_COMPONENT_INSTALL_ENABLED=1",
  ));
  assert.deepEqual(commands.filter((entry) => entry[0] === "systemctl" && entry[1] === "restart"), [
    ["systemctl", "restart", "gateway-blue.service"],
  ]);
  const journal = JSON.parse(await readFile(path.join(fixture.stateRoot, "ops", "component-rollout.json"), "utf8"));
  assert.equal(journal.stage, "committed");
  assert.equal(journal.componentManifestSha256, MANIFEST_SHA);
});

test("failed component verification restores the exact sharing environment", async (t) => {
  const fixture = await createFixture(t);
  const before = await readFile(fixture.environmentPath);
  const commands = [];
  await assert.rejects(
    () => executeProductionComponentRollout(fixture.componentConfig, {
      ...authorizedOptions(fixture),
      runner: runner(commands),
      verifyPublished: async () => {},
      verifyPrevious: async () => "offline",
      verifyEnabled: async () => { throw new Error("enabled capability missing secret@example.com"); },
    }),
    (error) => error?.kind === "productionComponentRollout"
      && error?.code === undefined
      && !error?.technicalCause?.includes("secret@example.com"),
  );
  assert.deepEqual(await readFile(fixture.environmentPath), before);
  assert.equal(commands.filter((entry) => entry[0] === "systemctl" && entry[1] === "restart").length, 2);
  const journal = JSON.parse(await readFile(path.join(fixture.stateRoot, "ops", "component-rollout.json"), "utf8"));
  assert.equal(journal.stage, "rolled_back");
});

test("component rollout error is bilingual, retryable, registered, and CLI fails closed", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionComponentRollout;
  assert.equal(definition.code, "HR-OPS-025");
  assert.match(definition.summaryZh, /[\u3400-\u9fff]/);
  assert.match(definition.summaryEn, /^[A-Z]/);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "inspect_component_rollout_stage_and_retry");
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-025`/);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "component-rollout-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const configRoot = path.join(base, "config");
  const stateRoot = path.join(base, "state");
  const slotsRoot = path.join(configRoot, "slots");
  const secretsRoot = path.join(base, "secrets");
  await mkdir(path.join(slotsRoot, "blue"), { recursive: true });
  await mkdir(secretsRoot, { recursive: true });
  await mkdir(path.join(stateRoot, "ops"), { recursive: true, mode: 0o700 });
  const environmentPath = path.join(slotsRoot, "blue", "gateway.env");
  const appTokenSource = path.join(secretsRoot, "app-token");
  const internalStatusTokenSource = path.join(secretsRoot, "internal-status-token");
  await writeFile(appTokenSource, `${"a".repeat(64)}\n`, { mode: 0o600 });
  await writeFile(internalStatusTokenSource, `${"b".repeat(64)}\n`, { mode: 0o600 });
  const releaseConfig = {
    host: { hostname: "gateway-prod", architecture: "amd64" },
    gateway: { accountAuthEnabled: false, accountBindingEnabled: false, defaultDeviceId: "production-mac" },
    database: null,
    paths: { installRoot: path.join(configRoot, "..", "install"), configRoot, stateRoot },
    nginx: { listenPort: 443, serverName: "gateway.example.com" },
    slots: { blue: { serviceName: "gateway-blue", gatewayPort: 18787 } },
    secrets: { appTokenSource, internalStatusTokenSource },
    targetArtifactManifest: path.join(base, "target.manifest.json"),
  };
  const accountConfig = {
    gateway: { emailIssuer: "https://gateway.example.com", origin: "https://gateway.example.com", trustLoopbackProxy: true },
    database: { ssl: true },
  };
  await writeFile(environmentPath, renderEmailRolloutEnvironment(releaseConfig, accountConfig, "blue"), { mode: 0o600 });
  let inspected = await inspectProductionReleaseEnvironment(releaseConfig, "blue");
  await writeFile(environmentPath, renderBindingRolloutEnvironment(releaseConfig, "blue", inspected), { mode: 0o600 });
  inspected = await inspectProductionReleaseEnvironment(releaseConfig, "blue");
  await writeFile(environmentPath, renderMultiDeviceRolloutEnvironment(releaseConfig, "blue", inspected), { mode: 0o600 });
  inspected = await inspectProductionReleaseEnvironment(releaseConfig, "blue");
  await writeFile(environmentPath, renderIdentityWebRolloutEnvironment(releaseConfig, "blue", inspected), { mode: 0o600 });
  inspected = await inspectProductionReleaseEnvironment(releaseConfig, "blue");
  await writeFile(environmentPath, renderSharingRolloutEnvironment(releaseConfig, "blue", inspected), { mode: 0o600 });

  const componentConfigPath = path.join(base, "component-rollout.json");
  const componentConfig = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    productionReleaseConfig: path.join(base, "production-release.json"),
    host: { hostname: "gateway-prod", architecture: "amd64" },
    gateway: { origin: "https://gateway.example.com", runtimeContract: "hermes-serve-v1" },
    componentRelease: {
      schemaVersion: 2,
      releaseVersion: "0.4.0",
      manifestUrl: "https://gateway.example.com/desktop/components/0.4.0/Hermes-Desktop-Components-0.4.0-arm64.manifest.json",
      manifestSha256: MANIFEST_SHA,
      keyId: "desktop-internal-2026-a",
      publicKey: PUBLIC_KEY,
    },
    deployment: { observationSeconds: 1 },
  };
  await writeJson(componentConfigPath, componentConfig);
  return { base, stateRoot, environmentPath, releaseConfig, componentConfig, componentConfigPath };
}

function authorizedOptions(fixture) {
  const manifest = {
    schemaVersion: 3,
    serverVersion: "0.4.16",
    sourceCommit: COMMIT,
    imageId: `sha256:${"1".repeat(64)}`,
    containerdImageId: `sha256:${"2".repeat(64)}`,
    archiveSha256: "3".repeat(64),
    releaseContract: { databaseSchemaVersion: 15, supportedPostgresqlMajors: [18] },
  };
  return {
    confirmation: "production:gateway-prod",
    getUid: () => 0,
    platform: "linux",
    architecture: "x64",
    hostname: "gateway-prod",
    ownership: { host: { uid: process.getuid(), gid: process.getgid() } },
    loadReleaseConfig: async () => fixture.releaseConfig,
    loadCurrentManifest: async () => manifest,
    loadBundleManifest: async () => manifest,
    resolveActiveSlot: async () => "blue",
    sleep: async () => {},
    now: () => new Date("2026-09-14T10:00:00.000Z"),
  };
}

function runner(commands) {
  return {
    run(command, args) {
      commands.push([command, ...args]);
      return { status: 0, stdout: "", stderr: "" };
    },
  };
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}
