import { describe, expect, it } from "vitest";
import {
  afterExpired,
  currentQuestion,
  initialQuestionState,
  interpretAnswer,
  planApprovalAnswer,
  planClarifyAnswer,
  planClarifyCancelAll,
  planClarifySkip,
  reduceQuestions,
  type QuestionAction,
  type QuestionState,
} from "./requests";
import type { JsonObject, ServerRequest } from "./types";

const srq = (id: string, method: "approval" | "clarify", params: JsonObject = {}): QuestionAction => ({
  type: "server-request",
  request: { id, method, params: method === "clarify" ? { ...params, request_id: id } : params, sessionId: "s" } satisfies ServerRequest,
});
const ev = (type: string, payload: JsonObject = {}): QuestionAction => ({ type: "event", event: { type, sessionId: "s", payload } });
const run = (...actions: QuestionAction[]) => actions.reduce(reduceQuestions, initialQuestionState);

describe("approval cards", () => {
  it("parses the approval payload", () => {
    const s = run(srq("srq-1", "approval", { command: "rm x", description: "delete", pattern_key: "rm", allow_permanent: true, smart_denied: "true" }));
    expect(s.approval).toEqual({
      command: "rm x", description: "delete", patternKeys: ["rm"], allowPermanent: true, smartDenied: true, serverRequestId: "srq-1", sessionId: "s",
    });
  });

  it("queues new-protocol approvals oldest first and updates a known id in place", () => {
    let s = run(srq("srq-1", "approval", { command: "a" }), srq("srq-2", "approval", { command: "b" }), srq("srq-3", "approval", { command: "c" }));
    expect(s.approval?.serverRequestId).toBe("srq-1");
    expect(s.queuedApprovals.map((a) => a.serverRequestId)).toEqual(["srq-2", "srq-3"]);
    s = reduceQuestions(s, srq("srq-2", "approval", { command: "b2" }));
    expect(s.queuedApprovals[0]?.command).toBe("b2");
    s = reduceQuestions(s, srq("srq-1", "approval", { command: "a2" }));
    expect(s.approval?.command).toBe("a2");
    s = reduceQuestions(s, { type: "approval-settled" });
    expect(s.approval?.serverRequestId).toBe("srq-2");
    expect(s.queuedApprovals.map((a) => a.serverRequestId)).toEqual(["srq-3"]);
  });

  it("a legacy approval replaces the card and is never queued", () => {
    let s = run(ev("approval.request", { command: "old" }), ev("approval.request", { command: "new", pattern_keys: ["k1", "k2"] }));
    expect(s.approval).toMatchObject({ command: "new", serverRequestId: null, patternKeys: ["k1", "k2"] });
    expect(s.queuedApprovals).toEqual([]);
    s = reduceQuestions(s, srq("srq-9", "approval"));
    expect(s.approval?.serverRequestId).toBe("srq-9");
  });

  it("request.cancel removes only the matching card", () => {
    let s = run(srq("srq-1", "approval"), srq("srq-2", "approval"), srq("srq-c", "clarify", { question: "?" }));
    s = reduceQuestions(s, ev("request.cancel", { id: "srq-2", method: "approval", reason: "timeout" }));
    expect(s.approval?.serverRequestId).toBe("srq-1");
    expect(s.queuedApprovals).toEqual([]);
    s = reduceQuestions(s, ev("request.cancel", { id: "srq-1" }));
    expect(s.approval).toBeNull();
    expect(s.clarify).not.toBeNull();
    s = reduceQuestions(s, ev("request.cancel", { id: "srq-c" }));
    expect(s.clarify).toBeNull();
  });

  it("request.cancel never touches a legacy card", () => {
    const s = run(ev("approval.request", { command: "x" }), ev("clarify.request", { request_id: "r1", question: "?" }), ev("request.cancel", { id: "r1" }));
    expect(s.approval).not.toBeNull();
    expect(s.clarify?.requestId).toBe("r1");
  });

  it("the open-requests snapshot drops unlisted server-request cards and keeps legacy ones", () => {
    let s = run(srq("srq-1", "approval"), srq("srq-2", "approval"), srq("srq-3", "approval"), srq("srq-c", "clarify", { question: "?" }));
    s = reduceQuestions(s, { type: "open-requests", snapshot: { sessionId: "s", ids: ["srq-2", "srq-3"] } });
    expect(s.approval?.serverRequestId).toBe("srq-2");
    expect(s.queuedApprovals.map((a) => a.serverRequestId)).toEqual(["srq-3"]);
    expect(s.clarify).toBeNull();

    const legacy = run(ev("approval.request", { command: "x" }), ev("clarify.request", { request_id: "r", question: "?" }));
    expect(reduceQuestions(legacy, { type: "open-requests", snapshot: { sessionId: "s", ids: [] } })).toEqual(legacy);
  });
});

