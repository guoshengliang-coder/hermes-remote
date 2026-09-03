import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, readlink, realpath, rm, symlink, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadBundleManifest, loadDeployConfig } from "../../ops/lib/config.mjs";
import {
  advanceDeploymentJournal,
  acquireDeploymentLock,
  createDeploymentJournal,
  DEPLOYMENT_STAGES,
  deploymentPlanDigest,
  readDeploymentJournal,
  readOrCreateDeploymentJournal,
  releaseDeploymentLock,
  writeDeploymentJournal,
} from "../../ops/lib/deploy-state.mjs";
import {
  otherSlot,
  renderDeployGatewayEnvironment,
  renderDeployNginxConfig,
  renderDeploySystemdUnit,
  renderNginxUpstream,
} from "../../ops/lib/deploy-system.mjs";
import { prepareCandidate } from "../../ops/lib/deploy.mjs";
import { createOpsError, OpsError } from "../../ops/lib/errors.mjs";

test("R4 blue/green templates isolate candidate process, state, and private port", async () => {
  const config = await loadDeployConfig("ops/staging.deploy.example.json");
  const manifest = targetIdentity();
  const blue = renderDeploySystemdUnit(config, manifest, "blue");
  const green = renderDeploySystemdUnit(config, manifest, "green");
  const blueEnvironment = renderDeployGatewayEnvironment(config, "blue");
  const nginx = renderDeployNginxConfig(config);
  const blueUpstream = renderNginxUpstream(config, "blue");
  const greenUpstream = renderNginxUpstream(config, "green");

  assert.equal(otherSlot("blue"), "green");
  assert.equal(otherSlot("green"), "blue");
  assert.match(blue, /hermes-go-gateway-blue/);
  assert.match(blue, /127\.0\.0\.1:8787:8787/);
  assert.match(blue, /gateway-slots\/blue/);
  assert.match(green, /hermes-go-gateway-green/);
  assert.match(green, /127\.0\.0\.1:8788:8787/);
  assert.match(green, /gateway-slots\/green/);
  assert.match(blueEnvironment, /ACCOUNT_AUTH_ENABLED=0/);
  for (const required of ["--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges", manifest.imageId]) {
    assert.equal(blue.includes(required), true, `${required} missing from blue unit`);
    assert.equal(green.includes(required), true, `${required} missing from green unit`);
  }

  assert.match(nginx, new RegExp(`^include ${escapeRegex(config.nginx.upstreamConfigFile)};`));
  assert.equal(nginx.includes(":8787"), false);
  assert.equal(nginx.includes(":8788"), false);
  assert.equal(nginx.includes("$proxy_add_x_forwarded_for"), false);
  assert.match(nginx, /location = \/api\/ws/);
  assert.match(nginx, /location = \/v1\/connect/);
  assert.match(blueUpstream, /127\.0\.0\.1:8787/);
  assert.equal(blueUpstream.includes("8788"), false);
  assert.match(greenUpstream, /127\.0\.0\.1:8788/);
  assert.equal(greenUpstream.includes("8787"), false);
});

