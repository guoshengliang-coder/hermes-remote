import { randomUUID } from "node:crypto";
import { lstat, readlink } from "node:fs/promises";
import path from "node:path";
import { loadBundleManifest, manifestIdentity } from "./config.mjs";
import { prepareCandidate } from "./deploy.mjs";
import { readDeploymentJournal } from "./deploy-state.mjs";
import { switchCandidate } from "./deploy-switch.mjs";
import { createOpsError, OpsError } from "./errors.mjs";
import { appendOpsAudit } from "./hermesctl.mjs";
import { ensureManagedDirectory } from "./system.mjs";

const RELEASE_TARGET = /^releases\/(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-[0-9a-f]{12}$/;

export async function executeDeployment(config, targetManifest, options = {}) {
  const operation = options.operation;
  if (!new Set(["deploy", "rollback"]).has(operation)) {
    throw new OpsError("config", "deploy_or_rollback_operation_required", "deploy_command_authorize");
  }
  authorizeCommand(config, options);
  const sourceManifest = await loadCurrentManifest(config);
  const activeSlot = await resolveActiveSlot(config, sourceManifest);
  const prepare = options.prepareCandidate ?? prepareCandidate;
  const switchTraffic = options.switchCandidate ?? switchCandidate;
  const now = options.now ?? (() => new Date());
  const runId = options.runId ?? randomUUID();
  const ownership = options.ownership ?? {
    host: { uid: 0, gid: 0 },
    container: { uid: 1000, gid: 1000 },
    secret: { uid: 0, gid: 1000 },
  };
  const shared = {
    operation,
    confirmation: options.confirmation,
    candidateSmoke: options.candidateSmoke,
    publicSmoke: options.publicSmoke,
    activeSlot,
    runId,
    ...(options.runner ? { runner: options.runner } : {}),
    ...(options.platform ? { platform: options.platform } : {}),
    ...(options.architecture ? { architecture: options.architecture } : {}),
    ...(options.getUid ? { getUid: options.getUid } : {}),
    ownership,
    ...(options.fetchImpl ? { fetchImpl: options.fetchImpl } : {}),
    ...(options.sleep ? { sleep: options.sleep } : {}),
    now,
  };
  const startedAt = now().toISOString();
  const opsRoot = path.join(config.paths.stateRoot, "ops");
  const auditPath = path.join(opsRoot, "operations.jsonl");
  await ensureManagedDirectory(config.paths.stateRoot, 0o750, ownership.host);
  await ensureManagedDirectory(opsRoot, 0o700, ownership.host);
  await appendOpsAudit(auditPath, auditRecord({
    config, targetManifest, operation, runId, startedAt, stage: "authorized", result: "started",
  }), { kind: "deployment", stage: "deploy_command_audit" });

  try {
    const prepared = await prepare(config, sourceManifest, targetManifest, shared);
    const switched = await switchTraffic(config, sourceManifest, targetManifest, shared);
    await appendOpsAudit(auditPath, auditRecord({
      config,
      targetManifest,
      operation,
      runId,
      startedAt,
      finishedAt: now().toISOString(),
      stage: "committed",
      result: "success",
    }), { kind: "deployment", stage: "deploy_command_audit" });
    return {
      ...switched,
      command: operation,
      preparedStage: prepared.stage,
    };
  } catch (error) {
    await appendOpsAudit(auditPath, auditRecord({
      config,
      targetManifest,
      operation,
      runId,
      startedAt,
      finishedAt: now().toISOString(),
      stage: "failed",
      result: "failed",
      errorCode: error instanceof OpsError
        ? createOpsError(error.kind, error.technicalCause, error.stage).code
        : "HR-OPS-007",
    }), { kind: "deployment", stage: "deploy_command_audit" }).catch(() => {});
    throw error;
  }
}

export async function loadCurrentManifest(config) {
  const currentTarget = await readReleaseTarget(config.paths.installRoot, "current");
  return loadBundleManifest(
    path.join(config.paths.installRoot, currentTarget, "bundle.manifest.json"),
    { verifyArchive: false },
  );
}

export async function resolveActiveSlot(config, sourceManifest) {
  const journalPath = path.join(config.paths.stateRoot, "ops", "deploy-state.json");
  try {
    const journal = await readDeploymentJournal(journalPath);
    if (journal.stage !== "committed"
        || journal.candidateSlot === null
        || JSON.stringify(journal.target) !== JSON.stringify(releaseIdentity(sourceManifest))) {
      throw new OpsError("deployment", "committed_journal_does_not_match_current_release", "deploy_command_source");
    }
    return journal.candidateSlot;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
  if (sourceManifest.schemaVersion !== 1) {
    throw new OpsError("deployment", "current_r4_release_requires_committed_journal", "deploy_command_source");
  }
  return null;
}

async function readReleaseTarget(installRoot, name) {
  const linkPath = path.join(installRoot, name);
  try {
    const info = await lstat(linkPath);
    if (!info.isSymbolicLink()) throw new Error("not_symlink");
    const target = await readlink(linkPath);
    if (!RELEASE_TARGET.test(target)) throw new Error("target_invalid");
    return target;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError("deployment", `${name}_release_invalid`, "deploy_command_source");
  }
}

function releaseIdentity(manifest) {
  const identity = manifestIdentity(manifest);
  return {
    serverVersion: identity.serverVersion,
    sourceCommit: identity.sourceCommit,
    imageId: identity.imageId,
    manifestSchemaVersion: identity.schemaVersion,
    databaseSchemaVersion: identity.releaseContract?.databaseSchemaVersion ?? null,
  };
}

function auditRecord({
  config,
  targetManifest,
  operation,
  runId,
  startedAt,
  finishedAt = null,
  stage,
  result,
  errorCode = null,
}) {
  return {
    runId,
    operation,
    operator: config.operator,
    environment: config.environment,
    serverVersion: targetManifest.serverVersion,
    sourceCommit: targetManifest.sourceCommit,
    imageId: targetManifest.imageId,
    startedAt,
    finishedAt,
    stage,
    result,
    errorCode,
  };
}

function authorizeCommand(config, options) {
  if (options.confirmation !== "staging" || config.environment !== "staging") {
    throw new OpsError("config", "staging_confirmation_required", "deploy_command_authorize");
  }
  if ((options.getUid ?? (() => process.getuid?.()))() !== 0) {
    throw new OpsError("config", "deployment_requires_root", "deploy_command_authorize");
  }
  if ((options.platform ?? process.platform) !== "linux" || (options.architecture ?? process.arch) !== "x64") {
    throw new OpsError("config", "unsupported_deployment_host", "deploy_command_authorize");
  }
  if (typeof options.candidateSmoke !== "function" || typeof options.publicSmoke !== "function") {
    throw new OpsError("config", "private_and_public_smoke_required", "deploy_command_authorize");
  }
}
