import { readFile } from "node:fs/promises";
import path from "node:path";
import { assertRegularFile } from "./config.mjs";
import { OpsError } from "./errors.mjs";

const TOP_LEVEL_KEYS = [
  "schemaVersion",
  "environment",
  "operator",
  "targetArtifactManifest",
  "host",
  "publicRoute",
  "legacyGateway",
  "postgresql",
  "evidence",
];

export async function loadProductionAuditConfig(filePath) {
  const raw = await readStrictJson(filePath, 64 * 1024, "production_audit_config");
  try {
    exactKeys(raw, TOP_LEVEL_KEYS, "production_audit_config");
    exactKeys(raw.host, ["hostname", "architecture", "minimumFreeDiskMiB", "minimumAvailableMemoryMiB"], "host");
    exactKeys(raw.publicRoute, ["serverName", "listenPort", "healthPath"], "public_route");
    exactKeys(raw.legacyGateway, ["serviceName", "gatewayPort", "stateFile", "identityFiles"], "legacy_gateway");
    exactKeys(raw.postgresql, ["serviceName", "majorVersion", "port"], "postgresql");
    exactKeys(raw.evidence, ["legacyRecoveryManifest", "databaseRestoreManifest"], "evidence");

    if (raw.schemaVersion !== 1) fail("unsupported_production_audit_schema");
    if (raw.environment !== "production") fail("production_audit_requires_production_environment");
    const config = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      targetArtifactManifest: absolutePath(raw.targetArtifactManifest, "targetArtifactManifest"),
      host: {
        hostname: token(raw.host.hostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "host.hostname"),
        architecture: exactValue(raw.host.architecture, "amd64", "host.architecture"),
        minimumFreeDiskMiB: boundedInteger(raw.host.minimumFreeDiskMiB, 1024, 1024 * 1024, "host.minimumFreeDiskMiB"),
        minimumAvailableMemoryMiB: boundedInteger(
          raw.host.minimumAvailableMemoryMiB,
          256,
          1024 * 1024,
          "host.minimumAvailableMemoryMiB",
        ),
      },
      publicRoute: {
        serverName: token(
          raw.publicRoute.serverName,
          /^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/,
          "publicRoute.serverName",
        ),
        listenPort: port(raw.publicRoute.listenPort, "publicRoute.listenPort", 1),
        healthPath: token(raw.publicRoute.healthPath, /^\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{1,255}$/, "publicRoute.healthPath"),
      },
      legacyGateway: {
        serviceName: token(
          raw.legacyGateway.serviceName,
          /^[a-z0-9][a-z0-9.-]{0,62}$/,
          "legacyGateway.serviceName",
        ),
        gatewayPort: port(raw.legacyGateway.gatewayPort, "legacyGateway.gatewayPort", 1024),
        stateFile: absolutePath(raw.legacyGateway.stateFile, "legacyGateway.stateFile"),
        identityFiles: identityFiles(raw.legacyGateway.identityFiles),
      },
      postgresql: {
        serviceName: token(raw.postgresql.serviceName, /^[a-z0-9][a-z0-9@.-]{0,62}$/, "postgresql.serviceName"),
        majorVersion: exactValue(raw.postgresql.majorVersion, 18, "postgresql.majorVersion"),
        port: exactValue(raw.postgresql.port, 5432, "postgresql.port"),
      },
      evidence: {
        legacyRecoveryManifest: absolutePath(
          raw.evidence.legacyRecoveryManifest,
          "evidence.legacyRecoveryManifest",
        ),
        databaseRestoreManifest: absolutePath(
          raw.evidence.databaseRestoreManifest,
          "evidence.databaseRestoreManifest",
        ),
      },
    };

    if (config.host.hostname.includes("..") || config.publicRoute.serverName.includes("..")) {
      fail("production_hostname_invalid");
    }
    const ports = [config.publicRoute.listenPort, config.legacyGateway.gatewayPort, config.postgresql.port];
    if (new Set(ports).size !== ports.length) fail("production_ports_must_be_distinct");
    const inputPaths = [
      config.targetArtifactManifest,
      config.legacyGateway.stateFile,
      ...config.legacyGateway.identityFiles.map((entry) => entry.path),
      config.evidence.legacyRecoveryManifest,
      config.evidence.databaseRestoreManifest,
    ];
    if (new Set(inputPaths).size !== inputPaths.length) fail("production_audit_paths_must_be_distinct");
    return config;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError(
      "config",
      error instanceof Error ? error.message : error,
      "production_audit_config_validate",
    );
  }
}