test("deployment journal permits only ordered, durable state transitions", async (t) => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-deploy-state-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const journalPath = path.join(root, "deploy-state.json");
  const source = sourceIdentity();
  const target = targetIdentity();
  const config = await loadDeployConfig("ops/staging.deploy.example.json");
  const planDigest = deploymentPlanDigest(config, source, target, "f".repeat(64));
  const initial = createDeploymentJournal({
    operation: "deploy",
    planDigest,
    runId: "run-first",
    activeSlot: null,
    candidateSlot: "blue",
    source,
    target,
    now: new Date("2026-09-03T10:00:00.000Z"),
  });
  await readOrCreateDeploymentJournal(journalPath, initial);
  assert.equal((await readDeploymentJournal(journalPath)).stage, "authorized");

  let journal = advanceDeploymentJournal(initial, "artifact_verified", "2026-09-03T10:00:01.000Z");
  journal = advanceDeploymentJournal(journal, "lock_acquired", "2026-09-03T10:00:02.000Z");
  assert.throws(
    () => advanceDeploymentJournal(journal, "candidate_started", "2026-09-03T10:00:03.000Z"),
    isOpsCode("HR-OPS-007"),
  );
  const checkpoint = {
    currentReleaseTarget: "releases/0.2.0-aaaaaaaaaaaa",
    previousReleaseTarget: null,
    nginxConfigSha256: "c".repeat(64),
    upstreamSha256: null,
  };
  assert.throws(
    () => advanceDeploymentJournal(journal, "checkpoint_created", "2026-09-03T10:00:03.000Z", {
      checkpoint: { ...checkpoint, previousReleaseTarget: "releases/.." },
    }),
    isOpsCode("HR-OPS-007"),
  );
  journal = advanceDeploymentJournal(journal, "checkpoint_created", "2026-09-03T10:00:03.000Z", { checkpoint });
  journal = advanceDeploymentJournal(journal, "migration_verified", "2026-09-03T10:00:04.000Z");
  journal = advanceDeploymentJournal(journal, "candidate_started", "2026-09-03T10:00:05.000Z");
  journal = advanceDeploymentJournal(journal, "candidate_verified", "2026-09-03T10:00:06.000Z");
  await writeDeploymentJournal(journalPath, journal);
  assert.equal((await readDeploymentJournal(journalPath)).stage, "candidate_verified");
  assert.deepEqual(DEPLOYMENT_STAGES.slice(0, 7), [
    "authorized",
    "artifact_verified",
    "lock_acquired",
    "checkpoint_created",
    "migration_verified",
    "candidate_started",
    "candidate_verified",
  ]);

  const resumed = await readOrCreateDeploymentJournal(journalPath, { ...initial, runId: "run-resume" });
  assert.equal(resumed.stage, "candidate_verified");
  assert.equal(resumed.runId, "run-resume");
  assert.equal((await readDeploymentJournal(journalPath)).runId, "run-resume");
  await assert.rejects(
    () => readOrCreateDeploymentJournal(journalPath, { ...initial, planDigest: "0".repeat(64) }),
    isOpsCode("HR-OPS-007"),
  );

  const tampered = { ...journal, unexpected: true };
  await writeFile(journalPath, `${JSON.stringify(tampered)}\n`, { mode: 0o600 });
  await assert.rejects(() => readDeploymentJournal(journalPath), isOpsCode("HR-OPS-007"));
});

test("deployment lock is exclusive, stale-owner aware, and ownership fenced", async (t) => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-deploy-lock-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(root, { recursive: true });
  const lockPath = path.join(root, "deploy.lock");
  const liveProbe = () => {};
  const first = await acquireDeploymentLock(lockPath, "run-first", {
    pid: 101,
    hostname: "test-host",
    processProbe: liveProbe,
  });
  await assert.rejects(
    () => acquireDeploymentLock(lockPath, "run-second", {
      pid: 102,
      hostname: "test-host",
      processProbe: liveProbe,
    }),
    isOpsCode("HR-OPS-007"),
  );

  await unlink(lockPath);
  await writeFile(lockPath, `${JSON.stringify({
    schemaVersion: 2,
    runId: "run-first",
    nonce: "successor-nonce",
    pid: 103,
    hostname: "test-host",
  })}\n`, { mode: 0o600 });
  await releaseDeploymentLock(first);
  assert.equal(JSON.parse(await readFile(lockPath, "utf8")).pid, 103);

  await releaseDeploymentLock({ filePath: lockPath, runId: "not-owner" });
  assert.equal(JSON.parse(await readFile(lockPath, "utf8")).runId, "run-first");
  await unlink(lockPath);

  await writeFile(lockPath, `${JSON.stringify({
    schemaVersion: 2,
    runId: "stale-run",
    nonce: "stale-nonce",
    pid: 999,
    hostname: "test-host",
  })}\n`, { mode: 0o600 });
  const recovered = await acquireDeploymentLock(lockPath, "run-recovered", {
    pid: 103,
    hostname: "test-host",
    processProbe: () => { const error = new Error("missing"); error.code = "ESRCH"; throw error; },
  });
  assert.equal(JSON.parse(await readFile(lockPath, "utf8")).runId, "run-recovered");
  await releaseDeploymentLock(recovered);
});

