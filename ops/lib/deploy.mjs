import { createHash, randomUUID } from "node:crypto";
import { lstat, readFile, readlink } from "node:fs/promises";
import path from "node:path";
import { manifestIdentity } from "./config.mjs";
import {
  acquireDeploymentLock,
  advanceDeploymentJournal,
  archiveCommittedDeploymentJournal,
  createDeploymentJournal,
  DEPLOYMENT_STAGES,
  deploymentPlanDigest,
  readOrCreateDeploymentJournal,
  releaseDeploymentLock,
  writeDeploymentJournal,
} from "./deploy-state.mjs";
import {
  otherSlot,
  renderDeployGatewayEnvironment,
  renderDeploySystemdUnit,
} from "./deploy-system.mjs";
import { verifyDatabaseMigration } from "./database-migration.mjs";
import { OpsError } from "./errors.mjs";
import {
  inspectInputMaterial,
  inspectLoadedImage,
  verifyArchiveAtUse,
  verifyLoadedImage,
} from "./hermesctl.mjs";
import { assessReleaseTransition } from "./release-transition.mjs";
import {
  atomicWrite,
  createCommandRunner,
  ensureManagedDirectory,
} from "./system.mjs";

const CANDIDATE_COMMANDS = Object.freeze(["docker", "systemctl", "ss"]);

