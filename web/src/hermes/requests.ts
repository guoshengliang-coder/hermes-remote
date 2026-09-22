import {
  approvalRespond,
  clarifyLock,
  clarifyRespond,
  requestAnswer,
  type ApprovalChoice,
  type RpcCall,
} from "./params";
import type { JsonObject, JsonValue, OpenRequestsSnapshot, ServerEvent, ServerRequest } from "./types";

// Question cards (approval + clarify) as a pure reducer, speaking both question protocols
// (docs/HERMES_CONTRACT.md §3 "How Hermes asks the phone a question"). Which protocol a card uses is
// decided PER CARD from what arrived: a server→client request carries its id (`serverRequestId` /
// `serverRequest`); a legacy `approval.request` / `clarify.request` event does not.
// Port of android ui/chat/ChatUiState.kt (reducers) and ChatViewModel.kt (respondApproval/clarify).

export interface ApprovalCard {
  command: string;
  description: string;
  patternKeys: string[];
  allowPermanent: boolean;
  smartDenied: boolean;
  /** Server request id (new protocol) or null (legacy `approval.respond`, resolves the oldest). */
  serverRequestId: string | null;
  sessionId: string | null;
}

export interface ClarifyQuestion {
  /** Empty for a legacy single question with no qid. */
  qid: string;
  question: string;
  choices: string[];
  multiSelect: boolean;
}

export interface ClarifyCard {
  requestId: string;
  questions: ClarifyQuestion[];
  /** Answers already accepted server-side, keyed by qid. */
  lockedAnswers: Record<string, string>;
  /** Raised by a server→client `clarify` request; its id is `requestId`. */
  serverRequest: boolean;
  sessionId: string | null;
}

export interface QuestionState {
  approval: ApprovalCard | null;
  /** Server-request approvals waiting behind `approval`, oldest (first arrived) first. */
  queuedApprovals: ApprovalCard[];
  clarify: ClarifyCard | null;
}

export const initialQuestionState: QuestionState = { approval: null, queuedApprovals: [], clarify: null };

export type QuestionAction =
  | { type: "event"; event: ServerEvent }
  | { type: "server-request"; request: ServerRequest }
  | { type: "open-requests"; snapshot: OpenRequestsSnapshot }
  /** The current approval was answered or withdrawn: the next queued one takes its place. */
  | { type: "approval-settled" }
  /** Replace the clarify card (optimistic advance, restore after a failed send, or clear). */
  | { type: "clarify-set"; card: ClarifyCard | null }
  | { type: "reset" };

function str(obj: JsonObject, key: string): string | null {
  const v = obj[key];
  if (v === null || v === undefined) return null;
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return JSON.stringify(v);
}

function nonBlankPrim(v: JsonValue | undefined): string | null {
  if (typeof v === "string") return v.trim() ? v : null;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return null;
}

function strList(obj: JsonObject, key: string): string[] {
  const v = obj[key];
  if (!Array.isArray(v)) return [];
  return v.flatMap((e) => (typeof e === "string" || typeof e === "number" || typeof e === "boolean" ? [String(e)] : []));
}

function bool(v: JsonValue | undefined): boolean {
  return v === true || v === "true";
}

export function parseApproval(payload: JsonObject, serverRequestId: string | null, sessionId: string | null): ApprovalCard {
  const keys = strList(payload, "pattern_keys");
  const single = str(payload, "pattern_key");
  return {
    command: str(payload, "command") ?? "",
    description: str(payload, "description") ?? "",
    patternKeys: keys.length ? keys : single !== null ? [single] : [],
    allowPermanent: bool(payload.allow_permanent),
    smartDenied: bool(payload.smart_denied),
    serverRequestId,
    sessionId,
  };
}

function choicesOf(obj: JsonObject): string[] {
  const v = obj.choices;
  return Array.isArray(v) ? v.flatMap((c) => { const s = nonBlankPrim(c); return s === null ? [] : [s]; }) : [];
}

