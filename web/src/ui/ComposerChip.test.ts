import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-178. The expanded composer showed two different control rows: with the keyboard down it kept
 * the model chip (`[麦克风] [模型名 · 强度] [＋] [发送]`), and once the keyboard was up the chip
 * disappeared and the same row was right-aligned — the report's two screenshots. The cause is two
 * stylesheets, not a rendering path: `.chat-page.compact-viewport` (iOS visual viewport) and
 * `@media (max-height: 30rem)` (landscape, or Chrome shrinking the layout viewport to the space
 * above the keyboard) each listed `.model-chip` under `display: none`, and each right-aligned
 * `.composer-actions` to compensate. Hiding the chip bought no reading room — it lives inside the
 * control row that is drawn either way — so the two states now match, and match Android's
 * `ChatScreen.kt`, which draws the chip in every focused composer.
 *
 * The sheet is read as text on purpose (same as `app/pageFrame.test.ts`): media queries are not
 * applied in happy-dom, so what is pinned is the declaration that produced the second style. The
 * look itself still needs a phone — `docs/SMOKE_TEST.md`, HG-178 entry.
 */
const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

function rule(selector: string): string {
  const start = css.indexOf(`\n${selector}`);
  if (start < 0) return "";
  // Either a group member (",") or the rule head; a longer name must not match a prefix of ours.
  const after = css.slice(start + 1 + selector.length);
  if (!after.startsWith(",") && !after.startsWith(" {") && !after.startsWith("{")) return "";
  const open = css.indexOf("{", start);
  const end = css.indexOf("}", open);
  return css.slice(open + 1, end).replace(/\s+/g, " ").trim();
}

function mediaBlock(query: string): string {
  const start = css.indexOf(`@media ${query} {`);
  if (start < 0) return "";
  const open = css.indexOf("{", start);
  let depth = 0;
  for (let index = open; index < css.length; index += 1) {
    if (css[index] === "{") depth += 1;
    else if (css[index] === "}") {
      depth -= 1;
      if (depth === 0) return css.slice(open + 1, index);
    }
  }
  return "";
}

describe("one expanded composer, with or without the keyboard (HG-178)", () => {
  it("the compact visual viewport keeps the model chip", () => {
    expect(rule(".chat-page.compact-viewport .model-chip")).toBe("");
    expect(css).not.toContain(".chat-page.compact-viewport .model-chip");
  });

  it("the short-screen query keeps the model chip too", () => {
    expect(mediaBlock("(max-height: 30rem)")).not.toContain(".model-chip");
  });

  it("still trims the chrome that costs no composer row", () => {
    // The project / branch line and the progress line go; the change is deliberately narrow.
    expect(rule(".chat-page.compact-viewport .chat-title .chat-workspace")).toContain("display: none");
    expect(rule(".chat-page.compact-viewport .composer-progress")).toContain("display: none");
    expect(mediaBlock("(max-height: 30rem)")).toContain(".composer-progress");
    expect(mediaBlock("(max-height: 30rem)")).toContain("display: none");
    expect(rule(".chat-page.compact-viewport .composer-input")).toContain("max-height: 4.75rem");
  });

  it("lays the control row out the same way in both expanded states", () => {
    // `justify-content: flex-end` only ever existed to place the row once the chip was gone; with
    // the chip present it takes the leftover width, and without it (feature off, bot row) the
    // spacer already pushes the buttons right. Nothing may re-align the row by viewport again.
    expect(css).not.toContain(".chat-page.compact-viewport .composer-actions");
    expect(mediaBlock("(max-height: 30rem)")).not.toContain(".composer-actions");
  });

  it("keeps a long model name inside the chip instead of pushing the buttons off screen", () => {
    // Android's contract, which the Web row must mirror: mic / ＋ / send are measured first at
    // intrinsic size, the chip is last and ellipsizes. Losing this while un-hiding the chip would
    // trade two styles for an unreachable send button.
    const chip = rule(".composer .model-chip");
    expect(chip).toContain("flex: 1");
    expect(chip).toContain("min-width: 0");
    const base = rule(".model-chip");
    expect(base).toContain("overflow: hidden");
    expect(base).toContain("text-overflow: ellipsis");
    expect(base).toContain("white-space: nowrap");
  });
});