export async function prepareCandidate(config, sourceManifest, targetManifest, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 90_000 });
  const platform = options.platform ?? process.platform;
  const architecture = options.architecture ?? process.arch;
  const getUid = options.getUid ?? (() => process.getuid?.());
  const now = options.now ?? (() => new Date());
  const runId = options.runId ?? randomUUID();
  const operation = options.operation ?? "deploy";
  const activeSlot = options.activeSlot ?? null;
  const candidateSlot = options.candidateSlot ?? (activeSlot === null ? "blue" : otherSlot(activeSlot));
  const ownership = options.ownership ?? {
    host: { uid: 0, gid: 0 },
    container: { uid: 1000, gid: 1000 },
    secret: { uid: 0, gid: 1000 },
  };
  const candidateSmoke = options.candidateSmoke;
  let lock;
  let journal;
  let candidateStartAttempted = false;
  let candidateInitiallyActive = false;
  if (!config.slots[candidateSlot]) fail("candidate_slot_missing", "candidate_authorize");
  const paths = deploymentPaths(config, targetManifest, candidateSlot);

  try {
    authorizeCandidate(config, options.confirmation, getUid, platform, architecture, candidateSmoke, operation);
    const transition = assessReleaseTransition(sourceManifest, targetManifest, {
      operation,
      databaseEnabled: config.database !== null,
    });
    verifyCurrentIdentity(await readReleaseLink(config.paths.installRoot, "current", true), sourceManifest);
    if (operation === "rollback") {
      verifyPreviousIdentity(await readReleaseLink(config.paths.installRoot, "previous", true), targetManifest);
    }

    await verifyArchiveAtUse(targetManifest);
    for (const command of CANDIDATE_COMMANDS) {
      if (runner.run("which", [command], { allowFailure: true }).status !== 0) {
        fail(`missing_command=${command}`, "candidate_dependencies");
      }
    }
    const systemdState = runner.run("systemctl", ["is-system-running"], { allowFailure: true }).stdout.trim();
    if (!new Set(["running", "degraded", "starting"]).has(systemdState)) {
      fail("systemd_not_running", "candidate_dependencies");
    }
    const docker = runner.run("docker", ["info", "--format", "{{.OSType}}/{{.Architecture}}"], { allowFailure: true });
    if (docker.status !== 0 || !new Set(["linux/amd64", "linux/x86_64"]).has(docker.stdout.trim())) {
      fail("docker_linux_amd64_unavailable", "candidate_dependencies");
    }
    const material = await inspectInputMaterial(config);
    candidateInitiallyActive = await assertCandidatePortAvailable(config, candidateSlot, runner);
    await prepareLockDirectories(config, paths, ownership);
    lock = await acquireDeploymentLock(paths.lock, runId);
    await prepareDeploymentDirectories(config, paths, candidateSlot, ownership);
    if (config.database !== null) {
      await atomicWrite(paths.databaseUrl, `${material.databaseUrl}\n`, 0o440, ownership.secret);
    }
    const source = transition.source;
    const target = transition.target;
    await archiveCommittedDeploymentJournal(
      paths.journal,
      paths.historyRoot,
      source,
      activeSlot,
      ownership.host,
    );
    const expectedJournal = createDeploymentJournal({
      operation,
      planDigest: deploymentPlanDigest(config, source, target, material.fingerprint),
      runId,
      activeSlot,
      candidateSlot,
      source,
      target,
      now: now(),
    });
    journal = await readOrCreateDeploymentJournal(paths.journal, expectedJournal, ownership.host);
    candidateStartAttempted = candidateInitiallyActive;
    journal = await persistStage(paths.journal, journal, "artifact_verified", now, ownership.host);
    journal = await persistStage(paths.journal, journal, "lock_acquired", now, ownership.host);

    if (!reached(journal, "checkpoint_created")) {
      const currentReleaseTarget = await readReleaseLink(config.paths.installRoot, "current", true);
      verifyCurrentIdentity(currentReleaseTarget, sourceManifest);
      const checkpoint = {
        currentReleaseTarget,
        previousReleaseTarget: await readReleaseLink(config.paths.installRoot, "previous", false),
        nginxConfigSha256: await optionalRegularFileSha256(config.nginx.configFile),
        upstreamSha256: await optionalRegularFileSha256(config.nginx.upstreamConfigFile),
      };
      journal = advanceDeploymentJournal(journal, "checkpoint_created", now(), { checkpoint });
      await writeDeploymentJournal(paths.journal, journal, ownership.host);
    }
    if (!inspectLoadedImage(runner, targetManifest).loaded) {
      await verifyArchiveAtUse(targetManifest);
      const loaded = runner.run("docker", ["load", "--input", targetManifest.archivePath], {
        allowFailure: true,
        timeout: 120_000,
      });
      if (loaded.status !== 0) fail("bundle_image_load_failed", "candidate_image_load");
    }
    verifyLoadedImage(runner, targetManifest);
    verifyDatabaseMigration(config, targetManifest, runner);
    journal = await persistStage(paths.journal, journal, "migration_verified", now, ownership.host);

    await installCandidateFiles(config, targetManifest, candidateSlot, paths, material, ownership);

    if (reached(journal, "route_switched")) fail("candidate_phase_already_complete", "candidate_resume");
    candidateStartAttempted = true;
    runner.run("systemctl", ["daemon-reload"]);
    runner.run("systemctl", ["restart", `${config.slots[candidateSlot].serviceName}.service`], { timeout: 90_000 });
    journal = await persistStage(paths.journal, journal, "candidate_started", now, ownership.host);
    await verifyCandidateBase(config, targetManifest, candidateSlot, material.internal, options);
    await candidateSmoke({
      gatewayUrl: `http://127.0.0.1:${config.slots[candidateSlot].gatewayPort}`,
      appTokenSource: config.secrets.appTokenSource,
      connectorTokenSource: config.secrets.connectorTokenSource,
      internalStatusTokenSource: config.secrets.internalStatusTokenSource,
      expectedServerVersion: targetManifest.serverVersion,
      expectedSourceCommit: targetManifest.sourceCommit,
      expectedDeviceId: config.gateway.defaultDeviceId,
      candidateSlot,
    });
    journal = await persistStage(paths.journal, journal, "candidate_verified", now, ownership.host);

    return {
      ok: true,
      command: operation === "rollback" ? "prepare-rollback-candidate" : "prepare-candidate",
      environment: config.environment,
      runId,
      activeSlot,
      candidateSlot,
      serverVersion: targetManifest.serverVersion,
      sourceCommit: targetManifest.sourceCommit,
      imageId: targetManifest.imageId,
      stage: journal.stage,
      publicRouteChanged: false,
    };
  } catch (error) {
    if (candidateStartAttempted) {
      runner.run("systemctl", ["stop", `${config.slots[candidateSlot].serviceName}.service`], {
        allowFailure: true,
        timeout: 30_000,
      });
      runner.run("docker", ["rm", "--force", config.slots[candidateSlot].containerName], { allowFailure: true });
    }
    if (error instanceof OpsError && new Set(["artifact", "compatibility", "config", "database", "deployment"]).has(error.kind)) {
      throw error;
    }
    fail(error instanceof Error ? error.message : error, journal ? `candidate_${journal.stage}` : "candidate_prepare");
  } finally {
    await releaseDeploymentLock(lock).catch(() => {});
  }
}

