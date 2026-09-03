import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, lstat, mkdir, mkdtemp, readFile, readlink, realpath, rm, symlink, unlink, writeFile } from "node:fs/promises";
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
import { switchCandidate } from "../../ops/lib/deploy-switch.mjs";
import { handoffLifecycleSnapshot } from "../../ops/lib/lifecycle-handoff.mjs";
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
  assert.equal(blue.includes("database-secrets"), false);
  assert.equal(green.includes("database-secrets"), false);
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

test("database migration is locked before candidate start and reverified before routing", async (t) => {
  const fixture = await createCandidateFixture(t);
  await enableDatabaseFixture(fixture);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  await switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    ...switchOptions(runner),
    runId: "database-switch-success",
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
  });

  const migrationCalls = runner.calls.filter((call) => call.command === "docker"
    && call.args.at(-1) === "gateway/dist/ops/migrate-account.mjs");
  assert.equal(migrationCalls.length, 2);
  const candidateStartIndex = runner.calls.findIndex((call) => call.command === "systemctl"
    && call.args[0] === "restart" && call.args[1] === "hermes-go-gateway-blue.service");
  assert.equal(runner.calls.indexOf(migrationCalls[0]) < candidateStartIndex, true);
  assert.equal(migrationCalls.every((call) => call.args.some((arg) => arg.includes(
    path.join(fixture.config.paths.configRoot, "database-secrets", "account-database-url"),
  ))), true);
  assert.equal(migrationCalls.every((call) => call.args.every((arg) => !arg.includes("database-test-password"))), true);
  const installedDatabaseSecret = await lstat(path.join(
    fixture.config.paths.configRoot,
    "database-secrets",
    "account-database-url",
  ));
  assert.equal(installedDatabaseSecret.mode & 0o777, 0o440);
});

test("database input with group or world permissions is rejected before managed state changes", async (t) => {
  const fixture = await createCandidateFixture(t);
  await enableDatabaseFixture(fixture);
  await chmod(fixture.databaseUrlSource, 0o640);
  const runner = createTransactionalRunner(fixture);

  await assert.rejects(
    () => prepareFixtureCandidate(fixture, runner),
    isOpsCode("HR-OPS-001"),
  );
  assert.equal(runner.calls.some((call) => call.command === "docker" && call.args[0] === "run"), false);
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), fixture.currentTarget);
});

test("database drift before routing stops the candidate and preserves the old public release", async (t) => {
  const fixture = await createCandidateFixture(t);
  await enableDatabaseFixture(fixture);
  const runner = createTransactionalRunner(fixture, { databaseFailureAt: 2 });
  await prepareFixtureCandidate(fixture, runner);

  await assert.rejects(
    () => switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      ...switchOptions(runner),
      runId: "database-switch-blocked",
      candidateSmoke: async () => {},
      publicSmoke: async () => {},
    }),
    isOpsCode("HR-OPS-009"),
  );

  assert.equal(runner.active.has(fixture.config.legacySource.serviceName), true);
  assert.equal(runner.active.has(fixture.config.slots.blue.serviceName), false);
  assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), fixture.currentTarget);
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

