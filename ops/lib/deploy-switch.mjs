import { constants } from "node:fs";
import { createHash, randomUUID } from "node:crypto";
import { lstat, open, readlink, rename, symlink, unlink } from "node:fs/promises";
import path from "node:path";
import {
  acquireDeploymentLock,
  advanceDeploymentJournal,
  DEPLOYMENT_STAGES,
  deploymentPlanDigest,
  readDeploymentJournal,
  releaseDeploymentLock,
  writeDeploymentJournal,
} from "./deploy-state.mjs";
import {
  renderDeployNginxConfig,
  renderNginxUpstream,
} from "./deploy-system.mjs";
import { OpsError } from "./errors.mjs";
import { inspectInputMaterial, verifyArchiveAtUse, verifyLoadedImage } from "./hermesctl.mjs";
import { handoffLifecycleSnapshot } from "./lifecycle-handoff.mjs";
import { assessReleaseTransition } from "./release-transition.mjs";
import { assertNoSymlinkAncestors, atomicWrite, createCommandRunner } from "./system.mjs";
import { verifyCandidateBase } from "./deploy.mjs";
import { verifyDatabaseMigration } from "./database-migration.mjs";

const FILE_LIMIT = 1024 * 1024;

export async function switchCandidate(config, sourceManifest, targetManifest, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 90_000 });
  const now = options.now ?? (() => new Date());
  const sleep = options.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const runId = options.runId ?? randomUUID();
  const operation = options.operation ?? "deploy";
  const ownership = options.ownership ?? {
    host: { uid: 0, gid: 0 },
    container: { uid: 1000, gid: 1000 },
  };
  const paths = switchPaths(config);
  let lock;
  let journal;
  let checkpoint;
  let sourceStopped = false;
  let recoveryRequired = false;
  let candidateControlled = false;

  try {
    authorizeSwitch(config, options);
    const transition = assessReleaseTransition(sourceManifest, targetManifest, {
      operation,
      databaseEnabled: config.database !== null,
    });
    if (transition.maintenanceRequired !== true) {
      fail("target_must_declare_maintenance_window", "switch_authorize");
    }
    await verifyArchiveAtUse(targetManifest);
    verifyLoadedImage(runner, targetManifest);
    const material = await inspectInputMaterial(config);
    const planDigest = deploymentPlanDigest(config, transition.source, transition.target, material.fingerprint);
    lock = await acquireDeploymentLock(paths.lock, runId);
    journal = await readDeploymentJournal(paths.journal);
    verifyJournal(journal, operation, planDigest, transition.source, transition.target);

    const source = sourceDescriptor(config, journal.activeSlot);
    const candidate = slotDescriptor(config, journal.candidateSlot);
    const stageIndex = DEPLOYMENT_STAGES.indexOf(journal.stage);
    const switchedIndex = DEPLOYMENT_STAGES.indexOf("route_switched");
    if (journal.stage === "committed") {
      await assertCurrentRelease(config, releaseTarget(targetManifest));
      await assertServiceActive(runner, candidate.serviceName, "committed_candidate_not_active");
      if (await serviceActive(runner, source.serviceName)) fail("committed_source_still_active", "switch_committed_verify");
      await assertSwitchedNginx(config, journal.candidateSlot);
      await options.publicSmoke(smokeRequest(config, targetManifest, journal.candidateSlot, true));
      await removeManagedFile(paths.handoff(planDigest), false);
      return committedResult(config, journal, targetManifest, operation);
    }
    if (stageIndex < DEPLOYMENT_STAGES.indexOf("candidate_verified")) {
      fail("candidate_not_verified", "switch_resume");
    }
    candidateControlled = true;
    verifyDatabaseMigration(config, targetManifest, runner);

    if (stageIndex < switchedIndex) {
      await assertCurrentRelease(config, journal.checkpoint.currentReleaseTarget);
      checkpoint = await readOrCreateSwitchCheckpoint(paths.checkpoint(planDigest), config, journal, planDigest, ownership.host);
      if (!await serviceActive(runner, source.serviceName)) {
        sourceStopped = true;
        recoveryRequired = true;
        fail("incomplete_switch_detected", "switch_resume");
      }
      await assertServiceActive(runner, candidate.serviceName, "candidate_service_not_active");
      await verifyCandidateBase(config, targetManifest, journal.candidateSlot, material.internal, options);
      await options.candidateSmoke(smokeRequest(config, targetManifest, journal.candidateSlot, false));

      mustRun(runner, "systemctl", ["stop", `${candidate.serviceName}.service`], "candidate_stop");
      mustRun(runner, "systemctl", ["stop", `${source.serviceName}.service`], "source_stop");
      await assertServiceInactive(runner, candidate.serviceName, "candidate_did_not_stop");
      await assertServiceInactive(runner, source.serviceName, "source_did_not_stop");
      sourceStopped = true;
      recoveryRequired = true;
      await handoffLifecycleSnapshot(source.stateDirectory, candidate.stateDirectory, { owner: ownership.container });
      await writeHandoffMarker(paths.handoff(planDigest), planDigest, source, candidate, "forward", now(), ownership.host);
      mustRun(runner, "systemctl", ["restart", `${candidate.serviceName}.service`], "candidate_restart");
      await verifyCandidateBase(config, targetManifest, journal.candidateSlot, material.internal, options);

      await atomicSwitchNginx(config, journal.candidateSlot, runner, ownership.host);
      journal = await persistStage(paths.journal, journal, "route_switched", now, ownership.host);
    } else {
      checkpoint = await readSwitchCheckpoint(paths.checkpoint(planDigest), journal, planDigest);
      recoveryRequired = true;
      const marker = await readHandoffMarker(paths.handoff(planDigest), planDigest, source, candidate);
      if (marker.phase !== "forward") fail("handoff_already_restored", "switch_resume");
      sourceStopped = !await serviceActive(runner, source.serviceName);
      if (!sourceStopped) fail("source_still_active_after_route_switch", "switch_resume");
      await assertServiceActive(runner, candidate.serviceName, "candidate_service_not_active");
      await assertSwitchedNginx(config, journal.candidateSlot);
    }

    if (!reached(journal, "draining")) {
      await options.publicSmoke(smokeRequest(config, targetManifest, journal.candidateSlot, true));
      await sleep(config.deployment.observationSeconds * 1000);
      await verifyCandidateBase(config, targetManifest, journal.candidateSlot, material.internal, options);
      await options.publicSmoke(smokeRequest(config, targetManifest, journal.candidateSlot, true));
      journal = await persistStage(paths.journal, journal, "draining", now, ownership.host);
    }

    mustRun(runner, "systemctl", ["enable", `${candidate.serviceName}.service`], "candidate_enable");
    mustRun(runner, "systemctl", ["disable", `${source.serviceName}.service`], "source_disable");
    await commitReleaseLinks(config.paths.installRoot, journal, targetManifest);
    journal = await persistStage(paths.journal, journal, "committed", now, ownership.host);
    await removeManagedFile(paths.handoff(planDigest), false);
    recoveryRequired = false;
    return committedResult(config, journal, targetManifest, operation);
  } catch (error) {
    let recoveryError;
    if (recoveryRequired && journal?.checkpoint && checkpoint) {
      try {
        await recoverExistingService(config, journal, checkpoint, runner, options, ownership, paths);
        await archiveRecoveredJournal(paths, journal, now(), ownership.host);
      } catch (recoveryFailure) {
        recoveryError = recoveryFailure;
      }
    } else if (candidateControlled) {
      try {
        await cleanCandidateBeforeSwitch(config, journal, runner);
      } catch (cleanupFailure) {
        recoveryError = cleanupFailure;
      }
    }
    if (recoveryError) {
      fail(`automatic_recovery_failed:${technical(recoveryError)}`, "switch_recovery_failed");
    }
    if (error instanceof OpsError && (error.kind === "switch"
        || (!recoveryRequired && new Set(["artifact", "compatibility", "config", "database"]).has(error.kind)))) {
      throw error;
    }
    fail(technical(error), sourceStopped ? "switch_recovered" : "switch_prepare");
  } finally {
    await releaseDeploymentLock(lock).catch(() => {});
  }
}

