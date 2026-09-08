import { randomBytes } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { once } from "node:events";
import {
  chmod, copyFile, lstat, mkdir, open, readFile, readdir, rename, rm, writeFile,
} from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { OpsError } from "./errors.mjs";
import {
  capturePostgresqlBackup, publishPostgresqlBackupStatus, verifyPostgresqlRestore,
} from "./postgresql-recovery.mjs";
import { sha256File } from "./config.mjs";
import { loadPostgresqlBackupManifest } from "./postgresql-recovery-config.mjs";
import { assertNoSymlinkAncestors, createCommandRunner } from "./system.mjs";

const GENERATION = /^\d{8}T\d{9}Z-[0-9a-f]{12}$/;
const HOSTNAME = /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/;
const TOKEN = /^[A-Za-z0-9._:-]{1,128}$/;
const DESCRIPTOR_KIND = "hermes-go-postgresql-backup-generation-v1";
const ACK_KIND = "hermes-go-postgresql-backup-ack-v1";

export async function loadPostgresqlCaptureScheduleConfig(filePath) {
  return loadStrictConfig(filePath, [
    "schemaVersion", "environment", "operator", "sourceHostname", "serviceName",
    "databaseUrlFile", "recipientCertificate", "backupRoot", "latestDescriptorFile",
    "activeStatusFile", "maximumEncryptedBytes", "retentionCount",
    "postgresqlMajorVersion", "databaseSchemaVersion",
  ], (raw) => {
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("postgresql_automation_capture_environment_invalid");
    const result = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      sourceHostname: hostname(raw.sourceHostname, "sourceHostname"),
      serviceName: token(raw.serviceName, /^[a-z0-9][a-z0-9@.-]{0,62}$/, "serviceName"),
      databaseUrlFile: absolutePath(raw.databaseUrlFile, "databaseUrlFile"),
      recipientCertificate: absolutePath(raw.recipientCertificate, "recipientCertificate"),
      backupRoot: absolutePath(raw.backupRoot, "backupRoot"),
      latestDescriptorFile: absolutePath(raw.latestDescriptorFile, "latestDescriptorFile"),
      activeStatusFile: absolutePath(raw.activeStatusFile, "activeStatusFile"),
      maximumEncryptedBytes: integer(raw.maximumEncryptedBytes, 1024, 1024 ** 4, "maximumEncryptedBytes"),
      retentionCount: integer(raw.retentionCount, 2, 365, "retentionCount"),
      postgresqlMajorVersion: exactInteger(raw.postgresqlMajorVersion, 18, "postgresqlMajorVersion"),
      databaseSchemaVersion: integer(raw.databaseSchemaVersion, 1, 2_147_483_647, "databaseSchemaVersion"),
    };
    if (new Set([result.databaseUrlFile, result.recipientCertificate, result.backupRoot,
      result.latestDescriptorFile, result.activeStatusFile]).size !== 5) fail("postgresql_automation_paths_overlap");
    if (path.dirname(result.latestDescriptorFile) !== result.backupRoot) fail("postgresql_automation_descriptor_outside_root");
    return result;
  });
}

