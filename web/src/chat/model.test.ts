import { describe, expect, it } from "vitest";
import { appError } from "../errors";
import type { JsonObject, ServerEvent } from "../hermes/types";
import { hasOpenQuestion, initialChatState, reduceChat, type ChatAction, type ChatState } from "./model";

const e = (type: string, payload: JsonObject = {}): ChatAction => ({ type: "event", event: { type, sessionId: "s", payload } as ServerEvent });
const run = (state: ChatState, ...actions: ChatAction[]) => actions.reduce(reduceChat, state);

describe("streaming", () => {
  it("start/delta/complete builds one assistant turn and ends generating", () => {
    let s = run(initialChatState, e("message.start"), e("message.delta", { text: "Hel" }), e("message.delta", { text: "lo" }));
    expect(s.generating).toBe(true);
    expect(s.items).toHaveLength(1);
    expect(s.items[0]).toMatchObject({ role: "assistant", text: "Hello", streaming: true });
    s = reduceChat(s, e("message.complete", { text: "Hello!" }));
    expect(s.items[0]).toMatchObject({ text: "Hello!", streaming: false });
    expect(s.generating).toBe(false);
  });

  it("a delta without start recovers a streaming assistant; a replayed start adds no duplicate", () => {
    const s = run(initialChatState, e("message.delta", { text: "a" }), e("message.start"), e("message.delta", { text: "b" }));
    expect(s.items).toHaveLength(1);
    expect(s.items[0]!.text).toBe("ab");
  });

  it("complete without deltas still shows the final text", () => {
    const s = reduceChat(initialChatState, e("message.complete", { text: "final" }));
    expect(s.items[0]).toMatchObject({ role: "assistant", text: "final", streaming: false });
  });

  it("MEDIA tags become attachments while streaming", () => {
    const s = run(initialChatState, e("message.delta", { text: "Here:\nMEDIA:/Users/me/out/chart.png\n" }));
    expect(s.items[0]!.text).toBe("Here:");
    expect(s.items[0]!.attachments).toMatchObject([{ kind: "image", path: "/Users/me/out/chart.png" }]);
  });

  it("reasoning and tools attach to the streaming turn; tool.complete fills the output", () => {
    const s = run(
      initialChatState,
      e("reasoning.delta", { text: "think" }),
      e("tool.start", { tool_id: "t1", name: "Bash" }),
      e("tool.complete", { tool_id: "t1", result: "ok" }),
    );
    expect(s.items[0]!.reasoning).toBe("think");
    expect(s.items[0]!.tools).toMatchObject([{ id: "t1", name: "Bash", output: "ok", done: true }]);
  });

  it("a new user turn starts a new assistant turn", () => {
    const s = run(
      initialChatState,
      e("message.complete", { text: "one" }),
      { type: "user-sent", key: "l-1", text: "again", nowMs: 1 },
      e("message.delta", { text: "two" }),
    );
    expect(s.items.map((i) => [i.role, i.text])).toEqual([["assistant", "one"], ["user", "again"], ["assistant", "two"]]);
  });

  it("interrupt and session.info running=false stop streaming", () => {
    let s = run(initialChatState, e("message.delta", { text: "x" }), e("tool.start", { tool_id: "t", name: "Read" }), { type: "interrupted" });
    expect(s.generating).toBe(false);
    expect(s.items[0]).toMatchObject({ streaming: false, interrupted: true });
    expect(s.items[0]!.tools[0]!.done).toBe(true);
    s = run(initialChatState, e("message.delta", { text: "x" }), e("session.info", { running: false }));
    expect(s.generating).toBe(false);
  });

  it("after an interrupt, trailing stream events of that run are dropped until the next send", () => {
    let s = run(initialChatState, e("message.delta", { text: "x" }), { type: "interrupted" }, e("message.delta", { text: "late" }), e("message.complete", { text: "x late" }));
    expect(s.items).toHaveLength(1);
    expect(s.items[0]).toMatchObject({ text: "x", interrupted: true });
    expect(s.generating).toBe(false);
    s = run(s, { type: "user-sent", key: "l-9", text: "next", nowMs: 1 }, e("message.delta", { text: "fresh" }));
    expect(s.items.map((i) => i.text)).toEqual(["x", "next", "fresh"]);
  });

  it("an error event ends the run", () => {
    const s = run(initialChatState, e("message.delta", { text: "x" }), e("error", { message: "boom" }));
    expect(s.generating).toBe(false);
    expect(s.items[0]!.streaming).toBe(false);
  });
});

describe("user turns", () => {
  it("sending → delivered marks the run as generating; failed keeps the error for retry", () => {
    let s = reduceChat(initialChatState, { type: "user-sent", key: "l-1", text: "hi", nowMs: 1 });
    expect(s.items[0]).toMatchObject({ role: "user", text: "hi", send: "sending" });
    s = reduceChat(s, { type: "user-delivered", key: "l-1" });
    expect(s.items[0]!.send).toBeUndefined();
    expect(s.generating).toBe(true);
    const failed = run(initialChatState, { type: "user-sent", key: "l-2", text: "x", nowMs: 1 }, { type: "user-failed", key: "l-2", error: appError("HR-SESS-007") });
    expect(failed.items[0]).toMatchObject({ send: "failed", error: { code: "HR-SESS-007" } });
    const retried = reduceChat(failed, { type: "user-retry", key: "l-2" });
    expect(retried.items[0]!.send).toBe("sending");
    expect(retried.items[0]!.error).toBeUndefined();
  });
});

