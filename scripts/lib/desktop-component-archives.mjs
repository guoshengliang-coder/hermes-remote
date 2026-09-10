import {
  chmod,
  copyFile,
  cp,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  realpath,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DesktopManagedReleaseError } from "./desktop-managed-release.mjs";

const defaultRepositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const HERMES_SOURCE_DIRECTORIES = Object.freeze([
  "agent", "cron", "gateway", "hermes_cli", "locales", "native", "optional-skills",
  "plugins", "skills", "tools", "tui_gateway",
]);
const HERMES_METADATA_FILES = Object.freeze(["LICENSE", "compat_manifest.json", "pyproject.toml"]);
const MAX_COMPONENT_ENTRIES = 65_536;
const MAX_COMPONENT_BYTES = 2 * 1024 * 1024 * 1024;

export async function packageDesktopComponentArchives({
  configPath,
  outputDirectory,
  repositoryRoot = defaultRepositoryRoot,
  prepareConnector = defaultPrepareConnector,
  inspectArchitecture = defaultInspectArchitecture,
}) {
  const created = [];
  let temporaryRoot;
  try {
    const config = await loadComponentConfig(configPath);
    const repo = await requireDirectory(repositoryRoot);
    const output = await requireDirectory(outputDirectory, true);
    await assertGitIdentity(repo, config.sourceCommit);
    await prepareConnector(repo);
    await assertGitIdentity(repo, config.sourceCommit);
    await assertConnectorVersion(repo, config.connector.version);

    const nodeBinary = await requireRegularFile(config.nodeBinary, 256 * 1024 * 1024);
    await inspectArchitecture(nodeBinary, config.architecture);
    const hermesRoot = await requireDirectory(config.hermes.sourceRoot);
    const pythonRoot = await requireDirectory(config.hermes.pythonRoot);
    const sitePackages = await requireDirectory(config.hermes.sitePackages);
    await assertGitIdentity(hermesRoot, config.hermes.sourceCommit);
    await assertHermesVersion(hermesRoot, config.hermes.version);
    const pythonBinary = await requireRegularFile(path.join(pythonRoot, "bin/python3.11"), 128 * 1024 * 1024);
    await inspectArchitecture(pythonBinary, config.architecture);

    temporaryRoot = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-desktop-components-")));
    const hermesStage = path.join(temporaryRoot, "hermes");
    const connectorStage = path.join(temporaryRoot, "connector");
    await stageHermes({
      destination: hermesStage,
      hermesRoot,
      pythonRoot,
      sitePackages,
      version: config.hermes.version,
      sourceCommit: config.hermes.sourceCommit,
      architecture: config.architecture,
    });
    await stageConnector({
      destination: connectorStage,
      repo,
      nodeBinary,
      version: config.connector.version,
      sourceCommit: config.sourceCommit,
      architecture: config.architecture,
    });

    const artifacts = [
      {
        component: "hermes_server",
        version: config.hermes.version,
        stage: hermesStage,
        fileName: `Hermes-Server-${config.hermes.version}-${config.architecture}.tar.gz`,
        entrypoint: "bin/hermes-server",
      },
      {
        component: "connector",
        version: config.connector.version,
        stage: connectorStage,
        fileName: `Hermes-Connector-${config.connector.version}-${config.architecture}.tar.gz`,
        entrypoint: "bin/hermes-connector",
      },
    ];
    const result = [];
    for (const artifact of artifacts) {
      await inspectTree(artifact.stage);
      const destination = path.join(output, artifact.fileName);
      const partial = `${destination}.partial`;
      await requireAbsent(destination);
      await requireAbsent(partial);
      created.push(partial);
      run("/usr/bin/tar", ["-czf", partial, "-C", artifact.stage, "."], { timeout: 15 * 60_000 });
      await chmod(partial, 0o644);
      await rename(partial, destination);
      created.pop();
      created.push(destination);
      result.push({
        component: artifact.component,
        version: artifact.version,
        path: destination,
        entrypoint: artifact.entrypoint,
      });
    }
    await assertGitIdentity(repo, config.sourceCommit);
    await assertGitIdentity(hermesRoot, config.hermes.sourceCommit);
    return { architecture: config.architecture, artifacts: result };
  } catch (error) {
    for (const value of created.reverse()) await rm(value, { force: true }).catch(() => {});
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail("component_archive_unexpected_failure");
  } finally {
    if (temporaryRoot) await rm(temporaryRoot, { recursive: true, force: true }).catch(() => {});
  }
}

