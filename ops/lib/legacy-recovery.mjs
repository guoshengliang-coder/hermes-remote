import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import {
  chmod, lstat, mkdir, opendir, readFile, readlink, realpath, rm, writeFile,
} from "node:fs/promises";
import { hostname as systemHostname } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { sha256File } from "./config.mjs";
import { OpsError, redactOpsValue } from "./errors.mjs";
import { loadLegacyArchiveManifest } from "./legacy-recovery-config.mjs";
import { assertNoSymlinkAncestors, createCommandRunner } from "./system.mjs";

const MAX_ENTRIES = 50_000;
const ENCRYPTION_KIND = "openssl-cms-auth-enveloped-aes-256-gcm";

export async function captureLegacyRecovery(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 30_000 });
  const actualHostname = options.hostname ?? systemHostname();
  const actualPlatform = options.platform ?? process.platform;
  const expectedConfirmation = `production:${config.sourceHostname}`;
  if (options.confirmation !== expectedConfirmation) fail("legacy_capture_confirmation_required", "legacy_capture_authorize");
  if (actualPlatform !== "linux" || actualHostname !== config.sourceHostname) fail("legacy_capture_host_mismatch", "legacy_capture_authorize");
  prerequisites(runner, ["openssl", "systemctl", "tar"]);
  assertServiceActive(runner, config.service.name, "legacy_capture_service_before");

  const cleanup = [config.archiveFile, config.manifestFile];
  try {
    await assertSecureInput(config.recipientCertificate, false, "legacy_capture_recipient_certificate_invalid");
    await prepareNewOutput(config.archiveFile);
    await prepareNewOutput(config.manifestFile);
    const certificateSha256 = await sha256File(config.recipientCertificate);
    const certificate = runner.run("openssl", [
      "x509", "-in", config.recipientCertificate, "-noout", "-checkend", "0",
    ], { allowFailure: true });
    if (certificate.status !== 0) fail("legacy_capture_recipient_certificate_invalid", "legacy_capture_encrypt");

    const before = await collectInventory(config.roots, config.maximumTotalBytes);
    await verifyIdentityFiles(config.identityFiles, before);
    await encryptInventory(before, config.recipientCertificate, config.archiveFile);
    const after = await collectInventory(config.roots, config.maximumTotalBytes);
    if (JSON.stringify(before) !== JSON.stringify(after)) fail("legacy_capture_source_changed", "legacy_capture_consistency");
    await verifyIdentityFiles(config.identityFiles, after);
    assertServiceActive(runner, config.service.name, "legacy_capture_service_after");

    const archiveInfo = await lstat(config.archiveFile);
    if (!archiveInfo.isFile() || archiveInfo.isSymbolicLink() || archiveInfo.size < 1) {
      fail("legacy_capture_archive_invalid", "legacy_capture_encrypt");
    }
    const archiveSha256 = await sha256File(config.archiveFile);
    const createdAt = (options.now ?? (() => new Date()))().toISOString();
    const identityDigest = createHash("sha256")
      .update(JSON.stringify([...config.identityFiles].sort((left, right) => left.path.localeCompare(right.path))))
      .digest("hex");
    const manifest = {
      schemaVersion: 1,
      kind: "hermes-go-legacy-recovery-archive-v1",
      sourceHostname: config.sourceHostname,
      createdAt,
      service: config.service,
      captureRoots: config.roots,
      encryption: {
        kind: ENCRYPTION_KIND,
        recipientCertificateSha256: certificateSha256,
      },
      archiveSha256,
      archiveBytes: archiveInfo.size,
      identityFiles: config.identityFiles,
      subject: { identityDigest },
      entries: before,
    };
    await writeFile(config.manifestFile, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    cleanup.length = 0;
    return {
      ok: true,
      command: "legacy-capture",
      environment: config.environment,
      sourceHostname: config.sourceHostname,
      createdAt,
      archiveSha256,
      archiveBytes: archiveInfo.size,
      entryCount: before.length,
      subject: { identityDigest },
      encryption: ENCRYPTION_KIND,
    };
  } catch (error) {
    for (const filePath of cleanup) await rm(filePath, { force: true }).catch(() => {});
    if (error instanceof OpsError) throw error;
    throw new OpsError("recovery", error instanceof Error ? error.message : error, "legacy_capture_execute");
  }
}

