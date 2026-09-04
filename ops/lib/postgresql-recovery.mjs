import { randomUUID } from "node:crypto";
import { createWriteStream } from "node:fs";
import { chmod, chown, lstat, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { sha256File } from "./config.mjs";
import { OpsError } from "./errors.mjs";
import { loadPostgresqlBackupManifest } from "./postgresql-recovery-config.mjs";
import { loadProductionEvidence } from "./production-config.mjs";
import { loadPostgresqlBackupStatus } from "./production-monitor-config.mjs";
import { assertNoSymlinkAncestors, createCommandRunner } from "./system.mjs";

const ENCRYPTION_KIND = "openssl-cms-auth-enveloped-aes-256-gcm";
const REQUIRED_CHECKS = Object.freeze([
  "encrypted_backup_hash", "database_restore", "schema_exact", "account_smoke",
]);

export async function capturePostgresqlBackup(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 30_000 });
  const actualHostname = options.hostname ?? systemHostname();
  if (options.confirmation !== `production:${config.sourceHostname}`) fail("postgresql_backup_confirmation_required", "postgresql_backup_authorize");
  if ((options.platform ?? process.platform) !== "linux" || actualHostname !== config.sourceHostname) {
    fail("postgresql_backup_host_mismatch", "postgresql_backup_authorize");
  }
  prerequisites(runner, ["openssl", "pg_dump", "psql", "systemctl"]);
  assertServiceActive(runner, config.serviceName, "postgresql_backup_service_before");
  const cleanup = [config.archiveFile, config.manifestFile];
  try {
    await assertSecureInput(config.databaseUrlFile, true, "postgresql_backup_database_url_unsafe");
    await assertSecureInput(config.recipientCertificate, false, "postgresql_backup_certificate_unsafe");
    await prepareNewOutput(config.archiveFile);
    await prepareNewOutput(config.manifestFile);
    const databaseUrl = await readDatabaseUrl(config.databaseUrlFile);
    const inspect = options.inspectDatabase ?? inspectDatabase;
    const before = await inspect(databaseUrl);
    assertExpectedDatabase(before, config, "postgresql_backup_database_before");
    await verifyCertificate(runner, config.recipientCertificate, "postgresql_backup_certificate_invalid");
    const writeArchive = options.writeArchive ?? dumpEncryptedArchive;
    await writeArchive(databaseUrl, config.recipientCertificate, config.archiveFile);
    const archiveInfo = await lstat(config.archiveFile);
    if (!archiveInfo.isFile() || archiveInfo.isSymbolicLink() || archiveInfo.size < 1
        || archiveInfo.size > config.maximumEncryptedBytes) {
      fail("postgresql_backup_archive_size_invalid", "postgresql_backup_encrypt");
    }
    const after = await inspect(databaseUrl);
    assertExpectedDatabase(after, config, "postgresql_backup_database_after");
    if (JSON.stringify(before) !== JSON.stringify(after)) fail("postgresql_backup_database_changed", "postgresql_backup_consistency");
    assertServiceActive(runner, config.serviceName, "postgresql_backup_service_after");
    const createdAt = (options.now ?? (() => new Date()))().toISOString();
    const manifest = {
      schemaVersion: 1,
      kind: "hermes-go-postgresql-backup-v1",
      sourceHostname: config.sourceHostname,
      createdAt,
      archiveSha256: await sha256File(config.archiveFile),
      archiveBytes: archiveInfo.size,
      encryption: {
        kind: ENCRYPTION_KIND,
        recipientCertificateSha256: await sha256File(config.recipientCertificate),
      },
      postgresqlMajorVersion: before.postgresqlMajorVersion,
      databaseSchemaVersion: before.databaseSchemaVersion,
    };
    await writePrivateJson(config.manifestFile, manifest);
    cleanup.length = 0;
    return {
      ok: true,
      command: "postgresql-backup",
      environment: config.environment,
      sourceHostname: config.sourceHostname,
      createdAt,
      archiveSha256: manifest.archiveSha256,
      archiveBytes: manifest.archiveBytes,
      encryption: ENCRYPTION_KIND,
      subject: {
        postgresqlMajorVersion: before.postgresqlMajorVersion,
        databaseSchemaVersion: before.databaseSchemaVersion,
      },
    };
  } catch (error) {
    for (const output of cleanup) await rm(output, { force: true }).catch(() => {});
    rethrow(error, "postgresql_backup_execute");
  }
}

