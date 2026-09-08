import { constants } from "node:fs";
import { open } from "node:fs/promises";
import path from "node:path";
import { OpsError } from "./errors.mjs";

const TOP_LEVEL_KEYS = Object.freeze([
  "schemaVersion",
  "environment",
  "operator",
  "targetArtifactManifest",
  "host",
  "paths",
  "legacySource",
  "slots",
  "gateway",
  "secrets",
  "database",
  "nginx",
  "deployment",
]);

export async function loadManagedBaselineConfig(filePath) {
  const raw = await readStrictJson(filePath);
  try {
    exactKeys(raw, TOP_LEVEL_KEYS, "managed_baseline_config");
    exactKeys(raw.host, ["hostname", "architecture"], "host");
    exactKeys(raw.legacySource, [
      "serviceName",
      "containerName",
      "gatewayPort",
      "stateDirectory",
      "compatibilityVersion",
      "identityFiles",
      "recoveryEvidence",
    ], "legacy_source");
    exactKeys(raw.paths, ["installRoot", "configRoot", "stateRoot", "systemdUnitDirectory"], "paths");
    exactKeys(raw.slots, ["blue", "green"], "slots");
    exactKeys(raw.gateway, ["defaultDeviceId", "accountAuthEnabled", "accountBindingEnabled"], "gateway");
    exactKeys(raw.secrets, ["appTokenSource", "connectorTokenSource", "internalStatusTokenSource"], "secrets");
    exactKeys(raw.nginx, [
      "serverName",
      "listenPort",
      "certificateSource",
      "privateKeySource",
      "candidateConfigSource",
      "candidateConfigSha256",
      "configFile",
      "upstreamConfigFile",
    ], "nginx");
    exactKeys(raw.deployment, ["drainTimeoutSeconds", "observationSeconds"], "deployment");

    if (raw.schemaVersion !== 1) fail("managed_baseline_schema_unsupported");
    if (raw.environment !== "production") fail("managed_baseline_requires_production");
    if (raw.database !== null) fail("managed_baseline_database_must_stay_disabled");

    const config = {
      schemaVersion: 1,
      environment: "production",
      operator: token(raw.operator, /^[A-Za-z0-9._-]{1,64}$/, "operator"),
      targetArtifactManifest: absolutePath(raw.targetArtifactManifest, "targetArtifactManifest"),
      host: {
        hostname: token(raw.host.hostname, /^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/, "host.hostname"),
        architecture: exactValue(raw.host.architecture, "amd64", "host.architecture"),
      },
      paths: {
        installRoot: absolutePath(raw.paths.installRoot, "paths.installRoot"),
        configRoot: absolutePath(raw.paths.configRoot, "paths.configRoot"),
        stateRoot: absolutePath(raw.paths.stateRoot, "paths.stateRoot"),
        systemdUnitDirectory: absolutePath(raw.paths.systemdUnitDirectory, "paths.systemdUnitDirectory"),
      },
      legacySource: {
        serviceName: token(raw.legacySource.serviceName, /^[a-z0-9][a-z0-9.-]{0,62}$/, "legacySource.serviceName"),
        containerName: token(raw.legacySource.containerName, /^[a-z0-9][a-z0-9_.-]{0,62}$/, "legacySource.containerName"),
        gatewayPort: port(raw.legacySource.gatewayPort, "legacySource.gatewayPort", 1024),
        stateDirectory: absolutePath(raw.legacySource.stateDirectory, "legacySource.stateDirectory"),
        compatibilityVersion: token(
          raw.legacySource.compatibilityVersion,
          /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/,
          "legacySource.compatibilityVersion",
        ),
        identityFiles: identityFiles(raw.legacySource.identityFiles),
        recoveryEvidence: absolutePath(raw.legacySource.recoveryEvidence, "legacySource.recoveryEvidence"),
      },
      slots: {
        blue: slot(raw.slots.blue, "blue"),
        green: slot(raw.slots.green, "green"),
      },
      gateway: {
        defaultDeviceId: token(raw.gateway.defaultDeviceId, /^[A-Za-z0-9._-]{1,64}$/, "gateway.defaultDeviceId"),
        accountAuthEnabled: raw.gateway.accountAuthEnabled,
        accountBindingEnabled: raw.gateway.accountBindingEnabled,
      },
      secrets: {
        appTokenSource: absolutePath(raw.secrets.appTokenSource, "secrets.appTokenSource"),
        connectorTokenSource: absolutePath(raw.secrets.connectorTokenSource, "secrets.connectorTokenSource"),
        internalStatusTokenSource: absolutePath(raw.secrets.internalStatusTokenSource, "secrets.internalStatusTokenSource"),
      },
      database: null,
      nginx: {
        serverName: token(
          raw.nginx.serverName,
          /^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/,
          "nginx.serverName",
        ),
        listenPort: port(raw.nginx.listenPort, "nginx.listenPort", 1),
        certificateSource: absolutePath(raw.nginx.certificateSource, "nginx.certificateSource"),
        privateKeySource: absolutePath(raw.nginx.privateKeySource, "nginx.privateKeySource"),
        candidateConfigSource: absolutePath(raw.nginx.candidateConfigSource, "nginx.candidateConfigSource"),
        candidateConfigSha256: token(raw.nginx.candidateConfigSha256, /^[0-9a-f]{64}$/, "nginx.candidateConfigSha256"),
        configFile: absolutePath(raw.nginx.configFile, "nginx.configFile"),
        upstreamConfigFile: absolutePath(raw.nginx.upstreamConfigFile, "nginx.upstreamConfigFile"),
      },
      deployment: {
        drainTimeoutSeconds: boundedInteger(raw.deployment.drainTimeoutSeconds, 1, 600, "drainTimeoutSeconds"),
        observationSeconds: boundedInteger(raw.deployment.observationSeconds, 1, 300, "observationSeconds"),
      },
      managedBaseline: true,
    };

    if (config.gateway.accountAuthEnabled !== false || config.gateway.accountBindingEnabled !== false) {
      fail("managed_baseline_account_features_must_stay_disabled");
    }
    const slotValues = Object.values(config.slots);
    for (const field of ["serviceName", "containerName", "gatewayPort"]) {
      if (new Set(slotValues.map((entry) => entry[field])).size !== 2) fail(`slot_${field}_must_be_distinct`);
    }
    if (slotValues.some((entry) => entry.serviceName === config.legacySource.serviceName
        || entry.containerName === config.legacySource.containerName
        || entry.gatewayPort === config.legacySource.gatewayPort)) {
      fail("legacy_and_slot_resources_must_be_distinct");
    }
    const ports = [
      config.legacySource.gatewayPort,
      config.slots.blue.gatewayPort,
      config.slots.green.gatewayPort,
      config.nginx.listenPort,
    ];
    if (new Set(ports).size !== ports.length) fail("managed_baseline_ports_must_be_distinct");
    if (config.nginx.configFile === config.nginx.upstreamConfigFile
        || !config.nginx.upstreamConfigFile.endsWith(".conf")) {
      fail("managed_baseline_nginx_files_invalid");
    }
    const nginxConfigBasename = path.basename(config.nginx.configFile);
    if (!(nginxConfigBasename === "hermes-edge.conf" || /^hermes-(?:go|remote)-/.test(nginxConfigBasename))
        || !path.basename(config.nginx.upstreamConfigFile).startsWith("hermes-go")) {
      fail("managed_baseline_nginx_basename_invalid");
    }

    const roots = [config.paths.installRoot, config.paths.configRoot, config.paths.stateRoot];
    if (new Set(roots).size !== roots.length
        || roots.some((root) => root === "/" || roots.some((other) => root !== other && root.startsWith(`${other}/`)))) {
      fail("managed_baseline_roots_invalid");
    }
    const inputs = [
      config.targetArtifactManifest,
      config.legacySource.recoveryEvidence,
      ...config.legacySource.identityFiles.map((entry) => entry.path),
      ...Object.values(config.secrets),
      config.nginx.certificateSource,
      config.nginx.privateKeySource,
      config.nginx.candidateConfigSource,
    ];
    if (new Set(inputs).size !== inputs.length) fail("managed_baseline_inputs_must_be_distinct");
    if (inputs.some((input) => roots.some((root) => input === root || input.startsWith(`${root}/`)))) {
      fail("managed_baseline_inputs_inside_managed_roots");
    }
    if (config.legacySource.stateDirectory === config.paths.stateRoot
        || config.legacySource.stateDirectory.startsWith(`${config.paths.stateRoot}/`)) {
      fail("managed_baseline_legacy_state_inside_managed_root");
    }
    return config;
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  }
}

