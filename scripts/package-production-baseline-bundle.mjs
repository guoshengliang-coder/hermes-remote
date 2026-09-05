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
  for (const directory of ["scripts", "ops/lib", "connector/dist", "protocol/dist", "gateway", "release-server"]) {
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
    "scripts/verify-gateway-image-candidate.mjs",
    "scripts/smoke-compat-client.mjs",
  ]) await copyFile(file, root);
  const connectorFiles = (await readdir(path.join(repoRoot, "connector/dist")))
    .filter((name) => name.endsWith(".js") && !name.endsWith(".test.js"))
    .sort();
  for (const name of connectorFiles) await copyFile(path.join("connector/dist", name), root);
  await copyFile("protocol/dist/index.js", root);
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
