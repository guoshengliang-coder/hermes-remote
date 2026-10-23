import { lstat, open, rm } from "node:fs/promises";
import { hostname } from "node:os";
import path from "node:path";
import { runtimeImageIds } from "./config.mjs";
import { OpsError } from "./errors.mjs";
import { verifyPostgresqlRestore, runAccountSmoke } from "./postgresql-recovery.mjs";
import { assertNoSymlinkAncestors, createCommandRunner } from "./system.mjs";
import { migrationIdentities, SCHEMA_REHEARSAL_KIND, SCHEMA_REHEARSAL_CHECKS } from "./schema-advance-plan.mjs";

// Caller owns the disposable PostgreSQL instance. Never accepts a production database URL;
// verifyPostgresqlRestore checks an empty isolated loopback database and a different hostname.
export async function rehearseSchemaAdvance(config, target, evidenceFile, options = {}) {
  const from = config.databaseSchemaVersion;
  const to = target.releaseContract?.databaseSchemaVersion;
  const restoreHostname = options.hostname ?? hostname();
  if (!Number.isSafeInteger(from) || to !== from + 2 || !/^[0-9a-f]{40}$/.test(target.sourceCommit)
      || !/^[0-9a-f]{64}$/.test(target.archiveSha256)
      || restoreHostname === config.expectedSourceHostname || options.confirmation !== `isolated:${restoreHostname}`) {
    fail("schema_rehearsal_subject_invalid");
  }
  if (!path.isAbsolute(evidenceFile) || path.normalize(evidenceFile) !== evidenceFile) fail("schema_rehearsal_output_invalid");
  await assertNoSymlinkAncestors(path.dirname(evidenceFile));
  try { await lstat(evidenceFile); fail("schema_rehearsal_output_exists"); }
  catch (error) { if (error.code !== "ENOENT") throw error; }
  const migrations = await migrationIdentities(from, to, options.directory);
  const runner = options.runner ?? createCommandRunner();
  const restored = await (options.restore ?? verifyPostgresqlRestore)(config, {
    ...options, runner, confirmation: `isolated:${config.expectedSourceHostname}`,
  });
  if (restored.subject?.databaseSchemaVersion !== from || restored.restoreHostname !== restoreHostname
      || !/^[0-9a-f]{40}$/.test(restored.targetRelease?.sourceCommit)
      || !/^[0-9a-f]{64}$/.test(restored.artifactSha256)) fail("schema_rehearsal_restore_identity_invalid");
  const inspected = runner.run("docker", ["image", "inspect", "--format", "{{.Id}}", target.imageReference], { allowFailure: true });
  const image = inspected.stdout.trim();
  if (inspected.status !== 0 || !runtimeImageIds(target).includes(image)) fail("schema_rehearsal_target_image_mismatch");
  const info = await lstat(config.imageDatabaseUrlFile);
  const mounted = "/run/secrets/hermes-go-rehearsal-url";
  const result = runner.run("docker", [
    "run", "--rm", "--read-only", "--network", "host", "--user", `${info.uid}:${info.gid}`,
    "--cap-drop=ALL", "--security-opt=no-new-privileges", "--memory=512m", "--cpus=1", "--pids-limit=128",
    "--mount", `type=bind,src=${config.imageDatabaseUrlFile},dst=${mounted},readonly`,
    "--env", `ACCOUNT_DATABASE_URL_FILE=${mounted}`, "--env", `ACCOUNT_DATABASE_SCHEMA_VERSION=${to}`,
    "--env", `ACCOUNT_DATABASE_SSL=0`, "--env", "ACCOUNT_DATABASE_MIGRATION_LOCK_ID=48203175",
    "--env", `ACCOUNT_DATABASE_SUPPORTED_MAJORS=${config.postgresqlMajorVersion}`,
    image, "node", "gateway/dist/ops/migrate-account.mjs",
  ], { allowFailure: true, timeout: 120_000 });
  let migrated;
  try {
    if (result.status !== 0 || !result.stdout.startsWith("DATABASE_MIGRATION_OK ")) throw new Error();
    migrated = JSON.parse(result.stdout.slice("DATABASE_MIGRATION_OK ".length));
  } catch { fail("schema_rehearsal_migration_failed"); }
  if (migrated.schemaVersion !== to || migrated.postgresqlMajor !== config.postgresqlMajorVersion
      || JSON.stringify(migrated.appliedMigrations) !== JSON.stringify([from + 1, to])) fail("schema_rehearsal_migration_sequence_mismatch");
  await (options.accountSmoke ?? runAccountSmoke)({ ...config, databaseSchemaVersion: to }, runner, target);
  const evidence = {
    schemaVersion: 1, kind: SCHEMA_REHEARSAL_KIND, sourceHostname: config.expectedSourceHostname, restoreHostname,
    fromSchemaVersion: from, toSchemaVersion: to, sourceCommit: restored.targetRelease.sourceCommit,
    targetSourceCommit: target.sourceCommit, backupArtifactSha256: restored.artifactSha256,
    targetImageId: target.imageId, targetArchiveSha256: target.archiveSha256,
    migrations, verifiedAt: (options.now ?? (() => new Date()))().toISOString(), verifiedChecks: SCHEMA_REHEARSAL_CHECKS,
  };
  const handle = await open(evidenceFile, "wx", 0o600);
  try { await handle.writeFile(`${JSON.stringify(evidence, null, 2)}\n`); await handle.sync(); }
  catch (error) { await rm(evidenceFile, { force: true }); throw error; }
  finally { await handle.close(); }
  return { ok: true, command: "schema-advance-rehearsal", evidence };
}

function fail(cause) { throw new OpsError("productionSchemaRelease", cause, "production_schema_rehearsal"); }