async function cleanCandidateBeforeSwitch(config, journal, runner) {
  const candidate = slotDescriptor(config, journal.candidateSlot);
  mustRun(runner, "systemctl", ["stop", `${candidate.serviceName}.service`], "pre_switch_candidate_stop");
  await assertServiceInactive(runner, candidate.serviceName, "pre_switch_candidate_did_not_stop");
  runner.run("docker", ["rm", "--force", candidate.containerName], { allowFailure: true });
}

async function recoverExistingService(config, journal, checkpoint, runner, options, ownership, paths) {
  const source = sourceDescriptor(config, journal.activeSlot);
  const candidate = slotDescriptor(config, journal.candidateSlot);
  const planDigest = journal.planDigest;
  mustRun(runner, "systemctl", ["stop", `${candidate.serviceName}.service`], "recovery_candidate_stop");
  await assertServiceInactive(runner, candidate.serviceName, "recovery_candidate_did_not_stop");
  const marker = await readOptionalHandoffMarker(paths.handoff(planDigest), planDigest, source, candidate);
  if (marker?.phase === "forward") {
    await handoffLifecycleSnapshot(candidate.stateDirectory, source.stateDirectory, { owner: ownership.container });
    await writeHandoffMarker(
      paths.handoff(planDigest),
      planDigest,
      source,
      candidate,
      "restored",
      (options.now ?? (() => new Date()))(),
      ownership.host,
    );
  }
  mustRun(runner, "systemctl", ["restart", `${source.serviceName}.service`], "recovery_source_restart");
  await assertServiceActive(runner, source.serviceName, "recovery_source_not_active");
  await restoreNginxCheckpoint(config, checkpoint, runner, ownership.host);
  await restoreReleaseLinks(config.paths.installRoot, journal.checkpoint);
  mustRun(runner, "systemctl", ["enable", `${source.serviceName}.service`], "recovery_source_enable");
  runner.run("systemctl", ["disable", `${candidate.serviceName}.service`], { allowFailure: true });
  await options.publicSmoke(smokeRequest(config, journal.source, journal.activeSlot, true, true));
  if (marker) await removeManagedFile(paths.handoff(planDigest), true);
}