test("route switch hands off lifecycle state, observes the public path, and commits atomically", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  await writeLifecycleSnapshot(fixture.config.legacySource.stateDirectory, [lifecycleRecord(1, "before-switch")], 2);
  const publicChecks = [];

  let result;
  try {
    result = await switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      ...switchOptions(runner),
      runId: "switch-success",
      candidateSmoke: async () => {},
      publicSmoke: async (request) => publicChecks.push(request),
    });
  } catch (error) {
    assert.fail(`unexpected switch failure at ${error.stage}: ${error.technicalCause}`);
  }

  const targetRelease = `releases/${fixture.targetManifest.serverVersion}-${fixture.targetManifest.sourceCommit.slice(0, 12)}`;
  assert.equal(result.stage, "committed");
  assert.equal(result.activeSlot, "blue");
  assert.equal(publicChecks.length, 2);
  assert.equal(publicChecks.every((request) => request.publicRoute && !request.recovery), true);
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), targetRelease);
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "previous")), fixture.currentTarget);
  assert.match(await readFile(fixture.config.nginx.upstreamConfigFile, "utf8"), /127\.0\.0\.1:18787/);
  assert.match(await readFile(fixture.config.nginx.configFile, "utf8"), /proxy_pass http:\/\/hermes_go_gateway_staging/);
  const candidateState = JSON.parse(await readFile(path.join(
    fixture.config.paths.stateRoot,
    "gateway-slots",
    "blue",
    "lifecycle-events.json",
  ), "utf8"));
  assert.deepEqual(candidateState.events.map((record) => record.event.eventId), ["before-switch"]);
  assert.equal(runner.active.has("hermes-go-gateway-staging"), false);
  assert.equal(runner.active.has("hermes-go-gateway-blue"), true);
  assert.equal(runner.enabled.has("hermes-go-gateway-blue"), true);
  assert.equal(runner.enabled.has("hermes-go-gateway-staging"), false);
  assert.equal((await readDeploymentJournal(path.join(fixture.config.paths.stateRoot, "ops", "deploy-state.json"))).stage, "committed");

  const resumed = await switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    ...switchOptions(runner),
    runId: "switch-success-resume",
    candidateSmoke: async () => {},
    publicSmoke: async (request) => publicChecks.push(request),
  });
  assert.equal(resumed.stage, "committed");
  assert.equal(publicChecks.length, 3);
});

test("public smoke failure restores the old route, service, release links, and latest lifecycle state", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  await writeLifecycleSnapshot(fixture.config.legacySource.stateDirectory, [lifecycleRecord(1, "before-switch")], 2);
  const candidateStateDirectory = path.join(fixture.config.paths.stateRoot, "gateway-slots", "blue");
  let recoveryChecked = false;

  let switchError;
  await assert.rejects(
    () => switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      ...switchOptions(runner),
      runId: "switch-recovery",
      candidateSmoke: async () => {},
      publicSmoke: async (request) => {
        if (request.recovery) {
          recoveryChecked = true;
          assert.equal(request.expectedServerVersion, fixture.sourceManifest.serverVersion);
          return;
        }
        await writeLifecycleSnapshot(candidateStateDirectory, [
          lifecycleRecord(1, "before-switch"),
          lifecycleRecord(2, "during-observation"),
        ], 3);
        throw new Error(`public smoke failed token=${fixture.tokens.app}`);
      },
    }),
    (error) => {
      switchError = error;
      return isOpsCode("HR-OPS-008")(error) && !error.technicalCause.includes(fixture.tokens.app);
    },
  );

  assert.equal(recoveryChecked, true, `${switchError.stage}: ${switchError.technicalCause}`);
  assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
  await assert.rejects(() => readFile(fixture.config.nginx.upstreamConfigFile, "utf8"), { code: "ENOENT" });
  assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), fixture.currentTarget);
  await assert.rejects(() => readlink(path.join(fixture.config.paths.installRoot, "previous")), { code: "ENOENT" });
  const restored = JSON.parse(await readFile(path.join(
    fixture.config.legacySource.stateDirectory,
    "lifecycle-events.json",
  ), "utf8"));
  assert.deepEqual(restored.events.map((record) => record.event.eventId), ["before-switch", "during-observation"]);
  assert.equal(runner.active.has("hermes-go-gateway-staging"), true);
  assert.equal(runner.active.has("hermes-go-gateway-blue"), false);
  await assert.rejects(
    () => readDeploymentJournal(path.join(fixture.config.paths.stateRoot, "ops", "deploy-state.json")),
    { code: "ENOENT" },
  );
  assert.equal((await readFile(path.join(
    fixture.config.paths.stateRoot,
    "ops",
    "deploy-state.recovered.candidate-fixture.json",
  ), "utf8")).includes('"stage": "route_switched"'), true);
});

