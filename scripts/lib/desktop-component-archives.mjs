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
  stat,
  utimes,
  writeFile,
} from "node:fs/promises";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import {
  HERMES_METADATA_FILES,
  HERMES_SOURCE_DIRECTORIES,
} from "./managed-hermes-source.mjs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  DesktopManagedReleaseError,
  desktopComponentArchiveContentIdentity,
} from "./desktop-managed-release.mjs";

const defaultRepositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const MAX_COMPONENT_ENTRIES = 65_536;
const MAX_COMPONENT_BYTES = 2 * 1024 * 1024 * 1024;

const COMPONENT_V2_ORDER = Object.freeze([
  "node_runtime", "connector",
]);

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
    await verifyConnectorSessionTokenContract(connectorStage, temporaryRoot);

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
      await normalizeTreeTimestamps(artifact.stage);
      await packDeterministicArchive(partial, artifact.stage, 15 * 60_000);
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

/**
 * Build the schema-v2 component inputs without changing the shipping schema-v1 release path.
 *
 * Runtime components are immutable and reusable. The two small application components locate their
 * runtime through an activation-time root supplied by Desktop, so no absolute build-machine path is
 * written into an archive and no shared component needs to be patched after extraction.
 */
export async function packageDesktopComponentArchivesV2({
  configPath,
  outputDirectory,
  repositoryRoot = defaultRepositoryRoot,
  prepareConnector = defaultPrepareConnector,
  inspectArchitecture = defaultInspectArchitecture,
  inspectPortability = defaultInspectPortability,
  inspectPythonVersion = defaultInspectPythonVersion,
  inspectNodeVersion = defaultInspectNodeVersion,
  inspectOptionalComponent = defaultInspectOptionalComponent,
}) {
  const created = [];
  let temporaryRoot;
  try {
    const config = await loadComponentConfigV2(configPath);
    const repo = await requireDirectory(repositoryRoot);
    const output = await requireDirectory(outputDirectory, true);
    await assertGitIdentity(repo, config.sourceCommit);
    await prepareConnector(repo);
    await assertGitIdentity(repo, config.sourceCommit);
    await assertConnectorVersion(repo, config.connector.version);

    const nodeBinary = await requireRegularFile(config.nodeRuntime.binary, 256 * 1024 * 1024);
    await inspectArchitecture(nodeBinary, config.architecture);
    await inspectPortability(nodeBinary);
    await inspectNodeVersion(nodeBinary, config.nodeRuntime.version);

    temporaryRoot = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-desktop-components-v2-")));
    const componentOrder = [...COMPONENT_V2_ORDER];
    const stages = Object.fromEntries(componentOrder.map((kind) => [kind, path.join(temporaryRoot, kind)]));
    await stageNodeRuntimeV2({
      destination: stages.node_runtime,
      nodeBinary,
      version: config.nodeRuntime.version,
      sourceCommit: config.sourceCommit,
      architecture: config.architecture,
    });
    await stageConnectorV2({
      destination: stages.connector,
      repo,
      version: config.connector.version,
      sourceCommit: config.sourceCommit,
      architecture: config.architecture,
    });
    await verifyConnectorSessionTokenContract(stages.connector, temporaryRoot);

    const definitions = [
      componentV2("node_runtime", config.nodeRuntime.version, "bin/node", []),
      componentV2("connector", config.connector.version, "bin/hermes-connector", ["node_runtime"]),
    ];
    const artifacts = [];
    for (const definition of definitions) {
      const stage = stages[definition.component];
      await inspectTree(stage);
      const fileName = `Hermes-Component-${definition.component}-${definition.version}-${config.architecture}.tar.gz`;
      const destination = path.join(output, fileName);
      const partial = `${destination}.partial`;
      await requireAbsent(destination);
      await requireAbsent(partial);
      created.push(partial);
      await normalizeTreeTimestamps(stage);
      await packDeterministicArchive(partial, stage, 15 * 60_000);
      await chmod(partial, 0o644);
      await rename(partial, destination);
      created.pop();
      created.push(destination);
      const archiveInfo = await stat(destination);
      artifacts.push({
        ...definition,
        architecture: config.architecture,
        path: destination,
        sizeBytes: archiveInfo.size,
        sha256: await hashRegularFile(destination),
        contentSHA256: await desktopComponentArchiveContentIdentity({
          archivePath: destination,
          entrypoint: definition.entrypoint,
        }),
      });
    }
    await assertGitIdentity(repo, config.sourceCommit);
    return {
      schemaVersion: 2,
      architecture: config.architecture,
      totalSizeBytes: artifacts.reduce((sum, artifact) => sum + artifact.sizeBytes, 0),
      bootstrapSizeBytes: artifacts
        .filter((artifact) => artifact.installPhase === "bootstrap")
        .reduce((sum, artifact) => sum + artifact.sizeBytes, 0),
      deferredSizeBytes: artifacts
        .filter((artifact) => artifact.installPhase === "on_demand")
        .reduce((sum, artifact) => sum + artifact.sizeBytes, 0),
      artifacts,
    };
  } catch (error) {
    for (const value of created.reverse()) await rm(value, { force: true }).catch(() => {});
    if (error instanceof DesktopManagedReleaseError) throw error;
    fail("component_archive_unexpected_failure");
  } finally {
    if (temporaryRoot) await rm(temporaryRoot, { recursive: true, force: true }).catch(() => {});
  }
}

