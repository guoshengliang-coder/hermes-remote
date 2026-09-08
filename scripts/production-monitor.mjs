#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { loadProductionMonitorConfig } from "../ops/lib/production-monitor-config.mjs";
import { monitorProduction } from "../ops/lib/production-monitor.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const config = await loadProductionMonitorConfig(args.config);
  const result = await monitorProduction(config, { confirmation: args.confirm });
  print(result);
  if (!result.ok) process.exitCode = 1;
} catch (error) {
  console.error(JSON.stringify(errorPayload(error, "monitoring", "production_monitor_command")));
  process.exitCode = 1;
}

function parseArguments(values) {
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("config", "production_monitor_arguments_invalid", "arguments_parse");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "confirm"]).has(key) || key in parsed) {
      throw new OpsError("config", "production_monitor_argument_unknown_or_duplicate", "arguments_parse");
    }
    parsed[key] = value;
  }
  if (!parsed.config || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(parsed.confirm ?? "")) {
    throw new OpsError("config", "production_monitor_arguments_required", "arguments_parse");
  }
  return parsed;
}

function print(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}
