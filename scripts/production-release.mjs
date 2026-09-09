#!/usr/bin/env node
import { fileURLToPath } from "node:url";
import { loadBundleManifest } from "../ops/lib/config.mjs";
import { createStagingSmokeCallbacks } from "../ops/lib/deploy-smoke.mjs";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { loadManagedBaselineConfig } from "../ops/lib/managed-baseline-config.mjs";
import { executeProductionRelease, recoverFailedProductionRelease } from "../ops/lib/production-release.mjs";
import { withProductionSmokeRuntime } from "../ops/lib/production-smoke-runtime.mjs";

// R5-F1: routine production Gateway release (deploy or rollback) inside the managed baseline.
// Same private configuration file as R5-D; `targetArtifactManifest` names the paired bundle to
// deploy, roll back to, or use while recovering an audited pre-switch candidate failure.
try {
  const args = parseArguments(process.argv.slice(2));
  const config = await loadManagedBaselineConfig(args.config);
  const manifest = await loadBundleManifest(config.targetArtifactManifest);
  const connectorEntry = fileURLToPath(new URL("../connector/dist/index.js", import.meta.url));
  const result = args.operation === "recover"
    ? await recoverFailedProductionRelease(config, manifest, { confirmation: args.confirm })
    : await withProductionSmokeRuntime(async (runtime) => {
      const smoke = await createStagingSmokeCallbacks(config, {
        env: runtime.environment,
        spawnImpl: runtime.spawn,
      });
      return executeProductionRelease(config, manifest, {
        operation: args.operation,
        confirmation: args.confirm,
        ...smoke,
      });
    }, {
      connectorEntry,
    });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(error, "productionRelease", "production_release_entrypoint"))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("productionRelease", "production_release_arguments_invalid", "production_release_arguments");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm", "operation"]).has(key) || key in parsed) {
      throw new OpsError("productionRelease", "production_release_argument_unknown_or_duplicate", "production_release_arguments");
    }
    parsed[key] = value;
  }
  if (!parsed.config
      || !new Set(["deploy", "rollback", "recover"]).has(parsed.operation)
      || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "")) {
    throw new OpsError("productionRelease", "production_release_exact_confirmation_required", "production_release_arguments");
  }
  return parsed;
}