test("an interrupted pre-handoff maintenance window is detected and restores the source", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  runner.active.delete(fixture.config.legacySource.serviceName);
  let recoveryChecked = false;

  await assert.rejects(
    () => switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      ...switchOptions(runner),
      runId: "switch-crash-recovery",
      candidateSmoke: async () => {},
      publicSmoke: async (request) => {
        assert.equal(request.recovery, true);
        recoveryChecked = true;
      },
    }),
    isOpsCode("HR-OPS-008"),
  );

  assert.equal(recoveryChecked, true);
  assert.equal(runner.active.has(fixture.config.legacySource.serviceName), true);
  assert.equal(runner.active.has(fixture.config.slots.blue.serviceName), false);
  assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
});

test("Nginx validation failure restores its exact old files before reloading", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createTransactionalRunner(fixture, { nginxTestFailures: 1 });
  await prepareFixtureCandidate(fixture, runner);
  await writeLifecycleSnapshot(fixture.config.legacySource.stateDirectory, [lifecycleRecord(1, "safe")], 2);
  let recoveryChecked = false;

  await assert.rejects(
    () => switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      ...switchOptions(runner),
      runId: "switch-nginx-recovery",
      candidateSmoke: async () => {},
      publicSmoke: async (request) => {
        assert.equal(request.recovery, true);
        recoveryChecked = true;
      },
    }),
    isOpsCode("HR-OPS-008"),
  );

  assert.equal(recoveryChecked, true);
  assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
  await assert.rejects(() => readFile(fixture.config.nginx.upstreamConfigFile), { code: "ENOENT" });
  assert.equal(runner.active.has(fixture.config.legacySource.serviceName), true);
  assert.equal(runner.calls.filter((call) => call.command === "nginx" && call.args[0] === "-t").length, 2);
  assert.equal(runner.calls.filter((call) => call.command === "systemctl" && call.args[0] === "reload").length, 1);
});

test("a route-switched journal resumes observation and commit after an operator process interruption", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  await writeLifecycleSnapshot(fixture.config.legacySource.stateDirectory, [lifecycleRecord(1, "before-resume")], 2);
  const opsRoot = path.join(fixture.config.paths.stateRoot, "ops");
  const journalPath = path.join(opsRoot, "deploy-state.json");
  let journal = await readDeploymentJournal(journalPath);
  const oldNginx = Buffer.from(fixture.nginxContent);
  await writeJson(path.join(opsRoot, `switch-checkpoint.${journal.planDigest}.json`), {
    schemaVersion: 1,
    planDigest: journal.planDigest,
    nginxConfig: {
      present: true,
      sha256: createHash("sha256").update(oldNginx).digest("hex"),
      contentBase64: oldNginx.toString("base64"),
    },
    upstream: { present: false, sha256: null, contentBase64: null },
  });
  const candidateStateDirectory = path.join(fixture.config.paths.stateRoot, "gateway-slots", "blue");
  runner.active.delete(fixture.config.legacySource.serviceName);
  await handoffLifecycleSnapshot(fixture.config.legacySource.stateDirectory, candidateStateDirectory, {
    owner: currentOwnership().container,
  });
  await writeJson(path.join(opsRoot, `lifecycle-handoff.${journal.planDigest}.json`), {
    schemaVersion: 1,
    planDigest: journal.planDigest,
    sourceStateDirectory: fixture.config.legacySource.stateDirectory,
    candidateStateDirectory,
    phase: "forward",
    updatedAt: "2026-09-03T10:00:07.000Z",
  });
  await writeFile(fixture.config.nginx.upstreamConfigFile, renderNginxUpstream(fixture.config, "blue"), { mode: 0o644 });
  await writeFile(fixture.config.nginx.configFile, renderDeployNginxConfig(fixture.config), { mode: 0o644 });
  journal = advanceDeploymentJournal(journal, "route_switched", "2026-09-03T10:00:08.000Z");
  await writeDeploymentJournal(journalPath, journal, currentOwnership().host);
  let publicChecks = 0;

  const result = await switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    ...switchOptions(runner),
    runId: "switch-resume",
    candidateSmoke: async () => {},
    publicSmoke: async () => { publicChecks += 1; },
  });

  assert.equal(result.stage, "committed");
  assert.equal(publicChecks, 2);
  assert.equal(runner.active.has(fixture.config.legacySource.serviceName), false);
  assert.equal(runner.active.has(fixture.config.slots.blue.serviceName), true);
});

