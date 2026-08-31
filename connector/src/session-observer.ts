import { createHash, randomUUID } from "node:crypto";
import { mkdir, open, readFile, rename, rm } from "node:fs/promises";
import { dirname } from "node:path";
import {
  PROTOCOL_VERSION,
  parseWireMessage,
  type SessionLifecycleEvent,
  type SessionLifecycleEventKind,
  type SessionLifecycleState,
} from "@hermes-remote/protocol";

export type LiveSessionStatus = "starting" | "working" | "waiting" | "idle";

export interface LiveSessionSnapshot {
  id: string;
  sessionKey: string;
  status: LiveSessionStatus;
  lastActive: number;
  title?: string;
}

interface TrackedSession extends LiveSessionSnapshot {
  transitionSequence: number;
}

export interface LifecycleTrackerState {
  initialized: boolean;
  sessions: TrackedSession[];
}

export interface ObserverPersistentState {
  version: 1;
  tracker: LifecycleTrackerState;
  outbox: SessionLifecycleEvent[];
}

export interface ObserverStatePersistence {
  load(): Promise<ObserverPersistentState | undefined>;
  save(state: ObserverPersistentState): Promise<void>;
}

const ACTIVE_STATES = new Set<LiveSessionStatus>(["starting", "working", "waiting"]);

export class SessionLifecycleTracker {
  private initialized = false;
  private sessions = new Map<string, TrackedSession>();

  constructor(
    private readonly deviceId: string,
    private readonly profile?: string,
    state?: LifecycleTrackerState,
  ) {
    if (state) this.restore(state);
  }

  reduce(snapshot: LiveSessionSnapshot[], now = new Date()): SessionLifecycleEvent[] {
    const current = new Map(snapshot.map((session) => [session.id, session]));
    if (!this.initialized) {
      this.initialized = true;
      this.sessions = new Map(snapshot.map((session) => [session.id, {
        ...session,
        transitionSequence: 0,
      }]));
      return [];
    }

    const events: SessionLifecycleEvent[] = [];
    const next = new Map<string, TrackedSession>();
    for (const session of snapshot) {
      const previous = this.sessions.get(session.id);
      const transition = lifecycleTransition(previous?.status, session.status);
      const transitionSequence = previous?.transitionSequence ?? 0;
      if (transition) {
        const sequence = transitionSequence + 1;
        events.push(this.eventFor(session, transition, sequence, now));
        next.set(session.id, { ...session, transitionSequence: sequence });
      } else {
        next.set(session.id, { ...session, transitionSequence });
      }
    }

    for (const previous of this.sessions.values()) {
      if (current.has(previous.id) || !isActive(previous.status)) continue;
      const sequence = previous.transitionSequence + 1;
      events.push(this.eventFor(
        { ...previous, status: "idle" },
        "run.completed",
        sequence,
        now,
      ));
    }

    this.sessions = next;
    return events;
  }

  exportState(): LifecycleTrackerState {
    return {
      initialized: this.initialized,
      sessions: [...this.sessions.values()],
    };
  }

  private restore(state: LifecycleTrackerState): void {
    this.initialized = Boolean(state.initialized);
    this.sessions = new Map(
      state.sessions.map((session) => [session.id, {
        ...session,
        transitionSequence: nonNegativeInteger(session.transitionSequence),
      }]),
    );
  }

  private eventFor(
    session: LiveSessionSnapshot,
    event: SessionLifecycleEventKind,
    sequence: number,
    now: Date,
  ): SessionLifecycleEvent {
    const state = eventState(event, session.status);
    const identity = [
      this.deviceId,
      this.profile ?? "",
      session.id,
      session.sessionKey,
      event,
      sequence,
    ].join("\u0000");
    const eventId = `life_${createHash("sha256").update(identity).digest("hex").slice(0, 32)}`;
    return {
      type: "session.lifecycle",
      version: PROTOCOL_VERSION,
      eventId,
      deviceId: this.deviceId,
      ...(this.profile ? { profile: this.profile } : {}),
      runtimeSessionId: session.id,
      storedSessionId: session.sessionKey,
      event,
      state,
      occurredAt: now.toISOString(),
      ...(session.title ? { title: session.title.slice(0, 256) } : {}),
    };
  }
}

export class LifecycleOutbox {
  private readonly events = new Map<string, SessionLifecycleEvent>();

  constructor(events: SessionLifecycleEvent[] = []) {
    for (const event of events) this.events.set(event.eventId, event);
  }

