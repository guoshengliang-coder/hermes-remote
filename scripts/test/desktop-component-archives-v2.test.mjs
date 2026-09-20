import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  mkdir,
  mkdtemp,
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
  MANAGED_SITE_PATH_FILE,
  nonPortableMachODependencies,
  packageDesktopComponentArchivesV2,
} from "../lib/desktop-component-archives.mjs";
import {
  DesktopManagedReleaseError,
  desktopComponentArchiveContentIdentity,
} from "../lib/desktop-managed-release.mjs";

test("v2 builder creates four independently reusable component archives with measured identities", async (t) => {
  const fixture = await makeFixture(t);
  const result = await build(fixture);

  assert.equal(result.schemaVersion, 2);
  assert.equal(result.architecture, "arm64");
  assert.equal(result.totalSizeBytes, result.bootstrapSizeBytes);
  assert.equal(result.deferredSizeBytes, 0);
  assert.deepEqual(result.artifacts.map((artifact) => artifact.component), [
    "python_runtime", "hermes_core", "node_runtime", "connector",
  ]);
  assert.deepEqual(result.artifacts.map((artifact) => artifact.dependencies), [
    [], ["python_runtime"], [], ["hermes_core", "node_runtime"],
  ]);
  assert.deepEqual((await readdir(fixture.output)).sort(), [
    "Hermes-Component-connector-0.1.2-arm64.tar.gz",
    "Hermes-Component-hermes_core-0.21.0-arm64.tar.gz",
    "Hermes-Component-node_runtime-22.23.2-arm64.tar.gz",
    "Hermes-Component-python_runtime-3.11.15-arm64.tar.gz",
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

  const pythonNames = archiveNames(result, "python_runtime");
  assert.match(pythonNames, /bin\/python3\.11/);
  assert.match(pythonNames, /site-packages\/dependency\.py/);
  assert.doesNotMatch(pythonNames, /hermes_cli|connector\/dist/);
  const coreNames = archiveNames(result, "hermes_core");
  assert.match(coreNames, /app\/hermes_cli\/module\.py/);
  assert.match(coreNames, /runtime\/read-private-session-token\.py/);
  assert.doesNotMatch(coreNames, /bin\/python3\.11|site-packages\/dependency\.py|connector\/dist/);
  const nodeNames = archiveNames(result, "node_runtime");
  assert.match(nodeNames, /bin\/node/);
  assert.doesNotMatch(nodeNames, /connector\/dist|site-packages/);
  const connectorNames = archiveNames(result, "connector");
  assert.match(connectorNames, /app\/connector\/dist\/index\.js/);
  assert.doesNotMatch(connectorNames, /runtime\/node|\.test\.|\.map$|\.d\.ts$/m);

  const pythonExtracted = path.join(fixture.root, "extracted-python_runtime");
  const componentPath = await readFile(path.join(
    pythonExtracted, "lib/python3.11/site-packages", MANAGED_SITE_PATH_FILE,
  ), "utf8");
  assert.match(componentPath, /HERMES_COMPONENT_CORE_ROOT/);
  assert.match(componentPath, /sys\.prefix/);
  assert.equal(componentPath.trimEnd().includes("\n"), false);

  const coreLauncher = await readFile(path.join(fixture.root, "extracted-hermes_core/bin/hermes"), "utf8");
  assert.match(coreLauncher, /HERMES_PYTHON_RUNTIME_ROOT/);
  assert.match(coreLauncher, /HERMES_COMPONENT_CORE_ROOT/);
  assert.match(coreLauncher, /HERMES_DASHBOARD_SESSION_TOKEN/);
  const connectorLauncher = await readFile(
    path.join(fixture.root, "extracted-connector/bin/hermes-connector"), "utf8",
  );
  assert.match(connectorLauncher, /HERMES_NODE_RUNTIME_ROOT/);
});

test("v2 builder rejects unsafe runtime input before publishing any archive", async (t) => {
  const fixture = await makeFixture(t);
  await symlink("dependency.py", path.join(fixture.sitePackages, "linked.py"));
  await assert.rejects(build(fixture), isCause("component_input_unsafe"));
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

test("v2 builder packages validated optional roots without adding them to bootstrap bytes", async (t) => {
  const fixture = await makeFixture(t);
  const browserRoot = await optionalRoot(fixture.root, "browser", "bin/chromium");
  const speechRoot = await optionalRoot(fixture.root, "speech", "bin/health-check");
  const documentRoot = await optionalRoot(fixture.root, "document", "bin/health-check");
  const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
  config.optionalComponents = [
    {
      kind: "browser_automation", version: "123.0.0", root: browserRoot,
      entrypoint: "bin/chromium", onDemandTrigger: "browser_automation",
      reuseContract: "verified_compatibility", compatibilityIdentifier: "chromium-cdp-1",
      dependencies: [],
    },
    {
      kind: "speech_runtime", version: "1.2.1", root: speechRoot,
      entrypoint: "bin/health-check", onDemandTrigger: "speech_runtime",
      reuseContract: "exact_content", dependencies: ["python_runtime"],
    },
    {
      kind: "document_tools", version: "0.1.6", root: documentRoot,
      entrypoint: "bin/health-check", onDemandTrigger: "document_tools",
      reuseContract: "exact_content", dependencies: ["python_runtime"],
    },
  ];
  await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`);

  const inspected = [];
  const result = await packageDesktopComponentArchivesV2({
    configPath: fixture.configPath,
    outputDirectory: fixture.output,
    repositoryRoot: fixture.repo,
    prepareConnector: async () => {},
    inspectArchitecture: async () => {},
    inspectPortability: async () => {},
    inspectPythonVersion: async () => {},
    inspectNodeVersion: async () => {},
    inspectOptionalComponent: async (value) => inspected.push(value.kind),
  });

  assert.deepEqual(inspected, ["browser_automation", "speech_runtime", "document_tools"]);
  assert.deepEqual(result.artifacts.map((artifact) => artifact.component), [
    "python_runtime", "hermes_core", "node_runtime", "connector",
    "browser_automation", "speech_runtime", "document_tools",
  ]);
  assert.equal(result.totalSizeBytes, result.bootstrapSizeBytes + result.deferredSizeBytes);
  assert.ok(result.deferredSizeBytes > 0);
  assert.deepEqual(result.artifacts.slice(4).map((artifact) => artifact.installPhase), [
    "on_demand", "on_demand", "on_demand",
  ]);
  assert.deepEqual(result.artifacts.slice(4).map((artifact) => artifact.dependencies), [
    [], ["python_runtime"], ["python_runtime"],
  ]);
  assert.match(archiveNames(result, "speech_runtime"), /site-packages\/feature\.py/);
  assert.doesNotMatch(archiveNames(result, "speech_runtime"), /owner\.pem/);
});

test("v2 builder rejects unsafe optional topology before publishing archives", async (t) => {
  const fixture = await makeFixture(t);
  const speechRoot = await optionalRoot(fixture.root, "speech", "bin/health-check");
  const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
  config.optionalComponents = [{
    kind: "speech_runtime", version: "1.2.1", root: speechRoot,
    entrypoint: "bin/health-check", onDemandTrigger: "speech_runtime",
    reuseContract: "exact_content", dependencies: [],
  }];
  await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`);
  await assert.rejects(build(fixture), isCause("component_config_optional_invalid"));
  assert.deepEqual(await readdir(fixture.output), []);
});

test("v2 builder runs a prepared Python optional component health entrypoint", async (t) => {
  const fixture = await makeFixture(t);
  const speechRoot = await optionalRoot(fixture.root, "speech", "bin/health-check");
  await writeFile(
    path.join(speechRoot, "bin/health-check"),
    "#!/bin/sh\n[ \"${1:-}\" = \"--health-check\" ] || exit 8\nexit 9\n",
    { mode: 0o700 },
  );
  const config = JSON.parse(await readFile(fixture.configPath, "utf8"));
  config.optionalComponents = [{
    kind: "speech_runtime", version: "1.2.1", root: speechRoot,
    entrypoint: "bin/health-check", onDemandTrigger: "speech_runtime",
    reuseContract: "exact_content", dependencies: ["python_runtime"],
  }];
  await writeFile(fixture.configPath, `${JSON.stringify(config)}\n`);

  await assert.rejects(packageDesktopComponentArchivesV2({
    configPath: fixture.configPath,
    outputDirectory: fixture.output,
    repositoryRoot: fixture.repo,
    prepareConnector: async () => {},
    inspectArchitecture: async () => {},
    inspectPortability: async () => {},
    inspectPythonVersion: async () => {},
    inspectNodeVersion: async () => {},
  }), isCause("component_command_failed"));
  assert.deepEqual(await readdir(fixture.output), []);
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
    inspectPythonVersion: async () => {},
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
  const hermes = path.join(root, "hermes");
  const python = path.join(root, "python");
  const sitePackages = path.join(root, "site-packages");
  const output = path.join(root, "output");
  await mkdir(repo);
  await mkdir(hermes);
  await mkdir(path.join(python, "bin"), { recursive: true });
  await mkdir(path.join(python, "lib/python3.11"), { recursive: true });
  await mkdir(sitePackages);
  await mkdir(output);
  await writeFixtureRepo(repo);
  await writeFixtureHermes(hermes);
  await writeFile(path.join(python, "bin/python3.11"), "fake mach-o", { mode: 0o700 });
  await writeFile(path.join(python, "lib/python3.11/os.py"), "# stdlib\n");
  await writeFile(path.join(sitePackages, "dependency.py"), "# dependency\n");
  const repoCommit = initializeGit(repo);
  const hermesCommit = initializeGit(hermes);
  const nodeBinary = path.join(root, "node");
  await writeFile(nodeBinary, "fake mach-o", { mode: 0o700 });
  const configPath = path.join(root, "config.json");
  await writeFile(configPath, `${JSON.stringify({
    schemaVersion: 2,
    architecture: "arm64",
    sourceCommit: repoCommit,
    pythonRuntime: { version: "3.11.15", root: python, sitePackages },
    nodeRuntime: { version: "22.23.2", binary: nodeBinary },
    hermesCore: { version: "0.21.0", sourceCommit: hermesCommit, sourceRoot: hermes },
    connector: { version: "0.1.2" },
    optionalComponents: [],
  })}\n`, { mode: 0o600 });
  return { root, repo, hermes, sitePackages, output, configPath };
}

async function optionalRoot(root, name, entrypoint) {
  const component = path.join(root, `optional-${name}`);
  await mkdir(path.join(component, path.dirname(entrypoint)), { recursive: true });
  await mkdir(path.join(component, "site-packages"), { recursive: true });
  await writeFile(path.join(component, entrypoint), "#!/bin/sh\nexit 0\n", { mode: 0o700 });
  await writeFile(path.join(component, "site-packages/feature.py"), "# optional feature\n");
  await writeFile(path.join(component, "owner.pem"), "must not ship\n");
  return component;
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

async function writeFixtureHermes(hermes) {
  for (const directory of [
    "agent", "cron", "gateway", "hermes_cli", "locales", "native", "optional-skills",
    "plugins", "skills", "tools", "tui_gateway",
  ]) {
    await mkdir(path.join(hermes, directory), { recursive: true });
    await writeFile(path.join(hermes, directory, "module.py"), "# source\n");
  }
  await writeFile(path.join(hermes, "pyproject.toml"), '[project]\nversion = "0.21.0"\n');
  await writeFile(path.join(hermes, "LICENSE"), "MIT\n");
  await writeFile(path.join(hermes, "compat_manifest.json"), "{}\n");
  // A Hermes tree carries its schema; the packer reads it to record the column baseline Desktop
  // later compares the live database against (HR-MIGRATE-006). Omitting it here would leave the
  // drift check untested and the build failing on a file every real tree has.
  await writeFile(path.join(hermes, "hermes_state_common.py"), [
    "SCHEMA_VERSION = 30",
    'SCHEMA_SQL = """',
    "CREATE TABLE IF NOT EXISTS sessions (",
    "    id TEXT PRIMARY KEY,",
    "    source TEXT NOT NULL",
    ");",
    "",
    "CREATE TABLE IF NOT EXISTS messages (",
    "    id INTEGER PRIMARY KEY AUTOINCREMENT,",
    "    session_id TEXT NOT NULL REFERENCES sessions(id),",
    "    role TEXT NOT NULL,",
    "    content TEXT",
    ");",
    '"""',
    "",
  ].join("\n"));
  await writeFile(path.join(hermes, "run_agent.py"), "# root module\n");
  await writeFile(path.join(hermes, ".env"), "SECRET=not-committed\n");
  await writeFile(path.join(hermes, "tools/private.pem"), "not-shipped\n");
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
