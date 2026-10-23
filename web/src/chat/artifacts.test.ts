import { describe, expect, it } from "vitest";
import { conversationArtifacts } from "./artifacts";
import { historyItems, initialChatState, reduceChat, type ChatItem } from "./model";
import { classifyAttachment } from "../hermes/media";
import { appError } from "../errors";

const item = (key: string, path: string, extra: Partial<ChatItem> = {}): ChatItem => ({
  key, role: "assistant", text: "", attachments: [classifyAttachment(path)], images: [],
  reasoning: "", tools: [], streaming: false, timestampMs: null, ...extra,
});
describe("conversation artifacts", () => {
  it("clears the history failure after a successful retry", () => {
    const error = appError("HR-SYNC-001", "history failed");
    let state = reduceChat(initialChatState, { type: "history-failed", error });
    state = reduceChat(state, { type: "notice", error });
    expect(state.historyError).toBe(error);
    state = reduceChat(state, { type: "history", rows: [{ id: 1, role: "assistant", content: "MEDIA:/tmp/recovered.md" }] });
    expect(state.historyError).toBeNull();
    expect(state.notice).toBeNull();
    expect(conversationArtifacts(state.items)[0]?.attachment.name).toBe("recovered.md");
  });
  it("excludes uploads, user references, tools, timeline notes and unfinished delivery", () => {
    const rows = historyItems([
      { id: 1, role: "user", content: "@file:/tmp/input.pdf" },
      { id: 2, role: "assistant", content: "@file:/tmp/input.pdf", tool_calls: [{ id: "t", type: "function", function: { name: "run", arguments: "{}" } }] },
      { id: 3, role: "tool", tool_call_id: "t", content: "MEDIA:/tmp/process.html" },
      { id: 4, role: "user", content: "继续" },
      { id: 5, role: "assistant", content: "MEDIA:/tmp/result.md" },
    ]);
    expect(conversationArtifacts([...rows, item("stream", "/tmp/incomplete.png", { streaming: true }),
      item("sys", "/tmp/sys.pdf", { role: "system" })]).map((a) => a.attachment.path)).toEqual(["/tmp/result.md"]);
    // Organizing a previously organized item cannot promote process files into deliveries.
    expect(rows[1]?.deliveredAttachments?.map((a) => a.path)).toEqual(["/tmp/input.pdf"]);
  });
  it("deduplicates by full path, keeps latest origin, preserves unknown time and distinguishes names", () => {
    const artifacts = conversationArtifacts([
      item("old", "/a/report.md", { timestampMs: 100 }),
      item("other", "/b/report.md"),
      item("reserved", "/c/report (2).md"),
      item("latest", "/a/report.md", { timestampMs: 200 }),
    ]);
    expect(artifacts.map((a) => [a.messageKey, a.displayName, a.timestampMs])).toEqual([
      ["latest", "report.md", 200], ["reserved", "report (2).md", null], ["other", "report (3).md", null],
    ]);
    expect(artifacts[2]?.attachment.name).toBe("report.md");
  });
  it("accepts local Markdown delivery links but ignores code examples and external links", () => {
    const rows = [item("links", "/tmp/a.md", { text: "[网页](/tmp/page.html)\n\n![图](/tmp/chart.png)\n\n[外链](https://example.com/out.pdf)\n\n```md\n[示例](/tmp/example.pdf)\n```" })];
    expect(conversationArtifacts(rows).map((a) => a.attachment.path)).toEqual(["/tmp/chart.png", "/tmp/page.html", "/tmp/a.md"]);
  });
  it("does not promote media inside flattened tool payloads to delivery", () => {
    const rows = historyItems([{ id: 1, role: "assistant", content: '检查结果\n<untrusted_tool_result source="run">\nMEDIA:/tmp/process.pdf\n</untrusted_tool_result>\n完成' }]);
    expect(conversationArtifacts(rows)).toEqual([]);
  });
  it("includes newly delivered files after a completed turn reopens for another tool segment", () => {
    let state = reduceChat(initialChatState, { type: "event", event: { type: "message.complete", sessionId: "s", payload: { text: "MEDIA:/tmp/first.md" } } });
    state = reduceChat(state, { type: "event", event: { type: "message.delta", sessionId: "s", payload: { text: "\nMEDIA:/tmp/second.md" } } });
    state = reduceChat(state, { type: "event", event: { type: "message.complete", sessionId: "s", payload: {} } });
    expect(conversationArtifacts(state.items).map((a) => a.attachment.path)).toEqual(["/tmp/second.md", "/tmp/first.md"]);
  });
});