export async function verifyLegacyRecovery(config, options = {}) {
  const runner = options.runner ?? createCommandRunner({ timeoutMs: 30_000 });
  const restoreHostname = options.hostname ?? systemHostname();
  const expectedConfirmation = `isolated:${config.expectedSourceHostname}`;
  if (options.confirmation !== expectedConfirmation) fail("legacy_restore_confirmation_required", "legacy_restore_authorize");
  if (restoreHostname === config.expectedSourceHostname) fail("legacy_restore_must_be_off_host", "legacy_restore_authorize");
  prerequisites(runner, ["node", "openssl", "tar"]);

  const manifest = await loadLegacyArchiveManifest(config.manifestFile);
  if (manifest.sourceHostname !== config.expectedSourceHostname) fail("legacy_restore_source_mismatch", "legacy_restore_validate");
  await assertSecureInput(config.archiveFile, false, "legacy_restore_archive_invalid");
  await assertSecureInput(config.recipientCertificate, false, "legacy_restore_certificate_invalid");
  await assertSecureInput(config.recipientPrivateKey, true, "legacy_restore_private_key_unsafe");
  const certSha256 = await sha256File(config.recipientCertificate);
  if (certSha256 !== manifest.encryption.recipientCertificateSha256) fail("legacy_restore_certificate_mismatch", "legacy_restore_validate");
  const archiveInfo = await lstat(config.archiveFile);
  if (!archiveInfo.isFile() || archiveInfo.isSymbolicLink() || archiveInfo.size !== manifest.archiveBytes) {
    fail("legacy_restore_archive_size_mismatch", "legacy_restore_validate");
  }
  if (await sha256File(config.archiveFile) !== manifest.archiveSha256) fail("legacy_restore_archive_hash_mismatch", "legacy_restore_validate");
  await assertPathMissing(config.restoreRoot, "legacy_restore_root_exists");
  await assertPathMissing(config.evidenceFile, "legacy_restore_evidence_exists");
  try {
    await assertNoSymlinkAncestors(path.dirname(config.restoreRoot));
  } catch (error) {
    fail(error instanceof OpsError ? error.technicalCause : error, "legacy_restore_root_validate");
  }
  await mkdir(config.restoreRoot, { mode: 0o700 });
  const plainArchive = path.join(config.restoreRoot, ".hermes-legacy-recovery.tar");
  try {
    await decryptArchive(config, plainArchive);
    const archivedPaths = await listArchive(plainArchive);
    const expectedPaths = manifest.entries.map((entry) => archiveName(entry.path)).sort();
    if (JSON.stringify(archivedPaths) !== JSON.stringify(expectedPaths)) fail("legacy_restore_archive_entries_mismatch", "legacy_restore_extract");
    await extractArchive(plainArchive, config.restoreRoot);
    await verifyRestoredEntries(config.restoreRoot, manifest);
    await rm(plainArchive, { force: true });

    const serviceVerifier = options.serviceVerifier ?? startAndSmokeRestoredService;
    await serviceVerifier(config, manifest, options);
  } catch (error) {
    if (error instanceof OpsError) throw error;
    throw new OpsError("recovery", error instanceof Error ? error.message : error, "legacy_restore_execute");
  } finally {
    await rm(config.restoreRoot, { recursive: true, force: true }).catch(() => {});
  }

  const restoredAt = (options.now ?? (() => new Date()))().toISOString();
  const evidence = {
    schemaVersion: 1,
    kind: "hermes-go-legacy-recovery-v1",
    sourceHostname: manifest.sourceHostname,
    createdAt: manifest.createdAt,
    artifactSha256: manifest.archiveSha256,
    subject: manifest.subject,
    restoreHostname,
    restoredAt,
    verifiedChecks: ["archive_hash", "files_restored", "service_start"],
  };
  await prepareNewOutput(config.evidenceFile);
  await writeFile(config.evidenceFile, `${JSON.stringify(evidence, null, 2)}\n`, { flag: "wx", mode: 0o600 });
  return {
    ok: true,
    command: "legacy-restore",
    environment: config.environment,
    sourceHostname: manifest.sourceHostname,
    restoreHostname,
    restoredAt,
    artifactSha256: manifest.archiveSha256,
    subject: manifest.subject,
    verifiedChecks: evidence.verifiedChecks,
  };
}