test("a second interruption never overwrites events written after reverse handoff", async (t) => {
  const fixture = await createCandidateFixture(t);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  const opsRoot = path.join(fixture.config.paths.stateRoot, "ops");
  const journalPath = path.join(opsRoot, "deploy-state.json");
  let journal = await readDeploymentJournal(journalPath);
  const oldNginx = Buffer.from(fixture.nginxContent);
  await writeJson(path.join(opsRoot, `switch-checkpoint.${journal.planDigest}.json`), {
    schemaVersion: 1,
    planDigest: journal.planDigest,
    nginxConfig: {
      present: true,
      sha256: createHash("sha256").update(oldNginx).digest("hex"),
      contentBase64: oldNginx.toString("base64"),
    },
    upstream: { present: false, sha256: null, contentBase64: null },
  });
  const candidateStateDirectory = path.join(fixture.config.paths.stateRoot, "gateway-slots", "blue");
  await writeLifecycleSnapshot(candidateStateDirectory, [lifecycleRecord(1, "candidate-old")], 2);
  await writeLifecycleSnapshot(fixture.config.legacySource.stateDirectory, [
    lifecycleRecord(1, "restored"),
    lifecycleRecord(2, "written-after-restart"),
  ], 3);
  await writeJson(path.join(opsRoot, `lifecycle-handoff.${journal.planDigest}.json`), {
    schemaVersion: 1,
    planDigest: journal.planDigest,
    sourceStateDirectory: fixture.config.legacySource.stateDirectory,
    candidateStateDirectory,
    phase: "restored",
    updatedAt: "2026-09-03T10:00:09.000Z",
  });
  journal = advanceDeploymentJournal(journal, "route_switched", "2026-09-03T10:00:10.000Z");
  await writeDeploymentJournal(journalPath, journal, currentOwnership().host);
  runner.active.delete(fixture.config.slots.blue.serviceName);
  let recoveryChecked = false;

  await assert.rejects(
    () => switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
      ...switchOptions(runner),
      runId: "switch-second-recovery",
      candidateSmoke: async () => {},
      publicSmoke: async (request) => {
        assert.equal(request.recovery, true);
        recoveryChecked = true;
      },
    }),
    isOpsCode("HR-OPS-008"),
  );

  const sourceState = JSON.parse(await readFile(path.join(
    fixture.config.legacySource.stateDirectory,
    "lifecycle-events.json",
  ), "utf8"));
  assert.equal(recoveryChecked, true);
  assert.deepEqual(sourceState.events.map((record) => record.event.eventId), ["restored", "written-after-restart"]);
  assert.equal(runner.active.has(fixture.config.legacySource.serviceName), true);
});

