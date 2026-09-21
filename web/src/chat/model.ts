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

// Chat view model as a pure reducer. Live streaming follows android ui/chat/ChatUiState.kt
// (`applyEvent`): message.start/delta/complete, reasoning.delta, tool.start/complete, error.

export interface ToolItem {
  id: string;
  name: string;
  output: string;
  done: boolean;
}

export type SendState = "sending" | "failed";

export interface ChatItem {
  key: string;
  role: "user" | "assistant" | "system";
  /** Visible text with attachment grammar removed. */
  text: string;
  attachments: Attachment[];
  images: DisplayImage[];
  reasoning: string;
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
}

export type ConnectionState = "connecting" | "ready" | "reconnecting" | "offline";

export interface ChatState {
  items: ChatItem[];
  generating: boolean;
  historyLoaded: boolean;
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
}

export const initialChatState: ChatState = {
  items: [],
  generating: false,
  historyLoaded: false,
  questions: initialQuestionState,
  connection: "connecting",
  notice: null,
  terminal: false,
  stopped: false,
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
  | { type: "history"; rows: MessageRow[] }
  | { type: "history-missing" }
  | { type: "event"; event: ServerEvent }
  | { type: "server-request"; request: ServerRequest }
  | { type: "open-requests"; snapshot: OpenRequestsSnapshot }
  | { type: "questions"; action: QuestionAction }
  | { type: "user-sent"; key: string; text: string; localImages?: string[]; localFiles?: string[]; nowMs: number }
  | { type: "user-delivered"; key: string }
  | { type: "user-failed"; key: string; error: AppError }
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
  const lastUser = lastIndex(state.items, (i) => i.role === "user");
  if (streaming > lastUser) return state.generating ? state : { ...state, generating: true };
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
      const done = mutateStreaming(prepared, (item) => ({ ...withRaw(item, complete ?? item.raw ?? ""), streaming: false }));
      return { ...done, generating: false };
    }
    case "tool.start":
      return mutateStreaming(ensureStreaming(state), (item) => ({
        ...item,
        tools: [...item.tools, { id: str(p, "tool_id") ?? `t-${item.tools.length}`, name: str(p, "name") ?? "tool", output: "", done: false }],
      }));
    case "tool.complete": {
      const id = str(p, "tool_id");
      const result = str(p, "result") ?? "";
      return mutateLastAssistant(state, (item) => ({
        ...item,
        tools: item.tools.map((t) => (t.id === id ? { ...t, done: true, output: result } : t)),
      }));
    }
    case "session.info": {
      if (p.running === false) return finishStreaming(state, false);
      if (p.running === true && !state.generating) return { ...state, generating: true };
      return state;
    }
    default:
      return state;
  }
}

function finishStreaming(state: ChatState, interrupted: boolean): ChatState {
  const items = state.items.map((i) =>
    i.role === "assistant" && i.streaming
      ? { ...i, streaming: false, ...(interrupted ? { interrupted: true } : {}), tools: i.tools.map((t) => ({ ...t, done: true })) }
      : i,
  );
  return { ...state, items, generating: false };
}

function historyItems(rows: MessageRow[]): ChatItem[] {
  return parseHistory(rows).map((m) => ({
    key: m.key,
    role: m.role,
    text: m.text,
    attachments: m.attachments,
    images: m.images,
    reasoning: m.reasoning,
    tools: m.tools.map((t) => ({ id: t.id, name: t.name, output: t.output, done: true })),
    streaming: false,
    timestampMs: m.timestampMs,
  }));
}

export function reduceChat(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case "history": {
      // While a run streams, the live items are newer than any history page: keep them.
      if (state.generating && state.historyLoaded) return state;
      const failed = state.items.filter((i) => i.send === "failed" || i.send === "sending");
      const live = state.generating ? state.items.filter((i) => i.streaming) : [];
      return { ...state, items: [...historyItems(action.rows), ...failed, ...live], historyLoaded: true };
    }
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
