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
 * Union of the inbox view and what live sockets know (an open approval/clarify card), minus
 * sessions a live socket has seen settle since.
 */
export function deriveNeedsYou(
  inbox: InboxState,
  liveOpen: ReadonlySet<string>,
  liveSettled: ReadonlySet<string> = new Set(),
): Set<string> {
  const out = needsYouFromInbox(inbox);
  for (const id of liveSettled) out.delete(id);
  for (const id of liveOpen) out.add(id);
  return out;
}

/** Foreground badge count: unseen notices for sessions other than the one on screen. */
export function noticeCount(state: InboxState, currentSessionId: string | null): number {
  return Object.keys(state.unseen).filter((id) => id !== currentSessionId).length;
}

/** Ids to POST to /api/mobile/events/ack: received but not yet marked delivered. */
export function undeliveredIds(events: readonly StoredLifecycleEvent[]): string[] {
  return events.filter((e) => !e.deliveredAt && e.event?.eventId).map((e) => e.event.eventId);
}
