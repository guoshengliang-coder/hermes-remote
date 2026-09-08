import { lstat, readFile } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const NAME = /^[a-z][a-z0-9_]{0,62}$/;

export async function loadPostgresqlProvisionConfig(filePath) {
  try {
    if (!path.isAbsolute(filePath)) fail("postgresql_provision_config_path_invalid");
    const info = await lstat(filePath);
    if (!info.isFile() || info.isSymbolicLink() || info.size < 2 || info.size > 32 * 1024) {
      fail("postgresql_provision_config_file_invalid");
    }
    const raw = JSON.parse(await readFile(filePath, "utf8"));
    exactKeys(raw, ["schemaVersion", "environment", "operator", "hostname", "serviceName", "databaseName", "roleName", "passwordFile", "databaseUrlFile", "postgresqlMajorVersion", "postgresqlPort"]);
    if (raw.schemaVersion !== 1 || raw.environment !== "production" || raw.postgresqlMajorVersion !== 18 || raw.postgresqlPort !== 5432) {
      fail("postgresql_provision_contract_invalid");
    }
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(raw.operator)
        || !/^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(raw.hostname)
        || !/^[a-z0-9][a-z0-9@.-]{0,62}$/.test(raw.serviceName)
        || !NAME.test(raw.databaseName) || !NAME.test(raw.roleName)
        || raw.databaseName === raw.roleName) fail("postgresql_provision_value_invalid");
    for (const value of [raw.passwordFile, raw.databaseUrlFile]) {
      if (!path.isAbsolute(value) || path.normalize(value) !== value || value === "/" || value.includes("//")) {
        fail("postgresql_provision_path_invalid");
      }
    }
    if (raw.passwordFile === raw.databaseUrlFile) fail("postgresql_provision_paths_must_be_distinct");
    return raw;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  }
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("postgresql_provision_config_must_be_object");
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail("postgresql_provision_config_fields_invalid");
  }
}

function fail(cause) {
  throw new OpsError("databaseProvision", cause, "postgresql_provision_config_validate");
}
