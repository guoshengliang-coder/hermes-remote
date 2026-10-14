import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, it } from "vitest";

it("uses Android's themed FAB corners in every layout and theme (HG-203)", () => {
  // M3 FloatingActionButtonDefaults.shape resolves CornerLarge; the app overrides it in Shape.kt.
  const shapes = readFileSync(resolve(process.cwd(), "../android/app/src/main/java/com/hermes/client/ui/theme/Shape.kt"), "utf8");
  const radiusDp = Number(shapes.match(/large\s*=\s*RoundedCornerShape\((\d+)\.dp\)/)?.[1]);
  expect(radiusDp).toBeGreaterThan(0);
  const style = document.createElement("style");
  style.textContent = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8");
  document.head.append(style);
  try {
    const fabRules: CSSStyleRule[] = [];
    function collect(rules: CSSRuleList) {
      for (const rule of rules) {
        if (rule instanceof CSSStyleRule && /\.fab\b/.test(rule.selectorText)) fabRules.push(rule);
        else if (rule instanceof CSSMediaRule) collect(rule.cssRules);
      }
    }
    collect(style.sheet!.cssRules);
    const base = fabRules.find(rule => rule.selectorText === ".fab")!.style;
    expect(base.width).toBe("3.5rem");
    expect(base.height).toBe("3.5rem");
    expect(base.borderRadius).toBe(`${radiusDp / 16}rem`);
    // Wide/touch/workspace positioning must not turn it back into a circle.
    for (const rule of fabRules) {
      if (rule.style.borderRadius) expect(rule.style.borderRadius).toBe(`${radiusDp / 16}rem`);
    }
  } finally {
    style.remove();
  }
});