export async function loadComponentConfigV2(configPath) {
  const file = await requireRegularFile(configPath, 128 * 1024);
  let config;
  try { config = JSON.parse(await readFile(file, "utf8")); } catch { fail("component_config_invalid"); }
  exactKeys(config, [
    "schemaVersion", "architecture", "sourceCommit", "nodeRuntime", "connector",
  ], "component_config_fields_invalid");
  if (config.schemaVersion !== 2 || !["arm64", "x86_64"].includes(config.architecture)
      || !fullCommit(config.sourceCommit)) fail("component_config_identity_invalid");

  exactKeys(config.nodeRuntime, ["version", "binary"], "component_config_node_invalid");
  if (!semanticVersion(config.nodeRuntime.version) || typeof config.nodeRuntime.binary !== "string"
      || !path.isAbsolute(config.nodeRuntime.binary)) fail("component_config_node_invalid");
  exactKeys(config.connector, ["version"], "component_config_connector_invalid");
  if (!semanticVersion(config.connector.version)) fail("component_config_connector_invalid");
  return config;
}

function componentV2(component, version, entrypoint, dependencies, extra = {}) {
  return { component, version, entrypoint, dependencies, installPhase: "bootstrap", ...extra };
}

function validIdentifier(value) {
  return typeof value === "string" && /^[A-Za-z0-9._-]{1,96}$/.test(value);
}

function validRelativePath(value) {
  return typeof value === "string" && value.length > 0 && value.length <= 512
    && !path.isAbsolute(value) && !value.includes("\\")
    && !value.split("/").some((part) => !part || part === "." || part === "..")
    && ![...value].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127);
}

async function stageOptionalComponentV2({
  destination, sourceRoot, kind, version, sourceCommit, architecture,
}) {
  await copyStrict(sourceRoot, destination, optionalComponentFilter);
  await writeIdentityV2(destination, {
    component: kind, version, sourceCommit, architecture,
  });
}

/**
 * Name of the `.pth` dropped into the bundled interpreter's own site-packages.
 * `_` first so `site` processes it before anything a dependency ships.
 */
export const MANAGED_SITE_PATH_FILE = "_hermes_go_managed_paths.pth";

