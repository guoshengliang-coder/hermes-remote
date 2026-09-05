import { randomUUID } from "node:crypto";
import { execFile } from "node:child_process";
import {
  chmod, copyFile, lstat, mkdir, mkdtemp, readdir, realpath, rm, writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { Pool } from "pg";
import { migrateAccountDatabase } from "../../gateway/dist/ops/migrate-account.mjs";
import { sha256File } from "../../ops/lib/config.mjs";
import {
  capturePostgresqlBackup, publishPostgresqlBackupStatus, verifyPostgresqlRestore,
} from "../../ops/lib/postgresql-recovery.mjs";
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
      ACCOUNT_DATABASE_SCHEMA_VERSION: "7",
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

  const sourceArchive = path.join(sourceRoot, "postgresql.cms");
  const sourceManifest = path.join(sourceRoot, "postgresql.manifest.json");
  const systemRunner = createCommandRunner({ timeoutMs: 120_000 });
  const runner = {
    run(command, args, options) {
      if (command === "systemctl") return { status: 0, stdout: "", stderr: "" };
      return systemRunner.run(command, args, options);
    },
  };
  const captured = await capturePostgresqlBackup({
    schemaVersion: 1,
    environment: "production",
    operator: "github-actions",
    sourceHostname: "github-r5e-source",
    serviceName: "postgresql",
    databaseUrlFile: sourceDatabaseUrlFile,
    recipientCertificate,
    archiveFile: sourceArchive,
    manifestFile: sourceManifest,
    maximumEncryptedBytes: 1024 * 1024 * 1024,
    postgresqlMajorVersion: 18,
    databaseSchemaVersion: 7,
  }, {
    confirmation: "production:github-r5e-source",
    hostname: "github-r5e-source",
    platform: "linux",
    runner,
  });

  const offHostArchive = path.join(offHostRoot, "postgresql.cms");
  const offHostManifest = path.join(offHostRoot, "postgresql.manifest.json");
  await copyFile(sourceArchive, offHostArchive);
  await copyFile(sourceManifest, offHostManifest);
  await chmod(offHostArchive, 0o600);
  await chmod(offHostManifest, 0o600);
  if (await sha256File(offHostArchive) !== captured.archiveSha256) throw new Error("off_host_archive_hash_mismatch");
  if ((await lstat(offHostArchive)).size !== captured.archiveBytes) throw new Error("off_host_archive_size_mismatch");

  const evidenceFile = path.join(offHostRoot, "postgresql-restore.evidence.json");
  const statusFile = path.join(offHostRoot, "postgresql-backup.status.json");
  const restored = await verifyPostgresqlRestore({
    schemaVersion: 2,
    environment: "isolated-restore",
    operator: "github-actions",
    expectedSourceHostname: "github-r5e-source",
    archiveFile: offHostArchive,
    manifestFile: offHostManifest,
    recipientCertificate,
    recipientPrivateKey,
    databaseUrlFile: restoreDatabaseUrlFile,
    imageDatabaseUrlFile,
    targetArtifactManifest: process.env.R5E_TARGET_MANIFEST,
    evidenceFile,
    statusFile,
    offHostStorageId: "github-ephemeral-runner",
    postgresqlMajorVersion: 18,
    databaseSchemaVersion: 7,
  }, {
    confirmation: "isolated:github-r5e-source",
    hostname: "github-r5e-restore",
    runner,
  });

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
  if (evidence.artifactSha256 !== captured.archiveSha256
      || candidateStatus.offHostSha256 !== captured.archiveSha256
      || restored.subject.databaseSchemaVersion !== 7) {
    throw new Error("recovery_evidence_binding_invalid");
  }
  const activeStatusFile = path.join(activeRoot, "latest-status.json");
  await publishPostgresqlBackupStatus({
    schemaVersion: 1,
    environment: "production",
    operator: "github-actions",
    sourceHostname: "github-r5e-source",
    manifestFile: sourceManifest,
    restoreEvidenceFile: evidenceFile,
    candidateStatusFile: statusFile,
    activeStatusFile,
  }, {
    confirmation: "production:github-r5e-source",
    hostname: "github-r5e-source",
    platform: "linux",
    getUid: () => 0,
    owner: { uid: process.getuid(), gid: process.getgid() },
  });
  const activeStatus = await loadPostgresqlBackupStatus(activeStatusFile);
  if (activeStatus.artifactSha256 !== captured.archiveSha256
      || ((await lstat(activeStatusFile)).mode & 0o777) !== 0o640) {
    throw new Error("active_status_invalid");
  }
  const names = await readdir(offHostRoot);
  if (names.some((name) => /\.dump$|\.sql$|plaintext/i.test(name))) throw new Error("plaintext_backup_found");
  process.stdout.write(`POSTGRESQL_RECOVERY_E2E_OK ${JSON.stringify({
    postgresqlMajorVersion: 18,
    databaseSchemaVersion: 7,
    encryptedBytes: captured.archiveBytes,
    verifiedChecks: restored.verifiedChecks,
    accountRowsRestored: accountCount.rows[0].count,
    databaseProvisioned: true,
    targetRelease: restored.targetRelease,
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
case "$PGDATABASE" in
  *:5432/*)
    container=${sourcePostgresContainerId}
    database=hermes_r5e_source
    ;;
  *:5433/*)
    container=${restorePostgresContainerId}
    database=hermes_r5e_restore
    ;;
  *) exit 64 ;;
esac
exec docker exec -i "$container" ${tool} --username hermes_r5e --dbname "$database" "$@"
`;
    await writeFile(filePath, script, { flag: "wx", mode: 0o700 });
    await chmod(filePath, 0o700);
  }
}
