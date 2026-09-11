import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  chmod,
  mkdtemp,
  mkdir,
  readFile,
  readdir,
  realpath,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import {
  DesktopManagedReleaseError,
  packageDesktopManagedRelease,
  verifyDesktopManagedRelease,
} from "../lib/desktop-managed-release.mjs";

test("publisher creates a signed two-artifact release that verifies byte-for-byte", async (t) => {
  const fixture = await makeFixture(t);
  const result = await packageDesktopManagedRelease({
    configPath: fixture.configPath,
    outputDirectory: fixture.output,
  });

  assert.equal(result.releaseVersion, "0.3.0");
  assert.equal(result.keyId, "desktop-internal-test-a");
  assert.match(result.publicKey, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(result.artifacts.length, 2);
  assert.deepEqual((await readdir(fixture.output)).sort(), [
    "Hermes-Connector-0.1.2-arm64.tar.gz",
    "Hermes-Desktop-0.3.0-arm64.manifest.json",
    "Hermes-Server-0.21.0-arm64.tar.gz",
  ]);

  const verified = await verifyDesktopManagedRelease({
    manifestPath: result.manifestPath,
    artifactDirectory: fixture.output,
    expectedKeyId: result.keyId,
    publicKey: result.publicKey,
    expectedOrigin: "https://mrlgs.net",
    expectedChannel: "internal",
    expectedArchitecture: "arm64",
    now: fixture.now,
  });
  assert.equal(verified.artifacts[0].component, "hermes_server");
  assert.equal(verified.artifacts[1].component, "connector");
  assert.equal(verified.artifacts[0].downloadURL,
    "https://mrlgs.net/desktop/releases/0.3.0/Hermes-Server-0.21.0-arm64.tar.gz");
});

test("publisher refuses weak private-key permissions without leaving partial output", async (t) => {
  const fixture = await makeFixture(t);
  await chmod(fixture.keyPath, 0o644);
  await assert.rejects(
    packageDesktopManagedRelease({ configPath: fixture.configPath, outputDirectory: fixture.output }),
    isCause("signing_key_permissions_invalid"),
  );
  assert.deepEqual(await readdir(fixture.output), []);
});

test("publisher refuses symlinked inputs and cleans an earlier copied artifact", async (t) => {
  const fixture = await makeFixture(t);
  const linked = path.join(fixture.root, "linked-connector.tar.gz");
  await symlink(fixture.connectorArchive, linked);
  const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
  config.artifacts[1].source = linked;
  await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`, { mode: 0o600 });

  await assert.rejects(
    packageDesktopManagedRelease({ configPath: fixture.configPath, outputDirectory: fixture.output }),
    isCause("input_file_invalid"),
  );
  assert.deepEqual(await readdir(fixture.output), []);
});

test("publisher rejects unknown fields, unsafe origin, and overlong lifetime", async (t) => {
  const mutations = [
    (config) => { config.futureUnsafeAction = true; },
    (config) => { config.artifactOrigin = "http://mrlgs.net"; },
    (config) => { config.expiresAt = iso(new Date(Date.parse(config.createdAt) + 31 * 24 * 60 * 60 * 1000)); },
  ];
  for (const mutate of mutations) {
    const fixture = await makeFixture(t);
    const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
    mutate(config);
    await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`, { mode: 0o600 });
    await assert.rejects(
      packageDesktopManagedRelease({ configPath: fixture.configPath, outputDirectory: fixture.output }),
      (error) => error instanceof DesktopManagedReleaseError,
    );
  }
});

test("publisher never overwrites an existing target", async (t) => {
  const fixture = await makeFixture(t);
  const target = path.join(fixture.output, "Hermes-Server-0.21.0-arm64.tar.gz");
  await writeFile(target, "owner data", { mode: 0o600 });
  await assert.rejects(
    packageDesktopManagedRelease({ configPath: fixture.configPath, outputDirectory: fixture.output }),
    isCause("output_target_exists"),
  );
  assert.equal(await readFile(target, "utf8"), "owner data");
});