export async function loadPostgresqlOffHostConfig(filePath) {
  return loadStrictConfig(filePath, [
    "schemaVersion", "environment", "operator", "expectedSourceHostname", "offHostStorageId",
    "remoteHost", "remoteUser", "sshIdentityFile", "knownHostsFile", "remoteCommandPath",
    "localRoot", "recipientCertificate",
    "recipientPrivateKey", "targetArtifactManifest", "dockerPath", "opensslPath",
    "postgresqlImage", "maximumEncryptedBytes", "retentionCount", "postgresqlMajorVersion", "databaseSchemaVersion",
  ], (raw) => {
    if (raw.schemaVersion !== 1 || raw.environment !== "off-host-recovery") fail("postgresql_automation_offhost_environment_invalid");
    const result = {
      schemaVersion: 1,
      environment: "off-host-recovery",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      expectedSourceHostname: hostname(raw.expectedSourceHostname, "expectedSourceHostname"),
      offHostStorageId: token(raw.offHostStorageId, TOKEN, "offHostStorageId"),
      remoteHost: hostname(raw.remoteHost, "remoteHost"),
      remoteUser: token(raw.remoteUser, /^[a-z_][a-z0-9_-]{0,31}$/, "remoteUser"),
      sshIdentityFile: absolutePath(raw.sshIdentityFile, "sshIdentityFile"),
      knownHostsFile: absolutePath(raw.knownHostsFile, "knownHostsFile"),
      remoteCommandPath: remotePath(raw.remoteCommandPath, "remoteCommandPath"),
      localRoot: absolutePath(raw.localRoot, "localRoot"),
      recipientCertificate: absolutePath(raw.recipientCertificate, "recipientCertificate"),
      recipientPrivateKey: absolutePath(raw.recipientPrivateKey, "recipientPrivateKey"),
      targetArtifactManifest: absolutePath(raw.targetArtifactManifest, "targetArtifactManifest"),
      dockerPath: absoluteExecutable(raw.dockerPath, "dockerPath"),
      opensslPath: absoluteExecutable(raw.opensslPath, "opensslPath"),
      postgresqlImage: token(raw.postgresqlImage, /^postgres:18-alpine@sha256:[0-9a-f]{64}$/, "postgresqlImage"),
      maximumEncryptedBytes: integer(raw.maximumEncryptedBytes, 1024, 1024 ** 4, "maximumEncryptedBytes"),
      retentionCount: integer(raw.retentionCount, 2, 365, "retentionCount"),
      postgresqlMajorVersion: exactInteger(raw.postgresqlMajorVersion, 18, "postgresqlMajorVersion"),
      databaseSchemaVersion: integer(raw.databaseSchemaVersion, 1, 2_147_483_647, "databaseSchemaVersion"),
    };
    return result;
  });
}

export function createBackupGenerationId(date = new Date(), entropy = randomBytes(6).toString("hex")) {
  const stamp = date.toISOString().replace(/[-:.]/g, "");
  const generationId = `${stamp}-${entropy}`;
  if (!GENERATION.test(generationId)) fail("postgresql_automation_generation_invalid");
  return generationId;
}

export async function captureScheduledPostgresqlBackup(config, options = {}) {
  assertProduction(config, options, "capture");
  await assertPrivateDirectory(config.backupRoot);
  const generationId = options.generationId ?? createBackupGenerationId(options.now?.() ?? new Date());
  assertGeneration(generationId);
  const generationRoot = path.join(config.backupRoot, generationId);
  let generationCreated = false;
  let descriptorCommitted = false;
  const archiveFile = path.join(generationRoot, "postgresql.cms");
  const manifestFile = path.join(generationRoot, "postgresql.manifest.json");
  try {
    await mkdir(generationRoot, { mode: 0o700 });
    generationCreated = true;
    await chmod(generationRoot, 0o700);
    const capture = options.capture ?? capturePostgresqlBackup;
    const result = await capture({
      schemaVersion: 1,
      environment: "production",
      operator: config.operator,
      sourceHostname: config.sourceHostname,
      serviceName: config.serviceName,
      databaseUrlFile: config.databaseUrlFile,
      recipientCertificate: config.recipientCertificate,
      archiveFile,
      manifestFile,
      maximumEncryptedBytes: config.maximumEncryptedBytes,
      postgresqlMajorVersion: config.postgresqlMajorVersion,
      databaseSchemaVersion: config.databaseSchemaVersion,
    }, {
      confirmation: options.confirmation,
      hostname: options.hostname,
      platform: options.platform,
      runner: options.runner,
      now: options.now,
    });
    const descriptor = {
      schemaVersion: 1,
      kind: DESCRIPTOR_KIND,
      generationId,
      sourceHostname: config.sourceHostname,
      createdAt: result.createdAt,
      archiveSha256: result.archiveSha256,
      archiveBytes: result.archiveBytes,
      postgresqlMajorVersion: config.postgresqlMajorVersion,
      databaseSchemaVersion: config.databaseSchemaVersion,
    };
    await atomicJson(config.latestDescriptorFile, descriptor, 0o600);
    descriptorCommitted = true;
    await pruneSourceGenerations(config.backupRoot, config.retentionCount, generationId, false);
    return { ok: true, command: "postgresql-scheduled-capture", generationId, createdAt: result.createdAt };
  } catch (error) {
    if (generationCreated && !descriptorCommitted) {
      await rm(generationRoot, { recursive: true, force: true }).catch(() => {});
    }
    rethrow(error, "postgresql_automation_capture");
  }
}

export async function readLatestBackupDescriptor(config, options = {}) {
  assertProduction(config, options, "latest", { rootRequired: false });
  const descriptor = await readDescriptor(config.latestDescriptorFile, config);
  return descriptor;
}

