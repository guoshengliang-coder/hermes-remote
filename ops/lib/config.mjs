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
const DEPLOY_CONFIG_KEYS = [
  "schemaVersion",
  "environment",
  "operator",
  "targetArtifactManifest",
  "paths",
  "legacySource",
  "slots",
  "gateway",
  "secrets",
  "database",
  "nginx",
  "deployment",
];
const MANIFEST_V1_KEYS = [
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
const MANIFEST_V2_KEYS = [...MANIFEST_V1_KEYS, "releaseContract"];
const RELEASE_CONTRACT_KEYS = [
  "manifestVersion",
  "configSchemaVersion",
  "databaseSchemaVersion",
  "supportedPostgresqlMajors",
  "protocolVersions",
  "minimumClients",
  "minimumSourceVersion",
  "maintenanceRequired",
  "rollbackSupported",
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

export async function loadDeployConfig(filePath) {
  const raw = await readStrictJson(filePath, 64 * 1024, "config");
  try {
    exactKeys(raw, DEPLOY_CONFIG_KEYS, "deploy_config");
    exactKeys(raw.paths, ["installRoot", "configRoot", "stateRoot", "systemdUnitDirectory"], "paths");
    exactKeys(raw.legacySource, ["serviceName", "containerName", "gatewayPort", "stateDirectory"], "legacy_source");
    exactKeys(raw.slots, ["blue", "green"], "slots");
    exactKeys(raw.gateway, ["defaultDeviceId", "accountAuthEnabled", "accountBindingEnabled"], "gateway");
    exactKeys(raw.secrets, ["appTokenSource", "connectorTokenSource", "internalStatusTokenSource"], "secrets");
    exactKeys(raw.nginx, [
      "serverName",
      "listenPort",
      "certificateSource",
      "privateKeySource",
      "configFile",
      "upstreamConfigFile",
    ], "nginx");
    exactKeys(raw.deployment, ["drainTimeoutSeconds", "observationSeconds"], "deployment");

    if (raw.schemaVersion !== 2) fail("unsupported_deploy_config_schema");
    if (raw.environment !== "staging") fail("r4_accepts_staging_only");
    token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator");

    const config = {
      schemaVersion: 2,
      environment: "staging",
      operator: raw.operator,
      targetArtifactManifest: absolutePath(raw.targetArtifactManifest, "targetArtifactManifest"),
      paths: {
        installRoot: absolutePath(raw.paths.installRoot, "installRoot"),
        configRoot: absolutePath(raw.paths.configRoot, "configRoot"),
        stateRoot: absolutePath(raw.paths.stateRoot, "stateRoot"),
        systemdUnitDirectory: absolutePath(raw.paths.systemdUnitDirectory, "systemdUnitDirectory"),
      },
      legacySource: {
        serviceName: token(raw.legacySource.serviceName, /^[a-z0-9][a-z0-9.-]{0,62}$/, "legacySource.serviceName"),
        containerName: token(raw.legacySource.containerName, /^[a-z0-9][a-z0-9_.-]{0,62}$/, "legacySource.containerName"),
        gatewayPort: port(raw.legacySource.gatewayPort, "legacySource.gatewayPort", 1024),
        stateDirectory: absolutePath(raw.legacySource.stateDirectory, "legacySource.stateDirectory"),
      },
      slots: {
        blue: deploySlot(raw.slots.blue, "blue"),
        green: deploySlot(raw.slots.green, "green"),
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
      database: deployDatabase(raw.database),
      nginx: {
        serverName: token(raw.nginx.serverName, /^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/, "serverName"),
        listenPort: port(raw.nginx.listenPort, "nginx.listenPort", 1),
        certificateSource: absolutePath(raw.nginx.certificateSource, "certificateSource"),
        privateKeySource: absolutePath(raw.nginx.privateKeySource, "privateKeySource"),
        configFile: absolutePath(raw.nginx.configFile, "nginx.configFile"),
        upstreamConfigFile: absolutePath(raw.nginx.upstreamConfigFile, "nginx.upstreamConfigFile"),
      },
      deployment: {
        drainTimeoutSeconds: boundedInteger(raw.deployment.drainTimeoutSeconds, 1, 600, "drainTimeoutSeconds"),
        observationSeconds: boundedInteger(raw.deployment.observationSeconds, 1, 300, "observationSeconds"),
      },
    };

    if (config.gateway.accountAuthEnabled !== false || config.gateway.accountBindingEnabled !== false) {
      fail("r4_account_features_must_stay_disabled");
    }
    const slotValues = Object.values(config.slots);
    for (const field of ["serviceName", "containerName", "gatewayPort"]) {
      if (new Set(slotValues.map((slot) => slot[field])).size !== 2) fail(`slot_${field}_must_be_distinct`);
    }
    if (slotValues.some((slot) => slot.gatewayPort === config.nginx.listenPort)) {
      fail("slot_and_nginx_ports_must_differ");
    }
    if (config.nginx.serverName.includes("..")) fail("server_name_invalid");
    if (config.nginx.configFile === config.nginx.upstreamConfigFile) fail("nginx_files_must_be_distinct");
    for (const configFile of [config.nginx.configFile, config.nginx.upstreamConfigFile]) {
      if (!configFile.endsWith(".conf")) fail("nginx_config_requires_conf_suffix");
      if (!path.basename(configFile).startsWith("hermes-go")) {
        fail("nginx_config_basename_must_start_with_hermes_go");
      }
    }

    const roots = [config.paths.installRoot, config.paths.configRoot, config.paths.stateRoot];
    validateManagedRoots(roots);
    if (config.legacySource.stateDirectory !== path.join(config.paths.stateRoot, "gateway")) {
      fail("legacy_state_directory_must_match_bootstrap_layout");
    }
    if (Object.values(config.slots).some((slot) => slot.serviceName === config.legacySource.serviceName
        || slot.containerName === config.legacySource.containerName
        || slot.gatewayPort === config.legacySource.gatewayPort)) {
      fail("legacy_and_slot_resources_must_be_distinct");
    }
    if (config.legacySource.gatewayPort === config.nginx.listenPort) fail("legacy_and_nginx_ports_must_differ");
    const inputs = [
      ...Object.values(config.secrets),
      config.nginx.certificateSource,
      config.nginx.privateKeySource,
      config.targetArtifactManifest,
      ...(config.database ? [config.database.urlSource] : []),
    ];
    if (new Set(inputs).size !== inputs.length) fail("input_files_must_be_distinct");
    if (inputs.some((input) => roots.some((root) => input === root || input.startsWith(`${root}/`)))) {
      fail("input_files_must_be_outside_managed_roots");
    }

    return config;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError("config", error instanceof Error ? error.message : error, "deploy_config_validate");
  }
}

export async function loadBundleManifest(manifestPath, { verifyArchive = true } = {}) {
  const manifest = await readStrictJson(manifestPath, 16 * 1024, "artifact");
  try {
    const manifestKeys = manifest.schemaVersion === 1
      ? MANIFEST_V1_KEYS
      : manifest.schemaVersion === 2
        ? MANIFEST_V2_KEYS
        : undefined;
    if (!manifestKeys || manifest.kind !== "hermes-go-gateway-oci") failArtifact("unsupported_bundle_manifest");
    exactKeys(manifest, manifestKeys, "artifact_manifest");
    token(manifest.serverVersion, /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/, "serverVersion", "artifact");
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
    if (manifest.schemaVersion === 2) validateReleaseContract(manifest.releaseContract);

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
    artifact: manifestIdentity(manifest),
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

export function manifestIdentity(manifest) {
  const keys = manifest.schemaVersion === 2 ? MANIFEST_V2_KEYS : MANIFEST_V1_KEYS;
  return keys.reduce((value, key) => ({
    ...value,
    [key]: key === "releaseContract" ? releaseContractIdentity(manifest[key]) : manifest[key],
  }), {});
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

function validateReleaseContract(value) {
  exactKeys(value, RELEASE_CONTRACT_KEYS, "release_contract");
  exactKeys(value.protocolVersions, ["legacy", "accountConnector"], "release_protocol_versions");
  exactKeys(value.minimumClients, ["android", "desktop", "connector"], "release_minimum_clients");
  for (const name of ["manifestVersion", "configSchemaVersion", "databaseSchemaVersion"]) {
    if (!Number.isSafeInteger(value[name]) || value[name] < 1) failArtifact(`${name}_invalid`);
  }
  if (!Array.isArray(value.supportedPostgresqlMajors)
      || value.supportedPostgresqlMajors.length === 0
      || !value.supportedPostgresqlMajors.every((entry) => Number.isSafeInteger(entry) && entry > 0)
      || new Set(value.supportedPostgresqlMajors).size !== value.supportedPostgresqlMajors.length) {
    failArtifact("supported_postgresql_majors_invalid");
  }
  for (const name of ["legacy", "accountConnector"]) {
    if (!Number.isSafeInteger(value.protocolVersions[name]) || value.protocolVersions[name] < 1) {
      failArtifact(`protocol_${name}_invalid`);
    }
  }
  const versionPattern = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/;
  for (const name of ["android", "desktop", "connector"]) {
    token(value.minimumClients[name], versionPattern, `minimum_${name}`, "artifact");
  }
  token(value.minimumSourceVersion, versionPattern, "minimumSourceVersion", "artifact");
  if (typeof value.maintenanceRequired !== "boolean" || typeof value.rollbackSupported !== "boolean") {
    failArtifact("release_policy_flags_invalid");
  }
}

function releaseContractIdentity(value) {
  return {
    manifestVersion: value.manifestVersion,
    configSchemaVersion: value.configSchemaVersion,
    databaseSchemaVersion: value.databaseSchemaVersion,
    supportedPostgresqlMajors: [...value.supportedPostgresqlMajors],
    protocolVersions: {
      legacy: value.protocolVersions.legacy,
      accountConnector: value.protocolVersions.accountConnector,
    },
    minimumClients: {
      android: value.minimumClients.android,
      desktop: value.minimumClients.desktop,
      connector: value.minimumClients.connector,
    },
    minimumSourceVersion: value.minimumSourceVersion,
    maintenanceRequired: value.maintenanceRequired,
    rollbackSupported: value.rollbackSupported,
  };
}

function deploySlot(value, label) {
  exactKeys(value, ["serviceName", "containerName", "gatewayPort"], `slot_${label}`);
  return {
    serviceName: token(value.serviceName, /^[a-z0-9][a-z0-9.-]{0,62}$/, `slots.${label}.serviceName`),
    containerName: token(value.containerName, /^[a-z0-9][a-z0-9_.-]{0,62}$/, `slots.${label}.containerName`),
    gatewayPort: port(value.gatewayPort, `slots.${label}.gatewayPort`, 1024),
  };
}

function deployDatabase(value) {
  if (value === null) return null;
  exactKeys(value, ["urlSource", "ssl", "migrationLockId"], "database");
  if (typeof value.ssl !== "boolean") throw new Error("database.ssl_invalid");
  return {
    urlSource: absolutePath(value.urlSource, "database.urlSource"),
    ssl: value.ssl,
    migrationLockId: boundedInteger(value.migrationLockId, 1, Number.MAX_SAFE_INTEGER, "database.migrationLockId"),
  };
}

function validateManagedRoots(roots) {
  if (new Set(roots).size !== roots.length) fail("managed_roots_must_be_distinct");
  if (roots.some((root) => !path.basename(root).startsWith("hermes-go"))) {
    fail("managed_root_basename_must_start_with_hermes_go");
  }
  if (roots.some((root, index) => roots.some((other, otherIndex) => index !== otherIndex && other.startsWith(`${root}/`)))) {
    fail("managed_roots_must_not_overlap");
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

function boundedInteger(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${label}_invalid`);
  return value;
}

function fail(cause) {
  throw new OpsError("config", cause, "config_validate");
}

function failArtifact(cause) {
  throw new OpsError("artifact", cause, "artifact_validate");
}