describe("clarify cards", () => {
  it("parses a single question and a batch (batch wins), with locked answers", () => {
    const single = run(ev("clarify.request", { clarify_id: "c1", question: "Which?", choices: ["a", " ", "b"], multi_select: "true" }));
    expect(single.clarify).toEqual({
      requestId: "c1", questions: [{ qid: "", question: "Which?", choices: ["a", "b"], multiSelect: true }], lockedAnswers: {}, serverRequest: false, sessionId: "s",
    });
    const batch = run(srq("srq-b", "clarify", {
      question: "ignored",
      questions: [{ qid: "q1", question: "One?" }, { qid: "q2", question: "Two?", choices: ["x"] }, { qid: "q3" }],
      answers: { q1: "yes" },
    }));
    expect(batch.clarify?.questions.map((q) => q.qid)).toEqual(["q1", "q2"]);
    expect(batch.clarify?.serverRequest).toBe(true);
    expect(batch.clarify?.requestId).toBe("srq-b");
    expect(currentQuestion(batch.clarify!)?.qid).toBe("q2");
  });

  it("a newer legacy clarify replaces the older; message.delta clears it", () => {
    let s = run(ev("clarify.request", { request_id: "r1", question: "a" }), ev("clarify.request", { request_id: "r2", question: "b" }));
    expect(s.clarify?.requestId).toBe("r2");
    s = reduceQuestions(s, ev("message.delta", { text: "…" }));
    expect(s.clarify).toBeNull();
  });
});

