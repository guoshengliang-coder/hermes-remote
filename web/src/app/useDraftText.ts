import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { draftEpoch, loadDraft, saveDraft } from "./drafts";

// Hold a conversation's writer across remounts while a durable send is taking ownership.
// A late handoff can consume exactly the revision it accepted and notify the reopened editor.
type Writer = ReturnType<typeof draftWriter>;
const writers = new Map<string, Writer>();

function draftWriter(key: string | null) {
  const epoch = draftEpoch();
  const listeners = new Set<(value: string) => void>();
  let text = key ? loadDraft(key) : "";
  let revision = 0;
  let holders = 0;
  let dirty = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const release = () => {
    if (key && !holders && !listeners.size && writers.get(key) === writer) writers.delete(key);
  };
  const flush = () => {
    clearTimeout(timer);
    if (dirty && key && epoch === draftEpoch()) saveDraft(key, text);
    dirty = false;
  };
  const writer = {
    epoch,
    get text() { return text; },
    set(value: string) {
      if (epoch !== draftEpoch()) return;
      text = value;
      revision++;
      dirty = true;
      clearTimeout(timer);
      timer = setTimeout(flush, 400);
      for (const listener of listeners) listener(value);
    },
    listen(listener: (value: string) => void) {
      listeners.add(listener);
      return () => { listeners.delete(listener); release(); };
    },
    hold() {
      const expected = revision;
      holders++;
      let released = false;
      return {
        consume() {
          if (revision === expected && epoch === draftEpoch()) {
            writer.set("");
            flush();
          }
        },
        release() {
          if (released) return;
          released = true;
          holders--;
          release();
        },
      };
    },
    flush,
  };
  return writer;
}

export function useDraftText(key: string | null) {
  const writer = useMemo(() => {
    const saved = key ? writers.get(key) : undefined;
    if (saved?.epoch === draftEpoch()) return saved;
    const created = draftWriter(key);
    if (key) writers.set(key, created);
    return created;
  }, [key]);
  const current = useRef(writer);
  current.current = writer;
  const [text, setText] = useState(writer.text);
  const update = useCallback((value: string | ((previous: string) => string)) => {
    const active = current.current;
    active.set(typeof value === "function" ? value(active.text) : value);
  }, []);
  const flush = useCallback(() => current.current.flush(), []);
  const hold = useCallback(() => current.current.hold(), []);
  useLayoutEffect(() => {
    setText(writer.text);
    const stop = writer.listen(setText);
    const hide = () => { if (document.visibilityState === "hidden") writer.flush(); };
    window.addEventListener("pagehide", writer.flush);
    document.addEventListener("visibilitychange", hide);
    return () => {
      writer.flush();
      stop();
      window.removeEventListener("pagehide", writer.flush);
      document.removeEventListener("visibilitychange", hide);
    };
  }, [writer]);
  return [text, update, flush, hold] as const;
}
