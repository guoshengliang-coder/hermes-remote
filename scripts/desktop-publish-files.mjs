#!/usr/bin/env node
import { desktopPublishFilesFromFile } from "./lib/desktop-publish-files.mjs";

if (process.argv.length !== 3) {
  process.stderr.write("usage: desktop-publish-files.mjs <signed-manifest>\n");
  process.exit(64);
}

try {
  const names = await desktopPublishFilesFromFile(process.argv[2]);
  process.stdout.write(`${names.join("\n")}\n`);
} catch {
  process.stderr.write("invalid signed desktop manifest\n");
  process.exitCode = 65;
}
