#!/usr/bin/env node
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  chmod,
  cp,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  realpath,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { errorPayload, OpsError } from "../ops/lib/errors.mjs";
import {
  createProductionBaselineBundleManifest,
  loadProductionBaselineBundleManifest,
} from "../ops/lib/production-baseline-bundle.mjs";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
let temporaryRoot;
let partialArchive;
let archivePath;
let manifestPath;
let partialArchiveCreated = false;
let archiveCreated = false;
let manifestCreated = false;

try {
  const outputDirectory = await resolveOutputDirectory(process.argv.slice(2));
  assertCleanSource();
  const sourceCommit = run("git", ["rev-parse", "HEAD"]).trim();
  const sourceEpoch = Number(run("git", ["show", "-s", "--format=%ct", sourceCommit]).trim());
  if (!/^[0-9a-f]{40}$/.test(sourceCommit) || !Number.isSafeInteger(sourceEpoch) || sourceEpoch < 1) {
    fail("production_baseline_bundle_source_identity_invalid");
  }

  run("npm", ["run", "build", "-w", "@hermes-remote/protocol"]);
  run("npm", ["run", "build", "-w", "@hermes-remote/connector"]);
  assertCleanSource();

  temporaryRoot = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-r5d-ops-package-")));
  await stageRuntime(temporaryRoot);
  run("npm", ["ci", "--omit=dev", "--ignore-scripts"], { cwd: temporaryRoot, timeout: 120_000 });
  run("node", ["--input-type=module", "-e", [
    "await import('ws');",
    "await import('@hermes-remote/protocol');",
    "await import('./ops/lib/production-smoke-runtime.mjs');",
  ].join(" ")], {
    cwd: temporaryRoot,
  });
  verifyStagedSmokeEntrypoint(temporaryRoot);
  verifyStagedProductionMonitorEntrypoint(temporaryRoot);
  verifyStagedPostgresqlAutomationEntrypoint(temporaryRoot);
  verifyStagedProductionReleaseEntrypoint(temporaryRoot);
  verifyStagedProductionAccountRolloutEntrypoint(temporaryRoot);
  verifyStagedProductionBindingRolloutEntrypoint(temporaryRoot);
  verifyStagedProductionMultiDeviceRolloutEntrypoint(temporaryRoot);
  verifyStagedProductionIdentityWebRolloutEntrypoint(temporaryRoot);
  verifyStagedProductionSharingRolloutEntrypoint(temporaryRoot);

  const sourceShort = sourceCommit.slice(0, 12);
  const archiveFile = `Hermes-R5D-Ops-${sourceShort}.tar.gz`;
  const manifestFile = `Hermes-R5D-Ops-${sourceShort}.manifest.json`;
  archivePath = path.join(outputDirectory, archiveFile);
  manifestPath = path.join(outputDirectory, manifestFile);
  partialArchive = `${archivePath}.tmp`;
  await assertTargetsAbsent([archivePath, manifestPath, partialArchive]);
  partialArchiveCreated = true;
  run("tar", ["-czf", partialArchive, "-C", temporaryRoot, "."], { timeout: 120_000 });
  await chmod(partialArchive, 0o644);
  await rename(partialArchive, archivePath);
  archiveCreated = true;
  partialArchiveCreated = false;
  partialArchive = undefined;

  const archiveSha256 = sha256(await readFile(archivePath));
  const manifest = createProductionBaselineBundleManifest({
    sourceCommit,
    createdAt: new Date(sourceEpoch * 1000).toISOString(),
    archiveFile,
    archiveSha256,
  });
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx", mode: 0o644 });
  manifestCreated = true;
  await loadProductionBaselineBundleManifest(manifestPath);
  process.stdout.write([
    "PRODUCTION_BASELINE_BUNDLE_OK",
    `SOURCE_COMMIT=${sourceCommit}`,
    `ARCHIVE=${archivePath}`,
    `ARCHIVE_SHA256=${archiveSha256}`,
    `MANIFEST=${manifestPath}`,
    "",
  ].join("\n"));
} catch (error) {
  await cleanupOutputs();
  process.stderr.write(`${JSON.stringify(errorPayload(error, "managedBaseline", "production_baseline_bundle_package"))}\n`);
  process.exitCode = 1;
} finally {
  if (temporaryRoot) await rm(temporaryRoot, { recursive: true, force: true }).catch(() => {});
}

