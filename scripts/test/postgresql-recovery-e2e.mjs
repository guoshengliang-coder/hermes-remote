import { randomUUID } from "node:crypto";
import { execFile } from "node:child_process";
import {
  chmod, copyFile, lstat, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { Pool } from "pg";
import { migrateAccountDatabase } from "../../gateway/dist/ops/migrate-account.mjs";
import {
  verifyPostgresqlRestore,
} from "../../ops/lib/postgresql-recovery.mjs";
import {
  activateScheduledPostgresqlBackup, captureScheduledPostgresqlBackup,
  readLatestBackupDescriptor, runOffHostRecoveryCycle,
} from "../../ops/lib/postgresql-automation.mjs";
import { loadProductionEvidence } from "../../ops/lib/production-config.mjs";
import { loadPostgresqlBackupStatus } from "../../ops/lib/production-monitor-config.mjs";
import { createCommandRunner } from "../../ops/lib/system.mjs";
import { provisionPostgresql } from "../../ops/lib/postgresql-provision.mjs";

const execFileAsync = promisify(execFile);
const requiredEnvironment = [
  "R5E_SOURCE_DATABASE_URL",
  "R5E_RESTORE_DATABASE_URL",
  "R5E_SOURCE_POSTGRES_CONTAINER_ID",
  "R5E_RESTORE_POSTGRES_CONTAINER_ID",
  "R5E_TARGET_MANIFEST",
];
for (const name of requiredEnvironment) {
  if (!process.env[name]) throw new Error(`missing_environment=${name}`);
}
const sourceContainerId = process.env.R5E_SOURCE_POSTGRES_CONTAINER_ID;
const restoreContainerId = process.env.R5E_RESTORE_POSTGRES_CONTAINER_ID;
if (![sourceContainerId, restoreContainerId].every((value) => /^[a-f0-9]{12,64}$/.test(value))) {
  throw new Error("postgres_container_id_invalid");
}
if (sourceContainerId === restoreContainerId) throw new Error("postgres_containers_must_differ");
const targetManifest = JSON.parse(await readFile(process.env.R5E_TARGET_MANIFEST, "utf8"));
const databaseSchemaVersion = targetManifest.releaseContract?.databaseSchemaVersion;
if (!Number.isSafeInteger(databaseSchemaVersion) || databaseSchemaVersion <= 0) {
  throw new Error("target_database_schema_version_invalid");
}
if (!targetManifest.releaseContract.supportedPostgresqlMajors?.includes(18)) {
  throw new Error("target_postgresql_major_unsupported");
}

const base = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-r5e-e2e-")));
try {
  const sourceRoot = path.join(base, "source-host");
  const offHostRoot = path.join(base, "off-host");
  const activeRoot = path.join(base, "production-monitor");
  const toolsRoot = path.join(base, "postgresql-18-tools");
  for (const directory of [sourceRoot, offHostRoot, activeRoot, toolsRoot]) await mkdir(directory, { mode: 0o700 });
  await installPostgresqlWrappers(toolsRoot, sourceContainerId, restoreContainerId);
  process.env.PATH = `${toolsRoot}:${process.env.PATH}`;

  const sourceUrl = process.env.R5E_SOURCE_DATABASE_URL;
  const restoreUrl = process.env.R5E_RESTORE_DATABASE_URL;
  const provisionRoot = path.join(base, "provision-input");
  await mkdir(provisionRoot, { mode: 0o700 });
  const provisionPassword = "ephemeral-r5e2-password-0123456789";
  const provisionPasswordFile = path.join(provisionRoot, "password");
  const provisionUrlFile = path.join(provisionRoot, "database-url");
  await privateFile(provisionPasswordFile, `${provisionPassword}\n`);
  const provisionConfig = {
    schemaVersion: 1, environment: "production", operator: "github-actions",
    hostname: "github-r5e-source", serviceName: "postgresql@18-main",
    databaseName: "hermes_r5e_provision", roleName: "hermes_r5e_gateway",
    passwordFile: provisionPasswordFile, databaseUrlFile: provisionUrlFile, postgresqlMajorVersion: 18, postgresqlPort: 5432,
  };
  const provisionAdmin = await disposableProvisionAdmin(sourceUrl);
  try {
    await provisionPostgresql(provisionConfig, {
      confirmation: "production:github-r5e-source", hostname: "github-r5e-source",
      platform: "linux", getUid: () => 0, admin: provisionAdmin,
    });
    const provisionPool = new Pool({ connectionString: (await readFile(provisionUrlFile, "utf8")).trim(), max: 1 });
    await provisionPool.query("SELECT 1");
    await provisionPool.end();
  } finally {
    await provisionAdmin.dropDatabase(provisionConfig.databaseName).catch(() => {});
    await provisionAdmin.dropRole(provisionConfig.roleName).catch(() => {});
    await provisionAdmin.close();
  }
  await migrateAccountDatabase({
    env: {
      ACCOUNT_DATABASE_URL: sourceUrl,
      ACCOUNT_DATABASE_SSL: "0",
      ACCOUNT_DATABASE_MIGRATION_LOCK_ID: "948501337",
      ACCOUNT_DATABASE_SCHEMA_VERSION: String(databaseSchemaVersion),
      ACCOUNT_DATABASE_SUPPORTED_MAJORS: "18",
    },
  });
  const seedAccountId = randomUUID();
  const seedIdentityId = randomUUID();
  const sourcePool = new Pool({ connectionString: sourceUrl, max: 1 });
  await sourcePool.query("INSERT INTO accounts (id) VALUES ($1)", [seedAccountId]);
  await sourcePool.query(
    `INSERT INTO external_identities (id, account_id, provider, issuer, subject, display_name)
     VALUES ($1, $2, 'google', 'https://accounts.google.com', 'r5e-e2e-seed', 'R5-E encrypted restore')`,
    [seedIdentityId, seedAccountId],
  );
  await sourcePool.end();

  const sourceDatabaseUrlFile = path.join(sourceRoot, "source-database-url");
  const restoreDatabaseUrlFile = path.join(offHostRoot, "restore-database-url");
  const imageDatabaseUrlFile = path.join(offHostRoot, "image-restore-database-url");
  const recipientCertificate = path.join(offHostRoot, "recipient-cert.pem");
  const recipientPrivateKey = path.join(offHostRoot, "recipient-key.pem");
  await privateFile(sourceDatabaseUrlFile, `${sourceUrl}\n`);
  await privateFile(restoreDatabaseUrlFile, `${restoreUrl}\n`);
  await privateFile(imageDatabaseUrlFile, `${restoreUrl}\n`);
  await execFileAsync("openssl", [
    "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "1",
    "-subj", "/CN=Hermes-R5E-Ephemeral-Recovery",
    "-keyout", recipientPrivateKey, "-out", recipientCertificate,
  ]);
  await chmod(recipientPrivateKey, 0o600);
  await chmod(recipientCertificate, 0o644);

  const sourceGenerations = path.join(sourceRoot, "generations");
  await mkdir(sourceGenerations, { mode: 0o700 });
  const activeStatusFile = path.join(activeRoot, "latest-status.json");
  const captureSchedule = {
    schemaVersion: 1, environment: "production", operator: "github-actions",
    sourceHostname: "github-r5e-source", serviceName: "postgresql",
    databaseUrlFile: sourceDatabaseUrlFile, recipientCertificate,
    backupRoot: sourceGenerations, latestDescriptorFile: path.join(sourceGenerations, "latest-ready.json"),
    activeStatusFile, maximumEncryptedBytes: 1024 * 1024 * 1024, retentionCount: 14,
    postgresqlMajorVersion: 18, databaseSchemaVersion,
  };
  const systemRunner = createCommandRunner({ timeoutMs: 120_000 });
  const runner = {
    run(command, args, options) {
      if (command === "systemctl") return { status: 0, stdout: "", stderr: "" };
      if (command === "which" && args[0] === "systemctl") return { status: 0, stdout: "/usr/bin/systemctl\n", stderr: "" };
      return systemRunner.run(command, args, options);
    },
  };
  const scheduled = await captureScheduledPostgresqlBackup(captureSchedule, {
    confirmation: "production:github-r5e-source",
    hostname: "github-r5e-source",
    platform: "linux",
    getUid: () => 0,
    runner,
  });
  const descriptor = await readLatestBackupDescriptor(captureSchedule, {
    confirmation: "production:github-r5e-source", hostname: "github-r5e-source", platform: "linux",
  });
  if (descriptor.generationId !== scheduled.generationId) throw new Error("scheduled_generation_mismatch");
  const sourceGenerationRoot = path.join(sourceGenerations, scheduled.generationId);
  const sourceArchive = path.join(sourceGenerationRoot, "postgresql.cms");
  const sourceManifest = path.join(sourceGenerationRoot, "postgresql.manifest.json");
  const offHostGenerations = path.join(offHostRoot, "generations");
  await mkdir(offHostGenerations, { mode: 0o700 });
  const sshIdentityFile = path.join(offHostRoot, "test-ssh-identity");
  const knownHostsFile = path.join(offHostRoot, "test-known-hosts");
  await privateFile(sshIdentityFile, "ephemeral-test-identity\n");
  await privateFile(knownHostsFile, "ephemeral-test-host-key\n");
  const offHostConfig = {
    schemaVersion: 1, environment: "off-host-recovery", operator: "github-actions",
    expectedSourceHostname: "github-r5e-source", offHostStorageId: "github-ephemeral-runner",
    remoteHost: "203.0.113.8", remoteUser: "runner", sshIdentityFile, knownHostsFile,
    remoteCommandPath: "/usr/local/sbin/hermes-go-postgresql-automation-remote",
    localRoot: offHostGenerations, recipientCertificate, recipientPrivateKey,
    targetArtifactManifest: process.env.R5E_TARGET_MANIFEST,
    dockerPath: "/usr/bin/docker", opensslPath: "/usr/bin/openssl",
    postgresqlImage: `postgres:18-alpine@sha256:${"a".repeat(64)}`,
    maximumEncryptedBytes: 1024 * 1024 * 1024, retentionCount: 30,
    postgresqlMajorVersion: 18, databaseSchemaVersion,
  };
  let restored;
  const remote = {
    latest: async () => descriptor,
    download: async (_generation, part, target) => copyFile(part === "archive" ? sourceArchive : sourceManifest, target),
    activate: async (generation, evidence, status) => activateScheduledPostgresqlBackup(
      captureSchedule, generation, evidence, status,
      { confirmation: "production:github-r5e-source", hostname: "github-r5e-source", platform: "linux",
        getUid: () => 0, owner: { uid: process.getuid(), gid: process.getgid() } },
    ),
  };
  const cycle = await runOffHostRecoveryCycle(offHostConfig, {
    hostname: "github-r5e-restore", remote,
    restore: async (config, paths) => {
      restored = await verifyPostgresqlRestore({
        schemaVersion: 2, environment: "isolated-restore", operator: config.operator,
        expectedSourceHostname: config.expectedSourceHostname,
        archiveFile: paths.archiveFile, manifestFile: paths.manifestFile,
        recipientCertificate, recipientPrivateKey, databaseUrlFile: restoreDatabaseUrlFile,
        imageDatabaseUrlFile, targetArtifactManifest: config.targetArtifactManifest,
        evidenceFile: paths.evidenceFile, statusFile: paths.statusFile,
        offHostStorageId: config.offHostStorageId, postgresqlMajorVersion: 18, databaseSchemaVersion,
      }, { confirmation: "isolated:github-r5e-source", hostname: "github-r5e-restore", runner });
    },
  });
  if (!cycle.ok || cycle.generationId !== scheduled.generationId) throw new Error("automated_cycle_failed");
  const offHostGenerationRoot = path.join(offHostGenerations, scheduled.generationId);
  const evidenceFile = path.join(offHostGenerationRoot, "postgresql-restore.evidence.json");
  const statusFile = path.join(offHostGenerationRoot, "postgresql-backup.status.json");

  const restorePool = new Pool({ connectionString: restoreUrl, max: 1 });
  const restoredSeed = await restorePool.query(
    `SELECT a.id, i.display_name
       FROM accounts a JOIN external_identities i ON i.account_id = a.id
      WHERE a.id = $1 AND i.id = $2 AND i.subject = 'r5e-e2e-seed'`,
    [seedAccountId, seedIdentityId],
  );
  const accountCount = await restorePool.query("SELECT count(*)::int AS count FROM accounts");
  await restorePool.end();
  if (restoredSeed.rowCount !== 1 || restoredSeed.rows[0]?.display_name !== "R5-E encrypted restore") {
    throw new Error("restored_seed_account_missing");
  }
  if (accountCount.rows[0]?.count !== 1) throw new Error("account_smoke_transaction_not_rolled_back");

  const evidence = await loadProductionEvidence(evidenceFile, "hermes-go-postgresql-restore-v1");
  const candidateStatus = await loadPostgresqlBackupStatus(statusFile);
  if (evidence.artifactSha256 !== descriptor.archiveSha256
      || candidateStatus.offHostSha256 !== descriptor.archiveSha256
      || restored.subject.databaseSchemaVersion !== databaseSchemaVersion) {
    throw new Error("recovery_evidence_binding_invalid");
  }
  const activeStatus = await loadPostgresqlBackupStatus(activeStatusFile);
  if (activeStatus.artifactSha256 !== descriptor.archiveSha256
      || ((await lstat(activeStatusFile)).mode & 0o777) !== 0o640) {
    throw new Error("active_status_invalid");
  }
  const disposableMacRoot = path.join(offHostRoot, "disposable-mac-runtime");
  await mkdir(disposableMacRoot, { mode: 0o700 });
  let disposableMacActivated = false;
  const disposableMacCycle = await runOffHostRecoveryCycle({
    ...offHostConfig,
    localRoot: disposableMacRoot,
    dockerPath: process.platform === "darwin"
      ? "/Applications/Docker.app/Contents/Resources/bin/docker" : "/usr/bin/docker",
    opensslPath: process.platform === "darwin"
      ? "/opt/homebrew/opt/openssl@3/bin/openssl" : "/usr/bin/openssl",
    postgresqlImage: "postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2",
  }, {
    hostname: "github-r5e-disposable-mac",
    remote: {
      latest: remote.latest,
      download: remote.download,
      activate: async () => { disposableMacActivated = true; },
    },
  });
  if (!disposableMacCycle.ok || !disposableMacActivated) throw new Error("disposable_mac_runtime_failed");
  const names = await readdir(offHostRoot);
  if (names.some((name) => /\.dump$|\.sql$|plaintext/i.test(name))) throw new Error("plaintext_backup_found");
  process.stdout.write(`POSTGRESQL_RECOVERY_E2E_OK ${JSON.stringify({
    postgresqlMajorVersion: 18,
    databaseSchemaVersion,
    encryptedBytes: descriptor.archiveBytes,
    verifiedChecks: restored.verifiedChecks,
    accountRowsRestored: accountCount.rows[0].count,
    databaseProvisioned: true,
    targetRelease: restored.targetRelease,
    automatedCycle: true,
    disposableMacRuntime: true,
  })}\n`);
} finally {
  await rm(base, { recursive: true, force: true });
}

async function disposableProvisionAdmin(sourceUrl) {
  const adminUrl = new URL(sourceUrl);
  adminUrl.pathname = "/postgres";
  const pool = new Pool({ connectionString: adminUrl.toString(), max: 1 });
  return {
    async inspect(config) {
      const database = await pool.query("SELECT count(*)::int AS count FROM pg_database WHERE datname=$1", [config.databaseName]);
      const role = await pool.query("SELECT count(*)::int AS count FROM pg_roles WHERE rolname=$1", [config.roleName]);
      return { serviceActive: true, postgresqlMajor: 18, postgresqlPort: 5432, loopbackOnly: true, statementLoggingDisabled: true, auditLoggingDisabled: true, databaseExists: database.rows[0].count !== 0, roleExists: role.rows[0].count !== 0 };
    },
    async createRole(role, password) {
      await pool.query("SET log_min_error_statement = 'panic'");
      await pool.query(`CREATE ROLE ${role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '${password.replaceAll("'", "''")}'`);
    },
    async createDatabase(database, role) { await pool.query(`CREATE DATABASE ${database} OWNER ${role}`); },
    async verify(config) {
      const result = await pool.query("SELECT d.datdba=r.oid AS owned, r.rolcanlogin AND NOT r.rolinherit AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolreplication AND NOT r.rolbypassrls AS least FROM pg_database d JOIN pg_roles r ON r.rolname=$1 WHERE d.datname=$2", [config.roleName, config.databaseName]);
      return { databaseOwnedByRole: result.rows[0]?.owned === true, leastPrivilegeRole: result.rows[0]?.least === true };
    },
    async dropDatabase(database) { await pool.query(`DROP DATABASE IF EXISTS ${database}`); },
    async dropRole(role) { await pool.query(`DROP ROLE IF EXISTS ${role}`); },
    async close() { await pool.end(); },
  };
}

async function privateFile(filePath, content) {
  await writeFile(filePath, content, { flag: "wx", mode: 0o600 });
  await chmod(filePath, 0o600);
}

async function installPostgresqlWrappers(directory, sourcePostgresContainerId, restorePostgresContainerId) {
  for (const tool of ["psql", "pg_dump", "pg_restore"]) {
    const filePath = path.join(directory, tool);
    const script = `#!/bin/sh
test "$PGHOST" = 127.0.0.1 || exit 65
test "$PGUSER" = hermes_r5e || exit 66
test "$PGPASSWORD" = ephemeral-only-password || exit 67
case "$PGPORT:$PGDATABASE" in
  5432:hermes_r5e_source)
    container=${sourcePostgresContainerId}
    database=hermes_r5e_source
    ;;
  5433:hermes_r5e_restore)
    container=${restorePostgresContainerId}
    database=hermes_r5e_restore
    ;;
  *) exit 64 ;;
esac
if [ "${tool}" = pg_restore ]; then
  test "$1" = --dbname || exit 68
  test "$2" = "$database" || exit 69
  exec docker exec -i "$container" ${tool} --username hermes_r5e "$@"
fi
exec docker exec -i "$container" ${tool} --username hermes_r5e --dbname "$database" "$@"
`;
    await writeFile(filePath, script, { flag: "wx", mode: 0o700 });
    await chmod(filePath, 0o700);
  }
}