test("candidate preparation reaches private verification without changing current or Nginx", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createCandidateRunner(fixture.targetManifest);
  let smokeCalls = 0;
  const result = await prepareCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    runner,
    platform: "linux",
    architecture: "x64",
    getUid: () => 0,
    confirmation: "staging",
    ownership: currentOwnership(),
    fetchImpl: candidateFetch(fixture.targetManifest),
    sleep: async () => {},
    now: incrementingClock(),
    runId: "candidate-success",
    candidateSmoke: async (request) => {
      smokeCalls += 1;
      assert.equal(request.gatewayUrl, `http://127.0.0.1:${fixture.config.slots.blue.gatewayPort}`);
      assert.equal(request.expectedServerVersion, fixture.targetManifest.serverVersion);
      assert.equal("appToken" in request, false);
    },
  });

  assert.equal(result.stage, "candidate_verified");
  assert.equal(result.publicRouteChanged, false);
  assert.equal(smokeCalls, 1);
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), fixture.currentTarget);
  assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
  assert.equal(runner.calls.some((call) => call.args.includes("nginx.service")), false);
  assert.equal(runner.calls.some((call) => call.args.includes("hermes-go-gateway-staging.service")), false);
  assert.match(
    await readFile(path.join(fixture.config.paths.systemdUnitDirectory, "hermes-go-gateway-blue.service"), "utf8"),
    new RegExp(fixture.targetManifest.imageId),
  );
  const journal = await readDeploymentJournal(path.join(fixture.config.paths.stateRoot, "ops", "deploy-state.json"));
  assert.equal(journal.stage, "candidate_verified");
  assert.equal(journal.checkpoint.currentReleaseTarget, fixture.currentTarget);
  assert.equal(journal.checkpoint.upstreamSha256, null);
});

test("candidate smoke failure stops only the candidate and safely resumes", async (t) => {
  const fixture = await createCandidateFixture(t);
  const failingRunner = createCandidateRunner(fixture.targetManifest);
  await assert.rejects(
    () => prepareCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      runner: failingRunner,
      platform: "linux",
      architecture: "x64",
      getUid: () => 0,
      confirmation: "staging",
      ownership: currentOwnership(),
      fetchImpl: candidateFetch(fixture.targetManifest),
      sleep: async () => {},
      now: incrementingClock(),
      runId: "candidate-failed",
      candidateSmoke: async () => { throw new Error(`connector smoke failed token=${fixture.tokens.app}`); },
    }),
    (error) => isOpsCode("HR-OPS-007")(error) && !error.technicalCause.includes(fixture.tokens.app),
  );
  assert.equal(failingRunner.calls.some((call) => call.command === "systemctl" && call.args[0] === "stop"), true);
  assert.equal(failingRunner.calls.some((call) => call.command === "docker" && call.args[0] === "rm"), true);
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), fixture.currentTarget);
  assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
  assert.equal((await readDeploymentJournal(path.join(fixture.config.paths.stateRoot, "ops", "deploy-state.json"))).stage, "candidate_started");

  const recoveryRunner = createCandidateRunner(fixture.targetManifest);
  const recovered = await prepareCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    runner: recoveryRunner,
    platform: "linux",
    architecture: "x64",
    getUid: () => 0,
    confirmation: "staging",
    ownership: currentOwnership(),
    fetchImpl: candidateFetch(fixture.targetManifest),
    sleep: async () => {},
    now: incrementingClock(),
    runId: "candidate-recovered",
    candidateSmoke: async () => {},
  });
  assert.equal(recovered.stage, "candidate_verified");
  assert.equal(recoveryRunner.calls.some((call) => call.command === "systemctl" && call.args[0] === "restart"), true);
});