export async function exportScheduledBackup(config, generationId, part, writable, options = {}) {
  assertProduction(config, options, "export", { rootRequired: false });
  assertGeneration(generationId);
  if (!new Set(["archive", "manifest"]).has(part)) fail("postgresql_automation_export_part_invalid");
  const descriptor = await readDescriptor(config.latestDescriptorFile, config);
  if (descriptor.generationId !== generationId) fail("postgresql_automation_generation_not_latest");
  const filePath = path.join(config.backupRoot, generationId,
    part === "archive" ? "postgresql.cms" : "postgresql.manifest.json");
  await assertSafeGenerationFile(filePath);
  await pipeFile(filePath, writable);
}

export async function activateScheduledPostgresqlBackup(config, generationId, evidenceInput, statusInput, options = {}) {
  assertProduction(config, options, "activate");
  assertGeneration(generationId);
  const descriptor = await readDescriptor(config.latestDescriptorFile, config);
  if (descriptor.generationId !== generationId) fail("postgresql_automation_generation_not_latest");
  const generationRoot = path.join(config.backupRoot, generationId);
  const manifestFile = path.join(generationRoot, "postgresql.manifest.json");
  const restoreEvidenceFile = path.join(generationRoot, "postgresql-restore.evidence.json");
  const candidateStatusFile = path.join(generationRoot, "postgresql-backup.status.json");
  const ackFile = path.join(generationRoot, "activated.ack.json");
  await assertSafeGenerationFile(evidenceInput);
  await assertSafeGenerationFile(statusInput);
  const ackExists = await exists(ackFile);
  if (ackExists) {
    await assertSafeGenerationFile(ackFile);
    const ack = JSON.parse(await readFile(ackFile, "utf8"));
    exactKeys(ack, ["schemaVersion", "kind", "generationId", "activatedAt"]);
    if (ack.schemaVersion !== 1 || ack.kind !== ACK_KIND || ack.generationId !== generationId
        || new Date(ack.activatedAt).toISOString() !== ack.activatedAt) fail("postgresql_automation_ack_invalid");
  }
  let published = false;
  let evidenceCreated = false;
  let statusCreated = false;
  try {
    evidenceCreated = await copyOrVerifyPrivate(evidenceInput, restoreEvidenceFile);
    statusCreated = await copyOrVerifyPrivate(statusInput, candidateStatusFile);
    const publish = options.publish ?? publishPostgresqlBackupStatus;
    const result = await publish({
      schemaVersion: 1,
      environment: "production",
      operator: config.operator,
      sourceHostname: config.sourceHostname,
      manifestFile,
      restoreEvidenceFile,
      candidateStatusFile,
      activeStatusFile: config.activeStatusFile,
    }, {
      confirmation: options.confirmation,
      hostname: options.hostname,
      platform: options.platform,
      getUid: options.getUid,
      owner: options.owner,
    });
    published = true;
    if (!ackExists) {
      await atomicJson(ackFile, {
        schemaVersion: 1,
        kind: ACK_KIND,
        generationId,
        activatedAt: (options.now?.() ?? new Date()).toISOString(),
      }, 0o600);
    }
    await pruneSourceGenerations(config.backupRoot, config.retentionCount, generationId, true);
    return { ok: true, command: "postgresql-scheduled-activate", generationId,
      backupCompletedAt: result.backupCompletedAt, alreadyActive: ackExists };
  } catch (error) {
    if (!published) {
      if (evidenceCreated) await rm(restoreEvidenceFile, { force: true }).catch(() => {});
      if (statusCreated) await rm(candidateStatusFile, { force: true }).catch(() => {});
    }
    rethrow(error, "postgresql_automation_activate");
  }
}

