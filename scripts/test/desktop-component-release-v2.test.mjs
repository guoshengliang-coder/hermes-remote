import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { spawnSync } from "node:child_process";
import { copyFile, mkdtemp, mkdir, readFile, readdir, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import {
  DesktopManagedReleaseError,
  desktopComponentArchiveContentIdentity,
  packageDesktopComponentReleaseV2,
  verifyDesktopComponentReleaseV2,
} from "../lib/desktop-managed-release.mjs";

test("Node archive identity matches the Swift component-store fixture", async (t) => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-component-hash-test-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const archive = await makeArchive(root, "known", "bin/python", "runtime");
  assert.equal(
    await desktopComponentArchiveContentIdentity({ archivePath: archive, entrypoint: "bin/python" }),
    "c3a207f2656841667491852cbf5e699077bdda037473f789ee8fad5a4c545415",
  );
});

test("v2 publisher signs component identities and independent verifier checks every archive", async (t) => {
  const fixture = await makeFixture(t);
  const result = await packageDesktopComponentReleaseV2({
    configPath: fixture.configPath,
    outputDirectory: fixture.output,
  });
  assert.equal(result.releaseVersion, "0.4.0");
  assert.equal(result.components.length, 2);
  assert.deepEqual((await readdir(fixture.output)).sort(), [
    "Hermes-Component-connector-0.3.0-arm64.tar.gz",
    "Hermes-Component-node_runtime-22.23.2-arm64.tar.gz",
    "Hermes-Desktop-Components-0.4.0-arm64.manifest.json",
  ]);
  const manifest = await verifyDesktopComponentReleaseV2({
    manifestPath: result.manifestPath,
    artifactDirectory: fixture.output,
    expectedKeyId: result.keyId,
    publicKey: result.publicKey,
    expectedOrigin: "https://mrlgs.net",
    expectedChannel: "internal",
    expectedArchitecture: "arm64",
    now: fixture.now,
  });
  assert.equal(manifest.schemaVersion, 2);
  assert.deepEqual(manifest.components[1].dependencies, [
    { kind: "node_runtime", contentSHA256: fixture.identities.node_runtime },
  ]);
});

test("v2 publisher rejects dependency identity mismatch, cycles, and unsigned fields", async (t) => {
  for (const mutate of [
    (config) => { config.components[1].dependencies[0].contentSHA256 = hash("f"); },
    (config) => {
      config.components[0].dependencies = [{
        kind: "connector", contentSHA256: config.components[1].contentSHA256,
      }];
    },
    (config) => {
      config.components[1].installPhase = "on_demand";
      config.components[1].requiredForBootstrap = false;
      config.components[1].onDemandTrigger = "connector";
    },
    (config) => { config.components[0].futureAction = true; },
  ]) {
    const fixture = await makeFixture(t);
    const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
    mutate(config);
    await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`, { mode: 0o600 });
    await assert.rejects(
      packageDesktopComponentReleaseV2({ configPath: fixture.configPath, outputDirectory: fixture.output }),
      (error) => error instanceof DesktopManagedReleaseError,
    );
    assert.deepEqual(await readdir(fixture.output), []);
  }
});

test("v2 independent verifier rejects a changed compressed artifact", async (t) => {
  const fixture = await makeFixture(t);
  const result = await packageDesktopComponentReleaseV2({
    configPath: fixture.configPath, outputDirectory: fixture.output,
  });
  await writeFile(result.components[0].path, "tampered");
  await assert.rejects(
    verifyDesktopComponentReleaseV2({
      manifestPath: result.manifestPath,
      artifactDirectory: fixture.output,
      expectedKeyId: result.keyId,
      publicKey: result.publicKey,
      expectedOrigin: "https://mrlgs.net",
      expectedChannel: "internal",
      expectedArchitecture: "arm64",
      now: fixture.now,
    }),
    (error) => error instanceof DesktopManagedReleaseError
      && error.technicalCause === "component_artifact_integrity_invalid",
  );
});

for (const fault of ["none", "artifact", "index", "index-http", "source", "version", "downgrade"]) {
  test(`component-only publisher verifies complete public bytes and preserves legacy channel (${fault})`, async (t) => {
    const fixture = await makeFixture(t);
    const signed = await packageDesktopComponentReleaseV2({ configPath: fixture.configPath, outputDirectory: fixture.output });
    const bin = path.join(fixture.root, "bin");
    const remote = path.join(fixture.root, "remote");
    const versionRoot = path.join(remote, "0.4.0");
    await mkdir(bin);
    await mkdir(versionRoot, { recursive: true });
    for (const name of await readdir(fixture.output)) await copyFile(path.join(fixture.output, name), path.join(versionRoot, name));
    const oldIndex = JSON.stringify({ releaseVersion: fault === "downgrade" ? "0.4.1" : "0.3.9",
      channel: "internal", architecture: "arm64" }) + "\n";
    await writeFile(path.join(remote, "index.json"), oldIndex);
    await writeFile(path.join(remote, "legacy-sentinel"), "must stay unchanged");
    await writeFile(path.join(bin, "git"), `#!/bin/sh
case "$1" in
  status) exit 0 ;;
  fetch) echo x >> "$TEST_ROOT/fetches"; exit 0 ;;
  rev-parse)
    [ "$2" = -q ] && exit 1
    if [ "$2" = origin/main ] && [ "$TEST_FAULT" = source ] && [ "$(wc -l < "$TEST_ROOT/fetches")" -gt 1 ]; then echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb; else echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; fi ;;
  ls-remote) exit 2 ;;
  tag|push) echo "$*" >> "$TEST_ROOT/tags" ;;
  *) exit 70 ;;
