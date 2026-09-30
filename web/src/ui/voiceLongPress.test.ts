import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * HG-172. In the Android WebView and on iOS a held press has a browser default on top of the
 * app's own gesture: Android pops the selection/search sheet and iOS starts selecting text, and
 * either one takes the pointer away — the recording is blocked and then cancelled. jsdom cannot
 * run that gesture, so this pins the declaration blocks that opt the control and its overlay out
 * of it; the event-level half of the fix (`contextmenu` preventDefault) is pinned in
 * Composer.test.tsx. The patterns are literals on purpose: a `RegExp` built from a variable reads
 * as attacker-controlled to the semgrep gate, and these two selectors are fixed.
 */
describe("hold-to-talk never doubles as the browser's selection gesture (DESIGN §5.21, HG-172)", () => {
  const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8");

  it("keeps both the hold button and the overlay it covers out of selection and the iOS callout", () => {
    const blocks = {
      ".voice-hold": /\.voice-hold\s*\{[^}]*\}/.exec(css)?.[0] ?? "",
      ".voice-overlay": /\.voice-overlay\s*\{[^}]*\}/.exec(css)?.[0] ?? "",
    };
    for (const [selector, rule] of Object.entries(blocks)) {
      expect(rule, selector).toContain("user-select: none");
      expect(rule, selector).toContain("-webkit-user-select: none");
      expect(rule, selector).toContain("-webkit-touch-callout: none");
    }
  });
});