export async function runOffHostRecoveryCycle(config, options = {}) {
  if ((options.hostname ?? systemHostname()) === config.expectedSourceHostname) fail("postgresql_automation_offhost_required");
  await assertPrivateDirectory(config.localRoot);
  await assertPrivateRegularFile(config.sshIdentityFile);
  await assertPrivateRegularFile(config.recipientPrivateKey);
  await assertProtectedRegularFile(config.knownHostsFile);
  const remote = options.remote ?? createSshRecoveryRemote(config);
  const descriptor = validateDescriptor(await remote.latest(), config);
  const generationRoot = path.join(config.localRoot, descriptor.generationId);
  await mkdir(generationRoot, { mode: 0o700, recursive: true });
  await assertPrivateDirectory(generationRoot);
  const successFile = path.join(generationRoot, "recovery-complete.json");
  if (await exists(successFile)) {
    await assertSafeGenerationFile(successFile);
    const completed = JSON.parse(await readFile(successFile, "utf8"));
    exactKeys(completed, ["schemaVersion", "kind", "generationId", "completedAt", "archiveSha256"]);
    if (completed.schemaVersion !== 1 || completed.kind !== "hermes-go-postgresql-offhost-cycle-v1"
        || completed.generationId !== descriptor.generationId || completed.archiveSha256 !== descriptor.archiveSha256
        || new Date(completed.completedAt).toISOString() !== completed.completedAt) fail("postgresql_automation_completion_marker_invalid");
    return { ok: true, command: "postgresql-offhost-recovery", generationId: descriptor.generationId, alreadyComplete: true };
  }
  const archiveFile = path.join(generationRoot, "postgresql.cms");
  const manifestFile = path.join(generationRoot, "postgresql.manifest.json");
  const evidenceFile = path.join(generationRoot, "postgresql-restore.evidence.json");
  const statusFile = path.join(generationRoot, "postgresql-backup.status.json");
  try {
    if (!await downloadedGenerationMatches(archiveFile, manifestFile, descriptor)) {
      await downloadGenerationAtomically(remote, descriptor, generationRoot, archiveFile, manifestFile);
    }
    const restore = options.restore ?? runDisposablePostgresqlRestore;
    await restore(config, { descriptor, generationRoot, archiveFile, manifestFile, evidenceFile, statusFile }, options);
    await remote.activate(descriptor.generationId, evidenceFile, statusFile);
    await atomicJson(successFile, {
      schemaVersion: 1,
      kind: "hermes-go-postgresql-offhost-cycle-v1",
      generationId: descriptor.generationId,
      completedAt: (options.now?.() ?? new Date()).toISOString(),
      archiveSha256: descriptor.archiveSha256,
    }, 0o600);
    await pruneLocalGenerations(config.localRoot, config.retentionCount, descriptor.generationId);
    return { ok: true, command: "postgresql-offhost-recovery", generationId: descriptor.generationId, alreadyComplete: false };
  } catch (error) {
    await rm(evidenceFile, { force: true }).catch(() => {});
    await rm(statusFile, { force: true }).catch(() => {});
    await rm(successFile, { force: true }).catch(() => {});
    rethrow(error, "postgresql_automation_offhost_cycle");
  }
}

async function downloadedGenerationMatches(archiveFile, manifestFile, descriptor) {
  try {
    const manifest = await loadPostgresqlBackupManifest(manifestFile);
    const archiveInfo = await lstat(archiveFile);
    return manifest.archiveSha256 === descriptor.archiveSha256
      && manifest.archiveBytes === descriptor.archiveBytes
      && archiveInfo.isFile() && !archiveInfo.isSymbolicLink()
      && archiveInfo.size === descriptor.archiveBytes
      && await sha256File(archiveFile) === descriptor.archiveSha256;
  } catch {
    return false;
  }
}

async function downloadGenerationAtomically(remote, descriptor, generationRoot, archiveFile, manifestFile) {
  const nonce = randomBytes(6).toString("hex");
  const archiveTemporary = path.join(generationRoot, `.postgresql.cms.${nonce}.download`);
  const manifestTemporary = path.join(generationRoot, `.postgresql.manifest.${nonce}.download`);
  try {
    await remote.download(descriptor.generationId, "archive", archiveTemporary);
    await remote.download(descriptor.generationId, "manifest", manifestTemporary);
    await chmod(archiveTemporary, 0o600);
    await chmod(manifestTemporary, 0o600);
    if (!await downloadedGenerationMatches(archiveTemporary, manifestTemporary, descriptor)) {
      fail("postgresql_automation_download_identity_mismatch");
    }
    await rename(archiveTemporary, archiveFile);
    await rename(manifestTemporary, manifestFile);
  } finally {
    await rm(archiveTemporary, { force: true }).catch(() => {});
    await rm(manifestTemporary, { force: true }).catch(() => {});
  }
}

