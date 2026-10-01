import { useLayoutEffect, useMemo, useRef } from "preact/hooks";
import { pageSnapshotKey, readPageSnapshot, savePageSnapshot } from "./router";

interface ListSnapshot<T> { view: T; scrollTop: number }

/** Keep the page frame's own scroll position until asynchronously fetched rows can restore it. */
export function useListReturn<T>(scope: string, initial: T) {
  const key = useMemo(() => pageSnapshotKey(scope), [scope]);
  const saved = useMemo(() => readPageSnapshot<ListSnapshot<T>>(key), [key]);
  const port = useRef<HTMLDivElement>(null);
  const pending = useRef<number | null>(saved?.scrollTop ?? 0);
  const view = useRef(saved?.view ?? initial);
  const remember = (next: T) => {
    view.current = next;
    savePageSnapshot(key, { view: next, scrollTop: pending.current ?? port.current?.scrollTop ?? 0 });
  };
  const restore = () => {
    const el = port.current;
    if (!el || pending.current === null) return;
    // Results may arrive after the shell. Do not clamp the saved position to the empty shell.
    if (el.scrollHeight - el.clientHeight < pending.current) return;
    el.scrollTop = pending.current;
    pending.current = null;
  };
  useLayoutEffect(() => {
    const el = port.current;
    if (!el) return;
    restore();
    const cancel = () => { pending.current = null; };
    const scroll = () => {
      if (pending.current === null) savePageSnapshot(key, { view: view.current, scrollTop: el.scrollTop });
    };
    const observer = typeof ResizeObserver === "function" ? new ResizeObserver(restore) : null;
    if (el.firstElementChild) observer?.observe(el.firstElementChild);
    el.addEventListener("scroll", scroll);
    el.addEventListener("pointerdown", cancel);
    el.addEventListener("wheel", cancel);
    el.addEventListener("keydown", cancel);
    return () => {
      observer?.disconnect();
      el.removeEventListener("scroll", scroll);
      el.removeEventListener("pointerdown", cancel);
      el.removeEventListener("wheel", cancel);
      el.removeEventListener("keydown", cancel);
    };
  }, [key]);
  return { initial: saved?.view ?? initial, port, remember, restore };
}
