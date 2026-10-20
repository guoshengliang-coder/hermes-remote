/** Metadata for one control connection. Observations never change heartbeat or retry policy. */
export class ControlHeartbeatDiagnostics {
  private lastTickAt?: number;
  private lastMessageAt?: number;
  private lastPingReceivedAt?: number;
  private lastPongAt?: number;
  private ping?: { queuedAt: number; writtenAt?: number; state: "pending" | "written" | "failed" };

  constructor(
    private readonly intervalMs: number,
    private readonly now: () => number = () => performance.now(),
  ) {}

  start(): void { this.lastTickAt = this.now(); }
  message(): void { this.lastMessageAt = this.now(); }
  receivedPing(): void { this.lastPingReceivedAt = this.now(); }
  pong(): void { this.lastPongAt = this.now(); }

  /** The callback reports a local write, not delivery or receipt of a remote pong. */
  queuedPing(): (error?: Error) => void {
    const ping: NonNullable<ControlHeartbeatDiagnostics["ping"]> = { queuedAt: this.now(), state: "pending" };
    this.ping = ping;
    return (error) => {
      if (this.ping !== ping) return;
      ping.state = error ? "failed" : "written";
      if (!error) ping.writtenAt = this.now();
    };
  }

  tick(bufferedBytes: number): Record<string, number | string | undefined> {
    const now = this.now();
    const age = (at?: number): number | undefined => at === undefined ? undefined : Math.max(0, Math.round(now - at));
    const fields = {
      bufferedBytes,
      tickDelayMs: this.lastTickAt === undefined ? 0 : Math.max(0, Math.round(now - this.lastTickAt - this.intervalMs)),
      lastMessageAgoMs: age(this.lastMessageAt),
      lastPingReceivedAgoMs: age(this.lastPingReceivedAt),
      lastPongAgoMs: age(this.lastPongAt),
      lastPingQueuedAgoMs: age(this.ping?.queuedAt),
      lastPingWrittenAgoMs: age(this.ping?.writtenAt),
      pingWriteState: this.ping?.state ?? "none",
    };
    this.lastTickAt = now;
    return fields;
  }
}
