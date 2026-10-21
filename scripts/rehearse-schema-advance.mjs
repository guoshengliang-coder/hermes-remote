#!/usr/bin/env node
import { loadPostgresqlRestoreConfig } from "../ops/lib/postgresql-recovery-config.mjs";
import { loadBundleManifest } from "../ops/lib/config.mjs";
import { rehearseSchemaAdvance } from "../ops/lib/schema-advance-rehearsal.mjs";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import { loadPostgresqlOffHostConfig, runDisposablePostgresqlRestore } from "../ops/lib/postgresql-automation.mjs";
import { assertNoSymlinkAncestors } from "../ops/lib/system.mjs";
import { mkdtemp, rm } from "node:fs/promises";
import path from "node:path";

let temporary;
try {
  const values = process.argv.slice(2);
  const disposable = values.length === 10 && values[0] === "--offhost-config" && values[2] === "--generation"
    && values[4] === "--target-manifest" && values[6] === "--evidence" && values[8] === "--confirm";
  const manual = values.length === 8 && values[0] === "--restore-config" && values[2] === "--target-manifest"
    && values[4] === "--evidence" && values[6] === "--confirm";
  if (!disposable && !manual) {
    throw new OpsError("productionSchemaRelease", "schema_rehearsal_arguments_invalid", "production_schema_rehearsal");
  }
  let result;
  if (disposable) {
    if (!/^[0-9]{8}T[0-9]{9}Z-[0-9a-f]{12}$/.test(values[3])) {
      throw new OpsError("productionSchemaRelease", "schema_rehearsal_generation_invalid", "production_schema_rehearsal");
    }
    const config = await loadPostgresqlOffHostConfig(values[1]);
    const target = await loadBundleManifest(values[5]);
    const generationRoot = path.join(config.localRoot, values[3]);
    await assertNoSymlinkAncestors(generationRoot);
    temporary = await mkdtemp(path.join(generationRoot, ".schema-rehearsal-"));
    result = await runDisposablePostgresqlRestore(config, {
      generationRoot: temporary, archiveFile: path.join(generationRoot, "postgresql.cms"),
      manifestFile: path.join(generationRoot, "postgresql.manifest.json"),
      evidenceFile: path.join(temporary, "source-restore.json"), statusFile: path.join(temporary, "source-status.json"),
    }, { verifyRestore: (restore, options) => rehearseSchemaAdvance(restore, target, values[7], { ...options, confirmation: values[9] }) });
  } else {
    const restore = await loadPostgresqlRestoreConfig(values[1]);
    const target = await loadBundleManifest(values[3]);
    result = await rehearseSchemaAdvance(restore, target, values[5], { confirmation: values[7] });
  }
  console.log(JSON.stringify(result));
} catch (error) {
  console.error(JSON.stringify(errorPayload(error, "productionSchemaRelease", "production_schema_rehearsal")));
  process.exitCode = 1;
} finally { if (temporary) await rm(temporary, { recursive: true, force: true }); }