async function atomicSwitchNginx(config, slot, runner, owner) {
  try {
    await atomicWrite(config.nginx.upstreamConfigFile, renderNginxUpstream(config, slot), 0o644, owner);
    await atomicWrite(config.nginx.configFile, renderDeployNginxConfig(config), 0o644, owner);
  } catch (error) {
    fail(technical(error), "nginx_configuration_write");
  }
  mustRun(runner, "nginx", ["-t"], "nginx_configuration_test");
  mustRun(runner, "systemctl", ["reload", "nginx.service"], "nginx_reload");
}

async function assertSwitchedNginx(config, slot) {
  const nginxConfig = await readManagedFile(config.nginx.configFile, true);
  const upstream = await readManagedFile(config.nginx.upstreamConfigFile, true);
  if (!nginxConfig.content.equals(Buffer.from(renderDeployNginxConfig(config)))
      || !upstream.content.equals(Buffer.from(renderNginxUpstream(config, slot)))) {
    fail("switched_nginx_configuration_mismatch", "switch_resume");
  }
}

async function restoreNginxCheckpoint(config, checkpoint, runner, owner) {
  await restoreFile(config.nginx.configFile, checkpoint.nginxConfig, owner);
  await restoreFile(config.nginx.upstreamConfigFile, checkpoint.upstream, owner);
  mustRun(runner, "nginx", ["-t"], "recovery_nginx_test");
  mustRun(runner, "systemctl", ["reload", "nginx.service"], "recovery_nginx_reload");
}

async function readOrCreateSwitchCheckpoint(filePath, config, journal, planDigest, owner) {
  try {
    return await readSwitchCheckpoint(filePath, journal, planDigest);
  } catch (error) {
    if (!(error instanceof OpsError) || error.technicalCause !== "switch_checkpoint_missing") throw error;
  }
  const nginxConfig = await readManagedFile(config.nginx.configFile, true);
  const upstream = await readManagedFile(config.nginx.upstreamConfigFile, false);
  verifyFileHash(nginxConfig, journal.checkpoint.nginxConfigSha256, "nginx_checkpoint_changed");
  verifyFileHash(upstream, journal.checkpoint.upstreamSha256, "upstream_checkpoint_changed");
  const checkpoint = {
    schemaVersion: 1,
    planDigest,
    nginxConfig: encodedFile(nginxConfig),
    upstream: encodedFile(upstream),
  };
  await atomicWrite(filePath, `${JSON.stringify(checkpoint)}\n`, 0o600, owner);
  return checkpoint;
}

