import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-185. On an iPhone the chat composer sat a quarter of a row higher than the Android client the
 * report compared it against. Measured at 390x844 with a 34px home-indicator inset, the block below
 * the input pill was 64.5px: 6px above the disclaimer, the 16.5px disclaimer line, 8px of
 * `.composer-wrap` bottom padding, then the 34px system inset. Android's own spacing below the pill
 * is ~56dp, and its app-level share of that is the column's 6dp plus the 2dp under the disclaimer —
 * the same ~8px the web app already used. The one thing the web app did *not* share was stacking
 * that 8px on top of the inset, so the padding goes and `.chat-bottom` owns the inset alone.
 *
 * The sheet is read as text on purpose, like `pageFrame.test.ts`: this is a layout contract, not a
 * rendered-DOM assertion, and happy-dom drops the units it would need.
 */
const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

function block(selector: string): string {
  const start = css.indexOf(`\n${selector} {`);
  if (start < 0) return "";
  const open = css.indexOf("{", start);
  const end = css.indexOf("}", open);
  return css.slice(open + 1, end).replace(/\s+/g, " ").trim();
}

/** The bottom value of a `padding` shorthand. One value repeats, two pairs, three and four put it last. */
function paddingBottom(rule: string): string | null {
  const padding = rule.match(/padding: ([^;]+);/);
  if (!padding || padding[1] === undefined) return null;
  const parts = padding[1].trim().split(" ");
  return (parts.length === 2 ? parts[0] : parts[parts.length - 1]) ?? null;
}

describe("the composer block's bottom margin is the system inset, not padding stacked on it (HG-185)", () => {
  it("adds no bottom padding under the disclaimer", () => {
    expect(paddingBottom(block(".composer-wrap"))).toBe("0");
  });

  it("leaves the safe-area inset to the chat footer", () => {
    expect(block(".chat-bottom")).toContain("padding-bottom: var(--safe-bottom)");
  });
});
