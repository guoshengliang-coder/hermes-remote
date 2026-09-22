import type { JsonObject } from "./types";

// Params builders for every WebSocket RPC this client sends. Hermes 17b5df02 validates params with
// `extra="forbid"`: ONE undeclared key fails the whole call with 4000 (docs/HERMES_CONTRACT.md §3,
// docs/hermes-rpc-params.json). Optional keys are omitted when unset, never sent as null.
// Mirrors android data/repository/ChatRepository.kt.

export const CLIENT_SOURCE = "hermes_remote";

/**
 * The only client→Hermes methods the Gateway forwards on a browser WebSocket; anything else is
 * answered in-band with RPC error 4403 / HR-WEB-001. The client refuses them locally too.
 */
export const WEB_TUNNEL_METHODS: ReadonlySet<string> = new Set([
  "client.capabilities",
  "session.create",
  "session.resume",
  "prompt.submit",
  "session.interrupt",
  "image.attach",
  "file.attach",
  "request.answer",
  "clarify.lock",
  "approval.respond",
  "clarify.respond",
]);

export interface RpcCall {
  method: string;
  params: JsonObject;
}

export type ApprovalChoice = "once" | "session" | "always" | "deny";

function nonBlank(value: string | null | undefined): value is string {
  return typeof value === "string" && value.trim() !== "";
}

export function sessionCreate(opts: { profile?: string | null; cwd?: string | null } = {}): RpcCall {
  const params: JsonObject = { source: CLIENT_SOURCE };
  if (nonBlank(opts.profile)) params.profile = opts.profile;
  if (nonBlank(opts.cwd)) params.cwd = opts.cwd;
  return { method: "session.create", params };
}

/**
 * `omit_messages: true`: the transcript comes from paged REST; the answer only needs the live handle
 * and `open_requests`. A transcript here re-inlines every base64 image (HG-65/HG-69).
 */
export function sessionResume(sessionId: string, opts: { profile?: string | null } = {}): RpcCall {
  const params: JsonObject = { session_id: sessionId, source: CLIENT_SOURCE, omit_messages: true };
  if (nonBlank(opts.profile)) params.profile = opts.profile;
  return { method: "session.resume", params };
}

export function promptSubmit(sessionId: string, text: string): RpcCall {
  return { method: "prompt.submit", params: { session_id: sessionId, text } };
}

export function sessionInterrupt(sessionId: string): RpcCall {
  return { method: "session.interrupt", params: { session_id: sessionId } };
}

export function imageAttach(sessionId: string, path: string): RpcCall {
  return { method: "image.attach", params: { session_id: sessionId, path } };
}

export function fileAttach(sessionId: string, path: string, name: string): RpcCall {
  return { method: "file.attach", params: { session_id: sessionId, path, name } };
}

export function clientCapabilities(): RpcCall {
  return { method: "client.capabilities", params: { server_requests: true } };
}

/** New protocol: answers exactly one server request by id → `{status:"ok"|"expired"}`. */
export function requestAnswer(id: string, result: JsonObject): RpcCall {
  return { method: "request.answer", params: { id, result } };
}

/** New protocol: lock one batch clarify answer; the last lock resolves the request. */
export function clarifyLock(requestId: string, questionId: string, answer: string): RpcCall {
  return { method: "clarify.lock", params: { request_id: requestId, question_id: questionId, answer } };
}

/** Old protocol: resolves the session's OLDEST approval. Only `choice` — `approved` is a 4000. */
export function approvalRespond(sessionId: string, choice: ApprovalChoice): RpcCall {
  return { method: "approval.respond", params: { session_id: sessionId, choice } };
}

/** Old protocol (f159e581 only; -32601 on 17b5df02). `questionId` only for a batch lock. */
export function clarifyRespond(
  sessionId: string,
  requestId: string,
  answer: string,
  questionId?: string | null,
): RpcCall {
  const params: JsonObject = { session_id: sessionId, request_id: requestId, answer };
  if (questionId) params.question_id = questionId;
  return { method: "clarify.respond", params };
}
