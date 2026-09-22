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