async function readSwitchCheckpoint(filePath, journal, planDigest) {
  const value = await readJsonFile(filePath, 3 * FILE_LIMIT, "switch_checkpoint_missing", "switch_checkpoint_invalid");
  exactKeys(value, ["schemaVersion", "planDigest", "nginxConfig", "upstream"], "switch_checkpoint");
  if (value.schemaVersion !== 1 || value.planDigest !== planDigest) fail("switch_checkpoint_identity_invalid", "switch_checkpoint_read");
  const nginxConfig = decodedFile(value.nginxConfig);
  const upstream = decodedFile(value.upstream);
  verifyFileHash(nginxConfig, journal.checkpoint.nginxConfigSha256, "nginx_checkpoint_invalid");
  verifyFileHash(upstream, journal.checkpoint.upstreamSha256, "upstream_checkpoint_invalid");
  if (!nginxConfig.present) fail("nginx_checkpoint_missing", "switch_checkpoint_read");
  return { ...value, nginxConfig: encodedFile(nginxConfig), upstream: encodedFile(upstream) };
}

async function writeHandoffMarker(filePath, planDigest, source, candidate, phase, now, owner) {
  const marker = {
    schemaVersion: 1,
    planDigest,
    sourceStateDirectory: source.stateDirectory,
    candidateStateDirectory: candidate.stateDirectory,
    phase,
    updatedAt: canonicalTimestamp(now),
  };
  await atomicWrite(filePath, `${JSON.stringify(marker)}\n`, 0o600, owner);
}

async function readHandoffMarker(filePath, planDigest, source, candidate) {
  const marker = await readJsonFile(filePath, 16 * 1024, "handoff_marker_missing", "handoff_marker_invalid");
  exactKeys(marker, [
    "schemaVersion",
    "planDigest",
    "sourceStateDirectory",
    "candidateStateDirectory",
    "phase",
    "updatedAt",
  ], "handoff_marker");
  if (marker.schemaVersion !== 1 || marker.planDigest !== planDigest
      || marker.sourceStateDirectory !== source.stateDirectory
      || marker.candidateStateDirectory !== candidate.stateDirectory
      || !new Set(["forward", "restored"]).has(marker.phase)) {
    fail("handoff_marker_identity_invalid", "handoff_marker_read");
  }
  canonicalTimestamp(marker.updatedAt);
  return marker;
}

async function readOptionalHandoffMarker(filePath, planDigest, source, candidate) {
  try {
    return await readHandoffMarker(filePath, planDigest, source, candidate);
  } catch (error) {
    if (error instanceof OpsError && error.technicalCause === "handoff_marker_missing") return null;
    throw error;
  }
}

async function readJsonFile(filePath, limit, missingCause, invalidCause) {
  let handle;
  try {
    await assertNoSymlinkAncestors(filePath);
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || (info.mode & 0o077) !== 0 || info.size < 2 || info.size > limit) fail(invalidCause, "switch_state_read");
    return JSON.parse(await handle.readFile("utf8"));
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code === "ENOENT") fail(missingCause, "switch_state_read");
    fail(invalidCause, "switch_state_read");
  } finally {
    await handle?.close().catch(() => {});
  }
}

async function readManagedFile(filePath, required) {
  let handle;
  try {
    await assertNoSymlinkAncestors(filePath);
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || info.size > FILE_LIMIT) fail("nginx_file_unsafe", "switch_checkpoint_create");
    return { present: true, content: await handle.readFile() };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code === "ENOENT" && !required) return { present: false, content: Buffer.alloc(0) };
    fail(required && error?.code === "ENOENT" ? "nginx_config_missing" : technical(error), "switch_checkpoint_create");
  } finally {
    await handle?.close().catch(() => {});
  }
}

function encodedFile(file) {
  return {
    present: file.present,
    sha256: file.present ? createHash("sha256").update(file.content).digest("hex") : null,
    contentBase64: file.present ? file.content.toString("base64") : null,
  };
}

function decodedFile(value) {
  exactKeys(value, ["present", "sha256", "contentBase64"], "checkpoint_file");
  if (typeof value.present !== "boolean") fail("checkpoint_file_presence_invalid", "switch_checkpoint_read");
  if (!value.present) {
    if (value.sha256 !== null || value.contentBase64 !== null) fail("checkpoint_absent_file_invalid", "switch_checkpoint_read");
    return { present: false, content: Buffer.alloc(0) };
  }
  if (typeof value.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(value.sha256)
      || typeof value.contentBase64 !== "string" || value.contentBase64.length > 2 * FILE_LIMIT) {
    fail("checkpoint_file_invalid", "switch_checkpoint_read");
  }
  const content = Buffer.from(value.contentBase64, "base64");
  if (content.toString("base64") !== value.contentBase64
      || createHash("sha256").update(content).digest("hex") !== value.sha256) {
    fail("checkpoint_file_hash_invalid", "switch_checkpoint_read");
  }
  return { present: true, content };
}