export async function runDisposablePostgresqlRestore(config, paths, options = {}) {
  const docker = config.dockerPath;
  const name = `hermes-r5e7-${randomBytes(6).toString("hex")}`;
  const password = randomBytes(32).toString("hex");
  const database = `hermes_restore_${randomBytes(5).toString("hex")}`;
  const role = "hermes_restore";
  const ephemeralRoot = path.join(paths.generationRoot, `.runtime-${randomBytes(6).toString("hex")}`);
  await mkdir(ephemeralRoot, { mode: 0o700 });
  const passwordFile = path.join(ephemeralRoot, "postgres-password");
  await writeFile(passwordFile, `${password}\n`, { mode: 0o600, flag: "wx" });
  let started = false;
  const originalPath = process.env.PATH;
  try {
    const startedResult = spawnSync(docker, [
      "run", "-d", "--rm", "--name", name, "-p", "127.0.0.1::5432",
      "--mount", `type=bind,src=${passwordFile},dst=/run/secrets/postgres-password,readonly`,
      "--env", "POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password",
      "--env", `POSTGRES_USER=${role}`, "--env", `POSTGRES_DB=${database}`,
      config.postgresqlImage,
    ], safeSpawn());
    if (startedResult.status !== 0) fail("postgresql_automation_restore_container_start_failed");
    started = true;
    await waitForPostgresql(docker, name, role, database, options);
    const portResult = spawnSync(docker, ["port", name, "5432/tcp"], safeSpawn());
    const match = /127\.0\.0\.1:(\d+)/.exec(String(portResult.stdout ?? ""));
    if (portResult.status !== 0 || !match) fail("postgresql_automation_restore_port_invalid");
    const port = Number(match[1]);
    const encodedRole = encodeURIComponent(role);
    const encodedPassword = encodeURIComponent(password);
    const encodedDatabase = encodeURIComponent(database);
    const databaseUrlFile = path.join(ephemeralRoot, "database-url");
    const imageDatabaseUrlFile = path.join(ephemeralRoot, "image-database-url");
    await writeFile(databaseUrlFile, `postgresql://${encodedRole}:${encodedPassword}@127.0.0.1:${port}/${encodedDatabase}\n`, { mode: 0o600, flag: "wx" });
    await writeFile(imageDatabaseUrlFile, `postgresql://${encodedRole}:${encodedPassword}@127.0.0.1:${port}/${encodedDatabase}\n`, { mode: 0o600, flag: "wx" });
    const toolsRoot = path.join(ephemeralRoot, "tools");
    await mkdir(toolsRoot, { mode: 0o700 });
    await installContainerToolWrapper(toolsRoot, docker, name, role, database);
    await symlinkExecutable(toolsRoot, "docker", docker);
    await symlinkExecutable(toolsRoot, "openssl", config.opensslPath);
    process.env.PATH = `${toolsRoot}:${originalPath ?? "/usr/bin:/bin"}`;
    await verifyPostgresqlRestore({
      schemaVersion: 2,
      environment: "isolated-restore",
      operator: config.operator,
      expectedSourceHostname: config.expectedSourceHostname,
      archiveFile: paths.archiveFile,
      manifestFile: paths.manifestFile,
      recipientCertificate: config.recipientCertificate,
      recipientPrivateKey: config.recipientPrivateKey,
      databaseUrlFile,
      imageDatabaseUrlFile,
      targetArtifactManifest: config.targetArtifactManifest,
      evidenceFile: paths.evidenceFile,
      statusFile: paths.statusFile,
      offHostStorageId: config.offHostStorageId,
      postgresqlMajorVersion: config.postgresqlMajorVersion,
      databaseSchemaVersion: config.databaseSchemaVersion,
    }, {
      confirmation: `isolated:${config.expectedSourceHostname}`,
      hostname: options.hostname,
      runner: createCommandRunner({ timeoutMs: 120_000 }),
      now: options.now,
    });
  } finally {
    if (originalPath === undefined) delete process.env.PATH;
    else process.env.PATH = originalPath;
    if (started) spawnSync(docker, ["rm", "-f", name], safeSpawn());
    await rm(ephemeralRoot, { recursive: true, force: true }).catch(() => {});
  }
}

