#!/usr/bin/env node
import { verifyDesktopReleaseKeyContinuity } from "./lib/desktop-release-key-continuity.mjs";

const [origin, keyId, publicKey, nextVersion] = process.argv.slice(2);
if (process.argv.length !== 5 && process.argv.length !== 6) {
  process.stderr.write("Usage: verify-desktop-release-key-continuity.mjs ORIGIN KEY_ID PUBLIC_KEY [NEXT_VERSION]\n");
  process.exit(64);
}
try {
  const releases = await verifyDesktopReleaseKeyContinuity({ origin, keyId, publicKey, nextVersion });
  for (const release of releases) {
    process.stdout.write(`${release.kind.toUpperCase()}_VERSION=${release.releaseVersion}\n`);
    process.stdout.write(`${release.kind.toUpperCase()}_MANIFEST_SHA256=${release.manifestSHA256}\n`);
  }
  process.stdout.write("DESKTOP_SIGNING_CONTINUITY_OK\n");
} catch (error) {
  process.stderr.write(`Desktop signing continuity check failed: ${error.message}\n`);
  process.exitCode = 1;
}
