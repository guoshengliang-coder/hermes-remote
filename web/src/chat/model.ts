import type { AppError } from "../errors";
import { parseAttachments, type Attachment } from "../hermes/media";
import { parseHistory, type DisplayImage } from "../hermes/messages";
import {
  initialQuestionState,
  reduceQuestions,
  type QuestionAction,
  type QuestionState,
} from "../hermes/requests";
import type { JsonObject, MessageRow, OpenRequestsSnapshot, ServerEvent, ServerRequest } from "../hermes/types";
import {
  completeTool,
  organizeAssistant,
  organizeUserText,
  parseToolPayloadMeta,
  timelineNoteFor,
  type TimelineNote,
  type ToolCard,
} from "./organize";
import { mergeOlder, mergeTail } from "./history";

// Chat view model as a pure reducer. Live streaming follows android ui/chat/ChatUiState.kt
// (`applyEvent`): message.start/delta/complete, reasoning.delta, tool.start/complete, error.

export type ToolItem = ToolCard;

export type SendState = "sending" | "failed";

export interface ChatItem {
  key: string;
  role: "user" | "assistant" | "system";
  /** Visible text with attachment grammar removed. */
  text: string;
  attachments: Attachment[];
  images: DisplayImage[];
  reasoning: string;
  reasoningParts?: Array<{ text: string; source?: import("../hermes/types").HistoryLocator }>;
  tools: ToolItem[];
  streaming: boolean;
  interrupted?: boolean;
  timestampMs: number | null;
  /** Local user turns only. */
  send?: SendState;
  error?: AppError;
  /** Blob URLs of images the user attached from this browser (local preview only). */
  localImages?: string[];
  /** Names of non-image files the user attached from this browser. */
  localFiles?: string[];
  /** Raw assistant text while streaming (attachments are parsed out on every update). */
  raw?: string;
  /** Where the current streamed segment starts in `raw` (a reopened answer keeps what came before). */
  segmentStart?: number;
  /** A server-injected system turn shown as a one-line note (TimelineNote.kt). */
  note?: TimelineNote;
}

export type ConnectionState = "connecting" | "ready" | "reconnecting" | "offline";

/** Paging of the stored transcript towards older rows (HG-104). */
export interface OlderHistoryState {
  /** The last page reached back as far as it could: an older one may exist. */
  hasMore: boolean;
  loading: boolean;
  error: AppError | null;
}

export interface ChatState {
  items: ChatItem[];
  generating: boolean;
  historyLoaded: boolean;
  /** Every stored row loaded so far, oldest first: the history part of `items` is built from it. */
  historyRows: MessageRow[];
  older: OlderHistoryState;
  /**
   * Bumped whenever `historyRows` starts over; an older page requested before that belongs to a
   * transcript that is no longer on screen and is dropped.
   */
  historyEpoch: number;
  questions: QuestionState;
  connection: ConnectionState;
  /** A page-level notice (terminal session, owned elsewhere, expired answer…). */
  notice: AppError | null;
  /** The session is gone (HR-SESS-001): the composer is disabled. */
  terminal: boolean;
  /**
   * The user stopped the run: trailing stream events of that run are dropped until the next send,
   * so they do not grow a second assistant turn under the interrupted one.
   */
  stopped: boolean;
  /** Live working folder and branch from `session.info` (the top-bar subtitle). */
  workspace: { cwd: string; branch: string | null } | null;
  /** The model `session.info` last reported for this session. */
  liveModel: string | null;
}

export const initialChatState: ChatState = {
  items: [],
  generating: false,
  historyLoaded: false,
  historyRows: [],
  older: { hasMore: false, loading: false, error: null },
  historyEpoch: 0,
  questions: initialQuestionState,
  connection: "connecting",
  notice: null,
  terminal: false,
  stopped: false,
  workspace: null,
  liveModel: null,
};

const STREAM_EVENTS: ReadonlySet<string> = new Set([
  "message.start",
  "message.delta",
  "message.complete",
  "reasoning.delta",
  "reasoning.available",
  "tool.start",
  "tool.complete",
]);

