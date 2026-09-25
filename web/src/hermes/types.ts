// Wire shapes consumed from upstream Hermes (docs/HERMES_CONTRACT.md). Nothing here is ours to
// version: every field is optional unless upstream guarantees it, and parsers must tolerate the rest.

export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };

/** `GET /api/sessions` row. The id is under `id`, never `session_id` (contract §1). */
export interface SessionListItem {
  id: string;
  title?: string | null;
  model?: string | null;
  provider?: string | null;
  /** Unix seconds (float). */
  last_active?: number | null;
  message_count?: number;
  profile?: string | null;
  is_default_profile?: boolean;
  archived?: boolean;
  cwd?: string | null;
  source?: string | null;
  git_branch?: string | null;
  git_repo_root?: string | null;
  display_name?: string | null;
  chat_type?: string | null;
}

export interface SessionListResponse {
  sessions: SessionListItem[];
}

/** `GET /api/profiles/sessions`. */
export interface ProfileSessionsResponse {
  sessions: SessionListItem[];
  total?: number;
  profile_totals?: Record<string, number>;
  errors?: string[];
}

/** One element of a block-list `content` (contract §1b). The vocabulary is upstream's and open. */
export interface MessageContentBlock {
  type?: string;
  text?: string;
  url?: string;
  path?: string;
  file_path?: string;
  source?: string;
  image_url?: { url?: string } | string;
  [key: string]: JsonValue | undefined;
}

export type MessageContent = string | null | MessageContentBlock[] | MessageContentBlock;

/** OpenAI-shaped tool call persisted on an assistant row. */
export interface ToolCallWire {
  id?: string;
  type?: string;
  function?: { name?: string; arguments?: string | JsonObject };
}

/** `GET /api/sessions/{id}/messages` row: SQLite `messages` columns under their column names. */
export interface MessageRow {
  id?: number | null;
  role: string;
  content?: MessageContent;
  /** `REAL NOT NULL`, Unix **seconds** as a float. Not `created_at`, not ISO. */
  timestamp?: number | null;
  /** Never emitted by upstream; accepted as a fallback only. */
  created_at?: string | null;
  display_kind?: string | null;
  display_metadata?: JsonObject | null;
  reasoning?: string | null;
  reasoning_content?: string | null;
  /** Usually a parsed array; may arrive stringified. */
  tool_calls?: ToolCallWire[] | string | null;
  tool_call_id?: string | null;
  tool_name?: string | null;
  /** Connector opt-in projection; absent on older Connectors and unabridged reads. */
  hr_preview?: { fields: string[]; offset: number; sessionId: string; profile?: string | null };
}

export interface HistoryLocator {
  sessionId: string;
  profile?: string | null;
  rowId: number;
  offset: number;
}

export interface MessagesResponse {
  messages: MessageRow[];
  /** Present when upstream paged the answer (`limit`/`offset`/`order` honoured). */
  pagination?: { limit?: number; offset?: number; order?: string; returned?: number } | null;
}

/** Relay inbox lifecycle event (ours, not upstream's): `LifecycleEventDtos.kt`. */
export interface LifecycleEvent {
  type: string;
  version: number;
  eventId: string;
  deviceId: string;
  profile?: string | null;
  runtimeSessionId: string;
  storedSessionId: string;
  event: string;
  state: string;
  occurredAt: string;
  title?: string | null;
}

export interface StoredLifecycleEvent {
  sequence: number;
  event: LifecycleEvent;
  receivedAt: string;
  deliveredAt?: string | null;
  readAt?: string | null;
}

export interface LifecycleEventPage {
  events: StoredLifecycleEvent[];
  nextCursor: number;
  hasMore: boolean;
}

/** Params of a server→client `approval` request, or payload of a legacy `approval.request` event. */
export interface ApprovalRequestParams {
  session_id?: string;
  command?: string;
  description?: string;
  pattern_keys?: string[];
  pattern_key?: string;
  allow_permanent?: boolean;
  smart_denied?: boolean;
  /** The approval queue's own id — NOT the server request id. */
  request_id?: string;
  [key: string]: JsonValue | undefined;
}

export interface ClarifyQuestionWire {
  qid?: string;
  question?: string;
  choices?: string[];
  multi_select?: boolean;
}

/** Params of a server→client `clarify` request, or payload of a legacy `clarify.request` event. */
export interface ClarifyRequestParams {
  session_id?: string;
  request_id?: string;
  clarify_id?: string;
  requestId?: string;
  question?: string;
  choices?: string[];
  multi_select?: boolean;
  /** Batch form; a single question may also arrive as a one-element batch. */
  questions?: ClarifyQuestionWire[];
  /** Answers already locked server-side, keyed by qid (reconnect snapshot). */
  answers?: Record<string, string>;
}

/** Entry of `session.resume`'s `open_requests` (new question protocol; omitted when empty). */
export interface OpenRequest {
  id: string;
  method: string;
  params?: JsonObject;
}

export interface SessionInfo {
  cwd?: string | null;
  model?: string | null;
  [key: string]: JsonValue | undefined;
}

/**
 * `session.create` result. `session_id` is the ephemeral live handle, `stored_session_id` the durable
 * id to navigate with (older Hermes may omit it — fall back to `session_id`).
 */
export interface SessionCreateResult {
  session_id: string;
  stored_session_id?: string | null;
  info?: SessionInfo | null;
}

/** `session.resume` result with `omit_messages: true`: a live handle and maybe open requests. */
export interface SessionResumeResult {
  session_id: string;
  message_count?: number;
  open_requests?: OpenRequest[];
  info?: SessionInfo | null;
}

/** `request.answer` / `clarify.respond` result. */
export interface AnswerStatusResult {
  status?: "ok" | "expired" | string;
}

/** `clarify.lock` result. */
export interface ClarifyLockResult extends AnswerStatusResult {
  remaining?: number;
}

/** `file.attach` result. */
export interface FileAttachResult {
  name?: string;
  path?: string;
  ref_text: string;
}

/** `image.attach` result. */
export interface ImageAttachResult {
  path?: string;
  width?: number;
  height?: number;
}

/**
 * A `{method:"event"}` frame's params, normalised (`ServerEvent.kt`). `sessionId` prefers the durable
 * id: `stored_session_id` over `session_id`, payload over params, snake_case over camelCase.
 */
export interface ServerEvent {
  type: string;
  sessionId: string | null;
  payload: JsonObject;
}

/** Server→client JSON-RPC request methods this client answers with a card. */
export type HandledServerRequestMethod = "approval" | "clarify";

export interface ServerRequest {
  /** Exact id as received (upstream mints `"srq-<12 hex>"`); echoed verbatim when answering. */
  id: string;
  method: HandledServerRequestMethod;
  params: JsonObject;
  sessionId: string | null;
  /** True when re-delivered from `session.resume`'s `open_requests`. */
  replayed?: boolean;
}

/**
 * Client-internal snapshot after a `session.resume` answer on a connection that advertised server
 * requests (`hr.open_requests` in Android): server-request cards whose id is not listed are stale.
 */
export interface OpenRequestsSnapshot {
  sessionId: string;
  ids: string[];
}
