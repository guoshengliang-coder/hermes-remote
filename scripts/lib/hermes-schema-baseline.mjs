/**
 * The database shape the pinned Hermes was built to read.
 *
 * Captured at package time from the Hermes source and recorded in the component's
 * `BUILD-IDENTITY.json`, so Desktop can later notice that the live `state.db` has moved past the
 * managed copy — see `docs/MANAGED_HERMES_STRATEGY.md` for why that happens by construction.
 *
 * **Not the `schema_version` number.** The obvious gate would compare
 * `hermes_state_common.SCHEMA_VERSION` against the `schema_version` row, and it would not have
 * caught the incident it exists for: upstream added `display_identity` and `display_order` to
 * `messages` while leaving `SCHEMA_VERSION = 30` on both sides. Columns are what actually differ,
 * so columns are what this records.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

/**
 * Tables whose shape reaching the API matters.
 *
 * Deliberately not every table. The point is a signal the owner can act on, not an inventory —
 * and each table listed here is one whose rows are read with `SELECT *` and handed towards a
 * response somewhere in upstream (`messages` and `sessions` both are).
 */
const WATCHED_TABLES = ["messages", "sessions"];

/** `CREATE TABLE IF NOT EXISTS <name> ( ... );` in `hermes_state_common.py`'s `SCHEMA_SQL`. */
function extractColumns(sql, table) {
  const start = new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\s*\\(`, "i").exec(sql);
  if (!start) return null;
  let depth = 1;
  let index = start.index + start[0].length;
  const body = [];
  while (index < sql.length && depth > 0) {
    const ch = sql[index];
    if (ch === "(") depth += 1;
    else if (ch === ")") depth -= 1;
    if (depth > 0) body.push(ch);
    index += 1;
  }
  if (depth !== 0) return null;

  const columns = [];
  let nesting = 0;
  let current = "";
  for (const ch of body.join("")) {
    if (ch === "(") nesting += 1;
    if (ch === ")") nesting -= 1;
    if (ch === "," && nesting === 0) {
      columns.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  columns.push(current);

  const names = [];
  for (const entry of columns) {
    const line = entry.trim().split("\n")[0].trim();
    if (!line) continue;
    // Table-level constraints are not columns.
    if (/^(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT)\b/i.test(line)) continue;
    const name = /^"?([A-Za-z_][A-Za-z0-9_]*)"?\b/.exec(line);
    if (name) names.push(name[1]);
  }
  return names.length > 0 ? names : null;
}

/**
 * Read the pinned Hermes source and return `{ table: [column, ...] }` for the watched tables.
 *
 * Returns null for a table it cannot parse rather than guessing: a baseline that silently lost a
 * table would report every one of its columns as new the first time Desktop looked.
 */
export async function readHermesSchemaBaseline(hermesRoot) {
  const source = await readFile(path.join(hermesRoot, "hermes_state_common.py"), "utf8");
  const baseline = {};
  for (const table of WATCHED_TABLES) {
    const columns = extractColumns(source, table);
    if (columns) baseline[table] = columns;
  }
  return baseline;
}

/**
 * Which live columns the baseline does not account for.
 *
 * A column the managed Hermes has never heard of is the shape that broke it: unknown columns travel
 * through `SELECT *` into the API's response encoder. A column the baseline has and the database
 * lacks is the opposite direction and not this check's business — the managed copy would simply
 * read nothing there.
 */
export function unknownColumns(baseline, live) {
  const findings = {};
  for (const [table, known] of Object.entries(baseline)) {
    const present = live[table];
    if (!present) continue;
    const extra = present.filter((column) => !known.includes(column));
    if (extra.length > 0) findings[table] = extra;
  }
  return findings;
}
