import { isAbsolute, resolve } from "node:path";

export type ConnectorMode = "legacy" | "account";

/**
 * Which Hermes backend the Connector talks to. `"mock"` echoes commands back and keeps the
 * session observer off; any other value is live.
 *
 * An explicit HERMES_MODE always wins. Unset, a legacy Connector stays on the historical `mock`
 * default the dev setup relies on, but an account Connector defaults to `live`: account mode needs
 * a signed credential, the Gateway /v2 handshake and a real Hermes session token, so a mock one is
 * never what was meant. The managed Desktop LaunchAgent shipped without HERMES_MODE, and that
 * default silently disabled every lifecycle event and push (HG-101).
 */
export function resolveHermesMode(
  env: Readonly<Record<string, string | undefined>>,
  connectorMode: ConnectorMode,
): string {
  const explicit = env.HERMES_MODE?.trim();
  if (explicit) return explicit;
  return connectorMode === "account" ? "live" : "mock";
}

/**
 * Additional read-only attachment roots (HG-245): `EXTRA_READ_ROOTS` is a semicolon-separated
 * list of absolute directory paths, each an explicitly authorized external volume mount point.
 * The primary `FILES_ROOT` keeps its role (uploads, thumbnails, and its existing boundary);
 * these extra roots only widen authenticated attachment reads, never writes.
 *
 * A syntactically invalid entry aborts startup: a misparsed grant must fail loudly rather than
 * silently narrow or shift the read boundary. A root that merely is not mounted yet is not a
 * configuration error — it is disabled at runtime and re-checked on the next Connector start.
 */
export function resolveExternalReadRoots(env: Readonly<Record<string, string | undefined>>): string[] {
  const raw = env.EXTRA_READ_ROOTS?.trim();
  if (!raw) return [];
  const roots: string[] = [];
  for (const segment of raw.split(";")) {
    const trimmed = segment.trim();
    if (!trimmed) continue;
    if (!isAbsolute(trimmed)) {
      throw new Error(`EXTRA_READ_ROOTS entries must be absolute paths, got: ${trimmed}`);
    }
    const resolved = resolve(trimmed);
    if (!roots.includes(resolved)) roots.push(resolved);
  }
  return roots;
}
