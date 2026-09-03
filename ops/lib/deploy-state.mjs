import { createHash, randomUUID } from "node:crypto";
import { lstat, open, readFile, unlink } from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { OpsError } from "./errors.mjs";
import { atomicWrite } from "./system.mjs";
import { DEPLOY_SLOTS } from "./deploy-system.mjs";

export const DEPLOYMENT_STAGES = Object.freeze([
  "authorized",
  "artifact_verified",
  "lock_acquired",
  "checkpoint_created",
  "migration_verified",
  "candidate_started",
  "candidate_verified",
  "route_switched",
  "draining",
  "committed",
]);

const JOURNAL_KEYS = Object.freeze([
  "schemaVersion",
  "operation",
  "planDigest",
  "runId",
  "stage",
  "activeSlot",
  "candidateSlot",
  "source",
  "target",
  "checkpoint",
  "startedAt",
  "updatedAt",
]);
const IDENTITY_KEYS = Object.freeze([
  "serverVersion",
  "sourceCommit",
  "imageId",
  "manifestSchemaVersion",
  "databaseSchemaVersion",
]);
const CHECKPOINT_KEYS = Object.freeze([
  "currentReleaseTarget",
  "previousReleaseTarget",
  "nginxConfigSha256",
  "upstreamSha256",
]);

export function deploymentPlanDigest(config, source, target, inputMaterialFingerprint = "") {
  const stable = {
    schemaVersion: config.schemaVersion,
    environment: config.environment,
    operator: config.operator,
    targetArtifactManifest: config.targetArtifactManifest,
    paths: config.paths,
    legacySource: config.legacySource,
    slots: config.slots,
    gateway: config.gateway,
    database: config.database ? {
      ssl: config.database.ssl,
      migrationLockId: config.database.migrationLockId,
    } : null,
    nginx: {
      serverName: config.nginx.serverName,
      listenPort: config.nginx.listenPort,
      configFile: config.nginx.configFile,
      upstreamConfigFile: config.nginx.upstreamConfigFile,
    },
    deployment: config.deployment,
    source,
    target,
    inputMaterialFingerprint,
  };
  return createHash("sha256").update(JSON.stringify(stable)).digest("hex");
}

export function createDeploymentJournal({
  operation,
  planDigest,
  runId,
  activeSlot,
  candidateSlot,
  source,
  target,
  now,
}) {
  const timestamp = canonicalTimestamp(now);
  const journal = {
    schemaVersion: 2,
    operation,
    planDigest,
    runId,
    stage: "authorized",
    activeSlot,
    candidateSlot,
    source,
    target,
    checkpoint: null,
    startedAt: timestamp,
    updatedAt: timestamp,
  };
  validateDeploymentJournal(journal);
  return journal;
}

export function advanceDeploymentJournal(journal, nextStage, now, { checkpoint } = {}) {
  validateDeploymentJournal(journal);
  const currentIndex = DEPLOYMENT_STAGES.indexOf(journal.stage);
  const nextIndex = DEPLOYMENT_STAGES.indexOf(nextStage);
  if (nextIndex < 0 || (nextIndex !== currentIndex && nextIndex !== currentIndex + 1)) {
    fail("deployment_stage_transition_invalid", "deploy_journal_advance");
  }
  if (nextIndex === currentIndex) {
    if (checkpoint !== undefined && JSON.stringify(checkpoint) !== JSON.stringify(journal.checkpoint)) {
      fail("deployment_checkpoint_conflict", "deploy_journal_advance");
    }
    return { ...journal };
  }
  if (nextStage === "checkpoint_created") validateCheckpoint(checkpoint);
  if (checkpoint !== undefined && nextStage !== "checkpoint_created") {
    fail("checkpoint_stage_invalid", "deploy_journal_advance");
  }
  const updated = {
    ...journal,
    stage: nextStage,
    checkpoint: checkpoint ?? journal.checkpoint,
    updatedAt: canonicalTimestamp(now),
  };
  validateDeploymentJournal(updated);
  return updated;
}

