import test from "node:test";
import assert from "node:assert/strict";
import {
  access, chmod, lstat, mkdir, mkdtemp, readFile, realpath, rm, writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  loadPostgresqlBackupConfig, loadPostgresqlBackupManifest, loadPostgresqlRestoreConfig,
  loadPostgresqlStatusActivationConfig,
} from "../../ops/lib/postgresql-recovery-config.mjs";
import {
  capturePostgresqlBackup, publishPostgresqlBackupStatus, verifyPostgresqlRestore,
} from "../../ops/lib/postgresql-recovery.mjs";
import { loadProductionEvidence } from "../../ops/lib/production-config.mjs";
import { loadPostgresqlBackupStatus } from "../../ops/lib/production-monitor-config.mjs";
import { OPS_ERROR_DEFINITIONS, createOpsError } from "../../ops/lib/errors.mjs";

test("R5-E publishes status only after encrypted capture and off-host restore checks", async (t) => {
  const fixture = await createFixture(t);
  const captured = await capturePostgresqlBackup(fixture.backup, {
    confirmation: "production:prod-host",
    hostname: "prod-host",
    platform: "linux",
    runner: healthyRunner(),
    inspectDatabase: async () => facts(),
    writeArchive: async (_url, _certificate, archive) => writeFile(archive, "authenticated-encrypted-backup", { mode: 0o600 }),
    now: () => new Date("2026-09-04T02:00:00.000Z"),
  });
  assert.equal(captured.encryption, "openssl-cms-auth-enveloped-aes-256-gcm");
  const manifest = await loadPostgresqlBackupManifest(fixture.backup.manifestFile);
  assert.equal(manifest.archiveSha256, captured.archiveSha256);

  const smoke = accountSmokeRunner(fixture.restore.targetImageId);
  const restored = await verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host",
    hostname: "mac-restore-host",
    runner: smoke.runner,
    inspectEmptyDatabase: async () => true,
    restoreDatabase: async () => {},
    inspectDatabase: async () => facts(),
    now: () => new Date("2026-09-04T02:05:00.000Z"),
  });
  assert.deepEqual(restored.verifiedChecks, [
    "encrypted_backup_hash", "database_restore", "schema_exact", "account_smoke",
  ]);
  const evidence = await loadProductionEvidence(fixture.restore.evidenceFile, "hermes-go-postgresql-restore-v1");
  assert.equal(evidence.restoreHostname, "mac-restore-host");
  const status = await loadPostgresqlBackupStatus(fixture.restore.statusFile);
  assert.equal(status.artifactSha256, status.offHostSha256);
  assert.equal(status.encryptedBytes, status.offHostBytes);
  assert.equal(status.offHostStorageId, "mac-recovery-store");
  const dockerRun = smoke.calls.find((call) => call.command === "docker" && call.args[0] === "run");
  assert.equal(dockerRun.args.includes("--read-only"), true);
  assert.equal(dockerRun.args.includes("--cap-drop=ALL"), true);
  assert.equal(dockerRun.args.includes(fixture.restore.targetImageId), true);
  assert.equal(dockerRun.args.some((value) => value.includes("postgresql://")), false);
  assert.equal((await lstat(fixture.restore.evidenceFile)).mode & 0o777, 0o600);
  assert.equal((await lstat(fixture.restore.statusFile)).mode & 0o777, 0o600);
  const activated = await publishPostgresqlBackupStatus(fixture.activation, {
    confirmation: "production:prod-host",
    hostname: "prod-host",
    platform: "linux",
    getUid: () => 0,
    owner: { uid: process.getuid(), gid: process.getgid() },
  });
  assert.equal(activated.ok, true);
  assert.equal((await lstat(fixture.activation.activeStatusFile)).mode & 0o777, 0o640);
  assert.equal((await loadPostgresqlBackupStatus(fixture.activation.activeStatusFile)).artifactSha256, captured.archiveSha256);
});

test("R5-E fails closed when account smoke fails and removes evidence and status", async (t) => {
  const fixture = await createFixture(t);
  await capture(fixture);
  await assert.rejects(() => verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host",
    hostname: "mac-restore-host",
    runner: healthyRunner(),
    inspectEmptyDatabase: async () => true,
    restoreDatabase: async () => {},
    inspectDatabase: async () => facts(),
    accountSmoke: async () => { throw new Error("token=must-not-leak password=must-not-leak"); },
  }), (error) => error?.kind === "databaseRecovery" && !error.technicalCause.includes("must-not-leak"));
  await assert.rejects(() => access(fixture.restore.evidenceFile));
  await assert.rejects(() => access(fixture.restore.statusFile));
});

