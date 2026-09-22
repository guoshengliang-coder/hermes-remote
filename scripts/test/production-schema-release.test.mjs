import assert from "node:assert/strict";
import { chmod, mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { OpsError } from "../../ops/lib/errors.mjs";
import {
  executeProductionSchemaRelease,
  probeMigrationState,
  verifyFreshOffHostBackup,
} from "../../ops/lib/production-schema-release.mjs";
import { loadProductionSchemaReleaseConfig } from "../../ops/lib/production-schema-release-config.mjs";

const NOW = new Date("2026-09-22T12:00:00.000Z");
const SHA = "a".repeat(64);

function backupStatus(overrides = {}) {
  return {
    schemaVersion: 1,
    kind: "hermes-go-postgresql-backup-status-v1",
    sourceHostname: "prod-host",
    backupCompletedAt: "2026-09-22T11:30:00.000Z",
    offHostCopiedAt: "2026-09-22T11:40:00.000Z",
    artifactSha256: SHA,
    encryptedBytes: 87632,
    offHostSha256: SHA,
    offHostBytes: 87632,
    postgresqlMajorVersion: 18,
    databaseSchemaVersion: 15,
    offHostStorageId: "mac-recovery-store",
    ...overrides,
  };
}

async function statusFile(t, value, mode = 0o600) {
  const root = await mkdtemp(path.join(await realpath(tmpdir()), "schema-release-test-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const filePath = path.join(root, "latest-status.json");
  await writeFile(filePath, JSON.stringify(value));
  await chmod(filePath, mode);
  return { root, filePath };
}

const gate = { hostname: "prod-host", databaseSchemaVersion: 15, now: () => NOW };

test("R5-F8 backup gate accepts only a fresh off-host copy of the schema being migrated from", async (t) => {
  const ok = await statusFile(t, backupStatus());
  assert.deepEqual(await verifyFreshOffHostBackup({ activeStatusFile: ok.filePath, maximumAgeMinutes: 120 }, gate), {
    backupCompletedAt: "2026-09-22T11:30:00.000Z",
    offHostCopiedAt: "2026-09-22T11:40:00.000Z",
  });
  const cases = [
    [backupStatus({ backupCompletedAt: "2026-09-22T09:00:00.000Z", offHostCopiedAt: "2026-09-22T09:10:00.000Z" }), "schema_release_backup_too_old"],
    [backupStatus({ backupCompletedAt: "2026-09-22T12:30:00.000Z", offHostCopiedAt: "2026-09-22T12:40:00.000Z" }), "schema_release_backup_too_old"],
    [backupStatus({ databaseSchemaVersion: 16 }), "schema_release_backup_schema_mismatch"],
    [backupStatus({ sourceHostname: "other-host" }), "schema_release_backup_status_invalid"],
    [backupStatus({ offHostSha256: "b".repeat(64) }), "schema_release_backup_status_invalid"],
    [backupStatus({ offHostCopiedAt: "2026-09-22T11:00:00.000Z" }), "schema_release_backup_status_invalid"],
    [backupStatus({ kind: "something-else" }), "schema_release_backup_status_invalid"],
  ];
  for (const [value, cause] of cases) {
    const file = await statusFile(t, value);
    await assert.rejects(
      () => verifyFreshOffHostBackup({ activeStatusFile: file.filePath, maximumAgeMinutes: 120 }, gate),
      (error) => error?.technicalCause === cause && error.kind === "productionSchemaRelease",
      cause,
    );
  }
  const writable = await statusFile(t, backupStatus(), 0o666);
  await assert.rejects(
    () => verifyFreshOffHostBackup({ activeStatusFile: writable.filePath, maximumAgeMinutes: 120 }, gate),
    (error) => error?.technicalCause === "schema_release_backup_status_unsafe",
  );
});

function admissionWith(sourceSchema, mode = "email_sharing_components_web") {
  return async (_config, _target, options) => {
    assert.equal(options.schemaMigration, true);
    assert.equal(options.operation, "deploy");
    return {
      operation: "deploy",
      activeSlot: "blue",
      sourceManifest: { serverVersion: "0.4.17", releaseContract: { databaseSchemaVersion: sourceSchema } },
      runtimeEnvironment: { mode },
    };
  };
}

const config = {
  host: { hostname: "prod-host" },
  slots: { blue: { gatewayPort: 18787 }, green: { gatewayPort: 18788 } },
};
const schemaConfig = {
  database: { urlSource: "/secure-input/hermes-go/account-database-url", ssl: false, migrationLockId: 7 },
  backup: { activeStatusFile: "/var/lib/hermes-go-monitor/latest-status.json", maximumAgeMinutes: 120 },
};
const target16 = { serverVersion: "0.4.18", releaseContract: { databaseSchemaVersion: 16 } };

test("R5-F8 admits one schema step behind the backup gate and hands the database to R5-F1", async () => {
  const calls = [];
  const result = await executeProductionSchemaRelease(config, target16, schemaConfig, {
    now: () => NOW,
    verifyAdmission: admissionWith(15),
    verifyBackup: async (backup, facts) => {
      calls.push(["backup", backup, facts.databaseSchemaVersion, facts.hostname]);
    },
    executeRelease: async (_config, target, options) => {
      calls.push(["release", target.serverVersion, options.schemaMigration, options.migrationDatabase, options.operation]);
      return { ok: true, stage: "committed", command: "production-deploy" };
    },
  });
  assert.deepEqual(calls, [
    ["backup", schemaConfig.backup, 15, "prod-host"],
    ["release", "0.4.18", true, schemaConfig.database, "deploy"],
  ]);
  assert.equal(result.command, "production-schema-release");
  assert.deepEqual(result.databaseSchemaVersion, { from: 15, to: 16 });
});

test("R5-F8 refuses anything but one forward step in an account runtime, before touching the backup", async () => {
  for (const [admission, target, cause] of [
    [admissionWith(15), { releaseContract: { databaseSchemaVersion: 17 } }, "schema_release_requires_exactly_one_schema_step"],
    [admissionWith(15), { releaseContract: { databaseSchemaVersion: 15 } }, "schema_release_requires_exactly_one_schema_step"],
    [admissionWith(16), { releaseContract: { databaseSchemaVersion: 15 } }, "schema_release_requires_exactly_one_schema_step"],
    [admissionWith(15, "disabled"), target16, "schema_release_requires_account_runtime"],
    [async () => { throw new OpsError("productionRelease", "production_release_host_mismatch", "x"); }, target16,
      "schema_release_admission_failed:production_release_host_mismatch"],
  ]) {
    await assert.rejects(
      () => executeProductionSchemaRelease(config, target, schemaConfig, {
        verifyAdmission: admission,
        verifyBackup: async () => assert.fail("backup gate must not run"),
        executeRelease: async () => assert.fail("release must not run"),
      }),
      (error) => error?.technicalCause === cause && error.kind === "productionSchemaRelease",
      cause,
    );
  }
});

test("R5-F8 names whether a failed release had already migrated", async () => {
  for (const [state, prefix] of [
    ["migrated", "schema_release_migrated_degraded"],
    ["not_migrated", "schema_release_failed_before_migration"],
    ["unknown", "schema_release_migration_state_unknown"],
  ]) {
    await assert.rejects(
      () => executeProductionSchemaRelease(config, target16, schemaConfig, {
        verifyAdmission: admissionWith(15),
        verifyBackup: async () => {},
        executeRelease: async () => { throw new OpsError("productionRelease", "candidate_smoke_failed", "x"); },
        probeMigrated: async () => state,
      }),
      (error) => error?.technicalCause === `${prefix}:candidate_smoke_failed`,
    );
  }
});

test("R5-F8 reads a schema mismatch on any slot as migrated", async () => {
  const fetchImpl = (states) => async (url) => {
    const port = new URL(url).port;
    const migrations = states[port];
    if (!migrations) throw new Error("connection refused");
    return new Response(JSON.stringify({ status: migrations === "ok" ? "ready" : "not_ready", checks: { migrations } }));
  };
  assert.equal(await probeMigrationState(config, fetchImpl({ 18787: "mismatch" })), "migrated");
  assert.equal(await probeMigrationState(config, fetchImpl({ 18788: "mismatch", 18787: "ok" })), "migrated");
  // Without a readable committed release the active slot cannot be named, so an "ok" proves nothing.
  assert.equal(await probeMigrationState({ ...config, paths: { installRoot: "/nonexistent" } }, fetchImpl({ 18787: "ok" })), "unknown");
  assert.equal(await probeMigrationState(config, fetchImpl({})), "unknown");
});

test("R5-F8 configuration is private, exact and bounded", async (t) => {
  const root = await mkdtemp(path.join(await realpath(tmpdir()), "schema-release-config-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const valid = {
    schemaVersion: 1,
    environment: "production",
    operator: "claude-hg94",
    productionReleaseConfig: "/secure-input/hermes-go/production-release.json",
    database: { urlSource: "/secure-input/hermes-go/account-database-url", ssl: false, migrationLockId: 48203175 },
    backup: { activeStatusFile: "/var/lib/hermes-go-monitor/latest-status.json", maximumAgeMinutes: 120 },
  };
  const write = async (name, value, mode = 0o600) => {
    const filePath = path.join(root, name);
    await writeFile(filePath, JSON.stringify(value));
    await chmod(filePath, mode);
    return filePath;
  };
  const loaded = await loadProductionSchemaReleaseConfig(await write("ok.json", valid));
  assert.equal(loaded.database.migrationLockId, 48203175);
  assert.equal(loaded.backup.maximumAgeMinutes, 120);
  for (const [name, value, mode] of [
    ["open.json", valid, 0o644],
    ["extra.json", { ...valid, extra: true }, 0o600],
    ["age.json", { ...valid, backup: { ...valid.backup, maximumAgeMinutes: 1440 } }, 0o600],
    ["ssl.json", { ...valid, database: { ...valid.database, ssl: "no" } }, 0o600],
    ["relative.json", { ...valid, database: { ...valid.database, urlSource: "account-database-url" } }, 0o600],
  ]) {
    const filePath = await write(name, value, mode);
    await assert.rejects(() => loadProductionSchemaReleaseConfig(filePath), (error) => error?.kind === "productionSchemaRelease", name);
  }
});
