import path from "node:path";
import { OpsError } from "./errors.mjs";

const RESULT_PREFIX = "DATABASE_MIGRATION_OK ";
const CONTAINER_DATABASE_URL = "/run/hermes-go/database/account-database-url";

export function verifyDatabaseMigration(config, manifest, runner) {
  if (config.database === null) {
    return { required: false, schemaVersion: null, postgresqlMajor: null, appliedMigrations: [] };
  }
  const contract = manifest.releaseContract;
  if (!contract) fail("database_release_contract_missing");
  const result = runner.run("docker", migrationArguments(config, manifest), {
    allowFailure: true,
    timeout: 120_000,
  });
  if (result.status !== 0) fail("database_migration_container_failed");
  const marker = result.stdout
    .split(/\r?\n/)
    .find((line) => line.startsWith(RESULT_PREFIX));
  if (!marker) fail("database_migration_result_missing");
  let value;
  try {
    value = JSON.parse(marker.slice(RESULT_PREFIX.length));
  } catch {
    fail("database_migration_result_invalid");
  }
  const resultKeys = value && typeof value === "object" && !Array.isArray(value)
    ? Object.keys(value).sort()
    : [];
  if (!value || JSON.stringify(resultKeys) !== JSON.stringify(["appliedMigrations", "postgresqlMajor", "schemaVersion"])
      || value.schemaVersion !== contract.databaseSchemaVersion
      || !contract.supportedPostgresqlMajors.includes(value.postgresqlMajor)
      || !Array.isArray(value.appliedMigrations)
      || value.appliedMigrations.some((entry, index) => !Number.isSafeInteger(entry)
        || entry <= 0 || entry > value.schemaVersion
        || (index > 0 && entry <= value.appliedMigrations[index - 1]))) {
    fail("database_migration_result_invalid");
  }
  return { required: true, ...value };
}

export function migrationArguments(config, manifest) {
  if (config.database === null || !manifest.releaseContract) fail("database_configuration_missing");
  if (!config.paths?.configRoot) fail("database_secret_root_missing");
  const databaseUrlPath = path.join(config.paths.configRoot, "database-secrets", "account-database-url");
  return [
    "run",
    "--rm",
    "--read-only",
    "--network",
    "host",
    "--cap-drop=ALL",
    "--security-opt=no-new-privileges",
    "--memory=256m",
    "--cpus=1",
    "--pids-limit=128",
    "--mount",
    `type=bind,src=${databaseUrlPath},dst=${CONTAINER_DATABASE_URL},readonly`,
    "--env",
    `ACCOUNT_DATABASE_URL_FILE=${CONTAINER_DATABASE_URL}`,
    "--env",
    `ACCOUNT_DATABASE_SSL=${config.database.ssl ? "1" : "0"}`,
    "--env",
    `ACCOUNT_DATABASE_MIGRATION_LOCK_ID=${config.database.migrationLockId}`,
    "--env",
    `ACCOUNT_DATABASE_SCHEMA_VERSION=${manifest.releaseContract.databaseSchemaVersion}`,
    "--env",
    `ACCOUNT_DATABASE_SUPPORTED_MAJORS=${manifest.releaseContract.supportedPostgresqlMajors.join(",")}`,
    manifest.imageId,
    "node",
    "gateway/dist/ops/migrate-account.mjs",
  ];
}

function fail(cause) {
  throw new OpsError("database", cause, "database_migration_verify");
}
