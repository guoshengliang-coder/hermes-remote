import { constants } from "node:fs";
import { lstat, open } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";
import { loadCurrentManifest, resolveActiveSlot } from "./deploy-command.mjs";
import { executeProductionRelease, verifyProductionReleaseAdmission } from "./production-release.mjs";
import { createCommandRunner } from "./system.mjs";

const BACKUP_STATUS_KIND = "hermes-go-postgresql-backup-status-v1";

/**
 * R5-F8: a production release whose Gateway needs the next database schema.
 *
 * The routine release (R5-F1) refuses a schema change in any account mode. This entrypoint is the
 * dedicated path docs/DEPLOYMENT.md requires instead: it admits exactly one forward step, only
 * when the published backup status proves a fresh encrypted backup of the current schema that was
 * copied off-host and restore-verified, and then drives the same R5-F1 machine with the database
 * enabled, so the target image's migrator runs under the deployment lock after the checkpoint and
 * before the candidate starts. The migration is forward-only: once it ran, the older image reports
 * `mismatch` readiness, so a failed switch leaves it serving degraded and the fix is a newer release.
 */
export async function executeProductionSchemaRelease(config, targetManifest, schemaConfig, input = {}) {
  // Admission is called directly here, so supply the defaults R5-F1's own entry would add first.
  const options = { ...input, runner: input.runner ?? createCommandRunner(), now: input.now ?? (() => new Date()) };
  const now = options.now;
  const fetchImpl = options.fetchImpl ?? fetch;
  let admission;
  try {
    admission = await (options.verifyAdmission ?? verifyProductionReleaseAdmission)(config, targetManifest, {
      ...options,
      operation: "deploy",
      schemaMigration: true,
    });
  } catch (error) {
    fail(`schema_release_admission_failed:${technical(error)}`, "production_schema_release_admission");
  }
  if (admission.runtimeEnvironment.mode === "disabled") {
    fail("schema_release_requires_account_runtime", "production_schema_release_admission");
  }
  const sourceSchema = admission.sourceManifest.releaseContract?.databaseSchemaVersion;
  const targetSchema = targetManifest.releaseContract?.databaseSchemaVersion;
  if (!Number.isSafeInteger(sourceSchema) || targetSchema !== sourceSchema + 1) {
    fail("schema_release_requires_exactly_one_schema_step", "production_schema_release_admission");
  }
  await (options.verifyBackup ?? verifyFreshOffHostBackup)(schemaConfig.backup, {
    hostname: config.host.hostname,
    databaseSchemaVersion: sourceSchema,
    now,
  });

  try {
    return {
      ...await (options.executeRelease ?? executeProductionRelease)(config, targetManifest, {
        ...options,
        operation: "deploy",
        schemaMigration: true,
        migrationDatabase: schemaConfig.database,
      }),
      command: "production-schema-release",
      databaseSchemaVersion: { from: sourceSchema, to: targetSchema },
    };
  } catch (error) {
    // Whether the migration ran decides what the operator does next, and the older image's own
    // readiness is the one read-only witness: `mismatch` means the database is already ahead.
    const state = await (options.probeMigrated ?? probeMigrationState)(config, fetchImpl);
    const prefix = {
      migrated: "schema_release_migrated_degraded",
      not_migrated: "schema_release_failed_before_migration",
    }[state] ?? "schema_release_migration_state_unknown";
    fail(`${prefix}:${technical(error)}`, "production_schema_release_execute");
  }
}

/**
 * The monitor's active backup status (published only after an off-host restore verification) must
 * describe the schema being migrated from, on this host, and be no older than the configured bound.
 */
export async function verifyFreshOffHostBackup(backup, { hostname, databaseSchemaVersion, now }) {
  let status;
  try {
    status = JSON.parse((await readProtectedFile(backup.activeStatusFile, 64 * 1024)).toString("utf8"));
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("schema_release_backup_status_unreadable", "production_schema_release_backup");
  }
  const completedAt = Date.parse(status?.backupCompletedAt ?? "");
  const copiedAt = Date.parse(status?.offHostCopiedAt ?? "");
  if (status?.schemaVersion !== 1
      || status.kind !== BACKUP_STATUS_KIND
      || status.sourceHostname !== hostname
      || status.postgresqlMajorVersion !== 18
      || !Number.isFinite(completedAt)
      || !Number.isFinite(copiedAt)
      || copiedAt < completedAt
      || !/^[0-9a-f]{64}$/.test(status.offHostSha256 ?? "")
      || status.offHostSha256 !== status.artifactSha256) {
    fail("schema_release_backup_status_invalid", "production_schema_release_backup");
  }
  if (status.databaseSchemaVersion !== databaseSchemaVersion) {
    fail("schema_release_backup_schema_mismatch", "production_schema_release_backup");
  }
  const ageMs = now().getTime() - completedAt;
  if (ageMs < 0 || ageMs > backup.maximumAgeMinutes * 60 * 1000) {
    fail("schema_release_backup_too_old", "production_schema_release_backup");
  }
  return { backupCompletedAt: status.backupCompletedAt, offHostCopiedAt: status.offHostCopiedAt };
}

/**
 * "migrated" when any slot's image reports a schema `mismatch` (the database is ahead of it);
 * "not_migrated" when the committed active slot (still the source release) reports `ok`; anything
 * else is "unknown" and the operator must look before retrying.
 */
export async function probeMigrationState(config, fetchImpl) {
  const readiness = async (slot) => {
    try {
      const response = await fetchImpl(`http://127.0.0.1:${config.slots[slot].gatewayPort}/readyz`, {
        signal: AbortSignal.timeout(3_000),
      });
      return (await response.json())?.checks?.migrations ?? null;
    } catch {
      return null;
    }
  };
  const states = {};
  for (const slot of Object.keys(config.slots ?? {})) states[slot] = await readiness(slot);
  if (Object.values(states).includes("mismatch")) return "migrated";
  try {
    const active = await resolveActiveSlot(config, await loadCurrentManifest(config));
    if (states[active] === "ok") return "not_migrated";
  } catch {}
  return "unknown";
}

async function readProtectedFile(filePath, maximumBytes) {
  if (typeof filePath !== "string" || !path.isAbsolute(filePath) || path.normalize(filePath) !== filePath) {
    fail("schema_release_file_path_invalid", "production_schema_release_backup");
  }
  let current = path.parse(filePath).root;
  for (const part of path.dirname(filePath).split(path.sep).filter(Boolean)) {
    current = path.join(current, part);
    const info = await lstat(current);
    if (info.isSymbolicLink() || !info.isDirectory()) {
      fail("schema_release_path_ancestor_unsafe", "production_schema_release_backup");
    }
  }
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 1 || info.size > maximumBytes || (info.mode & 0o022) !== 0) {
      fail("schema_release_backup_status_unsafe", "production_schema_release_backup");
    }
    return await handle.readFile();
  } finally {
    await handle?.close().catch(() => {});
  }
}

function technical(error) {
  return error?.technicalCause ?? (error instanceof Error ? error.message : String(error));
}

function fail(cause, stage) {
  throw new OpsError("productionSchemaRelease", cause, stage);
}
