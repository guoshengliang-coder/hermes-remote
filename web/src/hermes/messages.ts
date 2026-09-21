import { classifyAttachment, parseAttachments, type Attachment } from "./media";
import type { JsonObject, JsonValue, MessageContent, MessageRow, ToolCallWire } from "./types";

// `/api/sessions/{id}/messages` rows → display model. Port of android data/network/Dtos.kt
// (MessageContentSerializer), domain/Mappers.kt (MessageDto.toDomain, historyToolCalls) and
// data/repository/SessionRepository.kt (mapHistory). Not ported: compaction-carrier projection and
// the server-injected scaffolding notes (TimelineNote.kt) — the UI layer decides how to show those.

export type DisplayRole = "user" | "assistant" | "system";

export interface DisplayImage {
  /** Absolute Mac path (fetch through the device files route) … */
  path?: string;
  /** … or an http(s) URL from a content block. */
  url?: string;
}

export interface DisplayToolCall {
  id: string;
  /** Resolved name: the real target for Hermes' dynamic `tool_call` wrapper. */
  name: string;
  /** Raw arguments JSON text, when present. */
  arguments: string | null;
  /** The matching role=tool row's content ("" when no result row was persisted). */
  output: string;
  hasResult: boolean;
}

export interface DisplayMessage {
  /** Stable within one history load: `h-<index>-<row id>`. */
  key: string;
  rowId: number | null;
  role: DisplayRole;
  text: string;
  /** From block-list content (path / http(s) URL). */
  images: DisplayImage[];
  /** From MEDIA:/@image:/@file: grammar in the text. */
  attachments: Attachment[];
  timestampMs: number | null;
  reasoning: string;
  tools: DisplayToolCall[];
  displayKind: string | null;
  displayMetadata: JsonObject | null;
}

export const INTERNAL_TOOL_ROLES: ReadonlySet<string> = new Set(["tool", "function", "tool_result", "tool_call"]);
const REFERENCE_KEYS = ["url", "path", "file_path", "source"] as const;

function nonBlank(v: unknown): string | null {
  return typeof v === "string" && v.trim() !== "" ? v : null;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** A non-text block is kept only when it names something renderable: `/…` or `http(s)://…`. */
function blockImage(block: Record<string, unknown>): DisplayImage | null {
  let ref: string | null = null;
  for (const key of REFERENCE_KEYS) {
    ref = nonBlank(block[key]);
    if (ref) break;
  }
  if (!ref && isObject(block.image_url)) ref = nonBlank(block.image_url.url);
  if (!ref) return null;
  ref = ref.trim();
  if (ref.startsWith("/")) return { path: ref };
  if (ref.startsWith("http://") || ref.startsWith("https://")) return { url: ref };
  // Inline base64 `data:` payloads, `[image]` placeholders and unknown shapes are dropped.
  return null;
}

export interface FlattenedContent {
  text: string | null;
  images: DisplayImage[];
}

/** Accepts string, null, a block list or a single block; unknown block types are skipped. */
export function flattenContent(content: MessageContent | JsonValue | undefined): FlattenedContent {
  if (content === null || content === undefined) return { text: null, images: [] };
  if (typeof content === "string") return { text: content, images: [] };
  if (typeof content === "number" || typeof content === "boolean") return { text: String(content), images: [] };
  const blocks: unknown[] = Array.isArray(content) ? content : [content];
  const texts: string[] = [];
  const images: DisplayImage[] = [];
  for (const block of blocks) {
    if (typeof block === "string") {
      if (block.trim()) texts.push(block);
      continue;
    }
    if (!isObject(block)) continue;
    const text = nonBlank(block.text);
    if (text) {
      texts.push(text);
      continue;
    }
    const image = blockImage(block);
    if (image) images.push(image);
  }
  const joined = texts.join("\n");
  return { text: joined.trim() ? joined : null, images };
}

/** Hermes' `timestamp` (float seconds) wins; ISO `created_at` is a fallback. Non-positive = absent. */
export function messageTimestampMs(seconds: number | null | undefined, isoCreatedAt?: string | null): number | null {
  if (typeof seconds === "number" && seconds > 0) return Math.trunc(seconds * 1000);
  if (isoCreatedAt) {
    const hasZone = /(Z|[+-]\d{2}:?\d{2})$/i.test(isoCreatedAt);
    const ms = Date.parse(hasZone ? isoCreatedAt : `${isoCreatedAt}Z`);
    return Number.isNaN(ms) ? null : ms;
  }
  return null;
}

function parseToolCalls(raw: MessageRow["tool_calls"]): ToolCallWire[] {
  if (Array.isArray(raw)) return raw;
  if (typeof raw === "string") {
    try {
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed) ? (parsed as ToolCallWire[]) : [];
    } catch {
      return [];
    }
  }
  return [];
}

