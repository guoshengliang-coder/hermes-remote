#!/usr/bin/env node
import { inspectGatewayArchiveIdentity } from "./lib/gateway-archive-identity.mjs";

try {
  const [archivePath, imageReference, configImageId] = process.argv.slice(2);
  if (process.argv.length !== 5) throw new Error("usage_requires_archive_reference_and_config_id");
  const identity = inspectGatewayArchiveIdentity(archivePath, imageReference, configImageId);
  process.stdout.write(`${identity.containerdImageId}\n`);
} catch (error) {
  process.stderr.write(`gateway_archive_identity_invalid:${error instanceof Error ? error.message : "unknown"}\n`);
  process.exitCode = 1;
}
