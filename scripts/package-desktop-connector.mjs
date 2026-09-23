#!/usr/bin/env node
import path from "node:path";
import { packageDesktopConnectorArchive } from "./lib/desktop-component-archives.mjs";
import { createReleaseError } from "./lib/release-errors.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const result = await packageDesktopConnectorArchive({
    configPath: path.resolve(args.config),
    outputDirectory: path.resolve(args.output),
  });
  process.stdout.write([
    "DESKTOP_CONNECTOR_ARCHIVE_OK",
    `ARCHITECTURE=${result.architecture}`,
    `CONNECTOR_VERSION=${result.artifact.version}`,
    `CONNECTOR_ARCHIVE=${result.artifact.path}`,
    `CONNECTOR_ENTRYPOINT=${result.artifact.entrypoint}`,
    "",
  ].join("\n"));
} catch (error) {
  process.stderr.write(`${JSON.stringify(createReleaseError(
    "desktopPackage", error?.technicalCause ?? "desktop_component_archive_failed",
  ))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!value || !["--config", "--output"].includes(key) || key.slice(2) in result) {
      throw Object.assign(new Error("invalid arguments"), { technicalCause: "component_arguments_invalid" });
    }
    result[key.slice(2)] = value;
  }
  if (values.length !== 4 || !result.config || !result.output) {
    throw Object.assign(new Error("invalid arguments"), { technicalCause: "component_arguments_invalid" });
  }
  return result;
}
