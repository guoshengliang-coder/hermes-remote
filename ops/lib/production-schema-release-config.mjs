import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion", "environment", "operator", "productionReleaseConfig", "database", "backup",
]);

/**
 * R5-F8 private configuration. The release itself comes from `productionReleaseConfig` (the same
 * file R5-F1 uses, whose `targetArtifactManifest` names the schema-changing bundle); this file adds
 * only what a migration needs: the database URL source and migration lock, and the backup gate.
 */
export async function loadProductionSchemaReleaseConfig(filePath) {
  let handle;
  try {
    if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath) fail("schema_release_config_path_invalid");
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 2 || info.size > 32 * 1024 || (info.mode & 0o077) !== 0) {
      fail("schema_release_config_file_unsafe");
    }
    const raw = JSON.parse(await handle.readFile("utf8"));
    exactKeys(raw, TOP_LEVEL_KEYS, "config");
    exactKeys(raw.database, ["urlSource", "ssl", "migrationLockId"], "database");
    exactKeys(raw.backup, ["activeStatusFile", "maximumAgeMinutes"], "backup");
    if (raw.schemaVersion !== 1 || raw.environment !== "production") fail("schema_release_config_invalid");
    if (typeof raw.database.ssl !== "boolean") fail("database_ssl_invalid");
    return Object.freeze({
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      productionReleaseConfig: absolutePath(raw.productionReleaseConfig, "productionReleaseConfig"),
      database: Object.freeze({
        urlSource: absolutePath(raw.database.urlSource, "database.urlSource"),
        ssl: raw.database.ssl,
        migrationLockId: integer(raw.database.migrationLockId, 1, Number.MAX_SAFE_INTEGER, "migrationLockId"),
      }),
      backup: Object.freeze({
        activeStatusFile: absolutePath(raw.backup.activeStatusFile, "backup.activeStatusFile"),
        maximumAgeMinutes: integer(raw.backup.maximumAgeMinutes, 5, 720, "maximumAgeMinutes"),
      }),
    });
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  } finally {
    await handle?.close().catch(() => {});
  }
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label}_invalid`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label}_fields_invalid`);
  }
}

function absolutePath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/"
      || !/^\/[A-Za-z0-9._/-]+$/.test(value) || value.includes("//")) {
    fail(`${label}_path_invalid`);
  }
  return value;
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value) || value.includes("..")) fail(`${label}_invalid`);
  return value;
}

function integer(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) fail(`${label}_invalid`);
  return value;
}

function fail(cause) {
  throw new OpsError("productionSchemaRelease", cause, "production_schema_release_config");
}
