import { OpsError } from "./errors.mjs";

const OPERATIONS = new Set(["deploy", "rollback"]);

export function assessReleaseTransition(current, target, {
  operation,
  databaseEnabled = false,
} = {}) {
  try {
    if (!OPERATIONS.has(operation)) incompatible("operation_invalid");
    assertManifest(current, "current");
    assertManifest(target, "target");
    if (operation === "deploy" && target.schemaVersion < 2) incompatible("target_release_contract_required");
    if (operation === "rollback" && current.schemaVersion < 2) incompatible("current_release_contract_required");

    const direction = compareVersions(target.serverVersion, current.serverVersion);
    if (operation === "deploy" && direction <= 0) incompatible("deploy_target_must_be_newer");
    if (operation === "rollback" && direction >= 0) incompatible("rollback_target_must_be_older");

    const governingContract = operation === "rollback" ? current.releaseContract : target.releaseContract;
    const comparedVersion = operation === "rollback" ? target.serverVersion : current.serverVersion;
    if (compareVersions(comparedVersion, governingContract.minimumSourceVersion) < 0) {
      incompatible("source_version_below_minimum");
    }
    if (operation === "rollback" && !current.releaseContract.rollbackSupported) {
      incompatible("rollback_not_supported");
    }
    if (operation === "rollback"
        && target.schemaVersion === 1
        && target.serverVersion !== current.releaseContract.minimumSourceVersion) {
      incompatible("legacy_rollback_target_not_baseline");
    }

    if (current.schemaVersion >= 2 && target.schemaVersion >= 2) {
      for (const protocol of ["legacy", "accountConnector"]) {
        if (current.releaseContract.protocolVersions[protocol] !== target.releaseContract.protocolVersions[protocol]) {
          incompatible(`protocol_${protocol}_incompatible`);
        }
      }
    }

    if (databaseEnabled) {
      if (current.schemaVersion < 2 || target.schemaVersion < 2) {
        incompatible("database_contract_required");
      }
      if (target.releaseContract.manifestVersion < 2) {
        incompatible("database_migration_contract_required");
      }
      if (target.releaseContract.databaseSchemaVersion !== current.releaseContract.databaseSchemaVersion) {
        incompatible("database_schema_change_requires_compatibility_contract");
      }
    }

    return {
      compatible: true,
      operation,
      source: releaseIdentity(current),
      target: releaseIdentity(target),
      databaseEnabled,
      maintenanceRequired: governingContract.maintenanceRequired,
      rollbackSupported: governingContract.rollbackSupported,
    };
  } catch (error) {
    if (error instanceof OpsError) throw error;
    incompatible(error instanceof Error ? error.message : error);
  }
}

export function compareVersions(left, right) {
  const leftParts = parseVersion(left, "left_version");
  const rightParts = parseVersion(right, "right_version");
  for (let index = 0; index < leftParts.length; index += 1) {
    if (leftParts[index] !== rightParts[index]) return leftParts[index] < rightParts[index] ? -1 : 1;
  }
  return 0;
}

function assertManifest(manifest, label) {
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) incompatible(`${label}_manifest_invalid`);
  parseVersion(manifest.serverVersion, `${label}_version`);
  if (![1, 2, 3].includes(manifest.schemaVersion)) incompatible(`${label}_manifest_schema_invalid`);
  if (manifest.schemaVersion >= 2 && !manifest.releaseContract) incompatible(`${label}_release_contract_missing`);
}

function releaseIdentity(manifest) {
  return {
    serverVersion: manifest.serverVersion,
    sourceCommit: manifest.sourceCommit,
    imageId: manifest.imageId,
    manifestSchemaVersion: manifest.schemaVersion,
    databaseSchemaVersion: manifest.releaseContract?.databaseSchemaVersion ?? null,
  };
}

function parseVersion(value, label) {
  if (typeof value !== "string" || !/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value)) {
    incompatible(`${label}_invalid`);
  }
  return value.split(".").map(Number);
}

function incompatible(cause) {
  throw new OpsError("compatibility", cause, "release_compatibility");
}
