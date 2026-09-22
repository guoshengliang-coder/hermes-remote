#!/usr/bin/env node
// Installs a packaged Web app release on the Gateway host and points `current` at it, or rolls
// `current` back to the release it replaced. The Gateway serves <root>/current (WEB_APP_DIR) from a
// read-only bind mount and reads files per request, so neither operation restarts anything.
//
//   node publish-web-app.mjs publish --root <web root> --archive <tar.gz> --manifest <json>
//   node publish-web-app.mjs rollback --root <web root>
//
// Layout under <root>: releases/<version>-<commit12>/ (immutable once installed), `current` (a
// relative symlink to releases/<id>), `.previous` (the id `current` pointed at before the last
// switch). Old releases are kept: a browser holding an older index.html still requests its hashed
// assets. Run as the owner of <root> (root on the Gateway host), under an exclusive lock held by the
// caller (scripts/publish-web-app.sh takes flock on <root>/.publish.lock).
import { createHash, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, mkdir, open, readFile, readlink, rename, rm, symlink } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";

const MAX_ARCHIVE_BYTES = 80 * 1024 * 1024;
const MAX_EXTRACTED_BYTES = 96 * 1024 * 1024;
const MAX_FILES = 2000;
const RELEASE_ID = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)-[0-9a-f]{12}$/;
const FILE_PATH = /^[A-Za-z0-9_-][A-Za-z0-9._-]*(?:\/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$/;

export class PublishWebAppError extends Error {
  constructor(cause) {
    super(cause);
    this.name = "PublishWebAppError";
  }
}

export async function publishWebApp({ root, archivePath, manifestPath }) {
  await requireRoot(root);
  const manifest = validateManifest(JSON.parse(await readBounded(manifestPath, 1024 * 1024)));
  const archive = await readBounded(archivePath, MAX_ARCHIVE_BYTES);
  if (sha256(archive) !== manifest.archiveSha256) fail("archive_sha256_mismatch");
  let tar;
  try {
    tar = gunzipSync(archive, { maxOutputLength: MAX_EXTRACTED_BYTES });
  } catch {
    fail("archive_not_gzip_or_too_large");
  }
  const entries = readTar(tar);
  const expected = new Map(manifest.files.map((file) => [file.path, file]));
  if (entries.length !== expected.size) fail("archive_entries_do_not_match_manifest");
  for (const entry of entries) {
    const file = expected.get(entry.path);
    if (!file || entry.data.length !== file.size || sha256(entry.data) !== file.sha256) {
      fail(`archive_entry_mismatch:${entry.path}`);
    }
    expected.delete(entry.path);
  }

  const releasesRoot = path.join(root, "releases");
  const target = path.join(releasesRoot, manifest.releaseId);
  let installed = false;
  if (await exists(target)) {
    // Same id means same version and commit: accept only an identical, complete installation.
    await verifyInstalled(target, manifest);
  } else {
    const staging = path.join(releasesRoot, `.staging-${manifest.releaseId}-${randomUUID()}`);
    try {
      await mkdir(staging, { mode: 0o755 });
      for (const entry of entries) {
        const destination = path.join(staging, entry.path);
        await mkdir(path.dirname(destination), { recursive: true, mode: 0o755 });
        await writeDurably(destination, entry.data, 0o644);
      }
      await syncDirectory(staging);
      await rename(staging, target);
      await syncDirectory(releasesRoot);
      installed = true;
    } catch (error) {
      await rm(staging, { recursive: true, force: true }).catch(() => {});
      throw error;
    }
  }
  const previous = await switchCurrent(root, manifest.releaseId);
  return { ok: true, releaseId: manifest.releaseId, installed, previous };
}

export async function rollbackWebApp({ root }) {
  await requireRoot(root);
  let previous;
  try {
    previous = (await readBounded(path.join(root, ".previous"), 256)).toString("utf8").trim();
  } catch {
    fail("no_previous_release");
  }
  if (!RELEASE_ID.test(previous)) fail("previous_release_invalid");
  const index = await lstat(path.join(root, "releases", previous, "index.html")).catch(() => null);
  if (!index?.isFile()) fail("previous_release_missing");
  const replaced = await switchCurrent(root, previous);
  return { ok: true, releaseId: previous, previous: replaced };
}

/** Points `current` at releases/<id> with one rename(2); records what it pointed at before. */
async function switchCurrent(root, releaseId) {
  const current = path.join(root, "current");
  let before = null;
  const link = await lstat(current).catch(() => null);
  if (link) {
    if (!link.isSymbolicLink()) fail("current_not_a_symlink");
    const target = await readlink(current);
    const match = /^releases\/(.+)$/.exec(target);
    if (!match || !RELEASE_ID.test(match[1])) fail("current_target_invalid");
    before = match[1];
  }
  if (before === releaseId) return before;
  const next = path.join(root, `.current-${randomUUID()}`);
  await symlink(`releases/${releaseId}`, next);
  await rename(next, current);
  if (before) {
    const previousTemp = path.join(root, `.previous-${randomUUID()}`);
    await writeDurably(previousTemp, `${before}\n`, 0o644);
    await rename(previousTemp, path.join(root, ".previous"));
  }
  await syncDirectory(root);
  return before;
}

export function validateManifest(value) {
  if (value?.schemaVersion !== 1 || value?.kind !== "hermes-go-web-app-release-v1") fail("manifest_kind_invalid");
  if (!/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(value.version ?? "")) fail("manifest_version_invalid");
  if (!/^[0-9a-f]{40}$/.test(value.sourceCommit ?? "")) fail("manifest_source_commit_invalid");
  if (value.releaseId !== `${value.version}-${value.sourceCommit.slice(0, 12)}`) fail("manifest_release_id_invalid");
  if (!/^[0-9a-f]{64}$/.test(value.archiveSha256 ?? "")) fail("manifest_archive_sha256_invalid");
  if (!Array.isArray(value.files) || value.files.length < 1 || value.files.length > MAX_FILES) fail("manifest_files_invalid");
  const seen = new Set();
  let total = 0;
  for (const file of value.files) {
    if (typeof file?.path !== "string" || !FILE_PATH.test(file.path) || Buffer.byteLength(file.path) > 100
        || seen.has(file.path)
        || !Number.isSafeInteger(file.size) || file.size < 0
        || !/^[0-9a-f]{64}$/.test(file.sha256 ?? "")) {
      fail("manifest_file_invalid");
    }
    seen.add(file.path);
    total += file.size;
  }
  if (!seen.has("index.html")) fail("manifest_index_html_missing");
  if (total > MAX_EXTRACTED_BYTES) fail("manifest_too_large");
  return value;
}

/**
 * Reads the ustar archives scripts/package-web-app.mjs writes and nothing else: regular files with
 * plain relative names only. A link, directory, device, long-name extension, absolute or `..` path
 * is refused before anything is written.
 */
export function readTar(buffer) {
  const entries = [];
  let offset = 0;
  while (offset + 512 <= buffer.length) {
    const header = buffer.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) return entries;
    if (header.toString("ascii", 257, 262) !== "ustar") fail("tar_not_ustar");
    let checksum = 0;
    for (let index = 0; index < 512; index += 1) checksum += index >= 148 && index < 156 ? 0x20 : header[index];
    if (parseInt(header.toString("ascii", 148, 156).replace(/\0.*$/, "").trim(), 8) !== checksum) fail("tar_checksum_invalid");
    const type = header.toString("ascii", 156, 157);
    if (type !== "0" && type !== "\0") fail("tar_entry_not_regular_file");
    const name = header.toString("utf8", 0, 100).replace(/\0.*$/s, "");
    const prefix = header.toString("utf8", 345, 500).replace(/\0.*$/s, "");
    if (prefix || !FILE_PATH.test(name)) fail(`tar_entry_path_invalid:${name}`);
    const size = parseInt(header.toString("ascii", 124, 136).replace(/\0.*$/, "").trim(), 8);
    if (!Number.isSafeInteger(size) || size < 0 || offset + 512 + size > buffer.length) fail("tar_entry_size_invalid");
    entries.push({ path: name, data: Buffer.from(buffer.subarray(offset + 512, offset + 512 + size)) });
    if (entries.length > MAX_FILES) fail("tar_too_many_entries");
    offset += 512 + Math.ceil(size / 512) * 512;
  }
  fail("tar_truncated");
}

