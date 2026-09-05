import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { Pool } from "pg";
import {
  AccountMigrationError,
  migrateAccountDatabase,
} from "../../gateway/scripts/migrate-account.mjs";
import {
  migrationArguments,
  verifyDatabaseMigration,
} from "../../ops/lib/database-migration.mjs";
import { createOpsError, OpsError } from "../../ops/lib/errors.mjs";

const migrationEnvironment = Object.freeze({
  ACCOUNT_DATABASE_URL: "postgresql://migration_user:migration_password@127.0.0.1/hermes_migration",
  ACCOUNT_DATABASE_SSL: "0",
  ACCOUNT_DATABASE_MIGRATION_LOCK_ID: "741852",
  ACCOUNT_DATABASE_SCHEMA_VERSION: "7",
  ACCOUNT_DATABASE_SUPPORTED_MAJORS: "18",
});

test("account migrator holds one PostgreSQL session lock and verifies the exact schema", async () => {
  const fake = fakeDatabase();
  const result = await migrateAccountDatabase({ env: migrationEnvironment, poolFactory: fake.poolFactory });

  assert.deepEqual(result, {
    schemaVersion: 7,
    postgresqlMajor: 18,
    appliedMigrations: [1, 2, 3, 4, 5, 6, 7],
  });
  assert.equal(fake.queries.filter(({ sql }) => sql.includes("pg_try_advisory_lock")).length, 1);
  assert.equal(fake.queries.filter(({ sql }) => sql.includes("pg_advisory_unlock")).length, 1);
  assert.equal(fake.released, 1);
  assert.equal(fake.ended, 1);
});

test("account migrator rejects lock contention before applying SQL", async () => {
  const fake = fakeDatabase({ lockAcquired: false });
  await assert.rejects(
    () => migrateAccountDatabase({ env: migrationEnvironment, poolFactory: fake.poolFactory }),
    migrationCode("migration_lock_unavailable"),
  );
  assert.equal(fake.queries.some(({ sql }) => sql.startsWith("BEGIN;")), false);
  assert.equal(fake.ended, 1);
});

test("account migrator rejects a database newer than its release without mutation", async () => {
  const fake = fakeDatabase({ schemaVersion: 8 });
  await assert.rejects(
    () => migrateAccountDatabase({ env: migrationEnvironment, poolFactory: fake.poolFactory }),
    migrationCode("database_schema_newer_than_release"),
  );
  assert.equal(fake.queries.some(({ sql }) => sql.startsWith("BEGIN;")), false);
  assert.equal(fake.queries.filter(({ sql }) => sql.includes("pg_advisory_unlock")).length, 1);
});

test("account migrator releases its advisory lock after an interrupted migration", async () => {
  const fake = fakeDatabase({ failMigration: 3 });
  await assert.rejects(
    () => migrateAccountDatabase({ env: migrationEnvironment, poolFactory: fake.poolFactory }),
    migrationCode("database_migration_failed"),
  );
  assert.equal(fake.queries.filter(({ sql }) => sql.includes("pg_advisory_unlock")).length, 1);
  assert.equal(fake.released, 1);
  assert.equal(fake.ended, 1);
});

test("Cloud Ops runs the versioned migration from the target image without exposing the URL", () => {
  const config = databaseDeployConfig();
  const manifest = databaseManifest();
  const calls = [];
  const runner = {
    run(command, args, options) {
      calls.push({ command, args, options });
      return {
        status: 0,
        stdout: `DATABASE_MIGRATION_OK ${JSON.stringify({
          schemaVersion: 7,
          postgresqlMajor: 18,
          appliedMigrations: [],
        })}\n`,
        stderr: "",
      };
    },
  };
  const result = verifyDatabaseMigration(config, manifest, runner);
  const args = migrationArguments(config, manifest);

  assert.equal(result.required, true);
  assert.equal(calls[0].command, "docker");
  assert.deepEqual(calls[0].args, args);
  assert.equal(args.includes(manifest.imageId), true);
  assert.equal(args.some((argument) => argument.includes("/etc/hermes-go/database-secrets/account-database-url")), true);
  assert.equal(args.some((argument) => argument.includes("migration_password")), false);
  assert.equal(args.includes("--network"), true);
  assert.equal(args.includes("host"), true);
});

test("Cloud Ops migration pins the manifest-bound Docker 29 containerd image ID", () => {
  const config = databaseDeployConfig();
  const manifest = {
    ...databaseManifest(),
    schemaVersion: 3,
    containerdImageId: `sha256:${"e".repeat(64)}`,
  };
  const runner = {
    run: (_command, args) => ({
      status: 0,
      stdout: `DATABASE_MIGRATION_OK ${JSON.stringify({
        schemaVersion: 7,
        postgresqlMajor: 18,
        appliedMigrations: [],
      })}\n`,
      stderr: "",
      args,
    }),
  };
  const result = verifyDatabaseMigration(config, manifest, runner, manifest.containerdImageId);
  const args = migrationArguments(config, manifest, manifest.containerdImageId);
  assert.equal(result.required, true);
  assert.equal(args.includes(manifest.containerdImageId), true);
  assert.equal(args.includes(manifest.imageId), false);
  assert.throws(
    () => migrationArguments(config, manifest, `sha256:${"f".repeat(64)}`),
    (error) => error instanceof OpsError && createOpsError(error.kind, error.technicalCause, error.stage).code === "HR-OPS-009",
  );
});