export type ChatAction =
  /** The newest page (the whole transcript when `hasOlder` is false or absent). */
  | { type: "history"; rows: MessageRow[]; hasOlder?: boolean }
  | { type: "older-loading" }
  | { type: "older-loaded"; rows: MessageRow[]; hasMore: boolean; epoch: number }
  | { type: "older-failed"; error: AppError; epoch: number }
  | { type: "history-missing" }
  | { type: "event"; event: ServerEvent }
  | { type: "server-request"; request: ServerRequest }
  | { type: "open-requests"; snapshot: OpenRequestsSnapshot }
  | { type: "questions"; action: QuestionAction }
  | { type: "user-sent"; key: string; text: string; localImages?: string[]; localFiles?: string[]; nowMs: number }
  | { type: "user-delivered"; key: string }
  /** Without an error the bubble is only marked failed: a page-level notice explains it. */
  | { type: "user-failed"; key: string; error?: AppError }
  | { type: "user-retry"; key: string }
  | { type: "interrupted" }
  | { type: "running"; running: boolean }
  | { type: "connection"; state: ConnectionState }
  | { type: "notice"; error: AppError | null; terminal?: boolean }
  | { type: "reset" };

function str(payload: JsonObject, key: string): string | null {
  const v = payload[key];
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return null;
}

function assistant(key: string, nowMs: number | null): ChatItem {
  return { key, role: "assistant", text: "", attachments: [], images: [], reasoning: "", tools: [], streaming: true, timestampMs: nowMs, raw: "" };
}

function lastIndex(items: readonly ChatItem[], pred: (i: ChatItem) => boolean): number {
  for (let i = items.length - 1; i >= 0; i--) if (pred(items[i]!)) return i;
  return -1;
}

/** The streaming assistant after the last user turn, created when missing (a lost start). */
function ensureStreaming(state: ChatState): ChatState {
  const streaming = lastIndex(state.items, (i) => i.role === "assistant" && i.streaming);
  const lastUser = lastIndex(state.items, (i) => i.role === "user" && !i.note);
  if (streaming > lastUser) return state.generating ? state : { ...state, generating: true };
  // Everything after the latest user message is ONE answer (Android latestAssistantTurnSource):
  // a second message.start after tools reopens it instead of growing a second turn.
  const settled = lastIndex(state.items, (i) => i.role === "assistant");
  // Only a turn this page streamed: a history row's text is not the start of a live stream.
  if (settled > lastUser && !state.items[settled]!.interrupted && !state.items[settled]!.key.startsWith("h-")) {
    const items = [...state.items];
    const prev = items[settled]!;
    const raw = prev.raw ?? prev.text;
    const kept = raw.trim() ? `${raw.trimEnd()}\n\n` : "";
    items[settled] = { ...prev, streaming: true, raw: kept, segmentStart: kept.length };
    return { ...state, items, generating: true };
  }
  return { ...state, items: [...state.items, assistant(`a-${state.items.length}-${Date.now()}`, Date.now())], generating: true };
}

function mutateStreaming(state: ChatState, block: (item: ChatItem) => ChatItem): ChatState {
  const idx = lastIndex(state.items, (i) => i.role === "assistant" && i.streaming);
  if (idx < 0) return state;
  const items = [...state.items];
  items[idx] = block(items[idx]!);
  return { ...state, items };
}

function mutateLastAssistant(state: ChatState, block: (item: ChatItem) => ChatItem): ChatState {
  const idx = lastIndex(state.items, (i) => i.role === "assistant");
  if (idx < 0) return state;
  const items = [...state.items];
  items[idx] = block(items[idx]!);
  return { ...state, items };
}

function withRaw(item: ChatItem, raw: string): ChatItem {
  const parsed = parseAttachments(raw);
  return { ...item, raw, text: parsed.text, attachments: parsed.attachments };
}

