import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, mkdir, rm, writeFile, chmod, symlink, realpath } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { readHermesInstallationIdentity } from "./hermes-installation-identity.js";
test("identity invalidates unchanged version after code or dependency generation changes without importing Hermes", async () => {
  const home = await mkdtemp(join(tmpdir(), "hg199-identity-"));
  try {
    let root = join(home, ".hermes/hermes-agent");
    await mkdir(join(root, ".git/refs/heads"), { recursive: true });
    root = await realpath(root);
    await writeFile(join(root, ".git/HEAD"), "ref: refs/heads/main\n");
    await writeFile(join(root, ".git/refs/heads/main"), "a".repeat(40));
    const first = await readHermesInstallationIdentity(home);
    assert.match(first!, /^[0-9a-f]{64}$/);
    await writeFile(join(root, ".git/refs/heads/main"), "b".repeat(40));
    const next = await readHermesInstallationIdentity(home);
    assert.notEqual(next, first);
    const key = createHash("sha256").update(root).digest("hex").slice(0, 16);
    const state = join(home, ".hermes/installs", key);
    await mkdir(state, { recursive: true });
    await writeFile(join(state, "facts.json"), JSON.stringify({ packages: { venv: { environment: "g1" } } }));
    const generation = await readHermesInstallationIdentity(home);
    assert.notEqual(generation, next);
    await writeFile(join(state, "facts.json"), JSON.stringify({ packages: { venv: { environment: "g2" } } }));
    assert.notEqual(await readHermesInstallationIdentity(home), generation);
    await chmod(join(root, ".git/HEAD"), 0o666);
    assert.equal(await readHermesInstallationIdentity(home), undefined);
  } finally { await rm(home, { recursive: true, force: true }); }
});
test("unknown or redirected checkout is never assigned a standard installation identity", async () => {
  const home = await mkdtemp(join(tmpdir(), "hg199-identity-"));
  try {
    assert.equal(await readHermesInstallationIdentity(home), undefined);
    await mkdir(join(home, ".hermes"));
    await mkdir(join(home, "external/.git"), { recursive: true });
    await writeFile(join(home, "external/.git/HEAD"), "a".repeat(40));
    await symlink(join(home, "external"), join(home, ".hermes/hermes-agent"));
    assert.equal(await readHermesInstallationIdentity(home), undefined);
  } finally { await rm(home, { recursive: true, force: true }); }
});
