import { useRef } from "preact/hooks";

// Press-and-hold on a row opens its action sheet (DESIGN §5.5 行长按操作单). 500 ms, cancelled by
// movement (a scroll) or release; the click that follows a long press is swallowed. The browser's
// own context menu (right click, or long press on some browsers) opens the same sheet.

const HOLD_MS = 500;
const SLOP_PX = 10;

export function useLongPress(onLongPress: (() => void) | undefined) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const start = useRef<{ x: number; y: number } | null>(null);
  const fired = useRef(false);
  const cancel = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
    start.current = null;
  };
  if (!onLongPress) return {};
  return {
    onPointerDown: (e: PointerEvent) => {
      if (e.button !== 0) return;
      fired.current = false;
      start.current = { x: e.clientX, y: e.clientY };
      timer.current = setTimeout(() => {
        fired.current = true;
        timer.current = null;
        onLongPress();
      }, HOLD_MS);
    },
    onPointerMove: (e: PointerEvent) => {
      if (start.current && Math.hypot(e.clientX - start.current.x, e.clientY - start.current.y) > SLOP_PX) cancel();
    },
    onPointerUp: cancel,
    onPointerCancel: cancel,
    onPointerLeave: cancel,
    onContextMenu: (e: Event) => {
      e.preventDefault();
      cancel();
      fired.current = true;
      onLongPress();
    },
    /** Wrap the row's onClick: a click right after a long press does not also open the row. */
    guard: (open: () => void) => () => {
      if (fired.current) {
        fired.current = false;
        return;
      }
      open();
    },
  };
}
