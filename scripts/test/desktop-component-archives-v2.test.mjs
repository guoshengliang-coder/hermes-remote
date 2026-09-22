import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, realpath, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import {
  nonPortableMachODependencies,
  packageDesktopComponentArchivesV2,
} from "../lib/desktop-component-archives.mjs";
import {
  DesktopManagedReleaseError,
  desktopComponentArchiveContentIdentity,
} from "../lib/desktop-managed-release.mjs";

test("v2 builder creates the two independently reusable release components", async (t) => {
  const fixture = await makeFixture(t);
  const result = await build(fixture);

  assert.equal(result.schemaVersion, 2);
  assert.equal(result.architecture, "arm64");
  assert.equal(result.totalSizeBytes, result.bootstrapSizeBytes);
  assert.equal(result.deferredSizeBytes, 0);
  assert.deepEqual(result.artifacts.map((artifact) => artifact.component), [
    "node_runtime", "connector",
  ]);
  assert.deepEqual(result.artifacts.map((artifact) => artifact.dependencies), [
    [], ["node_runtime"],
  ]);
  assert.deepEqual((await readdir(fixture.output)).sort(), [
    "Hermes-Component-connector-0.1.2-arm64.tar.gz",
    "Hermes-Component-node_runtime-22.23.2-arm64.tar.gz",
  ]);

  for (const artifact of result.artifacts) {
    assert.ok(artifact.sizeBytes > 0);
    assert.match(artifact.sha256, /^[0-9a-f]{64}$/);
    assert.match(artifact.contentSHA256, /^[0-9a-f]{64}$/);
    assert.equal(artifact.contentSHA256, await desktopComponentArchiveContentIdentity({
      archivePath: artifact.path,
      entrypoint: artifact.entrypoint,
    }));
    const extracted = path.join(fixture.root, `extracted-${artifact.component}`);
    await mkdir(extracted);
    run("/usr/bin/tar", ["-xzf", artifact.path, "-C", extracted]);
    const identity = JSON.parse(await readFile(path.join(extracted, "BUILD-IDENTITY.json"), "utf8"));
    assert.equal(identity.schemaVersion, 2);
    assert.equal(identity.component, artifact.component);
  }

  const nodeNames = archiveNames(result, "node_runtime");
  assert.match(nodeNames, /bin\/node/);
  assert.doesNotMatch(nodeNames, /connector\/dist|site-packages/);
  const connectorNames = archiveNames(result, "connector");
  assert.match(connectorNames, /app\/connector\/dist\/index\.js/);
  assert.doesNotMatch(connectorNames, /runtime\/node|\.test\.|\.map$|\.d\.ts$/m);
  const connectorLauncher = await readFile(
    path.join(fixture.root, "extracted-connector/bin/hermes-connector"), "utf8",
  );
  assert.match(connectorLauncher, /HERMES_NODE_RUNTIME_ROOT/);
});

test("v2 builder rejects unsafe runtime input before publishing any archive", async (t) => {
  const fixture = await makeFixture(t);
  await rm(fixture.nodeBinary);
  await symlink("missing-node", fixture.nodeBinary);
  await assert.rejects(build(fixture), isCause("component_file_invalid"));
  assert.deepEqual(await readdir(fixture.output), []);
});

test("v2 builder preserves an existing target and removes archives from the failed attempt", async (t) => {
  const fixture = await makeFixture(t);
  const existing = path.join(fixture.output, "Hermes-Component-node_runtime-22.23.2-arm64.tar.gz");
  await writeFile(existing, "owner data");
  await assert.rejects(build(fixture), isCause("component_output_exists"));
  assert.equal(await readFile(existing, "utf8"), "owner data");
  assert.deepEqual(await readdir(fixture.output), ["Hermes-Component-node_runtime-22.23.2-arm64.tar.gz"]);
});

