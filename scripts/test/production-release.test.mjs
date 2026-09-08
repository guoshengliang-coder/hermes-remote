import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { executeDeployment } from "../../ops/lib/deploy-command.mjs";
import { satisfiesProductionNginxContract } from "../../ops/lib/deploy-switch.mjs";
import { renderNginxUpstream } from "../../ops/lib/deploy-system.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";
import { loadManagedBaselineConfig } from "../../ops/lib/managed-baseline-config.mjs";
import {
  executeProductionRelease,
  verifyProductionReleaseAdmission,
  verifyReleaseInputs,
} from "../../ops/lib/production-release.mjs";

const CURRENT_COMMIT = "c".repeat(40);
const NEXT_COMMIT = "f".repeat(40);
const LEGACY_DIGEST = "1".repeat(64);
const CURRENT_RELEASE = `releases/0.4.0-${CURRENT_COMMIT.slice(0, 12)}`;
const LEGACY_RELEASE = `releases/0.2.0-${LEGACY_DIGEST.slice(0, 12)}`;

test("R5-F1 admission binds the exact host, confirmation, root, smoke callbacks and the disabled account/database flags", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const base = releaseOptions(fixture);
  const cases = [
    [{ operation: "restart" }, "production_release_operation_invalid"],
    [{ confirmation: "production:other-host" }, "production_release_confirmation_required"],
    [{ getUid: () => 501 }, "production_release_requires_root"],
    [{ hostname: "other-host" }, "production_release_host_mismatch"],
    [{ architecture: "arm64" }, "production_release_host_mismatch"],
    [{ publicSmoke: undefined }, "production_release_smoke_callbacks_required"],
  ];
  for (const [override, cause] of cases) {
    await assert.rejects(
      () => verifyProductionReleaseAdmission(config, fixture.nextManifest, { ...base, ...override }),
      (error) => error?.technicalCause === cause && error.kind === "productionRelease",
      cause,
    );
  }
  await assert.rejects(
    () => verifyProductionReleaseAdmission(
      { ...config, gateway: { ...config.gateway, accountAuthEnabled: true } },
      fixture.nextManifest,
      base,
    ),
    (error) => error?.technicalCause === "production_release_account_and_database_must_stay_disabled",
  );
  await assert.rejects(
    () => verifyProductionReleaseAdmission(
      config,
      { ...fixture.nextManifest, releaseContract: { ...fixture.nextManifest.releaseContract, rollbackSupported: false } },
      base,
    ),
    (error) => error?.technicalCause === "production_release_target_contract_invalid" && error.kind === "compatibility",
  );
  const admitted = await verifyProductionReleaseAdmission(config, fixture.nextManifest, base);
  assert.equal(admitted.activeSlot, "blue");
  assert.equal(admitted.sourceManifest.serverVersion, "0.4.0");
  assert.equal(admitted.sourceManifest.schemaVersion, 3);
});

test("R5-F1 refuses to run before R5-D committed a managed release behind current", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const base = releaseOptions(fixture);

  // No committed journal: the R5-D adoption never finished.
  await rm(fixture.journalPath);
  await assert.rejects(
    () => verifyProductionReleaseAdmission(config, fixture.nextManifest, base),
    (error) => error?.technicalCause?.startsWith("production_release_journal_invalid:") && error.kind === "productionRelease",
  );

  // `current` still points at the legacy descriptor: there is no managed release to move.
  await rm(path.join(config.paths.installRoot, "current"));
  await symlink(LEGACY_RELEASE, path.join(config.paths.installRoot, "current"));
  await assert.rejects(
    () => verifyProductionReleaseAdmission(config, fixture.nextManifest, base),
    (error) => error?.technicalCause?.startsWith("production_release_current_release_unreadable:"),
  );
});

