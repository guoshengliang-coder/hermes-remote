import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { spawnSync } from "node:child_process";
import { migrationIdentities, validateSchemaAdvancePlan, verifySchemaAdvancePlan } from "../../ops/lib/schema-advance-plan.mjs";
import { rehearseSchemaAdvance } from "../../ops/lib/schema-advance-rehearsal.mjs";
import { executeProductionSchemaRelease } from "../../ops/lib/production-schema-release.mjs";
import { loadProductionSchemaReleaseConfig } from "../../ops/lib/production-schema-release-config.mjs";
import { assessReleaseTransition } from "../../ops/lib/release-transition.mjs";

const SOURCE = "a".repeat(40), TARGET = "b".repeat(40), HASH = "c".repeat(64), IMAGE = `sha256:${"d".repeat(64)}`;
const NOW = new Date("2026-10-21T12:00:00.000Z");
const source = { schemaVersion: 3, serverVersion: "0.4.33", sourceCommit: SOURCE,
  releaseContract: { databaseSchemaVersion: 18, manifestVersion: 2, minimumSourceVersion: "0.2.0", protocolVersions: { legacy: 1, accountConnector: 2 } } };
const target = { ...source, serverVersion: "0.4.34", sourceCommit: TARGET, imageReference: "fixture:target", imageId: IMAGE,
  containerdImageId: IMAGE, archiveSha256: "e".repeat(64),
  releaseContract: { ...source.releaseContract, databaseSchemaVersion: 20 } };