/**
 * A `.pth` that puts the bundle's `app/` and `runtime/site-packages/` on `sys.path` for EVERY
 * process started with this interpreter — not just the ones the launcher starts.
 *
 * The launcher's `PYTHONPATH` is not enough, and this is not belt-and-braces. Hermes spawns its
 * slash worker as a child of `sys.executable` and builds that child's environment through
 * `tools/environments/local.py`, which deliberately strips the Hermes repo root back out of
 * `PYTHONPATH` (so a child Python of a different version cannot load the backend's C extensions).
 * In a normal editable install that is harmless, because `tui_gateway` lives in site-packages. In
 * this bundle `app/` IS the repo root and `PYTHONPATH` is its only route, so the strip left the
 * worker unable to import `tui_gateway` at all — every slash command (`/model`, `/compact`, …)
 * died with `ModuleNotFoundError` (HG-28).
 *
 * `site` processes `.pth` files for real site directories, which `PYTHONPATH` entries are not, so
 * the path arrives through a channel the strip does not touch. The root is derived from
 * `sys.prefix` at run time rather than baked in, so the bundle stays relocatable, and each entry
 * is guarded by `isdir` so a partially-extracted bundle degrades instead of raising on startup.
 */
export function managedSitePathLine() {
  return 'import os, sys; _r = os.path.dirname(os.path.dirname(sys.prefix)); ' +
    '[sys.path.insert(0, _p) for _p in (os.path.join(_r, "runtime", "site-packages"), ' +
    'os.path.join(_r, "app")) if os.path.isdir(_p) and _p not in sys.path]\n';
}

/**
 * Runtime reader embedded in the managed Hermes Server bundle.
 *
 * New installs generate a 43-character base64url token, while installations migrated from the
 * original Connector may legitimately retain a 64-character lowercase hexadecimal token. Desktop
 * validates and preserves both formats, so the packaged reader must accept that same frozen
 * compatibility boundary before it exports the token to Hermes.
 */
