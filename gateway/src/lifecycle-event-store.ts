import { randomUUID } from "node:crypto";
import { mkdir, open, readFile, rename, rm } from "node:fs/promises";
import { dirname } from "node:path";
import {
  parseWireMessage,
  type SessionLifecycleEvent,
} from "@hermes-remote/protocol";
import { silentGatewayLogger, type GatewayLogger } from "./gateway-log.js";

export interface StoredLifecycleEvent {
  sequence: number;
  event: SessionLifecycleEvent;
  receivedAt: string;
  deliveredAt?: string;
  readAt?: string;
}

interface StoreFile {
  version: 1;
  nextSequence: number;
  events: StoredLifecycleEvent[];
}

export interface LifecycleEventPage {
  events: StoredLifecycleEvent[];
  nextCursor: number;
  hasMore: boolean;
}

/**
 * Small single-user durable event inbox.
 *
 * The whole snapshot is replaced atomically after each mutation. Lifecycle
 * traffic is tiny (state transitions, never deltas or file bodies), so this is
 * intentionally dependency-free and works on the Node 22 Alpine deployment.
 */
export class LifecycleEventStore {
  private loaded = false;
  private nextSequence = 1;
  private readonly events = new Map<string, StoredLifecycleEvent>();
  private operation: Promise<unknown> = Promise.resolve();

  constructor(
    private readonly path: string,
    private readonly maxEvents = 10_000,
    private readonly log: GatewayLogger = silentGatewayLogger,
  ) {}

  ingest(event: SessionLifecycleEvent, now = new Date()): Promise<StoredLifecycleEvent> {
    return this.exclusive(async () => {
      await this.loadIfNeeded();
      const existing = this.events.get(event.eventId);
      if (existing) {
        if (JSON.stringify(existing.event) !== JSON.stringify(event)) throw new Error("event_id_conflict");
        return existing;
      }
      const record: StoredLifecycleEvent = {
        sequence: this.nextSequence++,
        event,
        receivedAt: now.toISOString(),
      };
      this.events.set(event.eventId, record);
      this.trim();
      await this.persist();
      return record;
    });
  }

  list(after = 0, limit = 100): Promise<LifecycleEventPage> {
    return this.exclusive(async () => {
      await this.loadIfNeeded();
      const boundedLimit = Math.max(1, Math.min(Math.trunc(limit), 500));
      const candidates = [...this.events.values()]
        .filter((record) => record.sequence > after)
        .sort((a, b) => a.sequence - b.sequence);
      const events = candidates.slice(0, boundedLimit);
      const hasMore = candidates.length > events.length;
      // A served page is the moment the phone learned of these events; the gap between a
      // record's receivedAt and this line is how long the phone was not asking.
      if (events.length > 0) {
        this.log.info("lifecycle.served", {
          after,
          count: events.length,
          firstSequence: events[0]?.sequence,
          lastSequence: events.at(-1)?.sequence,
          hasMore,
        });
      } else {
        this.log.debug("lifecycle.served", { after, count: 0 });
      }
      return {
        events,
        nextCursor: events.at(-1)?.sequence ?? Math.max(0, after),
        hasMore,
      };
    });
  }

  markDelivered(eventIds: string[], now = new Date()): Promise<number> {
    return this.mark(eventIds, "deliveredAt", now);
  }

  markRead(eventIds: string[], now = new Date()): Promise<number> {
    return this.mark(eventIds, "readAt", now);
  }

  private mark(
    eventIds: string[],
    field: "deliveredAt" | "readAt",
    now: Date,
  ): Promise<number> {
    return this.exclusive(async () => {
      await this.loadIfNeeded();
      const timestamp = now.toISOString();
      let changed = 0;
      for (const eventId of new Set(eventIds)) {
        const record = this.events.get(eventId);
        if (!record || record[field]) continue;
        record[field] = timestamp;
        changed += 1;
      }
      if (changed > 0) await this.persist();
      this.log.info("lifecycle.acked", { field, requested: new Set(eventIds).size, changed });
      return changed;
    });
  }

  private async loadIfNeeded(): Promise<void> {
    if (this.loaded) return;
    this.loaded = true;
    try {
      const parsed = parseStoreFile(JSON.parse(await readFile(this.path, "utf8")));
      this.nextSequence = parsed.nextSequence;
      for (const record of parsed.events) this.events.set(record.event.eventId, record);
      this.trim();
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
      this.loaded = false;
      throw error;
    }
  }

  private trim(): void {
    if (this.events.size <= this.maxEvents) return;
    const ordered = [...this.events.values()].sort((a, b) => a.sequence - b.sequence);
    for (const record of ordered.slice(0, this.events.size - this.maxEvents)) {
      this.events.delete(record.event.eventId);
    }
  }

  private async persist(): Promise<void> {
    const directory = dirname(this.path);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const temporary = `${this.path}.tmp-${process.pid}-${randomUUID()}`;
    const handle = await open(temporary, "wx", 0o600);
    try {
      const file: StoreFile = {
        version: 1,
        nextSequence: this.nextSequence,
        events: [...this.events.values()].sort((a, b) => a.sequence - b.sequence),
      };
      await handle.writeFile(`${JSON.stringify(file)}\n`, "utf8");
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

  private exclusive<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.operation.catch(() => undefined).then(operation);
    this.operation = result;
    return result;
  }
}

function parseStoreFile(value: unknown): StoreFile {
  if (!isRecord(value) || value.version !== 1 || !Number.isSafeInteger(value.nextSequence)
      || (value.nextSequence as number) < 1 || !Array.isArray(value.events)) {
    throw new Error("invalid_lifecycle_event_store");
  }
  const events = value.events.map((item) => {
    if (!isRecord(item) || !Number.isSafeInteger(item.sequence) || (item.sequence as number) < 1
        || typeof item.receivedAt !== "string" || !isRecord(item.event)) {
      throw new Error("invalid_lifecycle_event_store");
    }
    const event = parseWireMessage(JSON.stringify(item.event));
    if (event.type !== "session.lifecycle") throw new Error("invalid_lifecycle_event_store");
    return {
      sequence: item.sequence as number,
      event,
      receivedAt: validTimestamp(item.receivedAt),
      ...(item.deliveredAt === undefined ? {} : { deliveredAt: validTimestamp(item.deliveredAt) }),
      ...(item.readAt === undefined ? {} : { readAt: validTimestamp(item.readAt) }),
    };
  });
  const eventIds = new Set(events.map((record) => record.event.eventId));
  const sequences = new Set(events.map((record) => record.sequence));
  const highestSequence = events.reduce((highest, record) => Math.max(highest, record.sequence), 0);
  if (eventIds.size !== events.length || sequences.size !== events.length
      || (value.nextSequence as number) <= highestSequence) {
    throw new Error("invalid_lifecycle_event_store");
  }
  return { version: 1, nextSequence: value.nextSequence as number, events };
}

function validTimestamp(value: unknown): string {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) {
    throw new Error("invalid_lifecycle_event_store");
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
