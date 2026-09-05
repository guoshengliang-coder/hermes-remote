import { lstat, readFile } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const HOSTNAME = /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/;
const TOKEN = /^[A-Za-z0-9._:-]{1,128}$/;

export async function loadPostgresqlBackupConfig(filePath) {
  const raw = await readStrictJson(filePath, "postgresql_backup_config");
  try {
    exactKeys(raw, [
      "schemaVersion", "environment", "operator", "sourceHostname", "serviceName",
      "databaseUrlFile", "recipientCertificate", "archiveFile", "manifestFile",
      "maximumEncryptedBytes", "postgresqlMajorVersion", "databaseSchemaVersion",
    ], "postgresql_backup_config");
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("postgresql_backup_requires_production");
    const config = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      sourceHostname: hostname(raw.sourceHostname, "sourceHostname"),
      serviceName: token(raw.serviceName, /^[a-z0-9][a-z0-9@.-]{0,62}$/, "serviceName"),
      databaseUrlFile: absolutePath(raw.databaseUrlFile, "databaseUrlFile"),
      recipientCertificate: absolutePath(raw.recipientCertificate, "recipientCertificate"),
      archiveFile: absolutePath(raw.archiveFile, "archiveFile"),
      manifestFile: absolutePath(raw.manifestFile, "manifestFile"),
      maximumEncryptedBytes: integer(raw.maximumEncryptedBytes, 1024, 1024 ** 4, "maximumEncryptedBytes"),
      postgresqlMajorVersion: exactInteger(raw.postgresqlMajorVersion, 18, "postgresqlMajorVersion"),
      databaseSchemaVersion: integer(raw.databaseSchemaVersion, 1, 2_147_483_647, "databaseSchemaVersion"),
    };
    distinctPaths(config.databaseUrlFile, config.recipientCertificate, config.archiveFile, config.manifestFile);
    return config;
  } catch (error) {
    wrap(error, "postgresql_backup_config_validate");
  }
}

export async function loadPostgresqlRestoreConfig(filePath) {
  const raw = await readStrictJson(filePath, "postgresql_restore_config");
  try {
    exactKeys(raw, [
      "schemaVersion", "environment", "operator", "expectedSourceHostname", "archiveFile",
      "manifestFile", "recipientCertificate", "recipientPrivateKey", "databaseUrlFile",
      "imageDatabaseUrlFile", "targetArtifactManifest", "evidenceFile", "statusFile",
      "offHostStorageId", "postgresqlMajorVersion", "databaseSchemaVersion",
    ], "postgresql_restore_config");
    if (raw.schemaVersion !== 2 || raw.environment !== "isolated-restore") fail("postgresql_restore_requires_isolated_environment");
    const config = {
      schemaVersion: 2,
      environment: "isolated-restore",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      expectedSourceHostname: hostname(raw.expectedSourceHostname, "expectedSourceHostname"),
      archiveFile: absolutePath(raw.archiveFile, "archiveFile"),
      manifestFile: absolutePath(raw.manifestFile, "manifestFile"),
      recipientCertificate: absolutePath(raw.recipientCertificate, "recipientCertificate"),
      recipientPrivateKey: absolutePath(raw.recipientPrivateKey, "recipientPrivateKey"),
      databaseUrlFile: absolutePath(raw.databaseUrlFile, "databaseUrlFile"),
      imageDatabaseUrlFile: absolutePath(raw.imageDatabaseUrlFile, "imageDatabaseUrlFile"),
      targetArtifactManifest: absolutePath(raw.targetArtifactManifest, "targetArtifactManifest"),
      evidenceFile: absolutePath(raw.evidenceFile, "evidenceFile"),
      statusFile: absolutePath(raw.statusFile, "statusFile"),
      offHostStorageId: token(raw.offHostStorageId, TOKEN, "offHostStorageId"),
      postgresqlMajorVersion: exactInteger(raw.postgresqlMajorVersion, 18, "postgresqlMajorVersion"),
      databaseSchemaVersion: integer(raw.databaseSchemaVersion, 1, 2_147_483_647, "databaseSchemaVersion"),
    };
    distinctPaths(
      config.archiveFile, config.manifestFile, config.recipientCertificate, config.recipientPrivateKey,
      config.databaseUrlFile, config.imageDatabaseUrlFile, config.targetArtifactManifest,
      config.evidenceFile, config.statusFile,
    );
    return config;
  } catch (error) {
    wrap(error, "postgresql_restore_config_validate");
  }
}