describe("answer planners", () => {
  it("new-protocol approval → request.answer {id, result:{choice}}; legacy → approval.respond", () => {
    for (const choice of ["once", "session", "always", "deny"] as const) {
      const s = run(srq("srq-1", "approval"), srq("srq-2", "approval"));
      const plan = planApprovalAnswer(s, choice, "live")!;
      expect(plan.rpc).toEqual({ method: "request.answer", params: { id: "srq-1", result: { choice } } });
      expect(plan.next.approval?.serverRequestId).toBe("srq-2");
      expect(plan.reportsStatus).toBe(true);
    }
    const legacy = planApprovalAnswer(run(ev("approval.request", { command: "x" })), "deny", "live")!;
    expect(legacy.rpc).toEqual({ method: "approval.respond", params: { session_id: "live", choice: "deny" } });
    expect(legacy.reportsStatus).toBe(false);
    expect(planApprovalAnswer(initialQuestionState, "once", "live")).toBeNull();
  });

  it("single new-protocol clarify without qid → request.answer {answer}", () => {
    const s = run(srq("srq-c", "clarify", { question: "?" }));
    const plan = planClarifyAnswer(s, "blue", "live")!;
    expect(plan.rpc).toEqual({ method: "request.answer", params: { id: "srq-c", result: { answer: "blue" } } });
    expect(plan.next.clarify).toBeNull();
    expect(plan.onFailure).toBe(s);
  });

  it("a one-element batch (real qid) is locked with clarify.lock, never request.answer", () => {
    const s = run(srq("srq-c", "clarify", { questions: [{ qid: "q1", question: "?" }] }));
    const plan = planClarifyAnswer(s, "blue", "live")!;
    expect(plan.rpc).toEqual({ method: "clarify.lock", params: { request_id: "srq-c", question_id: "q1", answer: "blue" } });
    expect(plan.finishes).toBe(true);
    expect(plan.next.clarify).toBeNull();
  });

  it("batch clarify locks per question and advances; the last lock finishes", () => {
    let s: QuestionState = run(srq("srq-b", "clarify", { questions: [{ qid: "q1", question: "1?" }, { qid: "q2", question: "2?" }] }));
    const first = planClarifyAnswer(s, "a", "live")!;
    expect(first.rpc.params).toEqual({ request_id: "srq-b", question_id: "q1", answer: "a" });
    expect(first.finishes).toBe(false);
    expect(first.next.clarify?.lockedAnswers).toEqual({ q1: "a" });
    s = first.next;
    const second = planClarifyAnswer(s, "b", "live")!;
    expect(second.rpc.params).toEqual({ request_id: "srq-b", question_id: "q2", answer: "b" });
    expect(second.finishes).toBe(true);
    expect(second.next.clarify).toBeNull();
    expect(second.onFailure.clarify?.lockedAnswers).toEqual({ q1: "a" });
  });

  it("legacy clarify uses clarify.respond, with question_id only for a qid", () => {
    const single = planClarifyAnswer(run(ev("clarify.request", { request_id: "r1", question: "?" })), "x", "live")!;
    expect(single.rpc).toEqual({ method: "clarify.respond", params: { session_id: "live", request_id: "r1", answer: "x" } });
    const batch = planClarifyAnswer(run(ev("clarify.request", { request_id: "r2", questions: [{ qid: "q1", question: "?" }] })), "y", "live")!;
    expect(batch.rpc).toEqual({ method: "clarify.respond", params: { session_id: "live", request_id: "r2", answer: "y", question_id: "q1" } });
  });

  it("skip and cancel-all send an empty answer without question id", () => {
    const s = run(srq("srq-b", "clarify", { questions: [{ qid: "q1", question: "1?" }, { qid: "q2", question: "2?" }] }));
    expect(planClarifySkip(s, "live")!.rpc).toEqual({ method: "request.answer", params: { id: "srq-b", result: { answer: "" } } });
    expect(planClarifyCancelAll(s, "live")!.next.clarify).toBeNull();
    const legacy = run(ev("clarify.request", { request_id: "r", questions: [{ qid: "q1", question: "?" }] }));
    expect(planClarifySkip(legacy, "live")!.rpc).toEqual({ method: "clarify.respond", params: { session_id: "live", request_id: "r", answer: "" } });
  });

  it("interprets {status:'expired'} as HR-APPROVAL-003 / HR-CLARIFY-001", () => {
    expect(interpretAnswer({ kind: "approval", reportsStatus: true }, { status: "expired" })).toEqual({ expired: true, code: "HR-APPROVAL-003", remaining: null });
    expect(interpretAnswer({ kind: "clarify", reportsStatus: true }, { status: "expired" })).toMatchObject({ expired: true, code: "HR-CLARIFY-001" });
    expect(interpretAnswer({ kind: "clarify", reportsStatus: true }, { status: "ok", remaining: 1 })).toEqual({ expired: false, code: null, remaining: 1 });
    // Legacy approval.respond answers nothing checkable.
    expect(interpretAnswer({ kind: "approval", reportsStatus: false }, { status: "expired" }).expired).toBe(false);
    expect(interpretAnswer({ kind: "approval", reportsStatus: true }, null).expired).toBe(false);
    const withCard = run(srq("srq-c", "clarify", { question: "?" }), srq("srq-a", "approval"));
    expect(afterExpired(withCard, "clarify").clarify).toBeNull();
    expect(afterExpired(withCard, "approval")).toBe(withCard);
  });
});