test("switch fault-injection matrix always leaves the old public release verified", async (t) => {
  const scenarios = [
    { name: "candidate private smoke", kind: "candidate-smoke", recovery: false },
    { name: "candidate stop", command: commandIs("systemctl", "stop", "hermes-go-gateway-blue.service"), recovery: false },
    { name: "source stop", command: commandIs("systemctl", "stop", "hermes-go-gateway-staging.service"), recovery: false },
    { name: "lifecycle handoff", kind: "handoff", recovery: true },
    { name: "candidate restart", command: commandIs("systemctl", "restart", "hermes-go-gateway-blue.service"), recovery: true },
    { name: "post-restart identity", versionProbeFailureAt: 2, recovery: true },
    { name: "nginx validation", command: commandIs("nginx", "-t"), recovery: true },
    { name: "nginx reload", command: commandIs("systemctl", "reload", "nginx.service"), recovery: true },
    { name: "first public smoke", publicSmokeFailureAt: 1, recovery: true },
    { name: "observation window", kind: "observation", recovery: true },
    { name: "post-observation identity", versionProbeFailureAt: 3, recovery: true },
    { name: "second public smoke", publicSmokeFailureAt: 2, recovery: true },
    { name: "candidate enable", command: commandIs("systemctl", "enable", "hermes-go-gateway-blue.service"), recovery: true },
    { name: "source disable", command: commandIs("systemctl", "disable", "hermes-go-gateway-staging.service"), recovery: true },
  ];

  for (const scenario of scenarios) {
    await t.test(scenario.name, async (scenarioTest) => {
      const fixture = await createCandidateFixture(scenarioTest);
      const baseRunner = createTransactionalRunner(fixture);
      await prepareFixtureCandidate(fixture, baseRunner);
      await writeLifecycleSnapshot(
        fixture.config.legacySource.stateDirectory,
        [lifecycleRecord(1, `safe-${scenario.name.replaceAll(" ", "-")}`)],
        2,
      );
      if (scenario.kind === "handoff") {
        await mkdir(path.join(
          fixture.config.paths.stateRoot,
          "gateway-slots",
          "blue",
          "lifecycle-events.json",
        ));
      }

      const runner = scenario.command ? injectOneCommandFailure(baseRunner, scenario.command) : baseRunner;
      const healthyFetch = candidateFetch(fixture.targetManifest);
      let versionProbeCount = 0;
      let publicSmokeCount = 0;
      let recoverySmokeCount = 0;
      const fetchImpl = async (url, init) => {
        if (new URL(url).pathname === "/internal/version") {
          versionProbeCount += 1;
          if (versionProbeCount === scenario.versionProbeFailureAt) {
            return new Response(JSON.stringify({ serverVersion: "0.0.0", sourceCommit: "0".repeat(40) }), {
              status: 200,
              headers: { "content-type": "application/json" },
            });
          }
        }
        return healthyFetch(url, init);
      };

      await assert.rejects(
        () => switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
          ...switchOptions(runner),
          runId: `fault-${scenario.name.replaceAll(" ", "-")}`,
          fetchImpl,
          candidateSmoke: async () => {
            if (scenario.kind === "candidate-smoke") throw new Error("injected candidate smoke failure");
          },
          publicSmoke: async (request) => {
            if (request.recovery) {
              recoverySmokeCount += 1;
              return;
            }
            publicSmokeCount += 1;
            if (publicSmokeCount === scenario.publicSmokeFailureAt) {
              throw new Error("injected public smoke failure");
            }
          },
          sleep: scenario.kind === "observation"
            ? async () => { throw new Error("injected observation failure"); }
            : async () => {},
        }),
        isOpsCode("HR-OPS-008"),
      );

      if (scenario.command) assert.equal(runner.injected, true, "command fault was not reached");
      assert.equal(recoverySmokeCount, scenario.recovery ? 1 : 0);
      if (!scenario.recovery) {
        assert.equal(runner.calls.some((call) => call.command === "docker"
          && call.args[0] === "rm" && call.args.at(-1) === fixture.config.slots.blue.containerName), true);
      }
      assert.equal(runner.active.has(fixture.config.legacySource.serviceName), true);
      assert.equal(runner.active.has(fixture.config.slots.blue.serviceName), false);
      assert.equal(runner.enabled.has(fixture.config.legacySource.serviceName), true);
      assert.equal(runner.enabled.has(fixture.config.slots.blue.serviceName), false);
      assert.equal(await readFile(fixture.config.nginx.configFile, "utf8"), fixture.nginxContent);
      await assert.rejects(() => readFile(fixture.config.nginx.upstreamConfigFile), { code: "ENOENT" });
      assert.equal(await readlink(path.join(fixture.config.paths.installRoot, "current")), fixture.currentTarget);
      await assert.rejects(
        () => readlink(path.join(fixture.config.paths.installRoot, "previous")),
        { code: "ENOENT" },
      );
      const sourceState = JSON.parse(await readFile(path.join(
        fixture.config.legacySource.stateDirectory,
        "lifecycle-events.json",
      ), "utf8"));
      assert.deepEqual(
        sourceState.events.map((record) => record.event.eventId),
        [`safe-${scenario.name.replaceAll(" ", "-")}`],
      );
    });
  }
});

