#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { executeProductionMultiDeviceRollout } from "../ops/lib/production-multi-device-rollout.mjs";
import { loadProductionMultiDeviceRolloutConfig } from "../ops/lib/production-multi-device-rollout-config.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const config = await loadProductionMultiDeviceRolloutConfig(args.config);
  const result = await executeProductionMultiDeviceRollout(config, { confirmation: args.confirm });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(
    error,
    "productionMultiDeviceRollout",
    "production_multi_device_rollout_entrypoint",
  ))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("productionMultiDeviceRollout", "multi_device_rollout_arguments_invalid", "production_multi_device_rollout_arguments");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm"]).has(key) || key in parsed) {
      throw new OpsError("productionMultiDeviceRollout", "multi_device_rollout_argument_unknown_or_duplicate", "production_multi_device_rollout_arguments");
    }
    parsed[key] = value;
  }
  if (!parsed.config || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "")) {
    throw new OpsError("productionMultiDeviceRollout", "multi_device_rollout_exact_confirmation_required", "production_multi_device_rollout_arguments");
  }
  return parsed;
}