test("v2 builder rejects unknown configuration fields", async (t) => {
  const fixture = await makeFixture(t);
  const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
  config.downloadDuringBuild = true;
  await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`);
  await assert.rejects(build(fixture), isCause("component_config_fields_invalid"));
});

test("v2 portability gate rejects Homebrew and unresolved runtime dependencies", () => {
  const dependencies = nonPortableMachODependencies(`/opt/homebrew/bin/node:
\t@rpath/libnode.127.dylib (compatibility version 0.0.0, current version 0.0.0)
\t/opt/homebrew/opt/libuv/lib/libuv.1.dylib (compatibility version 1.0.0, current version 1.0.0)
\t/System/Library/Frameworks/Security.framework/Versions/A/Security (compatibility version 1.0.0)
\t/usr/lib/libSystem.B.dylib (compatibility version 1.0.0)
`);
  assert.deepEqual(dependencies, ["@rpath/libnode.127.dylib", "/opt/homebrew/opt/libuv/lib/libuv.1.dylib"]);
});

async function build(fixture) {
  return packageDesktopComponentArchivesV2({
    configPath: fixture.configPath,
    outputDirectory: fixture.output,
    repositoryRoot: fixture.repo,
    prepareConnector: async () => {},
    inspectArchitecture: async () => {},
    inspectPortability: async () => {},
    inspectNodeVersion: async () => {},
  });
}

function archiveNames(result, component) {
  return run("/usr/bin/tar", ["-tzf", result.artifacts.find((item) => item.component === component).path]);
}

async function makeFixture(t) {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-component-v2-test-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const repo = path.join(root, "repo");
  const output = path.join(root, "output");
  await mkdir(repo);
  await mkdir(output);
  await writeFixtureRepo(repo);
  const repoCommit = initializeGit(repo);
  const nodeBinary = path.join(root, "node");
  await writeFile(nodeBinary, "fake mach-o", { mode: 0o700 });
  const configPath = path.join(root, "config.json");
  await writeFile(configPath, `${JSON.stringify({
    schemaVersion: 2,
    architecture: "arm64",
    sourceCommit: repoCommit,
    nodeRuntime: { version: "22.23.2", binary: nodeBinary },
    connector: { version: "0.1.2" },
  })}\n`, { mode: 0o600 });
  return { root, repo, nodeBinary, output, configPath };
}

async function writeFixtureRepo(repo) {
  await mkdir(path.join(repo, "connector/dist"), { recursive: true });
  await mkdir(path.join(repo, "protocol/dist"), { recursive: true });
  await mkdir(path.join(repo, "node_modules/ws/lib"), { recursive: true });
  await writeFile(path.join(repo, "connector/package.json"), JSON.stringify({
    name: "@hermes-remote/connector", version: "0.1.2", type: "module",
  }));
  await writeFile(path.join(repo, "connector/dist/index.js"), "console.log('connector');\n");
  await writeFile(path.join(repo, "connector/dist/hermes-session-token.js"), `import { readFileSync } from "node:fs";
export function loadHermesSessionToken({ file }) {
  const token = readFileSync(file, "utf8");
  if (!/^(?:[A-Za-z0-9_-]{43}|[0-9a-f]{64})$/.test(token)) throw new Error("malformed");
  return token;
}
`);
  await writeFile(path.join(repo, "connector/dist/index.test.js"), "throw new Error('do not ship');\n");
  await writeFile(path.join(repo, "connector/dist/index.js.map"), "{}\n");
  await writeFile(path.join(repo, "connector/dist/index.d.ts"), "export {};\n");
  await writeFile(path.join(repo, "protocol/package.json"), JSON.stringify({
    name: "@hermes-remote/protocol", version: "0.1.0", type: "module", main: "dist/index.js",
  }));
  await writeFile(path.join(repo, "protocol/dist/index.js"), "export const protocol = 2;\n");
  await writeFile(path.join(repo, "node_modules/ws/package.json"), JSON.stringify({
    name: "ws", version: "8.18.3", type: "commonjs", main: "index.js",
  }));
  await writeFile(path.join(repo, "node_modules/ws/index.js"), "module.exports = {};\n");
  await writeFile(path.join(repo, "node_modules/ws/lib/websocket.js"), "module.exports = {};\n");
}

function initializeGit(directory) {
  run("git", ["init", "-q"], directory);
  run("git", ["add", "."], directory);
  run("git", ["-c", "user.name=Hermes Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture"], directory);
  return run("git", ["rev-parse", "HEAD"], directory).trim();
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, encoding: "utf8", shell: false });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout;
}

function isCause(cause) {
  return (error) => error instanceof DesktopManagedReleaseError && error.technicalCause === cause;
}