export function createSshRecoveryRemote(config, options = {}) {
  const ssh = options.sshPath ?? "/usr/bin/ssh";
  const scp = options.scpPath ?? "/usr/bin/scp";
  const destination = `${config.remoteUser}@${config.remoteHost}`;
  const base = ["-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=yes",
    "-o", `UserKnownHostsFile=${config.knownHostsFile}`, "-i", config.sshIdentityFile];
  const remoteCommand = (...args) => ["sudo", "-n", config.remoteCommandPath, ...args];
  return {
    async latest() {
      const result = spawnSync(ssh, [...base, destination, ...remoteCommand("latest")], safeSpawn());
      if (result.status !== 0) fail("postgresql_automation_remote_latest_failed");
      try { return JSON.parse(String(result.stdout)); } catch { fail("postgresql_automation_remote_latest_invalid"); }
    },
    async download(generationId, part, target) {
      await streamRemoteToFile(ssh, [...base, destination, ...remoteCommand("export", "--generation", generationId, "--part", part)], target);
    },
    async activate(generationId, evidenceFile, statusFile) {
      const tempResult = spawnSync(ssh, [...base, destination, "umask 077; mktemp -d /tmp/hermes-go-r5e7.XXXXXXXX"], safeSpawn());
      const remoteRoot = String(tempResult.stdout ?? "").trim();
      if (tempResult.status !== 0 || !/^\/tmp\/hermes-go-r5e7\.[A-Za-z0-9]{8}$/.test(remoteRoot)) fail("postgresql_automation_remote_stage_failed");
      try {
        for (const [source, name] of [[evidenceFile, "evidence.json"], [statusFile, "status.json"]]) {
          const result = spawnSync(scp, [...base, source, `${destination}:${remoteRoot}/${name}`], safeSpawn());
          if (result.status !== 0) fail("postgresql_automation_remote_upload_failed");
        }
        const activation = remoteCommand(
          "activate", "--generation", generationId,
          "--evidence", `${remoteRoot}/evidence.json`, "--status", `${remoteRoot}/status.json`,
        );
        const result = spawnSync(ssh, [...base, destination, ...activation], safeSpawn());
        if (result.status !== 0) fail("postgresql_automation_remote_activate_failed");
      } finally {
        spawnSync(ssh, [...base, destination, "rm", "-rf", "--", remoteRoot], safeSpawn());
      }
    },
  };
}

async function loadStrictConfig(filePath, expectedKeys, transform) {
  try {
    const safePath = absolutePath(filePath, "config");
    const info = await lstat(safePath);
    if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 || info.size < 2 || info.size > 128 * 1024) {
      fail("postgresql_automation_config_unsafe");
    }
    const raw = JSON.parse(await readFile(safePath, "utf8"));
    exactKeys(raw, expectedKeys);
    return transform(raw);
  } catch (error) {
    rethrow(error, "postgresql_automation_config");
  }
}

function validateDescriptor(raw, config) {
  exactKeys(raw, ["schemaVersion", "kind", "generationId", "sourceHostname", "createdAt", "archiveSha256", "archiveBytes", "postgresqlMajorVersion", "databaseSchemaVersion"]);
  if (raw.schemaVersion !== 1 || raw.kind !== DESCRIPTOR_KIND || !GENERATION.test(raw.generationId)
      || raw.sourceHostname !== (config.sourceHostname ?? config.expectedSourceHostname)
      || new Date(raw.createdAt).toISOString() !== raw.createdAt
      || !/^[0-9a-f]{64}$/.test(raw.archiveSha256)
      || !Number.isSafeInteger(raw.archiveBytes) || raw.archiveBytes < 1
      || raw.archiveBytes > config.maximumEncryptedBytes
      || raw.postgresqlMajorVersion !== config.postgresqlMajorVersion
      || raw.databaseSchemaVersion !== config.databaseSchemaVersion) fail("postgresql_automation_descriptor_invalid");
  return Object.freeze({ ...raw });
}

async function readDescriptor(filePath, config) {
  await assertSafeGenerationFile(filePath);
  return validateDescriptor(JSON.parse(await readFile(filePath, "utf8")), config);
}

function assertProduction(config, options, command, { rootRequired = true } = {}) {
  if (options.confirmation !== `production:${config.sourceHostname}`
      || (options.platform ?? process.platform) !== "linux"
      || (options.hostname ?? systemHostname()) !== config.sourceHostname) fail(`postgresql_automation_${command}_authorization_failed`);
  if (rootRequired && (options.getUid ?? (() => process.getuid?.()))() !== 0) fail(`postgresql_automation_${command}_requires_root`);
}

