import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

test("protected publisher rollback swaps both root-owned index paths through sudo", async (t) => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "desktop-publish-protected-test-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const releaseRoot = path.join(root, "releases");
  const componentRoot = path.join(root, "components");
  const bin = path.join(root, "bin");
  await Promise.all([mkdir(releaseRoot), mkdir(componentRoot), mkdir(bin)]);
  for (const [directory, current, previous] of [
    [releaseRoot, "release-current", "release-previous"],
    [componentRoot, "component-current", "component-previous"],
  ]) {
    await writeFile(path.join(directory, "index.json"), current);
    await writeFile(path.join(directory, "index.previous.json"), previous);
  }
  await writeFile(path.join(bin, "ssh"), `#!/bin/sh
[ "$1" = "release@example.test" ] && [ "$2" = sudo ] && [ "$3" = -n ] && [ "$4" = bash ] && [ "$5" = -s ] || exit 70
exec /bin/bash -s
`, { mode: 0o700 });
  await writeFile(path.join(bin, "curl"), `#!/bin/sh
out=
url=
while [ "$#" -gt 0 ]; do
  case "$1" in -o) out="$2"; shift 2 ;; https://*) url="$1"; shift ;; *) shift ;; esac
done
case "$url" in
  https://example.test/desktop/releases/index.json) cp "$TEST_RELEASE_ROOT/index.json" "$out" ;;
  https://example.test/desktop/components/index.json) cp "$TEST_COMPONENT_ROOT/index.json" "$out" ;;
  *) exit 71 ;;
esac
`, { mode: 0o700 });
  const result = spawnSync("scripts/publish-desktop-release.sh", ["--rollback"], {
    cwd: process.cwd(), encoding: "utf8", shell: false,
    env: {
      ...process.env, PATH: `${bin}:${process.env.PATH}`,
      DESKTOP_RELEASE_SSH_TARGET: "release@example.test",
      DESKTOP_RELEASE_PUBLIC_ORIGIN: "https://example.test",
      DESKTOP_RELEASE_PRESTAGED_PROTECTED: "1",
      DESKTOP_RELEASE_REMOTE_RELEASE_ROOT: releaseRoot,
      DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT: componentRoot,
      TEST_RELEASE_ROOT: releaseRoot,
      TEST_COMPONENT_ROOT: componentRoot,
    },
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /DESKTOP_RELEASE_ROLLBACK_OK/);
  assert.equal(await readFile(path.join(releaseRoot, "index.json"), "utf8"), "release-previous");
  assert.equal(await readFile(path.join(componentRoot, "index.json"), "utf8"), "component-previous");
  assert.equal(await readFile(path.join(releaseRoot, "index.previous.json"), "utf8"), "release-current");
  assert.equal(await readFile(path.join(componentRoot, "index.previous.json"), "utf8"), "component-current");
});
