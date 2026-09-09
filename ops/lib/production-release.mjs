import { createHash, randomUUID } from "node:crypto";
import { lstat, readFile, readlink } from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { executeDeployment, loadCurrentManifest, resolveActiveSlot } from "./deploy-command.mjs";
import {
  acquireDeploymentLock,
  readDeploymentJournal,
  releaseDeploymentLock,
  restoreCommittedJournalAfterFailedCandidate,
} from "./deploy-state.mjs";
import {
  inspectProductionReleaseEnvironment,
  renderProductionReleaseEnvironment,
  sameProductionReleaseEnvironment,
} from "./production-release-environment.mjs";
import { satisfiesProductionNginxContract } from "./deploy-switch.mjs";
import { renderNginxUpstream } from "./deploy-system.mjs";
import { OpsError } from "./errors.mjs";
import { createCommandRunner } from "./system.mjs";

const OPERATIONS = new Set(["deploy", "rollback"]);
const RELEASE_TARGET = /^releases\/(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-[0-9a-f]{12}$/;
const FILE_LIMIT = 1024 * 1024;

/**
 * R5-F1: a routine production release inside the managed baseline that R5-D established.
 *
 * R5-D is a one-time adoption (`activeSlot: null` → blue) and deliberately refuses to run
 * again. This entrypoint is the ordinary path for every later Gateway version: it moves the
 * committed release from the active slot to the other slot with the same R4 lock, journal,
 * checkpoint, private/public smoke, observation window and automatic recovery, and it never
 * touches the legacy Node service, the Nginx site file, the account flags or the database.
 * `rollback` is the same machine pointed at the release behind `previous`.
 */
export async function executeProductionRelease(config, targetManifest, options = {}) {
  const now = options.now ?? (() => new Date());
  const runner = options.runner ?? createCommandRunner();
  const runId = options.runId ?? randomUUID();
  const ownership = options.ownership ?? {
    host: { uid: 0, gid: 0 },
    container: { uid: 1000, gid: 1000 },
    secret: { uid: 0, gid: 1000 },
  };
  const admission = await verifyProductionReleaseAdmission(config, targetManifest, { ...options, now, runner });

  const execute = options.executeDeployment ?? executeDeployment;
  let result;
  try {
    const candidateSmoke = preserveAccountSurface(
      options.candidateSmoke,
      admission.runtimeEnvironment,
      options.fetchImpl,
    );
    const publicSmoke = preserveAccountSurface(
      options.publicSmoke,
      admission.runtimeEnvironment,
      options.fetchImpl,
    );
    result = await execute(config, targetManifest, {
      operation: admission.operation,
      confirmation: options.confirmation,
      candidateSmoke,
      publicSmoke,
      candidateEnvironment: (_config, slot) => renderProductionReleaseEnvironment(
        config,
        slot,
        admission.runtimeEnvironment,
      ),
      sourcePreflight: () => verifyReleaseInputs(
        config,
        admission.activeSlot,
        runner,
        admission.runtimeEnvironment,
      ),
      runner,
      platform: options.platform,
      architecture: options.architecture,
      getUid: options.getUid,
      ownership,
      fetchImpl: options.fetchImpl,
      sleep: options.sleep,
      now,
      runId,
      sourceManifest: admission.sourceManifest,
      authorization: "production-release",
    });
  } catch (error) {
    throw new OpsError(
      "productionRelease",
      error instanceof Error ? `${error.stage ?? "production_release_execute"}:${error.technicalCause ?? error.message}` : error,
      error?.stage ?? "production_release_execute",
    );
  }
  return {
    ...result,
    command: `production-${admission.operation}`,
    activeSlotBefore: admission.activeSlot,
    rollbackPoint: releaseTarget(admission.sourceManifest),
  };
}

export async function verifyProductionReleaseAdmission(config, targetManifest, options = {}) {
  try {
    if (!OPERATIONS.has(options.operation)) fail("production_release_operation_invalid");
    if (config.managedBaseline !== true || config.environment !== "production") fail("production_release_config_required");
    if (options.confirmation !== `production:${config.host.hostname}`) fail("production_release_confirmation_required");
    if ((options.getUid ?? (() => process.getuid?.()))() !== 0) fail("production_release_requires_root");
    if ((options.platform ?? process.platform) !== "linux"
        || (options.architecture ?? process.arch) !== "x64"
        || (options.hostname ?? systemHostname()) !== config.host.hostname) {
      fail("production_release_host_mismatch");
    }
    if (typeof options.candidateSmoke !== "function" || typeof options.publicSmoke !== "function") {
      fail("production_release_smoke_callbacks_required");
    }
    if (config.database !== null
        || config.gateway.accountAuthEnabled !== false
        || config.gateway.accountBindingEnabled !== false) {
      fail("production_release_account_and_database_must_stay_disabled");
    }
    const release = targetManifest?.releaseContract;
    if (![2, 3].includes(targetManifest?.schemaVersion)
        || release?.maintenanceRequired !== true
        || release?.rollbackSupported !== true) {
      throw new OpsError("compatibility", "production_release_target_contract_invalid", "production_release_admission");
    }

    let sourceManifest;
    try {
      sourceManifest = await loadCurrentManifest(config);
    } catch (error) {
      fail(`production_release_current_release_unreadable:${technical(error)}`);
    }
    // The legacy descriptor that R5-D registers as `previous` is schema 1; a `current` that is
    // still schema 1 means the managed baseline was never adopted.
    if (sourceManifest.schemaVersion < 2) fail("production_release_requires_managed_current");
    let activeSlot;
    try {
      activeSlot = await resolveActiveSlot(config, sourceManifest);
    } catch (error) {
      fail(`production_release_journal_invalid:${technical(error)}`);
    }
    if (activeSlot === null) fail("production_release_requires_committed_managed_slot");
    if (options.operation === "rollback") {
      const previous = await readReleaseLink(config.paths.installRoot, "previous");
      if (previous !== releaseTarget(targetManifest)) fail("production_release_rollback_target_not_previous");
      // Straight after R5-D `previous` is the legacy Node descriptor: there is no image to start,
      // so going back there is an R5-B recovery, never a slot rollback.
      try {
        await lstat(path.join(config.paths.installRoot, previous, "bundle.manifest.json"));
      } catch {
        fail("production_release_rollback_to_legacy_requires_recovery");
      }
    }
    const runtimeEnvironment = await verifyReleaseInputs(config, activeSlot, options.runner);
    if (runtimeEnvironment.mode === "email_otp"
        && sourceManifest.releaseContract?.databaseSchemaVersion
          !== targetManifest.releaseContract?.databaseSchemaVersion) {
      fail("production_release_email_database_schema_change_requires_migration");
    }
    return { operation: options.operation, sourceManifest, activeSlot, runtimeEnvironment };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  }
}

export async function recoverFailedProductionRelease(config, targetManifest, options = {}) {
  const runner = options.runner ?? createCommandRunner();
  const runId = options.runId ?? randomUUID();
  const owner = options.owner ?? { uid: 0, gid: 0 };
  let lock;
  try {
    if (config.managedBaseline !== true || config.environment !== "production") fail("production_release_config_required");
    if (options.confirmation !== `production:${config.host.hostname}`) fail("production_release_confirmation_required");
    if ((options.getUid ?? (() => process.getuid?.()))() !== 0) fail("production_release_requires_root");
    if ((options.platform ?? process.platform) !== "linux"
        || (options.architecture ?? process.arch) !== "x64"
        || (options.hostname ?? systemHostname()) !== config.host.hostname) {
      fail("production_release_host_mismatch");
    }
    if (config.database !== null
        || config.gateway.accountAuthEnabled !== false
        || config.gateway.accountBindingEnabled !== false) {
      fail("production_release_account_and_database_must_stay_disabled");
    }
    const release = targetManifest?.releaseContract;
    if (![2, 3].includes(targetManifest?.schemaVersion)
        || release?.maintenanceRequired !== true
        || release?.rollbackSupported !== true) {
      throw new OpsError("compatibility", "production_release_target_contract_invalid", "production_release_recover");
    }

    const sourceManifest = await loadCurrentManifest(config);
    const journalPath = path.join(config.paths.stateRoot, "ops", "deploy-state.json");
    const historyRoot = path.join(config.paths.stateRoot, "ops", "history");
    const auditPath = path.join(config.paths.stateRoot, "ops", "operations.jsonl");
    const journal = await readDeploymentJournal(journalPath);
    if (journal.stage !== "candidate_started"
        || journal.operation !== "deploy"
        || journal.activeSlot === null
        || JSON.stringify(journal.source) !== JSON.stringify(releaseIdentity(sourceManifest))) {
      fail("production_release_failed_candidate_not_recoverable");
    }
    await verifyFailedCandidateLiveState(config, journal, runner, sourceManifest);

    const lockPath = path.join(config.paths.stateRoot, "ops", "deploy.lock");
    lock = await acquireDeploymentLock(lockPath, runId);
    await verifyFailedCandidateLiveState(config, journal, runner, sourceManifest);
    const currentCheckpoint = await productionCheckpoint(config);
    const recovered = await restoreCommittedJournalAfterFailedCandidate({
      filePath: journalPath,
      historyRoot,
      auditPath,
      expectedSource: releaseIdentity(sourceManifest),
      activeSlot: journal.activeSlot,
      currentCheckpoint,
      owner,
    });
    await verifyFailedCandidateLiveState(config, recovered.failed, runner, sourceManifest);
    return {
      ok: true,
      command: "production-recover",
      recoveredRunId: recovered.failed.runId,
      activeSlot: recovered.failed.activeSlot,
      candidateSlot: recovered.failed.candidateSlot,
      sourceVersion: sourceManifest.serverVersion,
      targetVersion: targetManifest.serverVersion,
    };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  } finally {
    await releaseDeploymentLock(lock).catch(() => {});
  }
}

/**
 * Re-run before candidate start and again immediately before the active slot is stopped:
 * the active slot must still be the one serving, the legacy Node unit must still be retired,
 * and the edge must still route through the production upstream that this release will move.
 */
export async function verifyReleaseInputs(config, activeSlot, runner, expectedEnvironment) {
  if (!runner) fail("production_release_runner_required");
  if (!config.slots[activeSlot]) fail("production_release_active_slot_unknown");
  if (!serviceActive(runner, config.slots[activeSlot].serviceName)) fail("production_release_active_slot_inactive");
  if (serviceActive(runner, config.legacySource.serviceName)) fail("production_release_legacy_service_still_active");
  const upstream = await readManagedFile(config.nginx.upstreamConfigFile);
  if (!upstream.equals(Buffer.from(renderNginxUpstream(config, activeSlot)))) {
    fail("production_release_upstream_not_active_slot");
  }
  const site = await readManagedFile(config.nginx.configFile);
  if (!satisfiesProductionNginxContract(config, site.toString("utf8"))) {
    fail("production_release_live_nginx_contract_invalid");
  }
  const runtimeEnvironment = await inspectProductionReleaseEnvironment(config, activeSlot);
  if (expectedEnvironment
      && !sameProductionReleaseEnvironment(runtimeEnvironment, expectedEnvironment)) {
    fail("production_release_environment_changed_after_admission");
  }
  return runtimeEnvironment;
}

export async function verifyPreservedEmailSurface(request, fetchImpl = fetch) {
  const capabilitiesResponse = await boundedFetch(fetchImpl, `${request.gatewayUrl}/v2/capabilities`);
  if (!capabilitiesResponse?.ok) fail("production_release_email_capabilities_unavailable");
  let capabilities;
  try {
    capabilities = await capabilitiesResponse.json();
  } catch {
    fail("production_release_email_capabilities_invalid");
  }
  const auth = capabilities?.accountAuth;
  const binding = capabilities?.binding;
  if (auth?.enabled !== true
      || !Array.isArray(auth.providers)
      || auth.providers.length !== 1
      || auth.providers[0] !== "email_otp"
      || auth.android !== true
      || auth.macos !== true
      || auth.identityManagement !== false
      || auth.webAccountCenter !== false
      || auth.accountDeletion === true
      || auth.webSessions === true
      || binding?.enabled !== false
      || binding?.replacement !== false
      || binding?.maxActiveConnectorsPerAccount !== 1
      || Object.hasOwn(binding ?? {}, "supportsDeviceSelection")
      || Object.hasOwn(binding ?? {}, "supportsDeviceSharing")
      || Object.hasOwn(capabilities ?? {}, "desktopBootstrap")) {
    fail("production_release_email_capabilities_invalid");
  }
  const account = await boundedFetch(fetchImpl, `${request.gatewayUrl}/v2/account`);
  if (account?.status !== 401) fail("production_release_email_account_guard_invalid");
  const bindingRoute = await boundedFetch(fetchImpl, `${request.gatewayUrl}/v2/connector-binding`);
  const expectedBindingStatus = request.publicRoute === true ? 404 : 503;
  if (bindingRoute?.status !== expectedBindingStatus) fail("production_release_binding_route_must_stay_absent");
}

function preserveAccountSurface(smoke, runtimeEnvironment, fetchImpl) {
  if (runtimeEnvironment.mode !== "email_otp") return smoke;
  return async (request) => {
    await smoke({ ...request, expectedRuntimeMode: "email_otp" });
    await verifyPreservedEmailSurface(request, fetchImpl);
  };
}

async function boundedFetch(fetchImpl, url) {
  try {
    return await fetchImpl(url, { signal: AbortSignal.timeout(5_000) });
  } catch {
    return null;
  }
}

function serviceActive(runner, serviceName) {
  return runner.run("systemctl", ["is-active", "--quiet", `${serviceName}.service`], { allowFailure: true }).status === 0;
}

async function verifyFailedCandidateLiveState(config, journal, runner, sourceManifest) {
  await verifyReleaseInputs(config, journal.activeSlot, runner);
  if ((await readReleaseLink(config.paths.installRoot, "current")) !== releaseTarget(sourceManifest)) {
    fail("production_release_current_release_changed");
  }
  const candidate = config.slots[journal.candidateSlot];
  if (!candidate || serviceActive(runner, candidate.serviceName)) {
    fail("production_release_failed_candidate_still_active");
  }
  const listeners = runner.run("ss", ["-ltnH", "sport", "=", `:${candidate.gatewayPort}`], { allowFailure: true });
  if (listeners.status !== 0 || listeners.stdout.trim()) {
    fail("production_release_failed_candidate_port_in_use");
  }
  if (JSON.stringify(await productionCheckpoint(config)) !== JSON.stringify(journal.checkpoint)) {
    fail("production_release_failed_candidate_checkpoint_changed");
  }
}

async function productionCheckpoint(config) {
  return {
    currentReleaseTarget: await readReleaseLink(config.paths.installRoot, "current"),
    previousReleaseTarget: await readReleaseLink(config.paths.installRoot, "previous"),
    nginxConfigSha256: createHash("sha256").update(await readManagedFile(config.nginx.configFile)).digest("hex"),
    upstreamSha256: createHash("sha256").update(await readManagedFile(config.nginx.upstreamConfigFile)).digest("hex"),
  };
}

function releaseIdentity(manifest) {
  return {
    serverVersion: manifest.serverVersion,
    sourceCommit: manifest.sourceCommit,
    imageId: manifest.imageId,
    manifestSchemaVersion: manifest.schemaVersion,
    databaseSchemaVersion: manifest.releaseContract?.databaseSchemaVersion ?? null,
  };
}

async function readManagedFile(filePath) {
  let info;
  try {
    info = await lstat(filePath);
  } catch (error) {
    fail(error?.code === "ENOENT" ? `production_release_file_missing=${path.basename(filePath)}` : technical(error));
  }
  if (info.isSymbolicLink() || !info.isFile() || info.size > FILE_LIMIT) {
    fail(`production_release_file_unsafe=${path.basename(filePath)}`);
  }
  return readFile(filePath);
}

async function readReleaseLink(installRoot, name) {
  const linkPath = path.join(installRoot, name);
  try {
    const info = await lstat(linkPath);
    if (!info.isSymbolicLink()) fail(`production_release_${name}_not_symlink`);
    const target = await readlink(linkPath);
    if (!RELEASE_TARGET.test(target)) fail(`production_release_${name}_target_invalid`);
    return target;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(`production_release_${name}_missing`);
  }
}

function releaseTarget(manifest) {
  return `releases/${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
}

function technical(error) {
  return error instanceof Error ? error.technicalCause || error.message : String(error);
}

function fail(cause) {
  throw new OpsError("productionRelease", cause, "production_release_admission");
}