test("rollback archives the committed deploy and reuses the reverse blue-green safety path", async (t) => {
  const fixture = await createCandidateFixture(t);
  const rollbackVersion = fixture.sourceManifest.serverVersion;
  const rollbackCommit = fixture.sourceManifest.sourceCommit;
  const rollbackStem = `Hermes-Gateway-${rollbackVersion}-${rollbackCommit.slice(0, 12)}-linux-amd64`;
  const rollbackArchive = path.join(fixture.artifacts, `${rollbackStem}.tar`);
  await writeFile(rollbackArchive, "rollback-oci-archive");
  const rollbackManifestPath = path.join(fixture.artifacts, `${rollbackStem}.manifest.json`);
  await writeJson(rollbackManifestPath, {
    schemaVersion: 1,
    kind: "hermes-go-gateway-oci",
    serverVersion: rollbackVersion,
    sourceCommit: rollbackCommit,
    imageReference: `hermes-remote-gateway:${rollbackVersion}-${rollbackCommit.slice(0, 12)}`,
    imageId: fixture.sourceManifest.imageId,
    architecture: "amd64",
    archiveFile: path.basename(rollbackArchive),
    archiveSha256: createHash("sha256").update("rollback-oci-archive").digest("hex"),
    createdAt: "2026-09-03T09:00:00.000Z",
  });
  fixture.rollbackManifest = await loadBundleManifest(rollbackManifestPath);
  const rollbackConfigPath = path.join(fixture.base, "inputs", "rollback-deploy.json");
  const rawConfig = JSON.parse(await readFile(fixture.deployConfigPath, "utf8"));
  await writeJson(rollbackConfigPath, { ...rawConfig, targetArtifactManifest: rollbackManifestPath });
  const rollbackConfig = await loadDeployConfig(rollbackConfigPath);
  const runner = createTransactionalRunner(fixture);
  await prepareFixtureCandidate(fixture, runner);
  await switchCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    ...switchOptions(runner),
    runId: "deploy-before-rollback",
    candidateSmoke: async () => {},
    publicSmoke: async () => {},
  });
  await writeLifecycleSnapshot(path.join(
    fixture.config.paths.stateRoot,
    "gateway-slots",
    "blue",
  ), [lifecycleRecord(1, "before-rollback")], 2);

  const previousLink = path.join(rollbackConfig.paths.installRoot, "previous");
  await unlink(previousLink);
  await symlink(`releases/0.1.0-${"e".repeat(12)}`, previousLink);
  await assert.rejects(
    () => prepareCandidate(rollbackConfig, fixture.targetManifest, fixture.rollbackManifest, {
      runner,
      operation: "rollback",
      activeSlot: "blue",
      candidateSlot: "green",
      platform: "linux",
      architecture: "x64",
      getUid: () => 0,
      confirmation: "staging",
      ownership: currentOwnership(),
      fetchImpl: candidateFetch(fixture.rollbackManifest),
      sleep: async () => {},
      runId: "rollback-wrong-previous",
      candidateSmoke: async () => {},
    }),
    isOpsCode("HR-OPS-006"),
  );
  await unlink(previousLink);
  await symlink(fixture.currentTarget, previousLink);

  const prepared = await prepareCandidate(rollbackConfig, fixture.targetManifest, fixture.rollbackManifest, {
    runner,
    operation: "rollback",
    activeSlot: "blue",
    candidateSlot: "green",
    platform: "linux",
    architecture: "x64",
    getUid: () => 0,
    confirmation: "staging",
    ownership: currentOwnership(),
    fetchImpl: candidateFetch(fixture.rollbackManifest),
    sleep: async () => {},
    now: incrementingClock(),
    runId: "rollback-candidate",
    candidateSmoke: async (request) => {
      assert.equal(request.expectedServerVersion, rollbackVersion);
    },
  });
  assert.equal(prepared.command, "prepare-rollback-candidate");
  assert.equal(prepared.candidateSlot, "green");
  const archivedDeploy = path.join(
    rollbackConfig.paths.stateRoot,
    "ops",
    "history",
    "deploy-state.committed.candidate-fixture.json",
  );
  assert.equal((await readDeploymentJournal(archivedDeploy)).stage, "committed");

  const rolledBack = await switchCandidate(rollbackConfig, fixture.targetManifest, fixture.rollbackManifest, {
    ...switchOptions(runner),
    operation: "rollback",
    runId: "rollback-switch",
    fetchImpl: candidateFetch(fixture.rollbackManifest),
    candidateSmoke: async () => {},
    publicSmoke: async (request) => assert.equal(request.expectedServerVersion, rollbackVersion),
  });

  assert.equal(rolledBack.command, "switch-rollback-candidate");
  assert.equal(rolledBack.stage, "committed");
  assert.equal(rolledBack.activeSlot, "green");
  assert.equal(await readlink(path.join(rollbackConfig.paths.installRoot, "current")), fixture.currentTarget);
  assert.equal(
    await readlink(path.join(rollbackConfig.paths.installRoot, "previous")),
    `releases/${fixture.targetManifest.serverVersion}-${fixture.targetManifest.sourceCommit.slice(0, 12)}`,
  );
  assert.equal(runner.active.has(rollbackConfig.slots.blue.serviceName), false);
  assert.equal(runner.active.has(rollbackConfig.slots.green.serviceName), true);
  const rolledBackState = JSON.parse(await readFile(path.join(
    rollbackConfig.paths.stateRoot,
    "gateway-slots",
    "green",
    "lifecycle-events.json",
  ), "utf8"));
  assert.deepEqual(rolledBackState.events.map((record) => record.event.eventId), ["before-rollback"]);
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
    legacySource: {
      serviceName: "hermes-go-gateway-staging",
      containerName: "hermes-go-gateway-staging",
      gatewayPort: 18786,
      stateDirectory: path.join(paths.stateRoot, "gateway"),
    },
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
    artifacts,
    config,
    deployConfigPath,
    sourceManifest,
    targetManifest: await loadBundleManifest(manifestPath),
    currentTarget,
    nginxContent,
    tokens,
  };
}

