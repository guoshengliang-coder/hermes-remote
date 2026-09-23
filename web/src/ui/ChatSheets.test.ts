// @vitest-environment jsdom
import { h, render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { initialChatState, reduceChat, type ChatItem, type ChatState } from "../chat/model";
import type { MessageRow } from "../hermes/types";
import { searchHits, useShareTranscript, type ShareTranscript } from "./ChatSheets";

const item = (key: string, role: ChatItem["role"], text: string, extra: Partial<ChatItem> = {}): ChatItem => ({
  key, role, text, attachments: [], images: [], reasoning: "", tools: [], streaming: false, timestampMs: null, ...extra,
});

describe("in-chat search (HG-45: visible body text only)", () => {
  it("counts each occurrence, case-insensitive, across user and assistant turns", () => {
    const hits = searchHits([item("u", "user", "Deploy the Gateway"), item("a", "assistant", "**deploy** done; deploy again")], "deploy");
    expect(hits).toEqual([{ key: "u", nth: 0 }, { key: "a", nth: 0 }, { key: "a", nth: 1 }]);
  });

  it("ignores reasoning, tools, timeline notes and Markdown syntax", () => {
    const hits = searchHits(
      [
        item("a", "assistant", "see [docs](https://x.test)", { reasoning: "docs", tools: [{ id: "t", name: "docs", output: "docs", done: true }] }),
        item("n", "user", "docs", { note: { glyph: "", zh: "", en: "", expandable: true } }),
      ],
      "docs",
    );
    expect(hits).toEqual([{ key: "a", nth: 0 }]);
    expect(searchHits([item("a", "assistant", "x")], "  ")).toEqual([]);
    expect(searchHits([item("a", "assistant", "a **b** c")], "**")).toEqual([]);
  });

  // HG-106. The CJK emphasis repair inserts zero-width spaces, but only on the way into the DOM
  // (Markdown.tsx). searchHits reads the message text, so the hit COUNT this produces — which is
  // what the highlight walker indexes into by `nth` — must be identical either way. If a future
  // change ever moves the repair into the shared render path, this goes red.
  it("counts hits off the original text, not the display repair", () => {
    const source = "**事实｜来源：**2026-08 的薪酬表；**边界：**来源未核验";
    expect(searchHits([item("a", "assistant", source)], "来源")).toEqual([
      { key: "a", nth: 0 },
      { key: "a", nth: 1 },
    ]);
    expect(searchHits([item("a", "assistant", source)], "​")).toEqual([]);
  });
});

describe("share transcript (HG-104: the whole conversation, not only the loaded pages)", () => {
  const turn = (id: number) => ({ id, role: id % 2 ? "user" : "assistant", content: `m${id}`, timestamp: id });
  const range = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => turn(from + i));
  let root: HTMLDivElement;
  let latest: ShareTranscript;

  function Probe({ open, state, loadFull }: { open: boolean; state: ChatState; loadFull: (() => Promise<MessageRow[]>) | null }) {
    latest = useShareTranscript(open, state, loadFull);
    return null;
  }
  function mount(open: boolean, state: ChatState, loadFull: (() => Promise<MessageRow[]>) | null) {
    act(() => render(h(Probe, { open, state, loadFull }), root));
  }
  const settle = () => act(async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
  });

  beforeEach(() => {
    root = document.createElement("div");
    document.body.appendChild(root);
  });
  afterEach(() => {
    render(null, root);
    root.remove();
  });

  it("uses the items on screen when nothing older exists, without fetching", () => {
    const state = reduceChat(initialChatState, { type: "history", rows: range(1, 40), hasOlder: false });
    const loadFull = vi.fn(async () => [] as MessageRow[]);
    mount(true, state, loadFull);
    expect(latest.items).toBe(state.items);
    expect(loadFull).not.toHaveBeenCalled();
  });

  it("fetches the whole conversation while older pages exist", async () => {
    const state = reduceChat(initialChatState, { type: "history", rows: range(101, 200), hasOlder: true });
    const loadFull = vi.fn(async () => range(1, 200) as MessageRow[]);
    mount(true, state, loadFull);
    expect(latest.items).toBeNull();
    expect(latest.loading).toBe(true);
    await settle();
    expect(loadFull).toHaveBeenCalledTimes(1);
    expect(latest.loading).toBe(false);
    expect(latest.items).toHaveLength(200);
  });

  it("a failed fetch is a retryable HR-SYNC-001; Retry fetches again", async () => {
    const state = reduceChat(initialChatState, { type: "history", rows: range(101, 200), hasOlder: true });
    let fail = true;
    const loadFull = vi.fn(async () => {
      if (fail) throw new Error("offline");
      return range(1, 200) as MessageRow[];
    });
    mount(true, state, loadFull);
    await settle();
    expect(latest.items).toBeNull();
    expect(latest.error).toMatchObject({ code: "HR-SYNC-001", retryable: true });
    fail = false;
    act(() => latest.retry());
    await settle();
    expect(loadFull).toHaveBeenCalledTimes(2);
    expect(latest.error).toBeNull();
    expect(latest.items).toHaveLength(200);
  });
});
