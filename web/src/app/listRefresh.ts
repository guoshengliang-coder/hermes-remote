// Session-list refresh scheduling (HG-104). The list is one ~500-row request, and the lifecycle
// inbox cursor that hints "the list moved" can advance every 5 s poll, so background hints are
// coalesced: a trailing debounce, nothing while the page is hidden (returning to it refreshes
// anyway), and at most one request in flight — a trigger that arrives meanwhile earns exactly one
// more request after it, never a queue.

export const LIST_REFRESH_DEBOUNCE_MS = 1000;

export interface ListRefresher {
  /** A user or lifecycle action (open, Retry, back to the page): run now, still single-flight. */
  now(): void;
  /** A background hint (inbox cursor moved): run once things have been quiet for the debounce. */
  soon(): void;
  dispose(): void;
}

export interface ListRefresherOptions {
  debounceMs?: number;
  isHidden?: () => boolean;
}

const documentHidden = () => typeof document !== "undefined" && document.visibilityState === "hidden";

export function createListRefresher(run: () => Promise<void>, options: ListRefresherOptions = {}): ListRefresher {
  const debounceMs = options.debounceMs ?? LIST_REFRESH_DEBOUNCE_MS;
  const isHidden = options.isHidden ?? documentHidden;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let inFlight = false;
  let again = false;
  let disposed = false;

  const clearTimer = () => {
    if (timer) clearTimeout(timer);
    timer = null;
  };

  const trigger = () => {
    if (disposed) return;
    if (inFlight) {
      again = true;
      return;
    }
    inFlight = true;
    void run()
      .catch(() => undefined)
      .finally(() => {
        inFlight = false;
        if (again && !disposed) {
          again = false;
          trigger();
        }
      });
  };

  return {
    now() {
      clearTimer();
      trigger();
    },
    soon() {
      if (disposed || isHidden()) return;
      clearTimer();
      timer = setTimeout(() => {
        timer = null;
        if (!isHidden()) trigger();
      }, debounceMs);
    },
    dispose() {
      disposed = true;
      again = false;
      clearTimer();
    },
  };
}