test("Cloud Ops maps migration failures to a redacted stable error", () => {
  const secret = "postgresql://user:do-not-leak@127.0.0.1/hermes";
  const runner = {
    run: () => ({ status: 1, stdout: "", stderr: `connection failed ${secret}` }),
  };
  assert.throws(
    () => verifyDatabaseMigration(databaseDeployConfig(), databaseManifest(), runner),
    (error) => error instanceof OpsError
      && createOpsError(error.kind, error.technicalCause, error.stage).code === "HR-OPS-009"
      && !error.technicalCause.includes(secret),
  );
});

test("Cloud Ops rejects ambiguous migration result fields", () => {
  const runner = {
    run: () => ({
      status: 0,
      stdout: `DATABASE_MIGRATION_OK ${JSON.stringify({
        schemaVersion: 7,
        postgresqlMajor: 18,
        appliedMigrations: [],
        unexpected: true,
      })}\n`,
      stderr: "",
    }),
  };
  assert.throws(
    () => verifyDatabaseMigration(databaseDeployConfig(), databaseManifest(), runner),
    (error) => error instanceof OpsError
      && createOpsError(error.kind, error.technicalCause, error.stage).code === "HR-OPS-009",
  );
});

test("account migrations are restart-safe and lock-exclusive on disposable PostgreSQL", {
  skip: process.env.ACCOUNT_TEST_DATABASE_URL ? false : "set ACCOUNT_TEST_DATABASE_URL to disposable PostgreSQL",
}, async (t) => {
  const admin = new Pool({ connectionString: process.env.ACCOUNT_TEST_DATABASE_URL, max: 2 });
  const schema = `migration_${randomUUID().replaceAll("-", "")}`;
  const lockId = String(800_000_000 + Math.floor(Math.random() * 100_000_000));
  await admin.query(`CREATE SCHEMA "${schema}"`);
  t.after(async () => {
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  });
  const url = new URL(process.env.ACCOUNT_TEST_DATABASE_URL);
  url.searchParams.set("options", `-csearch_path=${schema}`);
  const env = {
    ...migrationEnvironment,
    ACCOUNT_DATABASE_URL: url.toString(),
    ACCOUNT_DATABASE_MIGRATION_LOCK_ID: lockId,
  };

  const first = await migrateAccountDatabase({ env });
  const resumed = await migrateAccountDatabase({ env });
  assert.deepEqual(first.appliedMigrations, [1, 2, 3, 4, 5, 6, 7]);
  assert.deepEqual(resumed.appliedMigrations, []);

  const lockClient = await admin.connect();
  try {
    assert.equal((await lockClient.query(
      "SELECT pg_try_advisory_lock($1::bigint) AS acquired",
      [lockId],
    )).rows[0].acquired, true);
    await assert.rejects(() => migrateAccountDatabase({ env }), migrationCode("migration_lock_unavailable"));
  } finally {
    await lockClient.query("SELECT pg_advisory_unlock($1::bigint)", [lockId]);
    lockClient.release();
  }
});

function fakeDatabase({ lockAcquired = true, schemaVersion = 0, failMigration = null } = {}) {
  const queries = [];
  let currentSchemaVersion = schemaVersion;
  let migrationCount = 0;
  const state = { released: 0, ended: 0 };
  const client = {
    async query(sql, parameters = []) {
      queries.push({ sql, parameters });
      if (sql === "SHOW server_version_num") return { rows: [{ server_version_num: "180006" }] };
      if (sql.includes("pg_try_advisory_lock")) return { rows: [{ acquired: lockAcquired }] };
      if (sql.includes("pg_advisory_unlock")) return { rows: [{ released: true }] };
      if (sql.includes("to_regclass")) {
        return { rows: [{ relation: currentSchemaVersion === 0 ? null : "gateway_schema_state" }] };
      }
      if (sql === "SELECT version FROM gateway_schema_state WHERE singleton = true") {
        return { rowCount: 1, rows: [{ version: currentSchemaVersion }] };
      }
      if (sql.startsWith("BEGIN;")) {
        migrationCount += 1;
        if (migrationCount === failMigration) throw new Error("injected database interruption");
        if (sql.includes("VALUES (true, 7)")) currentSchemaVersion = 7;
      }
      return { rows: [] };
    },
    release() { state.released += 1; },
  };
  return {
    queries,
    get released() { return state.released; },
    get ended() { return state.ended; },
    poolFactory: () => ({
      connect: async () => client,
      end: async () => { state.ended += 1; },
    }),
  };
}

function databaseDeployConfig() {
  return {
    paths: { configRoot: "/etc/hermes-go" },
    database: {
      urlSource: "/secure-input/hermes-go/account-database-url",
      ssl: false,
      migrationLockId: 741852,
    },
  };
}

function databaseManifest() {
  return {
    imageId: `sha256:${"d".repeat(64)}`,
    releaseContract: {
      databaseSchemaVersion: 7,
      supportedPostgresqlMajors: [18],
    },
  };
}

function migrationCode(code) {
  return (error) => error instanceof AccountMigrationError && error.code === code;
}