async function verifyInstalled(target, manifest) {
  for (const file of manifest.files) {
    const data = await readBounded(path.join(target, file.path), MAX_EXTRACTED_BYTES).catch(() => null);
    if (!data || data.length !== file.size || sha256(data) !== file.sha256) fail("installed_release_differs");
  }
}

async function requireRoot(root) {
  if (typeof root !== "string" || !path.isAbsolute(root) || path.normalize(root) !== root || root === "/") {
    fail("root_invalid");
  }
  for (const directory of [root, path.join(root, "releases")]) {
    const info = await lstat(directory).catch(() => null);
    if (!info?.isDirectory() || info.isSymbolicLink()) fail("root_layout_invalid");
  }
}

async function readBounded(filePath, maximum) {
  const handle = await open(filePath, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size > maximum) fail("input_file_unsafe");
    return await handle.readFile();
  } finally {
    await handle.close();
  }
}

async function writeDurably(filePath, data, mode) {
  const handle = await open(filePath, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, mode);
  try {
    await handle.writeFile(data);
    await handle.chmod(mode);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function syncDirectory(directory) {
  const handle = await open(directory, constants.O_RDONLY);
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function exists(filePath) {
  return (await lstat(filePath).catch(() => null)) !== null;
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function fail(cause) {
  throw new PublishWebAppError(cause);
}

function parseArguments(values) {
  const [command, ...rest] = values;
  const options = {};
  for (let index = 0; index < rest.length; index += 2) {
    const flag = rest[index];
    const value = rest[index + 1];
    if (!flag?.startsWith("--") || !value || value.startsWith("--") || flag.slice(2) in options) fail("arguments_invalid");
    options[flag.slice(2)] = value;
  }
  const allowed = command === "publish" ? ["root", "archive", "manifest"] : command === "rollback" ? ["root"] : null;
  if (!allowed || Object.keys(options).sort().join() !== [...allowed].sort().join()) fail("arguments_invalid");
  return { command, options };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  try {
    const { command, options } = parseArguments(process.argv.slice(2));
    const result = command === "publish"
      ? await publishWebApp({ root: options.root, archivePath: options.archive, manifestPath: options.manifest })
      : await rollbackWebApp({ root: options.root });
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } catch (error) {
    const cause = error instanceof PublishWebAppError ? error.message : `unexpected:${error?.code ?? "error"}`;
    process.stderr.write(`${JSON.stringify({ ok: false, cause })}\n`);
    process.exitCode = 1;
  }
}