async function collectInventory(roots, maximumTotalBytes) {
  const entries = new Map();
  let totalBytes = 0;
  for (const root of roots) {
    if (await realpath(root.path) !== root.path) fail("legacy_capture_root_symlinked", "legacy_capture_inventory");
    await walk(root.path);
  }
  const result = [...entries.values()].sort((left, right) => left.path.localeCompare(right.path));
  if (result.length > MAX_ENTRIES) fail("legacy_capture_entry_limit_exceeded", "legacy_capture_inventory");
  return result;

  async function walk(current) {
    safeArchivePath(current);
    const info = await lstat(current);
    const mode = info.mode & 0o7777;
    if (info.isDirectory()) {
      add({ path: current, type: "directory", mode });
      const directory = await opendir(current);
      const names = [];
      for await (const item of directory) {
        if (/[\u0000-\u001f\u007f]/.test(item.name)) fail("legacy_capture_filename_unsafe", "legacy_capture_inventory");
        if (item.name.startsWith("._")) continue;
        names.push(item.name);
      }
      names.sort();
      for (const name of names) await walk(path.join(current, name));
      return;
    }
    if (info.isFile()) {
      totalBytes += info.size;
      if (totalBytes > maximumTotalBytes) fail("legacy_capture_size_limit_exceeded", "legacy_capture_inventory");
      add({ path: current, type: "file", mode, size: info.size, sha256: await sha256File(current) });
      return;
    }
    if (info.isSymbolicLink()) {
      const linkTarget = await readlink(current);
      if (path.isAbsolute(linkTarget) || /[\u0000-\u001f\u007f]/.test(linkTarget)) fail("legacy_capture_symlink_unsafe", "legacy_capture_inventory");
      const resolved = await realpath(current);
      if (!roots.some((root) => within(root.path, resolved))) fail("legacy_capture_symlink_outside_roots", "legacy_capture_inventory");
      add({
        path: current,
        type: "symlink",
        mode,
        linkTarget,
        sha256: createHash("sha256").update(linkTarget).digest("hex"),
      });
      return;
    }
    fail("legacy_capture_special_file_rejected", "legacy_capture_inventory");
  }

  function add(entry) {
    const previous = entries.get(entry.path);
    if (previous && JSON.stringify(previous) !== JSON.stringify(entry)) fail("legacy_capture_overlapping_roots_changed", "legacy_capture_inventory");
    entries.set(entry.path, entry);
    if (entries.size > MAX_ENTRIES) fail("legacy_capture_entry_limit_exceeded", "legacy_capture_inventory");
  }
}

async function verifyIdentityFiles(identityFiles, entries) {
  const byPath = new Map(entries.map((entry) => [entry.path, entry]));
  for (const identity of identityFiles) {
    const entry = byPath.get(identity.path);
    if (!entry || entry.type !== "file" || entry.sha256 !== identity.sha256) {
      fail("legacy_capture_identity_mismatch", "legacy_capture_identity");
    }
  }
}

async function encryptInventory(entries, certificate, archiveFile) {
  const tar = spawn("tar", ["-C", "/", "--format", "pax", "--no-recursion", "--null", "-T", "-", "-cf", "-"], {
    stdio: ["pipe", "pipe", "pipe"], shell: false,
  });
  const openssl = spawn("openssl", ["cms", "-encrypt", "-binary", "-aes-256-gcm", "-outform", "DER", certificate], {
    stdio: ["pipe", "pipe", "pipe"], shell: false,
  });
  const output = createWriteStream(archiveFile, { flags: "wx", mode: 0o600 });
  const tarError = boundedStderr(tar.stderr);
  const opensslError = boundedStderr(openssl.stderr);
  tar.stdout.pipe(openssl.stdin);
  openssl.stdout.pipe(output);
  tar.stdin.end(Buffer.from(`${entries.map((entry) => archiveName(entry.path)).join("\0")}\0`));
  const [tarStatus, opensslStatus, outputStatus] = await Promise.all([
    processExit(tar), processExit(openssl), streamFinish(output),
  ]);
  if (tarStatus !== 0 || opensslStatus !== 0 || outputStatus !== 0) {
    const cause = `archive_encrypt_failed tar=${tarStatus} openssl=${opensslStatus} output=${outputStatus} ${await tarError} ${await opensslError}`;
    fail(cause, "legacy_capture_encrypt");
  }
}

