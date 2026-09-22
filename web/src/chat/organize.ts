// Assistant/user text organisation for display — a port of android ui/chat/ChatUiState.kt
// (normalizeDisplayPayload, organizeAssistantContent, stabilizeStreamingMarkdown), ui/chat/
// SemanticCards.kt (parseToolPayloadMeta, groupToolsForDisplay, runningStatusFor) and ui/chat/
// TimelineNote.kt. Pure functions: the reducer and the view both call them.
//
// Hermes flattens tool payloads into prose, wraps tool results in `<untrusted_tool_result>` for the
// model's safety boundary, staples compression and attachment scaffolding to user turns, and
// injects system turns as role=user. None of that is something a person wrote or wants to read.

export interface TodoItem {
  content: string;
  status: string;
}

/** Synthesised tool cards carry a label key; the view localises it. */
export type ToolLabelKey = "terminal" | "tool" | "web-search" | "browser" | "details" | "file-mutation";

export interface ToolCard {
  id: string;
  name: string;
  labelKey?: ToolLabelKey;
  output: string;
  done: boolean;
  command?: string | null;
  exitCode?: number | null;
  durationMs?: number | null;
  todos?: TodoItem[];
}

// ---- payload metadata ---------------------------------------------------------------------

export interface ToolPayloadMeta {
  command: string | null;
  exitCode: number | null;
  durationMs: number | null;
  outputBody: string | null;
  todos: TodoItem[];
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Recognise the command-execution / todo shapes; null for anything else (never make it worse). */
export function parseToolPayloadMeta(raw: string): ToolPayloadMeta | null {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{")) return null;
  const obj = parseJson(trimmed);
  if (!isObject(obj)) return null;
  const str = (...keys: string[]) => {
    for (const k of keys) {
      const v = obj[k];
      if ((typeof v === "string" && v.trim()) || typeof v === "number" || typeof v === "boolean") return String(v);
    }
    return null;
  };
  const int = (...keys: string[]) => {
    for (const k of keys) {
      const v = obj[k];
      if (typeof v === "number" && Number.isFinite(v)) return Math.trunc(v);
      if (typeof v === "string" && /^-?\d+$/.test(v.trim())) return Number(v.trim());
    }
    return null;
  };
  const todos: TodoItem[] = Array.isArray(obj.todos)
    ? obj.todos.flatMap((item) => {
        if (!isObject(item) || typeof item.content !== "string" || !item.content.trim()) return [];
        return [{ content: item.content, status: typeof item.status === "string" ? item.status.toLowerCase() : "pending" }];
      })
    : [];
  const command = str("command", "cmd");
  const outputBody = str("output", "stdout", "result_text");
  const exitCode = int("exit_code", "exitCode");
  const durationMs = int("duration_ms", "durationMs");
  if (command === null && exitCode === null && outputBody === null && todos.length === 0) return null;
  return { command, exitCode, durationMs, outputBody, todos };
}

/** `{"output":"line\nline"}` → the text; other JSON is pretty-printed; non-JSON is unchanged. */
export function normalizeDisplayPayload(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return raw;
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith('"')) return raw;
  const parsed = parseJson(trimmed);
  if (parsed === undefined) return raw;
  const unwrap = (element: unknown, depth = 0): string | null => {
    if (depth > 2) return null;
    if (typeof element === "string") return element;
    if (isObject(element)) {
      for (const key of ["output", "result", "content", "text"]) {
        if (!(key in element)) continue;
        const found = unwrap(element[key], depth + 1);
        if (found !== null) return found;
      }
    }
    return null;
  };
  return unwrap(parsed) ?? JSON.stringify(parsed, null, 2);
}

/** A finished tool's card fields from its raw result payload. */
export function completeTool(tool: ToolCard, rawResult: string | null): ToolCard {
  const meta = rawResult ? parseToolPayloadMeta(rawResult) : null;
  return {
    ...tool,
    done: true,
    output: rawResult ? normalizeDisplayPayload(rawResult) : "",
    command: meta?.command ?? tool.command ?? null,
    exitCode: meta?.exitCode ?? null,
    durationMs: meta?.durationMs ?? null,
    todos: meta?.todos ?? [],
  };
}

export function toolFailed(tool: ToolCard): boolean {
  return tool.done && (tool.exitCode ?? 0) !== 0;
}

// ---- assistant prose organisation ---------------------------------------------------------

const EXTRA_BLANK_LINES = /\n[ \t]*\n(?:[ \t]*\n)+/g;
const FILE_MUTATION_HEADER = /^[ \t]*File-mutation verifier[ \t]*:[ \t]*/im;
const UNIX_ABS_PATH = /(?<![A-Za-z0-9_])\/(?:[^\s,;:()]+\/)*[^\s,;:()]+/g;
const WINDOWS_ABS_PATH = /\b[A-Z]:\\(?:[^\s,;:()]+\\)*[^\s,;:()]+/gi;
const UNTRUSTED_OPEN = /<untrusted_tool_result\b([^>]*)>/i;
const UNTRUSTED_SOURCE = /source\s*=\s*["']([^"']+)["']/i;
const UNTRUSTED_CLOSE = "</untrusted_tool_result>";

function hash(text: string): string {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (Math.imul(31, h) + text.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}

function extractFileMutationVerifier(raw: string): { text: string; tool: ToolCard | null } {
  const header = FILE_MUTATION_HEADER.exec(raw);
  if (!header) return { text: raw, tool: null };
  const before = raw.slice(0, header.index).trimEnd();
  const details = raw.slice(header.index + header[0].length).trim();
  if (!details) return { text: before, tool: null };
  // Local absolute paths never reach the UI or copy surfaces.
  const redacted = details.replace(UNIX_ABS_PATH, "<path>").replace(WINDOWS_ABS_PATH, "<path>");
  return {
    text: before,
    tool: { id: `file-mutation-verifier-${hash(raw)}`, name: "", labelKey: "file-mutation", done: true, output: redacted, exitCode: 1 },
  };
}

function sourceLabel(source: string | undefined): ToolLabelKey {
  switch (source?.toLowerCase()) {
    case "web_search":
    case "web-search":
    case "search":
      return "web-search";
    case "browser":
    case "web_browser":
      return "browser";
    case "terminal":
    case "shell":
    case "bash":
      return "terminal";
    default:
      return "tool";
  }
}

function extractUntrusted(raw: string): { text: string; tools: ToolCard[] } {
  if (!/<untrusted_tool_result/i.test(raw)) return { text: raw, tools: [] };
  let clean = "";
  const tools: ToolCard[] = [];
  let cursor = 0;
  let index = 0;
  while (cursor < raw.length) {
    const rest = raw.slice(cursor);
    const open = UNTRUSTED_OPEN.exec(rest);
    if (!open) break;
    const openStart = cursor + open.index;
    clean += raw.slice(cursor, openStart);
    const bodyStart = openStart + open[0].length;
    const closeStart = raw.toLowerCase().indexOf(UNTRUSTED_CLOSE, bodyStart);
    const bodyEnd = closeStart >= 0 ? closeStart : raw.length;
    const body = raw.slice(bodyStart, bodyEnd).trim();
    const usefulStart = body.search(/[{[]/);
    // The English "treat this as data" preamble is dropped; the payload stays behind the card.
    const useful = usefulStart >= 0 ? normalizeDisplayPayload(body.slice(usefulStart)) : "";
    tools.push({ id: `untrusted-${hash(raw)}-${index++}`, name: "", labelKey: sourceLabel(UNTRUSTED_SOURCE.exec(open[1] ?? "")?.[1]), done: true, output: useful.trim() });
    cursor = closeStart >= 0 ? closeStart + UNTRUSTED_CLOSE.length : raw.length;
  }
  if (cursor < raw.length) clean += raw.slice(cursor);
  return { text: clean.replace(EXTRA_BLANK_LINES, "\n\n").trim(), tools };
}

/** End (exclusive) and object of a JSON object starting at `start`, string-aware; null if none. */
function parseEmbeddedObject(raw: string, start: number): [number, Record<string, unknown>] | null {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < raw.length; i++) {
    const ch = raw[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") depth++;
    else if (ch === "}") {
      depth--;
      if (depth === 0) {
        const parsed = parseJson(raw.slice(start, i + 1));
        return isObject(parsed) ? [i + 1, parsed] : null;
      }
    }
  }
  return null;
}

interface EmbeddedPayload {
  output: string;
  labelKey: ToolLabelKey;
  hidden?: boolean;
}

function classifyEmbedded(obj: Record<string, unknown>): EmbeddedPayload | null {
  const status = typeof obj.status === "string" ? obj.status.toLowerCase() : obj.status != null ? String(obj.status).toLowerCase() : null;
  const error = typeof obj.error === "string" ? obj.error : "";
  for (const key of ["output", "result", "content", "text"]) {
    if (!(key in obj)) continue;
    const wrapped = normalizeDisplayPayload(JSON.stringify(obj[key]));
    const terminal = /github\.com/i.test(wrapped) || /Token scopes/i.test(wrapped);
    return { output: wrapped.trim(), labelKey: terminal ? "terminal" : "tool" };
  }
  // A background-process poll's "not_found" after the command ended is an implementation detail.
  if (status === "not_found" || /No process with ID/i.test(error)) return { output: "", labelKey: "details", hidden: true };
  if (status !== null || error.trim()) return { output: JSON.stringify(obj, null, 2), labelKey: "details" };
  return null;
}

/** A short paragraph right before a payload is narration: cut it and reuse it as the card label. */
function detachNarration(text: string): [string, string | null] {
  const trimmedEnd = text.trimEnd();
  if (!trimmedEnd.trim()) return ["", null];
  const split = trimmedEnd.lastIndexOf("\n\n");
  const paragraph = trimmedEnd.slice(split + 2).trim();
  const narration =
    paragraph.length >= 2 && paragraph.length <= 80 && !paragraph.includes("```") && !/^[#\-•]/.test(paragraph);
  if (!narration) return [text, null];
  const kept = split >= 0 ? trimmedEnd.slice(0, split).trimEnd() : "";
  return [kept, paragraph.replace(/[。.：:]+$/, "").slice(0, 28)];
}

/** Pull embedded payloads, untrusted wrappers and the verifier footer out of assistant prose. */
export function organizeAssistant(raw: string, existing: readonly ToolCard[] = []): { text: string; tools: ToolCard[] } {
  const verifier = extractFileMutationVerifier(raw);
  const untrusted = extractUntrusted(verifier.text);
  const display = untrusted.text;
  const tools = [...existing];
  if (verifier.tool && !tools.some((t) => t.id === verifier.tool!.id)) tools.push(verifier.tool);
  for (const t of untrusted.tools) if (!tools.some((x) => x.output.trim() === t.output.trim() && x.labelKey === t.labelKey)) tools.push(t);

  if (!display.includes("{")) return { text: normalizeDisplayPayload(display).trim(), tools };

  let prose = "";
  let cursor = 0;
  let searchFrom = 0;
  let extracted = 0;
  while (searchFrom < display.length) {
    const start = display.indexOf("{", searchFrom);
    if (start < 0) break;
    const match = parseEmbeddedObject(display, start);
    if (!match) {
      searchFrom = start + 1;
      continue;
    }
    const [end, obj] = match;
    const embedded = classifyEmbedded(obj);
    if (!embedded) {
      searchFrom = end;
      continue;
    }
    const [kept, narration] = detachNarration(display.slice(cursor, start));
    prose += kept;
    if (!embedded.hidden && embedded.output.trim() && !tools.some((t) => t.output.trim() === embedded.output.trim())) {
      const meta = parseToolPayloadMeta(JSON.stringify(obj));
      tools.push({
        id: `embedded-${hash(display)}-${extracted++}`,
        name: narration ?? "",
        ...(narration ? {} : { labelKey: embedded.labelKey }),
        done: true,
        output: embedded.output,
        command: meta?.command ?? null,
        exitCode: meta?.exitCode ?? null,
        durationMs: meta?.durationMs ?? null,
      });
    }
    cursor = end;
    searchFrom = end;
  }
  if (cursor === 0) return { text: normalizeDisplayPayload(display).trim(), tools };
  prose += display.slice(cursor);
  return { text: prose.replace(EXTRA_BLANK_LINES, "\n\n").trim(), tools };
}

// ---- streaming stabiliser -----------------------------------------------------------------

const FENCE_LINE = /^\s*```/gm;
const STREAMED_JSON_LINE_START = /^\s*\{\s*"/gm;

function closeOpenFence(text: string): string {
  return (text.match(FENCE_LINE)?.length ?? 0) % 2 === 1 ? `${text}\n\`\`\`` : text;
}

/** Index of a trailing, still-unbalanced `{"…` payload (string-aware, so monotone), or −1. */
function trailingUnbalancedJson(text: string): number {
  let start = -1;
  for (const m of text.matchAll(STREAMED_JSON_LINE_START)) start = m.index ?? -1;
  if (start < 0) return -1;
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = start; i < text.length; i++) {
    const c = text[i];
    if (escaped) escaped = false;
    else if (inString) {
      if (c === "\\") escaped = true;
      else if (c === '"') inString = false;
    } else if (c === '"') inString = true;
    else if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return -1;
    }
  }
  return start;
}

/**
 * A streaming snapshot safe to render: an unclosed untrusted wrapper or trailing JSON payload is
 * masked behind `placeholder`, and an odd code fence is closed, so earlier lines never restyle.
 */
export function stabilizeStreaming(raw: string, placeholder: string): string {
  const mark = (text: string) => (placeholder ? `${text.trimEnd()}\n\n*${placeholder}*` : text);
  const wrapper = raw.toLowerCase().lastIndexOf("<untrusted_tool_result");
  if (wrapper >= 0 && raw.toLowerCase().indexOf(UNTRUSTED_CLOSE, wrapper) < 0) return mark(closeOpenFence(raw.slice(0, wrapper)));
  const json = trailingUnbalancedJson(raw);
  if (json >= 0) return mark(closeOpenFence(raw.slice(0, json)));
  return closeOpenFence(raw);
}

// ---- tool grouping and running status -----------------------------------------------------

export type ToolGroup = { kind: "single"; tool: ToolCard } | { kind: "timeline"; tools: ToolCard[] };

/** Runs of ≥2 consecutive tools become one timeline; a todo card always stands alone. */
export function groupTools(tools: readonly ToolCard[], threshold = 2): ToolGroup[] {
  const groups: ToolGroup[] = [];
  let run: ToolCard[] = [];
  const flush = () => {
    if (run.length >= threshold) groups.push({ kind: "timeline", tools: run });
    else for (const tool of run) groups.push({ kind: "single", tool });
    run = [];
  };
  for (const tool of tools) {
    if (tool.todos?.length) {
      flush();
      groups.push({ kind: "single", tool });
    } else run.push(tool);
  }
  flush();
  return groups;
}

export function formatToolDuration(ms: number): string {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

/** "12秒" / "1分24秒" (or "12s" / "1m24s"). */
export function formatElapsed(ms: number, zh: boolean): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  if (m <= 0) return zh ? `${s}秒` : `${s}s`;
  return zh ? `${m}分${s}秒` : `${m}m${s}s`;
}

/** Below this a wait is ordinary and says nothing (HG-56). */
export const RUN_WAIT_ELAPSED_AFTER_MS = 5000;

export type RunningStatus = { kind: "tool"; label: string } | { kind: "thinking"; preview: string } | { kind: "generating" };

const THINKING_PREVIEW_CHARS = 24;

export function runningStatus(item: { tools: readonly ToolCard[]; text: string; reasoning: string }): RunningStatus {
  const tool = [...item.tools].reverse().find((t) => !t.done);
  if (tool) {
    const label = tool.command?.trim() ? tool.command : tool.name;
    return { kind: "tool", label: (label.split("\n")[0] ?? "").slice(0, 64) };
  }
  if (!item.text.trim() && item.reasoning.trim()) {
    const lines = item.reasoning.trimEnd().split("\n").filter((l) => l.trim());
    const line = (lines[lines.length - 1] ?? "").trim();
    if (line) {
      const chars = Array.from(line);
      return { kind: "thinking", preview: chars.length > THINKING_PREVIEW_CHARS ? `…${chars.slice(-THINKING_PREVIEW_CHARS).join("")}` : line };
    }
  }
  return { kind: "generating" };
}

/** A todo item after the turn ended: `in_progress` reads as pending, never failed (HG-16). */
export function settledTodoStatus(status: string, turnCompleted: boolean): string {
  return turnCompleted && status === "in_progress" ? "pending" : status;
}

export function todoProgress(todos: readonly TodoItem[]): [number, number] {
  let done = 0;
  let total = 0;
  for (const t of todos) {
    if (t.status === "cancelled") continue;
    total++;
    if (t.status === "completed") done++;
  }
  return [done, total];
}

// ---- diff ---------------------------------------------------------------------------------

export type DiffKind = "add" | "del" | "hunk" | "context";

export function looksLikeDiff(code: string, language: string | null): boolean {
  if (language && ["diff", "patch"].includes(language.trim().toLowerCase())) return true;
  const lines = code.split("\n").filter((l) => l.trim());
  if (lines.length < 2) return false;
  const add = lines.filter((l) => l.startsWith("+") && !l.startsWith("+++")).length;
  const del = lines.filter((l) => l.startsWith("-") && !l.startsWith("---")).length;
  if (add === 0 || del === 0) return false;
  return (add + del) * 100 >= lines.length * 30;
}

export function diffKind(line: string): DiffKind {
  if (line.startsWith("@@") || line.startsWith("+++") || line.startsWith("---")) return "hunk";
  if (line.startsWith("+")) return "add";
  if (line.startsWith("-")) return "del";
  return "context";
}

// ---- user-turn scaffolding and timeline notes ---------------------------------------------

/** Copied from upstream `tools/todo_tool.py` TODO_INJECTION_HEADER (docs/HERMES_CONTRACT.md). */
export const COMPRESSION_SNAPSHOT_HEADER = "[Your active task list was preserved across context compression]";

export function withoutCompressionScaffolding(text: string): string {
  const i = text.indexOf(COMPRESSION_SNAPSHOT_HEADER);
  return i < 0 ? text : text.slice(0, i).trimEnd();
}

// Upstream `gateway/run_inbound.py` / `gateway/run.py` attachment notes (HERMES_CONTRACT §4b).
const ATTACHMENT_PATH_NOTE = /\[The user sent [^[\]]{0,500}?saved at:[^[\]]{0,2000}?]/g;
const ATTACHMENT_URL_PLACEHOLDER = /\[User sent (?:an image|audio|a video|a file): [^[\]\r\n]{0,2000}?]/g;
const ATTACHMENT_VOICE_NOTE = /\[The user sent a voice message: \/[^[\]\r\n]{0,2000}?]/g;
const ATTACHMENT_BARE_PLACEHOLDER = /^[ \t]*\[(?:screenshot|image|photo|picture|attachment|file|document|audio|video)]\s*$/gim;

export function withoutAttachmentScaffolding(text: string): string {
  const mayCarryNote = /sent an?/i.test(text);
  if (!mayCarryNote && !text.includes("[")) return text;
  return [ATTACHMENT_PATH_NOTE, ATTACHMENT_URL_PLACEHOLDER, ATTACHMENT_VOICE_NOTE, ATTACHMENT_BARE_PLACEHOLDER]
    .reduce((carried, pattern) => carried.replace(pattern, ""), text)
    .split("\n")
    .map((l) => l.trimEnd())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/** A user turn's text as shown: scaffolding cut; a turn that was only scaffolding keeps its text. */
export function organizeUserText(text: string): string {
  const stripped = withoutAttachmentScaffolding(withoutCompressionScaffolding(text));
  return stripped.trim() ? stripped : text;
}

export interface TimelineNote {
  glyph: string;
  zh: string;
  en: string;
  expandable: boolean;
  hidden?: boolean;
}

// Model names carry dots (gpt-5.6-sol): capture up to " via …", the sentence end, or "]".
const MODEL_SWITCH_NAME = /changed to (\S+?)(?: via |\. |\.]|$)/;

function metaInt(meta: Record<string, unknown> | null, key: string): number | null {
  const v = meta?.[key];
  return typeof v === "number" && Number.isFinite(v) ? Math.trunc(v) : null;
}

/** A server-injected system turn → a one-line note; null for a real conversation turn. */
export function timelineNoteFor(message: {
  role: "user" | "assistant" | "system";
  text: string;
  displayKind?: string | null;
  displayMetadata?: Record<string, unknown> | null;
}): TimelineNote | null {
  const kind = message.displayKind;
  if (kind) {
    switch (kind) {
      case "hidden":
        return { glyph: "", zh: "", en: "", expandable: false, hidden: true };
      case "async_delegation_complete": {
        const n = metaInt(message.displayMetadata ?? null, "task_count");
        const failed = metaInt(message.displayMetadata ?? null, "failed_count") ?? 0;
        const zhBase = n !== null ? `${n} 个后台子任务已完成` : "后台子任务已完成";
        const enBase = n !== null ? `${n} background task${n === 1 ? "" : "s"} finished` : "Background tasks finished";
        return { glyph: "⚙", zh: failed > 0 ? `${zhBase}，${failed} 个失败` : zhBase, en: failed > 0 ? `${enBase}, ${failed} failed` : enBase, expandable: true };
      }
      case "model_switch": {
        const model = MODEL_SWITCH_NAME.exec(message.text)?.[1];
        const suffix = model ? ` · ${model}` : "";
        return { glyph: "⇄", zh: `已切换模型${suffix}`, en: `Model switched${suffix}`, expandable: false };
      }
      case "personality_switch":
        return { glyph: "◐", zh: "已切换人格", en: "Personality changed", expandable: false };
      case "auto_continue":
        return { glyph: "↻", zh: "已从中断处继续", en: "Resumed interrupted turn", expandable: false };
      default:
        // Forward compatibility: any future marker is a quiet note, never a bubble.
        return { glyph: "ℹ", zh: "系统备注", en: "System note", expandable: true };
    }
  }
  if (message.role !== "user") return null;
  const text = message.text;
  if (text.startsWith("[ASYNC DELEGATION")) return { glyph: "⚙", zh: "后台子任务已完成", en: "Background tasks finished", expandable: true };
  if (text.startsWith("[IMPORTANT: Background process")) return { glyph: "⚙", zh: "后台进程通报", en: "Background process report", expandable: true };
  if (text.includes(COMPRESSION_SNAPSHOT_HEADER) && !withoutCompressionScaffolding(text).trim()) {
    return { glyph: "⧉", zh: "上下文已压缩", en: "Context compressed", expandable: true };
  }
  return null;
}