esac
`, { mode: 0o700 });
    await writeFile(path.join(bin, "ssh"), `#!/bin/sh
shift
if [ "$1" = sudo ]; then exec /bin/bash -s; fi
exec "$@"
`, { mode: 0o700 });
    await writeFile(path.join(bin, "scp"), '#!/bin/sh\nexec /bin/cp "$1" "${2#*:}"\n', { mode: 0o700 });
    await writeFile(path.join(bin, "curl"), `#!/usr/bin/env python3
import os, sys, pathlib
args=sys.argv[1:]
url=next(x for x in args if x.startswith('https://'))
out=pathlib.Path(args[args.index('-o')+1])
with open(os.environ['TEST_ROOT']+'/requests','a') as f: f.write(url+'\\n')
prefix='https://mrlgs.net/desktop/components/'
if not url.startswith(prefix): sys.exit(71)
data=(pathlib.Path(os.environ['TEST_REMOTE'])/url[len(prefix):]).read_bytes()
if os.environ['TEST_FAULT']=='artifact' and url.endswith('.tar.gz'): data=b'tampered'
if os.environ['TEST_FAULT']=='index' and url.endswith('/index.json') and b'"releaseVersion":"0.4.0"' in data: data=b'tampered'
if os.environ['TEST_FAULT']=='index-http' and url.endswith('/index.json') and b'"releaseVersion":"0.4.0"' in data: sys.exit(22)
out.write_bytes(data)
`, { mode: 0o700 });
    const result = spawnSync("scripts/publish-desktop-release.sh", [], {
      cwd: process.cwd(), encoding: "utf8", shell: false,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}`,
        TEST_ROOT: fixture.root, TEST_REMOTE: remote, TEST_FAULT: fault,
        DESKTOP_RELEASE_COMPONENTS_ONLY: "1", DESKTOP_RELEASE_PRESTAGED_PROTECTED: "1",
        DESKTOP_RELEASE_REMOTE_COMPONENT_ROOT: remote, DESKTOP_RELEASE_REMOTE_RELEASE_ROOT: "",
        DESKTOP_RELEASE_MANIFEST: "", DESKTOP_COMPONENT_MANIFEST: signed.manifestPath,
        DESKTOP_RELEASE_SSH_TARGET: "release@example.test", DESKTOP_RELEASE_PUBLIC_ORIGIN: "https://mrlgs.net",
        DESKTOP_RELEASE_VERSION: fault === "version" ? "0.4.1" : "0.4.0",
        DESKTOP_RELEASE_ARCHITECTURE: "arm64", DESKTOP_RELEASE_CHANNEL: "internal",
        DESKTOP_RELEASE_SIGNING_KEY_ID: signed.keyId, DESKTOP_RELEASE_SIGNING_PUBLIC_KEY: signed.publicKey,
      },
    });
    assert.equal(await readFile(path.join(remote, "legacy-sentinel"), "utf8"), "must stay unchanged");
    if (fault === "none") {
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /DESKTOP_COMPONENT_RELEASE_OK/);
      assert.equal(JSON.parse(await readFile(path.join(remote, "index.json"), "utf8")).releaseVersion, "0.4.0");
      assert.equal(await readFile(path.join(remote, "index.previous.json"), "utf8"), oldIndex);
      assert.match(await readFile(path.join(fixture.root, "tags"), "utf8"), /desktop-components-v0.4.0/);
      const requests = (await readFile(path.join(fixture.root, "requests"), "utf8")).trim().split("\n");
      assert.equal(requests.length, 5);
      assert.ok(requests.every((url) => url.includes("/desktop/components/")));
    } else {
      assert.notEqual(result.status, 0);
      assert.equal(await readFile(path.join(remote, "index.json"), "utf8"), oldIndex);
      assert.ok(!(await readdir(fixture.root)).includes("tags"));
    }
  });
}

