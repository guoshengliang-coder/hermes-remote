import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, cp, mkdir, mkdtemp, readFile, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { hostname, tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";
import {
  loadPostgresqlBackupStatus,
  loadProductionMonitorConfig,
} from "../../ops/lib/production-monitor-config.mjs";
import { monitorProduction } from "../../ops/lib/production-monitor.mjs";

test("production monitor config and backup status contracts are strict and production-only", async (t) => {
  const fixture = await createFixture(t);
  const example = JSON.parse(await readFile("ops/production.monitor.example.json", "utf8"));
  const configSchema = JSON.parse(await readFile("ops/hermesctl-production-monitor-config.schema.json", "utf8"));
  const statusSchema = JSON.parse(await readFile("ops/postgresql-backup-status.schema.json", "utf8"));
  assert.equal(example.environment, "production");
  assert.equal(configSchema.additionalProperties, false);
  assert.equal(configSchema.properties.environment.const, "production");
  assert.equal(configSchema.properties.host.properties.diskMount.const, "/");
  assert.equal(configSchema.properties.backup.properties.expectedPostgresqlMajorVersion.const, 18);
  assert.equal(statusSchema.additionalProperties, false);
  assert.equal(statusSchema.properties.kind.const, "hermes-go-postgresql-backup-status-v1");

  const config = await loadProductionMonitorConfig(fixture.configPath);
  assert.equal(config.host.hostname, "prod-host");
  assert.equal(config.host.criticalFreeDiskMiB, 10240);
  const status = await loadPostgresqlBackupStatus(fixture.statusPath);
  assert.equal(status.databaseSchemaVersion, 7);

  await writeJson(fixture.configPath, { ...fixture.config, environment: "staging" });
  await assert.rejects(() => loadProductionMonitorConfig(fixture.configPath), isOpsCode("HR-OPS-001"));
  await writeJson(fixture.configPath, { ...fixture.config, unexpected: true });
  await assert.rejects(() => loadProductionMonitorConfig(fixture.configPath), isOpsCode("HR-OPS-001"));
  await writeJson(fixture.configPath, {
    ...fixture.config,
    host: { ...fixture.config.host, criticalFreeDiskMiB: fixture.config.host.warningFreeDiskMiB },
  });
  await assert.rejects(() => loadProductionMonitorConfig(fixture.configPath), isOpsCode("HR-OPS-001"));

  await writeJson(fixture.statusPath, { ...fixture.status, unexpected: true });
  await assert.rejects(() => loadPostgresqlBackupStatus(fixture.statusPath), isOpsCode("HR-OPS-012"));
  const statusLink = path.join(fixture.base, "status-link.json");
  await symlink(fixture.statusPath, statusLink);
  await assert.rejects(() => loadPostgresqlBackupStatus(statusLink), isOpsCode("HR-OPS-012"));
});

test("production monitor passes with sufficient root disk and a fresh verified off-host backup status", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadProductionMonitorConfig(fixture.configPath);
  const runner = diskRunner(40960);
  const result = await monitorProduction(config, {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    now: () => new Date("2026-09-04T12:00:00.000Z"),
    runner,
  });

  assert.equal(result.ok, true);
  assert.deepEqual(result.checks.map(({ id, status }) => [id, status]), [
    ["host_identity", "pass"],
    ["disk_capacity", "pass"],
    ["database_backup", "pass"],
  ]);
  assert.deepEqual(runner.calls, [["df", "-Pk", "--", "/"]]);
  assert.equal(JSON.stringify(result).includes(fixture.base), false);
  assert.equal(JSON.stringify(result).includes(fixture.status.artifactSha256), false);
  assert.equal(JSON.stringify(result).includes(fixture.status.offHostStorageId), false);
});

