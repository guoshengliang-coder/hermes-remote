import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
import {
  LifecycleOutbox,
  type ObserverStatePersistence,
  SessionLifecycleTracker,
  nextPollDelayMs,
  parseActiveList,
} from "./session-observer.js";

export interface ObserverSocket {
  readonly readyState: number;
  on(event: "open", listener: () => void): this;
  on(event: "message", listener: (data: unknown) => void): this;
  on(event: "close", listener: () => void): this;
  on(event: "error", listener: (error: Error) => void): this;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface SessionObserverOptions {
  deviceId: string;
  profile?: string;
  stateStore: ObserverStatePersistence;
  websocketUrl: () => Promise<string>;
  createSocket: (url: string) => ObserverSocket;
  sendLifecycle: (event: SessionLifecycleEvent) => boolean;
  activePollMs?: number;
  idlePollMs?: number;
  rpcTimeoutMs?: number;
  reconnectMs?: number;
  unsupportedRetryMs?: number;
  log?: (message: string) => void;
}

const SOCKET_OPEN = 1;

/**
 * Read-only observer for Hermes' official `session.active_list` RPC.
 *
 * It never calls session.resume/session.activate and therefore cannot steal a
 * Desktop or TUI session's transport. Lifecycle events are persisted before
 * they are offered to the Relay; only a durable Relay acknowledgement removes
 * an event from the outbox.
 */
export class HermesSessionObserver {
  private tracker: SessionLifecycleTracker;
  private outbox = new LifecycleOutbox();
  private socket?: ObserverSocket;
  private pollTimer?: NodeJS.Timeout;
  private rpcTimer?: NodeJS.Timeout;
  private reconnectTimer?: NodeJS.Timeout;
  private requestSequence = 0;
  private pendingRequestId?: string;
  private stopped = false;
  private reconnectAfterCloseMs?: number;
  private sentOnConnection = new Set<string>();
  private saveChain: Promise<void> = Promise.resolve();

  constructor(private readonly options: SessionObserverOptions) {
    this.tracker = new SessionLifecycleTracker(options.deviceId, options.profile);
  }

  async start(): Promise<void> {
    let state;
    try {
      state = await this.options.stateStore.load();
    } catch (error) {
      // Observation is an enhancement, never a prerequisite for chat. A partial/corrupt state
      // file must not disable the Connector; the next successful snapshot replaces it atomically.
      this.options.log?.(`Lifecycle observer state was ignored: ${safeError(error)}`);
    }
    if (state) {
      this.tracker = new SessionLifecycleTracker(
        this.options.deviceId,
        this.options.profile,
        state.tracker,
      );
      this.outbox = new LifecycleOutbox(state.outbox);
    }
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.clearTimers();
    this.socket?.close(1000, "connector stopping");
    this.socket = undefined;
  }

  /** Called when the Relay control connection is authenticated again. */
  relayConnected(): void {
    this.sentOnConnection.clear();
    this.flushPending();
  }

  acknowledge(eventId: string): void {
    if (!this.outbox.acknowledge(eventId)) return;
    this.sentOnConnection.delete(eventId);
    // Relay already committed the event before acknowledging it. Keep the in-memory removal even
    // if this cleanup write fails; a restart may replay the old outbox entry and Relay dedupes it.
    void this.persist().catch(() => undefined);
  }

  pendingCount(): number {
    return this.outbox.pending().length;
  }

  private connect(): void {
    if (this.stopped) return;
    void this.options.websocketUrl().then((url) => {
      if (this.stopped) return;
      const socket = this.options.createSocket(url);
      this.socket = socket;
      socket.on("open", () => this.options.log?.("Hermes lifecycle observer connected"));
      socket.on("message", (data) => void this.handleMessage(data));
      socket.on("error", (error) => this.options.log?.(`Hermes lifecycle observer error: ${error.message}`));
      socket.on("close", () => {
        if (this.socket === socket) this.socket = undefined;
        this.clearPollTimers();
        const delay = this.reconnectAfterCloseMs ?? this.options.reconnectMs ?? 5_000;
        this.reconnectAfterCloseMs = undefined;
        this.scheduleReconnect(delay);
      });
    }).catch((error) => {
      this.options.log?.(`Hermes lifecycle observer authentication failed: ${safeError(error)}`);
      this.scheduleReconnect(this.options.reconnectMs ?? 5_000);
    });
  }

