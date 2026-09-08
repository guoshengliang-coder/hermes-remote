import { createHash, randomUUID } from "node:crypto";
import { lstat, readFile, readlink, rename, symlink, unlink } from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { executeDeployment } from "./deploy-command.mjs";
import { OpsError } from "./errors.mjs";
import { loadProductionEvidence } from "./production-config.mjs";
import { atomicWrite, createCommandRunner, ensureManagedDirectory } from "./system.mjs";

const MAXIMUM_EVIDENCE_AGE_MS = 30 * 24 * 60 * 60 * 1000;

export async function executeManagedBaseline(config, targetManifest, options = {}) {
  const now = options.now ?? (() => new Date());
  const runner = options.runner ?? createCommandRunner();
  const runId = options.runId ?? randomUUID();
  const ownership = options.ownership ?? {
    host: { uid: 0, gid: 0 },
    container: { uid: 1000, gid: 1000 },
    secret: { uid: 0, gid: 1000 },
  };
  const sourceManifest = await verifyManagedBaselineAdmission(config, targetManifest, {
    ...options,
    now,
    runner,
  });
  const seed = options.seedLegacyBaseline ?? seedLegacyBaseline;
  await seed(config, sourceManifest, { ownership });

  const execute = options.executeDeployment ?? executeDeployment;
  let result;
  try {
    result = await execute(config, targetManifest, {
      operation: "deploy",
      confirmation: options.confirmation,
      candidateSmoke: options.candidateSmoke,
      publicSmoke: options.publicSmoke,
      legacySmoke: options.legacySmoke,
      sourcePreflight: () => verifyLegacyInputs(config, runner),
      runner,
      platform: options.platform,
      architecture: options.architecture,
      getUid: options.getUid,
      ownership,
      fetchImpl: options.fetchImpl,
      sleep: options.sleep,
      now,
      runId,
      sourceManifest,
      authorization: "production-managed-baseline",
    });
  } catch (error) {
    throw new OpsError(
      "managedBaseline",
      error instanceof Error ? `${error.stage ?? "managed_baseline_execute"}:${error.technicalCause ?? error.message}` : error,
      error?.stage ?? "managed_baseline_execute",
    );
  }
  return {
    ...result,
    command: "managed-baseline",
    legacyRollbackPoint: releaseTarget(sourceManifest),
  };
}

export async function verifyManagedBaselineAdmission(config, targetManifest, options = {}) {
  try {
    if (config.managedBaseline !== true || config.environment !== "production") fail("managed_baseline_config_required");
    if (options.confirmation !== `production:${config.host.hostname}`) fail("managed_baseline_confirmation_required");
    if ((options.getUid ?? (() => process.getuid?.()))() !== 0) fail("managed_baseline_requires_root");
    if ((options.platform ?? process.platform) !== "linux"
        || (options.architecture ?? process.arch) !== "x64"
        || (options.hostname ?? systemHostname()) !== config.host.hostname) {
      fail("managed_baseline_host_mismatch");
    }
    if (typeof options.candidateSmoke !== "function"
        || typeof options.publicSmoke !== "function"
        || typeof options.legacySmoke !== "function") {
      fail("managed_baseline_smoke_callbacks_required");
    }
    if (config.database !== null
        || config.gateway.accountAuthEnabled !== false
        || config.gateway.accountBindingEnabled !== false) {
      fail("managed_baseline_account_and_database_must_stay_disabled");
    }
    const release = targetManifest.releaseContract;
    if (![2, 3].includes(targetManifest.schemaVersion)
        || release?.maintenanceRequired !== true
        || release?.rollbackSupported !== true
        || release.minimumSourceVersion !== config.legacySource.compatibilityVersion) {
      throw new OpsError("compatibility", "managed_baseline_target_contract_invalid", "managed_baseline_admission");
    }

    const identityDigest = legacyIdentityDigest(config.legacySource.identityFiles);
    await verifyLegacyInputs(config, options.runner);
    const evidence = await loadProductionEvidence(
      config.legacySource.recoveryEvidence,
      "hermes-go-legacy-recovery-v1",
    );
    const timestamp = options.now ? options.now().getTime() : Date.now();
    const createdAt = Date.parse(evidence.createdAt);
    const restoredAt = Date.parse(evidence.restoredAt);
    if (evidence.sourceHostname !== config.host.hostname
        || evidence.subject.identityDigest !== identityDigest
        || createdAt > restoredAt
        || restoredAt > timestamp
        || timestamp - restoredAt > MAXIMUM_EVIDENCE_AGE_MS) {
      fail("managed_baseline_recovery_evidence_invalid");
    }
    await options.legacySmoke({
      gatewayUrl: publicUrl(config),
      appTokenSource: config.secrets.appTokenSource,
      expectedDeviceId: config.gateway.defaultDeviceId,
      publicRoute: true,
      recovery: true,
    });
    return legacySourceManifest(config, identityDigest);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  }
}