test("production monitor emits HR-OPS-012 for warning disk and stale backup without changing the host", async (t) => {
  const fixture = await createFixture(t, {
    status: { backupCompletedAt: "2026-09-01T00:00:00.000Z", offHostCopiedAt: "2026-09-01T00:05:00.000Z" },
  });
  const config = await loadProductionMonitorConfig(fixture.configPath);
  const runner = diskRunner(15360);
  const result = await monitorProduction(config, {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    now: () => new Date("2026-09-04T12:00:00.000Z"),
    runner,
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "HR-OPS-012");
  assert.deepEqual(result.checks.map(({ id, status }) => [id, status]), [
    ["host_identity", "pass"],
    ["disk_capacity", "warning"],
    ["database_backup", "critical"],
  ]);
  assert.equal(result.checks[2].detail, "off_host_backup_stale");
  assert.deepEqual(runner.calls, [["df", "-Pk", "--", "/"]]);

  const critical = await monitorProduction(config, { ...resultOptions(), runner: diskRunner(5120) });
  assert.equal(findCheck(critical, "disk_capacity").status, "critical");
});

test("production monitor fails closed for missing, future, and mismatched backup status", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadProductionMonitorConfig(fixture.configPath);
  const options = {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    now: () => new Date("2026-09-04T12:00:00.000Z"),
    runner: diskRunner(40960),
  };

  await writeJson(fixture.statusPath, { ...fixture.status, sourceHostname: "other-host" });
  let result = await monitorProduction(config, options);
  assert.equal(findCheck(result, "database_backup").detail, "backup_identity_or_size_mismatch");

  await writeJson(fixture.statusPath, { ...fixture.status, offHostSha256: "b".repeat(64) });
  result = await monitorProduction(config, options);
  assert.equal(findCheck(result, "database_backup").detail, "backup_identity_or_size_mismatch");

  await writeJson(fixture.statusPath, {
    ...fixture.status,
    backupCompletedAt: "2026-09-04T13:00:00.000Z",
    offHostCopiedAt: "2026-09-04T13:05:00.000Z",
  });
  result = await monitorProduction(config, options);
  assert.equal(findCheck(result, "database_backup").detail, "backup_timeline_invalid");

  await rm(fixture.statusPath);
  result = await monitorProduction(config, options);
  assert.equal(findCheck(result, "database_backup").detail, "backup_status_missing_or_invalid");
  await assert.rejects(
    () => monitorProduction(config, { ...options, confirmation: "production:wrong-host" }),
    isOpsCode("HR-OPS-001"),
  );
});

test("production monitor systemd templates generate local daemon.alert without service mutation", async () => {
  const service = await readFile("deploy/hermes-go-production-monitor.service.template", "utf8");
  const timer = await readFile("deploy/hermes-go-production-monitor.timer.template", "utf8");
  const alert = await readFile("deploy/hermes-go-production-monitor-alert.service.template", "utf8");
  const rootPackage = JSON.parse(await readFile("package.json", "utf8"));
  assert.match(service, /scripts\/production-monitor\.mjs/);
  assert.doesNotMatch(service, /hermesctl\.mjs/);
  assert.equal(rootPackage.scripts["ops:production-monitor"], "node scripts/production-monitor.mjs");
  assert.match(service, /--confirm production:__PRODUCTION_HOSTNAME__/);
  assert.match(service, /OnFailure=hermes-go-production-monitor-alert\.service/);
  assert.match(timer, /OnCalendar=\*:0\/15/);
  assert.match(timer, /Persistent=true/);
  assert.match(alert, /--priority daemon\.alert/);
  assert.match(alert, /HR-OPS-012/);
  for (const content of [service, timer, alert]) {
    assert.doesNotMatch(content, /^ExecStart=.*\/(?:systemctl|docker|psql|pg_dump)\b/m);
    assert.doesNotMatch(content, /^ExecStart=.*\b(?:restart|stop|reload|install|rm|run|exec)\b/m);
  }
});

