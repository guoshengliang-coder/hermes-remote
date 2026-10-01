import type { ComponentChildren } from "preact";
import { useBackClose } from "../app/useBackClose";
import { useEffect } from "preact/hooks";
import { CloseIcon } from "./icons";

// Bottom sheet (DESIGN §5.8 bottom-up rule): scrim, grip, optional title with ✕, then content.
// Escape and the scrim close it.

export function Sheet({
  title,
  closeLabel,
  onClose,
  children,
  wide,
  headerAction,
  aboveDrawer,
}: {
  title?: string;
  closeLabel: string;
  onClose: () => void;
  children: ComponentChildren;
  wide?: boolean;
  headerAction?: ComponentChildren;
  /**
   * Lift the sheet above the account drawer. The drawer owns z 44/45 while the shared sheet is
   * 24/25 for in-page use, so a sheet opened from the card page would otherwise paint behind it.
   */
  aboveDrawer?: boolean;
}) {
  useBackClose(onClose);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <>
      <div class={`sheet-scrim${aboveDrawer ? " above-drawer" : ""}`} onClick={onClose} />
      <div class={`picker-sheet${wide ? " tall" : ""}${aboveDrawer ? " above-drawer" : ""}`} role="dialog" aria-modal="true" aria-label={title ?? closeLabel}>
        <div class="sheet-grip" aria-hidden="true" />
        {title ? (
          <div class="picker-head">
            <h2 class="picker-title">{title}</h2>
            {headerAction}
            <button type="button" class="icon-button" aria-label={closeLabel} onClick={onClose}>
              <CloseIcon />
            </button>
          </div>
        ) : null}
        <div class="picker-list">{children}</div>
      </div>
    </>
  );
}

/** One row of an action sheet: full text, optional hint, red when destructive. */
export function SheetAction({ label, hint, danger, disabled, onClick }: { label: string; hint?: string; danger?: boolean; disabled?: boolean; onClick: () => void }) {
  return (
    <button type="button" class={`sheet-action${danger ? " danger" : ""}`} disabled={disabled} onClick={onClick}>
      <span class="sheet-action-label">{label}</span>
      {hint ? <span class="sheet-action-hint">{hint}</span> : null}
    </button>
  );
}
