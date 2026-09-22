// System back closes the top overlay (Android Chrome's back gesture, iOS edge swipe in the Home
// Screen app): every open sheet, menu, viewer or full-screen page owns one same-URL history
// entry. Back pops it and closes that overlay only; closing the overlay any other way (✕, scrim,
// a choice) pops its entry silently so the history never fills with dead steps.
//
// `live` mirrors which of our entries are in the browser's history, bottom to top. We never read
// history back to decide what to rewind: history.go() is asynchronous, so the browser's idea of
// "now" lags behind a close that happened in the same render.

export interface OverlayEntry {
  id: number;
  /** Close in response to back. Return false to stay open (e.g. "discard these edits?"). */
  close: () => boolean | void;
}

const KEY = "hrOverlay";
/** Open overlays, bottom to top. */
const stack: OverlayEntry[] = [];
/** Ids of our entries currently in history, bottom to top. */
const live: number[] = [];
/** Opened while one of our rewinds was still travelling: pushed once it lands. */
const waiting: OverlayEntry[] = [];
let seq = 0;
/** Rewinds of our own whose popstate must not close anything. */
let ignorePops = 0;
let installed = false;

function pushEntry(entry: OverlayEntry): void {
  history.pushState({ [KEY]: entry.id }, "");
  live.push(entry.id);
}

function stateId(): number | null {
  const s = history.state as Record<string, unknown> | null;
  return s && typeof s[KEY] === "number" ? (s[KEY] as number) : null;
}

function onPopState(): void {
  if (ignorePops > 0) {
    ignorePops--;
    // An overlay that opened while the rewind travelled (viewer → editor) gets its entry now,
    // or the rewind would have landed behind it.
    if (ignorePops === 0) for (const entry of waiting.splice(0)) if (stack.includes(entry)) pushEntry(entry);
    return;
  }
  // The user went back past one or more of our entries: they are gone from history. Close the
  // overlays they belonged to, top first.
  const now = stateId();
  while (live.length && live[live.length - 1] !== now) {
    const id = live.pop()!;
    const index = stack.findIndex((e) => e.id === id);
    if (index < 0) continue;
    const [entry] = stack.splice(index, 1);
    if (entry!.close() === false) {
      // It refused (a confirmation is showing): it stays open and gets its entry back.
      stack.splice(index, 0, entry!);
      pushEntry(entry!);
      break;
    }
  }
}

function install(): void {
  if (installed || typeof window === "undefined") return;
  installed = true;
  window.addEventListener("popstate", onPopState);
}

/** Register an open overlay; the returned function is called when it closes by other means. */
export function pushOverlay(close: OverlayEntry["close"]): () => void {
  install();
  const entry: OverlayEntry = { id: ++seq, close };
  stack.push(entry);
  if (ignorePops > 0) waiting.push(entry);
  else pushEntry(entry);
  return () => release(entry);
}

function release(entry: OverlayEntry): void {
  const index = stack.indexOf(entry);
  if (index < 0) return; // already closed by back
  stack.splice(index, 1);
  const w = waiting.indexOf(entry);
  if (w >= 0) {
    waiting.splice(w, 1); // never got an entry
    return;
  }
  const at = live.indexOf(entry.id);
  if (at < 0) return; // a navigation took its entry's place
  // Entries above it belong to overlays that are closing in the same render (children); they
  // go with it in one rewind.
  const count = live.length - at;
  live.splice(at);
  ignorePops++;
  history.go(-count);
}

/**
 * navigate() while an overlay is open replaces the top overlay entry with the new page instead
 * of stacking on it; returns whether it did (the caller then uses replaceState).
 */
export function yieldTopEntry(): boolean {
  if (!live.length || stateId() !== live[live.length - 1]) return false;
  live.pop();
  return true;
}

/** Forget everything (tests). */
export function resetOverlays(): void {
  stack.length = 0;
  live.length = 0;
  waiting.length = 0;
  ignorePops = 0;
}

export function overlayDepth(): number {
  return stack.length;
}

export function liveEntries(): number {
  return live.length;
}
