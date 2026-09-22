import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { liveEntries, overlayDepth, pushOverlay, resetOverlays, yieldTopEntry } from "./overlayHistory";

// happy-dom's history does not fire popstate on go(); drive it like the browser would.
const entries: unknown[] = [];
let index = 0;
function fakeHistory() {
  entries.length = 0;
  entries.push(null);
  index = 0;
  vi.spyOn(history, "pushState").mockImplementation((state: unknown) => {
    entries.splice(index + 1);
    entries.push(state);
    index++;
  });
  vi.spyOn(history, "replaceState").mockImplementation((state: unknown) => {
    entries[index] = state;
  });
  vi.spyOn(history, "go").mockImplementation((delta?: number) => {
    index = Math.max(0, index + (delta ?? 0));
    queueMicrotask(() => window.dispatchEvent(new PopStateEvent("popstate", { state: entries[index] })));
  });
  vi.spyOn(history, "state", "get").mockImplementation(() => entries[index] ?? null);
}
const back = async () => {
  history.go(-1);
  await Promise.resolve();
  await Promise.resolve();
};

describe("system back closes the top overlay", () => {
  beforeEach(() => {
    resetOverlays();
    fakeHistory();
  });
  afterEach(() => vi.restoreAllMocks());

  it("back closes only the top overlay, then the next", async () => {
    const closed: string[] = [];
    pushOverlay(() => void closed.push("sheet"));
    pushOverlay(() => void closed.push("confirm"));
    expect(index).toBe(2);
    await back();
    expect(closed).toEqual(["confirm"]);
    await back();
    expect(closed).toEqual(["confirm", "sheet"]);
    expect(overlayDepth()).toBe(0);
  });

  it("closing by other means rewinds silently and closes nothing else", async () => {
    const closed: string[] = [];
    pushOverlay(() => void closed.push("sheet"));
    const release = pushOverlay(() => void closed.push("viewer"));
    release();
    await Promise.resolve();
    await Promise.resolve();
    expect(index).toBe(1);
    expect(closed).toEqual([]);
    expect(overlayDepth()).toBe(1);
  });

  it("closing a parent with a child open rewinds both in one step", async () => {
    const releaseParent = pushOverlay(() => undefined);
    const releaseChild = pushOverlay(() => undefined);
    releaseParent();
    releaseChild(); // already gone: no second rewind
    await Promise.resolve();
    await Promise.resolve();
    expect(index).toBe(0);
    expect(history.go).toHaveBeenCalledTimes(1);
  });

  it("an overlay that refuses stays open with its entry restored", async () => {
    let asks = 0;
    pushOverlay(() => {
      asks++;
      return false;
    });
    await back();
    expect(asks).toBe(1);
    expect(overlayDepth()).toBe(1);
    expect(index).toBe(1);
    expect(liveEntries()).toBe(1);
  });

  it("does not rewind when a navigation already replaced the entry", async () => {
    const release = pushOverlay(() => undefined);
    expect(yieldTopEntry()).toBe(true);
    history.replaceState(null, "", "/app/s/x");
    release();
    expect(history.go).not.toHaveBeenCalled();
    expect(overlayDepth()).toBe(0);
  });

  it("child closing before its parent in the same render still rewinds both", async () => {
    const releaseViewer = pushOverlay(() => undefined);
    const releaseEditor = pushOverlay(() => undefined);
    releaseEditor();
    releaseViewer();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    expect(index).toBe(0);
    expect(liveEntries()).toBe(0);
  });

  it("an overlay opened while a rewind is travelling gets its entry after it lands", async () => {
    const closed: string[] = [];
    const releaseViewer = pushOverlay(() => void closed.push("viewer"));
    releaseViewer(); // viewer closes...
    pushOverlay(() => void closed.push("editor")); // ...and the editor opens in the same commit
    await Promise.resolve();
    await Promise.resolve();
    expect(index).toBe(1);
    expect(liveEntries()).toBe(1);
    await back();
    expect(closed).toEqual(["editor"]);
  });
});
