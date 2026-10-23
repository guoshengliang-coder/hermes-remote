/** GO-owned display projection v1. Original upstream rows and content remain available. */
export const GO_DISPLAY_SCHEMA = 1 as const;
export const COMPRESSION_HEADERS = [
  "[Your active task list was preserved across context compression]",
  "[Skills pruned during compression — reload before acting on these tasks]",
] as const;
export interface HermesDisplayProjection {
  schema: typeof GO_DISPLAY_SCHEMA;
  kind: string | null;
  body?: string;
}
export function projectHermesDisplay(row: Record<string, unknown>): HermesDisplayProjection {
  const explicit = typeof row.display_kind === "string" && row.display_kind ? row.display_kind : null;
  const base: HermesDisplayProjection = { schema: GO_DISPLAY_SCHEMA, kind: explicit };
  const text = row.display_content ?? row.content;
  if (explicit || row.role !== "user" || typeof text !== "string") return base;
  const positions = COMPRESSION_HEADERS.map(header => text.indexOf(header)).filter(index => index >= 0);
  if (!positions.length) return base;
  const body = text.slice(0, Math.min(...positions)).trimEnd();
  return body.trim()
    ? { ...base, body }
    : { ...base, kind: "compression", body: text };
}
