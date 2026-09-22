import { useLayoutEffect, useRef } from "preact/hooks";
import { pushOverlay } from "./overlayHistory";

/**
 * While mounted (and `active`), system back calls `onClose` instead of leaving the page. Return
 * false from `onClose` to stay open. See app/overlayHistory.ts.
 */
export function useBackClose(onClose: () => boolean | void, active = true): void {
  const latest = useRef(onClose);
  latest.current = onClose;
  // Layout effect: the history entry exists by the time the overlay is on screen, so a back
  // pressed right away closes it instead of leaving the page.
  useLayoutEffect(() => {
    if (!active) return;
    return pushOverlay(() => latest.current());
  }, [active]);
}

/** The same, for places where a hook call is awkward (inside a conditional block of JSX). */
export function BackClose({ onClose, active = true }: { onClose: () => boolean | void; active?: boolean }) {
  useBackClose(onClose, active);
  return null;
}