async function prepareDeploymentDirectories(config, paths, candidateSlot, ownership) {
  await ensureManagedDirectory(config.paths.installRoot, 0o755, ownership.host);
  await ensureManagedDirectory(path.join(config.paths.installRoot, "releases"), 0o755, ownership.host);
  await ensureManagedDirectory(paths.releaseDir, 0o755, ownership.host);
  await ensureManagedDirectory(config.paths.configRoot, 0o750, ownership.host);
  await ensureManagedDirectory(path.join(config.paths.configRoot, "secrets"), 0o750, ownership.secret);
  await ensureManagedDirectory(path.join(config.paths.configRoot, "database-secrets"), 0o750, ownership.secret);
  await ensureManagedDirectory(path.join(config.paths.configRoot, "tls"), 0o750, ownership.host);
  await ensureManagedDirectory(path.join(config.paths.configRoot, "slots"), 0o750, ownership.host);
  await ensureManagedDirectory(paths.slotConfigDir, 0o750, ownership.host);
  await ensureManagedDirectory(path.join(config.paths.stateRoot, "gateway-slots"), 0o700, ownership.container);
  await ensureManagedDirectory(path.join(config.paths.stateRoot, "gateway-slots", candidateSlot), 0o700, ownership.container);
}

async function prepareLockDirectories(config, paths, ownership) {
  await ensureManagedDirectory(config.paths.stateRoot, 0o750, ownership.host);
  await ensureManagedDirectory(paths.opsRoot, 0o700, ownership.host);
  await ensureManagedDirectory(paths.historyRoot, 0o700, ownership.host);
}

async function installCandidateFiles(config, manifest, slot, paths, material, ownership) {
  await installImmutableFile(paths.releaseManifest, `${JSON.stringify(stripArchivePath(manifest), null, 2)}\n`, 0o644, ownership.host);
  await installImmutableFile(paths.appToken, `${material.app}\n`, 0o440, ownership.secret);
  await installImmutableFile(paths.connectorToken, `${material.connector}\n`, 0o440, ownership.secret);
  await installImmutableFile(paths.internalStatusToken, `${material.internal}\n`, 0o440, ownership.secret);
  await installImmutableFile(paths.certificate, material.certificate, 0o644, ownership.host);
  await installImmutableFile(paths.privateKey, material.privateKey, 0o600, ownership.host);
  await atomicWrite(paths.environment, renderDeployGatewayEnvironment(config, slot), 0o600, ownership.host);
  await atomicWrite(paths.unit, renderDeploySystemdUnit(config, manifest, slot), 0o644, ownership.host);
}

async function installImmutableFile(filePath, content, mode, owner) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile()) fail("immutable_file_unsafe", "candidate_install");
    const existing = await readFile(filePath);
    const expected = Buffer.isBuffer(content) ? content : Buffer.from(content);
    if (!existing.equals(expected)) fail("immutable_file_conflict", "candidate_install");
    await import("node:fs/promises").then(async ({ chmod, chown }) => {
      await chmod(filePath, mode);
      if (owner) await chown(filePath, owner.uid, owner.gid);
    });
    return;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
  await atomicWrite(filePath, content, mode, owner);
}

export async function verifyCandidateBase(config, manifest, slot, internalToken, options) {
  const fetchImpl = options.fetchImpl ?? fetch;
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const origin = `http://127.0.0.1:${config.slots[slot].gatewayPort}`;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const health = await fetchJson(fetchImpl, `${origin}/healthz`);
    const ready = await fetchJson(fetchImpl, `${origin}/readyz`);
    if (health?.status === "alive" && ready?.status === "ready") break;
    if (attempt === 39) fail("candidate_probe_timeout", "candidate_smoke");
    await sleep(250);
  }
  const version = await fetchJson(fetchImpl, `${origin}/internal/version`, {
    headers: { authorization: `Bearer ${internalToken}` },
  });
  if (version?.serverVersion !== manifest.serverVersion || version?.sourceCommit !== manifest.sourceCommit) {
    fail("candidate_version_identity_mismatch", "candidate_smoke");
  }
}