test("R5-F1 release inputs re-check the live edge before any service is stopped", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  await verifyReleaseInputs(config, "blue", slotRunner({ blue: true }));

  await assert.rejects(
    () => verifyReleaseInputs(config, "blue", slotRunner({ blue: false })),
    (error) => error?.technicalCause === "production_release_active_slot_inactive",
  );
  await assert.rejects(
    () => verifyReleaseInputs(config, "blue", slotRunner({ blue: true, legacy: true })),
    (error) => error?.technicalCause === "production_release_legacy_service_still_active",
  );
  await assert.rejects(
    () => verifyReleaseInputs(config, "green", slotRunner({ green: true })),
    (error) => error?.technicalCause === "production_release_upstream_not_active_slot",
  );
  await writeFile(config.nginx.configFile, [
    `include ${config.nginx.upstreamConfigFile};`,
    `server { listen 443 ssl; server_name ${config.nginx.serverName};`,
    `location /api/ { proxy_pass http://127.0.0.1:${config.legacySource.gatewayPort}; }`,
    "}",
    "",
  ].join("\n"), { mode: 0o644 });
  await assert.rejects(
    () => verifyReleaseInputs(config, "blue", slotRunner({ blue: true })),
    (error) => error?.technicalCause === "production_release_live_nginx_contract_invalid",
  );
});

test("R5-F1 rollback only targets the release behind previous", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const legacyTarget = {
    ...fixture.nextManifest,
    serverVersion: "0.2.0",
    sourceCommit: LEGACY_DIGEST.slice(0, 40),
  };
  await assert.rejects(
    () => verifyProductionReleaseAdmission(config, legacyTarget, { ...releaseOptions(fixture), operation: "rollback" }),
    (error) => error?.technicalCause === "production_release_rollback_to_legacy_requires_recovery",
    "previous is the legacy descriptor right after R5-D: that is an R5-B recovery, not a slot rollback",
  );
  await assert.rejects(
    () => verifyProductionReleaseAdmission(config, { ...fixture.nextManifest, serverVersion: "0.3.0" }, { ...releaseOptions(fixture), operation: "rollback" }),
    (error) => error?.technicalCause === "production_release_rollback_target_not_previous",
  );
  // After a committed 0.4.1 release, previous is 0.4.0 and rollback to it is admitted.
  const installRoot = config.paths.installRoot;
  const nextRelease = `releases/0.4.1-${NEXT_COMMIT.slice(0, 12)}`;
  await mkdir(path.join(installRoot, nextRelease), { recursive: true });
  await writeJson(path.join(installRoot, nextRelease, "bundle.manifest.json"), fixture.nextManifest, 0o644);
  await rm(path.join(installRoot, "current"));
  await rm(path.join(installRoot, "previous"));
  await symlink(nextRelease, path.join(installRoot, "current"));
  await symlink(CURRENT_RELEASE, path.join(installRoot, "previous"));
  await writeJson(fixture.journalPath, committedJournal({
    activeSlot: "blue",
    candidateSlot: "green",
    source: identity(fixture.currentManifest),
    target: identity(fixture.nextManifest),
    currentReleaseTarget: CURRENT_RELEASE,
    previousReleaseTarget: LEGACY_RELEASE,
  }));
  await writeFile(config.nginx.upstreamConfigFile, renderNginxUpstream(config, "green"), { mode: 0o644 });
  const admitted = await verifyProductionReleaseAdmission(config, fixture.currentManifest, {
    ...releaseOptions(fixture),
    operation: "rollback",
    runner: slotRunner({ green: true }),
  });
  assert.equal(admitted.activeSlot, "green");
  assert.equal(admitted.sourceManifest.serverVersion, "0.4.1");
});

test("R5-F1 delegates only through the production-release capability with the committed slot", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  let delegated;
  const result = await executeProductionRelease(config, fixture.nextManifest, {
    ...releaseOptions(fixture),
    now: () => new Date("2026-09-07T12:00:00.000Z"),
    executeDeployment: async (_config, target, options) => {
      delegated = options;
      assert.equal(target.serverVersion, "0.4.1");
      await options.sourcePreflight();
      return { ok: true, stage: "committed", activeSlot: "green", previousSlot: "blue" };
    },
  });
  assert.equal(delegated.authorization, "production-release");
  assert.equal(delegated.operation, "deploy");
  assert.equal(delegated.confirmation, "production:prod-host");
  assert.equal(delegated.sourceManifest.serverVersion, "0.4.0");
  assert.equal(delegated.legacySmoke, undefined);
  assert.equal(result.command, "production-deploy");
  assert.equal(result.activeSlotBefore, "blue");
  assert.equal(result.rollbackPoint, CURRENT_RELEASE);

  await assert.rejects(
    () => executeProductionRelease(config, fixture.nextManifest, {
      ...releaseOptions(fixture),
      executeDeployment: async () => {
        throw Object.assign(new Error("boom"), { stage: "candidate_smoke", technicalCause: "gateway_smoke_failed=1" });
      },
    }),
    (error) => error?.kind === "productionRelease"
      && error.technicalCause === "candidate_smoke:gateway_smoke_failed=1"
      && error.stage === "candidate_smoke",
  );
});

