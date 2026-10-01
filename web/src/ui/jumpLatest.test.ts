import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-179. The chat's "jump to latest" button used to float at a fixed `bottom: 6.5rem` from the
 * page. That distance only fits a one-row composer: a two-row composer (model chip, mic, send) or
 * an attachment strip makes the footer taller, and the button ended up on top of the input — the
 * report's screenshot has it over the message field. The button is now a child of `.chat-bottom`
 * and is anchored to that footer's top edge, so it clears the composer whatever its height.
 *
 * The sheet is read as text (like `pageFrame.test.ts`): happy-dom cannot measure layout, and
 * `bottom: calc(100% + 0.5rem)` resolving against the footer is the whole fix. That only means the
 * footer's top while `.jump-latest`'s containing block is `.chat-bottom` (`position: relative`)
 * and the button is rendered inside it — the DOM half lives in `ChatPage.tsx` and is checked below
 * by reading the source, because a layout this depends on cannot be observed in the test DOM.
 */
const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const chatPage = readFileSync(resolve(process.cwd(), "src/ui/ChatPage.tsx"), "utf8");

function block(selector: string): string {
  const start = css.indexOf(`\n${selector} {`);
  if (start < 0) return "";
  const open = css.indexOf("{", start);
  const end = css.indexOf("}", open);
  return css.slice(open + 1, end).replace(/\s+/g, " ").trim();
}

describe("the jump-to-latest button clears the composer (HG-179)", () => {
  it("anchors to the footer's top edge instead of a fixed distance from the page bottom", () => {
    const rule = block(".jump-latest");
    expect(rule).toContain("bottom: calc(100% + 0.5rem)");
    // The old offset only fitted a single-row composer; a fixed `rem` drifts under the input.
    expect(rule).not.toContain("6.5rem");
    // Horizontal placement keeps clearing the safe area on its own.
    expect(rule).toContain("right: calc(1rem + var(--safe-right))");
  });

  it("the footer is the containing block the button anchors to", () => {
    expect(block(".chat-bottom")).toContain("position: relative");
  });

  it("renders the button inside the footer, not as its sibling", () => {
    const footer = chatPage.indexOf('<footer class={`chat-bottom');
    const button = chatPage.indexOf('class="jump-latest"');
    const footerEnd = chatPage.indexOf("</footer>", footer);
    expect(footer).toBeGreaterThan(-1);
    expect(button).toBeGreaterThan(footer);
    expect(button).toBeLessThan(footerEnd);
  });
});