export function managedSessionTokenReaderSource() {
  return `import os
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
    if not (re.fullmatch(r"[A-Za-z0-9_-]{43}", token)
            or re.fullmatch(r"[a-f0-9]{64}", token)):
        raise ValueError("malformed")
except Exception:
    print("Hermes session token file is invalid", file=sys.stderr)
    raise SystemExit(78)
sys.stdout.write(token)
`;
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
  const interpreterSitePackages = path.join(destination, "runtime/python/lib/python3.11/site-packages");
  await mkdir(interpreterSitePackages, { recursive: true, mode: 0o755 });
  await writeFile(path.join(interpreterSitePackages, MANAGED_SITE_PATH_FILE), managedSitePathLine(), {
    mode: 0o644,
  });
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
  await writeFile(
    path.join(destination, "runtime/read-private-session-token.py"),
    managedSessionTokenReaderSource(),
    { mode: 0o600 },
  );
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
  await writeIdentity(destination, {
    component: "hermes_server", version, sourceCommit, architecture,
  });
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

async function stageNodeRuntimeV2({ destination, nodeBinary, version, sourceCommit, architecture }) {
  await mkdir(path.join(destination, "bin"), { recursive: true, mode: 0o700 });
  await copyStrict(nodeBinary, path.join(destination, "bin/node"));
  await chmod(path.join(destination, "bin/node"), 0o700);
  await writeIdentityV2(destination, {
    component: "node_runtime", version, sourceCommit, architecture,
  });
}

async function stageConnectorV2({ destination, repo, version, sourceCommit, architecture }) {
  await mkdir(path.join(destination, "bin"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "app/connector"), { recursive: true, mode: 0o700 });
  await mkdir(path.join(destination, "app/node_modules/@hermes-remote/protocol"), {
    recursive: true, mode: 0o700,
  });
  await copyStrict(path.join(repo, "connector/dist"), path.join(destination, "app/connector/dist"), connectorFilter);
  await copyStrict(path.join(repo, "connector/package.json"), path.join(destination, "app/connector/package.json"));
  await copyStrict(path.join(repo, "protocol/dist/index.js"),
    path.join(destination, "app/node_modules/@hermes-remote/protocol/dist/index.js"));
  await copyStrict(path.join(repo, "protocol/package.json"),
    path.join(destination, "app/node_modules/@hermes-remote/protocol/package.json"));
  await copyStrict(path.join(repo, "node_modules/ws"),
    path.join(destination, "app/node_modules/ws"), runtimeJavaScriptFilter);
  const launcher = `#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
: "\${HERMES_NODE_RUNTIME_ROOT:?HERMES_NODE_RUNTIME_ROOT is required}"
case "$HERMES_NODE_RUNTIME_ROOT" in /*) ;; *) exit 78 ;; esac
[ -x "$HERMES_NODE_RUNTIME_ROOT/bin/node" ] || exit 78
exec "$HERMES_NODE_RUNTIME_ROOT/bin/node" "$ROOT/app/connector/dist/index.js" "$@"
`;
  await writeFile(path.join(destination, "bin/hermes-connector"), launcher, { mode: 0o700 });
  await writeIdentityV2(destination, {
    component: "connector", version, sourceCommit, architecture,
  });
}

async function verifyConnectorSessionTokenContract(connectorStage, temporaryRoot) {
  const fixtureRoot = path.join(temporaryRoot, "connector-token-contract");
  await mkdir(fixtureRoot, { mode: 0o700 });
  const base64urlFile = path.join(fixtureRoot, "base64url-token");
  const hexFile = path.join(fixtureRoot, "hex-token");
  const uppercaseHexFile = path.join(fixtureRoot, "uppercase-hex-token");
  await writeFile(base64urlFile, "A".repeat(43), { mode: 0o600 });
  await writeFile(hexFile, "a".repeat(64), { mode: 0o600 });
  await writeFile(uppercaseHexFile, "A".repeat(64), { mode: 0o600 });

  const verifier = path.join(fixtureRoot, "verify.mjs");
  await writeFile(verifier, `import { pathToFileURL } from "node:url";
const { loadHermesSessionToken } = await import(pathToFileURL(process.argv[2]).href);
if (loadHermesSessionToken({ file: process.argv[3] }) !== "A".repeat(43)) process.exit(1);
if (loadHermesSessionToken({ file: process.argv[4] }) !== "a".repeat(64)) process.exit(1);
let rejected = false;
try { loadHermesSessionToken({ file: process.argv[5] }); } catch { rejected = true; }
if (!rejected) process.exit(1);
`, { mode: 0o600 });

  const result = spawnSync(process.execPath, [
    verifier,
    path.join(connectorStage, "app/connector/dist/hermes-session-token.js"),
    base64urlFile,
    hexFile,
    uppercaseHexFile,
  ], {
    encoding: "utf8",
    shell: false,
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 1024 * 1024,
    timeout: 30_000,
  });
  if (result.error || result.status !== 0) fail("connector_token_contract_invalid");
}

async function writeIdentity(destination, value) {
  await writeFile(path.join(destination, "BUILD-IDENTITY.json"), `${JSON.stringify({ schemaVersion: 1, ...value })}\n`, {
    mode: 0o600,
  });
}

async function writeIdentityV2(destination, value) {
  await writeFile(path.join(destination, "BUILD-IDENTITY.json"), `${JSON.stringify({
    schemaVersion: 2,
    ...value,
  })}\n`, { mode: 0o600 });
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

function optionalComponentFilter(value, name) {
  return hermesSourceFilter(value, name) && name !== "BUILD-IDENTITY.json";
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

async function defaultInspectPortability(file) {
  const output = run("/usr/bin/otool", ["-L", file]);
  if (nonPortableMachODependencies(output).length > 0) fail("component_binary_not_portable");
}

export function nonPortableMachODependencies(output) {
  if (typeof output !== "string" || !output.includes(":")) return ["invalid"];
  return output.split("\n").slice(1).map((line) => line.trim().split(" ")[0]).filter(Boolean)
    .filter((dependency) => !dependency.startsWith("/System/Library/") && !dependency.startsWith("/usr/lib/"));
}

async function defaultInspectPythonVersion(file, expected) {
  const observed = run(file, ["--version"]).trim().replace(/^Python\s+/, "");
  if (observed !== expected) fail("component_python_version_invalid");
}

async function defaultInspectNodeVersion(file, expected) {
  const observed = run(file, ["--version"]).trim().replace(/^v/, "");
  if (observed !== expected) fail("component_node_version_invalid");
}

async function defaultInspectOptionalComponent({ kind, entrypoint, architecture }) {
  const executable = await requireRegularFile(entrypoint, MAX_COMPONENT_BYTES);
  const info = await stat(executable);
  if ((info.mode & 0o111) === 0) fail("component_optional_entrypoint_invalid");
  if (kind === "browser_automation") {
    await defaultInspectArchitecture(executable, architecture);
    run(executable, ["--version"], { timeout: 30_000 });
    return;
  }
  run(executable, ["--health-check"], { timeout: 120_000 });
}

async function hashRegularFile(file) {
  const digest = createHash("sha256");
  await new Promise((resolve, reject) => {
    const stream = createReadStream(file);
    stream.on("error", reject);
    stream.on("data", (chunk) => digest.update(chunk));
    stream.on("end", resolve);
  }).catch(() => fail("component_output_invalid"));
  return digest.digest("hex");
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
    env: options.env ? { ...process.env, ...options.env } : undefined,
  });
  if (result.error || result.status !== 0) fail("component_command_failed");
  return result.stdout;
}

/**
 * Every mtime in a staged tree, set to one fixed instant.
 *
 * A component archive has to be byte-reproducible or its provenance is a claim rather than a check
 * (docs/MANAGED_HERMES_STRATEGY.md). Two things stopped it, and this is the second of them: a file
 * that reaches the packer with a sub-second mtime cannot be described in `ustar`, so bsdtar
 * silently switches that entry to `pax` and writes an extended header carrying the fractional
 * value. The archive then changes whenever the copy that produced the stage ran.
 *
 * The constant is arbitrary and deliberately not "now": the point is that two builds of the same
 * inputs agree, not that the timestamps mean anything. Symlinks are skipped — `utimes` would
 * follow them and touch the target instead.
 */
const ARCHIVE_MTIME_SECONDS = 1_700_000_000;

async function normalizeTreeTimestamps(root) {
  const when = new Date(ARCHIVE_MTIME_SECONDS * 1000);
  const entries = await readdir(root, { withFileTypes: true, recursive: true });
  for (const entry of entries) {
    if (entry.isSymbolicLink()) continue;
    await utimes(path.join(entry.parentPath ?? entry.path, entry.name), when, when);
  }
  await utimes(root, when, when);
}

/**
 * Pack a staged tree so that the same inputs always produce the same bytes.
 *
 * `COPYFILE_DISABLE=1` is the first of the two fixes, and the larger one. Without it macOS tar
 * archives each file's extended attributes as a separate AppleDouble `._name` member: measured on
 * the 0.3.6 hermes_server stage, 23,111 files became 46,222 entries and 47 MiB of the uncompressed
 * stream. Those members carry volume-specific metadata, so they are both noise and a reason the
 * hash moves between machines.
 *
 * `--format ustar` pins the format rather than letting the tar implementation choose per entry.
 * Checked before pinning: the longest path in that stage is 122 characters and every path splits
 * inside ustar's 155 + 100 limit, so nothing in these trees needs pax.
 *
 * Compression is a separate step because the deterministic spelling is not portable. macOS tar is
 * bsdtar and takes `--options '!timestamp'`; Linux tar is GNU tar and rejects it, which is how this
 * first reached CI. `gzip -n` omits the name and timestamp on both, so the archive is built
 * uncompressed and compressed afterwards.
 */
async function packDeterministicArchive(destination, stage, timeout) {
  // `destination` is the caller's `.partial` path, so it does not end in `.gz`; gzip derives its
  // own output name and would otherwise land somewhere the caller never looks.
  const uncompressed = `${destination}.tar`;
  run(
    "/usr/bin/tar",
    ["--format", "ustar", "-cf", uncompressed, "-C", stage, "."],
    { timeout, env: { COPYFILE_DISABLE: "1" } },
  );
  run("/usr/bin/gzip", ["-n", uncompressed], { timeout });
  await rename(`${uncompressed}.gz`, destination);
}

function fail(cause) {
  throw new DesktopManagedReleaseError(cause);
}