async function assertPrivateDirectory(directory) {
  await assertNoSymlinkAncestors(directory);
  const info = await lstat(directory);
  if (!info.isDirectory() || info.isSymbolicLink() || (info.mode & 0o077) !== 0) fail("postgresql_automation_directory_unsafe");
}

async function assertSafeGenerationFile(filePath) {
  await assertNoSymlinkAncestors(path.dirname(filePath));
  const info = await lstat(filePath);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 || info.size < 1) fail("postgresql_automation_file_unsafe");
}

async function atomicJson(filePath, value, mode) {
  await assertNoSymlinkAncestors(path.dirname(filePath));
  const temporary = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${randomBytes(6).toString("hex")}.tmp`);
  let committed = false;
  try {
    const handle = await open(temporary, "wx", mode);
    try { await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`); await handle.sync(); } finally { await handle.close(); }
    await rename(temporary, filePath);
    committed = true;
  } finally {
    if (!committed) await rm(temporary, { force: true }).catch(() => {});
  }
}

async function copyOrVerifyPrivate(source, target) {
  if (await exists(target)) {
    await assertSafeGenerationFile(target);
    const [sourceInfo, targetInfo, sourceHash, targetHash] = await Promise.all([
      lstat(source), lstat(target), sha256File(source), sha256File(target),
    ]);
    if (sourceInfo.size !== targetInfo.size || sourceHash !== targetHash) fail("postgresql_automation_activation_input_changed");
    return false;
  }
  await copyFile(source, target, 0);
  await chmod(target, 0o600);
  return true;
}

async function pruneSourceGenerations(root, retentionCount, current, newerGenerationActivated) {
  const generations = (await readdir(root, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory() && GENERATION.test(entry.name)).map((entry) => entry.name).sort().reverse();
  for (const generation of generations.slice(retentionCount)) {
    if (generation === current) continue;
    if (newerGenerationActivated || await exists(path.join(root, generation, "activated.ack.json"))) {
      await rm(path.join(root, generation), { recursive: true, force: true });
    }
  }
}

async function pruneLocalGenerations(root, retentionCount, current) {
  const generations = (await readdir(root, { withFileTypes: true }))
    .filter((entry) => entry.isDirectory() && GENERATION.test(entry.name)).map((entry) => entry.name).sort().reverse();
  for (const generation of generations.slice(retentionCount)) {
    if (generation !== current && await exists(path.join(root, generation, "recovery-complete.json"))) {
      await rm(path.join(root, generation), { recursive: true, force: true });
    }
  }
}

async function pipeFile(source, writable) {
  const input = createReadStream(source);
  try {
    for await (const chunk of input) {
      if (!writable.write(chunk)) await once(writable, "drain");
    }
  } catch (error) {
    input.destroy();
    throw error;
  }
}

async function streamRemoteToFile(command, args, target) {
  if (await exists(target)) fail("postgresql_automation_local_output_exists");
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"], shell: false, env: { PATH: process.env.PATH ?? "/usr/bin:/bin", LANG: "C" } });
    const output = createWriteStream(target, { flags: "wx", mode: 0o600 });
    child.stdout.pipe(output);
    let childDone = false;
    let outputDone = false;
    const finish = () => { if (childDone && outputDone) resolve(); };
    child.once("error", reject);
    child.once("close", (code) => {
      if (code !== 0) reject(new Error("remote_export_failed"));
      else { childDone = true; finish(); }
    });
    output.once("finish", () => { outputDone = true; finish(); });
    output.once("error", reject);
  }).catch(async (error) => { await rm(target, { force: true }).catch(() => {}); throw error; });
}

async function waitForPostgresql(docker, name, role, database, options) {
  const attempts = options.postgresqlReadyAttempts ?? 30;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const result = spawnSync(docker, [
      "exec", name, "pg_isready", "-h", "127.0.0.1", "-p", "5432", "-U", role, "-d", database,
    ], safeSpawn());
    if (result.status === 0) return;
    await new Promise((resolve) => setTimeout(resolve, options.postgresqlReadyDelayMs ?? 1000));
  }
  fail("postgresql_automation_restore_database_not_ready");
}

async function installContainerToolWrapper(root, docker, container, role, database) {
  const wrapper = path.join(root, "postgresql-container-tool");
  const source = createPostgresqlContainerToolWrapperSource({ docker, container, role, database });
  await writeFile(wrapper, source, { mode: 0o700, flag: "wx" });
  await copyFile(wrapper, path.join(root, "psql"));
  await copyFile(wrapper, path.join(root, "pg_restore"));
  await chmod(path.join(root, "psql"), 0o700);
  await chmod(path.join(root, "pg_restore"), 0o700);
}

