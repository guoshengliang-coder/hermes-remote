import type { StoredLifecycleEvent } from "../hermes/types";

// Relay inbox (lifecycle events) → "needs you" and foreground notices. Pure state + reducer; the
// polling loop lives in the app store.

export interface SessionLifecycle {
  storedSessionId: string;
  event: string;
  title: string | null;
  sequence: number;
  occurredAtMs: number;
}

export interface InboxState {
  cursor: number;
  /** Latest lifecycle event per stored session id (for this device). */
  latest: Record<string, SessionLifecycle>;
  /** Sessions that finished or started waiting after the first poll and were not opened since. */
  unseen: Record<string, SessionLifecycle>;
  /** The first page is the backlog: it sets state but raises no foreground notice. */
  primed: boolean;
}

export const initialInbox: InboxState = { cursor: 0, latest: {}, unseen: {}, primed: false };

/** Events that raise a foreground notice for a session the user is not looking at. */
const NOTIFYING: ReadonlySet<string> = new Set(["run.waiting", "run.completed"]);

export type InboxAction =
  | { type: "page"; events: StoredLifecycleEvent[]; nextCursor: number; deviceId: string; currentSessionId: string | null }
  | { type: "seen"; storedSessionId: string }
  | { type: "primed" };

export function reduceInbox(state: InboxState, action: InboxAction): InboxState {
  switch (action.type) {
    case "primed":
      return state.primed ? state : { ...state, primed: true };
    case "seen": {
      if (!(action.storedSessionId in state.unseen)) return state;
      const unseen = { ...state.unseen };
      delete unseen[action.storedSessionId];
      return { ...state, unseen };
    }
    case "page": {
      let latest = state.latest;
      let unseen = state.unseen;
      for (const stored of action.events) {
        const e = stored.event;
        if (!e || e.deviceId !== action.deviceId || !e.storedSessionId) continue;
        const prev = latest[e.storedSessionId];
        if (prev && prev.sequence >= stored.sequence) continue;
        const occurred = Date.parse(e.occurredAt);
        const entry: SessionLifecycle = {
          storedSessionId: e.storedSessionId,
          event: e.event,
          title: e.title ?? prev?.title ?? null,
          sequence: stored.sequence,
          occurredAtMs: Number.isNaN(occurred) ? 0 : occurred,
        };
        latest = { ...latest, [e.storedSessionId]: entry };
        if (state.primed && NOTIFYING.has(e.event) && e.storedSessionId !== action.currentSessionId) {
          unseen = { ...unseen, [e.storedSessionId]: entry };
        } else if (e.storedSessionId in unseen && !NOTIFYING.has(e.event)) {
          unseen = { ...unseen };
          delete unseen[e.storedSessionId];
        }
      }
      return { ...state, latest, unseen, cursor: Math.max(state.cursor, action.nextCursor) };
    }
  }
}

/** Sessions whose latest lifecycle event says the run is waiting on the user. */
export function needsYouFromInbox(state: InboxState): Set<string> {
  const out = new Set<string>();
  for (const entry of Object.values(state.latest)) if (entry.event === "run.waiting") out.add(entry.storedSessionId);
  return out;
}

/**
 * Per session, the inbox sequence that was the latest when a live chat saw its question settle
 * (-1 when the inbox had nothing for it yet). A waiting event at or before that point is stale; a
 * later one is a new question and shows again.
 */
export type LiveSettled = ReadonlyMap<string, number>;

export function settledMark(inbox: InboxState, storedSessionId: string): number {
  return inbox.latest[storedSessionId]?.sequence ?? -1;
}

/**
 * Union of the inbox view and what live sockets know (an open approval/clarify card), minus
 * waiting entries a live socket has seen settle since.
 */
export function deriveNeedsYou(
  inbox: InboxState,
  liveOpen: ReadonlySet<string>,
  liveSettled: LiveSettled = new Map(),
): Set<string> {
  const out = new Set<string>();
  for (const entry of Object.values(inbox.latest)) {
    if (entry.event !== "run.waiting") continue;
    if ((liveSettled.get(entry.storedSessionId) ?? -Infinity) >= entry.sequence) continue;
    out.add(entry.storedSessionId);
  }
  for (const id of liveOpen) out.add(id);
  return out;
}

/**
 * How a chat page reports its question state. "left" only withdraws what the page itself knew:
 * leaving a conversation with a card still open must not read as having answered it.
 */
export type LiveQuestionReport = "open" | "settled" | "left";

export function applyLiveReport(
  state: { open: ReadonlySet<string>; settled: LiveSettled },
  storedSessionId: string,
  report: LiveQuestionReport,
  mark: number,
): { open: ReadonlySet<string>; settled: LiveSettled } {
  const open = new Set(state.open);
  const settled = new Map(state.settled);
  if (report === "open") {
    open.add(storedSessionId);
    settled.delete(storedSessionId);
  } else {
    open.delete(storedSessionId);
    if (report === "settled") settled.set(storedSessionId, mark);
  }
  return { open, settled };
}

/** Foreground badge count: unseen notices for sessions other than the one on screen. */
export function noticeCount(state: InboxState, currentSessionId: string | null): number {
  return Object.keys(state.unseen).filter((id) => id !== currentSessionId).length;
}

/** Ids to POST to /api/mobile/events/ack: received but not yet marked delivered. */
export function undeliveredIds(events: readonly StoredLifecycleEvent[]): string[] {
  return events.filter((e) => !e.deliveredAt && e.event?.eventId).map((e) => e.event.eventId);
}
