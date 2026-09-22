import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GatewayHttpError } from "../api/gateway";
import { loadSessions } from "../ui/SessionList";
import { createListRefresher, LIST_REFRESH_DEBOUNCE_MS } from "./listRefresh";

// HG-104: the session list is refetched on inbox-cursor moves through a debounced, single-flight
// refresher, never while the page is hidden.

async function flush() {
  for (let i = 0; i < 10; i++) await Promise.resolve();
}

function deferredRun() {
  const releases: Array<() => void> = [];
  const run = vi.fn(() => new Promise<void>((resolve) => releases.push(resolve)));
  return { run, release: async () => {
    releases.shift()?.();
    await flush();
  } };
}

describe("createListRefresher", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("coalesces a burst of cursor moves into one trailing request", async () => {
    const { run } = deferredRun();
    const r = createListRefresher(run, { isHidden: () => false });
    for (let i = 0; i < 5; i++) {
      r.soon();
      vi.advanceTimersByTime(300);
    }
    expect(run).not.toHaveBeenCalled();
    vi.advanceTimersByTime(LIST_REFRESH_DEBOUNCE_MS);
    expect(run).toHaveBeenCalledTimes(1);
    r.dispose();
  });

  it("keeps one request in flight and runs exactly one more for triggers that arrived meanwhile", async () => {
    const { run, release } = deferredRun();
    const r = createListRefresher(run, { isHidden: () => false });
    r.now();
    r.now();
    r.soon();
    vi.advanceTimersByTime(LIST_REFRESH_DEBOUNCE_MS);
    r.now();
    expect(run).toHaveBeenCalledTimes(1);
    await release();
    expect(run).toHaveBeenCalledTimes(2);
    await release();
    expect(run).toHaveBeenCalledTimes(2);
    r.dispose();
  });

  it("a failed request does not wedge the refresher", async () => {
    const run = vi.fn(() => Promise.reject(new Error("offline")));
    const r = createListRefresher(run, { isHidden: () => false });
    r.now();
    expect(run).toHaveBeenCalledTimes(1);
    await flush();
    r.now();
    expect(run).toHaveBeenCalledTimes(2);
    r.dispose();
  });

  it("does nothing for cursor moves while the page is hidden, or when it became hidden meanwhile", () => {
    let hidden = true;
    const { run } = deferredRun();
    const r = createListRefresher(run, { isHidden: () => hidden });
    r.soon();
    vi.advanceTimersByTime(LIST_REFRESH_DEBOUNCE_MS * 2);
    expect(run).not.toHaveBeenCalled();
    hidden = false;
    r.soon();
    hidden = true;
    vi.advanceTimersByTime(LIST_REFRESH_DEBOUNCE_MS);
    expect(run).not.toHaveBeenCalled();
    // Coming back is the visible handler's immediate refresh.
    hidden = false;
    r.now();
    expect(run).toHaveBeenCalledTimes(1);
    r.dispose();
  });

  it("reads document visibility by default", () => {
    const spy = vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    const { run } = deferredRun();
    const r = createListRefresher(run);
    r.soon();
    vi.advanceTimersByTime(LIST_REFRESH_DEBOUNCE_MS);
    expect(run).not.toHaveBeenCalled();
    spy.mockRestore();
    r.dispose();
  });

  it("is silent after dispose", () => {
    const { run } = deferredRun();
    const r = createListRefresher(run, { isHidden: () => false });
    r.soon();
    r.dispose();
    vi.advanceTimersByTime(LIST_REFRESH_DEBOUNCE_MS);
    r.now();
    expect(run).not.toHaveBeenCalled();
  });
});

describe("loadSessions", () => {
  it("asks the cross-profile list for 500 rows and falls back to the default profile's list at limit=100", async () => {
    const calls: string[] = [];
    const client = {
      deviceApi: async (_device: string, _method: string, rest: string) => {
        calls.push(rest);
        if (rest.startsWith("profiles/sessions")) throw new GatewayHttpError(404, null, rest);
        return { sessions: [{ id: "s1" }] };
      },
    } as unknown as Parameters<typeof loadSessions>[0];
    expect(await loadSessions(client, "mac")).toEqual([{ id: "s1" }]);
    expect(calls).toEqual(["profiles/sessions?limit=500&order=recent", "sessions?limit=100&offset=0&order=recent"]);
  });
});
