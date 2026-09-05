import assert from "node:assert/strict";
import test from "node:test";
import { chmod, mkdtemp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { loadPostgresqlProvisionConfig } from "../../ops/lib/postgresql-provision-config.mjs";
import { databaseUrl, provisionPostgresql } from "../../ops/lib/postgresql-provision.mjs";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";

test("R5-E2 creates a least-privilege database and installs only a 0600 URL", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  const result = await provisionPostgresql(fixture.config, {
    confirmation: "production:prod-host", hostname: "prod-host", platform: "linux", getUid: () => 0,
    admin: healthyAdmin(calls),
  });
  assert.deepEqual(result, { ok: true, changed: true, databaseName: "hermes_go_account", roleName: "hermes_go_gateway", postgresqlMajorVersion: 18 });
  assert.deepEqual(calls.map((entry) => entry[0]), ["inspect", "createRole", "createDatabase", "verify"]);
  assert.equal((await stat(fixture.config.databaseUrlFile)).mode & 0o777, 0o600);
  const url = await readFile(fixture.config.databaseUrlFile, "utf8");
  assert.equal(url.trim(), databaseUrl(fixture.config, fixture.password));
  assert.equal(JSON.stringify(result).includes(fixture.password), false);
});

test("R5-E2 fails closed on partial state before mutation", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  const admin = healthyAdmin(calls, { roleExists: true });
  await assert.rejects(() => provisionPostgresql(fixture.config, {
    confirmation: "production:prod-host", hostname: "prod-host", platform: "linux", getUid: () => 0, admin,
  }), (error) => error?.kind === "databaseProvision" && error.technicalCause === "postgresql_provision_partial_state");
  assert.deepEqual(calls.map((entry) => entry[0]), ["inspect"]);
});

test("R5-E2 removes the new database and role when verification fails", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  const admin = healthyAdmin(calls, { leastPrivilegeRole: false });
  await assert.rejects(() => provisionPostgresql(fixture.config, {
    confirmation: "production:prod-host", hostname: "prod-host", platform: "linux", getUid: () => 0, admin,
  }), (error) => error?.kind === "databaseProvision");
  assert.deepEqual(calls.map((entry) => entry[0]), ["inspect", "createRole", "createDatabase", "verify", "dropDatabase", "dropRole"]);
  await assert.rejects(() => stat(fixture.config.databaseUrlFile));
});

test("R5-E2 reports failed cleanup and never exposes a dependency secret", async (t) => {
  const fixture = await createFixture(t);
  const calls = [];
  const admin = healthyAdmin(calls);
  admin.createDatabase = async () => { throw new Error(`password=${fixture.password}`); };
  admin.dropRole = async () => { throw new Error("cleanup failed"); };
  await assert.rejects(() => provisionPostgresql(fixture.config, {
    confirmation: "production:prod-host", hostname: "prod-host", platform: "linux", getUid: () => 0, admin,
  }), (error) => error?.stage === "postgresql_provision_cleanup" && !error.technicalCause.includes(fixture.password));
});

test("R5-E2 requires exact production identity, PG18, loopback and disabled statement logging", async (t) => {
  const fixture = await createFixture(t);
  for (const override of [{ postgresqlMajor: 17 }, { postgresqlPort: 5433 }, { loopbackOnly: false }, { statementLoggingDisabled: false }, { auditLoggingDisabled: false }, { serviceActive: false }]) {
    await assert.rejects(() => provisionPostgresql(fixture.config, {
      confirmation: "production:prod-host", hostname: "prod-host", platform: "linux", getUid: () => 0,
      admin: healthyAdmin([], override),
    }), (error) => error?.kind === "databaseProvision" && error.stage === "postgresql_provision_preflight");
  }
  await assert.rejects(() => provisionPostgresql(fixture.config, {
    confirmation: "production:other-host", hostname: "prod-host", platform: "linux", getUid: () => 0,
    admin: healthyAdmin([]),
  }), (error) => error?.stage === "postgresql_provision_admission");
});

test("R5-E2 config, schema, example and bilingual error contract stay strict", async (t) => {
  const fixture = await createFixture(t);
  const loaded = await loadPostgresqlProvisionConfig(fixture.configFile);
  assert.equal(loaded.databaseName, "hermes_go_account");
  await writeFile(fixture.configFile, JSON.stringify({ ...fixture.config, unknown: true }), { mode: 0o600 });
  await assert.rejects(() => loadPostgresqlProvisionConfig(fixture.configFile), (error) => error?.kind === "databaseProvision");
  const schema = JSON.parse(await readFile("ops/hermesctl-postgresql-provision-config.schema.json", "utf8"));
  const example = JSON.parse(await readFile("ops/postgresql.provision.example.json", "utf8"));
  assert.equal(schema.additionalProperties, false);
  assert.equal(example.postgresqlMajorVersion, 18);
  const implementation = await readFile("ops/lib/postgresql-provision.mjs", "utf8");
  assert.match(implementation, /SET log_min_error_statement = 'panic'/);
  assert.match(implementation, /shared_preload_libraries/);
  const definition = OPS_ERROR_DEFINITIONS.databaseProvision;
  assert.equal(definition.code, "HR-OPS-015");
  assert.match(definition.summaryZh, /数据库初始化/);
  assert.match(definition.summaryEn, /initialization/);
});

async function createFixture(t) {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-r5e2-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const inputs = path.join(root, "inputs");
  await mkdir(inputs, { mode: 0o700 });
  const password = "R5e2-safe-random-password-0123456789";
  const passwordFile = path.join(inputs, "password");
  const databaseUrlFile = path.join(inputs, "database-url");
  await writeFile(passwordFile, `${password}\n`, { mode: 0o600 });
  await chmod(passwordFile, 0o600);
  const config = { schemaVersion: 1, environment: "production", operator: "codex", hostname: "prod-host", serviceName: "postgresql@18-main", databaseName: "hermes_go_account", roleName: "hermes_go_gateway", passwordFile, databaseUrlFile, postgresqlMajorVersion: 18, postgresqlPort: 5432 };
  const configFile = path.join(root, "config.json");
  await writeFile(configFile, JSON.stringify(config), { mode: 0o600 });
  return { root, inputs, password, config, configFile };
}

function healthyAdmin(calls, overrides = {}) {
  const facts = { serviceActive: true, postgresqlMajor: 18, postgresqlPort: 5432, loopbackOnly: true, statementLoggingDisabled: true, auditLoggingDisabled: true, databaseExists: false, roleExists: false, databaseOwnedByRole: true, leastPrivilegeRole: true, ...overrides };
  return {
    async inspect(config) { calls.push(["inspect", config]); return facts; },
    async createRole(role, password) { calls.push(["createRole", role, password]); },
    async createDatabase(database, role) { calls.push(["createDatabase", database, role]); },
    async verify(config) { calls.push(["verify", config]); return facts; },
    async dropDatabase(database) { calls.push(["dropDatabase", database]); },
    async dropRole(role) { calls.push(["dropRole", role]); },
  };
}