  private async handleMessage(data: unknown): Promise<void> {
    let frame: unknown;
    try {
      frame = JSON.parse(rawText(data));
    } catch {
      return;
    }
    if (!isRecord(frame)) return;
    if (frame.method === "event") {
      const params = frame.params;
      if (isRecord(params) && params.type === "gateway.ready") this.schedulePoll(0);
      return;
    }
    if (typeof frame.id !== "string" || frame.id !== this.pendingRequestId) return;
    this.pendingRequestId = undefined;
    if (this.rpcTimer) clearTimeout(this.rpcTimer);
    this.rpcTimer = undefined;

    if (frame.error !== undefined) {
      const message = isRecord(frame.error) ? String(frame.error.message ?? "") : String(frame.error);
      if (/unknown|not found|unsupported|method/i.test(message)) {
        this.options.log?.("Hermes does not support session.active_list; observer will retry later");
        const delay = this.options.unsupportedRetryMs ?? 15 * 60_000;
        if (this.socket) {
          this.reconnectAfterCloseMs = delay;
          this.socket.close(1000, "active_list unsupported");
        } else {
          this.scheduleReconnect(delay);
        }
      } else {
        this.options.log?.(`Hermes session.active_list failed: ${message || "unknown error"}`);
        this.schedulePoll(this.options.idlePollMs ?? 20_000);
      }
      return;
    }

    try {
      const snapshot = parseActiveList(frame.result);
      const events = this.tracker.reduce(snapshot);
      if (events.length > 0) this.outbox.add(events);
      await this.persist();
      this.flushPending();
      this.schedulePoll(nextPollDelayMs(
        snapshot,
        this.options.activePollMs ?? 2_000,
        this.options.idlePollMs ?? 20_000,
      ));
    } catch (error) {
      this.options.log?.(`Hermes session.active_list response rejected: ${safeError(error)}`);
      this.schedulePoll(this.options.idlePollMs ?? 20_000);
    }
  }

  private schedulePoll(delayMs: number): void {
    if (this.stopped) return;
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.pollTimer = setTimeout(() => this.poll(), delayMs);
  }

  private poll(): void {
    this.pollTimer = undefined;
    const socket = this.socket;
    if (!socket || socket.readyState !== SOCKET_OPEN || this.pendingRequestId) return;
    const id = `observer-${++this.requestSequence}`;
    this.pendingRequestId = id;
    socket.send(JSON.stringify({ jsonrpc: "2.0", id, method: "session.active_list", params: {} }));
    this.rpcTimer = setTimeout(() => {
      if (this.pendingRequestId !== id) return;
      this.pendingRequestId = undefined;
      socket.close(1011, "active_list timeout");
    }, this.options.rpcTimeoutMs ?? 10_000);
  }

  private flushPending(): void {
    for (const event of this.outbox.pending()) {
      if (this.sentOnConnection.has(event.eventId)) continue;
      if (!this.options.sendLifecycle(event)) return;
      this.sentOnConnection.add(event.eventId);
    }
  }

  private persist(): Promise<void> {
    const snapshot = {
      version: 1 as const,
      tracker: this.tracker.exportState(),
      outbox: this.outbox.pending(),
    };
    const operation = this.saveChain
      .catch(() => undefined)
      .then(() => this.options.stateStore.save(snapshot));
    // Preserve a live serialization chain after a failed write, while returning the rejecting
    // operation to callers that must not forward an event until this exact snapshot is durable.
    this.saveChain = operation.catch((error) => {
      this.options.log?.(`Unable to persist lifecycle observer state: ${safeError(error)}`);
    });
    return operation;
  }

  private scheduleReconnect(delayMs: number): void {
    if (this.stopped || this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delayMs);
  }

  private clearPollTimers(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    if (this.rpcTimer) clearTimeout(this.rpcTimer);
    this.pollTimer = undefined;
    this.rpcTimer = undefined;
    this.pendingRequestId = undefined;
  }

  private clearTimers(): void {
    this.clearPollTimers();
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
  }
}

function rawText(data: unknown): string {
  if (typeof data === "string") return data;
  if (Buffer.isBuffer(data)) return data.toString("utf8");
  return String(data);
}

function safeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
