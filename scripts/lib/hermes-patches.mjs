/**
 * The managed Hermes patch set.
 *
 * Decided 2026-09-20 (docs/MANAGED_HERMES_STRATEGY.md): the managed copy of Hermes carries a thin
 * set of patches, and every patch is submitted upstream in parallel. Upstream acceptance removes a
 * patch; upstream silence does not block a fix.
 *
 * Patches are applied to the **staged** Hermes tree during packaging. There is deliberately no
 * long-lived checkout of upstream carrying our changes — that is the thing that diverges quietly,
 * and it is what this arrangement exists to avoid.
 */
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import path from "node:path";

/** `NNN-short-name.patch`; the number fixes apply order, which a patch set needs. */
const PATCH_NAME = /^(\d{3})-[a-z0-9][a-z0-9-]*\.patch$/;

const REQUIRED_HEADERS = ["Upstream-Issue", "Why-upstream-will-not", "Read-side-only", "Added"];

/**
 * Files whose whole purpose is the shape of the database or the path into it.
 *
 * A patch may not touch these at all. Files that merely contain writes alongside reads are not
 * listed — `hermes_state_messages.py` is both, and the founding `SELECT *` patch is a legitimate
 * read-side change to it. Those are covered by [assertNoWriteStatements] instead.
 */
const SCHEMA_OWNING_FILES = [
  "hermes_state_common.py",
  "hermes_cli/session_schema_history.py",
];

/** Write verbs in a diff's own lines. See [assertReadSideOnly] for why this is mechanical. */
const WRITE_STATEMENT = /\b(INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|ALTER\s+TABLE|CREATE\s+TABLE|DROP\s+TABLE|CREATE\s+INDEX)\b/i;

export class HermesPatchError extends Error {
  constructor(technicalCause, detail) {
    super(technicalCause);
    this.technicalCause = technicalCause;
    this.detail = detail;
  }
}

function fail(cause, detail) {
  throw new HermesPatchError(cause, detail);
}

/**
 * Read, validate and order the patch set.
 *
 * Validation is not decoration. `Why-upstream-will-not` is the field that decides whether a patch
 * should exist: "inlining is free between local processes and not free over a relay" is a reason to
 * carry one indefinitely; "they have not got round to it" is a patch waiting on an issue, and it is
 * supposed to be deleted the moment upstream merges. A patch that cannot answer it is one nobody
 * will dare remove later.
 */
export async function loadHermesPatches(directory) {
  let names;
  try {
    names = await readdir(directory);
  } catch {
    return [];
  }
  const patchNames = names.filter((name) => name.endsWith(".patch")).sort();
  const seen = new Set();
  const patches = [];
  for (const name of patchNames) {
    const match = PATCH_NAME.exec(name);
    if (!match) fail("hermes_patch_name_invalid", name);
    if (seen.has(match[1])) fail("hermes_patch_order_duplicated", name);
    seen.add(match[1]);

    // Absolute: `git apply` runs with cwd inside the staged tree, so a relative patch path would
    // resolve against that tree instead of the repository.
    const file = path.resolve(directory, name);
    const text = await readFile(file, "utf8");
    const headers = parseHeaders(text, name);
    assertReadSideOnly(text, name);

    patches.push({
      name,
      order: Number(match[1]),
      path: file,
      sha256: createHash("sha256").update(text).digest("hex"),
      upstreamIssue: headers["Upstream-Issue"],
      whyUpstreamWillNot: headers["Why-upstream-will-not"],
      added: headers.Added,
    });
  }
  return patches;
}

function parseHeaders(text, name) {
  const headers = {};
  for (const line of text.split("\n")) {
    const match = /^([A-Za-z-]+):\s*(.+?)\s*$/.exec(line);
    if (match) headers[match[1]] = match[2];
    if (line.startsWith("diff ") || line.startsWith("--- ")) break;
  }
  for (const required of REQUIRED_HEADERS) {
    if (!headers[required]) fail("hermes_patch_header_missing", `${name}: ${required}`);
  }
  if (headers["Read-side-only"] !== "yes") fail("hermes_patch_not_read_side", name);
  if (!/^https:\/\/github\.com\/NousResearch\/hermes-agent\/(issues|pull)\/\d+$/.test(headers["Upstream-Issue"])) {
    fail("hermes_patch_upstream_issue_invalid", `${name}: ${headers["Upstream-Issue"]}`);
  }
  return headers;
}

/**
 * Refuse a patch that changes what is written rather than what is read.
 *
 * This is the rule the whole arrangement rests on, so it is checked by the machine rather than left
 * to whoever reviews the diff. The managed copy and the owner's own Hermes share one `state.db`.
 * Today they are two versions of one program; a patch makes them two different programs. A patch
 * that wrote a row only our copy understands would leave the owner's Hermes unable to read its own
 * database — the 2026-09-19 failure again, and harder to explain because no version number would
 * account for it.
 *
 * The check is deliberately strict: it looks at the diff's own added and removed lines, so a write
 * statement merely *near* the change is fine, and a patch that touches one is not. Strict is the
 * right direction here — a false positive costs an argument, a false negative costs the database.
 */
function assertReadSideOnly(text, name) {
  for (const line of text.split("\n")) {
    if (/^(\+\+\+|---)\s/.test(line)) {
      const file = line.slice(4).replace(/^[ab]\//, "").split("\t")[0].trim();
      if (file !== "/dev/null" && SCHEMA_OWNING_FILES.some((owned) => file.endsWith(owned))) {
        fail("hermes_patch_touches_schema", `${name}: ${file}`);
      }
      continue;
    }
    if (!/^[+-]/.test(line)) continue;
    if (WRITE_STATEMENT.test(line)) fail("hermes_patch_touches_writes", `${name}: ${line.trim()}`);
  }
}

/**
 * Apply the set to a staged Hermes tree, in order, refusing to continue at the first conflict.
 *
 * A conflict means upstream moved, and resolving it here would be resolving it invisibly. The
 * adoption gate in docs/MANAGED_HERMES_STRATEGY.md stops instead, so that each conflicted patch is
 * decided deliberately: upstream fixed it (delete), upstream moved the code (rewrite and say so in
 * the header), or upstream changed the behaviour (re-read the read-side rule first).
 */
export function applyHermesPatches(patches, appRoot) {
  const applied = [];
  for (const patch of patches) {
    // `git apply` runs with cwd inside the staged tree; `--directory .` would prefix every path
    // with `./`, which git rejects as invalid.
    const check = spawnSync("git", ["apply", "--check", "-p1", patch.path], {
      cwd: appRoot,
      encoding: "utf8",
    });
    if (check.status !== 0) {
      fail("hermes_patch_does_not_apply", `${patch.name}: ${(check.stderr || "").trim()}`);
    }
    const result = spawnSync("git", ["apply", "-p1", patch.path], { cwd: appRoot, encoding: "utf8" });
    if (result.status !== 0) {
      fail("hermes_patch_apply_failed", `${patch.name}: ${(result.stderr || "").trim()}`);
    }
    applied.push({ name: patch.name, sha256: patch.sha256, upstreamIssue: patch.upstreamIssue });
  }
  return applied;
}
