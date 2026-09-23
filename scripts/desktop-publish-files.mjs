#!/usr/bin/env node
import { desktopPublishFilesFromFile } from "./lib/desktop-publish-files.mjs";
import { createReleaseError } from "./lib/release-errors.mjs";

if (process.argv.length !== 3) {
  process.stderr.write(`${JSON.stringify(createReleaseError("desktopPackage", "publisher_arguments_invalid"))}\n`);
  process.exit(64);
}

try {
  const names = await desktopPublishFilesFromFile(process.argv[2]);
  process.stdout.write(`${names.join("\n")}\n`);
} catch {
  process.stderr.write(`${JSON.stringify(createReleaseError("desktopPackage", "manifest_payload_invalid"))}\n`);
  process.exitCode = 65;
}
