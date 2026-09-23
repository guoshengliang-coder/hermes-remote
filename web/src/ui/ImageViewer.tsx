import { useEffect, useRef, useState } from "preact/hooks";
import { useBackClose } from "../app/useBackClose";
import { toAppError } from "../app/failures";
import { useApp } from "../app/store";
import type { AppError } from "../errors";
import { ErrorNotice } from "./ErrorNotice";
import { CloseIcon, DownloadIcon, EditIcon, TrashIcon } from "./icons";
import { cachedBlobUrl } from "./Media";
import {
  clampView,
  distance,
  doubleTap,
  fitSize,
  IDENTITY,
  midpoint,
  swipeTarget,
  zoomAt,
  type Point,
  type Size,
  type View,
} from "./viewer-math";

// Full-screen image viewer (DESIGN §5.4, Android ImageViewer.kt): black backdrop, pinch 1–5×,
// double-tap to zoom/reset, clamped pan, swipe between the images of ONE message, a "2 / 5" pill.
// Two fingers always zoom; one finger at 1× belongs to paging; one finger zoomed in pans.

export type ViewerImage = { kind: "mac"; path: string; name: string } | { kind: "local"; url: string; name: string };

const TAP_MS = 250;
const DOUBLE_TAP_MS = 300;
const TAP_SLOP = 10;

type Gesture =
  | { mode: "none" }
  | { mode: "pinch"; startDist: number; startMid: Point; startView: View }
  | { mode: "pan"; start: Point; startView: View }
  | { mode: "swipe"; start: Point; at: number; moved: boolean };

/** A pending (not yet sent) attachment: the viewer offers 编辑 / 移除 instead of saving. */
export interface PendingImageActions {
  onEdit: () => void;
  onRemove: () => void;
}