test("R5-E rejects same-host restore and archive tampering", async (t) => {
  const fixture = await createFixture(t);
  await capture(fixture);
  await assert.rejects(() => verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host", hostname: "prod-host", runner: healthyRunner(),
  }), hasCause("postgresql_restore_must_be_off_host"));
  await writeFile(fixture.restore.archiveFile, "tampered", { mode: 0o600 });
  await assert.rejects(() => verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host", hostname: "mac-restore-host", runner: healthyRunner(),
  }), hasCause("postgresql_restore_archive_integrity_mismatch"));
});

test("R5-E rejects a non-isolated image database URL before restore", async (t) => {
  const fixture = await createFixture(t);
  await capture(fixture);
  await writeFile(fixture.restore.imageDatabaseUrlFile, "postgresql://user:secret@database.example.com:5432/hermes\n", { mode: 0o600 });
  await chmod(fixture.restore.imageDatabaseUrlFile, 0o600);
  await assert.rejects(() => verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host", hostname: "mac-restore-host", runner: healthyRunner(),
  }), hasCause("postgresql_database_url_not_isolated_loopback"));
  await assert.rejects(() => access(fixture.restore.evidenceFile));
  await assert.rejects(() => access(fixture.restore.statusFile));
});

test("R5-E refuses to activate status that diverges from restore evidence", async (t) => {
  const fixture = await createFixture(t);
  await capture(fixture);
  await verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host", hostname: "mac-restore-host", runner: healthyRunner(),
    inspectEmptyDatabase: async () => true, restoreDatabase: async () => {},
    inspectDatabase: async () => facts(), accountSmoke: async () => {},
  });
  const status = JSON.parse(await readFile(fixture.restore.statusFile, "utf8"));
  await privateJson(fixture.restore.statusFile, { ...status, offHostBytes: status.offHostBytes + 1 });
  await assert.rejects(() => publishPostgresqlBackupStatus(fixture.activation, {
    confirmation: "production:prod-host", hostname: "prod-host", platform: "linux",
    getUid: () => 0, owner: { uid: process.getuid(), gid: process.getgid() },
  }), hasCause("postgresql_status_evidence_mismatch"));
  await assert.rejects(() => access(fixture.activation.activeStatusFile));
});

test("R5-E rejects a non-empty target and a mismatched immutable image", async (t) => {
  const fixture = await createFixture(t);
  await capture(fixture);
  await assert.rejects(() => verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host", hostname: "mac-restore-host", runner: healthyRunner(),
    inspectEmptyDatabase: async () => false,
  }), hasCause("postgresql_restore_database_not_empty"));
  const wrongImage = accountSmokeRunner(`sha256:${"c".repeat(64)}`);
  await assert.rejects(() => verifyPostgresqlRestore(fixture.restore, {
    confirmation: "isolated:prod-host", hostname: "mac-restore-host", runner: wrongImage.runner,
    inspectEmptyDatabase: async () => true, restoreDatabase: async () => {}, inspectDatabase: async () => facts(),
  }), hasCause("postgresql_restore_target_image_identity_mismatch"));
  await assert.rejects(() => access(fixture.restore.evidenceFile));
  await assert.rejects(() => access(fixture.restore.statusFile));
});

test("R5-E strict schemas, examples, error contract, and URL secrecy remain wired", async (t) => {
  const fixture = await createFixture(t);
  const backupPath = path.join(fixture.base, "backup.config.json");
  const restorePath = path.join(fixture.base, "restore.config.json");
  const activationPath = path.join(fixture.base, "activation.config.json");
  await privateJson(backupPath, fixture.backup);
  await privateJson(restorePath, fixture.restore);
  await privateJson(activationPath, fixture.activation);
  assert.equal((await loadPostgresqlBackupConfig(backupPath)).postgresqlMajorVersion, 18);
  assert.equal((await loadPostgresqlRestoreConfig(restorePath)).offHostStorageId, "mac-recovery-store");
  assert.equal((await loadPostgresqlStatusActivationConfig(activationPath)).sourceHostname, "prod-host");
  await privateJson(backupPath, { ...fixture.backup, unexpected: true });
  await assert.rejects(() => loadPostgresqlBackupConfig(backupPath), isCode("HR-OPS-013"));

  for (const file of [
    "ops/hermesctl-postgresql-backup-config.schema.json",
    "ops/hermesctl-postgresql-restore-config.schema.json",
    "ops/postgresql-backup-manifest.schema.json",
    "ops/hermesctl-postgresql-status-activation-config.schema.json",
  ]) {
    const schema = JSON.parse(await readFile(file, "utf8"));
    assert.equal(schema.additionalProperties, false);
  }
  const definition = OPS_ERROR_DEFINITIONS.databaseRecovery;
  assert.equal(definition.code, "HR-OPS-013");
  assert.match(definition.summaryZh, /PostgreSQL/);
  assert.match(definition.summaryEn, /PostgreSQL/);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "inspect_database_recovery_stage_and_retry");
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.match(registry, /`HR-OPS-013`/);
  const secret = "postgresql://user:database-secret@127.0.0.1/hermes";
  assert.equal(JSON.stringify(createOpsError("databaseRecovery", secret)).includes("database-secret"), false);
  const cli = await readFile("scripts/postgresql-recovery.mjs", "utf8");
  assert.equal(cli.includes("hermesctl.mjs"), false);
});

