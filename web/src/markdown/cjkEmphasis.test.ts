import { describe, expect, it } from "vitest";
import { withCjkEmphasisRepaired } from "./cjkEmphasis";

// Regression for HG-106, ported case for case from the Android suite this fix comes from
// (HG-24, `CjkEmphasisTest.kt`). The expectations were checked against a CommonMark reference
// implementation first: the "broken" strings really do render with literal asterisks, and the
// repaired ones really do produce `<strong>`.
const ZWSP = "​";

describe("withCjkEmphasisRepaired", () => {
  it("repairs a closing run against chinese punctuation", () => {
    const broken = "**关键问题：你有没有已经托管在 Cloudflare 的域名？**有的话可以直接迁移";
    expect(withCjkEmphasisRepaired(broken)).toBe(
      `**关键问题：你有没有已经托管在 Cloudflare 的域名？${ZWSP}**有的话可以直接迁移`,
    );
  });

  it("repairs an opening run followed by punctuation", () => {
    expect(withCjkEmphasisRepaired("见**「关键问题」**说明")).toBe(`见**${ZWSP}「关键问题」${ZWSP}**说明`);
  });

  // Everything CommonMark already parses must come back character for character — the repair is
  // not a reformatter, and a zero-width character in text that did not need one is litter.
  it("leaves text that already renders untouched", () => {
    for (const text of [
      "**bold**text",
      "**关键问题**：后面是中文",
      "**关键问题：域名？** 有的话",
      "英文 **bold phrase** 后面",
      "没有任何强调的一句话",
      "a * b * c",
    ]) {
      expect(withCjkEmphasisRepaired(text)).toBe(text);
    }
  });

  // Asterisks inside code are content, not markup. Rewriting them would change what the reader is
  // being shown a copy of.
  it("passes code through unchanged", () => {
    const inline = "用 `**kwargs：**args` 传参";
    expect(withCjkEmphasisRepaired(inline)).toBe(inline);

    const fenced = '```python\nprint(f"**总计：{n}**行")\n```';
    expect(withCjkEmphasisRepaired(fenced)).toBe(fenced);
  });

  // An unmatched delimiter has no pair to repair, so it must be left exactly as it is rather than
  // picking up a stray zero-width space.
  it("does not rewrite a lone delimiter", () => {
    for (const text of ["请看第 **3 条", "总计 ** 三项", "**", "a**b"]) {
      expect(withCjkEmphasisRepaired(text)).toBe(text);
    }
  });

  it("repairs every emphasis in a paragraph independently", () => {
    const broken = "**第一点。**说明；**第二点**：说明；**第三点？**说明";
    const repaired = withCjkEmphasisRepaired(broken);

    expect(repaired).toBe(`**第一点。${ZWSP}**说明；**第二点**：说明；**第三点？${ZWSP}**说明`);
    // The middle one already worked and must not have gained a marker.
    expect(repaired).toContain("**第二点**：");
  });

  // The sentence from the HG-106 screenshot, which is what reported this.
  it("repairs the reported sentence", () => {
    const broken = "**事实｜来源：**2026-08《小迈科技薪酬表》薪酬档案快照";
    expect(withCjkEmphasisRepaired(broken)).toBe(
      `**事实｜来源：${ZWSP}**2026-08《小迈科技薪酬表》薪酬档案快照`,
    );
  });
});