function verifyFileHash(file, expected, cause) {
  const actual = file.present ? createHash("sha256").update(file.content).digest("hex") : null;
  if (actual !== expected) fail(cause, "switch_checkpoint_verify");
}

async function restoreFile(filePath, encoded, owner) {
  const file = decodedFile(encoded);
  if (file.present) await atomicWrite(filePath, file.content, 0o644, owner);
  else await removeManagedFile(filePath, false);
}

async function removeManagedFile(filePath, required) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile()) fail("managed_file_remove_unsafe", "switch_cleanup");
    await unlink(filePath);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code === "ENOENT" && !required) return;
    throw error;
  }
}

async function commitReleaseLinks(installRoot, journal, targetManifest) {
  const target = releaseTarget(targetManifest);
  await replaceReleaseLink(installRoot, "previous", journal.checkpoint.currentReleaseTarget);
  await replaceReleaseLink(installRoot, "current", target);
}

function releaseTarget(manifest) {
  return `releases/${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
}

async function restoreReleaseLinks(installRoot, checkpoint) {
  await replaceReleaseLink(installRoot, "current", checkpoint.currentReleaseTarget);
  await replaceReleaseLink(installRoot, "previous", checkpoint.previousReleaseTarget);
}

async function replaceReleaseLink(installRoot, name, target) {
  const linkPath = path.join(installRoot, name);
  await assertNoSymlinkAncestors(installRoot);
  if (target === null) {
    try {
      const info = await lstat(linkPath);
      if (!info.isSymbolicLink()) fail(`${name}_release_not_symlink`, "release_link_restore");
      await unlink(linkPath);
    } catch (error) {
      if (error instanceof OpsError) throw error;
      if (error?.code !== "ENOENT") throw error;
    }
    return;
  }
  if (!/^releases\/(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-[0-9a-f]{12}$/.test(target)) {
    fail("release_link_target_invalid", "release_link_write");
  }
  try {
    const releaseDirectory = path.join(installRoot, target);
    await assertNoSymlinkAncestors(releaseDirectory);
    const releaseInfo = await lstat(releaseDirectory);
    if (releaseInfo.isSymbolicLink() || !releaseInfo.isDirectory()) {
      fail("release_link_directory_unsafe", "release_link_write");
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("release_link_directory_missing", "release_link_write");
  }
  try {
    const info = await lstat(linkPath);
    if (!info.isSymbolicLink()) fail(`${name}_release_not_symlink`, "release_link_write");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
  const temporary = path.join(installRoot, `.${name}.${randomUUID()}.tmp`);
  await symlink(target, temporary);
  try {
    await rename(temporary, linkPath);
  } catch (error) {
    await unlink(temporary).catch(() => {});
    throw error;
  }
}

async function assertCurrentRelease(config, expected) {
  let target;
  try {
    const info = await lstat(path.join(config.paths.installRoot, "current"));
    if (!info.isSymbolicLink()) fail("current_release_not_symlink", "switch_checkpoint_verify");
    target = await readlink(path.join(config.paths.installRoot, "current"));
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("current_release_missing", "switch_checkpoint_verify");
  }
  if (target !== expected) fail("current_release_changed", "switch_checkpoint_verify");
}

async function archiveRecoveredJournal(paths, journal, now, owner) {
  const archive = {
    ...journal,
    updatedAt: canonicalTimestamp(now),
  };
  const archivePath = path.join(paths.opsRoot, `deploy-state.recovered.${journal.runId}.json`);
  await atomicWrite(archivePath, `${JSON.stringify(archive, null, 2)}\n`, 0o600, owner);
  await removeManagedFile(paths.journal, true);
}

function verifyJournal(journal, operation, planDigest, source, target) {
  if (journal.operation !== operation || journal.planDigest !== planDigest
      || JSON.stringify(journal.source) !== JSON.stringify(source)
      || JSON.stringify(journal.target) !== JSON.stringify(target)) {
    fail("deployment_journal_identity_mismatch", "switch_resume");
  }
}

function sourceDescriptor(config, activeSlot) {
  if (activeSlot === null) {
    return {
      serviceName: config.legacySource.serviceName,
      containerName: config.legacySource.containerName,
      stateDirectory: config.legacySource.stateDirectory,
    };
  }
  return slotDescriptor(config, activeSlot);
}

function slotDescriptor(config, slot) {
  return {
    ...config.slots[slot],
    stateDirectory: path.join(config.paths.stateRoot, "gateway-slots", slot),
  };
}

async function assertServiceActive(runner, serviceName, cause) {
  if (!await serviceActive(runner, serviceName)) fail(cause, "switch_service_check");
}

async function assertServiceInactive(runner, serviceName, cause) {
  if (await serviceActive(runner, serviceName)) fail(cause, "switch_service_check");
}

async function serviceActive(runner, serviceName) {
  return runner.run("systemctl", ["is-active", "--quiet", `${serviceName}.service`], { allowFailure: true }).status === 0;
}

function mustRun(runner, command, args, stage) {
  const result = runner.run(command, args, { allowFailure: true, timeout: 90_000 });
  if (result.status !== 0) fail(`${command}_returned_${result.status}`, stage);
  return result;
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

function smokeRequest(config, manifest, candidateSlot, publicRoute, recovery = false) {
  const port = config.nginx.listenPort === 443 ? "" : `:${config.nginx.listenPort}`;
  return {
    gatewayUrl: publicRoute
      ? `https://${config.nginx.serverName}${port}`
      : `http://127.0.0.1:${config.slots[candidateSlot].gatewayPort}`,
    appTokenSource: config.secrets.appTokenSource,
    connectorTokenSource: config.secrets.connectorTokenSource,
    internalStatusTokenSource: config.secrets.internalStatusTokenSource,
    expectedServerVersion: manifest.serverVersion,
    expectedSourceCommit: manifest.sourceCommit,
    expectedDeviceId: config.gateway.defaultDeviceId,
    candidateSlot,
    publicRoute,
    recovery,
  };
}