export async function loadPostgresqlStatusActivationConfig(filePath) {
  const raw = await readStrictJson(filePath, "postgresql_status_activation_config");
  try {
    exactKeys(raw, [
      "schemaVersion", "environment", "operator", "sourceHostname", "manifestFile",
      "restoreEvidenceFile", "candidateStatusFile", "activeStatusFile",
    ], "postgresql_status_activation_config");
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("postgresql_status_activation_requires_production");
    const config = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      sourceHostname: hostname(raw.sourceHostname, "sourceHostname"),
      manifestFile: absolutePath(raw.manifestFile, "manifestFile"),
      restoreEvidenceFile: absolutePath(raw.restoreEvidenceFile, "restoreEvidenceFile"),
      candidateStatusFile: absolutePath(raw.candidateStatusFile, "candidateStatusFile"),
      activeStatusFile: absolutePath(raw.activeStatusFile, "activeStatusFile"),
    };
    distinctPaths(config.manifestFile, config.restoreEvidenceFile, config.candidateStatusFile, config.activeStatusFile);
    return config;
  } catch (error) {
    wrap(error, "postgresql_status_activation_config_validate");
  }
}

export async function loadPostgresqlBackupManifest(filePath) {
  const raw = await readStrictJson(filePath, "postgresql_backup_manifest");
  try {
    exactKeys(raw, [
      "schemaVersion", "kind", "sourceHostname", "createdAt", "archiveSha256", "archiveBytes",
      "encryption", "postgresqlMajorVersion", "databaseSchemaVersion",
    ], "postgresql_backup_manifest");
    exactKeys(raw.encryption, ["kind", "recipientCertificateSha256"], "postgresql_backup_encryption");
    if (raw.schemaVersion !== 1 || raw.kind !== "hermes-go-postgresql-backup-v1") fail("postgresql_backup_manifest_kind_invalid");
    if (raw.encryption.kind !== "openssl-cms-auth-enveloped-aes-256-gcm") fail("postgresql_backup_encryption_invalid");
    return {
      schemaVersion: 1,
      kind: raw.kind,
      sourceHostname: hostname(raw.sourceHostname, "sourceHostname"),
      createdAt: timestamp(raw.createdAt, "createdAt"),
      archiveSha256: token(raw.archiveSha256, /^[0-9a-f]{64}$/, "archiveSha256"),
      archiveBytes: integer(raw.archiveBytes, 1, 1024 ** 4, "archiveBytes"),
      encryption: {
        kind: raw.encryption.kind,
        recipientCertificateSha256: token(raw.encryption.recipientCertificateSha256, /^[0-9a-f]{64}$/, "recipientCertificateSha256"),
      },
      postgresqlMajorVersion: integer(raw.postgresqlMajorVersion, 1, 99, "postgresqlMajorVersion"),
      databaseSchemaVersion: integer(raw.databaseSchemaVersion, 1, 2_147_483_647, "databaseSchemaVersion"),
    };
  } catch (error) {
    wrap(error, "postgresql_backup_manifest_validate");
  }
}

async function readStrictJson(filePath, label) {
  try {
    if (!path.isAbsolute(filePath)) fail(`${label}_path_invalid`);
    const info = await lstat(filePath);
    if (!info.isFile() || info.isSymbolicLink() || info.size < 2 || info.size > 128 * 1024) fail(`${label}_file_invalid`);
    return JSON.parse(await readFile(filePath, "utf8"));
  } catch (error) {
    wrap(error, `${label}_read`);
  }
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label}_must_be_object`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) fail(`${label}_fields_invalid`);
}

function absolutePath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/") fail(`${label}_path_invalid`);
  if (value.includes("//") || /[\u0000-\u001f\u007f]/.test(value)) fail(`${label}_path_unsafe`);
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

function timestamp(value, label) {
  if (typeof value !== "string" || new Date(value).toISOString() !== value) fail(`${label}_invalid`);
  return value;
}

function distinctPaths(...values) {
  if (new Set(values).size !== values.length) fail("postgresql_recovery_paths_must_be_distinct");
}

function fail(cause) {
  throw new Error(cause);
}

function wrap(error, stage) {
  if (error instanceof OpsError) throw error;
  throw new OpsError("databaseRecovery", error instanceof Error ? error.message : error, stage);
}