/** Hermes invokes dynamic (MCP) tools through a `tool_call` wrapper whose target is `arguments.name`. */
export function toolLabel(wrapperName: string, args: unknown): string {
  if (wrapperName !== "tool_call") return wrapperName;
  return (isObject(args) && nonBlank(args.name)) || wrapperName;
}

function historyToolCalls(row: MessageRow, results: Map<string, MessageRow>): DisplayToolCall[] {
  const out: DisplayToolCall[] = [];
  parseToolCalls(row.tool_calls).forEach((call, index) => {
    if (!isObject(call)) return;
    const fn = isObject(call.function) ? call.function : null;
    const wrapper = nonBlank(fn?.name);
    if (!wrapper) return;
    const id = nonBlank(call.id) ?? `h-tool-${index}`;
    const rawArgs = fn?.arguments;
    const argText = typeof rawArgs === "string" ? rawArgs : isObject(rawArgs) ? JSON.stringify(rawArgs) : null;
    let argObj: unknown = isObject(rawArgs) ? rawArgs : null;
    if (typeof rawArgs === "string") {
      try {
        argObj = JSON.parse(rawArgs);
      } catch {
        argObj = null;
      }
    }
    const result = results.get(id);
    const output = result ? flattenContent(result.content).text ?? "" : "";
    out.push({ id, name: toolLabel(wrapper, argObj), arguments: argText, output, hasResult: output.trim() !== "" });
  });
  return out;
}

function roleOf(role: string): DisplayRole {
  const r = role.toLowerCase();
  return r === "user" ? "user" : r === "assistant" ? "assistant" : "system";
}

export function isRenderable(m: DisplayMessage): boolean {
  return (
    m.text.trim() !== "" ||
    m.images.length > 0 ||
    m.attachments.length > 0 ||
    m.tools.length > 0 ||
    m.reasoning.trim() !== "" ||
    m.displayKind !== null
  );
}

export function toDisplayMessage(row: MessageRow, index: number, toolResults: Map<string, MessageRow> = new Map()): DisplayMessage {
  const flat = flattenContent(row.content);
  const parsed = parseAttachments(flat.text ?? "");
  const assistant = row.role.toLowerCase() === "assistant";
  const rowId = typeof row.id === "number" ? row.id : null;
  return {
    key: `h-${index}-${rowId ?? "x"}`,
    rowId,
    role: roleOf(row.role),
    text: parsed.text,
    images: flat.images,
    attachments: parsed.attachments,
    timestampMs: messageTimestampMs(row.timestamp, row.created_at),
    reasoning: assistant ? nonBlank(row.reasoning_content) ?? nonBlank(row.reasoning) ?? "" : "",
    tools: assistant ? historyToolCalls(row, toolResults) : [],
    displayKind: nonBlank(row.display_kind),
    displayMetadata: isObject(row.display_metadata) ? (row.display_metadata as JsonObject) : null,
  };
}

/**
 * Tool-result rows never become turns of their own; they are joined onto the assistant turn's
 * tool calls by `tool_call_id` (grouped for a collapsible display). Keys are assigned before the
 * renderable filter so they stay stable.
 */
export function parseHistory(rows: readonly MessageRow[]): DisplayMessage[] {
  const toolResults = new Map<string, MessageRow>();
  for (const row of rows) {
    if (INTERNAL_TOOL_ROLES.has(row.role.toLowerCase()) && nonBlank(row.tool_call_id)) toolResults.set(row.tool_call_id!, row);
  }
  return rows
    .filter((row) => !INTERNAL_TOOL_ROLES.has(row.role.toLowerCase()))
    .map((row, i) => toDisplayMessage(row, i, toolResults))
    .filter(isRenderable);
}

/** Block images as attachments, for UIs that render one attachment list. */
export function blockImagesAsAttachments(images: readonly DisplayImage[]): Attachment[] {
  return images.flatMap((img) => (img.path ? [classifyAttachment(img.path)] : []));
}