test("production monitor entrypoint runs from an isolated dependency-free snapshot", async (t) => {
  const fixture = await createFixture(t);
  const isolated = path.join(fixture.base, "isolated");
  const bin = path.join(isolated, "bin");
  await mkdir(path.join(isolated, "scripts"), { recursive: true });
  await mkdir(path.join(isolated, "ops", "lib"), { recursive: true });
  await mkdir(bin, { recursive: true });
  await cp("scripts/production-monitor.mjs", path.join(isolated, "scripts", "production-monitor.mjs"));
  for (const name of [
    "config.mjs",
    "errors.mjs",
    "production-monitor-config.mjs",
    "production-monitor.mjs",
    "system.mjs",
  ]) {
    await cp(path.join("ops", "lib", name), path.join(isolated, "ops", "lib", name));
  }

  const actualHostname = hostname();
  const now = Date.now();
  await writeJson(fixture.statusPath, {
    ...fixture.status,
    sourceHostname: actualHostname,
    backupCompletedAt: new Date(now - 5 * 60 * 1000).toISOString(),
    offHostCopiedAt: new Date(now - 4 * 60 * 1000).toISOString(),
  });
  await writeJson(fixture.configPath, {
    ...fixture.config,
    host: {
      ...fixture.config.host,
      hostname: actualHostname,
      warningFreeDiskMiB: 2048,
      criticalFreeDiskMiB: 1024,
    },
  });
  const fakeDf = path.join(bin, "df");
  await writeFile(fakeDf, "#!/bin/sh\nprintf 'Filesystem 1024-blocks Used Available Capacity Mounted on\\n/dev/test 100000000 1000000 41943040 3%% /\\n'\n", { mode: 0o700 });

  const entrypoint = path.join(isolated, "scripts", "production-monitor.mjs");
  const result = spawnSync(process.execPath, [
    entrypoint,
    "--config",
    fixture.configPath,
    "--confirm",
    `production:${actualHostname}`,
  ], {
    cwd: isolated,
    encoding: "utf8",
    env: { ...process.env, PATH: `${bin}:${process.env.PATH ?? ""}` },
  });

  assert.equal(result.stderr, "");
  const payload = JSON.parse(result.stdout);
  assert.equal(payload.command, "production-monitor");
  assert.deepEqual(payload.checks.slice(1).map(({ status }) => status), ["pass", "pass"]);
  if (process.platform === "linux" && process.arch === "x64") {
    assert.equal(result.status, 0, result.stdout);
    assert.equal(payload.ok, true);
    assert.equal(payload.checks[0].status, "pass");
  } else {
    assert.equal(result.status, 1, result.stdout);
    assert.equal(payload.ok, false);
    assert.equal(payload.checks[0].detail, "host_identity_mismatch");
  }

  const rejected = spawnSync(process.execPath, [entrypoint, "--config", fixture.configPath], {
    cwd: isolated,
    encoding: "utf8",
  });
  assert.equal(rejected.status, 1);
  assert.equal(rejected.stdout, "");
  const error = JSON.parse(rejected.stderr);
  assert.equal(error.code, "HR-OPS-001");
  assert.equal(error.retryable, true);
  assert.equal(error.stage, "arguments_parse");
});

test("production monitoring error is localized, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.monitoring;
  assert.equal(definition.code, "HR-OPS-012");
  assert.match(definition.summaryZh, /生产主机/);
  assert.match(definition.summaryEn, /Production disk/);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "inspect_production_monitor_alert_and_retry");
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.match(registry, /`HR-OPS-012`/);
});

async function createFixture(t, { status: statusOverrides = {} } = {}) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-monitor-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const state = path.join(base, "state");
  await mkdir(inputs, { recursive: true });
  await mkdir(state, { recursive: true });
  const statusPath = path.join(state, "latest-status.json");
  const status = {
    schemaVersion: 1,
    kind: "hermes-go-postgresql-backup-status-v1",
    sourceHostname: "prod-host",
    backupCompletedAt: "2026-09-04T10:00:00.000Z",
    offHostCopiedAt: "2026-09-04T10:05:00.000Z",
    artifactSha256: "a".repeat(64),
    encryptedBytes: 2 * 1024 * 1024,
    offHostSha256: "a".repeat(64),
    offHostBytes: 2 * 1024 * 1024,
    postgresqlMajorVersion: 18,
    databaseSchemaVersion: 7,
    offHostStorageId: "test-off-host-store",
    ...statusOverrides,
  };
  await writeJson(statusPath, status);
  const configPath = path.join(inputs, "production-monitor.json");
  const config = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    host: {
      hostname: "prod-host",
      diskMount: "/",
      warningFreeDiskMiB: 20480,
      criticalFreeDiskMiB: 10240,
    },
    backup: {
      statusFile: statusPath,
      maximumAgeHours: 36,
      minimumEncryptedBytes: 1024 * 1024,
      expectedPostgresqlMajorVersion: 18,
      expectedDatabaseSchemaVersion: 7,
    },
  };
  await writeJson(configPath, config);
  return { base, config, configPath, status, statusPath };
}

function diskRunner(freeDiskMiB) {
  const calls = [];
  return {
    calls,
    run(command, args) {
      calls.push([command, ...args]);
      if (command !== "df") throw new Error(`unexpected command ${command}`);
      return {
        status: 0,
        stdout: `Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/vda1 100000000 1000000 ${freeDiskMiB * 1024} 2% /\n`,
        stderr: "",
      };
    },
  };
}

function resultOptions() {
  return {
    confirmation: "production:prod-host",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    now: () => new Date("2026-09-04T12:00:00.000Z"),
  };
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

function findCheck(result, id) {
  return result.checks.find((entry) => entry.id === id);
}

function isOpsCode(code) {
  return (error) => error?.name === "OpsError"
    && OPS_ERROR_DEFINITIONS[error.kind]?.code === code;
}
