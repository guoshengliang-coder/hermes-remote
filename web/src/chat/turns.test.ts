import { describe, expect, it } from "vitest";
import type { ChatItem } from "./model";
import { pillGroup, promptSummary, turnGroups } from "./turns";

const item = (key: string, role: ChatItem["role"], text: string, extra: Partial<ChatItem> = {}): ChatItem => ({
  key, role, text, attachments: [], images: [], reasoning: "", tools: [], streaming: false, timestampMs: null, ...extra,
});

describe("turn groups (TurnJump.kt)", () => {
  it("one group per real prompt; notes do not count; content before the first prompt is the start group", () => {
    const groups = turnGroups([
      item("a0", "assistant", "hello"),
      item("u1", "user", "\n  first line\nsecond"),
      item("n", "user", "x", { note: { glyph: "", zh: "", en: "", expandable: false } }),
      item("a1", "assistant", "ok"),
      item("u2", "user", "", { localImages: ["blob:1", "blob:2"] }),
    ]);
    expect(groups.map((g) => [g.key, g.summary.zh])).toEqual([[null, "会话开始"], ["u1", "first line"], ["u2", "图片 ×2"]]);
    expect(turnGroups([item("u1", "user", "hi")]).map((g) => g.key)).toEqual(["u1"]);
  });

  it("an attachment-only prompt names its file", () => {
    expect(promptSummary(item("u", "user", "", { localFiles: ["report.pdf"] })).zh).toBe("文件：report.pdf");
  });

  it("shows the group at the viewport top only while its prompt is off screen", () => {
    const groups = turnGroups([item("u1", "user", "one"), item("a", "assistant", "…"), item("u2", "user", "two")]);
    const tops = new Map([["u1", { top: 0, bottom: 40 }], ["u2", { top: 1000, bottom: 1040 }]]);
    expect(pillGroup(groups, tops, 20)).toBeNull(); // u1's bubble still visible
    expect(pillGroup(groups, tops, 500)?.key).toBe("u1"); // scrolled past it, inside its answer
    expect(pillGroup(groups, tops, 1010)).toBeNull(); // u2's bubble on screen
    expect(pillGroup(groups, tops, 1100)?.key).toBe("u2");
  });
});