function dedupeAttachments(list: readonly Attachment[]): Attachment[] {
  const seen = new Set<string>();
  return list.filter((a) => (seen.has(a.path) ? false : (seen.add(a.path), true)));
}

/**
 * A settled assistant turn as displayed: embedded payloads and wrappers become tool cards, and
 * `MEDIA:` lines inside tool output join the turn's attachments (ChatMessage.organizedForDisplay).
 */
export function organizeAssistantItem(item: ChatItem): ChatItem {
  const organized = organizeAssistant(item.text, item.tools);
  const toolFiles: Attachment[] = [];
  const tools = organized.tools.map((tool) => {
    if (!tool.output) return tool;
    const parsed = parseAttachments(tool.output);
    toolFiles.push(...parsed.attachments);
    return parsed.attachments.length ? { ...tool, output: parsed.text } : tool;
  });
  return { ...item, text: organized.text, tools, attachments: dedupeAttachments([...item.attachments, ...toolFiles]) };
}

function joinParts(first: string, second: string): string {
  if (!first.trim()) return second;
  if (!second.trim()) return first;
  return `${first.trimEnd()}\n\n${second.trimStart()}`;
}

/** Hermes splits one answer into several records around tool activity: fold them into one turn. */
function mergeAssistant(previous: ChatItem, next: ChatItem): ChatItem {
  const tools = new Map<string, ToolItem>();
  for (const t of [...previous.tools, ...next.tools]) tools.set(t.id, t);
  const images = [...previous.images];
  for (const img of next.images) if (!images.some((x) => x.path === img.path && x.url === img.url)) images.push(img);
  return {
    ...next,
    key: previous.key,
    text: joinParts(previous.text, next.text),
    reasoning: joinParts(previous.reasoning, next.reasoning),
    reasoningParts: [...(previous.reasoningParts ?? []), ...(next.reasoningParts ?? [])],
    timestampMs: previous.timestampMs ?? next.timestampMs,
    attachments: dedupeAttachments([...previous.attachments, ...next.attachments]),
    images,
    tools: [...tools.values()],
    interrupted: previous.interrupted || next.interrupted,
  };
}

function applyEvent(state: ChatState, event: ServerEvent): ChatState {
  const p = event.payload;
  switch (event.type) {
    case "message.start":
      return ensureStreaming(state);
    case "message.delta":
      return mutateStreaming(ensureStreaming(state), (item) => withRaw(item, (item.raw ?? "") + (str(p, "text") ?? "")));
    case "reasoning.delta":
    case "reasoning.available":
      return mutateStreaming(ensureStreaming(state), (item) => ({ ...item, reasoning: item.reasoning + (str(p, "text") ?? "") }));
    case "message.complete": {
      const complete = str(p, "text") ?? str(p, "rendered");
      const hasStreaming = state.items.some((i) => i.role === "assistant" && i.streaming);
      const prepared = hasStreaming || (complete !== null && complete.trim() !== "") ? ensureStreaming(state) : state;
      const done = mutateStreaming(prepared, (item) => {
        // A reopened answer's completion text covers only its last segment: keep the earlier ones.
        const base = item.raw ?? "";
        const finalRaw = complete === null ? base : base.slice(0, item.segmentStart ?? 0) + complete;
        const { segmentStart: _s, ...rest } = item;
        return organizeAssistantItem({ ...withRaw(rest, finalRaw), streaming: false });
      });
      return { ...done, generating: false };
    }
    case "tool.start":
      return mutateStreaming(ensureStreaming(state), (item) => ({
        ...item,
        tools: [...item.tools, { id: str(p, "tool_id") ?? `t-${item.tools.length}`, name: str(p, "name") ?? "tool", output: "", done: false, command: commandOf(p) }],
      }));
    case "tool.complete": {
      const id = str(p, "tool_id");
      const result = str(p, "result");
      return mutateLastAssistant(state, (item) => ({
        ...item,
        tools: item.tools.map((t) => (t.id === id ? completeTool(t, result) : t)),
      }));
    }
    case "session.info": {
      const cwd = str(p, "cwd");
      const model = str(p, "model");
      const withModel = model && model.trim() && model !== state.liveModel ? { ...state, liveModel: model.trim() } : state;
      const withWorkspace = cwd && cwd.trim() ? { ...withModel, workspace: { cwd: cwd.trim(), branch: str(p, "branch") } } : withModel;
      if (p.running === false) return finishStreaming(withWorkspace, false);
      if (p.running === true && !withWorkspace.generating) return { ...withWorkspace, generating: true };
      return withWorkspace;
    }
    default:
      return state;
  }
}

