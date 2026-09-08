#!/usr/bin/env node
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { loadPostgresqlProvisionConfig } from "../ops/lib/postgresql-provision-config.mjs";
import { provisionPostgresql } from "../ops/lib/postgresql-provision.mjs";

try {
  const values = process.argv.slice(2);
  if (values.length !== 4 || values[0] !== "--config" || values[2] !== "--confirm" || !/^production:[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(values[3])) {
    throw new OpsError("databaseProvision", "postgresql_provision_arguments_invalid", "postgresql_provision_arguments");
  }
  const config = await loadPostgresqlProvisionConfig(values[1]);
  process.stdout.write(`${JSON.stringify(await provisionPostgresql(config, { confirmation: values[3] }), null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(error, "databaseProvision", "postgresql_provision_entrypoint"))}\n`);
  process.exitCode = 1;
}
