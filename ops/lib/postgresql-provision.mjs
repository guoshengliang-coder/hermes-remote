import { spawnSync } from "node:child_process";
import { link, lstat, open, readFile, unlink } from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { OpsError } from "./errors.mjs";

export async function provisionPostgresql(config, options = {}) {
  const admin = options.admin ?? createPostgresqlAdmin();
  const hostname = options.hostname ?? systemHostname();
  const getUid = options.getUid ?? (() => process.getuid?.());
  if (options.confirmation !== `production:${config.hostname}` || hostname !== config.hostname
      || (options.platform ?? process.platform) !== "linux" || getUid() !== 0) fail("postgresql_provision_admission_failed", "postgresql_provision_admission");
  const password = await readSecret(config.passwordFile);
  if (await exists(config.databaseUrlFile)) fail("postgresql_database_url_already_exists", "postgresql_provision_preflight");
  const facts = await admin.inspect(config);
    if (facts.serviceActive !== true || facts.postgresqlMajor !== 18 || facts.postgresqlPort !== config.postgresqlPort || facts.loopbackOnly !== true
      || facts.statementLoggingDisabled !== true || facts.auditLoggingDisabled !== true) fail("postgresql_provision_prerequisites_invalid", "postgresql_provision_preflight");
  if (facts.databaseExists || facts.roleExists) fail("postgresql_provision_partial_state", "postgresql_provision_preflight");

  let roleCreated = false;
  let databaseCreated = false;
  try {
    await admin.createRole(config.roleName, password);
    roleCreated = true;
    await admin.createDatabase(config.databaseName, config.roleName);
    databaseCreated = true;
    const verified = await admin.verify(config);
    if (!verified.databaseOwnedByRole || !verified.leastPrivilegeRole) fail("postgresql_provision_verification_failed", "postgresql_provision_verify");
    await atomicSecret(config.databaseUrlFile, databaseUrl(config, password));
    return { ok: true, changed: true, databaseName: config.databaseName, roleName: config.roleName, postgresqlMajorVersion: 18 };
  } catch (error) {
    let cleanupFailed = false;
    if (databaseCreated) await admin.dropDatabase(config.databaseName).catch(() => { cleanupFailed = true; });
    if (roleCreated) await admin.dropRole(config.roleName).catch(() => { cleanupFailed = true; });
    await unlink(config.databaseUrlFile).catch(() => {});
    if (cleanupFailed) fail("postgresql_provision_cleanup_failed", "postgresql_provision_cleanup");
    if (error instanceof OpsError) throw error;
    fail("postgresql_provision_operation_failed", "postgresql_provision_execute");
  }
}

export function databaseUrl(config, password) {
  return `postgresql://${encodeURIComponent(config.roleName)}:${encodeURIComponent(password)}@127.0.0.1:${config.postgresqlPort}/${encodeURIComponent(config.databaseName)}`;
}

export function createPostgresqlAdmin() {
  const query = (database, sql) => {
    const result = spawnSync("runuser", ["-u", "postgres", "--", "psql", "-X", "--no-psqlrc", "-v", "ON_ERROR_STOP=1", "-At", "--dbname", database], {
      input: sql, encoding: "utf8", maxBuffer: 1024 * 1024, timeout: 30_000, shell: false,
    });
    if (result.status !== 0) throw new Error("postgresql_admin_command_failed");
    return String(result.stdout ?? "").trim();
  };
  return {
    async inspect(config) {
      const serverVersion = query("postgres", "SHOW server_version_num;\n");
      const listenAddresses = query("postgres", "SHOW listen_addresses;\n");
      const postgresqlPort = Number(query("postgres", "SHOW port;\n"));
      const statementLogging = query("postgres", "SHOW log_statement;\n");
      const preloadLibraries = query("postgres", "SHOW shared_preload_libraries;\n");
      return {
        serviceActive: spawnSync("systemctl", ["is-active", "--quiet", `${config.serviceName}.service`]).status === 0,
        postgresqlMajor: Math.floor(Number(serverVersion) / 10000),
        postgresqlPort,
        loopbackOnly: new Set(listenAddresses.split(",").map((value) => value.trim())).size === 1 && listenAddresses === "127.0.0.1",
        statementLoggingDisabled: statementLogging === "none",
        auditLoggingDisabled: !preloadLibraries.toLowerCase().split(",").map((value) => value.trim()).includes("pgaudit"),
        databaseExists: query("postgres", `SELECT count(*) FROM pg_database WHERE datname = '${config.databaseName}';\n`) !== "0",
        roleExists: query("postgres", `SELECT count(*) FROM pg_roles WHERE rolname = '${config.roleName}';\n`) !== "0",
      };
    },
    async createRole(role, password) {
      query("postgres", `SET log_statement = 'none';\nSET log_min_error_statement = 'panic';\nCREATE ROLE ${role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '${sqlLiteral(password)}';\n`);
    },
    async createDatabase(database, role) { query("postgres", `CREATE DATABASE ${database} OWNER ${role};\n`); },
    async verify(config) {
      const output = query("postgres", `SELECT count(*) FROM pg_database d JOIN pg_roles r ON r.oid=d.datdba WHERE d.datname='${config.databaseName}' AND r.rolname='${config.roleName}';\nSELECT count(*) FROM pg_roles WHERE rolname='${config.roleName}' AND rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls;\n`).split(/\r?\n/);
      return { databaseOwnedByRole: output[0] === "1", leastPrivilegeRole: output[1] === "1" };
    },
    async dropDatabase(database) { query("postgres", `DROP DATABASE IF EXISTS ${database};\n`); },
    async dropRole(role) { query("postgres", `DROP ROLE IF EXISTS ${role};\n`); },
  };
}

async function readSecret(filePath) {
  const info = await lstat(filePath);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 || info.size < 32 || info.size > 256) fail("postgresql_password_file_invalid", "postgresql_provision_secret");
  const value = (await readFile(filePath, "utf8")).trimEnd();
  if (!/^[A-Za-z0-9._~!$%&*+,:;=?@^-]{32,128}$/.test(value)) fail("postgresql_password_invalid", "postgresql_provision_secret");
  return value;
}

async function atomicSecret(filePath, value) {
  const directory = await lstat(path.dirname(filePath));
  if (!directory.isDirectory() || directory.isSymbolicLink() || (directory.mode & 0o022) !== 0) {
    fail("postgresql_database_url_directory_invalid", "postgresql_provision_secret_install");
  }
  const temporary = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${randomUUID()}.tmp`);
  const handle = await open(temporary, "wx", 0o600);
  try { await handle.writeFile(`${value}\n`, "utf8"); await handle.sync(); await handle.close(); await link(temporary, filePath); await unlink(temporary); }
  catch (error) { await handle.close().catch(() => {}); await unlink(temporary).catch(() => {}); throw error; }
}

async function exists(filePath) { try { await lstat(filePath); return true; } catch (error) { if (error?.code === "ENOENT") return false; throw error; } }
function sqlLiteral(value) { return value.replaceAll("'", "''"); }
function fail(cause, stage) { throw new OpsError("databaseProvision", cause, stage); }
