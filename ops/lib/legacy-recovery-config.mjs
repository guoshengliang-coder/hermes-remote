import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { assertRegularFile } from "./config.mjs";
import { OpsError } from "./errors.mjs";

const CAPTURE_ROLES = Object.freeze(["configuration", "lifecycle", "nginx", "runtime", "systemd"]);
const SHA256 = /^[0-9a-f]{64}$/;
const HOSTNAME = /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/;

export async function loadLegacyCaptureConfig(filePath) {
  const raw = await readStrictJson(filePath, 128 * 1024, "legacy_capture_config");
  try {
    exactKeys(raw, [
      "schemaVersion", "environment", "operator", "sourceHostname", "service",
      "recipientCertificate", "archiveFile", "manifestFile", "maximumTotalBytes",
      "roots", "identityFiles",
    ], "legacy_capture_config");
    exactKeys(raw.service, [
      "name", "workingDirectory", "entrypoint", "environmentFile", "healthPath", "authenticatedPath",
    ], "legacy_capture_service");
    if (raw.schemaVersion !== 1) fail("legacy_capture_schema_unsupported");
    if (raw.environment !== "production") fail("legacy_capture_requires_production_environment");

    const config = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      sourceHostname: hostname(raw.sourceHostname, "sourceHostname"),
      service: {
        name: token(raw.service.name, /^[a-z0-9][a-z0-9.-]{0,62}$/, "service.name"),
        workingDirectory: absolutePath(raw.service.workingDirectory, "service.workingDirectory"),
        entrypoint: absolutePath(raw.service.entrypoint, "service.entrypoint"),
        environmentFile: absolutePath(raw.service.environmentFile, "service.environmentFile"),
        healthPath: httpPath(raw.service.healthPath, "service.healthPath"),
        authenticatedPath: httpPath(raw.service.authenticatedPath, "service.authenticatedPath"),
      },
      recipientCertificate: absolutePath(raw.recipientCertificate, "recipientCertificate"),
      archiveFile: absolutePath(raw.archiveFile, "archiveFile"),
      manifestFile: absolutePath(raw.manifestFile, "manifestFile"),
      maximumTotalBytes: boundedInteger(raw.maximumTotalBytes, 1024, 8 * 1024 * 1024 * 1024, "maximumTotalBytes"),
      roots: captureRoots(raw.roots),
      identityFiles: identityFiles(raw.identityFiles),
    };
    const inputs = [
      config.recipientCertificate,
      ...config.roots.map((entry) => entry.path),
      ...config.identityFiles.map((entry) => entry.path),
    ];
    if (config.archiveFile === config.manifestFile || inputs.includes(config.archiveFile) || inputs.includes(config.manifestFile)) {
      fail("legacy_capture_paths_must_be_distinct");
    }
    for (const output of [config.archiveFile, config.manifestFile]) {
      if (config.roots.some((root) => within(root.path, output))) fail("legacy_capture_output_inside_source");
    }
    for (const required of [config.service.workingDirectory, config.service.entrypoint, config.service.environmentFile]) {
      if (!config.roots.some((root) => within(root.path, required))) fail("legacy_capture_service_path_outside_roots");
    }
    for (const identity of config.identityFiles) {
      if (!config.roots.some((root) => within(root.path, identity.path))) fail("legacy_capture_identity_outside_roots");
    }
    if (!config.identityFiles.some((identity) => identity.path === config.service.entrypoint)) {
      fail("legacy_capture_entrypoint_identity_required");
    }
    return config;
  } catch (error) {
    wrap(error, "legacy_capture_config_validate");
  }
}

export async function loadLegacyRestoreConfig(filePath) {
  const raw = await readStrictJson(filePath, 64 * 1024, "legacy_restore_config");
  try {
    exactKeys(raw, [
      "schemaVersion", "environment", "operator", "expectedSourceHostname", "archiveFile", "manifestFile",
      "recipientCertificate", "recipientPrivateKey", "restoreRoot", "listenPort", "evidenceFile",
    ], "legacy_restore_config");
    if (raw.schemaVersion !== 1) fail("legacy_restore_schema_unsupported");
    if (raw.environment !== "isolated-restore") fail("legacy_restore_requires_isolated_environment");
    const config = {
      schemaVersion: 1,
      environment: "isolated-restore",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      expectedSourceHostname: hostname(raw.expectedSourceHostname, "expectedSourceHostname"),
      archiveFile: absolutePath(raw.archiveFile, "archiveFile"),
      manifestFile: absolutePath(raw.manifestFile, "manifestFile"),
      recipientCertificate: absolutePath(raw.recipientCertificate, "recipientCertificate"),
      recipientPrivateKey: absolutePath(raw.recipientPrivateKey, "recipientPrivateKey"),
      restoreRoot: absolutePath(raw.restoreRoot, "restoreRoot"),
      listenPort: boundedInteger(raw.listenPort, 1024, 65535, "listenPort"),
      evidenceFile: absolutePath(raw.evidenceFile, "evidenceFile"),
    };
    const paths = [
      config.archiveFile, config.manifestFile, config.recipientCertificate, config.recipientPrivateKey,
      config.restoreRoot, config.evidenceFile,
    ];
    if (new Set(paths).size !== paths.length) fail("legacy_restore_paths_must_be_distinct");
    if (paths.filter((entry) => entry !== config.restoreRoot).some((entry) => within(config.restoreRoot, entry))) {
      fail("legacy_restore_input_inside_restore_root");
    }
    return config;
  } catch (error) {
    wrap(error, "legacy_restore_config_validate");
  }
}

