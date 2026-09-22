#!/usr/bin/env node
import { fileURLToPath } from "node:url";
import { loadBundleManifest } from "../ops/lib/config.mjs";
import { createStagingSmokeCallbacks } from "../ops/lib/deploy-smoke.mjs";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { loadManagedBaselineConfig } from "../ops/lib/managed-baseline-config.mjs";
import { withProductionSmokeRuntime } from "../ops/lib/production-smoke-runtime.mjs";
import { executeProductionSchemaRelease } from "../ops/lib/production-schema-release.mjs";
import { loadProductionSchemaReleaseConfig } from "../ops/lib/production-schema-release-config.mjs";

// R5-F8: a production release that advances the database schema by one step, behind a fresh
// off-host-verified backup. The release target is the R5-F1 configuration's targetArtifactManifest.
try {
  const args = parseArguments(process.argv.slice(2));
  const schemaConfig = await loadProductionSchemaReleaseConfig(args.config);
  const config = await loadManagedBaselineConfig(schemaConfig.productionReleaseConfig);
  const manifest = await loadBundleManifest(config.targetArtifactManifest);
  const connectorEntry = fileURLToPath(new URL("../connector/dist/index.js", import.meta.url));
  const result = await withProductionSmokeRuntime(async (runtime) => {
    const smoke = await createStagingSmokeCallbacks(config, {
      env: runtime.environment,
      spawnImpl: runtime.spawn,
    });
    return executeProductionSchemaRelease(config, manifest, schemaConfig, {
      confirmation: args.confirm,
      ...smoke,
    });
  }, {
    connectorEntry,
  });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(
    error,
    "productionSchemaRelease",
    "production_schema_release_entrypoint",
  ))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("productionSchemaRelease", "schema_release_arguments_invalid", "production_schema_release_arguments");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm"]).has(key) || key in parsed) {
      throw new OpsError("productionSchemaRelease", "schema_release_argument_unknown_or_duplicate", "production_schema_release_arguments");
    }
    parsed[key] = value;
  }
  if (!parsed.config || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "")) {
    throw new OpsError("productionSchemaRelease", "schema_release_exact_confirmation_required", "production_schema_release_arguments");
  }
  return parsed;
}