async function prepareFixtureCandidate(fixture, runner) {
  return prepareCandidate(fixture.config, fixture.sourceManifest, fixture.targetManifest, {
    runner,
    platform: "linux",
    architecture: "x64",
    getUid: () => 0,
    confirmation: "staging",
    ownership: currentOwnership(),
    fetchImpl: candidateFetch(fixture.targetManifest),
    sleep: async () => {},
    now: incrementingClock(),
    runId: "candidate-fixture",
    candidateSmoke: async () => {},
  });
}

function switchOptions(runner) {
  return {
    runner,
    platform: "linux",
    architecture: "x64",
    getUid: () => 0,
    confirmation: "staging",
    ownership: currentOwnership(),
    fetchImpl: candidateFetch(targetIdentity()),
    sleep: async () => {},
    now: incrementingClock(),
  };
}

function createTransactionalRunner(fixture, { nginxTestFailures = 0, databaseFailureAt = null } = {}) {
  const loadedImages = new Set();
  const calls = [];
  const active = new Set([fixture.config.legacySource.serviceName]);
  const enabled = new Set([fixture.config.legacySource.serviceName]);
  let databaseCalls = 0;
  return {
    calls,
    active,
    enabled,
    run(command, args = []) {
      calls.push({ command, args: [...args] });
      if (command === "which") return success(`/usr/bin/${args[0]}\n`);
      if (command === "nginx" && args[0] === "-t") {
        if (nginxTestFailures > 0) {
          nginxTestFailures -= 1;
          return failure();
        }
        return success();
      }
      if (command === "systemctl" && args[0] === "is-system-running") return success("running\n");
      if (command === "systemctl" && args[0] === "is-active") {
        return active.has(args.at(-1).replace(/\.service$/, "")) ? success() : failure();
      }
      if (command === "systemctl" && ["restart", "start"].includes(args[0])) {
        active.add(args[1].replace(/\.service$/, ""));
        return success();
      }
      if (command === "systemctl" && args[0] === "stop") {
        active.delete(args[1].replace(/\.service$/, ""));
        return success();
      }
      if (command === "systemctl" && args[0] === "enable") {
        enabled.add(args[1].replace(/\.service$/, ""));
        return success();
      }
      if (command === "systemctl" && args[0] === "disable") {
        enabled.delete(args[1].replace(/\.service$/, ""));
        return success();
      }
      if (command === "docker" && args[0] === "info") return success("linux/x86_64\n");
      if (command === "docker" && args[0] === "image" && args[1] === "inspect") {
        const manifest = [fixture.targetManifest, fixture.rollbackManifest]
          .find((candidate) => candidate?.imageReference === args.at(-1));
        return manifest && loadedImages.has(manifest.imageReference)
          ? success(`${manifest.imageId}|amd64\n`)
          : failure();
      }
      if (command === "docker" && args[0] === "load") {
        const manifest = [fixture.targetManifest, fixture.rollbackManifest]
          .find((candidate) => candidate?.archivePath === args.at(-1));
        if (manifest) loadedImages.add(manifest.imageReference);
        return success();
      }
      if (command === "docker" && args[0] === "run"
          && args.at(-1) === "gateway/dist/ops/migrate-account.mjs") {
        databaseCalls += 1;
        if (databaseCalls === databaseFailureAt) return failure();
        return success(`DATABASE_MIGRATION_OK ${JSON.stringify({
          schemaVersion: fixture.targetManifest.releaseContract.databaseSchemaVersion,
          postgresqlMajor: fixture.targetManifest.releaseContract.supportedPostgresqlMajors[0],
          appliedMigrations: databaseCalls === 1 ? [1, 2, 3, 4, 5, 6, 7] : [],
        })}\n`);
      }
      if (command === "ss") return success();
      return success();
    },
  };
}