test("the R4 deployment command admits production-release only for a committed managed slot with a preflight", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const calls = [];
  const stubs = {
    prepareCandidate: async (_config, source, _target, options) => {
      calls.push(["prepare", source.serverVersion, options.activeSlot, options.authorization]);
      return { stage: "candidate_verified" };
    },
    switchCandidate: async (_config, source, _target, options) => {
      calls.push(["switch", source.serverVersion, options.activeSlot, options.authorization]);
      return { ok: true, stage: "committed" };
    },
  };
  const common = {
    operation: "deploy",
    confirmation: "production:prod-host",
    sourceManifest: fixture.currentManifest,
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
    ownership: currentOwnership(),
    getUid: () => 0,
    platform: "linux",
    architecture: "x64",
    ...stubs,
  };
  await assert.rejects(
    () => executeDeployment(config, fixture.nextManifest, { ...common, authorization: "production-release" }),
    (error) => error?.technicalCause === "staging_confirmation_required",
    "a production release without the edge preflight is not a staging deploy either",
  );
  await assert.rejects(
    () => executeDeployment(config, fixture.nextManifest, {
      ...common,
      authorization: "production-release",
      sourcePreflight: async () => {},
      sourceManifest: { ...fixture.currentManifest, schemaVersion: 1 },
    }),
    (error) => error?.technicalCause === "staging_confirmation_required",
    "the legacy descriptor can never be the source of a routine release",
  );
  const result = await executeDeployment(config, fixture.nextManifest, {
    ...common,
    authorization: "production-release",
    sourcePreflight: async () => {},
  });
  assert.deepEqual(calls, [
    ["prepare", "0.4.0", "blue", "production-release"],
    ["switch", "0.4.0", "blue", "production-release"],
  ]);
  assert.equal(result.command, "deploy");

  await rm(fixture.journalPath);
  await assert.rejects(
    () => executeDeployment(config, fixture.nextManifest, {
      ...common,
      authorization: "production-release",
      sourcePreflight: async () => {},
    }),
    (error) => error?.technicalCause === "current_r4_release_requires_committed_journal",
  );
});

test("the production Nginx contract accepts the R5-D site file and rejects a legacy proxy or a second include", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadManagedBaselineConfig(fixture.configPath);
  const site = await readFile(config.nginx.configFile, "utf8");
  assert.equal(satisfiesProductionNginxContract(config, site), true);
  assert.equal(satisfiesProductionNginxContract(config, `${site}include ${config.nginx.upstreamConfigFile};\n`), false);
  assert.equal(satisfiesProductionNginxContract(config, site.replace("hermes_go_gateway_production", `127.0.0.1:${config.legacySource.gatewayPort}`)), false);
  assert.equal(satisfiesProductionNginxContract(config, site.replace(config.nginx.serverName, "other.example.com")), false);
});

test("R5-F1 error is bilingual, retryable, registered, and its entrypoint fails closed on arguments", async () => {
  const definition = OPS_ERROR_DEFINITIONS.productionRelease;
  assert.equal(definition.code, "HR-OPS-016");
  assert.equal(definition.retryable, true);
  assert.match(definition.summaryZh, /[㐀-鿿]/);
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.equal(registry.includes("| `HR-OPS-016` |"), true);

  for (const args of [[], ["--config", "/x.json", "--confirm", "production:h"], ["--config", "/x.json", "--confirm", "staging", "--operation", "deploy"], ["--config", "/x.json", "--confirm", "production:h", "--operation", "restart"]]) {
    const result = spawnSync(process.execPath, ["scripts/production-release.mjs", ...args], {
      encoding: "utf8",
      env: {},
      timeout: 10_000,
      stdio: ["ignore", "pipe", "pipe"],
    });
    assert.equal(result.status, 1, JSON.stringify(args));
    assert.equal(result.stdout, "");
    const diagnostic = JSON.parse(result.stderr.trim());
    assert.equal(diagnostic.code, "HR-OPS-016");
    assert.equal(diagnostic.stage, "production_release_arguments");
  }
});

