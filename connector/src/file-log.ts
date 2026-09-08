/**
 * Diagnostics for refused `GET /api/files` requests.
 *
 * A rejected download used to leave no trace anywhere: the phone showed one generic message and
 * `connector.log` said nothing, so diagnosing a 2026-09-05 failure meant reading `FILES_ROOT` by
 * hand. The path itself still must never be logged — `connector.log` ships with diagnostics, and
 * the path would carry the Mac's directory layout. The extension separates whitelist/type problems
 * from traversal ones, and the length catches truncation or encoding damage.
 */
export function describeRejectedPath(
  requestPath: string,
  rawQueryParameter: (path: string, name: string) => string | undefined,
): string {
  const raw = rawQueryParameter(requestPath, "path");
  if (raw === undefined || raw.length === 0) return "path=absent";
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw.replace(/\+/g, " "));
  } catch {
    return "path=undecodable";
  }
  const dot = decoded.lastIndexOf(".");
  const slash = Math.max(decoded.lastIndexOf("/"), decoded.lastIndexOf("\\"));
  const ext = dot > slash && dot >= 0 ? decoded.slice(dot).toLowerCase().slice(0, 12) : "none";
  return `ext=${ext} len=${decoded.length}`;
}