/** A command shown while the tool still runs, when tool.start carries one (args or payload). */
function commandOf(p: JsonObject): string | null {
  const direct = str(p, "command");
  if (direct) return direct;
  const args = p.args ?? p.arguments;
  if (typeof args === "string") return parseToolPayloadMeta(args)?.command ?? null;
  if (args && typeof args === "object" && !Array.isArray(args)) return parseToolPayloadMeta(JSON.stringify(args))?.command ?? null;
  return null;
}

function finishStreaming(state: ChatState, interrupted: boolean): ChatState {
  const items = state.items.map((i) =>
    i.role === "assistant" && i.streaming
      ? { ...i, streaming: false, ...(interrupted ? { interrupted: true } : {}), tools: i.tools.map((t) => ({ ...t, done: true })) }
      : i,
  );
  return { ...state, items, generating: false };
}

export function historyItems(rows: MessageRow[]): ChatItem[] {
  const out: ChatItem[] = [];
  for (const m of parseHistory(rows)) {
    const note = timelineNoteFor(m);
    if (note?.hidden) continue;
    const base: ChatItem = {
      key: m.key,
      role: m.role,
      text: m.role === "user" && !note ? organizeUserText(m.text) : m.text,
      attachments: m.attachments,
      images: m.images,
      reasoning: m.reasoning,
      ...(m.reasoning ? { reasoningParts: [{ text: m.reasoning, ...(m.reasoningSource ? { source: m.reasoningSource } : {}) }] } : {}),
      tools: m.tools.map((t) => {
        const argCommand = t.arguments ? parseToolPayloadMeta(t.arguments)?.command ?? null : null;
        return completeTool({ id: t.id, name: t.name, output: "", done: true, command: argCommand,
          ...(t.historySource ? { historySource: t.historySource } : {}) }, t.hasResult ? t.output : null);
      }),
      streaming: false,
      timestampMs: m.timestampMs,
      ...(note ? { note } : {}),
    };
    const item = base.role === "assistant" ? organizeAssistantItem(base) : base;
    const prev = out[out.length - 1];
    if (item.role === "assistant" && prev?.role === "assistant") out[out.length - 1] = mergeAssistant(prev, item);
    else out.push(item);
  }
  return out;
}

/**
 * The page's items rebuilt on `rows` (loaded rows plus older ones): everything not built from
 * stored rows (sending/failed/streamed/delivered turns) keeps its place after them.
 */
function itemsWithRows(state: ChatState, rows: readonly MessageRow[]): ChatItem[] {
  return [...historyItems([...rows]), ...state.items.filter((i) => !i.key.startsWith("h-"))];
}

/**
 * The whole conversation as the page would show it with every older page loaded (sharing):
 * `fullRows` contributes only rows older than those loaded, so the loaded tail and local turns
 * appear exactly as on screen.
 */
export function itemsWithFullHistory(state: ChatState, fullRows: readonly MessageRow[]): ChatItem[] {
  return itemsWithRows(state, mergeOlder(state.historyRows, fullRows));
}