async function resolveOutputDirectory(values) {
  if (values.length !== 1) fail("production_baseline_bundle_output_required");
  const input = values[0];
  if (!input || /[\u0000-\u001f\u007f]/.test(input) || path.normalize(input) !== input) {
    fail("production_baseline_bundle_output_invalid");
  }
  let output;
  if (path.isAbsolute(input)) {
    output = await realpath(input);
  } else {
    if (!/^outputs\/[A-Za-z0-9._-]+$/.test(input)) fail("production_baseline_bundle_output_invalid");
    output = path.join(repoRoot, input);
    await mkdir(output, { recursive: true, mode: 0o755 });
    output = await realpath(output);
  }
  const info = await lstat(output);
  if (!info.isDirectory() || info.isSymbolicLink() || output === repoRoot
      || (output.startsWith(`${repoRoot}${path.sep}`) && !output.startsWith(`${path.join(repoRoot, "outputs")}${path.sep}`))) {
    fail("production_baseline_bundle_output_unsafe");
  }
  return output;
}

function assertCleanSource() {
  run("git", ["diff", "--check"]);
  if (run("git", ["status", "--porcelain", "--untracked-files=normal"]).trim()) {
    fail("production_baseline_bundle_requires_clean_source");
  }
}

async function stageRuntime(root) {
  for (const directory of [
    "scripts/lib", "ops/lib", "deploy", "connector/dist", "protocol/dist", "gateway", "release-server",
  ]) {
    await mkdir(path.join(root, directory), { recursive: true, mode: 0o755 });
  }
  for (const file of ["package.json", "package-lock.json", "connector/package.json", "protocol/package.json",
    "gateway/package.json", "release-server/package.json"]) {
    await copyFile(file, root);
  }
  const opsFiles = (await readdir(path.join(repoRoot, "ops/lib"))).filter((name) => name.endsWith(".mjs")).sort();
  for (const name of opsFiles) await copyFile(path.join("ops/lib", name), root);
  for (const file of [
    "scripts/production-baseline.mjs",
    "scripts/production-release.mjs",
    "scripts/production-account-rollout.mjs",
    "scripts/production-binding-rollout.mjs",
    "scripts/production-multi-device-rollout.mjs",
    "scripts/production-identity-web-rollout.mjs",
    "scripts/production-sharing-rollout.mjs",
    "scripts/production-monitor.mjs",
    "scripts/postgresql-recovery.mjs",
    "scripts/postgresql-automation.mjs",
    "scripts/postgresql-provision.mjs",
    "scripts/verify-production-baseline-bundle.mjs",
    "scripts/verify-gateway-image-candidate.mjs",
    "scripts/smoke-compat-client.mjs",
    "scripts/lib/release-errors.mjs",
    "scripts/lib/gateway-candidate-smoke.mjs",
    "ops/production.monitor.example.json",
    "ops/production.account-rollout.example.json",
    "ops/hermes-go-production-account-rollout-config.schema.json",
    "ops/production.binding-rollout.example.json",
    "ops/hermes-go-production-binding-rollout-config.schema.json",
    "ops/production.multi-device-rollout.example.json",
    "ops/hermes-go-production-multi-device-rollout-config.schema.json",
    "ops/production.identity-web-rollout.example.json",
    "ops/hermes-go-production-identity-web-rollout-config.schema.json",
    "ops/production.sharing-rollout.example.json",
    "ops/hermes-go-production-sharing-rollout-config.schema.json",
    "ops/hermesctl-production-monitor-config.schema.json",
    "ops/postgresql-backup-status.schema.json",
    "ops/postgresql.capture-schedule.example.json",
    "ops/postgresql.offhost.example.json",
    "ops/hermesctl-postgresql-capture-schedule-config.schema.json",
    "ops/hermesctl-postgresql-offhost-config.schema.json",
    "ops/postgresql-backup-generation.schema.json",
    "deploy/hermes-go-production-monitor.service.template",
    "deploy/hermes-go-production-monitor-alert.service.template",
    "deploy/hermes-go-production-monitor.timer.template",
    "deploy/hermes-go-postgresql-capture.service.template",
    "deploy/hermes-go-postgresql-capture-alert.service.template",
    "deploy/hermes-go-postgresql-capture.timer.template",
    "deploy/com.hermesgo.postgresql-offhost.plist.template",
    "deploy/hermes-go-postgresql-automation-remote.template",
    "deploy/hermes-go-postgresql-automation.sudoers.template",
  ]) await copyFile(file, root);
  const connectorFiles = (await readdir(path.join(repoRoot, "connector/dist")))
    .filter((name) => name.endsWith(".js") && !name.endsWith(".test.js"))
    .sort();
  for (const name of connectorFiles) await copyFile(path.join("connector/dist", name), root);
  await copyFile("protocol/dist/index.js", root);
}

function verifyStagedSmokeEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/verify-gateway-image-candidate.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1
      || diagnostic?.code !== "HR-RELEASE-003"
      || diagnostic?.stage !== "gateway_oci_smoke"
      || diagnostic?.technicalCause !== "smoke_check=configuration") {
    fail("production_baseline_bundle_smoke_entrypoint_invalid");
  }
}

function verifyStagedProductionMonitorEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-monitor.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-001" || diagnostic?.stage !== "arguments_parse") {
    fail("production_baseline_bundle_monitor_entrypoint_invalid");
  }
}

function verifyStagedPostgresqlAutomationEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/postgresql-automation.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-013"
      || diagnostic?.stage !== "postgresql_automation_arguments") {
    fail("production_baseline_bundle_postgresql_automation_entrypoint_invalid");
  }
}

function verifyStagedProductionReleaseEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-release.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-016"
      || diagnostic?.stage !== "production_release_arguments") {
    fail("production_baseline_bundle_release_entrypoint_invalid");
  }
}

function verifyStagedProductionAccountRolloutEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-account-rollout.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-020"
      || diagnostic?.stage !== "production_account_rollout_arguments") {
    fail("production_baseline_bundle_account_rollout_entrypoint_invalid");
  }
}

function verifyStagedProductionBindingRolloutEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-binding-rollout.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-021"
      || diagnostic?.stage !== "production_binding_rollout_arguments") {
    fail("production_baseline_bundle_binding_rollout_entrypoint_invalid");
  }
}

function verifyStagedProductionMultiDeviceRolloutEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-multi-device-rollout.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-022"
      || diagnostic?.stage !== "production_multi_device_rollout_arguments") {
    fail("production_baseline_bundle_multi_device_rollout_entrypoint_invalid");
  }
}

function verifyStagedProductionIdentityWebRolloutEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-identity-web-rollout.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-023"
      || diagnostic?.stage !== "production_identity_web_rollout_arguments") {
    fail("production_baseline_bundle_identity_web_rollout_entrypoint_invalid");
  }
}

function verifyStagedProductionSharingRolloutEntrypoint(root) {
  const result = spawnSync(process.execPath, ["scripts/production-sharing-rollout.mjs"], {
    cwd: root,
    encoding: "utf8",
    env: {},
    maxBuffer: 64 * 1024,
    timeout: 10_000,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let diagnostic;
  try {
    diagnostic = JSON.parse(String(result.stderr ?? "").trim());
  } catch {}
  if (result.error || result.status !== 1 || String(result.stdout ?? "") !== ""
      || diagnostic?.code !== "HR-OPS-024"
      || diagnostic?.stage !== "production_sharing_rollout_arguments") {
    fail("production_baseline_bundle_sharing_rollout_entrypoint_invalid");
  }
}

async function copyFile(relative, root) {
  const source = path.join(repoRoot, relative);
  const destination = path.join(root, relative);
  const info = await lstat(source);
  if (!info.isFile() || info.isSymbolicLink()) fail(`production_baseline_bundle_source_unsafe=${relative}`);
  await cp(source, destination, { errorOnExist: true, force: false });
}

async function assertTargetsAbsent(paths) {
  for (const target of paths) {
    try {
      await lstat(target);
      fail("production_baseline_bundle_output_exists");
    } catch (error) {
      if (error instanceof OpsError) throw error;
      if (error?.code !== "ENOENT") throw error;
    }
  }
}

function run(command, args, { cwd = repoRoot, timeout = 30_000 } = {}) {
  const result = spawnSync(command, args, {
    cwd,
    encoding: "utf8",
    maxBuffer: 1024 * 1024,
    timeout,
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error || result.status !== 0) fail(`${command}_failed`);
  return String(result.stdout ?? "");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function cleanupOutputs() {
  if (partialArchiveCreated && partialArchive) await rm(partialArchive, { force: true }).catch(() => {});
  if (manifestCreated && manifestPath) await rm(manifestPath, { force: true }).catch(() => {});
  if (archiveCreated && archivePath) await rm(archivePath, { force: true }).catch(() => {});
}

function fail(cause) {
  throw new OpsError("managedBaseline", cause, "production_baseline_bundle_package");
}