export async function readOrCreateDeploymentJournal(filePath, expected, owner) {
  try {
    const existing = await readDeploymentJournal(filePath);
    if (existing.planDigest !== expected.planDigest
        || existing.operation !== expected.operation
        || JSON.stringify(existing.source) !== JSON.stringify(expected.source)
        || JSON.stringify(existing.target) !== JSON.stringify(expected.target)
        || existing.activeSlot !== expected.activeSlot
        || existing.candidateSlot !== expected.candidateSlot) {
      fail("deployment_journal_conflict", "deploy_journal_resume");
    }
    const resumed = { ...existing, runId: expected.runId };
    await writeDeploymentJournal(filePath, resumed, owner);
    return resumed;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") fail("deployment_journal_invalid", "deploy_journal_resume");
  }

  let handle;
  try {
    handle = await open(filePath, "wx", 0o600);
    await handle.writeFile(`${JSON.stringify(expected, null, 2)}\n`, "utf8");
    await handle.sync();
  } catch (error) {
    if (error?.code === "EEXIST") return readOrCreateDeploymentJournal(filePath, expected, owner);
    fail(error instanceof Error ? error.message : error, "deploy_journal_create");
  } finally {
    await handle?.close().catch(() => {});
  }
  if (owner) {
    await import("node:fs/promises").then(async ({ chmod, chown }) => {
      await chmod(filePath, 0o600);
      await chown(filePath, owner.uid, owner.gid);
    });
  }
  return { ...expected };
}

export async function readDeploymentJournal(filePath) {
  const info = await lstat(filePath);
  if (info.isSymbolicLink() || !info.isFile() || (info.mode & 0o077) !== 0 || info.size > 64 * 1024) {
    fail("deployment_journal_unsafe", "deploy_journal_read");
  }
  let journal;
  try {
    journal = JSON.parse(await readFile(filePath, "utf8"));
    validateDeploymentJournal(journal);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error, "deploy_journal_read");
  }
  return journal;
}

export async function writeDeploymentJournal(filePath, journal, owner) {
  validateDeploymentJournal(journal);
  try {
    await atomicWrite(filePath, `${JSON.stringify(journal, null, 2)}\n`, 0o600, owner);
  } catch (error) {
    if (error instanceof OpsError && error.kind === "deployment") throw error;
    fail(error instanceof Error ? error.technicalCause || error.message : error, "deploy_journal_write");
  }
}

export async function archiveCommittedDeploymentJournal(filePath, historyRoot, expectedSource, activeSlot, owner) {
  let journal;
  let originalInfo;
  try {
    originalInfo = await lstat(filePath);
    journal = await readDeploymentJournal(filePath);
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
  if (journal.stage !== "committed") return null;
  if (journal.candidateSlot !== activeSlot || JSON.stringify(journal.target) !== JSON.stringify(expectedSource)) {
    fail("committed_journal_does_not_match_active_release", "deploy_journal_archive");
  }
  const archivePath = path.join(historyRoot, `deploy-state.committed.${journal.runId}.json`);
  try {
    let archiveMissing = false;
    try {
      const existing = await readDeploymentJournal(archivePath);
      if (JSON.stringify(existing) !== JSON.stringify(journal)) {
        fail("committed_journal_archive_conflict", "deploy_journal_archive");
      }
    } catch (error) {
      if (error?.code === "ENOENT") archiveMissing = true;
      else throw error;
    }
    if (archiveMissing) {
      await atomicWrite(archivePath, `${JSON.stringify(journal, null, 2)}\n`, 0o600, owner);
    }
    const archived = await readDeploymentJournal(archivePath);
    if (JSON.stringify(archived) !== JSON.stringify(journal)) {
      fail("committed_journal_archive_mismatch", "deploy_journal_archive");
    }
    const currentInfo = await lstat(filePath);
    if (currentInfo.dev !== originalInfo.dev || currentInfo.ino !== originalInfo.ino) {
      fail("committed_journal_changed", "deploy_journal_archive");
    }
    await unlink(filePath);
    return { journal, archivePath };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error, "deploy_journal_archive");
  }
}