export async function loadLegacyArchiveManifest(filePath) {
  const raw = await readStrictJson(filePath, 16 * 1024 * 1024, "legacy_archive_manifest");
  try {
    exactKeys(raw, [
      "schemaVersion", "kind", "sourceHostname", "createdAt", "service", "captureRoots", "encryption",
      "archiveSha256", "archiveBytes", "identityFiles", "subject", "entries",
    ], "legacy_archive_manifest");
    if (raw.schemaVersion !== 1 || raw.kind !== "hermes-go-legacy-recovery-archive-v1") {
      fail("legacy_archive_manifest_kind_invalid");
    }
    exactKeys(raw.service, ["name", "workingDirectory", "entrypoint", "environmentFile", "healthPath", "authenticatedPath"], "legacy_archive_service");
    exactKeys(raw.encryption, ["kind", "recipientCertificateSha256"], "legacy_archive_encryption");
    exactKeys(raw.subject, ["identityDigest"], "legacy_archive_subject");
    if (raw.encryption.kind !== "openssl-cms-auth-enveloped-aes-256-gcm") fail("legacy_archive_encryption_invalid");
    if (!Array.isArray(raw.entries) || raw.entries.length < 1 || raw.entries.length > 50_000) fail("legacy_archive_entries_invalid");
    const entries = raw.entries.map(manifestEntry);
    if (new Set(entries.map((entry) => entry.path)).size !== entries.length) fail("legacy_archive_entries_duplicate");
    const identities = identityFiles(raw.identityFiles);
    const identityDigest = createHash("sha256")
      .update(JSON.stringify([...identities].sort((left, right) => left.path.localeCompare(right.path))))
      .digest("hex");
    if (identityDigest !== raw.subject.identityDigest) fail("legacy_archive_identity_digest_mismatch");
    if (!identities.some((identity) => identity.path === raw.service.entrypoint)) fail("legacy_archive_entrypoint_identity_missing");
    for (const identity of identities) {
      const entry = entries.find((candidate) => candidate.path === identity.path);
      if (!entry || entry.type !== "file" || entry.sha256 !== identity.sha256) fail("legacy_archive_identity_entry_mismatch");
    }
    return {
      schemaVersion: 1,
      kind: raw.kind,
      sourceHostname: hostname(raw.sourceHostname, "sourceHostname"),
      createdAt: canonicalTimestamp(raw.createdAt, "createdAt"),
      service: {
        name: token(raw.service.name, /^[a-z0-9][a-z0-9.-]{0,62}$/, "service.name"),
        workingDirectory: absolutePath(raw.service.workingDirectory, "service.workingDirectory"),
        entrypoint: absolutePath(raw.service.entrypoint, "service.entrypoint"),
        environmentFile: absolutePath(raw.service.environmentFile, "service.environmentFile"),
        healthPath: httpPath(raw.service.healthPath, "service.healthPath"),
        authenticatedPath: httpPath(raw.service.authenticatedPath, "service.authenticatedPath"),
      },
      captureRoots: captureRoots(raw.captureRoots),
      encryption: {
        kind: raw.encryption.kind,
        recipientCertificateSha256: token(raw.encryption.recipientCertificateSha256, SHA256, "recipientCertificateSha256"),
      },
      archiveSha256: token(raw.archiveSha256, SHA256, "archiveSha256"),
      archiveBytes: boundedInteger(raw.archiveBytes, 1, 16 * 1024 * 1024 * 1024, "archiveBytes"),
      identityFiles: identities,
      subject: { identityDigest },
      entries,
    };
  } catch (error) {
    wrap(error, "legacy_archive_manifest_validate");
  }
}

