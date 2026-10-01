import { useEffect, useRef, useState } from "preact/hooks";
import type { MessageRow } from "../hermes/types";
import type { AppError } from "../errors";
import { historySyncError } from "./history";
import type { ChatState } from "./model";

/** A prompt count covers the whole conversation. Merge older rows without replacing live turns. */
export function usePromptHistory(open: boolean, stored: boolean, state: ChatState,
  loadFull: () => Promise<MessageRow[]>, onLoaded: (rows: MessageRow[], epoch: number) => void) {
  const calls = useRef({ loadFull, onLoaded });
  calls.current = { loadFull, onLoaded };
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<AppError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const needFull = open && stored && state.older.hasMore;
  useEffect(() => {
    if (!needFull) { setLoading(false); setError(null); return; }
    if (!state.historyLoaded) return;
    let live = true;
    const epoch = state.historyEpoch;
    setLoading(true); setError(null);
    calls.current.loadFull().then((rows) => {
      if (!live) return;
      calls.current.onLoaded(rows, epoch);
      setLoading(false);
    }, (failure: unknown) => {
      if (!live) return;
      setError(historySyncError(failure, "full history for prompts"));
      setLoading(false);
    });
    return () => { live = false; };
  }, [open, needFull, state.historyLoaded, state.historyEpoch, attempt]);
  return {
    loading: open && (loading || (stored && !state.historyLoaded) || (needFull && !error)),
    error,
    retry: () => setAttempt((n) => n + 1),
  };
}
