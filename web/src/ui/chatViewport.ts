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

export function followChatVisualViewport(page: HTMLElement, win: Window = window): () => void {
  const viewport = win.visualViewport;
  if (!viewport) return () => {};
  let frame = 0;

  function update() {
    frame = 0;
    const geometry = chatViewportGeometry(
      win.document.documentElement.clientHeight,
      viewport!.height,
      viewport!.offsetTop,
      viewport!.scale,
    );
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
  return () => {
    viewport.removeEventListener("resize", schedule);
    viewport.removeEventListener("scroll", schedule);
    win.removeEventListener("resize", schedule);
    if (frame) win.cancelAnimationFrame(frame);
    page.classList.remove("keyboard-viewport", "compact-viewport");
    page.style.removeProperty("--chat-viewport-height");
    page.style.removeProperty("--chat-viewport-top");
  };
}
