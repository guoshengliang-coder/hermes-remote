import assert from "node:assert/strict";
import { chmod, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { loadHermesSessionToken } from "./hermes-session-token.js";

const TOKEN = "A".repeat(43);

test("loads a private installation-local Hermes session token", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-session-token-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "token");
  await writeFile(file, TOKEN, { mode: 0o600 });

  assert.equal(loadHermesSessionToken({ file }), TOKEN);
  assert.equal(loadHermesSessionToken({ inline: "legacy-token" }), "legacy-token");
  assert.equal(loadHermesSessionToken({}), undefined);
});

test("rejects ambiguous, linked, permissive, and malformed token files", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-session-token-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const file = path.join(root, "token");
  const linked = path.join(root, "linked");
  await writeFile(file, TOKEN, { mode: 0o600 });
  await symlink(file, linked);

  assert.throws(() => loadHermesSessionToken({ inline: "legacy-token", file }));
  assert.throws(() => loadHermesSessionToken({ file: linked }));
  await chmod(file, 0o644);
  assert.throws(() => loadHermesSessionToken({ file }));
  await chmod(file, 0o600);
  await writeFile(file, `${TOKEN}\n`, { mode: 0o600 });
  assert.throws(() => loadHermesSessionToken({ file }));
  assert.throws(() => loadHermesSessionToken({ file: "relative-token" }));
});