export async function loadComponentConfig(configPath) {
  const file = await requireRegularFile(configPath, 128 * 1024);
  let config;
  try { config = JSON.parse(await readFile(file, "utf8")); } catch { fail("component_config_invalid"); }
  exactKeys(config, [
    "schemaVersion", "architecture", "sourceCommit", "nodeBinary", "hermes", "connector",
  ], "component_config_fields_invalid");
  if (config.schemaVersion !== 1 || !["arm64", "x86_64"].includes(config.architecture)
      || !fullCommit(config.sourceCommit) || !path.isAbsolute(config.nodeBinary)) {
    fail("component_config_identity_invalid");
  }
  exactKeys(config.hermes, [
    "version", "sourceCommit", "sourceRoot", "pythonRoot", "sitePackages",
  ], "component_config_hermes_invalid");
  if (!semanticVersion(config.hermes.version) || !fullCommit(config.hermes.sourceCommit)
      || ![config.hermes.sourceRoot, config.hermes.pythonRoot, config.hermes.sitePackages]
        .every((value) => typeof value === "string" && path.isAbsolute(value))) {
    fail("component_config_hermes_invalid");
  }
  exactKeys(config.connector, ["version"], "component_config_connector_invalid");
  if (!semanticVersion(config.connector.version)) fail("component_config_connector_invalid");
  return config;
}

async function stageHermes({ destination, hermesRoot, pythonRoot, sitePackages, version, sourceCommit, architecture }) {
  await mkdir(path.join(destination, "bin"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "runtime/python/bin"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "runtime/python/lib"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "runtime"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "app"), { recursive: true, mode: 0o700 });

  await copyStrict(path.join(pythonRoot, "bin/python3.11"), path.join(destination, "runtime/python/bin/python3.11"));
  await copyStrict(path.join(pythonRoot, "lib/python3.11"), path.join(destination, "runtime/python/lib/python3.11"));
  await copyStrict(sitePackages, path.join(destination, "runtime/site-packages"));
  for (const directory of HERMES_SOURCE_DIRECTORIES) {
    await copyStrict(
      path.join(hermesRoot, directory),
      path.join(destination, "app", directory),
      hermesSourceFilter,
    );
  }
  for (const file of HERMES_METADATA_FILES) {
    await copyStrict(path.join(hermesRoot, file), path.join(destination, "app", file));
  }
  for (const entry of await readdir(hermesRoot, { withFileTypes: true })) {
    if (entry.isFile() && entry.name.endsWith(".py")) {
      await copyStrict(path.join(hermesRoot, entry.name), path.join(destination, "app", entry.name));
    }
  }
  const tokenReader = `import os
import re
import stat
import sys

path = os.environ.get("HERMES_SESSION_TOKEN_FILE", "")
try:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid()
                or metadata.st_mode & 0o077 or not 1 <= metadata.st_size <= 256):
            raise ValueError("unsafe")
        token = os.read(descriptor, 257).decode("ascii")
    finally:
        os.close(descriptor)
    if not re.fullmatch(r"[A-Za-z0-9_-]{43}", token):
        raise ValueError("malformed")
except Exception:
    print("Hermes session token file is invalid", file=sys.stderr)
    raise SystemExit(78)
sys.stdout.write(token)
`;
  await writeFile(path.join(destination, "runtime/read-private-session-token.py"), tokenReader, { mode: 0o600 });
  const launcher = `#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
export PYTHONNOUSERSITE=1
export PYTHONDONTWRITEBYTECODE=1
export PYTHONPATH="$ROOT/app:$ROOT/runtime/site-packages"
if [ -n "\${HERMES_SESSION_TOKEN_FILE:-}" ]; then
  HERMES_DASHBOARD_SESSION_TOKEN=$("$ROOT/runtime/python/bin/python3.11" -s "$ROOT/runtime/read-private-session-token.py")
  export HERMES_DASHBOARD_SESSION_TOKEN
fi
exec "$ROOT/runtime/python/bin/python3.11" -s -m hermes_cli.main "$@"
`;
  await writeFile(path.join(destination, "bin/hermes-server"), launcher, { mode: 0o700 });
  await writeIdentity(destination, { component: "hermes_server", version, sourceCommit, architecture });
}