export function createPostgresqlContainerToolWrapperSource({ docker, container, role, database }, nodePath = process.execPath) {
  if (!path.isAbsolute(nodePath) || /[\u0000-\u0020\u007f]/.test(nodePath)) {
    fail("postgresql_automation_node_path_invalid");
  }
  const dockerEnvironment = safeDockerEnvironment();
  return `#!${nodePath}\nimport { spawnSync } from "node:child_process";\nimport path from "node:path";\nconst tool=path.basename(process.argv[1]);\nconst docker=${JSON.stringify(docker)};\nconst container=${JSON.stringify(container)};\nconst role=${JSON.stringify(role)};\nconst database=${JSON.stringify(database)};\nconst dockerEnvironment=${JSON.stringify(dockerEnvironment)};\nconst command='export PGPASSWORD="$(cat /run/secrets/postgres-password)" PGHOST=127.0.0.1 PGPORT=5432 PGUSER="$1" PGDATABASE="$2"; shift 2; exec "$0" "$@"';\nconst args=["exec","-i",container,"sh","-c",command,tool,role,database,...process.argv.slice(2)];\nconst result=spawnSync(docker,args,{stdio:"inherit",shell:false,env:dockerEnvironment});\nprocess.exit(result.status??1);\n`;
}

async function symlinkExecutable(root, name, source) {
  const { symlink } = await import("node:fs/promises");
  await symlink(source, path.join(root, name));
}

function safeSpawn() {
  return { encoding: "utf8", maxBuffer: 128 * 1024, timeout: 120_000, shell: false,
    stdio: ["ignore", "pipe", "pipe"], env: safeDockerEnvironment() };
}

function safeDockerEnvironment() {
  const env = { PATH: process.env.PATH ?? "/usr/bin:/bin", LANG: "C", HOME: process.env.HOME ?? "/tmp" };
  for (const name of ["DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT"]) {
    if (process.env[name]) env[name] = process.env[name];
  }
  return env;
}

function absoluteExecutable(value, label) {
  const result = absolutePath(value, label);
  if (!/^[A-Za-z0-9._+/@ -]+$/.test(result)) fail(`${label}_invalid`);
  return result;
}

function remotePath(value, label) {
  const result = absolutePath(value, label);
  if (!/^\/(?:[A-Za-z0-9._+@-]+\/)*[A-Za-z0-9._+@-]+$/.test(result)) fail(`${label}_invalid`);
  return result;
}

function absolutePath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/"
      || value.includes("//") || /[\u0000-\u001f\u007f]/.test(value)) fail(`${label}_path_invalid`);
  return value;
}

function hostname(value, label) {
  const result = token(value, HOSTNAME, label);
  if (result.includes("..")) fail(`${label}_invalid`);
  return result;
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) fail(`${label}_invalid`);
  return value;
}

function integer(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) fail(`${label}_invalid`);
  return value;
}

function exactInteger(value, expected, label) {
  if (value !== expected) fail(`${label}_unsupported`);
  return value;
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("postgresql_automation_object_invalid");
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) fail("postgresql_automation_fields_invalid");
}

function assertGeneration(value) {
  if (!GENERATION.test(value)) fail("postgresql_automation_generation_invalid");
}

async function exists(filePath) {
  try { await lstat(filePath); return true; } catch (error) { if (error?.code === "ENOENT") return false; throw error; }
}

async function assertPrivateRegularFile(filePath) {
  await assertNoSymlinkAncestors(path.dirname(filePath));
  const info = await lstat(filePath);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0) fail("postgresql_automation_private_input_unsafe");
}

async function assertProtectedRegularFile(filePath) {
  await assertNoSymlinkAncestors(path.dirname(filePath));
  const info = await lstat(filePath);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o022) !== 0) fail("postgresql_automation_protected_input_unsafe");
}

function fail(cause) {
  throw new OpsError("databaseRecovery", cause, "postgresql_automation");
}

function rethrow(error, stage) {
  if (error instanceof OpsError && error.kind === "databaseRecovery") throw error;
  throw new OpsError("databaseRecovery", error instanceof Error ? error.message : error, stage);
}