async function verifyLegacyInputs(config, runner) {
  if (!runner) fail("managed_baseline_runner_required");
  if (runner.run(
    "systemctl",
    ["is-active", "--quiet", `${config.legacySource.serviceName}.service`],
    { allowFailure: true },
  ).status !== 0) fail("managed_baseline_legacy_service_inactive");
  for (const expected of config.legacySource.identityFiles) {
    const info = await lstat(expected.path);
    if (info.isSymbolicLink() || !info.isFile() || await sha256(expected.path) !== expected.sha256) {
      fail("managed_baseline_legacy_identity_mismatch");
    }
  }
  const candidateInfo = await lstat(config.nginx.candidateConfigSource);
  const candidateContent = candidateInfo.isFile()
    ? await readFile(config.nginx.candidateConfigSource)
    : Buffer.alloc(0);
  if (candidateInfo.isSymbolicLink() || !candidateInfo.isFile()
      || (candidateInfo.mode & 0o022) !== 0
      || candidateInfo.size < 2
      || candidateInfo.size > 1024 * 1024
      || createHash("sha256").update(candidateContent).digest("hex") !== config.nginx.candidateConfigSha256) {
    fail("managed_baseline_nginx_candidate_identity_mismatch");
  }
  const content = candidateContent.toString("utf8");
  const include = `include ${config.nginx.upstreamConfigFile};`;
  if (content.split(include).length !== 2
      || !hasExactServerName(content, config.nginx.serverName)
      || !content.includes("proxy_pass http://hermes_go_gateway_production")
      || content.includes(`proxy_pass http://127.0.0.1:${config.legacySource.gatewayPort}`)) {
    fail("managed_baseline_nginx_candidate_contract_invalid");
  }
}

export async function seedLegacyBaseline(config, sourceManifest, { ownership } = {}) {
  const hostOwner = ownership?.host ?? { uid: 0, gid: 0 };
  const releaseDirectory = path.join(config.paths.installRoot, releaseTarget(sourceManifest));
  const descriptorPath = path.join(releaseDirectory, "legacy.manifest.json");
  const descriptor = {
    schemaVersion: 1,
    kind: "hermes-go-managed-legacy-v1",
    compatibilityVersion: sourceManifest.serverVersion,
    identityDigest: sourceManifest.imageId.slice("sha256:".length),
    serviceName: config.legacySource.serviceName,
    stateDirectory: config.legacySource.stateDirectory,
  };
  await ensureManagedDirectory(config.paths.installRoot, 0o755, hostOwner);
  await ensureManagedDirectory(path.join(config.paths.installRoot, "releases"), 0o755, hostOwner);
  await ensureManagedDirectory(releaseDirectory, 0o755, hostOwner);
  await installImmutable(descriptorPath, `${JSON.stringify(descriptor, null, 2)}\n`, hostOwner);
  await installInitialCurrent(config.paths.installRoot, releaseTarget(sourceManifest));
}

export function legacySourceManifest(config, identityDigest = legacyIdentityDigest(config.legacySource.identityFiles)) {
  return {
    schemaVersion: 1,
    serverVersion: config.legacySource.compatibilityVersion,
    sourceCommit: identityDigest.slice(0, 40),
    imageId: `sha256:${identityDigest}`,
  };
}

export function legacyIdentityDigest(identityFiles) {
  return createHash("sha256")
    .update(JSON.stringify([...identityFiles].sort((left, right) => left.path.localeCompare(right.path))))
    .digest("hex");
}

async function installImmutable(filePath, content, owner) {
  try {
    const info = await lstat(filePath);
    if (info.isSymbolicLink() || !info.isFile() || (await readFile(filePath, "utf8")) !== content) {
      fail("managed_baseline_legacy_descriptor_conflict");
    }
    return;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
  await atomicWrite(filePath, content, 0o644, owner);
}

async function installInitialCurrent(installRoot, target) {
  const current = path.join(installRoot, "current");
  try {
    const info = await lstat(current);
    if (!info.isSymbolicLink() || await readlink(current) !== target) fail("managed_baseline_current_conflict");
    return;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
  const temporary = path.join(installRoot, `.current.${randomUUID()}.tmp`);
  await symlink(target, temporary);
  try {
    await rename(temporary, current);
  } catch (error) {
    await unlink(temporary).catch(() => {});
    throw error;
  }
}

function releaseTarget(manifest) {
  return `releases/${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`;
}

function publicUrl(config) {
  return `https://${config.nginx.serverName}${config.nginx.listenPort === 443 ? "" : `:${config.nginx.listenPort}`}`;
}

async function sha256(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

function hasExactServerName(content, expected) {
  return [...content.matchAll(/\bserver_name\s+([A-Za-z0-9.-]+)\s*;/g)]
    .some((match) => match[1] === expected);
}

function fail(cause) {
  throw new OpsError("managedBaseline", cause, "managed_baseline_admission");
}
