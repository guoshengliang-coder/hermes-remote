#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { executeProductionAccountRollout } from "../ops/lib/production-account-rollout.mjs";
import { loadProductionAccountRolloutConfig } from "../ops/lib/production-account-rollout-config.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const config = await loadProductionAccountRolloutConfig(args.config);
  const result = await executeProductionAccountRollout(config, { confirmation: args.confirm });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(error, "productionAccountRollout", "production_account_rollout_entrypoint"))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("productionAccountRollout", "account_rollout_arguments_invalid", "production_account_rollout_arguments");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm"]).has(key) || key in parsed) {
      throw new OpsError("productionAccountRollout", "account_rollout_argument_unknown_or_duplicate", "production_account_rollout_arguments");
    }
    parsed[key] = value;
  }
  if (!parsed.config || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "")) {
    throw new OpsError("productionAccountRollout", "account_rollout_exact_confirmation_required", "production_account_rollout_arguments");
  }
  return parsed;
}
