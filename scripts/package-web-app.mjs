#!/usr/bin/env node
// Packages a built Web app (web/dist) as the artifact deploy/publish-web-app.mjs installs on the
// host: a reproducible gzip'd ustar holding regular files only, plus a manifest naming every file
// with its size and SHA-256 and the archive's own SHA-256.
//
//   node scripts/package-web-app.mjs <dist dir> <output dir> <version> <40-hex source commit>
//
// Prints ARCHIVE=, MANIFEST= and RELEASE_ID= lines for the publisher.
import { createHash } from "node:crypto";
import { lstat, readdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { gzipSync } from "node:zlib";

const MAX_FILES = 2000;
const MAX_TOTAL_BYTES = 64 * 1024 * 1024;
const FILE_PATH = /^[A-Za-z0-9_-][A-Za-z0-9._-]*(?:\/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$/;

export async function packageWebApp({ distDir, outputDir, version, sourceCommit }) {
  if (!/^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/.test(version)) throw new Error("version_invalid");
  if (!/^[0-9a-f]{40}$/.test(sourceCommit)) throw new Error("source_commit_invalid");
  const files = [];
  await collect(distDir, "", files);
  files.sort((left, right) => (left.path < right.path ? -1 : left.path > right.path ? 1 : 0));
  if (!files.some((file) => file.path === "index.html")) throw new Error("index_html_missing");
  if (files.length > MAX_FILES) throw new Error("too_many_files");
  if (files.reduce((sum, file) => sum + file.data.length, 0) > MAX_TOTAL_BYTES) throw new Error("too_large");

  const archive = gzipSync(writeTar(files), { level: 9 });
  const releaseId = `${version}-${sourceCommit.slice(0, 12)}`;
  const archiveName = `Hermes-Web-${releaseId}.tar.gz`;
  const manifest = {
    schemaVersion: 1,
    kind: "hermes-go-web-app-release-v1",
    version,
    sourceCommit,
    releaseId,
    archiveFile: archiveName,
    archiveSha256: sha256(archive),
    files: files.map((file) => ({ path: file.path, size: file.data.length, sha256: sha256(file.data) })),
  };
  const archivePath = path.join(outputDir, archiveName);
  const manifestPath = path.join(outputDir, `Hermes-Web-${releaseId}.manifest.json`);
  await writeFile(archivePath, archive, { mode: 0o644 });
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o644 });
  return { archivePath, manifestPath, releaseId, manifest };
}

async function collect(root, relative, files) {
  for (const entry of await readdir(path.join(root, relative), { withFileTypes: true })) {
    const child = relative ? `${relative}/${entry.name}` : entry.name;
    const info = await lstat(path.join(root, child));
    if (info.isDirectory()) {
      await collect(root, child, files);
    } else if (info.isFile()) {
      if (!FILE_PATH.test(child) || Buffer.byteLength(child) > 100) throw new Error(`file_path_invalid:${child}`);
      files.push({ path: child, data: await readFile(path.join(root, child)) });
    } else {
      // A build never needs links, devices or sockets; refusing them keeps the host side simple.
      throw new Error(`unsupported_entry:${child}`);
    }
  }
}

/** A ustar archive of regular files: mode 0644, uid/gid 0, mtime 0, so equal inputs give equal bytes. */
export function writeTar(files) {
  const blocks = [];
  for (const file of files) {
    const header = Buffer.alloc(512, 0);
    header.write(file.path, 0, 100, "utf8");
    header.write("0000644\0", 100, 8, "ascii");
    header.write("0000000\0", 108, 8, "ascii");
    header.write("0000000\0", 116, 8, "ascii");
    header.write(`${file.data.length.toString(8).padStart(11, "0")}\0`, 124, 12, "ascii");
    header.write("00000000000\0", 136, 12, "ascii");
    header.fill(0x20, 148, 156);
    header.write("0", 156, 1, "ascii");
    header.write("ustar\0", 257, 6, "ascii");
    header.write("00", 263, 2, "ascii");
    let checksum = 0;
    for (const byte of header) checksum += byte;
    header.write(`${checksum.toString(8).padStart(6, "0")}\0 `, 148, 8, "ascii");
    blocks.push(header, file.data);
    const padding = (512 - (file.data.length % 512)) % 512;
    if (padding) blocks.push(Buffer.alloc(padding, 0));
  }
  blocks.push(Buffer.alloc(1024, 0));
  return Buffer.concat(blocks);
}

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const [distDir, outputDir, version, sourceCommit] = process.argv.slice(2);
  try {
    const result = await packageWebApp({ distDir, outputDir, version, sourceCommit });
    process.stdout.write(`ARCHIVE=${result.archivePath}\nMANIFEST=${result.manifestPath}\nRELEASE_ID=${result.releaseId}\n`);
  } catch (error) {
    process.stderr.write(`package-web-app: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