test("verification rejects a modified archive after signature verification", async (t) => {
  const fixture = await makeFixture(t);
  const result = await packageDesktopManagedRelease({ configPath: fixture.configPath, outputDirectory: fixture.output });
  await writeFile(result.artifacts[1].path, "tampered");
  await assert.rejects(
    verifyDesktopManagedRelease({
      manifestPath: result.manifestPath,
      artifactDirectory: fixture.output,
      expectedKeyId: result.keyId,
      publicKey: result.publicKey,
      expectedOrigin: "https://mrlgs.net",
      expectedChannel: "internal",
      expectedArchitecture: "arm64",
      now: fixture.now,
    }),
    isCause("artifact_integrity_invalid"),
  );
});

test("CLI reports HR-RELEASE-004 without disclosing the signing-key path", async (t) => {
  const fixture = await makeFixture(t);
  await chmod(fixture.keyPath, 0o644);
  const result = spawnSync(process.execPath, [
    "scripts/package-desktop-managed-release.mjs",
    "--config", fixture.configPath,
    "--output", fixture.output,
  ], { cwd: process.cwd(), encoding: "utf8", shell: false });
  assert.equal(result.status, 1);
  assert.equal(result.stdout, "");
  const diagnostic = JSON.parse(result.stderr.trim());
  assert.equal(diagnostic.code, "HR-RELEASE-004");
  assert.equal(diagnostic.stage, "desktop_managed_release_package");
  assert.equal(result.stderr.includes(fixture.keyPath), false);
  assert.equal(diagnostic.technicalCause, "signing_key_permissions_invalid");
});

async function makeFixture(t) {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-desktop-release-test-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const input = path.join(root, "input");
  const output = path.join(root, "output");
  await mkdir(input, { mode: 0o700 });
  await mkdir(output, { mode: 0o700 });
  const hermesArchive = await makeArchive(root, "hermes", "bin/hermes-server", "#!/bin/sh\necho hermes\n");
  const connectorArchive = await makeArchive(root, "connector", "bin/hermes-connector", "#!/bin/sh\necho connector\n");
  const { privateKey } = generateKeyPairSync("ed25519");
  const keyPath = path.join(root, "release-signing-key.pem");
  await writeFile(keyPath, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  const now = new Date(Math.floor(Date.now() / 1000) * 1000);
  const config = {
    schemaVersion: 1,
    releaseVersion: "0.3.0",
    channel: "internal",
    architecture: "arm64",
    minimumMacOS: "14.0",
    createdAt: iso(now),
    expiresAt: iso(new Date(now.valueOf() + 7 * 24 * 60 * 60 * 1000)),
    artifactOrigin: "https://mrlgs.net",
    artifactPathPrefix: "/desktop/releases/0.3.0",
    signing: { keyId: "desktop-internal-test-a", privateKeyFile: keyPath },
    artifacts: [
      { component: "hermes_server", version: "0.21.0", source: hermesArchive, entrypoint: "bin/hermes-server" },
      { component: "connector", version: "0.1.2", source: connectorArchive, entrypoint: "bin/hermes-connector" },
    ],
  };
  const configPath = path.join(root, "publisher.json");
  await writeFile(configPath, `${JSON.stringify(config)}\n`, { mode: 0o600 });
  return { root, output, keyPath, configPath, hermesArchive, connectorArchive, now };
}

async function makeArchive(root, name, entrypoint, content) {
  const source = path.join(root, `${name}-source`);
  const executable = path.join(source, entrypoint);
  await mkdir(path.dirname(executable), { recursive: true, mode: 0o700 });
  await writeFile(executable, content, { mode: 0o700 });
  const archive = path.join(root, `${name}.tar.gz`);
  const result = spawnSync("/usr/bin/tar", ["-czf", archive, "-C", source, "."], {
    encoding: "utf8", shell: false,
  });
  assert.equal(result.status, 0, result.stderr);
  return archive;
}

function iso(value) {
  return value.toISOString().replace(".000Z", "Z");
}

function isCause(value) {
  return (error) => error instanceof DesktopManagedReleaseError && error.technicalCause === value;
}