test("a competing deployment lock never stops its active candidate", async (t) => {
  const fixture = await createCandidateFixture(t);
  const opsRoot = path.join(fixture.config.paths.stateRoot, "ops");
  await mkdir(opsRoot, { recursive: true });
  const lockPath = path.join(opsRoot, "deploy.lock");
  const ownerLock = await acquireDeploymentLock(lockPath, "competing-run");
  const runner = createCandidateRunner(fixture.targetManifest, { active: true });
  try {
    await assert.rejects(
      () => prepareCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
        runner,
        platform: "linux",
        architecture: "x64",
        getUid: () => 0,
        confirmation: "staging",
        ownership: currentOwnership(),
        fetchImpl: candidateFetch(fixture.targetManifest),
        sleep: async () => {},
        runId: "blocked-run",
        candidateSmoke: async () => {},
      }),
      isOpsCode("HR-OPS-007"),
    );
    assert.equal(runner.calls.some((call) => call.command === "systemctl" && call.args[0] === "stop"), false);
    assert.equal(runner.calls.some((call) => call.command === "docker" && call.args[0] === "rm"), false);
  } finally {
    await releaseDeploymentLock(ownerLock);
  }
});

async function createCandidateFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-candidate-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const artifacts = path.join(base, "artifacts");
  await mkdir(inputs, { recursive: true });
  await mkdir(artifacts, { recursive: true });
  const tokens = { app: "a".repeat(64), connector: "b".repeat(64), internal: "c".repeat(64) };
  const inputPaths = {
    appTokenSource: path.join(inputs, "app-token"),
    connectorTokenSource: path.join(inputs, "connector-token"),
    internalStatusTokenSource: path.join(inputs, "internal-status-token"),
    certificateSource: path.join(inputs, "fullchain.pem"),
    privateKeySource: path.join(inputs, "privkey.pem"),
  };
  await writePrivate(inputPaths.appTokenSource, `${tokens.app}\n`);
  await writePrivate(inputPaths.connectorTokenSource, `${tokens.connector}\n`);
  await writePrivate(inputPaths.internalStatusTokenSource, `${tokens.internal}\n`);
  await writeFile(inputPaths.certificateSource, `-----BEGIN CERTIFICATE-----\n${"Z".repeat(96)}\n-----END CERTIFICATE-----\n`, { mode: 0o644 });
  await writePrivate(inputPaths.privateKeySource, `-----BEGIN PRIVATE KEY-----\n${"K".repeat(96)}\n-----END PRIVATE KEY-----\n`);

  const targetVersion = "0.3.0";
  const targetCommit = "c".repeat(40);
  const stem = `Hermes-Gateway-${targetVersion}-${targetCommit.slice(0, 12)}-linux-amd64`;
  const archivePath = path.join(artifacts, `${stem}.tar`);
  await writeFile(archivePath, "candidate-oci-archive");
  const manifestPath = path.join(artifacts, `${stem}.manifest.json`);
  const targetRaw = {
    schemaVersion: 2,
    kind: "hermes-go-gateway-oci",
    serverVersion: targetVersion,
    sourceCommit: targetCommit,
    imageReference: `hermes-remote-gateway:${targetVersion}-${targetCommit.slice(0, 12)}`,
    imageId: `sha256:${"d".repeat(64)}`,
    architecture: "amd64",
    archiveFile: path.basename(archivePath),
    archiveSha256: createHash("sha256").update("candidate-oci-archive").digest("hex"),
    createdAt: "2026-09-03T10:00:00.000Z",
    releaseContract: {
      manifestVersion: 1,
      configSchemaVersion: 1,
      databaseSchemaVersion: 7,
      supportedPostgresqlMajors: [18],
      protocolVersions: { legacy: 1, accountConnector: 2 },
      minimumClients: { android: "0.1.0", desktop: "0.2.0", connector: "0.1.1" },
      minimumSourceVersion: "0.2.0",
      maintenanceRequired: false,
      rollbackSupported: true,
    },
  };
  await writeJson(manifestPath, targetRaw);

  const paths = {
    installRoot: path.join(base, "hermes-go-install"),
    configRoot: path.join(base, "hermes-go-config"),
    stateRoot: path.join(base, "hermes-go-state"),
    systemdUnitDirectory: path.join(base, "systemd"),
  };
  const nginxContent = "legacy-nginx-configuration\n";
  const nginxConfig = path.join(base, "nginx", "hermes-go-staging.conf");
  await mkdir(path.dirname(nginxConfig), { recursive: true });
  await writeFile(nginxConfig, nginxContent, { mode: 0o644 });
  const deployConfigPath = path.join(inputs, "deploy.json");
  await writeJson(deployConfigPath, {
    schemaVersion: 2,
    environment: "staging",
    operator: "ci-operator",
    targetArtifactManifest: manifestPath,
    paths,
    slots: {
      blue: { serviceName: "hermes-go-gateway-blue", containerName: "hermes-go-gateway-blue", gatewayPort: 18787 },
      green: { serviceName: "hermes-go-gateway-green", containerName: "hermes-go-gateway-green", gatewayPort: 18788 },
    },
    gateway: { defaultDeviceId: "staging-mac", accountAuthEnabled: false, accountBindingEnabled: false },
    secrets: {
      appTokenSource: inputPaths.appTokenSource,
      connectorTokenSource: inputPaths.connectorTokenSource,
      internalStatusTokenSource: inputPaths.internalStatusTokenSource,
    },
    database: null,
    nginx: {
      serverName: "staging.example.invalid",
      listenPort: 443,
      certificateSource: inputPaths.certificateSource,
      privateKeySource: inputPaths.privateKeySource,
      configFile: nginxConfig,
      upstreamConfigFile: path.join(base, "nginx", "hermes-go-staging-upstream.conf"),
    },
    deployment: { drainTimeoutSeconds: 60, observationSeconds: 30 },
  });
  const config = await loadDeployConfig(deployConfigPath);
  const sourceManifest = {
    schemaVersion: 1,
    serverVersion: "0.2.0",
    sourceCommit: "a".repeat(40),
    imageId: `sha256:${"b".repeat(64)}`,
  };
  const currentTarget = `releases/${sourceManifest.serverVersion}-${sourceManifest.sourceCommit.slice(0, 12)}`;
  await mkdir(path.join(paths.installRoot, currentTarget), { recursive: true });
  await symlink(currentTarget, path.join(paths.installRoot, "current"));
  return {
    base,
    config,
    sourceManifest,
    targetManifest: await loadBundleManifest(manifestPath),
    currentTarget,
    nginxContent,
    tokens,
  };
}

