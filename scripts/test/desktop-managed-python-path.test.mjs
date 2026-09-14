/**
 * HG-28 regression. The managed bundle keeps the Hermes sources in `app/` and the launcher puts
 * that directory on `PYTHONPATH`. That is not enough: Hermes spawns its slash worker as a child of
 * `sys.executable`, and the environment factory in `tools/environments/local.py` deliberately
 * strips the Hermes repo root back out of the child's `PYTHONPATH`. In the bundle `app/` IS the
 * repo root, so the worker lost its only route to `tui_gateway` and every slash command — the model
 * switch among them — failed with `ModuleNotFoundError`.
 *
 * The fix is a `.pth` in the interpreter's own site-packages, which `site` processes on every
 * start of that interpreter and which no `PYTHONPATH` edit can remove. This test exercises exactly
 * that: a child interpreter with `PYTHONPATH` cleared must still import a package that lives only
 * in `app/` — and must NOT be able to without the `.pth`, or the test would pass for the wrong
 * reason.
 *
 * A venv stands in for the bundled interpreter: it reproduces the geometry the `.pth` depends on
 * (`sys.prefix` two levels below the bundle root, a real site-packages directory), without needing
 * a packaged Hermes release on the machine running the tests.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { chmod, mkdtemp, mkdir, writeFile, rm, readdir } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  managedSessionTokenReaderSource,
  managedSitePathLine,
  MANAGED_SITE_PATH_FILE,
} from "../lib/desktop-component-archives.mjs";

function findPython() {
  for (const candidate of ["python3.11", "python3"]) {
    const which = spawnSync("/usr/bin/which", [candidate], { encoding: "utf8" });
    if (which.status === 0 && which.stdout.trim()) return which.stdout.trim();
  }
  return null;
}

/** Build `<root>/{app/tui_gateway, runtime/site-packages, runtime/python}` with a real venv. */
async function makeBundle(t, python) {
  const root = await mkdtemp(path.join(tmpdir(), "hermes-managed-path-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, "app/tui_gateway"), { recursive: true });
  await writeFile(path.join(root, "app/tui_gateway/__init__.py"), "MARKER = 'from-app'\n");
  await mkdir(path.join(root, "runtime/site-packages"), { recursive: true });
  await writeFile(path.join(root, "runtime/site-packages/dependency.py"), "MARKER = 'from-deps'\n");

  const venv = path.join(root, "runtime/python");
  const created = spawnSync(python, ["-m", "venv", "--without-pip", venv], { encoding: "utf8" });
  assert.equal(created.status, 0, `venv creation failed: ${created.stderr}`);
  return { root, interpreter: path.join(venv, "bin/python3") };
}

/** Absolute path of the venv's site-packages (its name carries the interpreter's version). */
async function sitePackagesOf(root) {
  const lib = path.join(root, "runtime/python/lib");
  const [versioned] = await readdir(lib);
  return path.join(lib, versioned, "site-packages");
}

/**
 * Import `tui_gateway` the way the slash worker does: same interpreter, `-s`, and an environment
 * whose `PYTHONPATH` has been stripped — which is what upstream hands its child.
 */
function importWithStrippedPythonPath(interpreter, expression) {
  const env = { ...process.env };
  delete env.PYTHONPATH;
  env.PYTHONNOUSERSITE = "1";
  return spawnSync(interpreter, ["-s", "-c", expression], { encoding: "utf8", env });
}

const python = findPython();

test("a child with no PYTHONPATH imports the bundled Hermes sources through the .pth", async (t) => {
  if (!python) {
    t.skip("no python3 on this host — the managed bundle's import path cannot be exercised here");
    return;
  }
  const { root, interpreter } = await makeBundle(t, python);

  // Without the .pth this is exactly the HG-28 failure, so assert it first: if the bundle were
  // importable anyway, the test below would prove nothing.
  const before = importWithStrippedPythonPath(interpreter, "import tui_gateway");
  assert.notEqual(before.status, 0, "expected the unpatched bundle to fail, like the 0.3.0 release did");
  assert.match(before.stderr, /ModuleNotFoundError: No module named 'tui_gateway'/);

  await writeFile(path.join(await sitePackagesOf(root), MANAGED_SITE_PATH_FILE), managedSitePathLine());

  const after = importWithStrippedPythonPath(
    interpreter,
    "import tui_gateway, dependency; print(tui_gateway.MARKER, dependency.MARKER)",
  );
  assert.equal(after.status, 0, `expected the import to succeed, got: ${after.stderr}`);
  assert.equal(after.stdout.trim(), "from-app from-deps");
});

test("the .pth survives relocation, because it derives the root at run time", async (t) => {
  if (!python) {
    t.skip("no python3 on this host");
    return;
  }
  const { root, interpreter } = await makeBundle(t, python);
  await writeFile(path.join(await sitePackagesOf(root), MANAGED_SITE_PATH_FILE), managedSitePathLine());

  // The line must name no absolute path of its own: a baked-in root would break the moment the
  // Desktop extracted the release anywhere but the machine that built it.
  assert.equal(managedSitePathLine().includes(root), false);
  assert.equal(managedSitePathLine().includes("/Users/"), false);

  const result = importWithStrippedPythonPath(interpreter, "import tui_gateway; print(tui_gateway.__file__)");
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), path.join(root, "app/tui_gateway/__init__.py"));
});

test("a partially extracted bundle degrades instead of failing every interpreter start", async (t) => {
  if (!python) {
    t.skip("no python3 on this host");
    return;
  }
  const { root, interpreter } = await makeBundle(t, python);
  await writeFile(path.join(await sitePackagesOf(root), MANAGED_SITE_PATH_FILE), managedSitePathLine());
  await rm(path.join(root, "app"), { recursive: true, force: true });

  // A .pth that raised would break `python` itself, not just the missing import — the bundle would
  // go from "slash commands fail" to "nothing starts".
  const result = importWithStrippedPythonPath(interpreter, "print('interpreter still usable')");
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout.trim(), "interpreter still usable");
  assert.equal(result.stderr.includes("Error processing line"), false);
});

test("the private token reader accepts both supported token formats and rejects unsafe input", async (t) => {
  if (!python) {
    t.skip("no python3 on this host");
    return;
  }
  const root = await mkdtemp(path.join(tmpdir(), "hermes-managed-token-reader-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const reader = path.join(root, "read-private-session-token.py");
  const tokenFile = path.join(root, "session-token");
  await writeFile(reader, managedSessionTokenReaderSource(), { mode: 0o600 });

  for (const token of ["A".repeat(43), "a".repeat(64)]) {
    await writeFile(tokenFile, token, { mode: 0o600 });
    const result = spawnSync(python, ["-s", reader], {
      encoding: "utf8",
      env: { ...process.env, HERMES_SESSION_TOKEN_FILE: tokenFile },
    });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, token);
  }

  await writeFile(tokenFile, "A".repeat(64), { mode: 0o600 });
  let rejected = spawnSync(python, ["-s", reader], {
    encoding: "utf8",
    env: { ...process.env, HERMES_SESSION_TOKEN_FILE: tokenFile },
  });
  assert.equal(rejected.status, 78);
  assert.equal(rejected.stderr.trim(), "Hermes session token file is invalid");

  await writeFile(tokenFile, "a".repeat(64), { mode: 0o644 });
  await chmod(tokenFile, 0o644);
  rejected = spawnSync(python, ["-s", reader], {
    encoding: "utf8",
    env: { ...process.env, HERMES_SESSION_TOKEN_FILE: tokenFile },
  });
  assert.equal(rejected.status, 78);
  assert.equal(rejected.stdout, "");
});