function captureRoots(value) {
  if (!Array.isArray(value) || value.length < CAPTURE_ROLES.length || value.length > 16) {
    throw new Error("legacy_capture_roots_invalid");
  }
  const roots = value.map((entry) => {
    exactKeys(entry, ["role", "path"], "legacy_capture_root");
    if (!CAPTURE_ROLES.includes(entry.role)) throw new Error("legacy_capture_role_invalid");
    return {
      role: token(entry.role, /^[a-z][a-z0-9_-]{0,31}$/, "root.role"),
      path: absolutePath(entry.path, "root.path"),
    };
  });
  if (CAPTURE_ROLES.some((role) => !roots.some((entry) => entry.role === role))) {
    throw new Error("legacy_capture_roles_incomplete");
  }
  if (new Set(roots.map((entry) => entry.path)).size !== roots.length) throw new Error("legacy_capture_root_paths_duplicate");
  return roots;
}

function identityFiles(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 16) throw new Error("legacy_identity_files_invalid");
  const result = value.map((entry) => {
    exactKeys(entry, ["path", "sha256"], "legacy_identity_file");
    return {
      path: absolutePath(entry.path, "identity.path"),
      sha256: token(entry.sha256, SHA256, "identity.sha256"),
    };
  });
  if (new Set(result.map((entry) => entry.path)).size !== result.length) throw new Error("legacy_identity_paths_duplicate");
  return result;
}

function manifestEntry(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("legacy_archive_entry_invalid");
  if (value.type === "file") {
    exactKeys(value, ["path", "type", "mode", "size", "sha256"], "legacy_archive_file");
    return {
      path: absolutePath(value.path, "entry.path"), type: "file",
      mode: boundedInteger(value.mode, 0, 0o7777, "entry.mode"),
      size: boundedInteger(value.size, 0, 8 * 1024 * 1024 * 1024, "entry.size"),
      sha256: token(value.sha256, SHA256, "entry.sha256"),
    };
  }
  if (value.type === "directory") {
    exactKeys(value, ["path", "type", "mode"], "legacy_archive_directory");
    return { path: absolutePath(value.path, "entry.path"), type: "directory", mode: boundedInteger(value.mode, 0, 0o7777, "entry.mode") };
  }
  if (value.type === "symlink") {
    exactKeys(value, ["path", "type", "mode", "linkTarget", "sha256"], "legacy_archive_symlink");
    if (typeof value.linkTarget !== "string" || path.isAbsolute(value.linkTarget) || value.linkTarget.length < 1 || value.linkTarget.length > 4096 || /[\u0000-\u001f\u007f]/.test(value.linkTarget)) {
      throw new Error("legacy_archive_link_target_invalid");
    }
    return {
      path: absolutePath(value.path, "entry.path"), type: "symlink",
      mode: boundedInteger(value.mode, 0, 0o7777, "entry.mode"), linkTarget: value.linkTarget,
      sha256: token(value.sha256, SHA256, "entry.sha256"),
    };
  }
  throw new Error("legacy_archive_entry_type_invalid");
}

async function readStrictJson(filePath, maximumBytes, label) {
  try {
    const info = await assertRegularFile(filePath, label);
    if (info.size < 2 || info.size > maximumBytes) throw new Error(`${label}_size_invalid`);
    return JSON.parse(await readFile(filePath, "utf8"));
  } catch (error) {
    wrap(error, `${label}_read`);
  }
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label}_must_be_object`);
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) throw new Error(`${label}_fields_invalid`);
}

function absolutePath(value, label) {
  if (typeof value !== "string" || !path.isAbsolute(value) || path.normalize(value) !== value || value === "/") throw new Error(`${label}_path_invalid`);
  if (value.includes("//") || /[\u0000-\u001f\u007f]/.test(value)) throw new Error(`${label}_path_unsafe`);
  return value;
}

function within(root, target) {
  const relative = path.relative(root, target);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== "..");
}

function hostname(value, label) {
  const result = token(value, HOSTNAME, label);
  if (result.includes("..")) throw new Error(`${label}_invalid`);
  return result;
}

function httpPath(value, label) {
  return token(value, /^\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,255}$/, label);
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) throw new Error(`${label}_invalid`);
  return value;
}

function boundedInteger(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${label}_invalid`);
  return value;
}

function canonicalTimestamp(value, label) {
  if (typeof value !== "string" || !value.endsWith("Z")) throw new Error(`${label}_invalid`);
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString() !== value) throw new Error(`${label}_invalid`);
  return value;
}

function fail(cause) {
  throw new OpsError("recovery", cause, "legacy_recovery_config_validate");
}

function wrap(error, stage) {
  if (error instanceof OpsError && error.kind === "recovery") throw error;
  throw new OpsError(
    "recovery",
    error instanceof OpsError ? error.technicalCause : error instanceof Error ? error.message : error,
    stage,
  );
}