function identityFiles(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 16) throw new Error("legacy_identity_files_invalid");
  const result = value.map((entry) => {
    exactKeys(entry, ["path", "sha256"], "legacy_identity_file");
    return {
      path: absolutePath(entry.path, "legacy_identity_file.path"),
      sha256: token(entry.sha256, /^[0-9a-f]{64}$/, "legacy_identity_file.sha256"),
    };
  });
  if (new Set(result.map((entry) => entry.path)).size !== result.length) throw new Error("legacy_identity_paths_duplicate");
  return result.sort((left, right) => left.path.localeCompare(right.path));
}

function slot(value, name) {
  exactKeys(value, ["serviceName", "containerName", "gatewayPort"], `slot_${name}`);
  return {
    serviceName: token(value.serviceName, /^[a-z0-9][a-z0-9.-]{0,62}$/, `${name}.serviceName`),
    containerName: token(value.containerName, /^[a-z0-9][a-z0-9_.-]{0,62}$/, `${name}.containerName`),
    gatewayPort: port(value.gatewayPort, `${name}.gatewayPort`, 1024),
  };
}

async function readStrictJson(filePath) {
  let handle;
  try {
    if (!path.isAbsolute(filePath)) fail("managed_baseline_config_path_must_be_absolute");
    handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
    const info = await handle.stat();
    if (!info.isFile() || (info.mode & 0o077) !== 0 || info.size < 2 || info.size > 128 * 1024) {
      fail("managed_baseline_config_file_unsafe");
    }
    return JSON.parse(await handle.readFile("utf8"));
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail("managed_baseline_config_read_failed");
  } finally {
    await handle?.close().catch(() => {});
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
  if (typeof value !== "string" || !path.isAbsolute(value) || value === "/" || /[\u0000-\u001f\u007f]/.test(value)
      || path.normalize(value) !== value) throw new Error(`${label}_invalid`);
  return value;
}

function token(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) throw new Error(`${label}_invalid`);
  return value;
}

function exactValue(value, expected, label) {
  if (value !== expected) throw new Error(`${label}_invalid`);
  return value;
}

function boundedInteger(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(`${label}_invalid`);
  return value;
}

function port(value, label, minimum) {
  return boundedInteger(value, minimum, 65535, label);
}

function fail(cause) {
  throw new OpsError("config", cause, "managed_baseline_config_validate");
}
