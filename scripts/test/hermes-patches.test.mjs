import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { applyHermesPatches, loadHermesPatches } from "../lib/hermes-patches.mjs";

const HEADER = [
  "Upstream-Issue: https://github.com/NousResearch/hermes-agent/issues/116510",
  "Why-upstream-will-not: inlining is free between local processes and not free over a relay",
  "Read-side-only: yes",
  "Added: 2026-09-20",
  "",
].join("\n");

test("an empty or absent directory is a valid patch set", async (t) => {
  // Verbatim upstream has to stay expressible, or every build becomes a patched build by default.
  const root = await makeRoot(t);
  assert.deepEqual(await loadHermesPatches(path.join(root, "nothing-here")), []);
  await mkdir(path.join(root, "patches"));
  assert.deepEqual(await loadHermesPatches(path.join(root, "patches")), []);
});

test("patches are ordered by their number, not by chance", async (t) => {
  const directory = await makePatchDirectory(t, {
    "020-second.patch": HEADER + patchFor("b.py", "b", "B"),
    "010-first.patch": HEADER + patchFor("a.py", "a", "A"),
  });
  const patches = await loadHermesPatches(directory);

  assert.deepEqual(patches.map((patch) => patch.name), ["010-first.patch", "020-second.patch"]);
});

test("a patch without the four headers is refused", async (t) => {
  for (const missing of ["Upstream-Issue", "Why-upstream-will-not", "Read-side-only", "Added"]) {
    const header = HEADER.split("\n").filter((line) => !line.startsWith(`${missing}:`)).join("\n");
    const directory = await makePatchDirectory(t, {
      "010-x.patch": header + patchFor("a.py", "a", "A"),
    });
    await assert.rejects(
      () => loadHermesPatches(directory),
      (error) => error.technicalCause === "hermes_patch_header_missing",
      `missing ${missing} should be refused`,
    );
  }
});

test("Why-upstream-will-not is required, because it is what makes a patch removable", async (t) => {
  // Not a formatting rule. A patch that cannot say why upstream would not do this itself is a patch
  // waiting on an issue, and it must be deleted when the issue lands rather than carried forever.
  const directory = await makePatchDirectory(t, {
    "010-x.patch": HEADER.replace(/Why-upstream-will-not:.*\n/, "") + patchFor("a.py", "a", "A"),
  });

  await assert.rejects(
    () => loadHermesPatches(directory),
    (error) => error.technicalCause === "hermes_patch_header_missing",
  );
});

test("a patch that touches a schema-owning file is refused", async (t) => {
  // The rule the whole arrangement rests on: the owner's own Hermes reads the same database and
  // has to keep understanding every row in it.
  const directory = await makePatchDirectory(t, {
    "010-schema.patch": HEADER + patchFor("hermes_state_common.py", "x", "y"),
  });

  await assert.rejects(
    () => loadHermesPatches(directory),
    (error) => error.technicalCause === "hermes_patch_touches_schema",
  );
});

test("a patch whose own lines carry a write statement is refused", async (t) => {
  for (const statement of [
    "    cursor.execute(\"INSERT INTO messages (id) VALUES (?)\", (1,))",
    "    cursor.execute(\"UPDATE messages SET content = ?\", (x,))",
    "    cursor.execute(\"DELETE FROM messages WHERE id = ?\", (1,))",
    "    cursor.execute(\"ALTER TABLE messages ADD COLUMN x BLOB\")",
  ]) {
    const directory = await makePatchDirectory(t, {
      "010-write.patch": HEADER + patchFor("reader.py", "old_line", statement.trim()),
    });
    await assert.rejects(
      () => loadHermesPatches(directory),
      (error) => error.technicalCause === "hermes_patch_touches_writes",
      statement,
    );
  }
});

test("a read-side change to a file that also contains writes is allowed", async (t) => {
  // The founding patch is exactly this shape: hermes_state_messages.py reads and writes, and
  // narrowing one of its SELECTs is legitimate. Banning the file would ban the patch set.
  const directory = await makePatchDirectory(t, {
    "010-select.patch": HEADER + patchFor(
      "hermes_state_messages.py",
      '    sql = "SELECT * FROM messages WHERE session_id = ?"',
      '    sql = "SELECT id, role, content FROM messages WHERE session_id = ?"',
    ),
  });

  const patches = await loadHermesPatches(directory);
  assert.equal(patches.length, 1);
  assert.equal(patches[0].upstreamIssue, "https://github.com/NousResearch/hermes-agent/issues/116510");
});

test("applying records each patch's hash, so provenance is checkable", async (t) => {
  const root = await makeRoot(t);
  const app = path.join(root, "app");
  await mkdir(app, { recursive: true });
  await writeFile(path.join(app, "reader.py"), "old_line\n");
  const directory = await makePatchDirectory(t, {
    "010-read.patch": HEADER + patchFor("reader.py", "old_line", "new_line"),
  });

  const patches = await loadHermesPatches(directory);
  const applied = applyHermesPatches(patches, app);

  assert.equal(await readFile(path.join(app, "reader.py"), "utf8"), "new_line\n");
  assert.equal(applied.length, 1);
  assert.match(applied[0].sha256, /^[0-9a-f]{64}$/);
  assert.equal(applied[0].name, "010-read.patch");
});

test("a patch that no longer applies stops the build rather than being skipped", async (t) => {
  // Upstream moved. Resolving that here would resolve it invisibly; the adoption gate exists so
  // each conflicted patch is decided deliberately.
  const root = await makeRoot(t);
  const app = path.join(root, "app");
  await mkdir(app, { recursive: true });
  await writeFile(path.join(app, "reader.py"), "upstream_changed_this\n");
  const directory = await makePatchDirectory(t, {
    "010-read.patch": HEADER + patchFor("reader.py", "old_line", "new_line"),
  });

  const patches = await loadHermesPatches(directory);
  assert.throws(
    () => applyHermesPatches(patches, app),
    (error) => error.technicalCause === "hermes_patch_does_not_apply",
  );
  // The tree is left as upstream had it, not half-patched.
  assert.equal(await readFile(path.join(app, "reader.py"), "utf8"), "upstream_changed_this\n");
});

test("the repository's own patch directory passes its own rules", async () => {
  // Whatever is checked in has to satisfy the loader, or the packer fails at release time.
  const directory = new URL("../../desktop/hermes-patches", import.meta.url).pathname;
  const patches = await loadHermesPatches(directory);
  for (const patch of patches) {
    assert.match(patch.upstreamIssue, /^https:\/\/github\.com\/NousResearch\/hermes-agent\//);
    assert.ok(patch.whyUpstreamWillNot.length > 20, `${patch.name} needs a real reason`);
  }
});

function patchFor(file, oldLine, newLine) {
  return [
    `diff --git a/${file} b/${file}`,
    `--- a/${file}`,
    `+++ b/${file}`,
    "@@ -1 +1 @@",
    `-${oldLine}`,
    `+${newLine}`,
    "",
  ].join("\n");
}

async function makeRoot(t) {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-patch-test-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}

async function makePatchDirectory(t, files) {
  const root = await makeRoot(t);
  const directory = path.join(root, "patches");
  await mkdir(directory, { recursive: true });
  for (const [name, body] of Object.entries(files)) {
    // No auto-prepending: a test that deliberately drops a header must get a file without it.
    await writeFile(path.join(directory, name), body);
  }
  return directory;
}

test("git is available, or these tests prove nothing", () => {
  assert.equal(spawnSync("git", ["--version"]).status, 0);
});