async function fixture(t) {
  const root = await mkdtemp(path.join(await realpath(tmpdir()), "schema-advance-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const evidenceFile = path.join(root, "rehearsal.json");
  const url = path.join(root, "isolated-url");
  await writeFile(url, "fixture-only", { mode: 0o600 });
  const calls = [];
  const config = { databaseSchemaVersion: 18, postgresqlMajorVersion: 18, expectedSourceHostname: "prod-host", imageDatabaseUrlFile: url };
  const options = {
    now: () => NOW, hostname: "restore-host", confirmation: "isolated:restore-host",
    restore: async (_config, received) => {
      assert.equal(received.confirmation, "isolated:prod-host"); calls.push("restore");
      return { restoreHostname: "restore-host", subject: { databaseSchemaVersion: 18 }, artifactSha256: HASH,
        targetRelease: { sourceCommit: SOURCE } };
    },
    runner: { run: (_command, args) => {
      assert.equal(calls[0], "restore");
      if (args[0] === "image") return { status: 0, stdout: IMAGE };
      assert(args.includes(IMAGE)); assert(args.includes("--read-only")); assert(args.includes("--security-opt=no-new-privileges"));
      assert(args.includes("ACCOUNT_DATABASE_SCHEMA_VERSION=20"));
      calls.push("migrate");
      return { status: 0, stdout: `DATABASE_MIGRATION_OK ${JSON.stringify({ schemaVersion: 20, postgresqlMajor: 18, appliedMigrations: [19,20] })}` };
    } },
    accountSmoke: async (received, _runner, manifest) => {
      assert.equal(received.databaseSchemaVersion, 20); assert.equal(manifest.sourceCommit, TARGET); calls.push("smoke");
    },
  };
  const plan = { fromSchemaVersion: 18, toSchemaVersion: 20, sourceCommit: SOURCE, targetSourceCommit: TARGET,
    targetImageId: IMAGE, targetArchiveSha256: target.archiveSha256,
    migrations: await migrationIdentities(18,20), rehearsalEvidenceFile: evidenceFile };
  const facts = { source, target, hostname: "prod-host", backup: { artifactSha256: HASH, backupCompletedAt: "2026-10-21T11:30:00.000Z" },
    maximumAgeMinutes: 120, now: () => NOW };
  return { root, evidenceFile, calls, config, options, plan, facts };
}

test("two-step rehearsal writes proof only after restore, exact migrations and target account smoke", async (t) => {
  const f = await fixture(t);
  await rehearseSchemaAdvance(f.config, target, f.evidenceFile, f.options);
  assert.deepEqual(f.calls, ["restore", "migrate", "smoke"]);
  const verified = await verifySchemaAdvancePlan(f.plan, f.facts);
  assert.match(verified.rehearsalDigest, /^[0-9a-f]{64}$/);
  const flags = { operation: "deploy", databaseEnabled: true, allowDatabaseSchemaAdvance: true, schemaAdvancePlan: verified };
  assert.equal(assessReleaseTransition(source, target, flags).compatible, true);
  for (const override of [
    { allowDatabaseSchemaAdvance: false }, { schemaAdvancePlan: undefined },
    { schemaAdvancePlan: { ...verified, targetSourceCommit: SOURCE } },
    { schemaAdvancePlan: { ...verified, sourceCommit: TARGET } },
    { schemaAdvancePlan: { ...verified, rehearsalDigest: undefined } },
  ]) assert.throws(() => assessReleaseTransition(source, target, { ...flags, ...override }), error => error.kind === "compatibility");
  assert.throws(() => assessReleaseTransition(target, source, { ...flags, operation: "rollback" }), error => error.kind === "compatibility");
});

test("rehearsal refuses bad subjects, skipped migrations and smoke failure without producing evidence", async (t) => {
  const f = await fixture(t);
  await assert.rejects(() => rehearseSchemaAdvance(f.config, target, f.evidenceFile, { ...f.options, confirmation: "isolated:prod-host" }));
  assert.deepEqual(f.calls, []);
  await assert.rejects(() => rehearseSchemaAdvance(f.config, target, f.evidenceFile, {
    ...f.options, restore: async () => { throw new Error("source restore failed"); },
  }));
  assert.deepEqual(f.calls, []);
  const runner = { run: (_command, args) => args[0] === "image" ? { status: 0, stdout: IMAGE }
    : { status: 0, stdout: 'DATABASE_MIGRATION_OK {"schemaVersion":20,"postgresqlMajor":18,"appliedMigrations":[20]}' } };
  await assert.rejects(() => rehearseSchemaAdvance(f.config, target, f.evidenceFile, { ...f.options, runner }), /./);
  await assert.rejects(() => readFile(f.evidenceFile), { code: "ENOENT" });
  await assert.rejects(() => rehearseSchemaAdvance(f.config, target, f.evidenceFile, {
    ...f.options, accountSmoke: async () => { throw new Error("smoke failed"); },
  }));
  await assert.rejects(() => readFile(f.evidenceFile), { code: "ENOENT" });
  await writeFile(f.evidenceFile, "preserve", { mode: 0o600 });
  await assert.rejects(() => rehearseSchemaAdvance(f.config, target, f.evidenceFile, f.options));
  assert.equal(await readFile(f.evidenceFile, "utf8"), "preserve");
});

test("two-step release gate binds proof to both releases, every SQL hash and the same fresh off-host backup", async (t) => {
  const f = await fixture(t);
  await rehearseSchemaAdvance(f.config, target, f.evidenceFile, f.options);
  const original = JSON.parse(await readFile(f.evidenceFile, "utf8"));
  for (const change of [
    { restoreHostname: "prod-host" }, { targetSourceCommit: SOURCE }, { sourceCommit: TARGET },
    { backupArtifactSha256: "d".repeat(64) }, { targetImageId: `sha256:${HASH}` }, { targetArchiveSha256: HASH },
    { migrations: [f.plan.migrations[1]] },
    { verifiedChecks: ["source_restore"] }, { verifiedAt: "2026-10-21T09:00:00.000Z" },
    { verifiedAt: "2026-10-21T13:00:00.000Z" }, { extra: true },
  ]) {
    await writeFile(f.evidenceFile, JSON.stringify({ ...original, ...change }));
    await assert.rejects(() => verifySchemaAdvancePlan(f.plan, f.facts));
  }
  await writeFile(f.evidenceFile, JSON.stringify(original));
  await chmod(f.evidenceFile, 0o644);
  await assert.rejects(() => verifySchemaAdvancePlan(f.plan, f.facts));
  await chmod(f.evidenceFile, 0o600);
  const link = path.join(f.root, "link.json"); await symlink(f.evidenceFile, link);
  await assert.rejects(() => verifySchemaAdvancePlan({ ...f.plan, rehearsalEvidenceFile: link }, f.facts));
  await assert.rejects(() => verifySchemaAdvancePlan({ ...f.plan, migrations: f.plan.migrations.map(m=>({ ...m, sha256: HASH })) }, f.facts));
  for (const change of [{ toSchemaVersion: 21 }, { sourceCommit: "invalid" }, { migrations: [...f.plan.migrations].reverse() },
    { rehearsalEvidenceFile: "/a/../b" }, { extra: true }]) assert.throws(() => validateSchemaAdvancePlan({ ...f.plan, ...change }));
});

test("configured two-step plan is delegated only after the real evidence gate; old single-step config stays valid", async (t) => {
  const f = await fixture(t);
  await rehearseSchemaAdvance(f.config, target, f.evidenceFile, f.options);
  const config = { host: { hostname: "prod-host" } };
  const schema = { schemaVersion: 1, environment: "production", operator: "fixture", productionReleaseConfig: "/secure/release.json",
    database: { urlSource: "/secure/url", ssl: false, migrationLockId: 7 },
    backup: { activeStatusFile: "/secure/status", maximumAgeMinutes: 120 }, schemaAdvancePlan: f.plan };
  const file = path.join(f.root,"config.json"); await writeFile(file,JSON.stringify(schema),{mode:0o600});
  const loaded = await loadProductionSchemaReleaseConfig(file);
  let releaseCalls = 0;
  const options = { now: () => NOW, verifyAdmission: async () => ({ sourceManifest: source, runtimeEnvironment: { mode: "account" } }),
    verifyBackup: async () => f.facts.backup,
    executeRelease: async (_config,_target,received) => { releaseCalls++; assert.match(received.schemaAdvancePlan.rehearsalDigest,/^[0-9a-f]{64}$/); return {ok:true}; } };
  await executeProductionSchemaRelease(config,target,loaded,options);
  assert.equal(releaseCalls,1);
  await assert.rejects(() => executeProductionSchemaRelease(config,target,{...loaded,schemaAdvancePlan:undefined},options));
  await assert.rejects(() => executeProductionSchemaRelease(config,target,loaded,{...options,verifyBackup:async()=>({...f.facts.backup,artifactSha256:"e".repeat(64)})}));
  assert.equal(releaseCalls,1);
  delete schema.schemaAdvancePlan; await writeFile(file,JSON.stringify(schema));
  assert.equal((await loadProductionSchemaReleaseConfig(file)).schemaAdvancePlan,undefined);
});

test("rehearsal CLI failures keep the bilingual structured error contract before reading credentials", () => {
  for (const args of [[], ["--offhost-config", "/not-read", "--generation", "../escape", "--target-manifest", "/not-read",
    "--evidence", "/not-created", "--confirm", "isolated:restore-host"]]) {
    const result = spawnSync(process.execPath, ["scripts/rehearse-schema-advance.mjs", ...args], { encoding: "utf8" });
    assert.equal(result.status, 1); assert.equal(result.stdout, "");
    const error = JSON.parse(result.stderr);
    assert.equal(error.code, "HR-OPS-027"); assert.equal(error.retryable, true);
    assert.match(error.summaryZh, /数据库迁移/); assert.match(error.summaryEn, /database migration/);
    assert.equal(error.recoveryAction, "inspect_schema_release_stage_and_fix_forward");
    assert.match(error.technicalCause, /^schema_rehearsal_(arguments|generation)_invalid$/);
  }
});
