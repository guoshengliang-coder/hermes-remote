import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { open, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { OpsError } from "./errors.mjs";
import { assertNoSymlinkAncestors } from "./system.mjs";

export const SCHEMA_REHEARSAL_KIND = "hermes-go-schema-advance-rehearsal-v1";
export const SCHEMA_REHEARSAL_CHECKS = Object.freeze(["source_restore", "contiguous_migrations", "target_account_smoke"]);
const MIGRATIONS = fileURLToPath(new URL("../../gateway/migrations/", import.meta.url));
const HASH = /^[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;

// An explicit, bounded two-step plan is the only addition to R5-F8's one-step default.
export function validateSchemaAdvancePlan(value) {
  exact(value, ["fromSchemaVersion", "toSchemaVersion", "sourceCommit", "targetSourceCommit", "targetImageId", "targetArchiveSha256", "migrations", "rehearsalEvidenceFile"]);
  if (!Number.isSafeInteger(value.fromSchemaVersion) || value.fromSchemaVersion < 1
      || value.toSchemaVersion !== value.fromSchemaVersion + 2
      || !COMMIT.test(value.sourceCommit) || !COMMIT.test(value.targetSourceCommit)
      || !/^sha256:[0-9a-f]{64}$/.test(value.targetImageId) || !HASH.test(value.targetArchiveSha256)
      || typeof value.rehearsalEvidenceFile !== "string"
      || !path.isAbsolute(value.rehearsalEvidenceFile)
      || path.normalize(value.rehearsalEvidenceFile) !== value.rehearsalEvidenceFile
      || !/^\/[A-Za-z0-9._/-]+$/.test(value.rehearsalEvidenceFile)) fail("schema_advance_plan_invalid");
  validateMigrations(value.migrations, value.fromSchemaVersion, value.toSchemaVersion);
  return Object.freeze({ ...value, migrations: Object.freeze(value.migrations.map((item) => Object.freeze({ fileName: item.fileName, sha256: item.sha256 }))) });
}

export function matchesSchemaAdvance(plan, source, target) {
  return plan?.fromSchemaVersion === source.releaseContract?.databaseSchemaVersion
    && plan?.toSchemaVersion === target.releaseContract?.databaseSchemaVersion
    && plan.toSchemaVersion === plan.fromSchemaVersion + 2
    && COMMIT.test(plan.sourceCommit) && plan.sourceCommit === source.sourceCommit
    && COMMIT.test(plan.targetSourceCommit) && plan.targetSourceCommit === target.sourceCommit
    && plan.targetImageId === target.imageId && HASH.test(plan.targetArchiveSha256) && plan.targetArchiveSha256 === target.archiveSha256
    && HASH.test(plan.rehearsalDigest ?? "");
}

export async function migrationIdentities(from, to, directory = MIGRATIONS) {
  const result = [];
  for (let version = from + 1; version <= to; version += 1) {
    const fileName = `${String(version).padStart(3, "0")}_gateway_schema_state.sql`;
    const bytes = await readFile(path.join(directory, fileName));
    result.push({ fileName, sha256: digest(bytes) });
  }
  return result;
}

export async function verifySchemaAdvancePlan(value, { source, target, hostname, backup, maximumAgeMinutes, now, directory }) {
  const plan = validateSchemaAdvancePlan(value);
  if (plan.fromSchemaVersion !== source.releaseContract?.databaseSchemaVersion
      || plan.toSchemaVersion !== target.releaseContract?.databaseSchemaVersion
      || plan.sourceCommit !== source.sourceCommit || plan.targetSourceCommit !== target.sourceCommit
      || plan.targetImageId !== target.imageId || plan.targetArchiveSha256 !== target.archiveSha256) {
    fail("schema_advance_release_identity_mismatch");
  }
  const actual = await migrationIdentities(plan.fromSchemaVersion, plan.toSchemaVersion, directory);
  if (JSON.stringify(actual) !== JSON.stringify(plan.migrations)) fail("schema_advance_migration_hash_mismatch");
  await assertNoSymlinkAncestors(path.dirname(plan.rehearsalEvidenceFile));
  const handle = await open(plan.rehearsalEvidenceFile, constants.O_RDONLY | constants.O_NOFOLLOW);
  let bytes;
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size < 2 || info.size > 16 * 1024 || (info.mode & 0o077) !== 0) {
      fail("schema_advance_rehearsal_file_unsafe");
    }
    bytes = await handle.readFile();
  } finally { await handle.close(); }
  let evidence;
  try { evidence = JSON.parse(bytes); } catch { fail("schema_advance_rehearsal_invalid"); }
  exact(evidence, ["schemaVersion", "kind", "sourceHostname", "restoreHostname", "fromSchemaVersion", "toSchemaVersion",
    "sourceCommit", "targetSourceCommit", "targetImageId", "targetArchiveSha256", "backupArtifactSha256", "migrations", "verifiedAt", "verifiedChecks"]);
  const verifiedAt = Date.parse(evidence.verifiedAt);
  const age = now().getTime() - verifiedAt;
  if (evidence.schemaVersion !== 1 || evidence.kind !== SCHEMA_REHEARSAL_KIND
      || evidence.sourceHostname !== hostname || typeof evidence.restoreHostname !== "string"
      || !/^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(evidence.restoreHostname)
      || evidence.restoreHostname === hostname
      || evidence.fromSchemaVersion !== plan.fromSchemaVersion || evidence.toSchemaVersion !== plan.toSchemaVersion
      || evidence.sourceCommit !== plan.sourceCommit || evidence.targetSourceCommit !== plan.targetSourceCommit
      || evidence.targetImageId !== plan.targetImageId || evidence.targetArchiveSha256 !== plan.targetArchiveSha256
      || !HASH.test(evidence.backupArtifactSha256 ?? "") || evidence.backupArtifactSha256 !== backup.artifactSha256
      || JSON.stringify(evidence.migrations) !== JSON.stringify(actual)
      || JSON.stringify(evidence.verifiedChecks) !== JSON.stringify(SCHEMA_REHEARSAL_CHECKS)
      || !Number.isFinite(verifiedAt) || age < 0 || age > maximumAgeMinutes * 60_000
      || verifiedAt < Date.parse(backup.backupCompletedAt)) fail("schema_advance_rehearsal_mismatch_or_stale");
  return Object.freeze({ ...plan, rehearsalDigest: digest(bytes) });
}

function validateMigrations(items, from, to) {
  if (!Array.isArray(items) || items.length !== to - from) fail("schema_advance_migrations_invalid");
  items.forEach((item, index) => {
    exact(item, ["fileName", "sha256"]);
    if (item.fileName !== `${String(from + index + 1).padStart(3, "0")}_gateway_schema_state.sql`
        || !HASH.test(item.sha256)) fail("schema_advance_migrations_invalid");
  });
}

function exact(value, keys) {
  if (!value || typeof value !== "object" || Array.isArray(value)
      || JSON.stringify(Object.keys(value).sort()) !== JSON.stringify([...keys].sort())) fail("schema_advance_fields_invalid");
}
function digest(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function fail(cause) { throw new OpsError("productionSchemaRelease", cause, "production_schema_advance"); }