export async function verifyPostgresqlRestore(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 90_000 });
  const now = options.now ?? (() => new Date());
  const restoreHostname = options.hostname ?? systemHostname();
  if (options.confirmation !== `isolated:${config.expectedSourceHostname}`) fail("postgresql_restore_confirmation_required", "postgresql_restore_authorize");
  if (restoreHostname === config.expectedSourceHostname) fail("postgresql_restore_must_be_off_host", "postgresql_restore_authorize");
  prerequisites(runner, ["docker", "openssl", "pg_restore", "psql"]);
  const manifest = await loadPostgresqlBackupManifest(config.manifestFile);
  if (manifest.sourceHostname !== config.expectedSourceHostname) fail("postgresql_restore_source_mismatch", "postgresql_restore_validate");
  if (manifest.postgresqlMajorVersion !== config.postgresqlMajorVersion
      || manifest.databaseSchemaVersion !== config.databaseSchemaVersion) {
    fail("postgresql_restore_subject_mismatch", "postgresql_restore_validate");
  }
  await assertSecureInput(config.archiveFile, false, "postgresql_restore_archive_unsafe");
  await assertSecureInput(config.recipientCertificate, false, "postgresql_restore_certificate_unsafe");
  await assertSecureInput(config.recipientPrivateKey, true, "postgresql_restore_private_key_unsafe");
  await assertSecureInput(config.databaseUrlFile, true, "postgresql_restore_database_url_unsafe");
  await assertSecureInput(config.imageDatabaseUrlFile, true, "postgresql_restore_image_database_url_unsafe");
  await readDatabaseUrl(config.imageDatabaseUrlFile, { allowDockerHost: true, allowNonstandardPort: true });
  await prepareNewOutput(config.evidenceFile);
  await prepareNewOutput(config.statusFile);
  if (await sha256File(config.recipientCertificate) !== manifest.encryption.recipientCertificateSha256) {
    fail("postgresql_restore_certificate_mismatch", "postgresql_restore_validate");
  }
  const archiveInfo = await lstat(config.archiveFile);
  if (archiveInfo.size !== manifest.archiveBytes || await sha256File(config.archiveFile) !== manifest.archiveSha256) {
    fail("postgresql_restore_archive_integrity_mismatch", "postgresql_restore_validate");
  }
  const offHostCopiedAt = now().toISOString();
  const databaseUrl = await readDatabaseUrl(config.databaseUrlFile, { allowNonstandardPort: true });
  const inspectEmpty = options.inspectEmptyDatabase ?? inspectEmptyDatabase;
  if (!await inspectEmpty(databaseUrl)) fail("postgresql_restore_database_not_empty", "postgresql_restore_preflight");

  try {
    const restore = options.restoreDatabase ?? restoreEncryptedArchive;
    await restore(config, databaseUrl);
    const inspect = options.inspectDatabase ?? inspectDatabase;
    const facts = await inspect(databaseUrl);
    assertExpectedDatabase(facts, config, "postgresql_restore_schema_verify");
    const smoke = options.accountSmoke ?? runAccountSmoke;
    await smoke(config, runner);
    const restoredAt = now().toISOString();
    const evidence = {
      schemaVersion: 1,
      kind: "hermes-go-postgresql-restore-v1",
      sourceHostname: manifest.sourceHostname,
      createdAt: manifest.createdAt,
      artifactSha256: manifest.archiveSha256,
      subject: {
        databaseSchemaVersion: facts.databaseSchemaVersion,
        postgresqlMajorVersion: facts.postgresqlMajorVersion,
      },
      restoreHostname,
      restoredAt,
      verifiedChecks: REQUIRED_CHECKS,
    };
    const status = {
      schemaVersion: 1,
      kind: "hermes-go-postgresql-backup-status-v1",
      sourceHostname: manifest.sourceHostname,
      backupCompletedAt: manifest.createdAt,
      offHostCopiedAt,
      artifactSha256: manifest.archiveSha256,
      encryptedBytes: manifest.archiveBytes,
      offHostSha256: manifest.archiveSha256,
      offHostBytes: manifest.archiveBytes,
      postgresqlMajorVersion: facts.postgresqlMajorVersion,
      databaseSchemaVersion: facts.databaseSchemaVersion,
      offHostStorageId: config.offHostStorageId,
    };
    await writePrivateJson(config.evidenceFile, evidence);
    try {
      await writePrivateJson(config.statusFile, status);
    } catch (error) {
      await rm(config.evidenceFile, { force: true }).catch(() => {});
      throw error;
    }
    return {
      ok: true,
      command: "postgresql-restore",
      environment: config.environment,
      sourceHostname: manifest.sourceHostname,
      restoreHostname,
      restoredAt,
      offHostCopiedAt,
      artifactSha256: manifest.archiveSha256,
      subject: evidence.subject,
      verifiedChecks: REQUIRED_CHECKS,
      offHostStorageId: config.offHostStorageId,
    };
  } catch (error) {
    await rm(config.evidenceFile, { force: true }).catch(() => {});
    await rm(config.statusFile, { force: true }).catch(() => {});
    rethrow(error, "postgresql_restore_execute");
  }
}

