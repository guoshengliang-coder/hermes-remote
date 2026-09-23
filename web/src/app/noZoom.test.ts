import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { blockPageZoom } from "./noZoom";

describe("no page zoom (DESIGN §5.21)", () => {
  it("the viewport meta refuses scaling but keeps the safe area and keyboard keys", () => {
    const html = readFileSync(resolve(process.cwd(), "index.html"), "utf8");
    const content = /<meta name="viewport" content="([^"]+)"/.exec(html)?.[1] ?? "";
    const parts = content.split(",").map((p) => p.trim());
    expect(parts).toContain("width=device-width");
    expect(parts).toContain("initial-scale=1");
    expect(parts).toContain("maximum-scale=1");
    expect(parts).toContain("user-scalable=no");
    expect(parts).toContain("viewport-fit=cover");
    expect(parts).toContain("interactive-widget=resizes-content");
  });

  it("text still scales with the browser: every font-size is in rem, none in px", () => {
    const css = readFileSync(resolve(process.cwd(), "src/styles.css"), "utf8");
    const pxSizes = [...css.matchAll(/font-size:\s*([^;]+);/g)].map((m) => m[1]!.trim()).filter((v) => /\dpx/.test(v));
    expect(pxSizes).toEqual([]);
  });

  it("blocks Safari's pinch only in an installed app", () => {
    const add = vi.spyOn(document, "addEventListener");
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    blockPageZoom();
    expect(add.mock.calls.filter(([name]) => String(name).startsWith("gesture"))).toEqual([]);
    vi.stubGlobal("matchMedia", (q: string) => ({ matches: q === "(display-mode: standalone)" }));
    const release = blockPageZoom();
    expect(add.mock.calls.filter(([name]) => String(name).startsWith("gesture")).length).toBe(3);
    const event = new Event("gesturestart", { cancelable: true });
    document.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    release();
    const after = new Event("gesturestart", { cancelable: true });
    document.dispatchEvent(after);
    expect(after.defaultPrevented).toBe(false);
    vi.unstubAllGlobals();
    add.mockRestore();
  });
});