async function decryptArchive(config, outputFile) {
  const openssl = spawn("openssl", [
    "cms", "-decrypt", "-binary", "-inform", "DER", "-in", config.archiveFile,
    "-recip", config.recipientCertificate, "-inkey", config.recipientPrivateKey,
  ], { stdio: ["ignore", "pipe", "pipe"], shell: false });
  const output = createWriteStream(outputFile, { flags: "wx", mode: 0o600 });
  const stderr = boundedStderr(openssl.stderr);
  openssl.stdout.pipe(output);
  const [status, outputStatus] = await Promise.all([processExit(openssl), streamFinish(output)]);
  if (status !== 0 || outputStatus !== 0) fail(`legacy_restore_decrypt_failed ${await stderr}`, "legacy_restore_decrypt");
}

async function listArchive(archiveFile) {
  const tar = spawn("tar", ["-tf", archiveFile], { stdio: ["ignore", "pipe", "pipe"], shell: false });
  const stdout = boundedOutput(tar.stdout, 16 * 1024 * 1024);
  const stderr = boundedStderr(tar.stderr);
  const status = await processExit(tar);
  if (status !== 0) fail(`legacy_restore_archive_list_failed ${await stderr}`, "legacy_restore_extract");
  const lines = (await stdout).split("\n").filter(Boolean).map((entry) => entry.endsWith("/") ? entry.slice(0, -1) : entry);
  if (lines.length > MAX_ENTRIES || new Set(lines).size !== lines.length || lines.some((entry) => !safeArchiveName(entry))) {
    fail("legacy_restore_archive_listing_unsafe", "legacy_restore_extract");
  }
  return lines.sort();
}

async function extractArchive(archiveFile, restoreRoot) {
  const tar = spawn("tar", ["-xf", archiveFile, "-C", restoreRoot, "--no-same-owner", "--no-same-permissions"], {
    stdio: ["ignore", "ignore", "pipe"], shell: false,
  });
  const stderr = boundedStderr(tar.stderr);
  const status = await processExit(tar);
  if (status !== 0) fail(`legacy_restore_extract_failed ${await stderr}`, "legacy_restore_extract");
}

async function verifyRestoredEntries(restoreRoot, manifest) {
  for (const entry of manifest.entries) {
    const restored = restoredPath(restoreRoot, entry.path);
    const info = await lstat(restored);
    if (entry.type === "directory") {
      if (!info.isDirectory()) fail("legacy_restore_entry_type_mismatch", "legacy_restore_verify_files");
      if ((info.mode & 0o7777) !== entry.mode) await chmod(restored, entry.mode);
    }
    if (entry.type === "file") {
      if (!info.isFile() || info.isSymbolicLink() || info.size !== entry.size || await sha256File(restored) !== entry.sha256) {
        fail("legacy_restore_file_mismatch", "legacy_restore_verify_files");
      }
      if ((info.mode & 0o7777) !== entry.mode) await chmod(restored, entry.mode);
    }
    if (entry.type === "symlink") {
      if (!info.isSymbolicLink() || await readlink(restored) !== entry.linkTarget) fail("legacy_restore_symlink_mismatch", "legacy_restore_verify_files");
      const resolved = await realpath(restored);
      if (!within(restoreRoot, resolved)) fail("legacy_restore_symlink_escape", "legacy_restore_verify_files");
    }
  }
}