/** `parseClarifyRequest`: batch `questions[]` wins over single question/choices. */
export function parseClarify(payload: JsonObject, serverRequest: boolean, sessionId: string | null): ClarifyCard {
  const requestId =
    nonBlankPrim(payload.request_id) ?? nonBlankPrim(payload.clarify_id) ?? nonBlankPrim(payload.requestId) ?? "";
  const batch: ClarifyQuestion[] = [];
  if (Array.isArray(payload.questions)) {
    for (const el of payload.questions) {
      if (typeof el !== "object" || el === null || Array.isArray(el)) continue;
      const question = nonBlankPrim(el.question);
      if (question === null) continue;
      batch.push({ qid: nonBlankPrim(el.qid) ?? "", question, choices: choicesOf(el), multiSelect: bool(el.multi_select) });
    }
  }
  const questions = batch.length
    ? batch
    : [{ qid: "", question: nonBlankPrim(payload.question) ?? "", choices: choicesOf(payload), multiSelect: bool(payload.multi_select) }];
  const lockedAnswers: Record<string, string> = {};
  const answers = payload.answers;
  if (typeof answers === "object" && answers !== null && !Array.isArray(answers)) {
    for (const [k, v] of Object.entries(answers)) {
      const s = nonBlankPrim(v);
      if (s !== null) lockedAnswers[k] = s;
    }
  }
  return { requestId, questions, lockedAnswers, serverRequest, sessionId };
}

/** First question not yet locked, or null when everything is answered. */
export function currentQuestion(card: ClarifyCard): ClarifyQuestion | null {
  return card.questions.find((q) => !(q.qid in card.lockedAnswers)) ?? null;
}

export function isBatch(card: ClarifyCard): boolean {
  return card.questions.length > 1;
}

function withApproval(state: QuestionState, card: ApprovalCard): QuestionState {
  const id = card.serverRequestId;
  const current = state.approval;
  // A legacy card (or anything arriving over a legacy card) replaces: legacy cards are never queued.
  if (id === null || current === null || current.serverRequestId === null) return { ...state, approval: card };
  if (current.serverRequestId === id) return { ...state, approval: card };
  if (state.queuedApprovals.some((a) => a.serverRequestId === id)) {
    return { ...state, queuedApprovals: state.queuedApprovals.map((a) => (a.serverRequestId === id ? card : a)) };
  }
  return { ...state, queuedApprovals: [...state.queuedApprovals, card] };
}

function approvalSettled(state: QuestionState): QuestionState {
  const [next = null, ...rest] = state.queuedApprovals;
  return { ...state, approval: next, queuedApprovals: rest };
}

/** `request.cancel {id}`: tear down only the card raised by that exact request. */
function cancelled(state: QuestionState, requestId: string | null): QuestionState {
  if (!requestId || !requestId.trim()) return state;
  let next: QuestionState = { ...state, queuedApprovals: state.queuedApprovals.filter((a) => a.serverRequestId !== requestId) };
  if (next.approval?.serverRequestId === requestId) next = approvalSettled(next);
  if (next.clarify?.serverRequest && next.clarify.requestId === requestId) next = { ...next, clarify: null };
  return next;
}

/** Snapshot after `session.resume`: server-request cards not listed are stale. Legacy cards stay. */
function onlyOpen(state: QuestionState, ids: ReadonlySet<string>): QuestionState {
  const approvals = [state.approval, ...state.queuedApprovals].filter(
    (a): a is ApprovalCard => a !== null && (a.serverRequestId === null || ids.has(a.serverRequestId)),
  );
  const [approval = null, ...queuedApprovals] = approvals;
  const clarify = state.clarify && state.clarify.serverRequest && !ids.has(state.clarify.requestId) ? null : state.clarify;
  return { approval, queuedApprovals, clarify };
}