export async function publishPostgresqlBackupStatus(config, options = {}) {
  const actualHostname = options.hostname ?? systemHostname();
  if (options.confirmation !== `production:${config.sourceHostname}`) fail("postgresql_status_confirmation_required", "postgresql_status_authorize");
  if ((options.platform ?? process.platform) !== "linux" || actualHostname !== config.sourceHostname) {
    fail("postgresql_status_host_mismatch", "postgresql_status_authorize");
  }
  if ((options.getUid ?? (() => process.getuid?.()))() !== 0) fail("postgresql_status_requires_root", "postgresql_status_authorize");
  try {
    await assertSecureInput(config.manifestFile, true, "postgresql_status_manifest_unsafe");
    await assertSecureInput(config.restoreEvidenceFile, true, "postgresql_status_evidence_unsafe");
    await assertSecureInput(config.candidateStatusFile, true, "postgresql_status_candidate_unsafe");
    const manifest = await loadPostgresqlBackupManifest(config.manifestFile);
    const evidence = await loadProductionEvidence(config.restoreEvidenceFile, "hermes-go-postgresql-restore-v1");
    const status = await loadPostgresqlBackupStatus(config.candidateStatusFile);
    if (manifest.sourceHostname !== config.sourceHostname
        || evidence.sourceHostname !== config.sourceHostname
        || status.sourceHostname !== config.sourceHostname
        || evidence.artifactSha256 !== manifest.archiveSha256
        || status.artifactSha256 !== manifest.archiveSha256
        || status.offHostSha256 !== manifest.archiveSha256
        || status.encryptedBytes !== manifest.archiveBytes
        || status.offHostBytes !== manifest.archiveBytes
        || evidence.createdAt !== manifest.createdAt
        || status.backupCompletedAt !== manifest.createdAt
        || Date.parse(status.offHostCopiedAt) < Date.parse(manifest.createdAt)
        || Date.parse(status.offHostCopiedAt) > Date.parse(evidence.restoredAt)
        || evidence.subject.postgresqlMajorVersion !== manifest.postgresqlMajorVersion
        || status.postgresqlMajorVersion !== manifest.postgresqlMajorVersion
        || evidence.subject.databaseSchemaVersion !== manifest.databaseSchemaVersion
        || status.databaseSchemaVersion !== manifest.databaseSchemaVersion) {
      fail("postgresql_status_evidence_mismatch", "postgresql_status_validate");
    }
    const activeParent = path.dirname(config.activeStatusFile);
    await assertNoSymlinkAncestors(activeParent);
    const parentInfo = await lstat(activeParent);
    if (!parentInfo.isDirectory() || parentInfo.isSymbolicLink() || (parentInfo.mode & 0o022) !== 0) {
      fail("postgresql_status_directory_unsafe", "postgresql_status_publish");
    }
    const owner = options.owner ?? { uid: 0, gid: parentInfo.gid };
    await atomicPrivateStatus(config.activeStatusFile, status, owner);
    return {
      ok: true,
      command: "postgresql-activate-status",
      environment: config.environment,
      sourceHostname: config.sourceHostname,
      backupCompletedAt: status.backupCompletedAt,
      offHostCopiedAt: status.offHostCopiedAt,
      postgresqlMajorVersion: status.postgresqlMajorVersion,
      databaseSchemaVersion: status.databaseSchemaVersion,
    };
  } catch (error) {
    if (error instanceof OpsError && error.kind === "databaseRecovery") throw error;
    throw new OpsError("databaseRecovery", error instanceof Error ? error.technicalCause ?? error.message : error, "postgresql_status_publish");
  }
}

