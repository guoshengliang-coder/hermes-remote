import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { assertRegularFile } from "./config.mjs";
import { OpsError } from "./errors.mjs";

const CONFIG_KEYS = ["schemaVersion", "environment", "operator", "host", "backup"];
const STATUS_KEYS = [
  "schemaVersion",
  "kind",
  "sourceHostname",
  "backupCompletedAt",
  "offHostCopiedAt",
  "artifactSha256",
  "encryptedBytes",
  "offHostSha256",
  "offHostBytes",
  "postgresqlMajorVersion",
  "databaseSchemaVersion",
  "offHostStorageId",
];

export async function loadProductionMonitorConfig(filePath) {
  const raw = await readStrictJson(filePath, 64 * 1024, "production_monitor_config", "config");
  try {
    exactKeys(raw, CONFIG_KEYS, "production_monitor_config");
    exactKeys(raw.host, ["hostname", "diskMount", "warningFreeDiskMiB", "criticalFreeDiskMiB"], "host");
    exactKeys(raw.backup, [
      "statusFile",
      "maximumAgeHours",
      "minimumEncryptedBytes",
      "expectedPostgresqlMajorVersion",
      "expectedDatabaseSchemaVersion",
    ], "backup");
    if (raw.schemaVersion !== 1) failConfig("unsupported_production_monitor_schema");
    if (raw.environment !== "production") failConfig("production_monitor_requires_production_environment");

    const config = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      host: {
        hostname: token(raw.host.hostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "host.hostname"),
        diskMount: exactValue(raw.host.diskMount, "/", "host.diskMount"),
        warningFreeDiskMiB: boundedInteger(raw.host.warningFreeDiskMiB, 2048, 1024 * 1024, "host.warningFreeDiskMiB"),
        criticalFreeDiskMiB: boundedInteger(raw.host.criticalFreeDiskMiB, 1024, 1024 * 1024 - 1, "host.criticalFreeDiskMiB"),
      },
      backup: {
        statusFile: absoluteJsonPath(raw.backup.statusFile, "backup.statusFile"),
        maximumAgeHours: boundedInteger(raw.backup.maximumAgeHours, 1, 168, "backup.maximumAgeHours"),
        minimumEncryptedBytes: boundedInteger(
          raw.backup.minimumEncryptedBytes,
          1024,
          1024 * 1024 * 1024 * 1024,
          "backup.minimumEncryptedBytes",
        ),
        expectedPostgresqlMajorVersion: exactValue(
          raw.backup.expectedPostgresqlMajorVersion,
          18,
          "backup.expectedPostgresqlMajorVersion",
        ),
        expectedDatabaseSchemaVersion: boundedInteger(
          raw.backup.expectedDatabaseSchemaVersion,
          1,
          2_147_483_647,
          "backup.expectedDatabaseSchemaVersion",
        ),
      },
    };
    if (config.host.hostname.includes("..")) failConfig("production_monitor_hostname_invalid");
    if (config.host.criticalFreeDiskMiB >= config.host.warningFreeDiskMiB) {
      failConfig("disk_threshold_order_invalid");
    }
    return config;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError(
      "config",
      error instanceof Error ? error.message : error,
      "production_monitor_config_validate",
    );
  }
}

export async function loadPostgresqlBackupStatus(filePath) {
  const raw = await readStrictJson(filePath, 64 * 1024, "postgresql_backup_status", "monitoring");
  try {
    exactKeys(raw, STATUS_KEYS, "postgresql_backup_status");
    if (raw.schemaVersion !== 1 || raw.kind !== "hermes-go-postgresql-backup-status-v1") {
      failMonitoring("postgresql_backup_status_kind_invalid");
    }
    const sourceHostname = token(
      raw.sourceHostname,
      /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/,
      "sourceHostname",
    );
    if (sourceHostname.includes("..")) failMonitoring("postgresql_backup_status_hostname_invalid");
    return {
      schemaVersion: 1,
      kind: "hermes-go-postgresql-backup-status-v1",
      sourceHostname,
      backupCompletedAt: canonicalTimestamp(raw.backupCompletedAt, "backupCompletedAt"),
      offHostCopiedAt: canonicalTimestamp(raw.offHostCopiedAt, "offHostCopiedAt"),
      artifactSha256: token(raw.artifactSha256, /^[0-9a-f]{64}$/, "artifactSha256"),
      encryptedBytes: boundedInteger(raw.encryptedBytes, 1, 1024 * 1024 * 1024 * 1024, "encryptedBytes"),
      offHostSha256: token(raw.offHostSha256, /^[0-9a-f]{64}$/, "offHostSha256"),
      offHostBytes: boundedInteger(raw.offHostBytes, 1, 1024 * 1024 * 1024 * 1024, "offHostBytes"),
      postgresqlMajorVersion: boundedInteger(raw.postgresqlMajorVersion, 1, 99, "postgresqlMajorVersion"),
      databaseSchemaVersion: boundedInteger(raw.databaseSchemaVersion, 1, 2_147_483_647, "databaseSchemaVersion"),
      offHostStorageId: token(raw.offHostStorageId, /^[A-Za-z0-9._:-]{1,128}$/, "offHostStorageId"),
    };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError(
      "monitoring",
      error instanceof Error ? error.message : error,
      "postgresql_backup_status_validate",
    );
  }
}

async function readStrictJson(filePath, maximumBytes, label, errorKind) {
  let handle;
  try {
    if (typeof filePath !== "string" || !path.isAbsolute(filePath)) throw new Error(`${label}_path_invalid`);
    await assertRegularFile(filePath, label);
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || (info.mode & 0o022) !== 0) throw new Error(`${label}_file_unsafe`);
    if (info.size < 2 || info.size > maximumBytes) throw new Error(`${label}_size_invalid`);
    const text = await handle.readFile("utf8");
    if (text.includes("\u0000")) throw new Error(`${label}_contains_nul`);
    return JSON.parse(text);
  } catch (error) {
    if (error instanceof OpsError && error.kind === errorKind) throw error;
    throw new OpsError(
      errorKind,
      error instanceof Error ? error.message : error,
      `${label}_read`,
    );
  } finally {
    await handle?.close();
  }
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label}_must_be_object`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error(`${label}_fields_invalid`);
  }
}

function absoluteJsonPath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/") {
    throw new Error(`${label}_path_invalid`);
  }
  if (!/^\/[A-Za-z0-9._/-]+\.json$/.test(value) || value.includes("//")) {
    throw new Error(`${label}_path_unsafe`);
  }
  return value;
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) throw new Error(`${label}_invalid`);
  return value;
}

function boundedInteger(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${label}_invalid`);
  return value;
}

function exactValue(value, expected, label) {
  if (value !== expected) throw new Error(`${label}_invalid`);
  return value;
}

function canonicalTimestamp(value, label) {
  if (typeof value !== "string" || !value.endsWith("Z")) throw new Error(`${label}_invalid`);
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== value) throw new Error(`${label}_invalid`);
  return value;
}

function failConfig(cause) {
  throw new OpsError("config", cause, "production_monitor_config_validate");
}

function failMonitoring(cause) {
  throw new OpsError("monitoring", cause, "postgresql_backup_status_validate");
}
