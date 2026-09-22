// Full-screen image viewer geometry (DESIGN §5.4 看图器, Android ImageViewer.kt): zoom 1–5×,
// double-tap toggles, pan is clamped so the picture can never be dragged off screen, and a
// horizontal swipe at 1× pages between the images of the same message. Pure, so it is testable.

export interface Size {
  w: number;
  h: number;
}

export interface Point {
  x: number;
  y: number;
}

/** Scale plus translation of the image centre from the box centre, in CSS px. */
export interface View {
  scale: number;
  x: number;
  y: number;
}

export const MIN_SCALE = 1;
export const MAX_SCALE = 5;
export const DOUBLE_TAP_SCALE = 2.5;
export const IDENTITY: View = { scale: 1, x: 0, y: 0 };

/** The image's size at 1×: fitted inside the box without cropping or upscaling past the box. */
export function fitSize(natural: Size, box: Size): Size {
  if (natural.w <= 0 || natural.h <= 0 || box.w <= 0 || box.h <= 0) return { w: box.w, h: box.h };
  const ratio = Math.min(box.w / natural.w, box.h / natural.h);
  return { w: natural.w * ratio, h: natural.h * ratio };
}

/** Keep the zoomed image covering what it can: |x| ≤ (scale × width − box) / 2, never negative. */
export function clampView(view: View, content: Size, box: Size): View {
  const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, view.scale));
  const limitX = Math.max(0, (scale * content.w - box.w) / 2);
  const limitY = Math.max(0, (scale * content.h - box.h) / 2);
  // `+ 0` turns a clamped −0 into 0.
  return {
    scale,
    x: Math.min(limitX, Math.max(-limitX, view.x)) + 0,
    y: Math.min(limitY, Math.max(-limitY, view.y)) + 0,
  };
}

/**
 * Scale to `targetScale` keeping the point under `focal` (relative to the box centre) still.
 * The result is clamped.
 */
export function zoomAt(view: View, targetScale: number, focal: Point, content: Size, box: Size): View {
  const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, targetScale));
  const k = scale / view.scale;
  return clampView({ scale, x: focal.x - (focal.x - view.x) * k, y: focal.y - (focal.y - view.y) * k }, content, box);
}

/** Double tap: back to 1× when zoomed, else zoom in on the tapped point. */
export function doubleTap(view: View, focal: Point, content: Size, box: Size): View {
  return view.scale > MIN_SCALE + 0.01 ? IDENTITY : zoomAt(view, DOUBLE_TAP_SCALE, focal, content, box);
}

/** Where a finished single-finger drag at 1× lands: the neighbour when it was a clear swipe. */
export function swipeTarget(dx: number, dy: number, index: number, count: number, threshold = 56): number {
  if (Math.abs(dx) < threshold || Math.abs(dx) < Math.abs(dy) * 1.2) return index;
  const next = dx < 0 ? index + 1 : index - 1;
  return next < 0 || next >= count ? index : next;
}

export function distance(a: Point, b: Point): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

export function midpoint(a: Point, b: Point): Point {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}