async function enableDatabaseFixture(fixture) {
  const databaseUrlSource = path.join(fixture.base, "inputs", "account-database-url");
  await writePrivate(
    databaseUrlSource,
    "postgresql://database-test-user:database-test-password@127.0.0.1/hermes_test\n",
  );
  fixture.config.database = { urlSource: databaseUrlSource, ssl: false, migrationLockId: 741852 };
  fixture.databaseUrlSource = databaseUrlSource;
  fixture.sourceManifest = {
    ...fixture.sourceManifest,
    schemaVersion: 2,
    releaseContract: structuredClone(fixture.targetManifest.releaseContract),
  };
}

function commandIs(command, ...args) {
  return (actualCommand, actualArgs) => actualCommand === command
    && args.every((argument, index) => actualArgs[index] === argument);
}

function injectOneCommandFailure(runner, matches) {
  let injected = false;
  return {
    calls: runner.calls,
    active: runner.active,
    enabled: runner.enabled,
    get injected() { return injected; },
    run(command, args = [], options) {
      if (!injected && matches(command, args)) {
        injected = true;
        runner.calls.push({ command, args: [...args] });
        return failure();
      }
      return runner.run(command, args, options);
    },
  };
}

async function writeLifecycleSnapshot(directory, events, nextSequence) {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await writeFile(path.join(directory, "lifecycle-events.json"), `${JSON.stringify({
    version: 1,
    nextSequence,
    events,
  })}\n`, { mode: 0o600 });
}

function lifecycleRecord(sequence, eventId) {
  return {
    sequence,
    event: {
      type: "session.lifecycle",
      version: 1,
      eventId,
      deviceId: "staging-mac",
      runtimeSessionId: `runtime-${eventId}`,
      storedSessionId: `stored-${eventId}`,
      event: "run.completed",
      state: "idle",
      occurredAt: "2026-09-03T10:00:00.000Z",
    },
    receivedAt: "2026-09-03T10:00:01.000Z",
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
