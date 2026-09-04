import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { Pool, type PoolClient } from "pg";

export class AccountRestoreVerificationError extends Error {
  constructor(readonly code: string) {
    super(code);
    this.name = "AccountRestoreVerificationError";
  }
}

export async function verifyAccountRestore({
  env = process.env,
  poolFactory = (options: ConstructorParameters<typeof Pool>[0]) => new Pool(options),
} = {}) {
  const databaseUrl = await secret(env, "ACCOUNT_DATABASE_URL");
  const databaseSsl = booleanFlag(env, "ACCOUNT_DATABASE_SSL", false);
  const expectedSchemaVersion = positiveInteger(env, "ACCOUNT_DATABASE_SCHEMA_VERSION");
  const expectedPostgresqlMajor = positiveInteger(env, "ACCOUNT_DATABASE_POSTGRESQL_MAJOR");
  const pool = poolFactory({
    connectionString: databaseUrl,
    max: 1,
    connectionTimeoutMillis: 3_000,
    ssl: databaseSsl ? { rejectUnauthorized: true } : undefined,
  });
  let client: PoolClient | undefined;
  try {
    client = await pool.connect();
    await client.query("SET statement_timeout = '30s'");
    const version = await client.query("SHOW server_version_num");
    const postgresqlMajor = parsePostgresqlMajor(version.rows[0]?.server_version_num);
    if (postgresqlMajor !== expectedPostgresqlMajor) fail("postgresql_major_mismatch");
    const schema = await client.query("SELECT version FROM gateway_schema_state WHERE singleton = true");
    if (schema.rowCount !== 1 || schema.rows[0]?.version !== expectedSchemaVersion) {
      fail("database_schema_mismatch");
    }
    await smokeAccountTransaction(client);
    return { postgresqlMajor, schemaVersion: expectedSchemaVersion, accountSmoke: "pass" as const };
  } catch (error) {
    if (error instanceof AccountRestoreVerificationError) throw error;
    fail("account_restore_verification_failed");
  } finally {
    client?.release();
    await pool.end();
  }
}

async function smokeAccountTransaction(client: PoolClient) {
  const accountId = randomUUID();
  const identityId = randomUUID();
  const installationId = randomUUID();
  const clientInstallationId = randomUUID();
  await client.query("BEGIN READ WRITE");
  try {
    await client.query("INSERT INTO accounts (id) VALUES ($1)", [accountId]);
    await client.query(
      `INSERT INTO external_identities (id, account_id, provider, issuer, subject)
       VALUES ($1, $2, 'google', 'https://accounts.google.com', $3)`,
      [identityId, accountId, `r5-restore-${identityId}`],
    );
    await client.query(
      `INSERT INTO installations
         (id, account_id, client_installation_id, kind, platform, display_name, app_version)
       VALUES ($1, $2, $3, 'phone', 'android', 'R5 restore smoke', '0.0.0')`,
      [installationId, accountId, clientInstallationId],
    );
    const joined = await client.query(
      `SELECT a.status, i.provider, d.kind, d.platform
         FROM accounts a
         JOIN external_identities i ON i.account_id = a.id
         JOIN installations d ON d.account_id = a.id
        WHERE a.id = $1 AND i.id = $2 AND d.id = $3`,
      [accountId, identityId, installationId],
    );
    if (joined.rowCount !== 1
        || joined.rows[0]?.status !== "active"
        || joined.rows[0]?.provider !== "google"
        || joined.rows[0]?.kind !== "phone"
        || joined.rows[0]?.platform !== "android") {
      fail("account_relational_smoke_failed");
    }
  } finally {
    await client.query("ROLLBACK");
  }
}

async function secret(env: NodeJS.ProcessEnv, name: string) {
  const file = env[`${name}_FILE`];
  const value = env[name] ?? (file ? (await readFile(file, "utf8")).trim() : undefined);
  if (!value) fail("database_url_missing");
  return value;
}

function positiveInteger(env: NodeJS.ProcessEnv, name: string) {
  const value = Number(env[name]);
  if (!Number.isSafeInteger(value) || value <= 0) fail(`${name.toLowerCase()}_invalid`);
  return value;
}

function booleanFlag(env: NodeJS.ProcessEnv, name: string, fallback: boolean) {
  const raw = env[name];
  if (raw === undefined) return fallback;
  if (raw === "1") return true;
  if (raw === "0") return false;
  fail(`${name.toLowerCase()}_invalid`);
}

function parsePostgresqlMajor(value: unknown) {
  const version = Number(value);
  if (!Number.isSafeInteger(version) || version < 10000) fail("postgresql_version_invalid");
  return Math.floor(version / 10000);
}

function fail(code: string): never {
  throw new AccountRestoreVerificationError(code);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const result = await verifyAccountRestore();
    console.log(`ACCOUNT_RESTORE_VERIFY_OK ${JSON.stringify(result)}`);
  } catch (error) {
    const code = error instanceof AccountRestoreVerificationError
      ? error.code
      : "account_restore_verification_failed";
    console.error(`ACCOUNT_RESTORE_VERIFY_ERROR ${code}`);
    process.exitCode = 1;
  }
}
