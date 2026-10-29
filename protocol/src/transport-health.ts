/** Negotiated through HTTP headers: old strict v2 JSON parsers never see new fields. */
export const TRANSPORT_HEADER = "x-hermes-transport";
export interface TransportOffer {
  version: 1;
  role: "business" | "control";
  instanceId: string;
  generation: number;
}
export function parseTransportOffer(value: unknown): TransportOffer | undefined {
  if (typeof value !== "string" || value.length > 100) return undefined;
  const match = /^1;(business|control);([a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12});([1-9][0-9]{0,9})$/.exec(value);
  if (!match || Number(match[3]) > 2_147_483_647) return undefined;
  return { version: 1, role: match[1] as TransportOffer["role"], instanceId: match[2]!, generation: Number(match[3]) };
}
export function transportOfferHeader(value: TransportOffer): string {
  return `1;${value.role};${value.instanceId};${value.generation}`;
}
export type LinkState = "normal" | "suspect" | "recovering" | "lost";
export type LocalHealthState = "healthy" | "degraded" | "unhealthy" | "unknown";
export type LocalHealthReason = "ok" | "timeout" | "auth" | "refused" | "invalid" | "unavailable";
export interface HealthObservation {
  state: LocalHealthState;
  reason: LocalHealthReason;
  ageMs: number;
  sequence: number;
}

/** One outstanding nonce per physical socket. All time arguments are MONOTONIC milliseconds. */
export class LinkHealth {
  state: LinkState = "normal";
  private lastRoundTrip: number;
  private sequence = 0;
  private good = 0;
  private slow = 0;
  private baseline?: number;
  private pending?: { id: string; queued: number; written?: number };
  rttMs?: number;
  constructor(now: number) { this.lastRoundTrip = now; }
  probe(now: number): string | undefined {
    if (this.pending || this.state === "lost") return undefined;
    const id = String(++this.sequence);
    this.pending = { id, queued: now };
    return id;
  }
  written(id: string, now: number): void {
    if (this.pending?.id === id) this.pending.written = now;
  }
  pong(id: string, now: number): boolean {
    if (this.tick(now) === "lost" || !this.pending || this.pending.id !== id) return false;
    const elapsed = now - this.pending.queued;
    this.pending = undefined;
    this.lastRoundTrip = now;
    this.rttMs = elapsed;
    this.baseline = Math.min(this.baseline ?? elapsed, elapsed);
    const healthy = elapsed < 2_000 && elapsed <= Math.max(1_000, this.baseline * 3);
    this.slow = healthy ? 0 : this.slow + 1;
    if (this.slow >= 3 || elapsed >= 2_000) this.degrade();
    if (this.state !== "normal") {
      this.good = healthy ? this.good + 1 : 0;
      this.state = this.good >= 3 ? "normal" : healthy ? "recovering" : "suspect";
    }
    return true;
  }
  tick(now: number): LinkState {
    if (now - this.lastRoundTrip >= 45_000) this.state = "lost";
    else if (this.pending && (now - this.pending.queued >= 15_000
      || (this.pending.written === undefined && now - this.pending.queued >= 2_000))) this.degrade();
    return this.state;
  }
  diagnostics(now: number) {
    const pendingAgeMs = this.pending ? Math.max(0, now - this.pending.queued) : 0;
    return { rttMs: this.rttMs, pendingAgeMs,
      lastRoundTripAgeMs: Math.max(0, now - this.lastRoundTrip),
      cause: this.state === "lost" ? "round_trip_deadline"
        : this.pending?.written === undefined && this.pending && pendingAgeMs >= 2_000 ? "write_queue"
        : pendingAgeMs >= 15_000 ? "round_trip_missing"
        : this.state === "suspect" ? "round_trip_slow" : "round_trip_valid" };
  }
  private degrade(): void { this.state = "suspect"; this.good = 0; }
}

export class LocalHealth {
  private state: LocalHealthState = "unknown";
  private reason: LocalHealthReason = "unavailable";
  private observedAt?: number;
  private firstFailure?: number;
  private failures = 0;
  private sequence = 0;
  observe(reachable: boolean, reason: LocalHealthReason, now: number): void {
    this.observedAt = now; this.sequence++; this.reason = reason;
    if (reachable) {
      this.state = "healthy"; this.failures = 0; this.firstFailure = undefined;
    } else {
      this.firstFailure ??= now; this.failures++;
      this.state = reason === "auth" || reason === "refused"
        || (this.failures >= 3 && now - this.firstFailure >= 30_000) ? "unhealthy" : "degraded";
    }
  }
  snapshot(now: number): HealthObservation {
    const ageMs = this.observedAt === undefined ? 60_000 : Math.max(0, now - this.observedAt);
    return { state: ageMs >= 60_000 ? "unknown" : this.state, reason: this.reason, ageMs, sequence: this.sequence };
  }
}

/** Driver shared by both ends; a generation owns the driver and must stop it on close. */
export class LinkHeartbeat {
  readonly health: LinkHealth;
  private timer?: ReturnType<typeof setInterval>;
  private nextProbe = 0;
  private previous: LinkState = "normal";
  constructor(
    private readonly ping: (nonce: string, written: (error?: Error) => void) => void,
    private readonly changed: (state: LinkState, diagnostics: ReturnType<LinkHealth["diagnostics"]>) => void,
    private readonly lost: () => void,
    private readonly now = () => performance.now(),
  ) { this.health = new LinkHealth(now()); }
  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.sweep(), 250);
    this.sweep();
  }
  stop(): void { if (this.timer) clearInterval(this.timer); this.timer = undefined; }
  pong(nonce: string): void { if (this.timer) { this.health.pong(nonce, this.now()); this.publish(); } }
  private publish(): void {
    if (this.previous !== this.health.state) {
      this.previous = this.health.state; this.changed(this.previous, this.health.diagnostics(this.now()));
    }
  }
  private sweep(): void {
    const now = this.now();
    this.health.tick(now); this.publish();
    if (this.health.state === "lost") { this.stop(); this.lost(); return; }
    if (now < this.nextProbe) return;
    const nonce = this.health.probe(now);
    if (!nonce) return;
    this.nextProbe = now + 5_000;
    try { this.ping(nonce, (error) => { if (!error && this.timer) this.health.written(nonce, this.now()); }); }
    catch { /* A write failure is handled by the socket lifecycle or the bounded loss deadline. */ }
  }
}
