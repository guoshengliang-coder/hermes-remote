import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { OPS_ERROR_DEFINITIONS } from "../../ops/lib/errors.mjs";
import { auditProductionReadiness } from "../../ops/lib/production-audit.mjs";
import { loadProductionAuditConfig, loadProductionEvidence } from "../../ops/lib/production-config.mjs";

test("production audit config is separate, strict, and production-only", async (t) => {
  const fixture = await createFixture(t);
  const example = JSON.parse(await readFile("ops/production.audit.example.json", "utf8"));
  const schema = JSON.parse(await readFile("ops/hermesctl-production-audit-config.schema.json", "utf8"));
  assert.equal(example.environment, "production");
  assert.equal(schema.additionalProperties, false);
  assert.equal(schema.properties.environment.const, "production");
  assert.equal(schema.properties.postgresql.properties.majorVersion.const, 18);
  assert.equal(schema.properties.postgresql.properties.port.const, 5432);

  const config = await loadProductionAuditConfig(fixture.configPath);
  assert.equal(config.host.hostname, "prod-host");
  assert.equal(config.legacyGateway.identityFiles.length, 2);

  await writeJson(fixture.configPath, { ...fixture.config, environment: "staging" });
  await assert.rejects(() => loadProductionAuditConfig(fixture.configPath), isOpsCode("HR-OPS-001"));
  await writeJson(fixture.configPath, { ...fixture.config, unexpected: true });
  await assert.rejects(() => loadProductionAuditConfig(fixture.configPath), isOpsCode("HR-OPS-001"));
  await writeJson(fixture.configPath, {
    ...fixture.config,
    postgresql: { ...fixture.config.postgresql, majorVersion: 17 },
  });
  await assert.rejects(() => loadProductionAuditConfig(fixture.configPath), isOpsCode("HR-OPS-001"));
});

test("production audit passes only with exact host, runtime, loopback services, and fresh restore evidence", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadProductionAuditConfig(fixture.configPath);
  const runner = productionRunner();
  const result = await auditProductionReadiness(config, targetManifest(), {
    confirmation: "production:gateway.example.com",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    runner,
    now: () => new Date("2026-09-03T12:00:00.000Z"),
    readProcFile: async () => "MemAvailable:       4194304 kB\n",
  });

  assert.equal(result.ok, true);
  assert.equal(result.checks.length, 10);
  assert.equal(result.checks.every((entry) => entry.status === "pass"), true);
  assert.equal(JSON.stringify(result).includes(fixture.base), false);
  assert.equal(runner.calls.every(readOnlyCommand), true);
});

test("production audit aggregates the current HK blockers without mutating the service", async (t) => {
  const fixture = await createFixture(t, { evidence: false });
  const config = await loadProductionAuditConfig(fixture.configPath);
  const runner = productionRunner({
    missing: new Set(["docker", "psql", "pg_dump", "pg_restore"]),
    dockerReady: false,
    postgresReady: false,
    sockets: [
      "LISTEN 0 511 0.0.0.0:8444 0.0.0.0:*",
    ].join("\n"),
  });
  const result = await auditProductionReadiness(config, targetManifest(), {
    confirmation: "production:gateway.example.com",
    platform: "linux",
    architecture: "x64",
    hostname: "prod-host",
    runner,
    now: () => new Date("2026-09-03T12:00:00.000Z"),
    readProcFile: async () => "MemAvailable:       4194304 kB\n",
  });

  assert.equal(result.ok, false);
  assert.equal(result.error.code, "HR-OPS-010");
  const blocked = result.checks.filter((entry) => entry.status === "blocked").map((entry) => entry.id);
  assert.deepEqual(blocked, [
    "dependencies",
    "public_routing",
    "docker",
    "postgresql",
    "legacy_recovery",
    "off_host_database_restore",
  ]);
  assert.equal(runner.calls.every(readOnlyCommand), true);
});

