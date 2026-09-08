#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import {
  exerciseEmailStaging,
  loadEmailStagingConfig,
  preflightEmailStaging,
} from "../ops/lib/email-staging.mjs";

const command = process.argv[2];

try {
  const args = parseArguments(command, process.argv.slice(3));
  const config = await loadEmailStagingConfig(args.config);
  const result = command === "preflight"
    ? await preflightEmailStaging(config)
    : await exerciseEmailStaging(config, {
      testCase: args.case,
      confirmation: args.confirm,
    });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  console.error(JSON.stringify(errorPayload(
    error,
    command === "exercise" ? "emailAcceptance" : "config",
    `email_staging_${command || "arguments"}`,
  )));
  process.exitCode = 1;
}

function parseArguments(selectedCommand, values) {
  if (selectedCommand !== "preflight" && selectedCommand !== "exercise") {
    throw new OpsError("config", "email_staging_command_invalid", "email_staging_arguments");
  }
  const parsed = {};
  for (let index = 0; index < values.length; index += 2) {
    const flag = values[index];
    const value = values[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--")) {
      throw new OpsError("config", "email_staging_arguments_invalid", "email_staging_arguments");
    }
    const key = flag.slice(2);
    if (!new Set(["config", "case", "confirm"]).has(key) || key in parsed) {
      throw new OpsError("config", "email_staging_argument_unknown", "email_staging_arguments");
    }
    parsed[key] = value;
  }
  if (!parsed.config) {
    throw new OpsError("config", "email_staging_config_required", "email_staging_arguments");
  }
  if (selectedCommand === "preflight") {
    if (parsed.case || parsed.confirm) {
      throw new OpsError("config", "email_staging_preflight_is_read_only", "email_staging_arguments");
    }
  } else if (!new Set(["delivered", "bounced"]).has(parsed.case) || !parsed.confirm) {
    throw new OpsError("config", "email_staging_exercise_arguments_required", "email_staging_arguments");
  }
  return parsed;
}
