import { hostname as systemHostname } from "node:os";
import { createOpsError, OpsError } from "./errors.mjs";
import { loadPostgresqlBackupStatus } from "./production-monitor-config.mjs";
import { createCommandRunner } from "./system.mjs";

export async function monitorProduction(config, options = {}) {
  const runner = options.runner ?? createCommandRunner();
  const now = options.now?.() ?? new Date();
  const confirmation = `production:${config.host.hostname}`;
  if (options.confirmation !== confirmation) {
    throw new OpsError("config", "production_monitor_confirmation_required", "production_monitor_authorize");
  }
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new OpsError("config", "production_monitor_clock_invalid", "production_monitor_authorize");
  }

  const checks = [
    hostIdentityCheck(config, {
      platform: options.platform ?? process.platform,
      architecture: options.architecture ?? process.arch,
      hostname: options.hostname ?? systemHostname(),
    }),
    diskCapacityCheck(config, runner),
    await backupFreshnessCheck(config, now),
  ];
  const alerts = checks.filter((entry) => entry.status !== "pass");
  const result = {
    ok: alerts.length === 0,
    command: "production-monitor",
    environment: config.environment,
    hostname: config.host.hostname,
    checkedAt: now.toISOString(),
    checks,
  };
  if (alerts.length > 0) {
    result.error = createOpsError(
      "monitoring",
      `alerts=${alerts.map((entry) => `${entry.id}:${entry.status}`).join(",")}`,
      "production_monitor_alert",
    );
  }
  return result;
}

function hostIdentityCheck(config, actual) {
  const ok = actual.platform === "linux"
    && actual.architecture === "x64"
    && actual.hostname === config.host.hostname;
  return check("host_identity", ok ? "pass" : "critical", ok ? "expected_linux_amd64_host" : "host_identity_mismatch");
}

function diskCapacityCheck(config, runner) {
  try {
    const result = runner.run("df", ["-Pk", "--", config.host.diskMount], { allowFailure: true });
    if (result.status !== 0) return check("disk_capacity", "critical", "disk_capacity_unavailable");
    const fields = result.stdout.trim().split("\n").at(-1)?.trim().split(/\s+/) ?? [];
    const availableKiB = Number(fields[3]);
    const freeDiskMiB = Math.floor(availableKiB / 1024);
    if (!Number.isSafeInteger(freeDiskMiB) || freeDiskMiB < 0 || fields.at(-1) !== config.host.diskMount) {
      return check("disk_capacity", "critical", "disk_capacity_invalid");
    }
    if (freeDiskMiB < config.host.criticalFreeDiskMiB) {
      return check("disk_capacity", "critical", "free_disk_below_critical", { freeDiskMiB });
    }
    if (freeDiskMiB < config.host.warningFreeDiskMiB) {
      return check("disk_capacity", "warning", "free_disk_below_warning", { freeDiskMiB });
    }
    return check("disk_capacity", "pass", "free_disk_above_warning", { freeDiskMiB });
  } catch {
    return check("disk_capacity", "critical", "disk_capacity_inspection_failed");
  }
}

async function backupFreshnessCheck(config, now) {
  try {
    const status = await loadPostgresqlBackupStatus(config.backup.statusFile);
    const completedAt = Date.parse(status.backupCompletedAt);
    const copiedAt = Date.parse(status.offHostCopiedAt);
    const current = now.getTime();
    const ageMs = current - completedAt;
    const expected = status.sourceHostname === config.host.hostname
      && status.postgresqlMajorVersion === config.backup.expectedPostgresqlMajorVersion
      && status.databaseSchemaVersion === config.backup.expectedDatabaseSchemaVersion
      && status.encryptedBytes >= config.backup.minimumEncryptedBytes
      && status.offHostBytes === status.encryptedBytes
      && status.offHostSha256 === status.artifactSha256
      && status.offHostStorageId !== status.sourceHostname;
    const timelineValid = completedAt <= copiedAt && copiedAt <= current && completedAt <= current;
    if (!expected) return check("database_backup", "critical", "backup_identity_or_size_mismatch");
    if (!timelineValid) return check("database_backup", "critical", "backup_timeline_invalid");
    const ageMinutes = Math.floor(ageMs / (60 * 1000));
    if (ageMs > config.backup.maximumAgeHours * 60 * 60 * 1000) {
      return check("database_backup", "critical", "off_host_backup_stale", { ageMinutes });
    }
    return check("database_backup", "pass", "fresh_encrypted_off_host_backup", { ageMinutes });
  } catch {
    return check("database_backup", "critical", "backup_status_missing_or_invalid");
  }
}

function check(id, status, detail, metrics = undefined) {
  return metrics ? { id, status, detail, metrics } : { id, status, detail };
}
