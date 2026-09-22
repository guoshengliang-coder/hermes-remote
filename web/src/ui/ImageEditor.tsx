import { useEffect, useRef, useState } from "preact/hooks";
import { useBackClose } from "../app/useBackClose";
import type { PendingAttachment } from "../chat/attachments";
import { JPEG_QUALITY, MAX_ATTACHMENT_BYTES } from "../chat/attachments";
import {
  aspectRatio,
  dragCrop,
  editedName,
  fitAspect,
  hitHandle,
  INK_COLORS,
  isFullCrop,
  mosaicBrushPx,
  rotateBoxCw,
  strokeWidthPx,
  workingSize,
  type Aspect,
  type BrushSize,
  type CropBox,
  type EditOp,
  type Handle,
  type InkColor,
  type Point,
  type StrokeWeight,
} from "../chat/imageEdit";
import { useApp } from "../app/store";
import { appError, type AppError } from "../errors";
import { ErrorNotice } from "./ErrorNotice";
import { CropIcon, MosaicIcon, PenIcon, RedoIcon, RotateIcon, UndoIcon } from "./icons";
import { bakeJpeg, composite, makeCanvas, paintMosaic, pixelated, rotateCanvasCw, rotateOpCw, strokePath } from "./imageEditCanvas";
import { Sheet } from "./Sheet";
import { clampView, distance, IDENTITY, midpoint, zoomAt, type View } from "./viewer-math";

// Pending-image editor (DESIGN §5.4b, Android ImageEditor): 涂鸦 / 打码 / 裁切 over a
// non-destructive op list, undo/redo per gesture, two fingers always zoom and never draw, and
// 完成 with no edits hands back the original bytes untouched.

type Mode = "ink" | "mosaic" | "crop";

interface EditState {
  base: HTMLCanvasElement;
  ops: EditOp[];
  crop: CropBox;
}

type Gesture =
  | { mode: "none" }
  | { mode: "pinch"; startDist: number; startMid: Point; startView: View }
  | { mode: "draw"; points: Point[] }
  | { mode: "crop"; handle: Handle; start: Point; startBox: CropBox; min: number }
  | { mode: "idle" };

/** Minimum crop side in view pixels (keeps neighbouring 48px hit circles apart). */
const MIN_CROP_VIEW = 96;

