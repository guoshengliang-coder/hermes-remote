import { setTimeout as delay } from "node:timers/promises";
import type { LinkState } from "@hermes-remote/protocol";

/** Process-wide budget shared by independent file HTTP and bulk tunnel producers. */
export class TransferBudget {
  private readonly states = new Map<string, LinkState>();
  private nextSend = 0;
  private factor = 1;
  private updated = 0;
  private suspectSince?: number;
  enabled = false;
  constructor(readonly bytesPerSecond: number, private readonly now = () => performance.now()) {}
  reset(): void {
    this.states.clear(); this.enabled = false; this.factor = 1; this.nextSend = 0;
    this.suspectSince = undefined; this.updated = this.now();
  }
  state(lane: string, state: LinkState): void {
    this.enabled = true;
    this.states.set(lane, state);
    if (this.congested) this.factor = Math.min(this.factor, 0.5);
    if (lane === "business") {
      if (state === "suspect") this.suspectSince ??= this.now();
      else this.suspectSince = undefined;
    }
    this.updated = this.now();
  }
  get congested(): boolean { return [...this.states.values()].some((state) => state !== "normal"); }
  get concurrency(): number { return this.congested ? 1 : 4; }
  get paused(): boolean {
    // A failed control lane alone is not evidence that the file/business path is unusable.
    return this.states.get("business") === "suspect" && this.suspectSince !== undefined
      && this.now() - this.suspectSince >= 10_000;
  }
  rate(): number {
    const now = this.now();
    if (!this.congested) this.factor = Math.min(1, this.factor + Math.max(0, now - this.updated) / 60_000);
    this.updated = now;
    return this.bytesPerSecond * this.factor;
  }
  async take(bytes: number, signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    // Wait at producer; never build an unbounded queue of file bytes in the socket.
    while (this.paused) await delay(250, undefined, { signal });
    const now = this.now();
    const start = Math.max(this.nextSend, now);
    this.nextSend = start + bytes / this.rate() * 1000;
    let remaining = this.nextSend - now;
    while (remaining > 0) {
      const step = Math.min(remaining, 250);
      await delay(step, undefined, { signal }); remaining -= step;
      while (this.paused) await delay(250, undefined, { signal });
    }
  }
}