export async function acquireDeploymentLock(filePath, runId, {
  pid = process.pid,
  hostname = systemHostname(),
  nonce = randomUUID(),
  processProbe = (ownerPid) => process.kill(ownerPid, 0),
  attempt = 0,
} = {}) {
  const proposedLock = { schemaVersion: 2, runId, nonce, pid, hostname };
  validateLock(proposedLock);
  let handle;
  try {
    handle = await open(filePath, "wx", 0o600);
    await handle.writeFile(`${JSON.stringify(proposedLock)}\n`, "utf8");
    await handle.sync();
    return { filePath, runId, nonce };
  } catch (error) {
    if (error?.code !== "EEXIST" || attempt > 0) {
      if (error instanceof OpsError) throw error;
      fail(error instanceof Error ? error.message : error, "deploy_lock_acquire");
    }
  } finally {
    await handle?.close().catch(() => {});
  }

  let existing;
  let originalInfo;
  try {
    originalInfo = await lstat(filePath);
    if (originalInfo.isSymbolicLink() || !originalInfo.isFile() || (originalInfo.mode & 0o077) !== 0 || originalInfo.size > 4096) {
      fail("deployment_lock_unsafe", "deploy_lock_acquire");
    }
    existing = JSON.parse(await readFile(filePath, "utf8"));
    validateLock(existing);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("deployment_lock_invalid", "deploy_lock_acquire");
  }
  if (existing.hostname !== hostname) fail("deployment_lock_owner_unknown", "deploy_lock_acquire");
  try {
    processProbe(existing.pid);
    fail("deployment_already_running", "deploy_lock_acquire");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ESRCH") fail("deployment_lock_probe_failed", "deploy_lock_acquire");
  }
  const currentInfo = await lstat(filePath);
  if (currentInfo.dev !== originalInfo.dev || currentInfo.ino !== originalInfo.ino) {
    fail("deployment_lock_changed", "deploy_lock_acquire");
  }
  await unlink(filePath);
  return acquireDeploymentLock(filePath, runId, { pid, hostname, processProbe, attempt: attempt + 1 });
}

export async function releaseDeploymentLock(lock) {
  if (!lock) return;
  let existing;
  let originalInfo;
  try {
    originalInfo = await lstat(lock.filePath);
    if (originalInfo.isSymbolicLink() || !originalInfo.isFile() || (originalInfo.mode & 0o077) !== 0) return;
    existing = JSON.parse(await readFile(lock.filePath, "utf8"));
  } catch {
    return;
  }
  if (existing.runId !== lock.runId || existing.nonce !== lock.nonce) return;
  const currentInfo = await lstat(lock.filePath).catch(() => null);
  if (currentInfo && currentInfo.dev === originalInfo.dev && currentInfo.ino === originalInfo.ino) {
    await unlink(lock.filePath);
  }
}

