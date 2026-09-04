#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import {
  loadPostgresqlBackupConfig, loadPostgresqlRestoreConfig, loadPostgresqlStatusActivationConfig,
} from "../ops/lib/postgresql-recovery-config.mjs";
import {
  capturePostgresqlBackup, publishPostgresqlBackupStatus, verifyPostgresqlRestore,
} from "../ops/lib/postgresql-recovery.mjs";

const command = process.argv[2];
try {
  const args = parseArguments(command, process.argv.slice(3));
  const config = command === "backup"
    ? await loadPostgresqlBackupConfig(args.config)
    : command === "restore"
      ? await loadPostgresqlRestoreConfig(args.config)
      : await loadPostgresqlStatusActivationConfig(args.config);
  const result = command === "backup"
    ? await capturePostgresqlBackup(config, { confirmation: args.confirm })
    : command === "restore"
      ? await verifyPostgresqlRestore(config, { confirmation: args.confirm })
      : await publishPostgresqlBackupStatus(config, { confirmation: args.confirm });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  console.error(JSON.stringify(errorPayload(error, "databaseRecovery", `postgresql_recovery_${command || "arguments"}`)));
  process.exitCode = 1;
}

function parseArguments(selectedCommand, values) {
  if (!new Set(["backup", "restore", "activate-status"]).has(selectedCommand)) {
    throw new OpsError("databaseRecovery", "command_not_supported", "postgresql_recovery_arguments");
  }
  if (values.length !== 4 || values[0] !== "--config" || values[2] !== "--confirm") {
    throw new OpsError("databaseRecovery", "command_arguments_invalid", "postgresql_recovery_arguments");
  }
  const config = values[1];
  const confirm = values[3];
  const confirmation = selectedCommand === "restore"
    ? /^isolated:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/
    : /^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/;
  if (!config || !confirm || !confirmation.test(confirm)) {
    throw new OpsError("databaseRecovery", "exact_confirmation_required", "postgresql_recovery_arguments");
  }
  return { config, confirm };
}