export function reduceChat(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case "history": {
      // While a run streams, the live items are newer than any history page: keep them.
      if (state.generating && state.historyLoaded) return state;
      const failed = state.items.filter((i) => i.send === "failed" || i.send === "sending");
      const live = state.generating ? state.items.filter((i) => i.streaming) : [];
      // The page replaces the tail; older pages already loaded stay (unless the page cannot be
      // joined to them, see mergeTail). Turns this page streamed are dropped as before: the server
      // rows now carry them.
      const hasOlder = action.hasOlder ?? false;
      const merged = state.historyLoaded ? mergeTail(state.historyRows, action.rows, hasOlder) : { rows: [...action.rows], reset: true };
      return {
        ...state,
        items: [...historyItems(merged.rows), ...failed, ...live],
        historyLoaded: true,
        historyRows: merged.rows,
        older: merged.reset ? { hasMore: hasOlder, loading: false, error: null } : state.older,
        historyEpoch: merged.reset ? state.historyEpoch + 1 : state.historyEpoch,
      };
    }
    case "older-loading":
      return { ...state, older: { ...state.older, loading: true, error: null } };
    case "older-loaded": {
      if (action.epoch !== state.historyEpoch) return { ...state, older: { ...state.older, loading: false } };
      const rows = mergeOlder(state.historyRows, action.rows);
      return { ...state, historyRows: rows, items: itemsWithRows(state, rows), older: { hasMore: action.hasMore, loading: false, error: null } };
    }
    case "older-failed":
      if (action.epoch !== state.historyEpoch) return { ...state, older: { ...state.older, loading: false } };
      return { ...state, older: { ...state.older, loading: false, error: action.error } };
    case "history-missing":
      return { ...state, historyLoaded: true };
    case "event": {
      const questions = reduceQuestions(state.questions, { type: "event", event: action.event });
      if (state.stopped && STREAM_EVENTS.has(action.event.type)) {
        return questions === state.questions ? state : { ...state, questions };
      }
      const next = applyEvent(state, action.event);
      if (action.event.type === "error") {
        return { ...finishStreaming(next, false), questions };
      }
      return questions === state.questions ? next : { ...next, questions };
    }
    case "server-request":
      return { ...state, questions: reduceQuestions(state.questions, { type: "server-request", request: action.request }) };
    case "open-requests":
      return { ...state, questions: reduceQuestions(state.questions, { type: "open-requests", snapshot: action.snapshot }) };
    case "questions":
      return { ...state, questions: reduceQuestions(state.questions, action.action) };
    case "user-sent": {
      const item: ChatItem = {
        key: action.key,
        role: "user",
        text: action.text,
        attachments: [],
        images: [],
        reasoning: "",
        tools: [],
        streaming: false,
        timestampMs: action.nowMs,
        send: "sending",
        ...(action.localImages?.length ? { localImages: action.localImages } : {}),
        ...(action.localFiles?.length ? { localFiles: action.localFiles } : {}),
      };
      return { ...state, items: [...state.items, item], notice: state.terminal ? state.notice : null, stopped: false };
    }
    case "user-delivered":
      return {
        ...state,
        generating: true,
        items: state.items.map((i) => {
          if (i.key !== action.key) return i;
          const { send: _s, error: _e, ...rest } = i;
          return rest;
        }),
      };
    case "user-failed":
      return { ...state, items: state.items.map((i) => (i.key === action.key ? { ...i, send: "failed", error: action.error } : i)) };
    case "user-retry":
      return {
        ...state,
        items: state.items.map((i) => {
          if (i.key !== action.key) return i;
          const { error: _e, ...rest } = i;
          return { ...rest, send: "sending" };
        }),
      };
    case "interrupted":
      return { ...finishStreaming(state, true), stopped: true };
    case "running":
      return action.running ? (state.generating ? state : { ...state, generating: true }) : finishStreaming(state, false);
    case "connection":
      return state.connection === action.state ? state : { ...state, connection: action.state };
    case "notice":
      return { ...state, notice: action.error, terminal: action.terminal ?? state.terminal };
    case "reset":
      return initialChatState;
  }
}

/** Does this chat currently have a question waiting on the user? */
export function hasOpenQuestion(state: ChatState): boolean {
  return state.questions.approval !== null || state.questions.clarify !== null;
}