test("production audit authorization and evidence fail closed", async (t) => {
  const fixture = await createFixture(t);
  const config = await loadProductionAuditConfig(fixture.configPath);
  await assert.rejects(() => auditProductionReadiness(config, targetManifest(), {
    confirmation: "production:wrong.example.com",
  }), isOpsCode("HR-OPS-001"));

  const evidence = JSON.parse(await readFile(fixture.legacyEvidencePath, "utf8"));
  await writeJson(fixture.legacyEvidencePath, { ...evidence, restoreHostname: "prod-host" });
  await assert.rejects(
    () => loadProductionEvidence(fixture.legacyEvidencePath, "hermes-go-legacy-recovery-v1"),
    isOpsCode("HR-OPS-001"),
  );
});

test("production promotion error is localized, retryable, and registered", async () => {
  const definition = OPS_ERROR_DEFINITIONS.promotion;
  assert.equal(definition.code, "HR-OPS-010");
  assert.match(definition.summaryZh, /生产晋级/);
  assert.match(definition.summaryEn, /Production promotion/);
  assert.equal(definition.retryable, true);
  assert.equal(definition.recoveryAction, "resolve_production_gates_and_retry");
  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  assert.match(registry, /`HR-OPS-010`/);
});

async function createFixture(t, { evidence = true } = {}) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "production-audit-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const inputs = path.join(base, "inputs");
  const runtime = path.join(base, "runtime");
  await mkdir(inputs, { recursive: true });
  await mkdir(runtime, { recursive: true });
  const packageFile = path.join(runtime, "package.json");
  const entryFile = path.join(runtime, "index.js");
  const stateFile = path.join(runtime, "lifecycle-events.json");
  await writeFile(packageFile, "{\"version\":\"0.1.0\"}\n", { mode: 0o644 });
  await writeFile(entryFile, "export {};\n", { mode: 0o644 });
  await writeFile(stateFile, "{\"events\":[],\"nextSequence\":1}\n", { mode: 0o600 });
  const identityFiles = [
    { path: packageFile, sha256: await sha256(packageFile) },
    { path: entryFile, sha256: await sha256(entryFile) },
  ];
  const identityDigest = createHash("sha256")
    .update(JSON.stringify([...identityFiles].sort((left, right) => left.path.localeCompare(right.path))))
    .digest("hex");

  const legacyEvidencePath = path.join(inputs, "legacy-recovery.json");
  const databaseEvidencePath = path.join(inputs, "database-restore.json");
  if (evidence) {
    await writeEvidence(legacyEvidencePath, {
      kind: "hermes-go-legacy-recovery-v1",
      artifactSha256: "c".repeat(64),
      subject: { identityDigest },
      verifiedChecks: ["archive_hash", "files_restored", "service_start"],
    });
    await writeEvidence(databaseEvidencePath, {
      kind: "hermes-go-postgresql-restore-v1",
      artifactSha256: "d".repeat(64),
      subject: { databaseSchemaVersion: 7, postgresqlMajorVersion: 18 },
      verifiedChecks: ["encrypted_backup_hash", "database_restore", "schema_exact", "account_smoke"],
    });
  }
  const targetArtifactManifest = path.join(inputs, "target.manifest.json");
  await writeJson(targetArtifactManifest, targetManifest());
  const configPath = path.join(inputs, "production-audit.json");
  const config = {
    schemaVersion: 1,
    environment: "production",
    operator: "test-operator",
    targetArtifactManifest,
    host: {
      hostname: "prod-host",
      architecture: "amd64",
      minimumFreeDiskMiB: 10240,
      minimumAvailableMemoryMiB: 1024,
    },
    publicRoute: {
      serverName: "gateway.example.com",
      listenPort: 443,
      healthPath: "/relay-health",
    },
    legacyGateway: {
      serviceName: "hermes-remote-gateway",
      gatewayPort: 8444,
      stateFile,
      identityFiles,
    },
    postgresql: {
      serviceName: "postgresql",
      majorVersion: 18,
      port: 5432,
    },
    evidence: {
      legacyRecoveryManifest: legacyEvidencePath,
      databaseRestoreManifest: databaseEvidencePath,
    },
  };
  await writeJson(configPath, config);
  return { base, config, configPath, legacyEvidencePath };
}

