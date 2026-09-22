// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import type { ChatItem } from "../chat/model";
import { searchHits } from "./ChatSheets";

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
});
