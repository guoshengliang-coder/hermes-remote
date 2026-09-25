import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("foldable conversation list layout (DESIGN §5.21)", () => {
  it("uses the available width on a wide touch screen while retaining the desktop measure", () => {
    const style = document.createElement("style");
    style.textContent = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8");
    document.head.append(style);
    try {
      const rules = [...(style.sheet?.cssRules ?? [])];
      const desktop = rules.find((rule): rule is CSSStyleRule =>
        rule instanceof CSSStyleRule && rule.selectorText === ".topbar-row",
      );
      expect(desktop?.style.maxWidth).toBe("var(--content-max)");

      const touch = rules.find((rule): rule is CSSMediaRule =>
        rule instanceof CSSMediaRule && rule.conditionText === "(min-width: 45rem) and (pointer: coarse)",
      );
      expect(touch).toBeDefined();

      const declaration = (selector: string) => [...(touch?.cssRules ?? [])]
        .find((rule): rule is CSSStyleRule =>
          rule instanceof CSSStyleRule && rule.selectorText.split(",").map((part) => part.trim()).includes(selector),
        )?.style;

      for (const selector of [".list-page .topbar-row", ".list-page .content", ".list-page .segments"]) {
        expect(declaration(selector)?.maxWidth, selector).toBe("none");
      }
      expect(declaration(".list-page .menu")?.right).toBe("calc(0.5rem + var(--safe-right))");
      expect(declaration(".list-page .fab")?.right).toBe("calc(1rem + var(--safe-right))");
    } finally {
      style.remove();
    }
  });
});