export function reduceQuestions(state: QuestionState, action: QuestionAction): QuestionState {
  switch (action.type) {
    case "reset":
      return initialQuestionState;
    case "approval-settled":
      return approvalSettled(state);
    case "clarify-set":
      return { ...state, clarify: action.card };
    case "open-requests":
      return onlyOpen(state, new Set(action.snapshot.ids));
    case "server-request": {
      const { request } = action;
      return request.method === "approval"
        ? withApproval(state, parseApproval(request.params, request.id, request.sessionId))
        : { ...state, clarify: parseClarify({ ...request.params, request_id: request.id }, true, request.sessionId) };
    }
    case "event": {
      const { event } = action;
      switch (event.type) {
        case "approval.request":
          return withApproval(state, parseApproval(event.payload, null, event.sessionId));
        case "clarify.request":
          // A newer legacy card replaces an older one.
          return { ...state, clarify: parseClarify(event.payload, false, event.sessionId) };
        case "request.cancel":
          return cancelled(state, str(event.payload, "id"));
        case "message.delta":
          // The agent talking again means the pending clarify expired (ChatUiState.kt).
          return state.clarify ? { ...state, clarify: null } : state;
        default:
          return state;
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Answer planners: what to send, and the optimistic state to show meanwhile.

export type AnswerKind = "approval" | "clarify";

export interface AnswerPlan {
  rpc: RpcCall;
  kind: AnswerKind;
  /** State to show while the answer is in flight. */
  next: QuestionState;
  /** State to restore if the send fails (clarify goes back for a retry; approval does not). */
  onFailure: QuestionState;
  /** The answer settles the whole request (last batch lock, single answer, skip). */
  finishes: boolean;
  /** Whether the RPC answers `{status}`; legacy `approval.respond` answers nothing checkable. */
  reportsStatus: boolean;
}

/** Answer the approval on screen. `sessionId` is the live handle, used only by the legacy RPC. */
export function planApprovalAnswer(state: QuestionState, choice: ApprovalChoice, sessionId: string): AnswerPlan | null {
  const card = state.approval;
  if (!card) return null;
  const next = approvalSettled(state);
  const rpc = card.serverRequestId ? requestAnswer(card.serverRequestId, { choice }) : approvalRespond(sessionId, choice);
  // Android keeps the sheet dismissed on failure and reports HR-RPC-001.
  return { rpc, kind: "approval", next, onFailure: next, finishes: true, reportsStatus: card.serverRequestId !== null };
}

/**
 * Answer the CURRENT clarify question. Routed by "the wire gave this question a real qid", never
 * by batch size: newer Hermes sends a single question as a one-element `questions[]` batch, and a
 * `request.answer {answer}` on a batch request is a cancel-all (contract: no `answers` key), while a
 * legacy respond without question_id leaves the agent an EMPTY answer (ChatViewModel.clarify).
 */
export function planClarifyAnswer(state: QuestionState, answer: string, sessionId: string): AnswerPlan | null {
  const card = state.clarify;
  if (!card) return null;
  const question = currentQuestion(card);
  if (question && question.qid !== "") {
    const advanced: ClarifyCard = { ...card, lockedAnswers: { ...card.lockedAnswers, [question.qid]: answer } };
    const finishes = currentQuestion(advanced) === null;
    const rpc = card.serverRequest
      ? clarifyLock(card.requestId, question.qid, answer)
      : clarifyRespond(sessionId, card.requestId, answer, question.qid);
    return { rpc, kind: "clarify", next: { ...state, clarify: finishes ? null : advanced }, onFailure: state, finishes, reportsStatus: true };
  }
  const rpc = card.serverRequest ? requestAnswer(card.requestId, { answer }) : clarifyRespond(sessionId, card.requestId, answer);
  return { rpc, kind: "clarify", next: { ...state, clarify: null }, onFailure: state, finishes: true, reportsStatus: true };
}

/**
 * Skip / cancel the WHOLE clarify request: empty answer, no question id. New protocol:
 * `request.answer {id, result:{answer:""}}` (no `answers` key = cancel-all); legacy:
 * `clarify.respond` without question_id. Android does not restore the card on a failed skip.
 */
export function planClarifySkip(state: QuestionState, sessionId: string): AnswerPlan | null {
  const card = state.clarify;
  if (!card) return null;
  const rpc = card.serverRequest ? requestAnswer(card.requestId, { answer: "" }) : clarifyRespond(sessionId, card.requestId, "");
  const next = { ...state, clarify: null };
  return { rpc, kind: "clarify", next, onFailure: next, finishes: true, reportsStatus: true };
}

/** Cancel-all is the same wire answer as skip. */
export const planClarifyCancelAll = planClarifySkip;

export interface AnswerOutcome {
  /** Hermes said the request was no longer open: the agent never received this answer. */
  expired: boolean;
  /** Registered code to surface when expired: HR-APPROVAL-003 or HR-CLARIFY-001. */
  code: "HR-APPROVAL-003" | "HR-CLARIFY-001" | null;
  /** Remaining questions reported by `clarify.lock`, when present. */
  remaining: number | null;
}

/** Read the RPC answer of a plan. `{status:"expired"}` means the request had already ended. */
export function interpretAnswer(plan: Pick<AnswerPlan, "kind" | "reportsStatus">, result: JsonValue): AnswerOutcome {
  const obj = typeof result === "object" && result !== null && !Array.isArray(result) ? result : {};
  const expired = plan.reportsStatus && obj.status === "expired";
  const remaining = typeof obj.remaining === "number" ? obj.remaining : null;
  return {
    expired,
    code: expired ? (plan.kind === "approval" ? "HR-APPROVAL-003" : "HR-CLARIFY-001") : null,
    remaining,
  };
}

/** Applies an expired outcome: a clarify card is torn down (the turn has moved on). */
export function afterExpired(state: QuestionState, kind: AnswerKind): QuestionState {
  return kind === "clarify" ? { ...state, clarify: null } : state;
}
