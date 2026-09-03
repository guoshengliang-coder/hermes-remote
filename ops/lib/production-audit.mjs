import { createHash } from "node:crypto";
import { hostname as systemHostname } from "node:os";
import { readFile } from "node:fs/promises";
import { assertRegularFile, sha256File } from "./config.mjs";
import { createOpsError, OpsError } from "./errors.mjs";
import { loadProductionEvidence } from "./production-config.mjs";
import { createCommandRunner } from "./system.mjs";

const REQUIRED_COMMANDS = Object.freeze([
  "curl",
  "df",
  "docker",
  "nginx",
  "pg_dump",
  "pg_restore",
  "psql",
  "ss",
  "systemctl",
]);
const MAXIMUM_EVIDENCE_AGE_MS = 30 * 24 * 60 * 60 * 1000;

export async function auditProductionReadiness(config, targetManifest, options = {}) {
  const runner = options.runner ?? createCommandRunner();
  const now = options.now ?? (() => new Date());
  const confirmation = `production:${config.publicRoute.serverName}`;
  if (options.confirmation !== confirmation) {
    throw new OpsError("config", "production_audit_confirmation_required", "production_audit_authorize");
  }

  const checks = [];
  checks.push(hostIdentityCheck(config, {
    platform: options.platform ?? process.platform,
    architecture: options.architecture ?? process.arch,
    hostname: options.hostname ?? systemHostname(),
  }));
  checks.push(await resourceCheck(config, runner, options.readProcFile ?? readFile));
  checks.push(dependencyCheck(runner));
  checks.push(targetArtifactCheck(config, targetManifest));
  checks.push(await legacyIdentityCheck(config, runner));
  checks.push(routingCheck(config, runner));
  checks.push(dockerCheck(runner));
  checks.push(postgresqlCheck(config, targetManifest, runner));
  checks.push(await evidenceCheck(
    config.evidence.legacyRecoveryManifest,
    "hermes-go-legacy-recovery-v1",
    config.host.hostname,
    now(),
    "legacy_recovery",
    {
      identityDigest: createHash("sha256")
        .update(JSON.stringify([...config.legacyGateway.identityFiles].sort((left, right) => left.path.localeCompare(right.path))))
        .digest("hex"),
    },
  ));
  checks.push(await evidenceCheck(
    config.evidence.databaseRestoreManifest,
    "hermes-go-postgresql-restore-v1",
    config.host.hostname,
    now(),
    "off_host_database_restore",
    {
      databaseSchemaVersion: targetManifest.releaseContract?.databaseSchemaVersion,
      postgresqlMajorVersion: config.postgresql.majorVersion,
    },
  ));

  const blocked = checks.filter((entry) => entry.status === "blocked").map((entry) => entry.id);
  const result = {
    ok: blocked.length === 0,
    command: "production-audit",
    environment: config.environment,
    serverName: config.publicRoute.serverName,
    target: {
      serverVersion: targetManifest.serverVersion,
      sourceCommit: targetManifest.sourceCommit,
      imageId: targetManifest.imageId,
    },
    checks,
  };
  if (blocked.length > 0) {
    result.error = createOpsError("promotion", `blocked_checks=${blocked.join(",")}`, "production_audit_blocked");
  }
  return result;
}

function hostIdentityCheck(config, actual) {
  const ok = actual.platform === "linux"
    && actual.architecture === "x64"
    && actual.hostname === config.host.hostname;
  return check("host_identity", ok, ok ? "expected_linux_amd64_host" : "host_identity_mismatch");
}

function resourceCheck(config, runner, readProcFile) {
  try {
    const disk = runner.run("df", ["-Pk", "--", "/"], { allowFailure: true });
    if (disk.status !== 0) return check("host_resources", false, "disk_capacity_unavailable");
    const lines = disk.stdout.trim().split("\n");
    const fields = lines.at(-1)?.trim().split(/\s+/) ?? [];
    const freeDiskMiB = Math.floor(Number(fields[3]) / 1024);
    const memoryText = readProcFile("/proc/meminfo", "utf8");
    return Promise.resolve(memoryText).then((value) => {
      const match = String(value).match(/^MemAvailable:\s+(\d+)\s+kB$/m);
      const availableMemoryMiB = match ? Math.floor(Number(match[1]) / 1024) : Number.NaN;
      const ok = Number.isSafeInteger(freeDiskMiB)
        && Number.isSafeInteger(availableMemoryMiB)
        && freeDiskMiB >= config.host.minimumFreeDiskMiB
        && availableMemoryMiB >= config.host.minimumAvailableMemoryMiB;
      return check("host_resources", ok, ok ? "capacity_thresholds_met" : "capacity_thresholds_not_met");
    }).catch(() => check("host_resources", false, "memory_capacity_unavailable"));
  } catch {
    return check("host_resources", false, "capacity_inspection_failed");
  }
}

function dependencyCheck(runner) {
  const missing = REQUIRED_COMMANDS.filter((command) => (
    runner.run("which", [command], { allowFailure: true }).status !== 0
  ));
  return check("dependencies", missing.length === 0, missing.length === 0 ? "required_commands_available" : `missing_${missing.join("_")}`);
}

function targetArtifactCheck(config, manifest) {
  const release = manifest.releaseContract;
  const ok = manifest.schemaVersion === 2
    && release?.manifestVersion >= 2
    && release?.rollbackSupported === true
    && release?.maintenanceRequired === true
    && release?.supportedPostgresqlMajors?.includes(config.postgresql.majorVersion);
  return check("target_artifact", ok, ok ? "production_contract_verified" : "production_contract_incomplete");
}

