import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { lstat, readFile } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const CONFIG_KEYS = [
  "schemaVersion",
  "environment",
  "operator",
  "artifactManifest",
  "paths",
  "service",
  "gateway",
  "secrets",
  "nginx",
];
const MANIFEST_KEYS = [
  "schemaVersion",
  "kind",
  "serverVersion",
  "sourceCommit",
  "imageReference",
  "imageId",
  "architecture",
  "archiveFile",
  "archiveSha256",
  "createdAt",
];

export async function loadOpsConfig(filePath) {
  const raw = await readStrictJson(filePath, 64 * 1024, "config");
  try {
    exactKeys(raw, CONFIG_KEYS, "config");
    exactKeys(raw.paths, ["installRoot", "configRoot", "stateRoot", "systemdUnitDirectory"], "paths");
    exactKeys(raw.service, ["name", "containerName", "gatewayPort"], "service");
    exactKeys(raw.gateway, ["defaultDeviceId", "accountAuthEnabled", "accountBindingEnabled"], "gateway");
    exactKeys(raw.secrets, ["appTokenSource", "connectorTokenSource", "internalStatusTokenSource"], "secrets");
    exactKeys(raw.nginx, ["serverName", "listenPort", "certificateSource", "privateKeySource", "configFile"], "nginx");

    if (raw.schemaVersion !== 1) fail("unsupported_config_schema");
    if (raw.environment !== "staging") fail("r3_accepts_staging_only");
    token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator");

    const config = {
      schemaVersion: 1,
      environment: "staging",
      operator: raw.operator,
      artifactManifest: absolutePath(raw.artifactManifest, "artifactManifest"),
      paths: {
        installRoot: absolutePath(raw.paths.installRoot, "installRoot"),
        configRoot: absolutePath(raw.paths.configRoot, "configRoot"),
        stateRoot: absolutePath(raw.paths.stateRoot, "stateRoot"),
        systemdUnitDirectory: absolutePath(raw.paths.systemdUnitDirectory, "systemdUnitDirectory"),
      },
      service: {
        name: token(raw.service.name, /^[a-z0-9][a-z0-9.-]{0,62}$/, "service.name"),
        containerName: token(raw.service.containerName, /^[a-z0-9][a-z0-9_.-]{0,62}$/, "service.containerName"),
        gatewayPort: port(raw.service.gatewayPort, "service.gatewayPort", 1024),
      },
      gateway: {
        defaultDeviceId: token(raw.gateway.defaultDeviceId, /^[A-Za-z0-9._-]{1,64}$/, "defaultDeviceId"),
        accountAuthEnabled: raw.gateway.accountAuthEnabled,
        accountBindingEnabled: raw.gateway.accountBindingEnabled,
      },
      secrets: {
        appTokenSource: absolutePath(raw.secrets.appTokenSource, "appTokenSource"),
        connectorTokenSource: absolutePath(raw.secrets.connectorTokenSource, "connectorTokenSource"),
        internalStatusTokenSource: absolutePath(raw.secrets.internalStatusTokenSource, "internalStatusTokenSource"),
      },
      nginx: {
        serverName: token(raw.nginx.serverName, /^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/, "serverName"),
        listenPort: port(raw.nginx.listenPort, "nginx.listenPort", 1),
        certificateSource: absolutePath(raw.nginx.certificateSource, "certificateSource"),
        privateKeySource: absolutePath(raw.nginx.privateKeySource, "privateKeySource"),
        configFile: absolutePath(raw.nginx.configFile, "nginx.configFile"),
      },
    };

    if (config.gateway.accountAuthEnabled !== false || config.gateway.accountBindingEnabled !== false) {
      fail("r3_account_features_must_stay_disabled");
    }
    if (config.service.gatewayPort === config.nginx.listenPort) fail("gateway_and_nginx_ports_must_differ");
    if (!config.nginx.configFile.endsWith(".conf")) fail("nginx_config_requires_conf_suffix");
    if (config.nginx.serverName.includes("..")) fail("server_name_invalid");

    const roots = [config.paths.installRoot, config.paths.configRoot, config.paths.stateRoot];
    if (new Set(roots).size !== roots.length) fail("managed_roots_must_be_distinct");
    if (roots.some((root) => !path.basename(root).startsWith("hermes-go"))) {
      fail("managed_root_basename_must_start_with_hermes_go");
    }
    if (roots.some((root, index) => roots.some((other, otherIndex) => index !== otherIndex && other.startsWith(`${root}/`)))) {
      fail("managed_roots_must_not_overlap");
    }
    if (!path.basename(config.nginx.configFile).startsWith("hermes-go")) {
      fail("nginx_config_basename_must_start_with_hermes_go");
    }
    const inputs = [
      ...Object.values(config.secrets),
      config.nginx.certificateSource,
      config.nginx.privateKeySource,
      config.artifactManifest,
    ];
    if (new Set(inputs).size !== inputs.length) fail("input_files_must_be_distinct");
    if (inputs.some((input) => roots.some((root) => input === root || input.startsWith(`${root}/`)))) {
      fail("input_files_must_be_outside_managed_roots");
    }

    return config;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError("config", error instanceof Error ? error.message : error, "config_validate");
  }
}