async function fetchJson(fetchImpl, url, init = {}) {
  try {
    const response = await fetchImpl(url, { ...init, signal: AbortSignal.timeout(2_000) });
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}

async function assertCandidatePortAvailable(config, slot, runner) {
  const selected = config.slots[slot];
  const unit = `${selected.serviceName}.service`;
  const active = runner.run("systemctl", ["is-active", "--quiet", unit], { allowFailure: true }).status === 0;
  const listeners = runner.run("ss", ["-ltnH", "sport", "=", `:${selected.gatewayPort}`], { allowFailure: true });
  if (listeners.status !== 0) fail("listen_socket_inspection_failed", "candidate_port");
  if (listeners.stdout.trim() && !active) fail("candidate_port_already_in_use", "candidate_port");
  return active;
}

async function readReleaseLink(installRoot, name, required) {
  const filePath = path.join(installRoot, name);
  try {
    const info = await lstat(filePath);
    if (!info.isSymbolicLink()) fail(`${name}_release_not_symlink`, "candidate_checkpoint");
    const target = await readlink(filePath);
    if (!/^releases\/(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-[0-9a-f]{12}$/.test(target)) {
      fail(`${name}_release_target_invalid`, "candidate_checkpoint");
    }
    return target;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code === "ENOENT" && !required) return null;
    fail(`${name}_release_missing`, "candidate_checkpoint");
  }
}

function verifyCurrentIdentity(currentTarget, sourceManifest) {
  const expected = `releases/${sourceManifest.serverVersion}-${sourceManifest.sourceCommit.slice(0, 12)}`;
  if (currentTarget !== expected) fail("current_release_identity_mismatch", "candidate_checkpoint");
}

function verifyPreviousIdentity(previousTarget, targetManifest) {
  const expected = `releases/${targetManifest.serverVersion}-${targetManifest.sourceCommit.slice(0, 12)}`;
  if (previousTarget !== expected) {
    throw new OpsError("compatibility", "rollback_target_must_match_previous_release", "release_compatibility");
  }
}

async function optionalRegularFileSha256(filePath) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile() || info.size > 1024 * 1024) {
      fail("checkpoint_file_unsafe", "candidate_checkpoint");
    }
    return createHash("sha256").update(await readFile(filePath)).digest("hex");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code === "ENOENT") return null;
    throw error;
  }
}

async function persistStage(filePath, journal, stage, now, owner) {
  if (reached(journal, stage)) return journal;
  const updated = advanceDeploymentJournal(journal, stage, now());
  await writeDeploymentJournal(filePath, updated, owner);
  return updated;
}

function reached(journal, stage) {
  return DEPLOYMENT_STAGES.indexOf(journal.stage) >= DEPLOYMENT_STAGES.indexOf(stage);
}

function deploymentPaths(config, manifest, slot) {
  const releaseName = `${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
  const opsRoot = path.join(config.paths.stateRoot, "ops");
  return {
    releaseDir: path.join(config.paths.installRoot, "releases", releaseName),
    releaseManifest: path.join(config.paths.installRoot, "releases", releaseName, "bundle.manifest.json"),
    slotConfigDir: path.join(config.paths.configRoot, "slots", slot),
    environment: path.join(config.paths.configRoot, "slots", slot, "gateway.env"),
    unit: path.join(config.paths.systemdUnitDirectory, `${config.slots[slot].serviceName}.service`),
    appToken: path.join(config.paths.configRoot, "secrets", "app-token"),
    connectorToken: path.join(config.paths.configRoot, "secrets", "connector-token"),
    internalStatusToken: path.join(config.paths.configRoot, "secrets", "internal-status-token"),
    databaseUrl: path.join(config.paths.configRoot, "database-secrets", "account-database-url"),
    certificate: path.join(config.paths.configRoot, "tls", "fullchain.pem"),
    privateKey: path.join(config.paths.configRoot, "tls", "privkey.pem"),
    journal: path.join(opsRoot, "deploy-state.json"),
    lock: path.join(opsRoot, "deploy.lock"),
    opsRoot,
    historyRoot: path.join(opsRoot, "history"),
  };
}

function authorizeCandidate(config, confirmation, getUid, platform, architecture, candidateSmoke, operation) {
  if (confirmation !== "staging" || config.environment !== "staging") fail("staging_confirmation_required", "candidate_authorize");
  if (getUid() !== 0) fail("candidate_requires_root", "candidate_authorize");
  if (platform !== "linux" || architecture !== "x64") fail(`unsupported_host=${platform}/${architecture}`, "candidate_authorize");
  if (typeof candidateSmoke !== "function") fail("candidate_full_smoke_required", "candidate_authorize");
  if (!new Set(["deploy", "rollback"]).has(operation)) fail("candidate_operation_invalid", "candidate_authorize");
}

function stripArchivePath(manifest) {
  const { archivePath: _archivePath, ...safe } = manifestIdentity(manifest);
  return safe;
}

function fail(cause, stage) {
  throw new OpsError("deployment", cause, stage);
}