async function inspectDatabase(databaseUrl) {
  const output = await runSensitive("psql", [
    "--no-psqlrc", "--tuples-only", "--no-align", "--set", "ON_ERROR_STOP=1",
    "--command", "SELECT current_setting('server_version_num') || '|' || (SELECT version::text FROM gateway_schema_state WHERE singleton = true)",
  ], databaseUrl);
  const match = /^(\d+)\|(\d+)$/.exec(output.trim());
  if (!match) fail("postgresql_database_facts_invalid", "postgresql_database_inspect");
  return {
    postgresqlMajorVersion: Math.floor(Number(match[1]) / 10_000),
    databaseSchemaVersion: Number(match[2]),
  };
}

async function inspectEmptyDatabase(databaseUrl) {
  const output = await runSensitive("psql", [
    "--no-psqlrc", "--tuples-only", "--no-align", "--set", "ON_ERROR_STOP=1",
    "--command", "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema')",
  ], databaseUrl);
  return output.trim() === "0";
}

async function dumpEncryptedArchive(databaseUrl, certificate, archiveFile) {
  const dump = spawnSensitive("pg_dump", ["--format=custom", "--no-owner", "--no-privileges"], databaseUrl, ["ignore", "pipe", "pipe"]);
  const openssl = spawn("openssl", ["cms", "-encrypt", "-binary", "-aes-256-gcm", "-outform", "DER", certificate], {
    stdio: ["pipe", "pipe", "pipe"], shell: false,
  });
  const output = createWriteStream(archiveFile, { flags: "wx", mode: 0o600 });
  const dumpError = bounded(dump.stderr);
  const opensslError = bounded(openssl.stderr);
  dump.stdout.pipe(openssl.stdin);
  openssl.stdout.pipe(output);
  const [dumpStatus, opensslStatus, outputStatus] = await Promise.all([processExit(dump), processExit(openssl), streamFinish(output)]);
  if (dumpStatus !== 0 || opensslStatus !== 0 || outputStatus !== 0) {
    fail(`postgresql_backup_pipeline_failed dump=${dumpStatus} openssl=${opensslStatus} output=${outputStatus} ${await dumpError} ${await opensslError}`, "postgresql_backup_encrypt");
  }
}