export async function loadBundleManifest(manifestPath, { verifyArchive = true } = {}) {
  const manifest = await readStrictJson(manifestPath, 16 * 1024, "artifact");
  try {
    exactKeys(manifest, MANIFEST_KEYS, "artifact_manifest");
    if (manifest.schemaVersion !== 1 || manifest.kind !== "hermes-go-gateway-oci") failArtifact("unsupported_bundle_manifest");
    token(manifest.serverVersion, /^\d+\.\d+\.\d+$/, "serverVersion", "artifact");
    token(manifest.sourceCommit, /^[0-9a-f]{40}$/, "sourceCommit", "artifact");
    token(manifest.imageReference, /^hermes-remote-gateway:[A-Za-z0-9._-]+$/, "imageReference", "artifact");
    token(manifest.imageId, /^sha256:[0-9a-f]{64}$/, "imageId", "artifact");
    if (manifest.architecture !== "amd64") failArtifact("bundle_architecture_must_be_amd64");
    token(manifest.archiveSha256, /^[0-9a-f]{64}$/, "archiveSha256", "artifact");
    if (manifest.archiveFile !== path.basename(manifest.archiveFile)) failArtifact("archive_file_must_be_basename");
    const expectedStem = `Hermes-Gateway-${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}-linux-amd64`;
    if (manifest.archiveFile !== `${expectedStem}.tar`) failArtifact("archive_filename_identity_mismatch");
    if (manifest.imageReference !== `hermes-remote-gateway:${manifest.serverVersion}-${manifest.sourceCommit.slice(0, 12)}`) {
      failArtifact("image_reference_identity_mismatch");
    }
    if (!Number.isFinite(Date.parse(manifest.createdAt)) || !manifest.createdAt.endsWith("Z")) {
      failArtifact("invalid_bundle_created_at");
    }

    const archivePath = path.join(path.dirname(manifestPath), manifest.archiveFile);
    if (verifyArchive) {
      try {
        await assertRegularFile(archivePath, "artifact_archive");
      } catch (error) {
        throw new OpsError("artifact", error instanceof Error ? error.technicalCause || error.message : error, "artifact_archive_inspect");
      }
      const actualSha256 = await sha256File(archivePath);
      if (actualSha256 !== manifest.archiveSha256) failArtifact("archive_sha256_mismatch");
    }
    return { ...manifest, archivePath };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError("artifact", error instanceof Error ? error.message : error, "artifact_validate");
  }
}

export function deploymentDigest(config, manifest, inputMaterialFingerprint = "") {
  const stable = {
    schemaVersion: config.schemaVersion,
    environment: config.environment,
    artifact: MANIFEST_KEYS.reduce((value, key) => ({ ...value, [key]: manifest[key] }), {}),
    paths: config.paths,
    service: config.service,
    gateway: config.gateway,
    nginx: {
      serverName: config.nginx.serverName,
      listenPort: config.nginx.listenPort,
      configFile: config.nginx.configFile,
    },
    inputMaterialFingerprint,
  };
  return createHash("sha256").update(JSON.stringify(stable)).digest("hex");
}

export async function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = createHash("sha256");
    const stream = createReadStream(filePath);
    stream.on("error", reject);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

export async function assertRegularFile(filePath, label) {
  let info;
  try {
    await assertNoSymlinkComponents(filePath, label);
    info = await lstat(filePath);
  } catch {
    throw new OpsError("config", `${label}_missing`, `${label}_inspect`);
  }
  if (info.isSymbolicLink() || !info.isFile()) {
    throw new OpsError("config", `${label}_must_be_regular_file`, `${label}_inspect`);
  }
  if ((info.mode & 0o022) !== 0) {
    throw new OpsError("config", `${label}_must_not_be_group_or_world_writable`, `${label}_inspect`);
  }
  return info;
}

async function assertNoSymlinkComponents(filePath, label) {
  const absolute = path.resolve(filePath);
  let cursor = path.parse(absolute).root;
  for (const segment of absolute.slice(cursor.length).split(path.sep).filter(Boolean)) {
    cursor = path.join(cursor, segment);
    try {
      const info = await lstat(cursor);
      if (info.isSymbolicLink()) throw new OpsError("config", `${label}_symlink_rejected`, `${label}_inspect`);
    } catch (error) {
      if (error instanceof OpsError) throw error;
      if (error?.code === "ENOENT") return;
      throw error;
    }
  }
}

async function readStrictJson(filePath, maximumBytes, kind) {
  try {
    const info = await assertRegularFile(filePath, `${kind}_file`);
    if (info.size < 2 || info.size > maximumBytes) throw new Error(`${kind}_file_size_invalid`);
    return JSON.parse(await readFile(filePath, "utf8"));
  } catch (error) {
    if (error instanceof OpsError) {
      if (kind === "artifact" && error.kind !== "artifact") {
        throw new OpsError("artifact", error.technicalCause, "artifact_manifest_read");
      }
      throw error;
    }
    throw new OpsError(kind === "artifact" ? "artifact" : "config", error instanceof Error ? error.message : error, `${kind}_read`);
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

function token(value, pattern, label, kind = "config") {
  if (typeof value !== "string" || !pattern.test(value)) {
    if (kind === "artifact") failArtifact(`${label}_invalid`);
    throw new Error(`${label}_invalid`);
  }
  return value;
}

function port(value, label, minimum) {
  if (!Number.isInteger(value) || value < minimum || value > 65535) throw new Error(`${label}_invalid`);
  return value;
}

function fail(cause) {
  throw new OpsError("config", cause, "config_validate");
}

function failArtifact(cause) {
  throw new OpsError("artifact", cause, "artifact_validate");
}
