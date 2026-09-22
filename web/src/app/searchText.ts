// Search result text rules (Android ui/sessions/SearchText.kt): where the query occurs, and a
// snippet cut so the first occurrence stays visible in a two-line row.

/** Case-insensitive, non-overlapping occurrences as [start, end) pairs; empty for a blank query. */
export function highlightRanges(text: string, query: string): [number, number][] {
  const q = query.trim().toLowerCase();
  if (!q || !text) return [];
  const lower = text.toLowerCase();
  const out: [number, number][] = [];
  let from = 0;
  while (from <= lower.length - q.length) {
    const at = lower.indexOf(q, from);
    if (at < 0) break;
    out.push([at, at + q.length]);
    from = at + q.length;
  }
  return out;
}

/** A window of ±`context` characters around the first match, whitespace collapsed, with ellipses. */
export function centerSnippet(raw: string | null | undefined, query: string, context = 40): string {
  const text = (raw ?? "").replace(/\s+/g, " ").trim();
  if (!text) return "";
  const q = query.trim();
  const at = q ? text.toLowerCase().indexOf(q.toLowerCase()) : -1;
  const start = at < 0 ? 0 : Math.max(0, at - context);
  const end = at < 0 ? Math.min(text.length, 2 * context + q.length) : Math.min(text.length, at + q.length + context);
  const core = text.slice(start, end).trim();
  return `${start > 0 && !core.startsWith("…") ? "…" : ""}${core}${end < text.length && !core.endsWith("…") ? "…" : ""}`;
}

export function titleMatches(title: string, projectLabel: string | null, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return false;
  return title.toLowerCase().includes(q) || (projectLabel?.toLowerCase().includes(q) ?? false);
}
