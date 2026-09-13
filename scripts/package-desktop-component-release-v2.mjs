#!/usr/bin/env node
import path from "node:path";
import { packageDesktopComponentReleaseV2 } from "./lib/desktop-managed-release.mjs";
import { createReleaseError } from "./lib/release-errors.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const result = await packageDesktopComponentReleaseV2({
    configPath: path.resolve(args.config),
    outputDirectory: path.resolve(args.output),
  });
  process.stdout.write([
    "DESKTOP_COMPONENT_RELEASE_V2_OK",
    `RELEASE_VERSION=${result.releaseVersion}`,
    `KEY_ID=${result.keyId}`,
    `PUBLIC_KEY=${result.publicKey}`,
    `MANIFEST=${result.manifestPath}`,
    `MANIFEST_SHA256=${result.manifestSha256}`,
    ...result.components.flatMap((component) => {
      const prefix = component.kind.toUpperCase();
      return [
        `${prefix}_ARCHIVE=${component.path}`,
        `${prefix}_SIZE=${component.sizeBytes}`,
        `${prefix}_SHA256=${component.sha256}`,
        `${prefix}_CONTENT_SHA256=${component.contentSHA256}`,
      ];
    }),
    "",
  ].join("\n"));
} catch (error) {
  const cause = error?.technicalCause ?? "desktop_component_release_v2_package_failed";
  process.stderr.write(`${JSON.stringify(createReleaseError("desktopPackage", cause))}\n`);
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
