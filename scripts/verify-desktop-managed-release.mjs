#!/usr/bin/env node
import path from "node:path";
import { verifyDesktopManagedRelease } from "./lib/desktop-managed-release.mjs";
import { createReleaseError } from "./lib/release-errors.mjs";

try {
  const args = parseArguments(process.argv.slice(2));
  const manifest = await verifyDesktopManagedRelease({
    manifestPath: path.resolve(args.manifest),
    artifactDirectory: path.resolve(args.artifacts),
    expectedKeyId: args["key-id"],
    publicKey: args["public-key"],
    expectedOrigin: args.origin,
    expectedChannel: args.channel,
    expectedArchitecture: args.architecture,
  });
  process.stdout.write(`${JSON.stringify({
    ok: true,
    command: "verify-desktop-managed-release",
    releaseVersion: manifest.releaseVersion,
    keyId: args["key-id"],
    architecture: manifest.architecture,
    artifacts: manifest.artifacts.map(({ component, fileName, sizeBytes, sha256 }) => ({
      component, fileName, sizeBytes, sha256,
    })),
  }, null, 2)}\n`);
} catch (error) {
  const technicalCause = error?.technicalCause ?? "desktop_managed_release_verify_failed";
  process.stderr.write(`${JSON.stringify(createReleaseError("desktopPackage", technicalCause))}\n`);
  process.exitCode = 1;
}

function parseArguments(values) {
  const allowed = new Set([
    "--manifest", "--artifacts", "--key-id", "--public-key", "--origin", "--channel", "--architecture",
  ]);
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const key = values[index];
    const value = values[index + 1];
    if (!value || !allowed.has(key) || key.slice(2) in result) {
      throw Object.assign(new Error("invalid arguments"), { technicalCause: "verification_arguments_invalid" });
    }
    result[key.slice(2)] = value;
  }
  if (values.length !== allowed.size * 2 || Object.keys(result).length !== allowed.size) {
    throw Object.assign(new Error("invalid arguments"), { technicalCause: "verification_arguments_invalid" });
  }
  return result;
}