function targetManifest() {
  return {
    schemaVersion: 3,
    kind: "hermes-go-gateway-oci",
    serverVersion: "0.4.0",
    sourceCommit: "a".repeat(40),
    imageReference: `hermes-remote-gateway:0.4.0-${"a".repeat(12)}`,
    imageId: `sha256:${"b".repeat(64)}`,
    containerdImageId: `sha256:${"c".repeat(64)}`,
    architecture: "amd64",
    archiveFile: `Hermes-Gateway-0.4.0-${"a".repeat(12)}-linux-amd64.tar`,
    archiveSha256: "e".repeat(64),
    createdAt: "2026-09-03T00:00:00.000Z",
    releaseContract: {
      manifestVersion: 2,
      configSchemaVersion: 1,
      databaseSchemaVersion: 7,
      supportedPostgresqlMajors: [18],
      protocolVersions: { legacy: 1, accountConnector: 2 },
      minimumClients: { android: "0.1.0", desktop: "0.2.0", connector: "0.1.1" },
      minimumSourceVersion: "0.2.0",
      maintenanceRequired: true,
      rollbackSupported: true,
    },
  };
}

function productionRunner({ missing = new Set(), dockerReady = true, postgresReady = true, sockets } = {}) {
  const calls = [];
  return {
    calls,
    run(command, args) {
      calls.push([command, ...args]);
      if (command === "which") return result(missing.has(args[0]) ? 1 : 0);
      if (command === "df") return result(0, "Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/vda1 100000000 1000000 83886080 2% /\n");
      if (command === "docker") return result(dockerReady ? 0 : 1, dockerReady ? "linux/amd64\n" : "");
      if (new Set(["psql", "pg_dump", "pg_restore"]).has(command)) {
        return result(postgresReady ? 0 : 1, postgresReady ? `${command} (PostgreSQL) 18.6\n` : "");
      }
      if (command === "systemctl") {
        if (args.includes("postgresql.service") && !postgresReady) return result(3);
        return result(0);
      }
      if (command === "ss") return result(0, sockets ?? [
        "LISTEN 0 511 127.0.0.1:8444 0.0.0.0:*",
        "LISTEN 0 244 127.0.0.1:5432 0.0.0.0:*",
      ].join("\n"));
      if (command === "nginx" || command === "curl") return result(0);
      throw new Error(`unexpected command ${command}`);
    },
  };
}

function readOnlyCommand(call) {
  const [command, ...args] = call;
  if (!new Set(["curl", "df", "docker", "nginx", "pg_dump", "pg_restore", "psql", "ss", "systemctl", "which"]).has(command)) {
    return false;
  }
  return !args.some((argument) => /^(?:start|stop|restart|reload|enable|disable|install|rm|run|exec)$/.test(argument));
}

async function writeEvidence(filePath, overrides) {
  await writeJson(filePath, {
    schemaVersion: 1,
    kind: overrides.kind,
    sourceHostname: "prod-host",
    createdAt: "2026-09-02T10:00:00.000Z",
    artifactSha256: overrides.artifactSha256,
    subject: overrides.subject,
    restoreHostname: "restore-host",
    restoredAt: "2026-09-02T11:00:00.000Z",
    verifiedChecks: overrides.verifiedChecks,
  });
}

async function writeJson(filePath, value) {
  await writeFile(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await chmod(filePath, 0o600);
}

async function sha256(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex");
}

function result(status, stdout = "") {
  return { status, stdout, stderr: "" };
}

function isOpsCode(code) {
  return (error) => error?.name === "OpsError"
    && OPS_ERROR_DEFINITIONS[error.kind]?.code === code;
}
