/**
 * iOS keeps the layout viewport (and 100dvh) at full height when the keyboard opens, then pans
 * the smaller visual viewport to the focused field. Keep the chat frame inside that visible area.
 * Chrome with interactive-widget=resizes-content already shrinks its layout viewport and needs
 * no adjustment. The margin avoids reacting to browser chrome changes or fractional measurements.
 */
export function chatViewportGeometry(
  layoutHeight: number,
  visualHeight: number,
  offsetTop: number,
  scale: number,
): { height: number; top: number; compact: boolean } | null {
  if (![layoutHeight, visualHeight, offsetTop, scale].every(Number.isFinite)) return null;
  if (scale !== 1 || visualHeight <= 0 || layoutHeight - visualHeight < 120) return null;
  return { height: visualHeight, top: Math.max(0, offsetTop), compact: visualHeight <= 480 };
}

/** Focus, rather than a stale viewport size, determines whether there can be a soft keyboard.
 * Use the document's focus so a search field or sheet can keep the keyboard, too.
 */
function hasTextFocus(doc: Document): boolean {
  const active = doc.activeElement;
  if (active instanceof HTMLTextAreaElement) return !active.disabled && !active.readOnly;
  if (active instanceof HTMLInputElement) {
    return !active.disabled && !active.readOnly && ![
      "button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit",
    ].includes(active.type);
  }
  return active instanceof HTMLElement && active.isContentEditable;
}

export function followChatVisualViewport(page: HTMLElement, win: Window = window): () => void {
  const viewport = win.visualViewport;
  if (!viewport) return () => {};
  const doc = win.document;
  let frame = 0;

  function update() {
    frame = 0;
    const focused = hasTextFocus(doc);
    // WebKit restores the document before its keyboard viewport: retaining the previous offset
    // then shifts the entire chat down, leaving blank space above/below it (HG-229).
    // The document is a locked app frame; only its inner message/list ports are user-scrollable.
    if (!focused && viewport!.scale === 1 && (win.scrollX || win.scrollY)) {
      win.scrollTo({ left: 0, top: 0, behavior: "instant" });
    }
    const geometry = focused ? chatViewportGeometry(
      doc.documentElement.clientHeight,
      viewport!.height,
      viewport!.offsetTop,
      viewport!.scale,
    ) : null;
    page.classList.toggle("keyboard-viewport", geometry !== null);
    page.classList.toggle("compact-viewport", geometry?.compact ?? false);
    if (geometry) {
      page.style.setProperty("--chat-viewport-height", `${geometry.height}px`);
      page.style.setProperty("--chat-viewport-top", `${geometry.top}px`);
    } else {
      page.style.removeProperty("--chat-viewport-height");
      page.style.removeProperty("--chat-viewport-top");
    }
  }

  function schedule() {
    if (!frame) frame = win.requestAnimationFrame(update);
  }

  update();
  viewport.addEventListener("resize", schedule);
  viewport.addEventListener("scroll", schedule);
  win.addEventListener("resize", schedule);
  win.addEventListener("scroll", schedule);
  win.addEventListener("pageshow", schedule);
  doc.addEventListener("focusin", schedule);
  doc.addEventListener("focusout", schedule);
  doc.addEventListener("visibilitychange", schedule);
  return () => {
    viewport.removeEventListener("resize", schedule);
    viewport.removeEventListener("scroll", schedule);
    win.removeEventListener("resize", schedule);
    win.removeEventListener("scroll", schedule);
    win.removeEventListener("pageshow", schedule);
    doc.removeEventListener("focusin", schedule);
    doc.removeEventListener("focusout", schedule);
    doc.removeEventListener("visibilitychange", schedule);
    if (frame) win.cancelAnimationFrame(frame);
    page.classList.remove("keyboard-viewport", "compact-viewport");
    page.style.removeProperty("--chat-viewport-height");
    page.style.removeProperty("--chat-viewport-top");
  };
}
