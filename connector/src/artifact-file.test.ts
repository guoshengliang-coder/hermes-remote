import assert from "node:assert/strict";
import { chmod, link, lstat, mkdir, mkdtemp, open, realpath, rename, rm, symlink, writeFile, type FileHandle } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test, { type TestContext } from "node:test";
import { ArtifactFileError, openArtifactFile } from "./artifact-file.js";

async function fixture(t: TestContext) {
  const root = await realpath(await mkdtemp(join(tmpdir(), "artifact-policy-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const filesRoot = join(root, "files");
  const temporaryRoot = join(root, "temp");
  const outside = join(root, "outside");
  await Promise.all([filesRoot, temporaryRoot, outside].map((path) => mkdir(path)));
  const report = join(temporaryRoot, "报告.html");
  await writeFile(report, "temporary report");
  await writeFile(join(filesRoot, "report.txt"), "existing report");
  await writeFile(join(outside, "报告.html"), "outside report");
  return { root, filesRoot, temporaryRoot, outside, report, options: { filesRoot, temporaryRoots: [temporaryRoot] } };
}

function refused(status = 403, reason = "forbidden") {
  return (error: unknown) => error instanceof ArtifactFileError && error.status === status && error.reason === reason;
}

test("temporary artifacts and their canonical root alias retain exact bytes", async (t) => {
  const f = await fixture(t);
  const alias = join(f.root, "temp-alias");
  await symlink(f.temporaryRoot, alias);
  for (const path of [f.report, join(alias, "报告.html")]) {
    const file = await openArtifactFile(path, { ...f.options, temporaryRoots: [alias] });
    try {
      assert.equal(file.canonicalPath, f.report);
      assert.equal((await file.handle.readFile()).toString(), "temporary report");
    } finally { await file.handle.close(); }
  }
});

test("FILES_ROOT keeps its original symlink boundary and works without a known uid", async (t) => {
  const f = await fixture(t);
  const report = join(f.filesRoot, "report.txt");
  await symlink(report, join(f.filesRoot, "inside.txt"));
  await symlink(f.report, join(f.filesRoot, "escape.html"));
  const file = await openArtifactFile(join(f.filesRoot, "inside.txt"), { ...f.options, userId: null });
  try { assert.equal((await file.handle.readFile()).toString(), "existing report"); }
  finally { await file.handle.close(); }
  await assert.rejects(openArtifactFile(join(f.filesRoot, "escape.html"), f.options), refused());
});

test("temporary grants reject traversal, sibling prefixes and links outside their own root", async (t) => {
  const f = await fixture(t);
  await symlink(join(f.outside, "报告.html"), join(f.temporaryRoot, "escape.html"));
  await symlink(join(f.filesRoot, "report.txt"), join(f.temporaryRoot, "home.txt"));
  await symlink(f.outside, join(f.temporaryRoot, "directory-link"));
  await mkdir(`${f.temporaryRoot}-sibling`);
  for (const path of [join(f.outside, "报告.html"), `${f.temporaryRoot}-sibling/file`,
    join(f.temporaryRoot, "escape.html"), join(f.temporaryRoot, "home.txt"),
    join(f.temporaryRoot, "directory-link", "报告.html")]) {
    await assert.rejects(openArtifactFile(path, f.options), refused());
  }
  for (const path of ["relative.html", `${f.temporaryRoot}/../outside/报告.html`, `${f.report}\0`, `${f.temporaryRoot}/..\\outside/file`]) {
    await assert.rejects(openArtifactFile(path, f.options), refused(400, "invalid_path"));
  }
});

test("temporary files require a known matching owner and cannot be hard links", async (t) => {
  const f = await fixture(t);
  await assert.rejects(openArtifactFile(f.report, { ...f.options, userId: null }), refused());
  // Simulate a different owner's file without requiring privileged chown in CI.
  await assert.rejects(openArtifactFile(f.report, {
    ...f.options, fileSystem: { realpath, open, lstat: async (path) => {
      const metadata = await lstat(path);
      if (path === f.report) metadata.uid++;
      return metadata;
    } },
  }), refused());
  await link(join(f.outside, "报告.html"), join(f.temporaryRoot, "hard-link.html"));
  await assert.rejects(openArtifactFile(join(f.temporaryRoot, "hard-link.html"), f.options), refused());
});

test("another user cannot replace an ancestor of an allowed temporary artifact", async (t) => {
  const f = await fixture(t);
  await chmod(f.temporaryRoot, 0o777);
  await assert.rejects(openArtifactFile(f.report, f.options), refused());
  await chmod(f.temporaryRoot, 0o700);
  await mkdir(join(f.temporaryRoot, "shared"), { mode: 0o777 });
  await chmod(join(f.temporaryRoot, "shared"), 0o777);
  const path = join(f.temporaryRoot, "shared", "report.html");
  await writeFile(path, "report");
  await assert.rejects(openArtifactFile(path, f.options), refused());
});

test("missing and non-regular temporary artifacts keep existing error categories", async (t) => {
  const f = await fixture(t);
  await assert.rejects(openArtifactFile(join(f.temporaryRoot, "gone.html"), f.options), { code: "ENOENT" });
  await assert.rejects(openArtifactFile(f.temporaryRoot, f.options), refused(400, "invalid_file"));
  await assert.rejects(openArtifactFile(f.report, { ...f.options, temporaryRoots: ["/", join(f.root, "missing-root")] }), refused());
  const alias = join(f.root, "whole-disk");
  await symlink("/", alias);
  await assert.rejects(openArtifactFile(f.report, { ...f.options, temporaryRoots: [alias] }), refused());
});

test("a replacement between validation and open is refused and its handle is closed", async (t) => {
  const f = await fixture(t);
  let opened: FileHandle | undefined;
  await assert.rejects(openArtifactFile(f.report, {
    ...f.options, fileSystem: { lstat, realpath, open: async (path, flags) => {
      await rename(path, `${path}.original`);
      await writeFile(path, "replacement");
      opened = await open(path, flags);
      return opened;
    } },
  }), refused());
  assert.ok(opened);
  await assert.rejects(opened.stat(), { code: "EBADF" });
});

test("a directory retargeted after open is refused even if the original handle is still valid", async (t) => {
  const f = await fixture(t);
  const nested = join(f.temporaryRoot, "nested");
  await mkdir(nested);
  const report = join(nested, "报告.html");
  await writeFile(report, "original");
  let opened: FileHandle | undefined;
  await assert.rejects(openArtifactFile(report, {
    ...f.options, fileSystem: { lstat, realpath, open: async (path, flags) => {
      opened = await open(path, flags);
      await rename(nested, `${nested}.original`);
      await symlink(f.outside, nested);
      return opened;
    } },
  }), refused());
  assert.ok(opened);
  await assert.rejects(opened.stat(), { code: "EBADF" });
});