async function stageConnector({ destination, repo, nodeBinary, version, sourceCommit, architecture }) {
  await mkdir(path.join(destination, "bin"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "runtime"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "app/connector"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "app/node_modules/@hermes-remote/protocol"), { recursive: true, mode: 0o700 });
  await copyStrict(nodeBinary, path.join(destination, "runtime/node"));
  await copyStrict(path.join(repo, "connector/dist"), path.join(destination, "app/connector/dist"), connectorFilter);
  await copyStrict(path.join(repo, "connector/package.json"), path.join(destination, "app/connector/package.json"));
  await copyStrict(path.join(repo, "protocol/dist/index.js"),
    path.join(destination, "app/node_modules/@hermes-remote/protocol/dist/index.js"));
  await copyStrict(path.join(repo, "protocol/package.json"),
    path.join(destination, "app/node_modules/@hermes-remote/protocol/package.json"));
  await copyStrict(path.join(repo, "node_modules/ws"), path.join(destination, "app/node_modules/ws"), runtimeJavaScriptFilter);
  const launcher = `#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
exec "$ROOT/runtime/node" "$ROOT/app/connector/dist/index.js" "$@"
`;
  await writeFile(path.join(destination, "bin/hermes-connector"), launcher, { mode: 0o700 });
  await writeIdentity(destination, { component: "connector", version, sourceCommit, architecture });
}

async function writeIdentity(destination, value) {
  await writeFile(path.join(destination, "BUILD-IDENTITY.json"), `${JSON.stringify({ schemaVersion: 1, ...value })}\n`, {
    mode: 0o600,
  });
}

async function copyStrict(source, destination, filter = defaultCopyFilter) {
  await inspectTree(source, filter);
  await mkdir(path.dirname(destination), { recursive: true, mode: 0o700 });
  await cp(source, destination, {
    recursive: true,
    errorOnExist: true,
    force: false,
    preserveTimestamps: false,
    filter: (value) => filter(value, path.basename(value)),
  });
  await inspectTree(destination, filter);
}

async function inspectTree(root, filter = defaultCopyFilter) {
  const stack = [root];
  let entries = 0;
  let bytes = 0;
  while (stack.length) {
    const current = stack.pop();
    const info = await lstat(current).catch(() => fail("component_input_missing"));
    if (info.isSymbolicLink() || (!info.isDirectory() && !info.isFile())) fail("component_input_unsafe");
    if (!filter(current, path.basename(current))) continue;
    entries += 1;
    if (entries > MAX_COMPONENT_ENTRIES) fail("component_input_too_many_entries");
    if (info.isFile()) {
      bytes += info.size;
      if (bytes > MAX_COMPONENT_BYTES) fail("component_input_too_large");
    } else {
      const children = await readdir(current);
      for (const child of children) stack.push(path.join(current, child));
    }
  }
}

function defaultCopyFilter(value, name) {
  return name !== "__pycache__" && !name.endsWith(".pyc") && name !== ".DS_Store";
}

function hermesSourceFilter(value, name) {
  return defaultCopyFilter(value, name)
    && name !== ".env" && !name.startsWith(".env.")
    && !name.endsWith(".pem") && !name.endsWith(".key")
    && name !== "id_rsa" && name !== "id_ed25519";
}

