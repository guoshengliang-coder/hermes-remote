#!/usr/bin/env node
import path from "node:path";
import { packageDesktopManagedRelease } from "./lib/desktop-managed-release.mjs";
import { createReleaseError } from "./lib/release-errors.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const result = await packageDesktopManagedRelease({
    configPath: path.resolve(args.config),
    outputDirectory: path.resolve(args.output),
  });
  process.stdout.write([
    "DESKTOP_MANAGED_RELEASE_OK",
    `RELEASE_VERSION=${result.releaseVersion}`,
    `KEY_ID=${result.keyId}`,
    `PUBLIC_KEY=${result.publicKey}`,
    `MANIFEST=${result.manifestPath}`,
    `MANIFEST_SHA256=${result.manifestSha256}`,
    ...result.artifacts.flatMap((artifact) => {
      const prefix = artifact.component === "hermes_server" ? "HERMES_SERVER" : "CONNECTOR";
      return [
        `${prefix}_ARCHIVE=${artifact.path}`,
        `${prefix}_SIZE=${artifact.sizeBytes}`,
        `${prefix}_SHA256=${artifact.sha256}`,
      ];
    }),
    "",
  ].join("\n"));
} catch (error) {
  const technicalCause = error?.technicalCause ?? "desktop_managed_release_package_failed";
  process.stderr.write(`${JSON.stringify(createReleaseError("desktopPackage", technicalCause))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!value || !["--config", "--output"].includes(key) || key.slice(2) in result) {
      throw Object.assign(new Error("invalid arguments"), { technicalCause: "publisher_arguments_invalid" });
    }
    result[key.slice(2)] = value;
  }
  if (values.length !== 4 || !result.config || !result.output) {
    throw Object.assign(new Error("invalid arguments"), { technicalCause: "publisher_arguments_invalid" });
  }
  return result;
}