function createCandidateRunner(manifest, { active = false } = {}) {
  let imageLoaded = false;
  const calls = [];
  return {
    calls,
    run(command, args = []) {
      calls.push({ command, args: [...args] });
      if (command === "which") return success(`/usr/bin/${args[0]}\n`);
      if (command === "systemctl" && args[0] === "is-system-running") return success("running\n");
      if (command === "systemctl" && args[0] === "is-active") return active ? success() : failure();
      if (command === "docker" && args[0] === "info") return success("linux/x86_64\n");
      if (command === "docker" && args[0] === "image" && args[1] === "inspect") {
        return imageLoaded ? success(`${manifest.imageId}|amd64\n`) : failure();
      }
      if (command === "docker" && args[0] === "load") imageLoaded = true;
      if (command === "ss") return active ? success("LISTEN 0 128 127.0.0.1:18787\n") : success();
      return success();
    },
  };
}

function candidateFetch(manifest) {
  return async (url) => {
    const endpoint = new URL(url).pathname;
    const body = endpoint === "/healthz"
      ? { status: "alive" }
      : endpoint === "/readyz"
        ? { status: "ready" }
        : { serverVersion: manifest.serverVersion, sourceCommit: manifest.sourceCommit };
    return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
  };
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
  let value = Date.parse("2026-09-03T10:00:00.000Z");
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

function sourceIdentity() {
  return {
    serverVersion: "0.2.0",
    sourceCommit: "a".repeat(40),
    imageId: `sha256:${"b".repeat(64)}`,
    manifestSchemaVersion: 1,
    databaseSchemaVersion: null,
  };
}

function targetIdentity() {
  return {
    serverVersion: "0.3.0",
    sourceCommit: "c".repeat(40),
    imageId: `sha256:${"d".repeat(64)}`,
    manifestSchemaVersion: 2,
    databaseSchemaVersion: 7,
  };
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function isOpsCode(code) {
  return (error) => error instanceof OpsError && createOpsError(error.kind, error.technicalCause, error.stage).code === code;
}
