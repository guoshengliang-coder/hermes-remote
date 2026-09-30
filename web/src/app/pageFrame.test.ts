import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-173. On an iPhone the session list was pulled down as a whole page — top bar and status-bar
 * area included — because the *document* scrolled and iOS rubber-bands whatever scrolls at the
 * document level. The `body { overscroll-behavior-y: none }` that shipped with the web app never
 * stopped it: WebKit reads that property for the root scroller from the root element. The fix is
 * structural — the document cannot scroll at all (it is a frame), and each page scrolls inside its
 * own port, which is a child of the page and never wraps the top bar. This pins the declarations;
 * the event half (the reveal pill following the list port, not the window) is in
 * `WebAlignment.test.tsx`, and the pull itself still needs a real iPhone (docs/SMOKE_TEST.md).
 *
 * The patterns read the sheet as text on purpose: happy-dom drops `100dvh` values and
 * `-webkit-overflow-scrolling` from its CSSOM, and both are load-bearing here.
 */
const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

function block(selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const body = new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? "";
  return body.replace(/\s+/g, " ").trim();
}

describe("the document is a frame and every page scrolls in its own port (DESIGN §5.21, HG-173)", () => {
  it("the document itself cannot scroll, and the root element carries the overscroll rule", () => {
    for (const selector of ["html", "body"]) {
      const rule = block(selector);
      expect(rule, selector).toContain("height: 100%");
      expect(rule, selector).toContain("overflow: hidden");
      expect(rule, selector).toContain("overscroll-behavior: none");
    }
    // The old declaration only ever lived on `body`, and the phone proved it was not enough.
    expect(block("body")).not.toContain("overscroll-behavior-y");
    expect(block("body")).not.toContain("min-height");
  });

  it("a page is exactly one viewport tall instead of growing past it", () => {
    expect(block(".page")).toContain("height: 100dvh");
    expect(block(".page")).not.toContain("min-height: 100dvh");
  });

  it("every page root offers a bounded port that keeps its bounce to itself", () => {
    // `.page-scroll` is the list and Mac-picker port; `.messages` (chat), `.login-scroll` (sign-in)
    // and `.full-center` (boot / notice) are the same shape.
    for (const selector of [".page-scroll", ".messages", ".login-scroll", ".full-center"]) {
      const rule = block(selector);
      expect(rule, selector).toContain("overflow-y: auto");
      expect(rule, selector).toContain("-webkit-overflow-scrolling: touch");
      expect(rule, selector).toContain("overscroll-behavior: contain");
    }
    // A flex child refuses to shrink below its content without this, and then never scrolls.
    expect(block(".page-scroll")).toContain("min-height: 0");
    for (const selector of [".page-scroll", ".login-scroll"]) {
      expect(block(selector), selector).toContain("flex: 1");
    }
    expect(block(".login-page")).toContain("height: 100dvh");
    // The sign-in submit row is the frame's bottom row again, not a sticky item of a scrolled page.
    expect(block(".bottom-bar")).not.toContain("position: sticky");
  });
});