async function legacyIdentityCheck(config, runner) {
  try {
    const service = runner.run(
      "systemctl",
      ["is-active", "--quiet", `${config.legacyGateway.serviceName}.service`],
      { allowFailure: true },
    );
    if (service.status !== 0) return check("legacy_identity", false, "legacy_service_inactive");
    await assertRegularFile(config.legacyGateway.stateFile, "legacy_gateway_state");
    for (const expected of config.legacyGateway.identityFiles) {
      await assertRegularFile(expected.path, "legacy_gateway_identity");
      if (await sha256File(expected.path) !== expected.sha256) {
        return check("legacy_identity", false, "legacy_runtime_hash_mismatch");
      }
    }
    return check("legacy_identity", true, "legacy_runtime_exact");
  } catch {
    return check("legacy_identity", false, "legacy_runtime_unverifiable");
  }
}

function routingCheck(config, runner) {
  const nginxActive = runner.run("systemctl", ["is-active", "--quiet", "nginx.service"], { allowFailure: true });
  const nginxConfig = runner.run("nginx", ["-t"], { allowFailure: true });
  const sockets = runner.run("ss", ["-ltnH"], { allowFailure: true });
  const health = runner.run("curl", [
    "--silent",
    "--show-error",
    "--fail",
    "--max-time",
    "10",
    publicHealthUrl(config),
  ], { allowFailure: true });
  if ([nginxActive, nginxConfig, sockets, health].some((result) => result.status !== 0)) {
    return check("public_routing", false, "public_route_unhealthy");
  }
  const listeners = listenerEndpoints(sockets.stdout, config.legacyGateway.gatewayPort);
  if (listeners.length === 0) return check("public_routing", false, "legacy_gateway_listener_missing");
  if (!listeners.every((endpoint) => loopbackEndpoint(endpoint, config.legacyGateway.gatewayPort))) {
    return check("public_routing", false, "legacy_gateway_port_publicly_bound");
  }
  return check("public_routing", true, "nginx_public_gateway_loopback_only");
}

function dockerCheck(runner) {
  const result = runner.run("docker", ["info", "--format", "{{.OSType}}/{{.Architecture}}"], { allowFailure: true });
  const ok = result.status === 0 && new Set(["linux/amd64", "linux/x86_64"]).has(result.stdout.trim());
  return check("docker", ok, ok ? "docker_linux_amd64_ready" : "docker_linux_amd64_unavailable");
}

function postgresqlCheck(config, targetManifest, runner) {
  const requiredMajor = config.postgresql.majorVersion;
  const versionCommands = ["psql", "pg_dump", "pg_restore"].map((command) => (
    runner.run(command, ["--version"], { allowFailure: true })
  ));
  if (versionCommands.some((result) => result.status !== 0 || majorVersion(result.stdout) !== requiredMajor)) {
    return check("postgresql", false, "postgresql_client_version_invalid");
  }
  if (!targetManifest.releaseContract?.supportedPostgresqlMajors?.includes(requiredMajor)) {
    return check("postgresql", false, "target_does_not_support_postgresql_major");
  }
  const service = runner.run(
    "systemctl",
    ["is-active", "--quiet", `${config.postgresql.serviceName}.service`],
    { allowFailure: true },
  );
  const sockets = runner.run("ss", ["-ltnH"], { allowFailure: true });
  const listeners = sockets.status === 0 ? listenerEndpoints(sockets.stdout, config.postgresql.port) : [];
  const ok = service.status === 0
    && listeners.length > 0
    && listeners.every((endpoint) => loopbackEndpoint(endpoint, config.postgresql.port));
  return check("postgresql", ok, ok ? "postgresql_expected_major_loopback_only" : "postgresql_service_or_listener_invalid");
}

async function evidenceCheck(filePath, kind, hostname, now, id, expectedSubject) {
  try {
    const evidence = await loadProductionEvidence(filePath, kind);
    const created = Date.parse(evidence.createdAt);
    const restored = Date.parse(evidence.restoredAt);
    const current = now.getTime();
    const fresh = evidence.sourceHostname === hostname
      && created <= restored
      && restored <= current
      && current - restored <= MAXIMUM_EVIDENCE_AGE_MS
      && JSON.stringify(evidence.subject) === JSON.stringify(expectedSubject);
    return check(id, fresh, fresh ? "fresh_off_host_restore_verified" : "restore_evidence_stale_or_mismatched");
  } catch {
    return check(id, false, "restore_evidence_missing_or_invalid");
  }
}

function listenerEndpoints(output, port) {
  return String(output)
    .split("\n")
    .map((line) => line.trim().split(/\s+/)[3])
    .filter((endpoint) => typeof endpoint === "string" && endpoint.endsWith(`:${port}`));
}

function loopbackEndpoint(endpoint, port) {
  return new Set([`127.0.0.1:${port}`, `[::1]:${port}`, `::1:${port}`]).has(endpoint);
}

function majorVersion(output) {
  const match = String(output).match(/\b(\d+)(?:\.\d+)?\b/);
  return match ? Number(match[1]) : Number.NaN;
}

function publicHealthUrl(config) {
  const port = config.publicRoute.listenPort === 443 ? "" : `:${config.publicRoute.listenPort}`;
  return `https://${config.publicRoute.serverName}${port}${config.publicRoute.healthPath}`;
}

function check(id, ok, detail) {
  return { id, status: ok ? "pass" : "blocked", detail };
}