function authorizeSwitch(config, options) {
  if (options.confirmation !== "staging" || config.environment !== "staging") fail("staging_confirmation_required", "switch_authorize");
  if ((options.getUid ?? (() => process.getuid?.()))() !== 0) fail("switch_requires_root", "switch_authorize");
  if ((options.platform ?? process.platform) !== "linux" || (options.architecture ?? process.arch) !== "x64") {
    fail("unsupported_switch_host", "switch_authorize");
  }
  if (typeof options.candidateSmoke !== "function" || typeof options.publicSmoke !== "function") {
    fail("private_and_public_smoke_required", "switch_authorize");
  }
  if (!new Set(["deploy", "rollback"]).has(options.operation ?? "deploy")) {
    fail("switch_operation_invalid", "switch_authorize");
  }
}

function switchPaths(config) {
  const opsRoot = path.join(config.paths.stateRoot, "ops");
  return {
    opsRoot,
    journal: path.join(opsRoot, "deploy-state.json"),
    lock: path.join(opsRoot, "deploy.lock"),
    checkpoint: (planDigest) => path.join(opsRoot, `switch-checkpoint.${planDigest}.json`),
    handoff: (planDigest) => path.join(opsRoot, `lifecycle-handoff.${planDigest}.json`),
  };
}

function committedResult(config, journal, targetManifest, operation) {
  return {
    ok: true,
    command: operation === "rollback" ? "switch-rollback-candidate" : "switch-candidate",
    environment: config.environment,
    runId: journal.runId,
    activeSlot: journal.candidateSlot,
    previousSlot: journal.activeSlot,
    serverVersion: targetManifest.serverVersion,
    sourceCommit: targetManifest.sourceCommit,
    stage: journal.stage,
    publicRouteChanged: true,
  };
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label}_must_be_object`, "switch_state_validate");
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    fail(`${label}_fields_invalid`, "switch_state_validate");
  }
}

function canonicalTimestamp(value) {
  const date = value instanceof Date ? value : new Date(value);
  let canonical;
  try {
    canonical = date.toISOString();
  } catch {
    fail("switch_timestamp_invalid", "switch_state_validate");
  }
  if (!(value instanceof Date) && canonical !== value) fail("switch_timestamp_invalid", "switch_state_validate");
  return canonical;
}

function technical(error) {
  return error instanceof Error ? error.technicalCause || error.message : String(error);
}

function fail(cause, stage) {
  throw new OpsError("switch", cause, stage);
}
