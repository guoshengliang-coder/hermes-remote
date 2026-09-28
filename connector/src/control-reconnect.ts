/** A successful WebSocket upgrade alone is not evidence that the relay recovered. */
const STABLE_READY_MS = 30_000;
const INITIAL_UPPER_DELAY_MS = 2_000;
const MAX_UPPER_DELAY_MS = 30_000;

export interface ReconnectDecision {
  attempt: number;
  delayMs: number;
  stable: boolean;
  readyForMs: number;
  lastPongAgoMs?: number;
}

/** One serial Connector connection; elapsed times use the monotonic clock. */
export class ControlReconnectBackoff {
  private upperDelayMs = INITIAL_UPPER_DELAY_MS;
  private attempt = 0;
  private readyAt?: number;
  private lastPongAt?: number;

  constructor(
    private readonly heartbeatIntervalMs: number,
    private readonly now: () => number = () => performance.now(),
    private readonly random: () => number = Math.random,
  ) {}

  beginAttempt(): void {
    this.readyAt = undefined;
    this.lastPongAt = undefined;
  }

  markReady(): void {
    this.readyAt ??= this.now();
  }

  markPong(): void {
    if (this.readyAt !== undefined) this.lastPongAt = this.now();
  }

  nextDelay(): ReconnectDecision {
    const now = this.now();
    const readyForMs = this.readyAt === undefined ? 0 : Math.max(0, now - this.readyAt);
    const lastPongAgoMs = this.lastPongAt === undefined
      ? undefined : Math.max(0, now - this.lastPongAt);
    const stable = this.readyAt !== undefined && readyForMs >= STABLE_READY_MS
      && lastPongAgoMs !== undefined && lastPongAgoMs <= this.heartbeatIntervalMs;
    if (stable) {
      this.upperDelayMs = INITIAL_UPPER_DELAY_MS;
      this.attempt = 0;
    }
    // Equal jitter keeps a lower bound while spreading retries even at the 30-second cap.
    const lowerDelayMs = this.upperDelayMs / 2;
    const delayMs = Math.floor(lowerDelayMs + this.random() * lowerDelayMs);
    this.upperDelayMs = Math.min(this.upperDelayMs * 2, MAX_UPPER_DELAY_MS);
    return { attempt: ++this.attempt, delayMs, stable, readyForMs, lastPongAgoMs };
  }
}