describe("history", () => {
  const rows = [
    { id: 1, role: "user", content: "q", timestamp: 1 },
    { id: 2, role: "assistant", content: "a", timestamp: 2, tool_calls: [{ id: "c1", function: { name: "Bash", arguments: "{}" } }] },
    { id: 3, role: "tool", content: "out", tool_call_id: "c1" },
  ];

  it("replaces the transcript and keeps unsent local turns", () => {
    const s = run(
      initialChatState,
      { type: "user-sent", key: "l-1", text: "unsent", nowMs: 1 },
      { type: "user-failed", key: "l-1", error: appError("HR-SESS-007") },
      { type: "history", rows },
    );
    expect(s.historyLoaded).toBe(true);
    expect(s.items.map((i) => i.text)).toEqual(["q", "a", "unsent"]);
    expect(s.items[1]!.tools).toMatchObject([{ id: "c1", name: "Bash", output: "out", done: true }]);
  });

  it("does not clobber a live stream with a history page", () => {
    let s = reduceChat(initialChatState, { type: "history", rows });
    s = reduceChat(s, e("message.delta", { text: "live" }));
    s = reduceChat(s, { type: "history", rows });
    expect(s.items.at(-1)!.text).toBe("live");
  });
});

describe("questions", () => {
  it("server requests and legacy events drive the sheet; request.cancel and open-requests clear it", () => {
    let s = reduceChat(initialChatState, {
      type: "server-request",
      request: { id: "srq-1", method: "approval", params: { command: "ls", allow_permanent: false }, sessionId: "live" },
    });
    expect(hasOpenQuestion(s)).toBe(true);
    expect(s.questions.approval).toMatchObject({ command: "ls", allowPermanent: false, serverRequestId: "srq-1" });
    s = reduceChat(s, e("request.cancel", { id: "srq-1" }));
    expect(hasOpenQuestion(s)).toBe(false);

    s = reduceChat(s, e("clarify.request", { request_id: "c1", question: "Which?", choices: ["A", "B"] }));
    expect(s.questions.clarify?.questions[0]).toMatchObject({ question: "Which?", choices: ["A", "B"] });

    s = reduceChat(s, { type: "server-request", request: { id: "srq-2", method: "clarify", params: { question: "Q" }, sessionId: "live" } });
    s = reduceChat(s, { type: "open-requests", snapshot: { sessionId: "live", ids: [] } });
    expect(s.questions.clarify).toBeNull();
  });

  it("the agent talking again drops a stale clarify card", () => {
    let s = reduceChat(initialChatState, e("clarify.request", { request_id: "c1", question: "Q" }));
    s = reduceChat(s, e("message.delta", { text: "moving on" }));
    expect(s.questions.clarify).toBeNull();
  });
});

describe("notices", () => {
  it("a terminal notice sticks and disables the page; reset clears everything", () => {
    let s = reduceChat(initialChatState, { type: "notice", error: appError("HR-SESS-001"), terminal: true });
    expect(s.terminal).toBe(true);
    s = reduceChat(s, { type: "user-sent", key: "l", text: "x", nowMs: 1 });
    expect(s.notice?.code).toBe("HR-SESS-001");
    expect(reduceChat(s, { type: "reset" })).toEqual(initialChatState);
  });
});

describe("history organisation (Android organizedForDisplay)", () => {
  it("folds consecutive assistant records into one turn and keeps tools from both", () => {
    const rows = [
      { id: 1, role: "user", content: "go", timestamp: 1 },
      { id: 2, role: "assistant", content: "first", timestamp: 2, tool_calls: [{ id: "c1", function: { name: "terminal", arguments: '{"command":"ls"}' } }] },
      { id: 3, role: "tool", content: '{"output":"a","exit_code":0}', tool_call_id: "c1" },
      { id: 4, role: "assistant", content: "second", timestamp: 4 },
    ];
    const s = reduceChat(initialChatState, { type: "history", rows });
    expect(s.items.map((i) => i.role)).toEqual(["user", "assistant"]);
    expect(s.items[1]).toMatchObject({ text: "first\n\nsecond", timestampMs: 2000 });
    expect(s.items[1]!.tools).toMatchObject([{ id: "c1", command: "ls", output: "a", exitCode: 0 }]);
  });

  it("drops hidden rows, notes injected turns, and cuts scaffolding from real prompts", () => {
    const rows = [
      { id: 1, role: "user", content: "secret", display_kind: "hidden" },
      { id: 2, role: "user", content: "Model changed to fable-5. ", display_kind: "model_switch" },
      { id: 3, role: "user", content: "real\n[Your active task list was preserved across context compression]\n- x" },
    ];
    const s = reduceChat(initialChatState, { type: "history", rows });
    expect(s.items).toHaveLength(2);
    expect(s.items[0]!.note?.zh).toBe("已切换模型 · fable-5");
    expect(s.items[1]).toMatchObject({ text: "real" });
    expect(s.items[1]!.note).toBeUndefined();
  });

  it("a second message.start after tools continues the same live answer", () => {
    let s = reduceChat(initialChatState, { type: "user-sent", key: "l-1", text: "hi", nowMs: 1 });
    s = reduceChat(s, e("message.start", {}));
    s = reduceChat(s, e("message.delta", { text: "part one" }));
    s = reduceChat(s, e("message.complete", { text: "part one" }));
    s = reduceChat(s, e("message.start", {}));
    s = reduceChat(s, e("message.delta", { text: "part two" }));
    s = reduceChat(s, e("message.complete", { text: "part two" }));
    expect(s.items.filter((i) => i.role === "assistant")).toHaveLength(1);
    expect(s.items.at(-1)!.text).toBe("part one\n\npart two");
  });
});