async function startAndSmokeRestoredService(config, manifest, options) {
  const environmentPath = restoredPath(config.restoreRoot, manifest.service.environmentFile);
  const environment = parseEnvironment(await readFile(environmentPath, "utf8"));
  for (const [key, value] of Object.entries(environment)) {
    if ((key.endsWith("_FILE") || key === "LIFECYCLE_EVENT_STORE_FILE") && path.isAbsolute(value)) {
      const candidate = restoredPath(config.restoreRoot, value);
      if (!manifest.entries.some((entry) => entry.path === value && entry.type === "file")) {
        fail("legacy_restore_environment_file_missing", "legacy_restore_service_prepare");
      }
      environment[key] = candidate;
    }
  }
  environment.HOST = "127.0.0.1";
  environment.PORT = String(config.listenPort);
  environment.ACCOUNT_AUTH_ENABLED = "0";
  environment.ACCOUNT_BINDING_ENABLED = "0";
  for (const key of ["NODE_OPTIONS", "NODE_PATH", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH"]) {
    delete environment[key];
  }
  const temporaryDirectory = path.join(config.restoreRoot, ".tmp");
  await mkdir(temporaryDirectory, { mode: 0o700 });
  environment.HOME = config.restoreRoot;
  environment.TMPDIR = temporaryDirectory;
  const appToken = environment.APP_TOKEN ?? await readSmallSecret(environment.APP_TOKEN_FILE);
  if (!appToken || appToken.length < 8) fail("legacy_restore_app_token_unavailable", "legacy_restore_service_prepare");
  const entrypoint = restoredPath(config.restoreRoot, manifest.service.entrypoint);
  const workingDirectory = restoredPath(config.restoreRoot, manifest.service.workingDirectory);
  const service = spawn("node", [entrypoint], {
    cwd: workingDirectory,
    env: {
      PATH: process.env.PATH,
      LANG: process.env.LANG ?? "C.UTF-8",
      LC_ALL: process.env.LC_ALL ?? "C.UTF-8",
      NODE_ENV: "production",
      ...environment,
    },
    stdio: ["ignore", "ignore", "pipe"],
    shell: false,
  });
  const serviceError = boundedStderr(service.stderr);
  let exited = false;
  let spawnFailure;
  service.once("error", (error) => { spawnFailure = error; });
  service.once("exit", () => { exited = true; });
  const fetchImpl = options.fetchImpl ?? fetch;
  const deadline = Date.now() + (options.smokeTimeoutMs ?? 15_000);
  let healthy = false;
  while (Date.now() < deadline && !exited) {
    try {
      const response = await fetchImpl(`http://127.0.0.1:${config.listenPort}${manifest.service.healthPath}`);
      if (response.status === 200) {
        healthy = true;
        break;
      }
    } catch {}
    await delay(150);
  }
  if (!healthy) {
    await stopService(service);
    fail(`legacy_restore_health_smoke_failed ${spawnFailure?.message ?? ""} ${await serviceError}`, "legacy_restore_service_smoke");
  }
  const invalid = await fetchImpl(`http://127.0.0.1:${config.listenPort}${manifest.service.authenticatedPath}`, {
    headers: { "x-hermes-session-token": `${appToken}-invalid` },
  });
  if (invalid.status !== 401) {
    await stopService(service);
    fail("legacy_restore_invalid_token_contract_failed", "legacy_restore_service_smoke");
  }
  const authenticated = await fetchImpl(`http://127.0.0.1:${config.listenPort}${manifest.service.authenticatedPath}`, {
    headers: { "x-hermes-session-token": appToken },
  });
  if (![200, 502, 503, 504].includes(authenticated.status)) {
    await stopService(service);
    fail("legacy_restore_authenticated_contract_failed", "legacy_restore_service_smoke");
  }
  await stopService(service);
}

function parseEnvironment(text) {
  const result = {};
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = line.match(/^([A-Z][A-Z0-9_]*)=(.*)$/);
    if (!match) fail("legacy_restore_environment_invalid", "legacy_restore_service_prepare");
    let value = match[2];
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    if (/[\u0000\r\n]/.test(value)) fail("legacy_restore_environment_value_invalid", "legacy_restore_service_prepare");
    result[match[1]] = value;
  }
  return result;
}

