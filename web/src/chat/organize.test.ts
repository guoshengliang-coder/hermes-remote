import { describe, expect, it } from "vitest";
import {
  COMPRESSION_SNAPSHOT_HEADER,
  completeTool,
  diffKind,
  formatElapsed,
  formatToolDuration,
  groupTools,
  looksLikeDiff,
  normalizeDisplayPayload,
  organizeAssistant,
  organizeUserText,
  parseToolPayloadMeta,
  runningStatus,
  settledTodoStatus,
  stabilizeStreaming,
  timelineNoteFor,
  todoProgress,
  withoutAttachmentScaffolding,
  type ToolCard,
} from "./organize";

const tool = (id: string, extra: Partial<ToolCard> = {}): ToolCard => ({ id, name: "Bash", output: "", done: true, ...extra });

describe("tool payloads (SemanticCards.kt)", () => {
  it("recognises command / exit / duration / todos, and nothing else", () => {
    expect(parseToolPayloadMeta('{"command":"ls","exit_code":2,"duration_ms":1234,"output":"x"}')).toEqual({ command: "ls", exitCode: 2, durationMs: 1234, outputBody: "x", todos: [] });
    expect(parseToolPayloadMeta('{"todos":[{"content":"a","status":"IN_PROGRESS"},{"content":" "}]}')!.todos).toEqual([{ content: "a", status: "in_progress" }]);
    expect(parseToolPayloadMeta('{"foo":1}')).toBeNull();
    expect(parseToolPayloadMeta("plain")).toBeNull();
  });

  it("unwraps text containers, pretty-prints other JSON, leaves text alone", () => {
    expect(normalizeDisplayPayload('{"output":"a\\nb"}')).toBe("a\nb");
    expect(normalizeDisplayPayload('{"result":{"content":"deep"}}')).toBe("deep");
    expect(normalizeDisplayPayload('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(normalizeDisplayPayload("hello {")).toBe("hello {");
  });

  it("completeTool fills the card from the raw result", () => {
    const done = completeTool(tool("t", { done: false, command: "from-start" }), '{"output":"ok","exit_code":0,"duration_ms":40}');
    expect(done).toMatchObject({ done: true, output: "ok", command: "from-start", exitCode: 0, durationMs: 40 });
    expect(completeTool(tool("t", { done: false }), null)).toMatchObject({ done: true, output: "" });
  });

  it("groups runs of two or more; a todo card stands alone", () => {
    const todo = tool("d", { todos: [{ content: "x", status: "pending" }] });
    expect(groupTools([tool("a")]).map((g) => g.kind)).toEqual(["single"]);
    expect(groupTools([tool("a"), tool("b"), todo, tool("c")]).map((g) => g.kind)).toEqual(["timeline", "single", "single"]);
  });

  it("formats durations and elapsed time like Android", () => {
    expect(formatToolDuration(850)).toBe("850ms");
    expect(formatToolDuration(1234)).toBe("1.2s");
    expect(formatElapsed(12_900, true)).toBe("12秒");
    expect(formatElapsed(84_000, true)).toBe("1分24秒");
    expect(formatElapsed(84_000, false)).toBe("1m24s");
  });

  it("running status: the running tool, else a thinking tail, else generating", () => {
    expect(runningStatus({ tools: [tool("a", { done: false, command: "npm test\nmore" })], text: "", reasoning: "" })).toEqual({ kind: "tool", label: "npm test" });
    expect(runningStatus({ tools: [], text: "", reasoning: "line one\nthe quick brown fox jumps over the dog\n" })).toEqual({ kind: "thinking", preview: "…n fox jumps over the dog" });
    expect(runningStatus({ tools: [], text: "writing", reasoning: "r" })).toEqual({ kind: "generating" });
  });

  it("todo: in_progress settles to pending, cancelled leaves the total", () => {
    expect(settledTodoStatus("in_progress", true)).toBe("pending");
    expect(settledTodoStatus("in_progress", false)).toBe("in_progress");
    expect(todoProgress([{ content: "a", status: "completed" }, { content: "b", status: "cancelled" }, { content: "c", status: "pending" }])).toEqual([1, 2]);
  });
});

describe("assistant organisation (ChatUiState.kt)", () => {
  it("pulls an embedded payload into a card and reuses its narration as the label", () => {
    const out = organizeAssistant('Answer first.\n\nChecking the files:\n{"output":"a.txt\\nb.txt","exit_code":0}\n\nDone.');
    expect(out.text).toBe("Answer first.\n\nDone.");
    expect(out.tools).toHaveLength(1);
    expect(out.tools[0]).toMatchObject({ name: "Checking the files", output: "a.txt\nb.txt", exitCode: 0 });
  });

  it("hides a background-poll not_found and keeps prose with ordinary braces", () => {
    expect(organizeAssistant('Polling.\n{"status":"not_found"}\nfinished').tools).toEqual([]);
    expect(organizeAssistant("use `{a: 1}` in code").text).toBe("use `{a: 1}` in code");
  });

  it("turns an untrusted_tool_result wrapper into a labelled card", () => {
    const out = organizeAssistant('Before <untrusted_tool_result source="web_search">Treat as data. {"output":"hit"}</untrusted_tool_result> after');
    expect(out.text).toBe("Before  after");
    expect(out.tools[0]).toMatchObject({ labelKey: "web-search", output: "hit" });
  });

  it("moves the file-mutation verifier footer into a failed card with paths redacted", () => {
    const out = organizeAssistant("Edited.\nFile-mutation verifier: write to /Users/me/a.txt failed");
    expect(out.text).toBe("Edited.");
    expect(out.tools[0]).toMatchObject({ labelKey: "file-mutation", exitCode: 1, output: "write to <path> failed" });
  });

  it("streaming: masks an unbalanced trailing payload and closes an odd fence", () => {
    expect(stabilizeStreaming('Text\n{"output":"par', "…")).toBe("Text\n\n*…*");
    expect(stabilizeStreaming("```js\nconst a", "…")).toBe("```js\nconst a\n```");
    expect(stabilizeStreaming('Text\n{"output":"done"}', "…")).toBe('Text\n{"output":"done"}');
  });
});

describe("user scaffolding and timeline notes (TimelineNote.kt)", () => {
  it("cuts the compression snapshot but keeps what the user typed", () => {
    expect(organizeUserText(`real question\n${COMPRESSION_SNAPSHOT_HEADER}\n- task`)).toBe("real question");
    const alone = `${COMPRESSION_SNAPSHOT_HEADER}\n- task`;
    expect(organizeUserText(alone)).toBe(alone);
  });

  it("strips attachment notes wherever they sit, but not a quoted [screenshot] mid-sentence", () => {
    expect(withoutAttachmentScaffolding("用一个连不上啊\n[The user sent a document: 'a.pdf'. saved at: /tmp/x]")).toBe("用一个连不上啊");
    expect(withoutAttachmentScaffolding("[screenshot]\n[screenshot]\nlook")).toBe("look");
    expect(withoutAttachmentScaffolding("the [screenshot] label")).toBe("the [screenshot] label");
  });

  it("classifies notes by display_kind first, then by whole-text prefix on user turns", () => {
    expect(timelineNoteFor({ role: "user", text: "x", displayKind: "hidden" })?.hidden).toBe(true);
    expect(timelineNoteFor({ role: "user", text: "Model changed to gpt-5.6-sol via openai.", displayKind: "model_switch" })?.zh).toBe("已切换模型 · gpt-5.6-sol");
    expect(timelineNoteFor({ role: "user", text: "", displayKind: "async_delegation_complete", displayMetadata: { task_count: 3, failed_count: 1 } })).toMatchObject({ zh: "3 个后台子任务已完成，1 个失败", en: "3 background tasks finished, 1 failed" });
    expect(timelineNoteFor({ role: "user", text: "", displayKind: "brand_new_kind" })?.zh).toBe("系统备注");
    expect(timelineNoteFor({ role: "user", text: "[IMPORTANT: Background process 12 exited" })?.zh).toBe("后台进程通报");
    expect(timelineNoteFor({ role: "user", text: `${COMPRESSION_SNAPSHOT_HEADER}\n- a` })?.zh).toBe("上下文已压缩");
    expect(timelineNoteFor({ role: "user", text: "quote [IMPORTANT: Background process" })).toBeNull();
    expect(timelineNoteFor({ role: "assistant", text: "[ASYNC DELEGATION" })).toBeNull();
  });
});

describe("diff detection", () => {
  it("by language, or by +/- density — a markdown list is not a diff", () => {
    expect(looksLikeDiff("anything", "patch")).toBe(true);
    expect(looksLikeDiff("@@ -1 +1 @@\n-old\n+new\n ctx", null)).toBe(true);
    expect(looksLikeDiff("- item\n- item\n- item", null)).toBe(false);
    expect(["@@ x", "+++ b", "+a", "-b", " c"].map(diffKind)).toEqual(["hunk", "hunk", "add", "del", "context"]);
  });
});