export function validateDeploymentJournal(value) {
  exactKeys(value, JOURNAL_KEYS, "deployment_journal");
  if (value.schemaVersion !== 2) fail("deployment_journal_schema_invalid", "deploy_journal_validate");
  if (!new Set(["deploy", "rollback"]).has(value.operation)) fail("deployment_operation_invalid", "deploy_journal_validate");
  token(value.planDigest, /^[0-9a-f]{64}$/, "plan_digest");
  token(value.runId, /^[A-Za-z0-9._-]{1,128}$/, "run_id");
  if (!DEPLOYMENT_STAGES.includes(value.stage)) fail("deployment_stage_invalid", "deploy_journal_validate");
  if (value.activeSlot !== null && !DEPLOY_SLOTS.includes(value.activeSlot)) fail("active_slot_invalid", "deploy_journal_validate");
  if (!DEPLOY_SLOTS.includes(value.candidateSlot) || value.candidateSlot === value.activeSlot) {
    fail("candidate_slot_invalid", "deploy_journal_validate");
  }
  validateIdentity(value.source, "source");
  validateIdentity(value.target, "target");
  if (value.checkpoint !== null) validateCheckpoint(value.checkpoint);
  const checkpointIndex = DEPLOYMENT_STAGES.indexOf("checkpoint_created");
  const stageIndex = DEPLOYMENT_STAGES.indexOf(value.stage);
  if ((stageIndex >= checkpointIndex) !== (value.checkpoint !== null)) {
    fail("deployment_checkpoint_stage_mismatch", "deploy_journal_validate");
  }
  canonicalTimestamp(value.startedAt);
  canonicalTimestamp(value.updatedAt);
  if (Date.parse(value.updatedAt) < Date.parse(value.startedAt)) fail("deployment_timestamp_order_invalid", "deploy_journal_validate");
  return value;
}

function validateIdentity(value, label) {
  exactKeys(value, IDENTITY_KEYS, `${label}_identity`);
  token(value.serverVersion, /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/, `${label}_version`);
  token(value.sourceCommit, /^[0-9a-f]{40}$/, `${label}_commit`);
  token(value.imageId, /^sha256:[0-9a-f]{64}$/, `${label}_image`);
  if (![1, 2].includes(value.manifestSchemaVersion)) fail(`${label}_manifest_schema_invalid`, "deploy_journal_validate");
  if (!(value.databaseSchemaVersion === null || (Number.isSafeInteger(value.databaseSchemaVersion) && value.databaseSchemaVersion > 0))) {
    fail(`${label}_database_schema_invalid`, "deploy_journal_validate");
  }
}

function validateCheckpoint(value) {
  exactKeys(value, CHECKPOINT_KEYS, "deployment_checkpoint");
  optionalReleaseTarget(value.currentReleaseTarget, "checkpoint_current");
  optionalReleaseTarget(value.previousReleaseTarget, "checkpoint_previous");
  optionalHash(value.nginxConfigSha256, "checkpoint_nginx");
  optionalHash(value.upstreamSha256, "checkpoint_upstream");
}

function optionalReleaseTarget(value, label) {
  if (value !== null && (typeof value !== "string"
      || !/^releases\/(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-[0-9a-f]{12}$/.test(value))) {
    fail(`${label}_invalid`, "deploy_checkpoint_validate");
  }
}

function optionalHash(value, label) {
  if (value !== null && (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value))) {
    fail(`${label}_invalid`, "deploy_checkpoint_validate");
  }
}

function validateLock(value) {
  exactKeys(value, ["schemaVersion", "runId", "nonce", "pid", "hostname"], "deployment_lock");
  if (value.schemaVersion !== 2) fail("deployment_lock_schema_invalid", "deploy_lock_validate");
  token(value.runId, /^[A-Za-z0-9._-]{1,128}$/, "lock_run_id");
  token(value.nonce, /^[A-Za-z0-9-]{1,128}$/, "lock_nonce");
  token(value.hostname, /^[A-Za-z0-9._-]{1,253}$/, "lock_hostname");
  if (!Number.isSafeInteger(value.pid) || value.pid < 1) fail("deployment_lock_pid_invalid", "deploy_lock_validate");
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label}_must_be_object`, "deploy_state_validate");
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label}_fields_invalid`, "deploy_state_validate");
  }
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) fail(`${label}_invalid`, "deploy_state_validate");
  return value;
}

function canonicalTimestamp(value) {
  const date = value instanceof Date ? value : new Date(value);
  let canonical;
  try {
    canonical = date.toISOString();
  } catch {
    fail("deployment_timestamp_invalid", "deploy_state_validate");
  }
  if (canonical !== (value instanceof Date ? canonical : value)) fail("deployment_timestamp_invalid", "deploy_state_validate");
  return canonical;
}

function fail(cause, stage) {
  throw new OpsError("deployment", cause, stage);
}
