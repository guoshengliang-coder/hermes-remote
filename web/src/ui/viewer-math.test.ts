import { describe, expect, it } from "vitest";
import { clampView, doubleTap, fitSize, IDENTITY, MAX_SCALE, swipeTarget, zoomAt } from "./viewer-math";

const box = { w: 400, h: 800 };

describe("image viewer geometry (DESIGN §5.4)", () => {
  it("fits the image inside the box without cropping", () => {
    expect(fitSize({ w: 2000, h: 1000 }, box)).toEqual({ w: 400, h: 200 });
    expect(fitSize({ w: 1000, h: 4000 }, box)).toEqual({ w: 200, h: 800 });
  });

  it("clamps pan so a zoomed image cannot be dragged off screen", () => {
    const content = { w: 400, h: 200 };
    // At 2× the picture is 800×400: x may move ±200; y has no slack (400 < 800).
    expect(clampView({ scale: 2, x: 999, y: 999 }, content, box)).toEqual({ scale: 2, x: 200, y: 0 });
    expect(clampView({ scale: 2, x: -999, y: -5 }, content, box)).toEqual({ scale: 2, x: -200, y: 0 });
    // At 1× nothing moves; scale is held to 1–5.
    expect(clampView({ scale: 0.3, x: 50, y: 50 }, content, box)).toEqual({ scale: 1, x: 0, y: 0 });
    expect(clampView({ scale: 9, x: 0, y: 0 }, content, box).scale).toBe(MAX_SCALE);
  });

  it("zooms around the focal point", () => {
    const content = { w: 400, h: 800 };
    const v = zoomAt(IDENTITY, 2, { x: 100, y: 0 }, content, box);
    // The point 100px right of centre stays under the finger: centre moves to −100.
    expect(v).toEqual({ scale: 2, x: -100, y: 0 });
  });

  it("double tap zooms in, and back to 1× when zoomed", () => {
    const content = { w: 400, h: 800 };
    const zoomed = doubleTap(IDENTITY, { x: 0, y: 0 }, content, box);
    expect(zoomed.scale).toBe(2.5);
    expect(doubleTap(zoomed, { x: 0, y: 0 }, content, box)).toEqual(IDENTITY);
  });

  it("pages only on a clear horizontal swipe, within the message's images", () => {
    expect(swipeTarget(-80, 5, 0, 3)).toBe(1);
    expect(swipeTarget(80, 5, 1, 3)).toBe(0);
    expect(swipeTarget(-30, 0, 0, 3)).toBe(0); // too short
    expect(swipeTarget(-80, 90, 0, 3)).toBe(0); // mostly vertical
    expect(swipeTarget(80, 0, 0, 3)).toBe(0); // no image before the first
    expect(swipeTarget(-80, 0, 2, 3)).toBe(2); // none after the last
  });
});
