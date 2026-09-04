#!/usr/bin/env node
import path from "node:path";
import { errorPayload } from "../ops/lib/errors.mjs";
import { loadProductionBaselineBundleManifest } from "../ops/lib/production-baseline-bundle.mjs";

try {
  if (process.argv.length !== 3) throw new Error("production_baseline_bundle_manifest_required");
  const manifest = await loadProductionBaselineBundleManifest(path.resolve(process.argv[2]));
  process.stdout.write(`${JSON.stringify({
    ok: true,
    command: "verify-production-baseline-bundle",
    sourceCommit: manifest.sourceCommit,
    archiveFile: manifest.archiveFile,
    archiveSha256: manifest.archiveSha256,
  }, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`${JSON.stringify(errorPayload(error, "managedBaseline", "production_baseline_bundle_verify"))}\n`);
  process.exitCode = 1;
}