  add(events: SessionLifecycleEvent[]): void {
    for (const event of events) this.events.set(event.eventId, event);
  }

  acknowledge(eventId: string): boolean {
    return this.events.delete(eventId);
  }

  pending(): SessionLifecycleEvent[] {
    return [...this.events.values()];
  }
}

export class ObserverStateStore implements ObserverStatePersistence {
  constructor(private readonly path: string) {}

  async load(): Promise<ObserverPersistentState | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.path, "utf8")) as unknown;
      return parsePersistentState(parsed);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
      throw error;
    }
  }

  async save(state: ObserverPersistentState): Promise<void> {
    const directory = dirname(this.path);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const temporary = `${this.path}.tmp-${process.pid}-${randomUUID()}`;
    const handle = await open(temporary, "wx", 0o600);
    try {
      await handle.writeFile(`${JSON.stringify(state)}\n`, "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    try {
      await rename(temporary, this.path);
    } finally {
      await rm(temporary, { force: true });
    }
  }
}

export function parseActiveList(value: unknown): LiveSessionSnapshot[] {
  if (!isRecord(value) || !Array.isArray(value.sessions)) throw new Error("invalid_active_list");
  return value.sessions.map((item) => {
    if (!isRecord(item)) throw new Error("invalid_active_session");
    const id = requiredString(item.id, "invalid_runtime_session_id", 256);
    const sessionKey = requiredString(item.session_key, "invalid_stored_session_id", 256);
    const status = item.status;
    if (status !== "starting" && status !== "working" && status !== "waiting" && status !== "idle") {
      throw new Error("invalid_active_session_status");
    }
    const lastActive = typeof item.last_active === "number" && Number.isFinite(item.last_active)
      ? item.last_active
      : 0;
    const title = typeof item.title === "string" && item.title.trim()
      ? item.title.trim().slice(0, 256)
      : undefined;
    return { id, sessionKey, status, lastActive, ...(title ? { title } : {}) };
  });
}

export function nextPollDelayMs(
  snapshot: LiveSessionSnapshot[],
  activeMs = 2_000,
  idleMs = 20_000,
): number {
  return snapshot.some((session) => isActive(session.status)) ? activeMs : idleMs;
}

function lifecycleTransition(
  previous: LiveSessionStatus | undefined,
  current: LiveSessionStatus,
): SessionLifecycleEventKind | undefined {
  if (previous === undefined || previous === "idle") {
    if (current === "waiting") return "run.waiting";
    if (current === "starting" || current === "working") return "run.started";
    return undefined;
  }
  if (previous === "waiting" && (current === "starting" || current === "working")) {
    return "run.resumed";
  }
  if (previous !== "waiting" && current === "waiting") return "run.waiting";
  if (isActive(previous) && current === "idle") return "run.completed";
  return undefined;
}

function eventState(
  event: SessionLifecycleEventKind,
  observed: LiveSessionStatus,
): SessionLifecycleState {
  if (event === "run.completed") return "idle";
  if (event === "run.interrupted" || event === "run.unknown") return "unknown";
  return observed;
}

function isActive(status: LiveSessionStatus): boolean {
  return ACTIVE_STATES.has(status);
}

function parsePersistentState(value: unknown): ObserverPersistentState {
  if (!isRecord(value) || value.version !== 1 || !isRecord(value.tracker) || !Array.isArray(value.outbox)) {
    throw new Error("invalid_observer_state");
  }
  const sessions = value.tracker.sessions;
  if (!Array.isArray(sessions)) throw new Error("invalid_observer_state");
  const tracker: LifecycleTrackerState = {
    initialized: value.tracker.initialized === true,
    sessions: sessions.map((item) => {
      if (!isRecord(item)) throw new Error("invalid_observer_state");
      const parsed = parseActiveList({ sessions: [{
        id: item.id,
        session_key: item.sessionKey,
        status: item.status,
        last_active: item.lastActive,
        title: item.title,
      }] })[0];
      return {
        ...parsed,
        transitionSequence: nonNegativeInteger(item.transitionSequence),
      };
    }),
  };
  const outbox = value.outbox.map((item) => {
    const parsed = parseWireMessage(JSON.stringify(item));
    if (parsed.type !== "session.lifecycle") throw new Error("invalid_observer_state");
    return parsed;
  });
  return { version: 1, tracker, outbox };
}

function requiredString(value: unknown, error: string, maxLength: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) throw new Error(error);
  return value;
}

function nonNegativeInteger(value: unknown): number {
  return Number.isInteger(value) && (value as number) >= 0 ? value as number : 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
