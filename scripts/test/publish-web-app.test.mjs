import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { lstat, mkdir, mkdtemp, readdir, readFile, readlink, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { gzipSync } from "node:zlib";
import { packageWebApp, writeTar } from "../package-web-app.mjs";
import { publishWebApp, readTar, rollbackWebApp } from "../../deploy/publish-web-app.mjs";

const COMMIT_A = "a".repeat(40);
const COMMIT_B = "b".repeat(40);

async function fixture(t) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), "publish-web-app-test-")));
  t.after(() => rm(base, { recursive: true, force: true }));
  const root = path.join(base, "web");
  await mkdir(path.join(root, "releases"), { recursive: true });
  const build = async (name, indexText) => {
    const dist = path.join(base, name);
    await mkdir(path.join(dist, "assets"), { recursive: true });
    await writeFile(path.join(dist, "index.html"), indexText);
    await writeFile(path.join(dist, "assets", `index-${name}.js`), `console.log("${name}")`);
    await writeFile(path.join(dist, "sw.js"), "self.addEventListener('fetch', () => {});");
    const out = path.join(base, `${name}-out`);
    await mkdir(out);
    return { dist, out };
  };
  return { base, root, build };
}

async function packaged(fx, name, commit, version = "0.1.0") {
  const { dist, out } = await fx.build(name, `<!doctype html><title>${name}</title>`);
  return packageWebApp({ distDir: dist, outputDir: out, version, sourceCommit: commit });
}

test("packaging is reproducible and lists every file with its hash", async (t) => {
  const fx = await fixture(t);
  const first = await packaged(fx, "one", COMMIT_A);
  const again = await packageWebApp({
    distDir: path.join(fx.base, "one"),
    outputDir: await mkdtemp(path.join(fx.base, "again-")),
    version: "0.1.0",
    sourceCommit: COMMIT_A,
  });
  assert.equal(first.manifest.archiveSha256, again.manifest.archiveSha256);
  assert.equal(first.releaseId, "0.1.0-aaaaaaaaaaaa");
  assert.deepEqual(first.manifest.files.map((file) => file.path), ["assets/index-one.js", "index.html", "sw.js"]);
  const entries = readTar((await import("node:zlib")).gunzipSync(await readFile(first.archivePath)));
  assert.deepEqual(entries.map((entry) => entry.path), ["assets/index-one.js", "index.html", "sw.js"]);
});

test("publish installs a release, switches current atomically, and rollback returns to the previous one", async (t) => {
  const fx = await fixture(t);
  const a = await packaged(fx, "one", COMMIT_A);
  const first = await publishWebApp({ root: fx.root, archivePath: a.archivePath, manifestPath: a.manifestPath });
  assert.deepEqual(first, { ok: true, releaseId: a.releaseId, installed: true, previous: null });
  assert.equal(await readlink(path.join(fx.root, "current")), `releases/${a.releaseId}`);
  assert.match(await readFile(path.join(fx.root, "current", "index.html"), "utf8"), /<title>one</);
  const mode = (await lstat(path.join(fx.root, "releases", a.releaseId, "index.html"))).mode & 0o777;
  assert.equal(mode, 0o644);

  // Publishing the same release again is a verified no-op.
  const repeat = await publishWebApp({ root: fx.root, archivePath: a.archivePath, manifestPath: a.manifestPath });
  assert.deepEqual(repeat, { ok: true, releaseId: a.releaseId, installed: false, previous: a.releaseId });

  const b = await packaged(fx, "two", COMMIT_B, "0.1.1");
  const second = await publishWebApp({ root: fx.root, archivePath: b.archivePath, manifestPath: b.manifestPath });
  assert.equal(second.previous, a.releaseId);
  assert.match(await readFile(path.join(fx.root, "current", "index.html"), "utf8"), /<title>two</);
  // The older release stays: browsers holding its index.html still fetch its hashed assets.
  assert.equal((await lstat(path.join(fx.root, "releases", a.releaseId, "assets", "index-one.js"))).isFile(), true);

  const rolledBack = await rollbackWebApp({ root: fx.root });
  assert.deepEqual(rolledBack, { ok: true, releaseId: a.releaseId, previous: b.releaseId });
  assert.match(await readFile(path.join(fx.root, "current", "index.html"), "utf8"), /<title>one</);
  // Rolling back again goes forward to the release it left, like a toggle.
  assert.equal((await rollbackWebApp({ root: fx.root })).releaseId, b.releaseId);
  assert.deepEqual((await readdir(fx.root)).filter((name) => name.startsWith(".current-") || name.startsWith(".previous-")), []);
});

