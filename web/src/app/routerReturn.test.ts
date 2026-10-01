import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { clearPageSnapshots, currentRoute, navigate, pageSnapshotKey, readPageSnapshot, returnFromChat, savePageSnapshot } from "./router";
import { pushOverlay, resetOverlays } from "./overlayHistory";

// Drive URLs as well as state: happy-dom does not deliver popstate for history.go().
let entries: Array<{ state: unknown; path: string }>;
let index: number;
beforeEach(() => {
  resetOverlays(); clearPageSnapshots();
  const replace = history.replaceState.bind(history);
  entries = [{ state: null, path: "/app/" }]; index = 0;
  replace(null, "", "/app/");
  vi.spyOn(history, "state", "get").mockImplementation(() => entries[index]!.state);
  vi.spyOn(history, "replaceState").mockImplementation((state, _, url) => {
    const path = url?.toString() ?? location.pathname;
    entries[index] = { state, path }; replace(state, "", path);
  });
  vi.spyOn(history, "pushState").mockImplementation((state, _, url) => {
    const path = url?.toString() ?? location.pathname;
    entries.splice(index + 1); entries.push({ state, path }); index++;
    replace(state, "", path);
  });
  vi.spyOn(history, "go").mockImplementation((delta) => {
    index = Math.max(0, Math.min(entries.length - 1, index + (delta ?? 0)));
    const entry = entries[index]!; replace(entry.state, "", entry.path);
    window.dispatchEvent(new PopStateEvent("popstate", { state: entry.state }));
  });
});
afterEach(() => { resetOverlays(); vi.restoreAllMocks(); });

it("toolbar Back returns to Archived and retains that entry's position", () => {
  navigate({ name: "archived" });
  const key = pageSnapshotKey("archive:mac");
  savePageSnapshot(key, { scrollTop: 420 });
  navigate({ name: "chat", sessionId: "archived-one" });
  returnFromChat();
  expect(currentRoute()).toEqual({ name: "archived" });
  expect(pageSnapshotKey("archive:mac")).toBe(key);
  expect(readPageSnapshot(key)).toEqual({ scrollTop: 420 });
});
it("system Back from a search result returns to the saved search entry", () => {
  const key = pageSnapshotKey("sessions:mac");
  savePageSnapshot(key, { searching: true, query: "release", scrollTop: 260 });
  const release = pushOverlay(() => undefined);
  navigate({ name: "chat", sessionId: "hit" });
  release();
  history.go(-1);
  expect(currentRoute()).toEqual({ name: "list" });
  expect(readPageSnapshot(pageSnapshotKey("sessions:mac"))).toEqual({ searching: true, query: "release", scrollTop: 260 });
  expect(readPageSnapshot(pageSnapshotKey("sessions:another-mac"))).toBeUndefined();
});
it("toolbar Back skips a focused composer and unmount cannot rewind twice", () => {
  navigate({ name: "archived" }); navigate({ name: "chat", sessionId: "one" });
  const release = pushOverlay(() => undefined);
  returnFromChat(); release();
  expect(currentRoute()).toEqual({ name: "archived" });
  expect(history.go).toHaveBeenCalledExactlyOnceWith(-2);
});
it("new chat and its durable id keep the original return entry", () => {
  navigate({ name: "archived" }); navigate({ name: "chat", sessionId: "one" });
  navigate({ name: "new" });
  navigate({ name: "chat", sessionId: "created" }, { replace: true });
  returnFromChat();
  expect(currentRoute()).toEqual({ name: "archived" });
});
it("system Back from a new chat skips the replaced chat even with a focused composer", () => {
  navigate({ name: "archived" }); navigate({ name: "chat", sessionId: "one" });
  const release = pushOverlay(() => undefined);
  navigate({ name: "new" }); release();
  expect(currentRoute()).toEqual({ name: "new" });
  history.go(-1);
  expect(currentRoute()).toEqual({ name: "archived" });
});
it("direct links fall back to the list without leaving the app", () => {
  history.replaceState(null, "", "/app/s/direct");
  returnFromChat();
  expect(currentRoute()).toEqual({ name: "list" });
  expect(history.go).not.toHaveBeenCalled();
});
it("sign-out removes saved searches and rows", () => {
  const key = pageSnapshotKey("sessions:mac"); savePageSnapshot(key, { query: "private" });
  clearPageSnapshots();
  expect(readPageSnapshot(key)).toBeUndefined();
  expect(pageSnapshotKey("sessions:mac")).not.toBe(key);
});
