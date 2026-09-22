// Pending-image editor geometry and rules (DESIGN §5.4b / §2.8, Android data/media/imageedit):
// pure so the numbers that matter are testable. Coordinates are in WORKING pixels (the image scaled
// so its long edge is at most EDIT_MAX_EDGE); rotation is committed into the working image, so
// strokes and the crop box always live in one pixel space.

export const EDIT_MAX_EDGE = 2560;

/** §2.8 annotation palette: theme-independent on purpose; one dark and one light escape colour. */
export const INK_COLORS = ["#EF4444", "#F59E0B", "#22C55E", "#2563EB", "#FFFFFF", "#111111"] as const;
export type InkColor = (typeof INK_COLORS)[number];
export type StrokeWeight = "thin" | "medium" | "thick";
export type BrushSize = "small" | "medium" | "large";

/** Pen width as a fraction of the long edge (a fixed px count is a hairline on 2560px, a blot on 900px). */
export function strokeWidthPx(weight: StrokeWeight, longEdge: number): number {
  const fraction = weight === "thin" ? 0.004 : weight === "medium" ? 0.008 : 0.014;
  return Math.max(2, longEdge * fraction);
}

/** Mosaic brush: far fatter than a pen — you are covering a phone number, not tracing it. */
export function mosaicBrushPx(brush: BrushSize, longEdge: number): number {
  const fraction = brush === "small" ? 0.03 : brush === "medium" ? 0.06 : 0.1;
  return Math.max(8, longEdge * fraction);
}

/** Block size ~1/64 of the long edge, floored at 8px; not user-adjustable. */
export function mosaicBlockPx(longEdge: number): number {
  return Math.max(8, Math.round(longEdge / 64));
}

/** Working size: long edge ≤ EDIT_MAX_EDGE, never upscaled. */
export function workingSize(width: number, height: number): { width: number; height: number; scale: number } {
  const scale = Math.min(1, EDIT_MAX_EDGE / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)), scale };
}

/** `photo.png` → `photo-edited.jpg`, idempotent, ASCII only (the name travels to the Mac). */
export function editedName(name: string): string {
  const stem = name.replace(/\.[A-Za-z0-9]{1,5}$/, "").replace(/-edited$/, "");
  const ascii = stem.replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
  return `${ascii || "image"}-edited.jpg`;
}

export interface Point {
  x: number;
  y: number;
}

export interface CropBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

export type EditOp =
  | { kind: "ink"; color: InkColor; width: number; points: Point[] }
  | { kind: "mosaic"; width: number; points: Point[] };

/** A point after rotating the whole image 90° clockwise (width/height of the image BEFORE). */
export function rotatePointCw(p: Point, height: number): Point {
  return { x: height - p.y, y: p.x };
}

export function rotateBoxCw(box: CropBox, height: number): CropBox {
  return { left: height - (box.top + box.height), top: box.left, width: box.height, height: box.width };
}

export type Aspect = "free" | "original" | "1:1" | "4:3" | "16:9";

/** Target ratio (w/h), read along the image's current long axis; null = free. */
export function aspectRatio(aspect: Aspect, imageW: number, imageH: number): number | null {
  if (aspect === "free") return null;
  if (aspect === "original") return imageW / imageH;
  const [a, b] = aspect === "1:1" ? [1, 1] : aspect === "4:3" ? [4, 3] : [16, 9];
  return imageW >= imageH ? a / b : b / a;
}

/** The largest box of `ratio` centred on `box`'s centre that fits the image. */
export function fitAspect(box: CropBox, ratio: number, imageW: number, imageH: number): CropBox {
  const cx = box.left + box.width / 2;
  const cy = box.top + box.height / 2;
  let w = Math.min(box.width, imageW);
  let h = w / ratio;
  if (h > Math.min(box.height, imageH)) {
    h = Math.min(box.height, imageH);
    w = h * ratio;
  }
  const left = Math.min(Math.max(0, cx - w / 2), imageW - w);
  const top = Math.min(Math.max(0, cy - h / 2), imageH - h);
  return { left, top, width: w, height: h };
}

export type Handle = "nw" | "n" | "ne" | "e" | "se" | "s" | "sw" | "w" | "move";

/**
 * Which handle a view-space point hits: corners first, then edges (48px hit circles), then the
 * inside (move); null outside. `box` is in view pixels.
 */
export function hitHandle(p: Point, box: CropBox, hit = 24): Handle | null {
  const { left: l, top: t, width: w, height: h } = box;
  const r = l + w;
  const b = t + h;
  const near = (x: number, y: number) => Math.hypot(p.x - x, p.y - y) <= hit;
  if (near(l, t)) return "nw";
  if (near(r, t)) return "ne";
  if (near(r, b)) return "se";
  if (near(l, b)) return "sw";
  if (near(l + w / 2, t)) return "n";
  if (near(r, t + h / 2)) return "e";
  if (near(l + w / 2, b)) return "s";
  if (near(l, t + h / 2)) return "w";
  if (p.x > l && p.x < r && p.y > t && p.y < b) return "move";
  return null;
}

/**
 * Drag a handle by (dx, dy) in image pixels, keeping the box inside the image and at least `min`
 * on each side; with a ratio, corner drags keep it (edges resize freely only without one).
 */
export function dragCrop(start: CropBox, handle: Handle, dx: number, dy: number, imageW: number, imageH: number, min: number, ratio: number | null): CropBox {
  if (handle === "move") {
    return {
      ...start,
      left: Math.min(Math.max(0, start.left + dx), imageW - start.width),
      top: Math.min(Math.max(0, start.top + dy), imageH - start.height),
    };
  }
  let l = start.left;
  let t = start.top;
  let r = start.left + start.width;
  let b = start.top + start.height;
  if (handle.includes("w")) l = Math.min(Math.max(0, l + dx), r - min);
  if (handle.includes("e")) r = Math.max(Math.min(imageW, r + dx), l + min);
  if (handle.includes("n")) t = Math.min(Math.max(0, t + dy), b - min);
  if (handle.includes("s")) b = Math.max(Math.min(imageH, b + dy), t + min);
  let box = { left: l, top: t, width: r - l, height: b - t };
  if (ratio && handle.length === 2) {
    // Keep the ratio from the corner opposite the one being dragged.
    let w = box.width;
    let h = w / ratio;
    if (h > box.height) {
      h = box.height;
      w = h * ratio;
    }
    box = {
      left: handle.includes("w") ? r - w : l,
      top: handle.includes("n") ? b - h : t,
      width: w,
      height: h,
    };
  }
  return box;
}

export function isFullCrop(box: CropBox, imageW: number, imageH: number): boolean {
  return box.left <= 0.5 && box.top <= 0.5 && Math.abs(box.width - imageW) <= 0.5 && Math.abs(box.height - imageH) <= 0.5;
}
