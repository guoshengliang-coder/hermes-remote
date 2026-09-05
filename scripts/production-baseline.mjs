#!/usr/bin/env node
import { fileURLToPath } from "node:url";
import { loadBundleManifest } from "../ops/lib/config.mjs";
import { createProductionBaselineSmokeCallbacks } from "../ops/lib/deploy-smoke.mjs";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { loadManagedBaselineConfig } from "../ops/lib/managed-baseline-config.mjs";
import { executeManagedBaseline } from "../ops/lib/managed-baseline.mjs";
import { withProductionSmokeRuntime } from "../ops/lib/production-smoke-runtime.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const config = await loadManagedBaselineConfig(args.config);
  const manifest = await loadBundleManifest(config.targetArtifactManifest);
  const connectorEntry = fileURLToPath(new URL("../connector/dist/index.js", import.meta.url));
  const result = await withProductionSmokeRuntime(async (runtime) => {
    const smoke = await createProductionBaselineSmokeCallbacks(config, {
      env: runtime.environment,
      spawnImpl: runtime.spawn,
    });
    return executeManagedBaseline(config, manifest, {
      confirmation: args.confirm,
      ...smoke,
    });
  }, {
    connectorEntry,
  });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(error, "managedBaseline", "managed_baseline_entrypoint"))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("config", "managed_baseline_arguments_invalid", "managed_baseline_arguments");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm"]).has(key) || key in parsed) {
      throw new OpsError("config", "managed_baseline_argument_unknown_or_duplicate", "managed_baseline_arguments");
    }
    parsed[key] = value;
  }
  if (!parsed.config || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "")) {
    throw new OpsError("config", "managed_baseline_exact_confirmation_required", "managed_baseline_arguments");
  }
  return parsed;
}
