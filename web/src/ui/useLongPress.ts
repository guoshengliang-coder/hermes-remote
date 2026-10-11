import { useLayoutEffect, useRef } from "preact/hooks";

// Press-and-hold on a row opens its action sheet (DESIGN §5.5 行长按操作单). 500 ms, cancelled by
// movement (a scroll) or release. The browser's context menu opens the same sheet.

const HOLD_MS = 500;
const SLOP_PX = 10;

let releaseClickGuard: (() => void) | null = null;

/**
 * iOS can hit-test its compatibility click against the sheet that appeared under the finger.
 * Capture it before ANY target handler, including the scrim. A fresh primary press or keyboard
 * action ends the guard; a timeout would expire while the opening finger is still held down.
 * The guard belongs to the gesture, not the row: a list refresh may unmount the source row.
 */
function guardOpeningGesture() {
  releaseClickGuard?.();
  const release = () => {
    document.removeEventListener("click", swallow, true);
    document.removeEventListener("pointerdown", nextPress, true);
    document.removeEventListener("keydown", release, true);
    window.removeEventListener("pagehide", release);
    releaseClickGuard = null;
  };
  const nextPress = (e: PointerEvent) => { if (e.isPrimary) release(); };
  const swallow = (e: MouseEvent) => {
    // Keyboard/assistive activation carries no pointer and a zero click count. PointerEvent
    // clicks can have detail=0 too, so inspect pointerType before allowing them through.
    if (e.detail === 0 && !("pointerType" in e && e.pointerType)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
  };
  document.addEventListener("click", swallow, true);
  document.addEventListener("pointerdown", nextPress, true);
  document.addEventListener("keydown", release, true);
  window.addEventListener("pagehide", release);
  releaseClickGuard = release;
}

export function useLongPress(onLongPress: (() => void) | undefined) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const start = useRef<{ x: number; y: number; pointerId: number } | null>(null);
  const fired = useRef(false);
  const latest = useRef(onLongPress);
  latest.current = onLongPress;
  const cancel = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
    start.current = null;
  };
  useLayoutEffect(() => cancel, [Boolean(onLongPress)]);
  const fire = () => {
    if (!latest.current) return;
    fired.current = true;
    guardOpeningGesture();
    latest.current();
  };
  const end = (e: PointerEvent) => {
    if (e.pointerId === start.current?.pointerId) cancel();
  };
  if (!onLongPress) return {};
  return {
    onPointerDown: (e: PointerEvent) => {
      if (!e.isPrimary) return;
      cancel();
      fired.current = false;
      if (e.button !== 0) return;
      start.current = { x: e.clientX, y: e.clientY, pointerId: e.pointerId };
      timer.current = setTimeout(() => {
        timer.current = null;
        fire();
      }, HOLD_MS);
    },
    onPointerMove: (e: PointerEvent) => {
      if (start.current?.pointerId === e.pointerId && Math.hypot(e.clientX - start.current.x, e.clientY - start.current.y) > SLOP_PX) cancel();
    },
    onPointerUp: end,
    onPointerCancel: end,
    onPointerLeave: end,
    onKeyDown: () => { fired.current = false; },
    onContextMenu: (e: Event) => {
      e.preventDefault();
      cancel();
      if (!fired.current) fire();
    },
  };
}