test("the operator bundle and the disposable rehearsal carry the R5-F1 entrypoint", async () => {
  const packager = await readFile("scripts/package-production-baseline-bundle.mjs", "utf8");
  assert.match(packager, /"scripts\/production-release\.mjs"/);
  assert.match(packager, /verifyStagedProductionReleaseEntrypoint\(temporaryRoot\)/);
  assert.match(packager, /diagnostic\?\.code !== "HR-OPS-016"/);
  const rehearsal = await readFile("scripts/test-gateway-staging-bootstrap.sh", "utf8");
  assert.match(rehearsal, /r5d_ops_root\/scripts\/production-release\.mjs/);
  assert.match(rehearsal, /--operation "\$1"/);
  assert.match(rehearsal, /run_release deploy/);
  assert.match(rehearsal, /run_release rollback/);
  assert.match(rehearsal, /site_sha_before/);
  assert.match(rehearsal, /GATEWAY_R5F1_PRODUCTION_RELEASE_OK/);
  assert.equal(/"0\.4\.0"/.test(rehearsal), false, "the rehearsal must read the Gateway version from the package, not pin it");
  const workflow = await readFile(".github/workflows/gateway-r5d-managed-baseline.yml", "utf8");
  assert.match(workflow, /HERMES_R5D_ONLY: "1"/);
  assert.match(workflow, /R5-F1/);
});

function releaseOptions(fixture) {
  return {
    operation: "deploy",
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    getUid: () => 0,
    runner: slotRunner({ blue: true }),
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
    ownership: currentOwnership(),
    ...fixture.overrides,
  };
}

function slotRunner(active) {
  return {
    run(command, args) {
      if (command !== "systemctl" || args[0] !== "is-active") return { status: 1, stdout: "", stderr: "" };
      const unit = args.at(-1);
      const state = unit === "hermes-remote-gateway.service"
        ? active.legacy === true
        : unit === "hermes-go-gateway-blue.service"
          ? active.blue === true
          : unit === "hermes-go-gateway-green.service"
            ? active.green === true
            : false;
      return { status: state ? 0 : 3, stdout: "", stderr: "" };
    },
  };
}

function currentOwnership() {
  const uid = process.getuid?.() ?? 0;
  const gid = process.getgid?.() ?? 0;
  return { host: { uid, gid }, container: { uid, gid }, secret: { uid, gid } };
}

function gatewayManifest(serverVersion, sourceCommit, imageId) {
  return {
    schemaVersion: 3,
    kind: "hermes-go-gateway-oci",
    serverVersion,
    sourceCommit,
    imageReference: `hermes-remote-gateway:${serverVersion}-${sourceCommit.slice(0, 12)}`,
    imageId: `sha256:${imageId}`,
    architecture: "amd64",
    archiveFile: `Hermes-Gateway-${serverVersion}-${sourceCommit.slice(0, 12)}-linux-amd64.tar`,
    archiveSha256: createHash("sha256").update(`${serverVersion}-archive`).digest("hex"),
    createdAt: "2026-09-05T10:00:00.000Z",
    releaseContract: {
      manifestVersion: 2,
      configSchemaVersion: 1,
      databaseSchemaVersion: 7,
      supportedPostgresqlMajors: [18],
      protocolVersions: { legacy: 1, accountConnector: 2 },
      minimumClients: { android: "0.1.0", desktop: "0.2.0", connector: "0.1.1" },
      minimumSourceVersion: "0.2.0",
      maintenanceRequired: true,
      rollbackSupported: true,
    },
    containerdImageId: `sha256:${imageId.slice(1)}e`,
  };
}

function identity(manifest) {
  return {
    serverVersion: manifest.serverVersion,
    sourceCommit: manifest.sourceCommit,
    imageId: manifest.imageId,
    manifestSchemaVersion: manifest.schemaVersion,
    databaseSchemaVersion: manifest.releaseContract?.databaseSchemaVersion ?? null,
  };
}

