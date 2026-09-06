#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import {
  activateScheduledPostgresqlBackup,
  captureScheduledPostgresqlBackup,
  exportScheduledBackup,
  loadPostgresqlCaptureScheduleConfig,
  loadPostgresqlOffHostConfig,
  readLatestBackupDescriptor,
  runOffHostRecoveryCycle,
} from "../ops/lib/postgresql-automation.mjs";

let binaryOutput = false;
try {
  const parsed = parseArguments(process.argv.slice(2));
  if (parsed.command === "offhost") {
    const config = await loadPostgresqlOffHostConfig(parsed.config);
    if (parsed.confirm !== `isolated:${config.expectedSourceHostname}`) fail("postgresql_automation_offhost_confirmation_required");
    const result = await runOffHostRecoveryCycle(config);
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } else {
    const config = await loadPostgresqlCaptureScheduleConfig(parsed.config);
    const options = { confirmation: parsed.confirm };
    if (parsed.command === "capture") {
      process.stdout.write(`${JSON.stringify(await captureScheduledPostgresqlBackup(config, options))}\n`);
    } else if (parsed.command === "latest") {
      process.stdout.write(`${JSON.stringify(await readLatestBackupDescriptor(config, options))}\n`);
    } else if (parsed.command === "export") {
      binaryOutput = true;
      await exportScheduledBackup(config, parsed.generation, parsed.part, process.stdout, options);
    } else if (parsed.command === "activate") {
      const result = await activateScheduledPostgresqlBackup(
        config, parsed.generation, parsed.evidence, parsed.status, options,
      );
      process.stdout.write(`${JSON.stringify(result)}\n`);
    }
  }
} catch (error) {
  if (binaryOutput) process.stdout.destroy();
  process.stderr.write(`${JSON.stringify(errorPayload(error, "databaseRecovery", "postgresql_automation_cli"))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const command = values.shift();
  if (!new Set(["capture", "latest", "export", "activate", "offhost"]).has(command)) {
    fail("postgresql_automation_command_invalid");
  }
  const parsed = { command };
  while (values.length > 0) {
    const flag = values.shift();
    const value = values.shift();
    if (!value || !(flag in flagsFor(command)) || parsed[flagsFor(command)[flag]]) {
      fail("postgresql_automation_arguments_invalid");
    }
    parsed[flagsFor(command)[flag]] = value;
  }
  const expected = command === "export"
    ? ["config", "confirm", "generation", "part"]
    : command === "activate"
      ? ["config", "confirm", "generation", "evidence", "status"]
      : ["config", "confirm"];
  if (expected.some((key) => !parsed[key])) fail("postgresql_automation_arguments_invalid");
  return parsed;
}

function flagsFor(command) {
  return {
    "--config": "config",
    "--confirm": "confirm",
    ...(command === "export" || command === "activate" ? { "--generation": "generation" } : {}),
    ...(command === "export" ? { "--part": "part" } : {}),
    ...(command === "activate" ? { "--evidence": "evidence", "--status": "status" } : {}),
  };
}

function fail(cause) {
  throw new OpsError("databaseRecovery", cause, "postgresql_automation_arguments");
}
