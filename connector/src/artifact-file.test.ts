import assert from "node:assert/strict";
import { chmod, link, lstat, mkdir, mkdtemp, open, realpath, rename, rm, stat, symlink, writeFile, type FileHandle } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test, { type TestContext } from "node:test";
import { ArtifactFileError, openArtifactFile, type ExternalRootGrant } from "./artifact-file.js";

async function fixture(t: TestContext) {
  const root = await realpath(await mkdtemp(join(tmpdir(), "artifact-policy-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const filesRoot = join(root, "files");
  const temporaryRoot = join(root, "temp");
  const outside = join(root, "outside");
  const externalRoot = join(root, "volume");
  await Promise.all([filesRoot, temporaryRoot, outside, externalRoot].map((path) => mkdir(path)));
  const report = join(temporaryRoot, "报告.html");
  const footage = join(externalRoot, "成片.mp4");
  await writeFile(report, "temporary report");
  await writeFile(join(filesRoot, "report.txt"), "existing report");
  await writeFile(join(outside, "报告.html"), "outside report");
  await writeFile(footage, "external footage");
  return {
    root, filesRoot, temporaryRoot, outside, externalRoot, footage, report,
    options: { filesRoot, temporaryRoots: [temporaryRoot] },
  };
}

async function externalGrant(lexical: string): Promise<ExternalRootGrant> {
  const canonical = await realpath(lexical);
  const metadata = await stat(canonical);
  return { lexical, canonical, dev: metadata.dev, ino: metadata.ino };
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
    ...f.options, fileSystem: { realpath, stat, open, lstat: async (path) => {
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
    ...f.options, fileSystem: { lstat, realpath, stat, open: async (path, flags) => {
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
    ...f.options, fileSystem: { lstat, realpath, stat, open: async (path, flags) => {
      opened = await open(path, flags);
      await rename(nested, `${nested}.original`);
      await symlink(f.outside, nested);
      return opened;
    } },
  }), refused());
  assert.ok(opened);
  await assert.rejects(opened.stat(), { code: "EBADF" });
});

test("authorized external read roots serve their files and lexical aliases (HG-245)", async (t) => {
  const f = await fixture(t);
  // Mirror the macOS /tmp vs /private/tmp shape: the configured lexical path is a link whose
  // canonical form differs, and both spellings must resolve onto the same pinned volume.
  const alias = join(f.root, "volume-alias");
  await symlink(f.externalRoot, alias);
  const canonical = await realpath(f.externalRoot);
  const metadata = await stat(canonical);
  const grant: ExternalRootGrant = { lexical: alias, canonical, dev: metadata.dev, ino: metadata.ino };
  for (const path of [f.footage, join(alias, "成片.mp4")]) {
    const file = await openArtifactFile(path, { ...f.options, temporaryRoots: [], externalRoots: [grant] });
    try {
      assert.equal(file.canonicalPath, await realpath(f.footage));
      assert.equal((await file.handle.readFile()).toString(), "external footage");
    } finally { await file.handle.close(); }
  }
});

test("external read roots reject traversal, sibling prefixes and links outside their own grant", async (t) => {
  const f = await fixture(t);
  const grant = await externalGrant(f.externalRoot);
  await symlink(join(f.outside, "报告.html"), join(f.externalRoot, "escape.html"));
  // A link onto another authorized root crosses the grant boundary all the same.
  await symlink(join(f.filesRoot, "report.txt"), join(f.externalRoot, "home.txt"));
  await symlink(f.outside, join(f.externalRoot, "dir-link"));
  await mkdir(`${f.externalRoot}-sibling`);
  const options = { ...f.options, temporaryRoots: [], externalRoots: [grant] };
  for (const path of [
    join(f.outside, "报告.html"),
    `${f.externalRoot}-sibling/成片.mp4`,
    join(f.externalRoot, "escape.html"),
    join(f.externalRoot, "home.txt"),
    join(f.externalRoot, "dir-link", "报告.html"),
  ]) {
    await assert.rejects(openArtifactFile(path, options), refused());
  }
  for (const path of [`${f.externalRoot}/../outside/报告.html`, `${f.footage}\0`, "成片.mp4"]) {
    await assert.rejects(openArtifactFile(path, options), refused(400, "invalid_path"));
  }
});

test("a volume swapped onto the mount point is refused via the startup dev/ino baseline", async (t) => {
  const f = await fixture(t);
  const grant = await externalGrant(f.externalRoot);
  for (const mutated of [{ dev: grant.dev + 1 }, { ino: grant.ino + 1 }, { dev: grant.dev + 1, ino: grant.ino + 1 }]) {
    await assert.rejects(openArtifactFile(f.footage, {
      ...f.options, temporaryRoots: [], externalRoots: [grant],
      fileSystem: { lstat, realpath, open, stat: async (path) => {
        const metadata = await stat(path);
        if (path === grant.canonical) return Object.assign(metadata, mutated);
        return metadata;
      } },
    }), refused());
  }
  // A non-directory occupying the mount point is equally refused.
  await assert.rejects(openArtifactFile(f.footage, {
    ...f.options, temporaryRoots: [], externalRoots: [grant],
    fileSystem: {
      lstat, realpath, open,
      stat: async (path) => Object.assign(await stat(path), { mode: 0o100644 }),
    },
  }), refused());
});

test("an unmounted external root reports not found and stays separable from the home path", async (t) => {
  const f = await fixture(t);
  const grant = await externalGrant(f.externalRoot);
  await assert.rejects(openArtifactFile(f.footage, {
    ...f.options, temporaryRoots: [], externalRoots: [grant],
    fileSystem: {
      lstat, realpath, open,
      stat: async (path) => {
        if (path === grant.canonical) throw Object.assign(new Error("unmounted"), { code: "ENOENT" });
        return stat(path);
      },
    },
  }), refused(404, "not_found"));
  // A file missing from an otherwise online volume keeps the raw ENOENT category.
  await assert.rejects(openArtifactFile(join(f.externalRoot, "missing.mp4"), {
    ...f.options, temporaryRoots: [], externalRoots: [grant],
  }), { code: "ENOENT" });
  // An inaccessible root is forbidden rather than not-found: the volume is there but not granted.
  await assert.rejects(openArtifactFile(f.footage, {
    ...f.options, temporaryRoots: [], externalRoots: [grant],
    fileSystem: {
      lstat, realpath, open,
      stat: async (path) => {
        if (path === grant.canonical) throw Object.assign(new Error("locked"), { code: "EACCES" });
        return stat(path);
      },
    },
  }), refused());
});

test("external grants never widen or shift the FILES_ROOT boundary", async (t) => {
  const f = await fixture(t);
  const grant = await externalGrant(f.externalRoot);
  // The FILES_ROOT escape rule keeps rejecting a link whose target sits in an authorized
  // external root: crossing out of FILES_ROOT stays forbidden no matter where it lands.
  await symlink(f.footage, join(f.filesRoot, "to-volume.txt"));
  const options = { ...f.options, temporaryRoots: [], externalRoots: [grant] };
  await assert.rejects(openArtifactFile(join(f.filesRoot, "to-volume.txt"), options), refused());
  const file = await openArtifactFile(join(f.filesRoot, "report.txt"), options);
  try { assert.equal((await file.handle.readFile()).toString(), "existing report"); }
  finally { await file.handle.close(); }
});

test("a file opened on a different volume than its grant is refused as the final word", async (t) => {
  const f = await fixture(t);
  // Simulate the mount-swap race end-state: every earlier check is fooled into agreeing with
  // the pinned baseline, and the file-level lstat/open pair is self-consistent — only the
  // final "opened file must live on the granted volume" dev comparison can refuse it.
  const canonical = await realpath(f.externalRoot);
  const metadata = await stat(canonical);
  const grant: ExternalRootGrant = {
    lexical: f.externalRoot, canonical,
    dev: metadata.dev + 12345, ino: metadata.ino,
  };
  await assert.rejects(openArtifactFile(f.footage, {
    ...f.options, temporaryRoots: [], externalRoots: [grant],
    fileSystem: { lstat, realpath, open, stat: async (path) => {
      // The root baseline check is spoofed into reporting the pinned (foreign) identity.
      if (path === canonical) return Object.assign(await stat(path), { dev: grant.dev, ino: grant.ino });
      return stat(path);
    } },
  }), refused());
  // Sanity: with an honest baseline the same file opens, so the refusal above is the dev rule.
  const honest = await externalGrant(f.externalRoot);
  const file = await openArtifactFile(f.footage, { ...f.options, temporaryRoots: [], externalRoots: [honest] });
  try { assert.equal((await file.handle.readFile()).toString(), "external footage"); }
  finally { await file.handle.close(); }
});

test("paths outside every root still fall through to the temporary-root policy", async (t) => {
  const f = await fixture(t);
  const grant = await externalGrant(f.externalRoot);
  // The temporary root keeps working alongside external grants.
  const file = await openArtifactFile(f.report, { ...f.options, externalRoots: [grant] });
  try { assert.equal((await file.handle.readFile()).toString(), "temporary report"); }
  finally { await file.handle.close(); }
  // And a path in neither stays forbidden.
  await assert.rejects(openArtifactFile(join(f.outside, "报告.html"), {
    ...f.options, temporaryRoots: [], externalRoots: [grant],
  }), refused());
});

test("a grant resolving to the filesystem root is refused defensively", async (t) => {
  const f = await fixture(t);
  const metadata = await stat("/");
  const wholeDisk: ExternalRootGrant = {
    lexical: "/", canonical: "/", dev: metadata.dev, ino: metadata.ino,
  };
  await assert.rejects(openArtifactFile(f.footage, {
    ...f.options, temporaryRoots: [], externalRoots: [wholeDisk],
  }), refused());
});
