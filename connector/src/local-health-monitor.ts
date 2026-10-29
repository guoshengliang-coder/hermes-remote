import { LocalHealth, type HealthObservation } from "@hermes-remote/protocol";
import { CONNECTOR_HEALTH_PROBE_TIMEOUT_MS, type HermesHealth } from "./connector-health.js";

/** Process-owned, single-flight probe. Losing any remote socket never cancels this monitor. */
export class LocalHealthMonitor {
  private readonly health = new LocalHealth();
  private timer?: NodeJS.Timeout;
  private active?: AbortController;
  private stopped = false;
  private running?: Promise<void>;
  version?: string;
  constructor(private readonly probe: (signal: AbortSignal) => Promise<HermesHealth>,
    private readonly now = () => performance.now(), private readonly timeoutMs = CONNECTOR_HEALTH_PROBE_TIMEOUT_MS) {}
  snapshot(): HealthObservation {
    const health = this.health.snapshot(this.now());
    return { ...health, ageMs: Math.min(2_147_483_647, Math.floor(health.ageMs)) };
  }
  start(): Promise<void> {
    if (this.running) return this.running;
    if (this.stopped) return Promise.resolve();
    if (this.timer) { clearTimeout(this.timer); this.timer = undefined; }
    this.running = this.run().finally(() => { this.running = undefined; });
    return this.running;
  }
  stop(): void { this.stopped = true; if (this.timer) clearTimeout(this.timer); this.active?.abort(); }
  private async run(): Promise<void> {
    const controller = new AbortController(); this.active = controller;
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let abort!: () => void;
    const timeout = new Promise<HermesHealth>((resolve) => {
      abort = () => resolve({ reachable: false, reason: "timeout" });
      controller.signal.addEventListener("abort", abort, { once: true });
    });
    try {
      const result = await Promise.race([this.probe(controller.signal).catch((): HermesHealth =>
        ({ reachable: false, reason: "unavailable" })), timeout]);
      if (this.stopped) return;
      this.health.observe(result.reachable, result.reason ?? (result.reachable ? "ok" : "unavailable"), this.now());
      if (result.reachable) this.version = result.version;
    } finally {
      clearTimeout(timer); controller.signal.removeEventListener("abort", abort); this.active = undefined;
      if (!this.stopped) {
        this.timer = setTimeout(() => { void this.start(); }, this.snapshot().state === "healthy" ? 20_000 : 5_000);
        this.timer.unref();
      }
    }
  }
}