async function createFixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "postgresql-recovery-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const recovery = path.join(base, "recovery");
  await mkdir(recovery, { mode: 0o700 });
  const databaseUrlFile = path.join(recovery, "database-url");
  const imageDatabaseUrlFile = path.join(recovery, "image-database-url");
  const recipientCertificate = path.join(recovery, "recipient-cert.pem");
  const recipientPrivateKey = path.join(recovery, "recipient-key.pem");
  await writeFile(databaseUrlFile, "postgresql://restore:secret@127.0.0.1:5432/hermes_restore\n", { mode: 0o600 });
  await writeFile(imageDatabaseUrlFile, "postgresql://restore:secret@host.docker.internal:5432/hermes_restore\n", { mode: 0o600 });
  await writeFile(recipientCertificate, "test-certificate\n", { mode: 0o644 });
  await writeFile(recipientPrivateKey, "test-private-key\n", { mode: 0o600 });
  await chmod(databaseUrlFile, 0o600);
  await chmod(imageDatabaseUrlFile, 0o600);
  await chmod(recipientPrivateKey, 0o600);
  const archiveFile = path.join(recovery, "postgresql.cms");
  const manifestFile = path.join(recovery, "postgresql.manifest.json");
  const backup = {
    schemaVersion: 1, environment: "production", operator: "test-operator",
    sourceHostname: "prod-host", serviceName: "postgresql", databaseUrlFile,
    recipientCertificate, archiveFile, manifestFile, maximumEncryptedBytes: 1024 * 1024,
    postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
  const restore = {
    schemaVersion: 1, environment: "isolated-restore", operator: "test-operator",
    expectedSourceHostname: "prod-host", archiveFile, manifestFile, recipientCertificate,
    recipientPrivateKey, databaseUrlFile, imageDatabaseUrlFile,
    targetImage: `hermes-go-gateway@sha256:${"a".repeat(64)}`,
    targetImageId: `sha256:${"b".repeat(64)}`,
    evidenceFile: path.join(recovery, "restore.evidence.json"),
    statusFile: path.join(recovery, "backup.status.json"),
    offHostStorageId: "mac-recovery-store", postgresqlMajorVersion: 18, databaseSchemaVersion: 7,
  };
  const activation = {
    schemaVersion: 1, environment: "production", operator: "test-operator",
    sourceHostname: "prod-host", manifestFile,
    restoreEvidenceFile: restore.evidenceFile,
    candidateStatusFile: restore.statusFile,
    activeStatusFile: path.join(recovery, "latest-status.json"),
  };
  return { base, backup, restore, activation };
}

async function capture(fixture) {
  return capturePostgresqlBackup(fixture.backup, {
    confirmation: "production:prod-host", hostname: "prod-host", platform: "linux",
    runner: healthyRunner(), inspectDatabase: async () => facts(),
    writeArchive: async (_url, _certificate, archive) => writeFile(archive, "authenticated-encrypted-backup", { mode: 0o600 }),
  });
}

function facts() {
  return { postgresqlMajorVersion: 18, databaseSchemaVersion: 7 };
}

function healthyRunner() {
  return { run: () => ({ status: 0, stdout: "", stderr: "" }) };
}

function accountSmokeRunner(imageId) {
  const calls = [];
  return {
    calls,
    runner: {
      run(command, args) {
        calls.push({ command, args });
        if (command === "docker" && args[0] === "image") return { status: 0, stdout: `${imageId}\n`, stderr: "" };
        if (command === "docker" && args[0] === "run") {
          return { status: 0, stdout: 'ACCOUNT_RESTORE_VERIFY_OK {"accountSmoke":"pass"}\n', stderr: "" };
        }
        return { status: 0, stdout: "", stderr: "" };
      },
    },
  };
}

function hasCause(cause) {
  return (error) => error?.kind === "databaseRecovery" && error.technicalCause.includes(cause);
}

function isCode(code) {
  return (error) => createOpsError(error?.kind, error?.technicalCause, error?.stage).code === code;
}

function privateJson(filePath, value) {
  return writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
}