async function makeFixture(t) {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-component-v2-test-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const output = path.join(root, "output");
  await mkdir(output, { mode: 0o700 });
  const archives = {
    node_runtime: await makeArchive(root, "node", "bin/node"),
    connector: await makeArchive(root, "connector", "bin/hermes-connector"),
  };
  const identities = {};
  for (const [kind, entrypoint] of Object.entries({
    node_runtime: "bin/node",
    connector: "bin/hermes-connector",
  })) {
    identities[kind] = await desktopComponentArchiveContentIdentity({
      archivePath: archives[kind], entrypoint,
    });
  }
  const { privateKey } = generateKeyPairSync("ed25519");
  const keyPath = path.join(root, "signing-key.pem");
  await writeFile(keyPath, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  const now = new Date(Math.floor(Date.now() / 1000) * 1000);
  const component = (kind, version, entrypoint, dependencies = []) => ({
    kind,
    version,
    architecture: "arm64",
    installPhase: "bootstrap",
    requiredForBootstrap: true,
    reuseContract: "exact_content",
    source: archives[kind],
    entrypoint,
    contentSHA256: identities[kind],
    dependencies,
  });
  const config = {
    schemaVersion: 2,
    releaseVersion: "0.4.0",
    channel: "internal",
    architecture: "arm64",
    minimumMacOS: "14.0",
    createdAt: iso(now),
    expiresAt: iso(new Date(now.valueOf() + 7 * 24 * 60 * 60 * 1000)),
    artifactOrigin: "https://mrlgs.net",
    artifactPathPrefix: "/desktop/components/0.4.0",
    signing: { keyId: "desktop-component-test-a", privateKeyFile: keyPath },
    components: [
      component("node_runtime", "22.23.2", "bin/node"),
      component("connector", "0.3.0", "bin/hermes-connector", [
        { kind: "node_runtime", contentSHA256: identities.node_runtime },
      ]),
    ],
  };
  const configPath = path.join(root, "publisher.json");
  await writeFile(configPath, `${JSON.stringify(config)}\n`, { mode: 0o600 });
  return { root, output, configPath, now, identities };
}

async function makeArchive(root, name, entrypoint, content = `#!/bin/sh\necho ${name}\n`) {
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

function hash(value) { return value.repeat(64); }
function iso(value) { return value.toISOString().replace(".000Z", "Z"); }
