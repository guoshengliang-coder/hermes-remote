import { describe, expect, it } from "vitest";
import type { StoredLifecycleEvent } from "../hermes/types";
import { applyLiveReport, deriveNeedsYou, initialInbox, settledMark, needsYouFromInbox, noticeCount, reduceInbox, undeliveredIds, type InboxState } from "./inbox";

let seq = 0;
function ev(storedSessionId: string, event: string, extra: Partial<StoredLifecycleEvent> = {}, deviceId = "mac"): StoredLifecycleEvent {
  seq += 1;
  return {
    sequence: seq,
    receivedAt: new Date().toISOString(),
    deliveredAt: null,
    ...extra,
    event: {
      type: "lifecycle",
      version: 1,
      eventId: `e${seq}`,
      deviceId,
      runtimeSessionId: `live-${storedSessionId}`,
      storedSessionId,
      event,
      state: event,
      occurredAt: "2026-09-21T10:00:00Z",
      title: `T ${storedSessionId}`,
    },
  };
}

function page(state: InboxState, events: StoredLifecycleEvent[], currentSessionId: string | null = null) {
  return reduceInbox(state, { type: "page", events, nextCursor: events.at(-1)?.sequence ?? state.cursor, deviceId: "mac", currentSessionId });
}

describe("needs-you from the inbox", () => {
  it("is the set of sessions whose LATEST event is run.waiting", () => {
    let s = page(initialInbox, [ev("a", "run.started"), ev("a", "run.waiting"), ev("b", "run.waiting"), ev("b", "run.resumed")]);
    expect([...needsYouFromInbox(s)]).toEqual(["a"]);
    s = page(s, [ev("a", "run.completed")]);
    expect(needsYouFromInbox(s).size).toBe(0);
  });

  it("ignores other devices and older sequences, and advances the cursor", () => {
    const late = ev("a", "run.waiting");
    const early = { ...ev("a", "run.completed"), sequence: 1 };
    const s = page(initialInbox, [late, early, ev("z", "run.waiting", {}, "other-mac")]);
    expect([...needsYouFromInbox(s)]).toEqual(["a"]);
    expect(s.cursor).toBeGreaterThan(0);
  });

  it("merges live socket knowledge: an open card adds, a settled card removes", () => {
    const s = page(initialInbox, [ev("a", "run.waiting"), ev("b", "run.waiting")]);
    const set = deriveNeedsYou(s, new Set(["c"]), new Map([["b", settledMark(s, "b")]]));
    expect([...set].sort()).toEqual(["a", "c"]);
  });

  it("a later waiting event outranks an earlier settle", () => {
    let s = page(initialInbox, [ev("b", "run.waiting")]);
    const settled = new Map([["b", settledMark(s, "b")]]);
    expect(deriveNeedsYou(s, new Set(), settled).has("b")).toBe(false);
    s = page(s, [ev("b", "run.started"), ev("b", "run.waiting")]);
    expect(deriveNeedsYou(s, new Set(), settled).has("b")).toBe(true);
  });

  it("leaving a chat with its card still open keeps the session in needs-you", () => {
    const s = page(initialInbox, [ev("a", "run.waiting")]);
    let live = applyLiveReport({ open: new Set(), settled: new Map() }, "a", "open", settledMark(s, "a"));
    live = applyLiveReport(live, "a", "left", settledMark(s, "a"));
    expect(deriveNeedsYou(s, live.open, live.settled).has("a")).toBe(true);
    live = applyLiveReport(live, "a", "settled", settledMark(s, "a"));
    expect(deriveNeedsYou(s, live.open, live.settled).has("a")).toBe(false);
  });
});

describe("foreground notices", () => {
  it("the backlog before priming never notifies", () => {
    const s = page(initialInbox, [ev("a", "run.completed")]);
    expect(noticeCount(s, null)).toBe(0);
  });

  it("after priming, waiting/completed of another session notify; the open session does not", () => {
    let s = reduceInbox(initialInbox, { type: "primed" });
    s = page(s, [ev("a", "run.completed"), ev("b", "run.waiting"), ev("c", "run.started")], "b");
    expect(Object.keys(s.unseen)).toEqual(["a"]);
    expect(noticeCount(s, null)).toBe(1);
    expect(noticeCount(s, "a")).toBe(0);
    s = reduceInbox(s, { type: "seen", storedSessionId: "a" });
    expect(noticeCount(s, null)).toBe(0);
  });

  it("a later non-notifying event clears a stale notice", () => {
    let s = reduceInbox(initialInbox, { type: "primed" });
    s = page(s, [ev("a", "run.waiting")]);
    s = page(s, [ev("a", "run.resumed")]);
    expect(noticeCount(s, null)).toBe(0);
  });

  it("acks only undelivered events", () => {
    const events = [ev("a", "run.started"), ev("a", "run.completed", { deliveredAt: "2026-09-21T10:00:01Z" })];
    expect(undeliveredIds(events)).toEqual([events[0]!.event.eventId]);
  });
});
