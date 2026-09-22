import { mosaicBlockPx, rotatePointCw, type CropBox, type EditOp, type Point } from "../chat/imageEdit";

// Canvas side of the pending-image editor (browser only). Everything happens at the working
// resolution, so what is on screen is what gets baked.

export function makeCanvas(width: number, height: number): HTMLCanvasElement {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

function ctx2d(canvas: HTMLCanvasElement): CanvasRenderingContext2D {
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("canvas 2d context unavailable");
  return ctx;
}

export function strokePath(ctx: CanvasRenderingContext2D, points: readonly Point[], width: number) {
  ctx.lineWidth = width;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  const [first, ...rest] = points;
  if (!first) return;
  ctx.moveTo(first.x, first.y);
  // A tap is a dot: a zero-length round-capped segment.
  if (!rest.length) ctx.lineTo(first.x + 0.01, first.y);
  for (const p of rest) ctx.lineTo(p.x, p.y);
  ctx.stroke();
}

/** A block-averaged copy of `source` (downscale, then upscale without smoothing). */
export function pixelated(source: HTMLCanvasElement): HTMLCanvasElement {
  const block = mosaicBlockPx(Math.max(source.width, source.height));
  const small = makeCanvas(Math.max(1, Math.ceil(source.width / block)), Math.max(1, Math.ceil(source.height / block)));
  const sctx = ctx2d(small);
  sctx.imageSmoothingEnabled = true;
  sctx.imageSmoothingQuality = "medium";
  sctx.drawImage(source, 0, 0, small.width, small.height);
  const out = makeCanvas(source.width, source.height);
  const octx = ctx2d(out);
  octx.imageSmoothingEnabled = false;
  octx.drawImage(small, 0, 0, small.width * block, small.height * block);
  return out;
}

/** Paint the pixelated copy into `target` only where the stroke goes. */
export function paintMosaic(target: HTMLCanvasElement, mosaic: HTMLCanvasElement, points: readonly Point[], width: number, scratch?: HTMLCanvasElement) {
  const mask = scratch && scratch.width === target.width && scratch.height === target.height ? scratch : makeCanvas(target.width, target.height);
  const mctx = ctx2d(mask);
  mctx.globalCompositeOperation = "source-over";
  mctx.clearRect(0, 0, mask.width, mask.height);
  mctx.strokeStyle = "#000";
  strokePath(mctx, points, width);
  mctx.globalCompositeOperation = "source-in";
  mctx.drawImage(mosaic, 0, 0);
  mctx.globalCompositeOperation = "source-over";
  ctx2d(target).drawImage(mask, 0, 0);
}

/** Apply one op onto `target` (which already holds everything beneath it: z order = list order). */
export function applyOp(target: HTMLCanvasElement, op: EditOp) {
  if (op.kind === "ink") {
    const ctx = ctx2d(target);
    ctx.strokeStyle = op.color;
    strokePath(ctx, op.points, op.width);
  } else {
    paintMosaic(target, pixelated(target), op.points, op.width);
  }
}

/** Base plus ops, into `into` (resized to the base) — the composite of committed work. */
export function composite(base: HTMLCanvasElement, ops: readonly EditOp[], into?: HTMLCanvasElement): HTMLCanvasElement {
  const out = into ?? makeCanvas(base.width, base.height);
  if (out.width !== base.width) out.width = base.width;
  if (out.height !== base.height) out.height = base.height;
  const ctx = ctx2d(out);
  ctx.clearRect(0, 0, out.width, out.height);
  ctx.drawImage(base, 0, 0);
  for (const op of ops) applyOp(out, op);
  return out;
}

/** The base turned 90° clockwise. */
export function rotateCanvasCw(base: HTMLCanvasElement): HTMLCanvasElement {
  const out = makeCanvas(base.height, base.width);
  const ctx = ctx2d(out);
  ctx.translate(base.height, 0);
  ctx.rotate(Math.PI / 2);
  ctx.drawImage(base, 0, 0);
  return out;
}

export function rotateOpCw(op: EditOp, height: number): EditOp {
  return { ...op, points: op.points.map((p) => rotatePointCw(p, height)) };
}

/** The crop of the composite as a JPEG. */
export async function bakeJpeg(image: HTMLCanvasElement, crop: CropBox, quality: number): Promise<Blob> {
  const left = Math.round(crop.left);
  const top = Math.round(crop.top);
  const width = Math.max(1, Math.round(crop.width));
  const height = Math.max(1, Math.round(crop.height));
  const out = makeCanvas(width, height);
  const ctx = ctx2d(out);
  // JPEG has no alpha: transparent PNG areas go white, not black.
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, width, height);
  ctx.drawImage(image, left, top, width, height, 0, 0, width, height);
  const blob = await new Promise<Blob | null>((resolve) => out.toBlob(resolve, "image/jpeg", quality));
  if (!blob) throw new Error("canvas.toBlob returned null");
  return blob;
}