function connectorFilter(value, name) {
  return defaultCopyFilter(value, name)
    && (!name.includes(".test.") && !name.endsWith(".map") && !name.endsWith(".d.ts"));
}

function runtimeJavaScriptFilter(value, name) {
  return defaultCopyFilter(value, name) && name !== "test" && name !== "docs";
}

async function assertGitIdentity(root, expectedCommit) {
  const commit = run("git", ["-C", root, "rev-parse", "HEAD"]).trim();
  const status = run("git", ["-C", root, "status", "--porcelain", "--untracked-files=normal"]).trim();
  if (commit !== expectedCommit || status) fail("component_source_identity_invalid");
}

async function assertConnectorVersion(repo, expected) {
  let manifest;
  try { manifest = JSON.parse(await readFile(path.join(repo, "connector/package.json"), "utf8")); }
  catch { fail("connector_version_invalid"); }
  if (manifest.version !== expected) fail("connector_version_invalid");
}

async function assertHermesVersion(root, expected) {
  const value = await readFile(path.join(root, "pyproject.toml"), "utf8").catch(() => fail("hermes_version_invalid"));
  const matches = [...value.matchAll(/^version\s*=\s*"([^"]+)"\s*$/gm)].map((match) => match[1]);
  if (matches.length !== 1 || matches[0] !== expected) fail("hermes_version_invalid");
}

async function defaultPrepareConnector(repo) {
  run("npm", ["run", "build", "-w", "@hermes-remote/protocol"], { cwd: repo, timeout: 120_000 });
  run("npm", ["run", "build", "-w", "@hermes-remote/connector"], { cwd: repo, timeout: 120_000 });
}

async function defaultInspectArchitecture(file, expected) {
  const architectures = run("/usr/bin/lipo", ["-archs", file]).trim().split(/\s+/);
  if (!architectures.includes(expected)) fail("component_binary_architecture_invalid");
}

async function requireDirectory(value, create = false) {
  if (typeof value !== "string" || !path.isAbsolute(value) || value === "/") fail("component_directory_invalid");
  if (create) await mkdir(value, { recursive: true, mode: 0o700 }).catch(() => fail("component_directory_invalid"));
  const info = await lstat(value).catch(() => fail("component_directory_invalid"));
  if (!info.isDirectory() || info.isSymbolicLink()) fail("component_directory_invalid");
  const resolved = await realpath(value).catch(() => fail("component_directory_invalid"));
  if (resolved !== value || resolved === "/") fail("component_directory_invalid");
  return resolved;
}

async function requireRegularFile(value, maximum) {
  if (typeof value !== "string" || !path.isAbsolute(value)) fail("component_file_invalid");
  const info = await lstat(value).catch(() => fail("component_file_invalid"));
  if (!info.isFile() || info.isSymbolicLink() || info.size < 1 || info.size > maximum) fail("component_file_invalid");
  const resolved = await realpath(value).catch(() => fail("component_file_invalid"));
  if (resolved !== value) fail("component_file_invalid");
  return resolved;
}

async function requireAbsent(value) {
  try { await lstat(value); fail("component_output_exists"); }
  catch (error) {
    if (error instanceof DesktopManagedReleaseError) throw error;
    if (error?.code !== "ENOENT") fail("component_output_invalid");
  }
}

function exactKeys(value, keys, cause) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(cause);
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((item, index) => item !== expected[index])) fail(cause);
}

function semanticVersion(value) {
  return typeof value === "string" && /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value);
}

function fullCommit(value) {
  return typeof value === "string" && /^[0-9a-f]{40}$/.test(value);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: "utf8",
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 4 * 1024 * 1024,
    timeout: options.timeout ?? 30_000,
  });
  if (result.error || result.status !== 0) fail("component_command_failed");
  return result.stdout;
}

function fail(cause) {
  throw new DesktopManagedReleaseError(cause);
}
