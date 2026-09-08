#!/usr/bin/env node
import { auditEmailDomain, loadEmailDomainConfig } from "../ops/lib/email-domain.mjs";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";

try {
  const configPath = parseArguments(process.argv.slice(2));
  const config = await loadEmailDomainConfig(configPath);
  const result = await auditEmailDomain(config);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (!result.ok) process.exitCode = 1;
} catch (error) {
  console.error(JSON.stringify(errorPayload(error, "config", "email_domain_cli")));
  process.exitCode = 1;
}

function parseArguments(values) {
  if (values.length !== 2 || values[0] !== "--config" || !values[1]
      || values[1].startsWith("--")) {
    throw new OpsError("config", "email_domain_config_argument_required", "email_domain_arguments");
  }
  return values[1];
}