function committedJournal({ activeSlot, candidateSlot, source, target, currentReleaseTarget, previousReleaseTarget }) {
  return {
    schemaVersion: 2,
    operation: "deploy",
    planDigest: "a".repeat(64),
    runId: "committed-run",
    stage: "committed",
    activeSlot,
    candidateSlot,
    source,
    target,
    checkpoint: {
      currentReleaseTarget,
      previousReleaseTarget,
      nginxConfigSha256: null,
      upstreamSha256: null,
    },
    startedAt: "2026-09-05T12:00:00.000Z",
    updatedAt: "2026-09-05T12:10:00.000Z",
  };
}

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-release-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const legacy = path.join(base, "legacy");
  const nginx = path.join(base, "nginx");
  const installRoot = path.join(base, "install");
  const stateRoot = path.join(base, "state");
  for (const directory of [inputs, legacy, nginx, path.join(installRoot, CURRENT_RELEASE), path.join(installRoot, LEGACY_RELEASE), path.join(stateRoot, "ops")]) {
    await mkdir(directory, { recursive: true });
  }
  const identityFiles = [{ path: path.join(legacy, "package.json"), sha256: "a".repeat(64) }];
  await writeFile(identityFiles[0].path, "{}\n");
  const rawConfig = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    targetArtifactManifest: path.join(inputs, "target.manifest.json"),
    host: { hostname: "prod-host", architecture: "amd64" },
    paths: {
      installRoot,
      configRoot: path.join(base, "config"),
      stateRoot,
      systemdUnitDirectory: path.join(base, "systemd"),
    },
    legacySource: {
      serviceName: "hermes-remote-gateway",
      containerName: "hermes-remote-gateway-legacy",
      gatewayPort: 18444,
      stateDirectory: path.join(legacy, "state"),
      compatibilityVersion: "0.2.0",
      identityFiles,
      recoveryEvidence: path.join(inputs, "legacy-recovery.json"),
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
      candidateConfigSource: path.join(inputs, "hermes-edge.candidate.conf"),
      candidateConfigSha256: "b".repeat(64),
      configFile: path.join(nginx, "hermes-edge.conf"),
      upstreamConfigFile: path.join(nginx, "hermes-go-production-upstream.conf"),
    },
    deployment: { drainTimeoutSeconds: 60, observationSeconds: 30 },
  };
  const configPath = path.join(inputs, "managed-baseline.json");
  await writeJson(configPath, rawConfig);
  const config = await loadManagedBaselineConfig(configPath);

  const currentManifest = gatewayManifest("0.4.0", CURRENT_COMMIT, "d".repeat(64));
  const nextManifest = gatewayManifest("0.4.1", NEXT_COMMIT, "e".repeat(64));
  await writeJson(path.join(installRoot, CURRENT_RELEASE, "bundle.manifest.json"), currentManifest, 0o644);
  await writeJson(path.join(installRoot, LEGACY_RELEASE, "legacy.manifest.json"), {
    schemaVersion: 1,
    kind: "hermes-go-managed-legacy-v1",
    compatibilityVersion: "0.2.0",
    identityDigest: LEGACY_DIGEST,
    serviceName: "hermes-remote-gateway",
    stateDirectory: rawConfig.legacySource.stateDirectory,
  }, 0o644);
  await symlink(CURRENT_RELEASE, path.join(installRoot, "current"));
  await symlink(LEGACY_RELEASE, path.join(installRoot, "previous"));
  const journalPath = path.join(stateRoot, "ops", "deploy-state.json");
  await writeJson(journalPath, committedJournal({
    activeSlot: null,
    candidateSlot: "blue",
    source: {
      serverVersion: "0.2.0",
      sourceCommit: LEGACY_DIGEST.slice(0, 40),
      imageId: `sha256:${LEGACY_DIGEST}`,
      manifestSchemaVersion: 1,
      databaseSchemaVersion: null,
    },
    target: identity(currentManifest),
    currentReleaseTarget: LEGACY_RELEASE,
    previousReleaseTarget: null,
  }));
  await writeFile(config.nginx.configFile, [
    `include ${config.nginx.upstreamConfigFile};`,
    `server { listen 443 ssl; server_name ${config.nginx.serverName};`,
    "location /api/ { proxy_pass http://hermes_go_gateway_production; }",
    "}",
    "",
  ].join("\n"), { mode: 0o644 });
  await writeFile(config.nginx.upstreamConfigFile, renderNginxUpstream(config, "blue"), { mode: 0o644 });
  return { base, configPath, journalPath, currentManifest, nextManifest, overrides: {} };
}

async function writeJson(filePath, value, mode = 0o600) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode });
}