async function restoreEncryptedArchive(config, databaseUrl) {
  const openssl = spawn("openssl", [
    "cms", "-decrypt", "-binary", "-inform", "DER", "-in", config.archiveFile,
    "-recip", config.recipientCertificate, "-inkey", config.recipientPrivateKey,
  ], { stdio: ["ignore", "pipe", "pipe"], shell: false });
  const restore = spawnSensitive("pg_restore", [
    "--exit-on-error", "--single-transaction", "--no-owner", "--no-privileges",
  ], databaseUrl, ["pipe", "ignore", "pipe"]);
  const opensslError = bounded(openssl.stderr);
  const restoreError = bounded(restore.stderr);
  openssl.stdout.pipe(restore.stdin);
  const [opensslStatus, restoreStatus] = await Promise.all([processExit(openssl), processExit(restore)]);
  if (opensslStatus !== 0 || restoreStatus !== 0) {
    fail(`postgresql_restore_pipeline_failed openssl=${opensslStatus} restore=${restoreStatus} ${await opensslError} ${await restoreError}`, "postgresql_restore_database");
  }
}

async function runAccountSmoke(config, runner) {
  const inspected = runner.run("docker", ["image", "inspect", "--format", "{{.Id}}", config.targetImage], { allowFailure: true });
  if (inspected.status !== 0 || inspected.stdout.trim() !== config.targetImageId) {
    fail("postgresql_restore_target_image_identity_mismatch", "postgresql_restore_account_smoke");
  }
  const mountedUrl = "/run/secrets/hermes-go-restore-database-url";
  const secretInfo = await lstat(config.imageDatabaseUrlFile);
  const result = runner.run("docker", [
    "run", "--rm", "--read-only", "--network", "host",
    "--user", `${secretInfo.uid}:${secretInfo.gid}`,
    "--cap-drop=ALL", "--security-opt=no-new-privileges",
    "--memory=256m", "--cpus=1", "--pids-limit=128",
    "--mount", `type=bind,src=${config.imageDatabaseUrlFile},dst=${mountedUrl},readonly`,
    "--env", `ACCOUNT_DATABASE_URL_FILE=${mountedUrl}`,
    "--env", `ACCOUNT_DATABASE_SCHEMA_VERSION=${config.databaseSchemaVersion}`,
    "--env", `ACCOUNT_DATABASE_POSTGRESQL_MAJOR=${config.postgresqlMajorVersion}`,
    config.targetImageId,
    "node", "gateway/dist/ops/verify-account-restore.js",
  ], { allowFailure: true, timeout: 90_000 });
  if (result.status !== 0 || !result.stdout.startsWith("ACCOUNT_RESTORE_VERIFY_OK ")) {
    fail(`postgresql_restore_account_smoke_failed ${result.stderr}`, "postgresql_restore_account_smoke");
  }
}

async function readDatabaseUrl(filePath, options = {}) {
  const value = (await readFile(filePath, "utf8")).trim();
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    fail("postgresql_database_url_invalid", "postgresql_database_url_validate");
  }
  const permittedHosts = options.allowDockerHost
    ? new Set(["127.0.0.1", "localhost", "::1", "host.docker.internal"])
    : new Set(["127.0.0.1", "localhost", "::1"]);
  const port = Number(parsed.port || 5432);
  const portAllowed = options.allowNonstandardPort
    ? Number.isSafeInteger(port) && port >= 1024 && port <= 65535
    : port === 5432;
  if (!new Set(["postgres:", "postgresql:"]).has(parsed.protocol)
      || !permittedHosts.has(parsed.hostname)
      || !portAllowed || !parsed.pathname || parsed.pathname === "/") {
    fail("postgresql_database_url_not_isolated_loopback", "postgresql_database_url_validate");
  }
  return value;
}

function assertExpectedDatabase(facts, config, stage) {
  if (facts.postgresqlMajorVersion !== config.postgresqlMajorVersion
      || facts.databaseSchemaVersion !== config.databaseSchemaVersion) {
    fail("postgresql_database_subject_mismatch", stage);
  }
}

function prerequisites(runner, commands) {
  for (const command of commands) {
    if (runner.run("which", [command], { allowFailure: true }).status !== 0) fail(`missing_command=${command}`, "postgresql_recovery_dependencies");
  }
}

function assertServiceActive(runner, serviceName, stage) {
  if (runner.run("systemctl", ["is-active", "--quiet", `${serviceName}.service`], { allowFailure: true }).status !== 0) {
    fail("postgresql_service_not_active", stage);
  }
}

