import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { open, readFile } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const MANIFEST_V1_KEYS = Object.freeze([
  "schemaVersion",
  "kind",
  "sourceCommit",
  "createdAt",
  "archiveFile",
  "archiveSha256",
  "entrypoint",
  "connectorEntry",
]);
const MANIFEST_V2_KEYS = Object.freeze([...MANIFEST_V1_KEYS, "smokeRuntimeEntry"]);
// Schema 3 (R5-F1) adds the routine production release entrypoint. Schema 2 bundles remain
// readable because the R5-E automation on the Mac still runs from one; they simply cannot be
// used for a release, which the release runbook checks before upload.
const MANIFEST_V3_KEYS = Object.freeze([...MANIFEST_V2_KEYS, "releaseEntrypoint"]);
// Schema 4 carries the separately confirmed, fail-closed production email-account rollout.
const MANIFEST_V4_KEYS = Object.freeze([...MANIFEST_V3_KEYS, "accountRolloutEntrypoint"]);
// Schema 5 carries the separately confirmed, fail-closed single-Mac binding/Desktop-bootstrap rollout.
const MANIFEST_V5_KEYS = Object.freeze([...MANIFEST_V4_KEYS, "bindingRolloutEntrypoint"]);
const MANIFEST_KEYS_BY_SCHEMA = Object.freeze({
  1: MANIFEST_V1_KEYS,
  2: MANIFEST_V2_KEYS,
  3: MANIFEST_V3_KEYS,
  4: MANIFEST_V4_KEYS,
  5: MANIFEST_V5_KEYS,
});

export async function loadProductionBaselineBundleManifest(filePath, {
  verifyArchive = true,
  allowLegacySchema = false,
} = {}) {
  try {
    const raw = await readStrictJson(filePath);
    const keys = MANIFEST_KEYS_BY_SCHEMA[raw.schemaVersion];
    if (!keys) fail("bundle_contract_invalid");
    exactKeys(raw, keys);
    if (raw.kind !== `hermes-go-production-baseline-bundle-v${raw.schemaVersion}`) fail("bundle_contract_invalid");
    if (raw.schemaVersion === 1 && !allowLegacySchema) fail("bundle_smoke_runtime_required");
    if (!/^[0-9a-f]{40}$/.test(raw.sourceCommit)) fail("bundle_source_commit_invalid");
    if (!isCanonicalTimestamp(raw.createdAt)) fail("bundle_created_at_invalid");
    const sourceShort = raw.sourceCommit.slice(0, 12);
    if (raw.archiveFile !== `Hermes-R5D-Ops-${sourceShort}.tar.gz`) fail("bundle_archive_name_invalid");
    if (!/^[0-9a-f]{64}$/.test(raw.archiveSha256)) fail("bundle_archive_hash_invalid");
    if (raw.entrypoint !== "scripts/production-baseline.mjs"
        || raw.connectorEntry !== "connector/dist/index.js") {
      fail("bundle_entrypoints_invalid");
    }
    if (raw.schemaVersion >= 2 && raw.smokeRuntimeEntry !== "ops/lib/production-smoke-runtime.mjs") {
      fail("bundle_smoke_runtime_entry_invalid");
    }
    if (raw.schemaVersion >= 3 && raw.releaseEntrypoint !== "scripts/production-release.mjs") {
      fail("bundle_release_entrypoint_invalid");
    }
    if (raw.schemaVersion >= 4 && raw.accountRolloutEntrypoint !== "scripts/production-account-rollout.mjs") {
      fail("bundle_account_rollout_entrypoint_invalid");
    }
    if (raw.schemaVersion >= 5 && raw.bindingRolloutEntrypoint !== "scripts/production-binding-rollout.mjs") {
      fail("bundle_binding_rollout_entrypoint_invalid");
    }
    if (verifyArchive) {
      const archivePath = path.join(path.dirname(filePath), raw.archiveFile);
      const archive = await readSafeFile(archivePath, 128 * 1024 * 1024);
      if (sha256(archive) !== raw.archiveSha256) fail("bundle_archive_identity_mismatch");
    }
    return Object.freeze({ ...raw });
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  }
}

export function createProductionBaselineBundleManifest({ sourceCommit, createdAt, archiveFile, archiveSha256 }) {
  return {
    schemaVersion: 5,
    kind: "hermes-go-production-baseline-bundle-v5",
    sourceCommit,
    createdAt,
    archiveFile,
    archiveSha256,
    entrypoint: "scripts/production-baseline.mjs",
    connectorEntry: "connector/dist/index.js",
    smokeRuntimeEntry: "ops/lib/production-smoke-runtime.mjs",
    releaseEntrypoint: "scripts/production-release.mjs",
    accountRolloutEntrypoint: "scripts/production-account-rollout.mjs",
    bindingRolloutEntrypoint: "scripts/production-binding-rollout.mjs",
  };
}

async function readStrictJson(filePath) {
  return JSON.parse((await readSafeFile(filePath, 64 * 1024)).toString("utf8"));
}

async function readSafeFile(filePath, maximumBytes) {
  if (!path.isAbsolute(filePath) || path.normalize(filePath) !== filePath) {
    fail("bundle_path_invalid");
  }
  let handle;
  try {
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size < 2 || info.size > maximumBytes || (info.mode & 0o022) !== 0) {
      fail("bundle_file_unsafe");
    }
    return await handle.readFile();
  } finally {
    await handle?.close().catch(() => {});
  }
}

function exactKeys(value, expected) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("bundle_manifest_invalid");
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail("bundle_manifest_fields_invalid");
  }
}

function isCanonicalTimestamp(value) {
  return typeof value === "string"
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
    && new Date(value).toISOString() === value;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function fail(cause) {
  throw new OpsError("managedBaseline", cause, "production_baseline_bundle_validate");
}
