import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  chmod,
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
  packageDesktopComponentArchives,
} from "../lib/desktop-component-archives.mjs";
import { DesktopManagedReleaseError } from "../lib/desktop-managed-release.mjs";

test("component builder creates source-pinned relocatable Hermes and Connector archives", async (t) => {
  const fixture = await makeFixture(t);
  const result = await build(fixture);

  assert.equal(result.architecture, "arm64");
  assert.deepEqual(result.artifacts.map((artifact) => artifact.component), ["hermes_server", "connector"]);
  assert.deepEqual((await readdir(fixture.output)).sort(), [
    "Hermes-Connector-0.1.2-arm64.tar.gz",
    "Hermes-Server-0.21.0-arm64.tar.gz",
  ]);

  const extracted = path.join(fixture.root, "extracted");
  await mkdir(extracted);
  for (const artifact of result.artifacts) {
    run("/usr/bin/tar", ["-xzf", artifact.path, "-C", extracted]);
    const launcher = await readFile(path.join(extracted, artifact.entrypoint), "utf8");
    assert.match(launcher, /ROOT=.*dirname/);
    assert.equal(launcher.includes("\.local/bin"), false);
    const identity = JSON.parse(await readFile(path.join(extracted, "BUILD-IDENTITY.json"), "utf8"));
    assert.equal(identity.component, artifact.component);
    await rm(extracted, { recursive: true, force: true });
    await mkdir(extracted);
  }

  const connectorNames = run("/usr/bin/tar", ["-tzf", result.artifacts[1].path]);
  assert.match(connectorNames, /app\/connector\/dist\/index\.js/);
  assert.doesNotMatch(connectorNames, /\.test\.|\.map$|\.d\.ts$/m);
  const hermesNames = run("/usr/bin/tar", ["-tzf", result.artifacts[0].path]);
  assert.match(hermesNames, /runtime\/python\/bin\/python3\.11/);
  assert.match(hermesNames, /runtime\/site-packages\/dependency\.py/);
  assert.doesNotMatch(hermesNames, /\.git|\.env|private\.pem|node_modules/);
});

test("component builder rejects dirty source identities before staging", async (t) => {
  const fixture = await makeFixture(t);
  await writeFile(path.join(fixture.repo, "untracked-secret"), "must not package");
  await assert.rejects(build(fixture), isCause("component_source_identity_invalid"));
  assert.deepEqual(await readdir(fixture.output), []);
});

test("component builder rejects a symlink in an allowlisted runtime tree", async (t) => {
  const fixture = await makeFixture(t);
  await symlink("dependency.py", path.join(fixture.sitePackages, "linked.py"));
  await assert.rejects(build(fixture), isCause("component_input_unsafe"));
  assert.deepEqual(await readdir(fixture.output), []);
});

test("component builder preserves an existing target and removes its earlier archive", async (t) => {
  const fixture = await makeFixture(t);
  const existing = path.join(fixture.output, "Hermes-Connector-0.1.2-arm64.tar.gz");
  await writeFile(existing, "owner data");
  await assert.rejects(build(fixture), isCause("component_output_exists"));
  assert.equal(await readFile(existing, "utf8"), "owner data");
  assert.deepEqual(await readdir(fixture.output), ["Hermes-Connector-0.1.2-arm64.tar.gz"]);
});

async function build(fixture) {
  return packageDesktopComponentArchives({
    configPath: fixture.configPath,
    outputDirectory: fixture.output,
    repositoryRoot: fixture.repo,
    prepareConnector: async () => {},
    inspectArchitecture: async () => {},
  });
}

async function makeFixture(t) {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-component-test-")));
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
  const config = {
    schemaVersion: 1,
    architecture: "arm64",
    sourceCommit: repoCommit,
    nodeBinary,
    hermes: {
      version: "0.21.0",
      sourceCommit: hermesCommit,
      sourceRoot: hermes,
      pythonRoot: python,
      sitePackages,
    },
    connector: { version: "0.1.2" },
  };
  const configPath = path.join(root, "config.json");
  await writeFile(configPath, `${JSON.stringify(config)}\n`, { mode: 0o600 });
  return { root, repo, hermes, python, sitePackages, output, configPath };
}

async function writeFixtureRepo(repo) {
  await mkdir(path.join(repo, "connector/dist"), { recursive: true });
  await mkdir(path.join(repo, "protocol/dist"), { recursive: true });
  await mkdir(path.join(repo, "node_modules/ws/lib"), { recursive: true });
  await writeFile(path.join(repo, "connector/package.json"), JSON.stringify({
    name: "@hermes-remote/connector", version: "0.1.2", type: "module",
  }));
  await writeFile(path.join(repo, "connector/dist/index.js"), "console.log('connector');\n");
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
  await writeFile(path.join(hermes, "run_agent.py"), "# root module\n");
  await writeFile(path.join(hermes, ".env"), "SECRET=not-committed\n");
  await writeFile(path.join(hermes, "tools/.env"), "SECRET=also-not-shipped\n");
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