async function readSmallSecret(filePath) {
  if (!filePath) return undefined;
  const info = await lstat(filePath);
  if (!info.isFile() || info.isSymbolicLink() || info.size < 1 || info.size > 16 * 1024) return undefined;
  return (await readFile(filePath, "utf8")).trim();
}

async function prepareNewOutput(filePath) {
  try {
    await assertNoSymlinkAncestors(path.dirname(filePath));
  } catch (error) {
    fail(error instanceof OpsError ? error.technicalCause : error, "legacy_recovery_output_validate");
  }
  await mkdir(path.dirname(filePath), { recursive: true, mode: 0o700 });
  await assertPathMissing(filePath, "legacy_recovery_output_exists");
}

async function assertSecureInput(filePath, privateInput, cause) {
  try {
    const info = await lstat(filePath);
    if (!info.isFile() || info.isSymbolicLink() || (privateInput && (info.mode & 0o077) !== 0)) {
      fail(cause, "legacy_recovery_input_validate");
    }
  } catch (error) {
    if (error instanceof OpsError) throw error;
    fail(cause, "legacy_recovery_input_validate");
  }
}

async function assertPathMissing(filePath, cause) {
  try {
    await lstat(filePath);
    fail(cause, "legacy_recovery_output_validate");
  } catch (error) {
    if (error instanceof OpsError) throw error;
    if (error?.code !== "ENOENT") throw error;
  }
}

function prerequisites(runner, commands) {
  const missing = commands.filter((command) => runner.run("which", [command], { allowFailure: true }).status !== 0);
  if (missing.length > 0) fail(`legacy_recovery_missing_${missing.join("_")}`, "legacy_recovery_prerequisites");
}

function assertServiceActive(runner, serviceName, stage) {
  const result = runner.run("systemctl", ["is-active", "--quiet", `${serviceName}.service`], { allowFailure: true });
  if (result.status !== 0) fail("legacy_capture_service_inactive", stage);
}

function safeArchivePath(value) {
  if (!path.isAbsolute(value) || path.normalize(value) !== value || value === "/" || /[\u0000-\u001f\u007f]/.test(value)) {
    fail("legacy_capture_path_unsafe", "legacy_capture_inventory");
  }
}

function archiveName(absolute) {
  safeArchivePath(absolute);
  return absolute.slice(1);
}

function safeArchiveName(value) {
  if (!value || path.isAbsolute(value) || path.posix.normalize(value) !== value || value === ".." || value.startsWith("../")) return false;
  return !/[\u0000-\u001f\u007f]/.test(value);
}

function restoredPath(root, absolute) {
  safeArchivePath(absolute);
  const result = path.join(root, absolute.slice(1));
  if (!within(root, result)) fail("legacy_restore_path_escape", "legacy_restore_validate");
  return result;
}

function within(root, target) {
  const relative = path.relative(root, target);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== "..");
}

function boundedStderr(stream) {
  return boundedOutput(stream, 16 * 1024).then((value) => redactOpsValue(value));
}

function boundedOutput(stream, maximum) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    stream.on("data", (chunk) => {
      size += chunk.length;
      if (size <= maximum) chunks.push(chunk);
    });
    stream.on("error", reject);
    stream.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

function processExit(child) {
  return new Promise((resolve, reject) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      resolve(Number.isInteger(child.exitCode) ? child.exitCode : 1);
      return;
    }
    child.once("error", reject);
    child.once("close", (code) => resolve(Number.isInteger(code) ? code : 1));
  });
}

function streamFinish(stream) {
  return new Promise((resolve) => {
    stream.once("finish", () => resolve(0));
    stream.once("error", () => resolve(1));
  });
}

async function stopService(service) {
  if (!service || service.exitCode !== null) return;
  const exit = processExit(service).then(() => true, () => true);
  service.kill("SIGTERM");
  let timeout;
  const elapsed = new Promise((resolve) => {
    timeout = setTimeout(() => resolve(false), 5_000);
  });
  const exited = await Promise.race([exit, elapsed]);
  clearTimeout(timeout);
  if (!exited && service.exitCode === null) {
    service.kill("SIGKILL");
    await exit;
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function fail(cause, stage) {
  throw new OpsError("recovery", cause, stage);
}
