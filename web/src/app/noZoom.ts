// iOS ignores `user-scalable=no`, so an installed Home Screen app would still pinch-zoom the whole
// page — including the top bar and composer — which no native app does. Blocking Safari's gesture
// events is the only way to stop it. Only in standalone: in a normal browser tab the page is a
// page, and the browser's zoom stays the user's to use.

export function blockPageZoom(): () => void {
  const standalone =
    (typeof matchMedia === "function" && matchMedia("(display-mode: standalone)").matches) ||
    (navigator as unknown as { standalone?: boolean }).standalone === true;
  if (!standalone) return () => {};
  const stop = (event: Event) => event.preventDefault();
  // gesture* are Safari-only and fire for pinch; Chrome honours the viewport meta instead.
  for (const name of ["gesturestart", "gesturechange", "gestureend"]) document.addEventListener(name, stop);
  return () => {
    for (const name of ["gesturestart", "gesturechange", "gestureend"]) document.removeEventListener(name, stop);
  };
}
