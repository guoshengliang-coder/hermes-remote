import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { Pool } from "pg";

const migrationsDirectory = fileURLToPath(new URL("../migrations/", import.meta.url));

export class AccountMigrationError extends Error {
  constructor(code) {
    super(code);
    this.name = "AccountMigrationError";
    this.code = code;
  }
}

export async function migrateAccountDatabase({
  env = process.env,
  directory = migrationsDirectory,
  poolFactory = (options) => new Pool(options),
} = {}) {
  const databaseUrl = await secret(env, "ACCOUNT_DATABASE_URL");
  const databaseSsl = booleanFlag(env, "ACCOUNT_DATABASE_SSL", false);
  const lockId = positiveInteger(env, "ACCOUNT_DATABASE_MIGRATION_LOCK_ID");
  const expectedSchemaVersion = positiveInteger(env, "ACCOUNT_DATABASE_SCHEMA_VERSION");
  const supportedPostgresqlMajors = integerList(env, "ACCOUNT_DATABASE_SUPPORTED_MAJORS");
  const migrationFiles = (await readdir(directory))
    .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
    .sort();
  validateMigrationFiles(migrationFiles, expectedSchemaVersion);

  const pool = poolFactory({
    connectionString: databaseUrl,
    max: 1,
    connectionTimeoutMillis: 3_000,
    ssl: databaseSsl ? { rejectUnauthorized: true } : undefined,
  });
  let client;
  let lockHeld = false;
  let clientDestroyed = false;
  try {
    client = await pool.connect();
    await client.query("SET statement_timeout = '60s'");
    await client.query("SET lock_timeout = '5s'");
    const versionResult = await client.query("SHOW server_version_num");
    const postgresqlMajor = parsePostgresqlMajor(versionResult.rows[0]?.server_version_num);
    if (!supportedPostgresqlMajors.includes(postgresqlMajor)) fail("postgresql_major_unsupported");

    const lockResult = await client.query(
      "SELECT pg_try_advisory_lock($1::bigint) AS acquired",
      [String(lockId)],
    );
    if (lockResult.rows[0]?.acquired !== true) fail("migration_lock_unavailable");
    lockHeld = true;

    const currentSchemaVersion = await readSchemaVersion(client);
    if (currentSchemaVersion > expectedSchemaVersion) fail("database_schema_newer_than_release");
    const appliedMigrations = [];
    for (const migrationFile of migrationFiles.slice(currentSchemaVersion)) {
      const migration = await readFile(join(directory, migrationFile), "utf8");
      await client.query(migration);
      appliedMigrations.push(Number(migrationFile.slice(0, 3)));
    }
    const verifiedSchemaVersion = await readSchemaVersion(client);
    if (verifiedSchemaVersion !== expectedSchemaVersion) fail("database_schema_verification_failed");
    return { schemaVersion: verifiedSchemaVersion, postgresqlMajor, appliedMigrations };
  } catch (error) {
    if (error instanceof AccountMigrationError) throw error;
    fail("database_migration_failed");
  } finally {
    if (lockHeld && client) {
      const unlocked = await client.query(
        "SELECT pg_advisory_unlock($1::bigint) AS released",
        [String(lockId)],
      ).catch(() => null);
      if (unlocked?.rows[0]?.released !== true) {
        client.release?.(true);
        clientDestroyed = true;
      }
    }
    if (!clientDestroyed) client?.release?.();
    await pool.end();
  }
}

async function readSchemaVersion(client) {
  const relation = await client.query("SELECT to_regclass('gateway_schema_state')::text AS relation");
  if (relation.rows[0]?.relation == null) return 0;
  const result = await client.query("SELECT version FROM gateway_schema_state WHERE singleton = true");
  if (result.rowCount !== 1 || !Number.isSafeInteger(result.rows[0]?.version) || result.rows[0].version <= 0) {
    fail("database_schema_state_invalid");
  }
  return result.rows[0].version;
}

function validateMigrationFiles(files, expectedSchemaVersion) {
  if (files.length !== expectedSchemaVersion) fail("migration_set_does_not_match_release");
  for (let index = 0; index < files.length; index += 1) {
    if (Number(files[index].slice(0, 3)) !== index + 1) fail("migration_sequence_invalid");
  }
}

function parsePostgresqlMajor(value) {
  const version = Number(value);
  if (!Number.isSafeInteger(version) || version < 10000) fail("postgresql_version_invalid");
  return Math.floor(version / 10000);
}

async function secret(env, name) {
  const file = env[`${name}_FILE`];
  const value = env[name] ?? (file ? (await readFile(file, "utf8")).trim() : undefined);
  if (!value) fail("database_url_missing");
  return value;
}

function booleanFlag(env, name, fallback) {
  const raw = env[name];
  if (raw === undefined) return fallback;
  if (raw === "1") return true;
  if (raw === "0") return false;
  fail("database_ssl_flag_invalid");
}

function positiveInteger(env, name) {
  const value = Number(env[name]);
  if (!Number.isSafeInteger(value) || value <= 0) fail(`${name.toLowerCase()}_invalid`);
  return value;
}

function integerList(env, name) {
  const values = String(env[name] ?? "").split(",").map((value) => Number(value));
  if (values.length === 0 || values.some((value) => !Number.isSafeInteger(value) || value <= 0)) {
    fail("supported_postgresql_majors_invalid");
  }
  return [...new Set(values)];
}

function fail(code) {
  throw new AccountMigrationError(code);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = await migrateAccountDatabase();
    console.log(`DATABASE_MIGRATION_OK ${JSON.stringify(result)}`);
  } catch (error) {
    const code = error instanceof AccountMigrationError ? error.code : "database_migration_failed";
    console.error(`DATABASE_MIGRATION_ERROR ${code}`);
    process.exitCode = 1;
  }
}