async function verifyCertificate(runner, certificate, cause) {
  const result = runner.run("openssl", ["x509", "-in", certificate, "-noout", "-checkend", "0"], { allowFailure: true });
  if (result.status !== 0) fail(cause, "postgresql_backup_certificate_validate");
}

async function assertSecureInput(filePath, privateFile, cause) {
  try {
    await assertNoSymlinkAncestors(path.dirname(filePath));
    const info = await lstat(filePath);
    if (!info.isFile() || info.isSymbolicLink() || (privateFile && (info.mode & 0o077) !== 0)) fail(cause, "postgresql_recovery_input_validate");
  } catch (error) {
    if (error instanceof OpsError && error.kind === "databaseRecovery") throw error;
    fail(cause, "postgresql_recovery_input_validate");
  }
}

async function prepareNewOutput(filePath) {
  try {
    await assertNoSymlinkAncestors(path.dirname(filePath));
  } catch {
    fail("postgresql_recovery_output_path_unsafe", "postgresql_recovery_output_validate");
  }
  try {
    await lstat(filePath);
    fail("postgresql_recovery_output_exists", "postgresql_recovery_output_validate");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

async function writePrivateJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { flag: "wx", mode: 0o600 });
  await chmod(filePath, 0o600);
}

async function atomicPrivateStatus(filePath, value, owner) {
  try {
    const existing = await lstat(filePath);
    if (!existing.isFile() || existing.isSymbolicLink() || (existing.mode & 0o022) !== 0) {
      fail("postgresql_status_existing_file_unsafe", "postgresql_status_publish");
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
  const temporary = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${randomUUID()}.tmp`);
  let committed = false;
  try {
    const handle = await open(temporary, "wx", 0o600);
    try {
      await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`);
      await handle.sync();
    } finally {
      await handle.close();
    }
    await chmod(temporary, 0o640);
    await chown(temporary, owner.uid, owner.gid);
    await rename(temporary, filePath);
    committed = true;
  } finally {
    if (!committed) await unlink(temporary).catch(() => {});
  }
}

function spawnSensitive(command, args, databaseUrl, stdio) {
  return spawn(command, args, {
    stdio,
    shell: false,
    env: { PATH: process.env.PATH ?? "/usr/bin:/bin", LANG: "C", PGDATABASE: databaseUrl },
  });
}

async function runSensitive(command, args, databaseUrl) {
  const child = spawnSensitive(command, args, databaseUrl, ["ignore", "pipe", "pipe"]);
  const stdout = bounded(child.stdout, 64 * 1024);
  const stderr = bounded(child.stderr, 64 * 1024);
  const status = await processExit(child);
  if (status !== 0) fail(`${command}_failed ${await stderr}`, "postgresql_database_command");
  return stdout;
}

function bounded(stream, maximum = 16 * 1024) {
  return new Promise((resolve) => {
    let value = "";
    stream.setEncoding("utf8");
    stream.on("data", (chunk) => { if (value.length < maximum) value += chunk.slice(0, maximum - value.length); });
    stream.on("end", () => resolve(value));
    stream.on("error", () => resolve("stream_error"));
  });
}

function processExit(child) {
  return new Promise((resolve) => {
    child.once("error", () => resolve(-1));
    child.once("close", (code) => resolve(code ?? -1));
  });
}

function streamFinish(stream) {
  return new Promise((resolve) => {
    stream.once("finish", () => resolve(0));
    stream.once("error", () => resolve(-1));
  });
}

function fail(cause, stage) {
  throw new OpsError("databaseRecovery", cause, stage);
}

function rethrow(error, stage) {
  if (error instanceof OpsError && error.kind === "databaseRecovery") throw error;
  throw new OpsError(
    "databaseRecovery",
    error instanceof OpsError ? error.technicalCause : error instanceof Error ? error.message : error,
    stage,
  );
}