export function ImageViewer({ images, index: initial, onClose, pending }: { images: ViewerImage[]; index: number; onClose: () => void; pending?: PendingImageActions }) {
  const { client, device, language, t } = useApp();
  const [index, setIndex] = useState(Math.min(Math.max(0, initial), images.length - 1));
  const [view, setView] = useState<View>(IDENTITY);
  const [dragX, setDragX] = useState(0);
  const [url, setUrl] = useState<string | null>(null);
  const [natural, setNatural] = useState<Size | null>(null);
  const [error, setError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [box, setBox] = useState<Size>({ w: window.innerWidth, h: window.innerHeight });
  const stage = useRef<HTMLDivElement>(null);
  const pointers = useRef(new Map<number, Point>());
  const gesture = useRef<Gesture>({ mode: "none" });
  const lastTap = useRef<{ at: number; p: Point } | null>(null);
  const viewRef = useRef(view);
  // Every change goes through here so a handler later in the same event sees the new view, not
  // the one from the last render (a double tap followed by the end-of-gesture snap used to undo it).
  const apply = (next: View) => {
    viewRef.current = next;
    setView(next);
  };

  useBackClose(onClose);

  const image = images[index];
  const content = natural ? fitSize(natural, box) : box;

  useEffect(() => {
    apply(IDENTITY);
    setDragX(0);
    setNatural(null);
    setError(null);
    if (!image) return;
    if (image.kind === "local") {
      setUrl(image.url);
      return;
    }
    setUrl(null);
    if (!device) return;
    let live = true;
    // No thumb width: this is the viewer, and a preview here is the whole point of the feature
    // going wrong — the bubble is what gets the downscaled copy (HG-115).
    cachedBlobUrl(client, device.deviceId, image.path).then(
      (u) => live && setUrl(u),
      (e: unknown) => live && setError(toAppError(e, "download")),
    );
    return () => {
      live = false;
    };
  }, [index, attempt, device?.deviceId]);

  useEffect(() => {
    const onResize = () => {
      const rect = stage.current?.getBoundingClientRect();
      setBox(rect ? { w: rect.width, h: rect.height } : { w: window.innerWidth, h: window.innerHeight });
      apply(IDENTITY);
    };
    onResize();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowRight") setIndex((i) => Math.min(images.length - 1, i + 1));
      else if (e.key === "ArrowLeft") setIndex((i) => Math.max(0, i - 1));
    };
    // Safari's own page pinch would fight ours.
    const stopGesture = (e: Event) => e.preventDefault();
    window.addEventListener("resize", onResize);
    window.addEventListener("keydown", onKey);
    document.addEventListener("gesturestart", stopGesture);
    return () => {
      window.removeEventListener("resize", onResize);
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("gesturestart", stopGesture);
    };
  }, []);

  function local(e: PointerEvent): Point {
    const rect = stage.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left - rect.width / 2, y: e.clientY - rect.top - rect.height / 2 };
  }

  function begin() {
    const pts = [...pointers.current.values()];
    if (pts.length >= 2) {
      gesture.current = { mode: "pinch", startDist: Math.max(1, distance(pts[0]!, pts[1]!)), startMid: midpoint(pts[0]!, pts[1]!), startView: viewRef.current };
      setDragX(0);
    } else if (pts.length === 1) {
      gesture.current = viewRef.current.scale > 1.01
        ? { mode: "pan", start: pts[0]!, startView: viewRef.current }
        : { mode: "swipe", start: pts[0]!, at: Date.now(), moved: false };
    } else {
      gesture.current = { mode: "none" };
    }
  }

  function onPointerDown(e: PointerEvent) {
    if ((e.target as Element).closest(".viewer-chrome")) return;
    stage.current?.setPointerCapture?.(e.pointerId);
    pointers.current.set(e.pointerId, local(e));
    begin();
  }

  function onPointerMove(e: PointerEvent) {
    if (!pointers.current.has(e.pointerId)) return;
    const p = local(e);
    pointers.current.set(e.pointerId, p);
    const g = gesture.current;
    if (g.mode === "pinch") {
      const pts = [...pointers.current.values()];
      if (pts.length < 2) return;
      const mid = midpoint(pts[0]!, pts[1]!);
      const zoomed = zoomAt(g.startView, (g.startView.scale * distance(pts[0]!, pts[1]!)) / g.startDist, g.startMid, content, box);
      apply(clampView({ ...zoomed, x: zoomed.x + mid.x - g.startMid.x, y: zoomed.y + mid.y - g.startMid.y }, content, box));
    } else if (g.mode === "pan") {
      apply(clampView({ scale: g.startView.scale, x: g.startView.x + p.x - g.start.x, y: g.startView.y + p.y - g.start.y }, content, box));
    } else if (g.mode === "swipe") {
      const dx = p.x - g.start.x;
      const dy = p.y - g.start.y;
      if (Math.hypot(dx, dy) > TAP_SLOP) g.moved = true;
      if (images.length > 1 && Math.abs(dx) > Math.abs(dy)) setDragX(dx);
    }
  }

  function onPointerUp(e: PointerEvent) {
    const p = pointers.current.get(e.pointerId);
    if (!p) return;
    pointers.current.delete(e.pointerId);
    const g = gesture.current;
    if (g.mode === "swipe") {
      const dx = p.x - g.start.x;
      const dy = p.y - g.start.y;
      setDragX(0);
      if (!g.moved && Date.now() - g.at < TAP_MS) {
        const prev = lastTap.current;
        if (prev && Date.now() - prev.at < DOUBLE_TAP_MS && distance(prev.p, p) < TAP_SLOP * 3) {
          lastTap.current = null;
          apply(doubleTap(viewRef.current, p, content, box));
        } else {
          lastTap.current = { at: Date.now(), p };
        }
      } else if (e.type === "pointerup") {
        const next = swipeTarget(dx, dy, index, images.length);
        if (next !== index) setIndex(next);
      }
    } else if (g.mode === "pan" && distance(p, g.start) < TAP_SLOP) {
      const prev = lastTap.current;
      if (prev && Date.now() - prev.at < DOUBLE_TAP_MS) {
        lastTap.current = null;
        apply(IDENTITY);
      } else {
        lastTap.current = { at: Date.now(), p };
      }
    }
    // A pinch that ends at (about) 1× snaps back to the centred picture.
    if (g.mode === "pinch" && viewRef.current.scale < 1.01) apply(IDENTITY);
    begin();
  }

  function download() {
    if (!url || !image) return;
    const a = document.createElement("a");
    a.href = url;
    a.download = image.name || "image";
    a.rel = "noopener";
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  const transform = `translate3d(${view.x + dragX}px, ${view.y}px, 0) scale(${view.scale})`;
  const sized = natural ? { width: `${content.w}px`, height: `${content.h}px` } : {};

  return (
    <div class="viewer" role="dialog" aria-modal="true" aria-label={t("查看图片", "Image viewer")}>
      <div
        class="viewer-stage"
        ref={stage}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        {error ? (
          <div class="viewer-error">
            <ErrorNotice error={error} language={language} onRetry={() => setAttempt(attempt + 1)} />
          </div>
        ) : url ? (
          <img
            class="viewer-image"
            src={url}
            alt={image?.name ?? ""}
            draggable={false}
            style={{ ...sized, transform }}
            onLoad={(e) => {
              const img = e.currentTarget as HTMLImageElement;
              setNatural({ w: img.naturalWidth, h: img.naturalHeight });
            }}
          />
        ) : (
          <span class="spinner" aria-label={t("正在加载", "Loading")} />
        )}
      </div>
      <div class="viewer-chrome">
        <button type="button" class="icon-button viewer-button" aria-label={t("关闭", "Close")} onClick={onClose}>
          <CloseIcon />
        </button>
        {images.length > 1 ? <span class="viewer-count mono">{`${index + 1} / ${images.length}`}</span> : <span />}
        {pending ? (
          <span class="viewer-actions">
            <button type="button" class="icon-button viewer-button" aria-label={t("移除附件", "Remove attachment")} onClick={pending.onRemove}>
              <TrashIcon />
            </button>
            <button type="button" class="icon-button viewer-button" aria-label={t("编辑图片", "Edit image")} disabled={!url} onClick={pending.onEdit}>
              <EditIcon />
            </button>
          </span>
        ) : (
          <button type="button" class="icon-button viewer-button" aria-label={t("保存图片", "Save image")} disabled={!url} onClick={download}>
            <DownloadIcon />
          </button>
        )}
      </div>
    </div>
  );
}
