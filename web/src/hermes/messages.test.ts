import { describe, expect, it } from "vitest";
import { flattenContent, messageTimestampMs, parseHistory, toolLabel } from "./messages";
import type { MessageRow } from "./types";

describe("flattenContent", () => {
  it("accepts a plain string and null", () => {
    expect(flattenContent("hello")).toEqual({ text: "hello", images: [] });
    expect(flattenContent(null)).toEqual({ text: null, images: [] });
    expect(flattenContent(undefined)).toEqual({ text: null, images: [] });
  });

  it("joins text blocks and keeps only renderable image references", () => {
    const flat = flattenContent([
      { type: "text", text: "look" },
      { type: "image_url", image_url: { url: "data:image/png;base64,AAAA" } },
      { type: "image_url", image_url: { url: "https://cdn.example/a.png" } },
      { type: "input_image", path: "/Users/me/a.jpg" },
      { type: "image_url", url: "[image]" },
      { type: "mystery", foo: 1 },
      { type: "text", text: "  " },
      { type: "text", text: "twice" },
    ]);
    expect(flat).toEqual({ text: "look\ntwice", images: [{ url: "https://cdn.example/a.png" }, { path: "/Users/me/a.jpg" }] });
  });

  it("accepts a single block object and blank-only lists", () => {
    expect(flattenContent({ type: "text", text: "one" })).toEqual({ text: "one", images: [] });
    expect(flattenContent([{ type: "text", text: " " }])).toEqual({ text: null, images: [] });
  });
});

describe("messageTimestampMs", () => {
  it("reads float seconds and ignores non-positive values", () => {
    expect(messageTimestampMs(1_758_000_000.5)).toBe(1_758_000_000_500);
    expect(messageTimestampMs(0)).toBeNull();
    expect(messageTimestampMs(null, "2026-09-21T10:00:00Z")).toBe(Date.parse("2026-09-21T10:00:00Z"));
    expect(messageTimestampMs(null, "2026-09-21T10:00:00")).toBe(Date.parse("2026-09-21T10:00:00Z"));
    expect(messageTimestampMs(null, "garbage")).toBeNull();
  });
});

describe("parseHistory", () => {
  const rows: MessageRow[] = [
    { id: 1, role: "user", content: "hi\n@image:/tmp/a.png", timestamp: 100 },
    {
      id: 2,
      role: "assistant",
      content: "working",
      reasoning: "r1",
      reasoning_content: "r2",
      tool_calls: [
        { id: "c1", function: { name: "terminal", arguments: '{"command":"ls"}' } },
        { id: "c2", function: { name: "tool_call", arguments: '{"name":"gmail.read"}' } },
        { function: { name: "" } },
      ],
      timestamp: 101,
    },
    { id: 3, role: "tool", tool_call_id: "c1", tool_name: "terminal", content: "file.txt" },
    { id: 4, role: "tool", tool_call_id: "c2", content: [{ type: "text", text: "3 unread" }] },
    { id: 5, role: "assistant", content: "done MEDIA:/Users/bs/out.pdf", display_kind: "model_switch", display_metadata: { task_count: 2 } },
    { id: 6, role: "system", content: "" },
    { id: 7, role: "assistant", content: null, tool_calls: '[{"id":"c9","function":{"name":"web"}}]' },
  ];
  const history = parseHistory(rows);

  it("drops tool rows as turns and filters unrenderable rows", () => {
    expect(history.map((m) => m.rowId)).toEqual([1, 2, 5, 7]);
    expect(history.map((m) => m.key)).toEqual(["h-0-1", "h-1-2", "h-2-5", "h-4-7"]);
  });

  it("maps user text, attachments and timestamps", () => {
    expect(history[0]).toMatchObject({ role: "user", text: "hi", timestampMs: 100_000, reasoning: "", tools: [] });
    expect(history[0]!.attachments.map((a) => a.path)).toEqual(["/tmp/a.png"]);
  });

  it("groups tool results onto the assistant's calls and prefers reasoning_content", () => {
    const m = history[1]!;
    expect(m.reasoning).toBe("r2");
    expect(m.tools).toEqual([
      { id: "c1", name: "terminal", arguments: '{"command":"ls"}', output: "file.txt", hasResult: true },
      { id: "c2", name: "gmail.read", arguments: '{"name":"gmail.read"}', output: "3 unread", hasResult: true },
    ]);
  });

  it("keeps display_kind and parses stringified tool_calls without results", () => {
    expect(history[2]).toMatchObject({ text: "done", displayKind: "model_switch", displayMetadata: { task_count: 2 } });
    expect(history[2]!.attachments[0]).toMatchObject({ kind: "download", path: "/Users/bs/out.pdf" });
    expect(history[3]!.tools).toEqual([{ id: "c9", name: "web", arguments: null, output: "", hasResult: false }]);
  });

  it("handles block-list content on a history row", () => {
    const [m] = parseHistory([{ id: 9, role: "user", content: [{ type: "text", text: "see" }, { type: "image_url", image_url: { url: "/Users/me/p.png" } }] }]);
    expect(m).toMatchObject({ text: "see", images: [{ path: "/Users/me/p.png" }] });
  });
});

describe("toolLabel", () => {
  it("resolves the dynamic tool_call wrapper", () => {
    expect(toolLabel("terminal", { name: "x" })).toBe("terminal");
    expect(toolLabel("tool_call", { name: "mcp.a" })).toBe("mcp.a");
    expect(toolLabel("tool_call", null)).toBe("tool_call");
  });
});