export async function loadProductionEvidence(filePath, expectedKind) {
  if (!new Set(["hermes-go-legacy-recovery-v1", "hermes-go-postgresql-restore-v1"]).has(expectedKind)) {
    fail("production_evidence_expected_kind_invalid");
  }
  const raw = await readStrictJson(filePath, 64 * 1024, "production_evidence");
  try {
    exactKeys(raw, [
      "schemaVersion",
      "kind",
      "sourceHostname",
      "createdAt",
      "artifactSha256",
      "subject",
      "restoreHostname",
      "restoredAt",
      "verifiedChecks",
    ], "production_evidence");
    if (raw.schemaVersion !== 1 || raw.kind !== expectedKind) fail("production_evidence_kind_invalid");
    const subject = evidenceSubject(raw.subject, expectedKind);
    const sourceHostname = token(raw.sourceHostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "sourceHostname");
    const restoreHostname = token(raw.restoreHostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "restoreHostname");
    if (sourceHostname === restoreHostname) fail("production_evidence_must_be_off_host");
    const requiredChecks = expectedKind === "hermes-go-legacy-recovery-v1"
      ? ["archive_hash", "files_restored", "service_start"]
      : ["encrypted_backup_hash", "database_restore", "schema_exact", "account_smoke"];
    if (!Array.isArray(raw.verifiedChecks)
        || raw.verifiedChecks.length !== requiredChecks.length
        || requiredChecks.some((check) => !raw.verifiedChecks.includes(check))
        || new Set(raw.verifiedChecks).size !== raw.verifiedChecks.length) {
      fail("production_evidence_checks_incomplete");
    }
    return {
      schemaVersion: 1,
      kind: expectedKind,
      sourceHostname,
      createdAt: canonicalTimestamp(raw.createdAt, "createdAt"),
      artifactSha256: token(raw.artifactSha256, /^[0-9a-f]{64}$/, "artifactSha256"),
      subject,
      restoreHostname,
      restoredAt: canonicalTimestamp(raw.restoredAt, "restoredAt"),
      verifiedChecks: [...raw.verifiedChecks].sort(),
    };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError(
      "config",
      error instanceof Error ? error.message : error,
      "production_evidence_validate",
    );
  }
}

function evidenceSubject(value, kind) {
  if (kind === "hermes-go-legacy-recovery-v1") {
    exactKeys(value, ["identityDigest"], "production_evidence_subject");
    return { identityDigest: token(value.identityDigest, /^[0-9a-f]{64}$/, "subject.identityDigest") };
  }
  exactKeys(value, ["databaseSchemaVersion", "postgresqlMajorVersion"], "production_evidence_subject");
  return {
    databaseSchemaVersion: boundedInteger(
      value.databaseSchemaVersion,
      1,
      Number.MAX_SAFE_INTEGER,
      "subject.databaseSchemaVersion",
    ),
    postgresqlMajorVersion: boundedInteger(value.postgresqlMajorVersion, 1, 99, "subject.postgresqlMajorVersion"),
  };
}

function identityFiles(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 16) {
    throw new Error("legacy_identity_files_invalid");
  }
  const parsed = value.map((entry) => {
    exactKeys(entry, ["path", "sha256"], "legacy_identity_file");
    return {
      path: absolutePath(entry.path, "legacy_identity_file.path"),
      sha256: token(entry.sha256, /^[0-9a-f]{64}$/, "legacy_identity_file.sha256"),
    };
  });
  if (new Set(parsed.map((entry) => entry.path)).size !== parsed.length) {
    throw new Error("legacy_identity_file_paths_must_be_distinct");
  }
  return parsed;
}

async function readStrictJson(filePath, maximumBytes, label) {
  try {
    const info = await assertRegularFile(filePath, label);
    if (info.size < 2 || info.size > maximumBytes) throw new Error(`${label}_size_invalid`);
    return JSON.parse(await readFile(filePath, "utf8"));
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError("config", error instanceof Error ? error.message : error, `${label}_read`);
  }
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label}_must_be_object`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error(`${label}_fields_invalid`);
  }
}

function absolutePath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/") {
    throw new Error(`${label}_path_invalid`);
  }
  if (!/^\/[A-Za-z0-9._/-]+$/.test(value) || value.includes("//")) throw new Error(`${label}_path_unsafe`);
  return value;
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) throw new Error(`${label}_invalid`);
  return value;
}

function boundedInteger(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${label}_invalid`);
  return value;
}

function port(value, label, minimum) {
  return boundedInteger(value, minimum, 65535, label);
}

function exactValue(value, expected, label) {
  if (value !== expected) throw new Error(`${label}_invalid`);
  return value;
}

function canonicalTimestamp(value, label) {
  if (typeof value !== "string" || !value.endsWith("Z")) throw new Error(`${label}_invalid`);
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== value) throw new Error(`${label}_invalid`);
  return value;
}

function fail(cause) {
  throw new OpsError("config", cause, "production_audit_config_validate");
}