test("tampered, mismatched or unsafe archives are refused before anything is installed", async (t) => {
  const fx = await fixture(t);
  const a = await packaged(fx, "one", COMMIT_A);
  const manifest = JSON.parse(await readFile(a.manifestPath, "utf8"));
  const attempt = async (archive, manifestValue, cause) => {
    const archivePath = path.join(fx.base, `tampered-${Math.random().toString(16).slice(2)}.tar.gz`);
    const manifestPath = `${archivePath}.json`;
    await writeFile(archivePath, archive);
    await writeFile(manifestPath, JSON.stringify(manifestValue));
    await assert.rejects(
      () => publishWebApp({ root: fx.root, archivePath, manifestPath }),
      (error) => error?.name === "PublishWebAppError" && cause.test(error.message),
    );
  };
  const archive = await readFile(a.archivePath);
  const withArchive = (bytes) => ({ ...manifest, archiveSha256: sha256(bytes) });

  await attempt(archive, { ...manifest, archiveSha256: "0".repeat(64) }, /archive_sha256_mismatch/);
  const altered = gzipSync(writeTar([
    { path: "assets/index-one.js", data: Buffer.from("evil()") },
    { path: "index.html", data: Buffer.from("<!doctype html><title>one</title>") },
    { path: "sw.js", data: Buffer.from("self.addEventListener('fetch', () => {});") },
  ]));
  await attempt(altered, withArchive(altered), /archive_entry_mismatch/);
  const extra = gzipSync(writeTar([
    ...manifest.files.map((file) => ({ path: file.path, data: Buffer.alloc(0) })),
    { path: "extra.html", data: Buffer.from("x") },
  ]));
  await attempt(extra, withArchive(extra), /archive_entries_do_not_match_manifest/);
  for (const name of ["../escape.html", "/etc/passwd", ".env", "assets/../../x"]) {
    const unsafe = gzipSync(rawTar(name, "0", "x"));
    await attempt(unsafe, withArchive(unsafe), /tar_entry_path_invalid|archive_entries/);
  }
  const link = gzipSync(rawTar("index.html", "2", ""));
  await attempt(link, withArchive(link), /tar_entry_not_regular_file|archive_entries/);
  const corrupt = rawTar("index.html", "0", "x");
  corrupt[150] ^= 1;
  const corruptArchive = gzipSync(corrupt);
  await attempt(corruptArchive, withArchive(corruptArchive), /tar_checksum_invalid|archive_entries/);
  await attempt(archive, { ...manifest, releaseId: "0.1.0-bbbbbbbbbbbb" }, /manifest_release_id_invalid/);
  await attempt(archive, { ...manifest, files: manifest.files.filter((file) => file.path !== "index.html") }, /manifest_index_html_missing/);

  assert.deepEqual(await readdir(path.join(fx.root, "releases")), []);
  assert.equal(await lstat(path.join(fx.root, "current")).catch(() => null), null);
});

test("an installed release that was changed on disk, or a hand-made current, is refused", async (t) => {
  const fx = await fixture(t);
  const a = await packaged(fx, "one", COMMIT_A);
  await publishWebApp({ root: fx.root, archivePath: a.archivePath, manifestPath: a.manifestPath });
  await writeFile(path.join(fx.root, "releases", a.releaseId, "sw.js"), "changed");
  await assert.rejects(
    () => publishWebApp({ root: fx.root, archivePath: a.archivePath, manifestPath: a.manifestPath }),
    /installed_release_differs/,
  );
  const b = await packaged(fx, "two", COMMIT_B, "0.1.1");
  await rm(path.join(fx.root, "current"));
  await mkdir(path.join(fx.root, "current"));
  await assert.rejects(
    () => publishWebApp({ root: fx.root, archivePath: b.archivePath, manifestPath: b.manifestPath }),
    /current_not_a_symlink/,
  );
  await rm(path.join(fx.root, "current"), { recursive: true });
  await symlink("/etc", path.join(fx.root, "current"));
  await assert.rejects(
    () => publishWebApp({ root: fx.root, archivePath: b.archivePath, manifestPath: b.manifestPath }),
    /current_target_invalid/,
  );
  await assert.rejects(() => rollbackWebApp({ root: fx.root }), /no_previous_release/);
});

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

/** A single-entry ustar written by hand, to feed names and types the real packager refuses. */
function rawTar(name, type, content) {
  const data = Buffer.from(content);
  const header = Buffer.alloc(512, 0);
  header.write(name, 0, 100, "utf8");
  header.write("0000644\0", 100, 8, "ascii");
  header.write(`${data.length.toString(8).padStart(11, "0")}\0`, 124, 12, "ascii");
  header.write("00000000000\0", 136, 12, "ascii");
  header.fill(0x20, 148, 156);
  header.write(type, 156, 1, "ascii");
  header.write("ustar\0", 257, 6, "ascii");
  header.write("00", 263, 2, "ascii");
  let checksum = 0;
  for (const byte of header) checksum += byte;
  header.write(`${checksum.toString(8).padStart(6, "0")}\0 `, 148, 8, "ascii");
  return Buffer.concat([header, data, Buffer.alloc((512 - (data.length % 512)) % 512), Buffer.alloc(1024)]);
}
