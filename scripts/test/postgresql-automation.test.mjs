import test from "node:test";
import assert from "node:assert/strict";
import { chmod, copyFile, lstat, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { Writable } from "node:stream";
import {
  activateScheduledPostgresqlBackup,
  captureScheduledPostgresqlBackup,
  createPostgresqlContainerToolWrapperSource,
  createBackupGenerationId,
  exportScheduledBackup,
  loadPostgresqlCaptureScheduleConfig,
  loadPostgresqlOffHostConfig,
  readLatestBackupDescriptor,
  runOffHostRecoveryCycle,
} from "../../ops/lib/postgresql-automation.mjs";
import { sha256File } from "../../ops/lib/config.mjs";
import { OPS_ERROR_DEFINITIONS, createOpsError } from "../../ops/lib/errors.mjs";

test("R5-E7 creates immutable generations and never prunes unacknowledged captures", async (t) => {
  const fixture = await createFixture(t);
  const first = await capture(fixture, "20260906T031500000Z-aaaaaaaaaaaa", "2026-09-06T03:15:00.000Z");
  assert.equal(first.command, "postgresql-scheduled-capture");
  const firstRoot = path.join(fixture.capture.backupRoot, first.generationId);
  assert.equal((await lstat(path.join(firstRoot, "postgresql.cms"))).mode & 0o777, 0o600);

  await capture(fixture, "20260907T031500000Z-bbbbbbbbbbbb", "2026-09-07T03:15:00.000Z");
  await capture(fixture, "20260908T031500000Z-cccccccccccc", "2026-09-08T03:15:00.000Z");
  assert.equal((await readdir(fixture.capture.backupRoot)).includes(first.generationId), true);

  await writeFile(path.join(firstRoot, "activated.ack.json"), "{}\n", { mode: 0o600 });
  await capture(fixture, "20260909T031500000Z-dddddddddddd", "2026-09-09T03:15:00.000Z");
  assert.equal((await readdir(fixture.capture.backupRoot)).includes(first.generationId), false);
  const latest = await readLatestBackupDescriptor(fixture.capture, productionOptions());
  assert.equal(latest.generationId, "20260909T031500000Z-dddddddddddd");
});

test("R5-E7 exports only the latest complete generation", async (t) => {
  const fixture = await createFixture(t);
  const result = await capture(fixture, "20260906T031500000Z-aaaaaaaaaaaa", "2026-09-06T03:15:00.000Z");
  const chunks = [];
  const writable = new WritableCollector(chunks);
  await exportScheduledBackup(fixture.capture, result.generationId, "archive", writable, productionOptions());
  assert.equal(Buffer.concat(chunks).toString(), "encrypted-generation");
  await assert.rejects(
    () => exportScheduledBackup(fixture.capture, "20260905T031500000Z-bbbbbbbbbbbb", "archive", new WritableCollector([]), productionOptions()),
    hasCause("postgresql_automation_generation_not_latest"),
  );
});

test("R5-E7 refuses a generation collision without deleting the existing capture", async (t) => {
  const fixture = await createFixture(t);
  const generationId = "20260906T031500000Z-aaaaaaaaaaaa";
  await capture(fixture, generationId, "2026-09-06T03:15:00.000Z");
  await assert.rejects(
    () => capture(fixture, generationId, "2026-09-06T03:15:00.000Z"),
    isCode("HR-OPS-013"),
  );
  assert.equal(await readFile(path.join(fixture.capture.backupRoot, generationId, "postgresql.cms"), "utf8"), "encrypted-generation");
});

test("R5-E7 activates status only after bound restore evidence and records acknowledgement", async (t) => {
  const fixture = await createFixture(t);
  const captureResult = await capture(fixture, "20260906T031500000Z-aaaaaaaaaaaa", "2026-09-06T03:15:00.000Z");
  const evidence = path.join(fixture.base, "evidence.json");
  const status = path.join(fixture.base, "status.json");
  await privateFile(evidence, "{}\n");
  await privateFile(status, "{}\n");
  let publishConfig;
  const activated = await activateScheduledPostgresqlBackup(
    fixture.capture, captureResult.generationId, evidence, status,
    { ...productionOptions(), publish: async (config) => { publishConfig = config; return { backupCompletedAt: "2026-09-06T03:15:00.000Z" }; } },
  );
  assert.equal(activated.ok, true);
  assert.equal(publishConfig.activeStatusFile, fixture.capture.activeStatusFile);
  const generationRoot = path.join(fixture.capture.backupRoot, captureResult.generationId);
  assert.equal((await lstat(path.join(generationRoot, "activated.ack.json"))).mode & 0o777, 0o600);
  assert.equal((await readFile(path.join(generationRoot, "postgresql-restore.evidence.json"), "utf8")), "{}\n");
  const replay = await activateScheduledPostgresqlBackup(
    fixture.capture, captureResult.generationId, evidence, status,
    { ...productionOptions(), publish: async () => ({ backupCompletedAt: "2026-09-06T03:15:00.000Z" }) },
  );
  assert.equal(replay.alreadyActive, true);
});

test("R5-E7 off-host cycle verifies bytes, restores, activates, and is idempotent", async (t) => {
  const fixture = await createFixture(t);
  const generationId = "20260906T031500000Z-aaaaaaaaaaaa";
  const archive = path.join(fixture.base, "remote.cms");
  const manifest = path.join(fixture.base, "remote.manifest.json");
  await privateFile(archive, "encrypted-generation");
  const archiveSha256 = await sha256File(archive);
  await privateJson(manifest, backupManifest(archiveSha256, 20));
  const descriptor = {
    schemaVersion: 1, kind: "hermes-go-postgresql-backup-generation-v1", generationId,
    sourceHostname: "prod-host", createdAt: "2026-09-06T03:15:00.000Z",
    archiveSha256, archiveBytes: 20, postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
  const calls = [];
  const remote = {
    latest: async () => descriptor,
    download: async (_generation, part, target) => copyFile(part === "archive" ? archive : manifest, target),
    activate: async (generation, evidence, status) => calls.push({ generation, evidence, status }),
  };
  const restore = async (_config, paths) => {
    await privateFile(paths.evidenceFile, "evidence\n");
    await privateFile(paths.statusFile, "status\n");
  };
  const first = await runOffHostRecoveryCycle(fixture.offhost, { hostname: "mac-mini", remote, restore, now: () => new Date("2026-09-06T04:00:00.000Z") });
  assert.equal(first.alreadyComplete, false);
  assert.equal(calls.length, 1);
  const second = await runOffHostRecoveryCycle(fixture.offhost, { hostname: "mac-mini", remote, restore });
  assert.equal(second.alreadyComplete, true);
  assert.equal(calls.length, 1);
});

test("R5-E7 PostgreSQL wrapper uses the running Node when LaunchAgent PATH has no node", async (t) => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "postgresql-wrapper-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const emptyPath = path.join(base, "empty-path");
  const wrapper = path.join(base, "psql");
  await mkdir(emptyPath, { mode: 0o700 });
  const source = createPostgresqlContainerToolWrapperSource({
    docker: "/usr/bin/true", container: "test-container", role: "test-role", database: "test-database",
  });
  await writeFile(wrapper, source, { mode: 0o700 });
  await chmod(wrapper, 0o700);

  assert.equal(source.startsWith(`#!${process.execPath}\n`), true);
  assert.doesNotMatch(source, /^#!\/usr\/bin\/env node/);
  const result = spawnSync(wrapper, ["--version"], {
    encoding: "utf8", env: { PATH: emptyPath, HOME: base, LANG: "C" }, shell: false,
  });
  assert.equal(result.status, 0, String(result.stderr));
});

test("R5-E7 rejects a corrupted off-host download before restore or activation", async (t) => {
  const fixture = await createFixture(t);
  const archiveSha256 = "a".repeat(64);
  const descriptor = {
    schemaVersion: 1, kind: "hermes-go-postgresql-backup-generation-v1",
    generationId: "20260906T031500000Z-aaaaaaaaaaaa", sourceHostname: "prod-host",
    createdAt: "2026-09-06T03:15:00.000Z", archiveSha256, archiveBytes: 20,
    postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
  let restored = false;
  let activated = false;
  const remote = {
    latest: async () => descriptor,
    download: async (_generation, part, target) => part === "archive"
      ? privateFile(target, "encrypted-generation")
      : privateJson(target, backupManifest(archiveSha256, 20)),
    activate: async () => { activated = true; },
  };
  await assert.rejects(
    () => runOffHostRecoveryCycle(fixture.offhost, { hostname: "mac-mini", remote, restore: async () => { restored = true; } }),
    hasCause("postgresql_automation_download_identity_mismatch"),
  );
  assert.equal(restored, false);
  assert.equal(activated, false);
});

test("R5-E7 strict configs, templates, bundle inputs, and HR-OPS-013 remain wired", async (t) => {
  const fixture = await createFixture(t);
  const captureConfig = path.join(fixture.base, "capture.json");
  const offhostConfig = path.join(fixture.base, "offhost.json");
  await privateJson(captureConfig, fixture.capture);
  await privateJson(offhostConfig, fixture.offhost);
  assert.equal((await loadPostgresqlCaptureScheduleConfig(captureConfig)).retentionCount, 2);
  assert.equal((await loadPostgresqlOffHostConfig(offhostConfig)).dockerPath, "/Applications/Docker.app/Contents/Resources/bin/docker");
  await privateJson(captureConfig, { ...fixture.capture, unexpected: true });
  await assert.rejects(() => loadPostgresqlCaptureScheduleConfig(captureConfig), isCode("HR-OPS-013"));

  for (const file of [
    "ops/hermesctl-postgresql-capture-schedule-config.schema.json",
    "ops/hermesctl-postgresql-offhost-config.schema.json",
    "ops/postgresql-backup-generation.schema.json",
  ]) assert.equal(JSON.parse(await readFile(file, "utf8")).additionalProperties, false);
  const service = await readFile("deploy/hermes-go-postgresql-capture.service.template", "utf8");
  const timer = await readFile("deploy/hermes-go-postgresql-capture.timer.template", "utf8");
  const plist = await readFile("deploy/com.hermesgo.postgresql-offhost.plist.template", "utf8");
  const remoteWrapper = await readFile("deploy/hermes-go-postgresql-automation-remote.template", "utf8");
  const sudoers = await readFile("deploy/hermes-go-postgresql-automation.sudoers.template", "utf8");
  assert.match(service, /User=root[\s\S]*ProtectSystem=strict[\s\S]*ReadWritePaths=\/var\/backups\/hermes-go\/postgresql-generations/);
  assert.match(service, /flock --exclusive --nonblock \/run\/lock\/hermes-go-postgresql-automation\.lock/);
  assert.match(timer, /OnCalendar=\*-\*-\* 03:15:00 Asia\/Hong_Kong[\s\S]*Persistent=true/);
  assert.match(plist, /StartInterval<\/key><integer>3600<\/integer>/);
  assert.match(remoteWrapper, /"\$@"[\s\S]*--config \/etc\/hermes-go\/recovery\/postgresql-capture-schedule\.json/);
  assert.match(remoteWrapper, /flock --exclusive --wait 300/);
  assert.doesNotMatch(sudoers, /node|postgresql-automation\.mjs|\*/);
  assert.equal(OPS_ERROR_DEFINITIONS.databaseRecovery.code, "HR-OPS-013");
  assert.match(await readFile("docs/ERROR_HANDLING.md", "utf8"), /`HR-OPS-013`/);
});

test("R5-E7 generation IDs are UTC sortable and entropy bounded", () => {
  assert.equal(createBackupGenerationId(new Date("2026-09-06T03:15:00.000Z"), "abcdef123456"), "20260906T031500000Z-abcdef123456");
  assert.throws(() => createBackupGenerationId(new Date("2026-09-06T03:15:00.000Z"), "unsafe"), isCode("HR-OPS-013"));
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "postgresql-automation-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const backupRoot = path.join(base, "production-generations");
  const localRoot = path.join(base, "offhost-generations");
  await mkdir(backupRoot, { mode: 0o700 });
  await mkdir(localRoot, { mode: 0o700 });
  const capture = {
    schemaVersion: 1, environment: "production", operator: "test-operator",
    sourceHostname: "prod-host", serviceName: "postgresql",
    databaseUrlFile: path.join(base, "database-url"), recipientCertificate: path.join(base, "recipient-cert.pem"),
    backupRoot, latestDescriptorFile: path.join(backupRoot, "latest-ready.json"),
    activeStatusFile: path.join(base, "latest-status.json"), maximumEncryptedBytes: 1024 * 1024,
    retentionCount: 2, postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
  const offhost = {
    schemaVersion: 1, environment: "off-host-recovery", operator: "test-operator",
    expectedSourceHostname: "prod-host", offHostStorageId: "mac-recovery-store",
    remoteHost: "203.0.113.8", remoteUser: "operator", sshIdentityFile: path.join(base, "id_ed25519"),
    knownHostsFile: path.join(base, "known_hosts"),
    remoteCommandPath: "/usr/local/sbin/hermes-go-postgresql-automation-remote",
    localRoot, recipientCertificate: path.join(base, "recipient-cert.pem"),
    recipientPrivateKey: path.join(base, "recipient-key.pem"),
    targetArtifactManifest: path.join(base, "gateway.manifest.json"),
    dockerPath: "/Applications/Docker.app/Contents/Resources/bin/docker",
    opensslPath: "/opt/homebrew/opt/openssl@3/bin/openssl",
    postgresqlImage: `postgres:18-alpine@sha256:${"b".repeat(64)}`,
    maximumEncryptedBytes: 1024 * 1024, retentionCount: 2,
    postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
  await privateFile(offhost.sshIdentityFile, "test-identity\n");
  await privateFile(offhost.knownHostsFile, "test-host-key\n");
  await privateFile(offhost.recipientPrivateKey, "test-private-key\n");
  return { base, capture, offhost };
}

async function capture(fixture, generationId, timestamp) {
  return captureScheduledPostgresqlBackup(fixture.capture, {
    ...productionOptions(), generationId, now: () => new Date(timestamp),
    capture: async (config) => {
      await privateFile(config.archiveFile, "encrypted-generation");
      const archiveSha256 = await sha256File(config.archiveFile);
      await privateJson(config.manifestFile, backupManifest(archiveSha256, 20, timestamp));
      return { createdAt: timestamp, archiveSha256, archiveBytes: 20 };
    },
  });
}

function backupManifest(archiveSha256, archiveBytes, createdAt = "2026-09-06T03:15:00.000Z") {
  return {
    schemaVersion: 1, kind: "hermes-go-postgresql-backup-v1", sourceHostname: "prod-host", createdAt,
    archiveSha256, archiveBytes,
    encryption: { kind: "openssl-cms-auth-enveloped-aes-256-gcm", recipientCertificateSha256: "c".repeat(64) },
    postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
}

function productionOptions() {
  return { confirmation: "production:prod-host", hostname: "prod-host", platform: "linux", getUid: () => 0 };
}

async function privateFile(file, value) {
  await writeFile(file, value, { mode: 0o600 });
  await chmod(file, 0o600);
}

function privateJson(file, value) {
  return privateFile(file, `${JSON.stringify(value, null, 2)}\n`);
}

function hasCause(cause) {
  return (error) => error?.kind === "databaseRecovery" && error.technicalCause.includes(cause);
}

function isCode(code) {
  return (error) => createOpsError(error?.kind, error?.technicalCause, error?.stage).code === code;
}

class WritableCollector extends Writable {
  constructor(chunks) { super(); this.chunks = chunks; }
  _write(chunk, _encoding, callback) { this.chunks.push(Buffer.from(chunk)); callback(); }
}
