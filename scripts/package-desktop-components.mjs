#!/usr/bin/env node
import path from "node:path";
import { packageDesktopComponentArchives } from "./lib/desktop-component-archives.mjs";
import { createReleaseError } from "./lib/release-errors.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const result = await packageDesktopComponentArchives({
    configPath: path.resolve(args.config),
    outputDirectory: path.resolve(args.output),
  });
  process.stdout.write([
    "DESKTOP_COMPONENT_ARCHIVES_OK",
    `ARCHITECTURE=${result.architecture}`,
    ...result.artifacts.flatMap((artifact) => {
      const prefix = artifact.component === "hermes_server" ? "HERMES_SERVER" : "CONNECTOR";
      return [
        `${prefix}_VERSION=${artifact.version}`,
        `${prefix}_ARCHIVE=${artifact.path}`,
        `${prefix}_ENTRYPOINT=${artifact.entrypoint}`,
      ];
    }),
    "",
  ].join("\n"));
} catch (error) {
  process.stderr.write(`${JSON.stringify(createReleaseError(
    "desktopPackage",
    error?.technicalCause ?? "desktop_component_archive_failed",
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

