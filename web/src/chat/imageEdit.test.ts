import { describe, expect, it } from "vitest";
import {
  aspectRatio,
  dragCrop,
  editedName,
  fitAspect,
  hitHandle,
  isFullCrop,
  mosaicBlockPx,
  mosaicBrushPx,
  rotateBoxCw,
  rotatePointCw,
  strokeWidthPx,
  workingSize,
} from "./imageEdit";

describe("image editor rules (DESIGN §5.4b)", () => {
  it("scales pen, brush and mosaic block with the long edge, with floors", () => {
    expect(strokeWidthPx("thin", 2560)).toBeCloseTo(10.24);
    expect(strokeWidthPx("medium", 1000)).toBe(8);
    expect(strokeWidthPx("thick", 1000)).toBe(14);
    expect(strokeWidthPx("thin", 100)).toBe(2);
    expect(mosaicBrushPx("small", 1000)).toBe(30);
    expect(mosaicBrushPx("large", 1000)).toBe(100);
    expect(mosaicBrushPx("small", 100)).toBe(8);
    expect(mosaicBlockPx(2560)).toBe(40);
    expect(mosaicBlockPx(300)).toBe(8);
  });

  it("works at a long edge of at most 2560 and never upscales", () => {
    expect(workingSize(4000, 3000)).toMatchObject({ width: 2560, height: 1920 });
    expect(workingSize(800, 600)).toMatchObject({ width: 800, height: 600, scale: 1 });
  });

  it("names the output name-edited.jpg, idempotent and ASCII", () => {
    expect(editedName("photo.png")).toBe("photo-edited.jpg");
    expect(editedName("photo-edited.jpg")).toBe("photo-edited.jpg");
    expect(editedName("截图 1.HEIC")).toBe("1-edited.jpg");
    expect(editedName("屏幕.png")).toBe("image-edited.jpg");
  });

  it("rotates points and boxes 90° clockwise", () => {
    // 100×50 image: the top-left corner goes to the top-right of the 50×100 result.
    expect(rotatePointCw({ x: 0, y: 0 }, 50)).toEqual({ x: 50, y: 0 });
    expect(rotatePointCw({ x: 100, y: 50 }, 50)).toEqual({ x: 0, y: 100 });
    expect(rotateBoxCw({ left: 10, top: 5, width: 30, height: 20 }, 50)).toEqual({ left: 25, top: 10, width: 20, height: 30 });
  });

  it("reads aspect presets along the current long axis", () => {
    expect(aspectRatio("free", 400, 300)).toBeNull();
    expect(aspectRatio("16:9", 400, 300)).toBeCloseTo(16 / 9);
    expect(aspectRatio("16:9", 300, 400)).toBeCloseTo(9 / 16);
    expect(aspectRatio("original", 300, 400)).toBeCloseTo(0.75);
    const fitted = fitAspect({ left: 0, top: 0, width: 400, height: 300 }, 1, 400, 300);
    expect(fitted).toEqual({ left: 50, top: 0, width: 300, height: 300 });
  });

  it("hit-tests corners before edges, then the inside", () => {
    const box = { left: 100, top: 100, width: 200, height: 200 };
    expect(hitHandle({ x: 110, y: 105 }, box)).toBe("nw");
    expect(hitHandle({ x: 200, y: 98 }, box)).toBe("n");
    expect(hitHandle({ x: 200, y: 200 }, box)).toBe("move");
    expect(hitHandle({ x: 20, y: 20 }, box)).toBeNull();
  });

  it("drags inside the image, never below the minimum side, keeping a ratio on corners", () => {
    const start = { left: 100, top: 100, width: 200, height: 200 };
    expect(dragCrop(start, "move", -500, 0, 400, 400, 16, null)).toMatchObject({ left: 0, top: 100 });
    expect(dragCrop(start, "e", 500, 0, 400, 400, 16, null)).toMatchObject({ width: 300 });
    expect(dragCrop(start, "w", 500, 0, 400, 400, 50, null)).toMatchObject({ left: 250, width: 50 });
    const kept = dragCrop(start, "se", 100, 20, 400, 400, 16, 1);
    expect(kept.width).toBeCloseTo(kept.height);
    expect(kept.left).toBe(100);
    expect(isFullCrop({ left: 0, top: 0, width: 400, height: 300 }, 400, 300)).toBe(true);
    expect(isFullCrop(start, 400, 400)).toBe(false);
  });
});
