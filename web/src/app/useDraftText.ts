import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "preact/hooks";
import { draftEpoch, loadDraft, saveDraft } from "./drafts";

// Track edits synchronously: a page can disappear before either React's effects or the 400 ms
// debounce run. Each conversation owns its writer, and sign-out invalidates existing writers.
function draftWriter(key: string | null) {
  const epoch = draftEpoch();
  let text = key ? loadDraft(key) : "";
  let dirty = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const flush = () => {
    clearTimeout(timer);
    if (dirty && key && epoch === draftEpoch()) saveDraft(key, text);
    dirty = false;
  };
  return {
    get text() { return text; },
    set(value: string) {
      text = value; dirty = true;
      clearTimeout(timer);
      timer = setTimeout(flush, 400);
    },
    flush,
  };
}

export function useDraftText(key: string | null): [string, (value: string | ((previous: string) => string)) => void, () => void] {
  const writer = useMemo(() => draftWriter(key), [key]);
  const current = useRef(writer);
  current.current = writer;
  const [text, setText] = useState(writer.text);
  // Composer listeners live across key changes, just as a normal useState setter does.
  const update = useCallback((value: string | ((previous: string) => string)) => {
    const active = current.current;
    active.set(typeof value === "function" ? value(active.text) : value);
    setText(active.text);
  }, []);
  const flush = useCallback(() => current.current.flush(), []);
  useLayoutEffect(() => {
    setText(writer.text);
    const hide = () => { if (document.visibilityState === "hidden") writer.flush(); };
    window.addEventListener("pagehide", writer.flush);
    document.addEventListener("visibilitychange", hide);
    return () => {
      writer.flush();
      window.removeEventListener("pagehide", writer.flush);
      document.removeEventListener("visibilitychange", hide);
    };
  }, [writer]);
  return [text, update, flush];
}