export function ImageEditor({ attachment, onDone, onClose }: { attachment: PendingAttachment; onDone: (next: PendingAttachment) => void; onClose: () => void }) {
  const { t, language } = useApp();
  const [initial, setInitial] = useState<EditState | null>(null);
  const [state, setState] = useState<EditState | null>(null);
  const [past, setPast] = useState<EditState[]>([]);
  const [future, setFuture] = useState<EditState[]>([]);
  const [mode, setMode] = useState<Mode>("ink");
  const [color, setColor] = useState<InkColor>(INK_COLORS[0]);
  const [weight, setWeight] = useState<StrokeWeight>("medium");
  const [brush, setBrush] = useState<BrushSize>("medium");
  const [aspect, setAspect] = useState<Aspect>("free");
  const [view, setView] = useState<View>(IDENTITY);
  const [dragging, setDragging] = useState(false);
  const [openError, setOpenError] = useState<AppError | null>(null);
  const [saveError, setSaveError] = useState<AppError | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [box, setBox] = useState({ w: window.innerWidth, h: window.innerHeight });
  const stage = useRef<HTMLDivElement>(null);
  const frame = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const committed = useRef<HTMLCanvasElement | null>(null);
  const mosaicSource = useRef<HTMLCanvasElement | null>(null);
  const scratch = useRef<HTMLCanvasElement | null>(null);
  const pointers = useRef(new Map<number, Point>());
  const gesture = useRef<Gesture>({ mode: "none" });
  const viewRef = useRef(view);
  const stateRef = useRef(state);
  stateRef.current = state;
  const raf = useRef(0);
  const applyView = (next: View) => {
    viewRef.current = next;
    setView(next);
  };

  // ---- open ----
  useEffect(() => {
    let live = true;
    setOpenError(null);
    (async () => {
      const bitmap = await createImageBitmap(attachment.file);
      try {
        const size = workingSize(bitmap.width, bitmap.height);
        const base = makeCanvas(size.width, size.height);
        const ctx = base.getContext("2d");
        if (!ctx) throw new Error("canvas 2d context unavailable");
        ctx.drawImage(bitmap, 0, 0, size.width, size.height);
        const first: EditState = { base, ops: [], crop: { left: 0, top: 0, width: size.width, height: size.height } };
        if (!live) return;
        setInitial(first);
        setState(first);
      } finally {
        bitmap.close();
      }
    })().catch((e: unknown) => live && setOpenError(appError("HR-MEDIA-004", `${attachment.name}: ${e instanceof Error ? e.message : String(e)}`)));
    return () => {
      live = false;
    };
  }, [attempt]);

  // ---- layout ----
  useEffect(() => {
    const measure = () => {
      const rect = stage.current?.getBoundingClientRect();
      if (rect) setBox({ w: rect.width, h: rect.height });
      applyView(IDENTITY);
    };
    measure();
    const stopGesture = (e: Event) => e.preventDefault();
    window.addEventListener("resize", measure);
    document.addEventListener("gesturestart", stopGesture);
    return () => {
      window.removeEventListener("resize", measure);
      document.removeEventListener("gesturestart", stopGesture);
      cancelAnimationFrame(raf.current);
    };
  }, [state !== null]);

  // Committed composite = base + ops; redrawn whenever they change.
  useEffect(() => {
    if (!state) return;
    committed.current = composite(state.base, state.ops, committed.current ?? undefined);
    paint();
  }, [state?.base, state?.ops]);

  function paint(live?: { points: Point[] }) {
    const el = canvas.current;
    const s = stateRef.current;
    if (!el || !s || !committed.current) return;
    if (el.width !== s.base.width) el.width = s.base.width;
    if (el.height !== s.base.height) el.height = s.base.height;
    const ctx = el.getContext("2d");
    if (!ctx) return;
    ctx.drawImage(committed.current, 0, 0);
    if (!live) return;
    const long = Math.max(s.base.width, s.base.height);
    if (mode === "ink") {
      ctx.strokeStyle = color;
      strokePath(ctx, live.points, strokeWidthPx(weight, long));
    } else if (mode === "mosaic" && mosaicSource.current) {
      scratch.current ??= makeCanvas(el.width, el.height);
      paintMosaic(el, mosaicSource.current, live.points, mosaicBrushPx(brush, long), scratch.current);
    }
  }

  // ---- edit history ----
  function commit(next: EditState) {
    if (!state) return;
    setPast([...past, state]);
    setFuture([]);
    setState(next);
  }
  function undo() {
    const prev = past[past.length - 1];
    if (!prev || !state) return;
    setPast(past.slice(0, -1));
    setFuture([state, ...future]);
    setState(prev);
  }
  function redo() {
    const next = future[0];
    if (!next || !state) return;
    setFuture(future.slice(1));
    setPast([...past, state]);
    setState(next);
  }

  const edited = !!state && !!initial && (state.base !== initial.base || state.ops.length > 0 || !isFullCrop(state.crop, state.base.width, state.base.height));

  // ---- geometry: which part of the image is shown, and at what size ----
  const W = state?.base.width ?? 1;
  const H = state?.base.height ?? 1;
  const region: CropBox = state && mode !== "crop" ? state.crop : { left: 0, top: 0, width: W, height: H };
  const pad = mode === "crop" ? 24 : 0;
  const fit = Math.min((box.w - pad * 2) / region.width, (box.h - pad * 2) / region.height);
  const content = { w: region.width * fit, h: region.height * fit };

  /** Image-pixel coordinates of a pointer (the frame's rect already includes the zoom). */
  function imagePoint(e: PointerEvent): Point {
    const rect = frame.current!.getBoundingClientRect();
    return { x: ((e.clientX - rect.left) / rect.width) * region.width + region.left, y: ((e.clientY - rect.top) / rect.height) * region.height + region.top };
  }
  function stagePoint(e: PointerEvent): Point {
    const rect = stage.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left - rect.width / 2, y: e.clientY - rect.top - rect.height / 2 };
  }

  function begin(e: PointerEvent | null) {
    const pts = [...pointers.current.values()];
    if (pts.length >= 2) {
      // A second finger turns whatever was happening into a zoom; a half-drawn stroke is dropped.
      if (gesture.current.mode === "draw" || gesture.current.mode === "crop") {
        paint();
        setDragging(false);
      }
      gesture.current = { mode: "pinch", startDist: Math.max(1, distance(pts[0]!, pts[1]!)), startMid: midpoint(pts[0]!, pts[1]!), startView: viewRef.current };
      return;
    }
    if (pts.length === 1 && e && gesture.current.mode === "none" && state) {
      if (mode === "crop") {
        const rect = frame.current!.getBoundingClientRect();
        const k = rect.width / region.width; // view px per image px
        const viewBox = { left: state.crop.left * k, top: state.crop.top * k, width: state.crop.width * k, height: state.crop.height * k };
        const handle = hitHandle({ x: e.clientX - rect.left, y: e.clientY - rect.top }, viewBox);
        if (handle) {
          gesture.current = { mode: "crop", handle, start: { x: e.clientX, y: e.clientY }, startBox: state.crop, min: Math.max(16, MIN_CROP_VIEW / k) };
          setDragging(true);
          return;
        }
        gesture.current = { mode: "idle" };
        return;
      }
      if (mode === "mosaic" && committed.current) mosaicSource.current = pixelated(committed.current);
      gesture.current = { mode: "draw", points: [imagePoint(e)] };
      paint({ points: (gesture.current as { points: Point[] }).points });
      return;
    }
    if (pts.length === 0) gesture.current = { mode: "none" };
  }

  function onPointerDown(e: PointerEvent) {
    if (!state || saving) return;
    stage.current?.setPointerCapture?.(e.pointerId);
    pointers.current.set(e.pointerId, stagePoint(e));
    begin(e);
  }

  function onPointerMove(e: PointerEvent) {
    if (!pointers.current.has(e.pointerId) || !state) return;
    pointers.current.set(e.pointerId, stagePoint(e));
    const g = gesture.current;
    if (g.mode === "pinch") {
      const pts = [...pointers.current.values()];
      if (pts.length < 2) return;
      const mid = midpoint(pts[0]!, pts[1]!);
      const zoomed = zoomAt(g.startView, (g.startView.scale * distance(pts[0]!, pts[1]!)) / g.startDist, g.startMid, content, box);
      applyView(clampView({ ...zoomed, x: zoomed.x + mid.x - g.startMid.x, y: zoomed.y + mid.y - g.startMid.y }, content, box));
    } else if (g.mode === "draw") {
      g.points.push(imagePoint(e));
      cancelAnimationFrame(raf.current);
      raf.current = requestAnimationFrame(() => paint({ points: g.points }));
    } else if (g.mode === "crop") {
      const k = frame.current!.getBoundingClientRect().width / region.width;
      const ratio = aspectRatio(aspect, W, H);
      const next = dragCrop(g.startBox, g.handle, (e.clientX - g.start.x) / k, (e.clientY - g.start.y) / k, W, H, g.min, ratio);
      setState({ ...state, crop: next });
    }
  }

  function onPointerUp(e: PointerEvent) {
    if (!pointers.current.has(e.pointerId)) return;
    pointers.current.delete(e.pointerId);
    const g = gesture.current;
    if (g.mode === "draw" && state && e.type === "pointerup") {
      cancelAnimationFrame(raf.current);
      const long = Math.max(W, H);
      const op: EditOp = mode === "ink" ? { kind: "ink", color, width: strokeWidthPx(weight, long), points: g.points } : { kind: "mosaic", width: mosaicBrushPx(brush, long), points: g.points };
      commit({ ...state, ops: [...state.ops, op] });
    } else if (g.mode === "draw") {
      paint();
    } else if (g.mode === "crop" && state) {
      setDragging(false);
      // The drag already moved `state.crop`; record the box it started from as one undo step.
      if (state.crop !== g.startBox) {
        setPast([...past, { ...state, crop: g.startBox }]);
        setFuture([]);
      }
    }
    if (g.mode === "pinch" && viewRef.current.scale < 1.01) applyView(IDENTITY);
    gesture.current = pointers.current.size ? { mode: "idle" } : { mode: "none" };
    if (pointers.current.size >= 2) begin(null);
  }

  // ---- crop tools ----
  function chooseAspect(next: Aspect) {
    setAspect(next);
    const ratio = aspectRatio(next, W, H);
    if (state && ratio) commit({ ...state, crop: fitAspect(state.crop, ratio, W, H) });
  }
  function rotate() {
    if (!state) return;
    const base = rotateCanvasCw(state.base);
    commit({ base, ops: state.ops.map((op) => rotateOpCw(op, state.base.height)), crop: rotateBoxCw(state.crop, state.base.height) });
    applyView(IDENTITY);
  }
  function resetCrop() {
    if (!state) return;
    setAspect("free");
    commit({ ...state, crop: { left: 0, top: 0, width: W, height: H } });
  }

  // ---- finish ----
  async function done() {
    if (!state || saving) return;
    if (!edited) {
      onDone(attachment); // nothing to write back: the original bytes stay exactly as they are
      return;
    }
    setSaving(true);
    setSaveError(null);
    try {
      const image = composite(state.base, state.ops);
      let blob = await bakeJpeg(image, state.crop, JPEG_QUALITY);
      for (const q of [0.8, 0.7, 0.6]) {
        if (blob.size <= MAX_ATTACHMENT_BYTES) break;
        blob = await bakeJpeg(image, state.crop, q);
      }
      if (blob.size > MAX_ATTACHMENT_BYTES) throw new Error(`edited image is ${blob.size} bytes`);
      onDone({ ...attachment, file: blob, name: editedName(attachment.name), mimeType: "image/jpeg", previewUrl: URL.createObjectURL(blob) });
    } catch (e) {
      setSaveError(appError("HR-MEDIA-005", e instanceof Error ? e.message : String(e)));
      setSaving(false);
    }
  }

  function cancel() {
    if (edited) setConfirmDiscard(true);
    else onClose();
  }
  // Back asks the same question as 取消 and stays open until it is answered.
  useBackClose(() => {
    if (saving) return false;
    if (!edited) return void onClose();
    setConfirmDiscard(true);
    return false;
  });

  const long = Math.max(W, H);
  const transform = `translate3d(${view.x}px, ${view.y}px, 0) scale(${view.scale})`;
  const k = fit; // image px → view px at 1×
  const crop = state?.crop;

  return (
    <div class="editor" role="dialog" aria-modal="true" aria-label={t("编辑图片", "Edit image")}>
      <div class="editor-top">
        <button type="button" class="editor-text" onClick={cancel}>
          {t("取消", "Cancel")}
        </button>
        <span class="editor-top-mid">
          <button type="button" class="icon-button viewer-button" aria-label={t("撤销", "Undo")} disabled={!past.length || saving} onClick={undo}>
            <UndoIcon />
          </button>
          <button type="button" class="icon-button viewer-button" aria-label={t("重做", "Redo")} disabled={!future.length || saving} onClick={redo}>
            <RedoIcon />
          </button>
        </span>
        <button type="button" class="editor-done" disabled={!state || saving} onClick={() => void done()}>
          {saving ? <span class="spinner tiny" aria-hidden="true" /> : null}
          {t("完成", "Done")}
        </button>
      </div>
      <div class="editor-stage" ref={stage} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
        {openError ? (
          <div class="viewer-error">
            <ErrorNotice error={openError} language={language} onRetry={() => setAttempt(attempt + 1)} onDismiss={onClose} />
          </div>
        ) : !state ? (
          <span class="spinner" aria-label={t("正在加载", "Loading")} />
        ) : (
          <div class="editor-frame" ref={frame} style={{ width: `${content.w}px`, height: `${content.h}px`, transform }}>
            <canvas
              ref={canvas}
              class="editor-canvas"
              style={{ width: `${W * k}px`, height: `${H * k}px`, left: `${-region.left * k}px`, top: `${-region.top * k}px` }}
            />
            {mode === "crop" && crop ? (
              <div class="crop-box" style={{ left: `${crop.left * k}px`, top: `${crop.top * k}px`, width: `${crop.width * k}px`, height: `${crop.height * k}px` }}>
                {dragging ? <span class="crop-grid" aria-hidden="true" /> : null}
                {(["nw", "ne", "se", "sw"] as const).map((h) => (
                  <span key={h} class={`crop-corner ${h}`} aria-hidden="true" />
                ))}
                {(["n", "e", "s", "w"] as const).map((h) => (
                  <span key={h} class={`crop-edge ${h}`} aria-hidden="true" />
                ))}
              </div>
            ) : null}
          </div>
        )}
      </div>
      {saveError ? (
        <div class="editor-error">
          <ErrorNotice error={saveError} language={language} onRetry={() => void done()} onDismiss={() => setSaveError(null)} variant="inline" />
        </div>
      ) : null}
      <div class="editor-bottom">
        <div class="editor-options">
          {mode === "ink" ? (
            <>
              {INK_COLORS.map((c) => (
                <button key={c} type="button" class={`ink-dot${c === color ? " on" : ""}`} style={{ background: c }} aria-label={c} aria-pressed={c === color} onClick={() => setColor(c)} />
              ))}
              <span class="editor-sep" />
              {(["thin", "medium", "thick"] as const).map((w) => (
                <button key={w} type="button" class={`weight-dot${w === weight ? " on" : ""}`} aria-pressed={w === weight} aria-label={{ thin: t("细", "Thin"), medium: t("中", "Medium"), thick: t("粗", "Thick") }[w]} onClick={() => setWeight(w)}>
                  <span style={{ width: `${{ thin: 4, medium: 8, thick: 13 }[w]}px`, height: `${{ thin: 4, medium: 8, thick: 13 }[w]}px` }} />
                </button>
              ))}
            </>
          ) : mode === "mosaic" ? (
            (["small", "medium", "large"] as const).map((b) => (
              <button key={b} type="button" class={`editor-chip${b === brush ? " on" : ""}`} aria-pressed={b === brush} onClick={() => setBrush(b)}>
                {{ small: t("小", "Small"), medium: t("中", "Medium"), large: t("大", "Large") }[b]}
                <span class="mono editor-chip-sub">{Math.round(mosaicBrushPx(b, long))}px</span>
              </button>
            ))
          ) : (
            <>
              {(["free", "original", "1:1", "4:3", "16:9"] as const).map((a) => (
                <button key={a} type="button" class={`editor-chip${a === aspect ? " on" : ""}`} aria-pressed={a === aspect} onClick={() => chooseAspect(a)}>
                  {a === "free" ? t("自由", "Free") : a === "original" ? t("原图", "Original") : a}
                </button>
              ))}
              <button type="button" class="icon-button viewer-button" aria-label={t("旋转 90°", "Rotate 90°")} onClick={rotate}>
                <RotateIcon />
              </button>
              <button type="button" class="editor-chip" onClick={resetCrop}>
                {t("重置", "Reset")}
              </button>
            </>
          )}
        </div>
        <div class="editor-tools">
          {(
            [
              ["ink", t("涂鸦", "Draw"), <PenIcon />],
              ["mosaic", t("打码", "Redact"), <MosaicIcon />],
              ["crop", t("裁切", "Crop"), <CropIcon />],
            ] as const
          ).map(([m, label, icon]) => (
            <button
              key={m}
              type="button"
              class={`editor-tool${mode === m ? " on" : ""}`}
              aria-pressed={mode === m}
              onClick={() => {
                setMode(m);
                applyView(IDENTITY);
              }}
            >
              {icon}
              <span>{label}</span>
            </button>
          ))}
        </div>
      </div>
      {confirmDiscard ? (
        <Sheet title={t("放弃这些修改？", "Discard these edits?")} closeLabel={t("继续编辑", "Keep editing")} onClose={() => setConfirmDiscard(false)}>
          <p class="sheet-body">{t("关闭后，这次的涂鸦、打码和裁切都会丢失。", "Your drawing, redaction and crop will be lost.")}</p>
          <div class="sheet-buttons">
            <button type="button" class="text-button subtle" onClick={() => setConfirmDiscard(false)}>
              {t("继续编辑", "Keep editing")}
            </button>
            <button type="button" class="primary-button inline danger" onClick={onClose}>
              {t("放弃", "Discard")}
            </button>
          </div>
        </Sheet>
      ) : null}
    </div>
  );
}
