import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash, createPublicKey, generateKeyPairSync, sign } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { verifyDesktopReleaseKeyContinuity } from "../lib/desktop-release-key-continuity.mjs";

test("official DMG verifier refuses incomplete or mismatched inputs before system tools run", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "desktop-official-release-test-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const dmg = path.join(root, "Hermes-Go-Desktop-0.2.27.dmg");
  await writeFile(dmg, "not-a-real-dmg");
  const verify = (args) => spawnSync("desktop/scripts/verify-official-dmg.sh", args, {
    cwd: process.cwd(), encoding: "utf8", shell: false,
  });
  assert.equal(verify([]).status, 64);
  const inputs = [
    dmg, "0.2.27", "30", "ABCDEF1234",
    "https://mrlgs.net/desktop/releases/index.json",
    "https://mrlgs.net/desktop/components/index.json",
    "desktop-internal-test", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  ];
  assert.equal(verify(["relative.dmg", ...inputs.slice(1)]).status, 64);
  assert.equal(verify([dmg, "0.2.26", ...inputs.slice(2)]).status, 65);
  assert.equal(verify([dmg, "0.2.27", "not-a-build", ...inputs.slice(3)]).status, 64);
});

test("official candidate workflow has no tag-triggered publish and requires notarized verification", async () => {
  const workflow = await readFile(".github/workflows/desktop-app-release.yml", "utf8");
  const connectorWorkflow = await readFile(".github/workflows/connector-release.yml", "utf8");
  const dmgBuild = await readFile("desktop/scripts/build-dmg.sh", "utf8");
  assert.match(workflow, /workflow_dispatch:/);
  assert.doesNotMatch(workflow, /tags:\s*\["desktop-v\*"\]/);
  assert.match(workflow, /environment: desktop-release/);
  assert.match(workflow, /xcrun notarytool submit/);
  assert.match(workflow, /xcrun stapler staple/);
  assert.match(workflow, /verify-official-dmg\.sh/);
  assert.match(workflow, /verify-desktop-release-key-continuity\.mjs/);
  assert.match(workflow, /if-no-files-found: error/);
  assert.match(dmgBuild, /Official DMG requires a Developer ID signing identity/);
  assert.match(dmgBuild, /Official DMG already exists; refusing to overwrite it/);
  assert.match(connectorWorkflow, /workflow_dispatch:/);
  assert.doesNotMatch(connectorWorkflow, /tags:\s*\["connector-v\*"\]/);
  assert.match(connectorWorkflow, /package-desktop-connector\.mjs/);
  assert.match(connectorWorkflow, /package-desktop-components-v2\.mjs/);
  assert.match(connectorWorkflow, /if-no-files-found: error/);
});

test("candidate key must verify both current public manifests and their index hashes", async () => {
  const { privateKey } = generateKeyPairSync("ed25519");
  const rawPublicKey = createPublicKey(privateKey).export({ format: "der", type: "spki" }).subarray(-32);
  const responses = new Map();
  for (const kind of ["releases", "components"]) {
    const signed = Buffer.from(JSON.stringify({
      releaseVersion: "0.4.3", channel: "internal", architecture: "arm64",
    }));
    const manifest = Buffer.from(`${JSON.stringify({
      algorithm: "Ed25519", keyId: "desktop-internal-test",
      payload: signed.toString("base64url"),
      signature: sign(null, signed, privateKey).toString("base64url"),
    })}\n`);
    const manifestURL = `https://example.test/desktop/${kind}/0.4.3/manifest.json`;
    responses.set(manifestURL, manifest);
    responses.set(`https://example.test/desktop/${kind}/index.json`, Buffer.from(JSON.stringify({
      releaseVersion: "0.4.3", channel: "internal", architecture: "arm64", manifestURL,
      manifestSizeBytes: manifest.length,
      manifestSHA256: createHash("sha256").update(manifest).digest("hex"),
    })));
  }
  const fetchImpl = async (url) => new Response(responses.get(url), { status: 200 });
  const args = {
    origin: "https://example.test", keyId: "desktop-internal-test",
    publicKey: rawPublicKey.toString("base64url"), fetchImpl,
  };
  assert.equal((await verifyDesktopReleaseKeyContinuity(args)).length, 2);
  await assert.rejects(
    verifyDesktopReleaseKeyContinuity({ ...args, publicKey: Buffer.alloc(32).toString("base64url") }),
    /desktop_manifest_signature_invalid/,
  );
  responses.set("https://example.test/desktop/components/0.4.3/manifest.json", Buffer.from("tampered"));
  await assert.rejects(verifyDesktopReleaseKeyContinuity(args), /desktop_manifest_index_mismatch/);
});
