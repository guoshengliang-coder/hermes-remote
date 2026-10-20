import { WebSocket } from "ws";
import type { GatewayLogger } from "./gateway-log.js";

export type ControlConnectionMode = "legacy" | "account";

type HeartbeatSocket = Pick<WebSocket, "readyState" | "bufferedAmount" | "ping" | "terminate" | "on" | "once">;

interface TrackedControlSocket {
  mode: ControlConnectionMode;
  lastPongAt: number;
  pongObserved?: boolean;
  lastSweepAt: number;
  lastMessageAt?: number;
  lastPingReceivedAt?: number;
  ping?: { queuedAt: number; writtenAt?: number; state: "pending" | "written" | "failed" };
}

/** Gateway-owned liveness for Connector control sockets in the server-to-Connector direction. */
export class ControlHeartbeat {
  private readonly tracked = new Map<HeartbeatSocket, TrackedControlSocket>();
  private timer?: NodeJS.Timeout;

  constructor(
    private readonly intervalMs: number,
    private readonly timeoutMs: number,
    private readonly log: GatewayLogger,
    private readonly now: () => number = Date.now,
  ) {}

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => this.sweep(), this.intervalMs);
    this.timer.unref();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.tracked.clear();
  }

  track(socket: HeartbeatSocket, mode: ControlConnectionMode): void {
    const now = this.now();
    this.tracked.set(socket, { mode, lastPongAt: now, lastSweepAt: now });
    socket.on("pong", () => {
      const tracked = this.tracked.get(socket);
      if (tracked) {
        tracked.lastPongAt = this.now();
        tracked.pongObserved = true;
      }
    });
    socket.once("close", () => this.tracked.delete(socket));
    // Observe metadata only. Neither payloads nor peer-provided error text enter diagnostics.
    socket.on("message", () => {
      const tracked = this.tracked.get(socket);
      if (tracked) tracked.lastMessageAt = this.now();
    });
    socket.on("ping", () => {
      const tracked = this.tracked.get(socket);
      if (tracked) tracked.lastPingReceivedAt = this.now();
    });
  }

  /** Public for deterministic fake-clock tests; production calls it from the interval above. */
  sweep(): void {
    const now = this.now();
    for (const [socket, tracked] of this.tracked) {
      if (socket.readyState !== WebSocket.OPEN) continue;
      const sweepDelayMs = Math.max(0, now - tracked.lastSweepAt - this.intervalMs);
      tracked.lastSweepAt = now;
      const silentMs = now - tracked.lastPongAt;
      if (silentMs >= this.timeoutMs) {
        this.tracked.delete(socket);
        this.log.error("connector.heartbeat_timeout", {
          mode: tracked.mode,
          silentMs,
          timeoutMs: this.timeoutMs,
          lastPongAgoMs: tracked.pongObserved ? silentMs : undefined,
          bufferedBytes: socket.bufferedAmount,
          sweepDelayMs,
          lastMessageAgoMs: tracked.lastMessageAt === undefined ? undefined : Math.max(0, now - tracked.lastMessageAt),
          lastPingReceivedAgoMs: tracked.lastPingReceivedAt === undefined ? undefined : Math.max(0, now - tracked.lastPingReceivedAt),
          lastPingQueuedAgoMs: tracked.ping === undefined ? undefined : Math.max(0, now - tracked.ping.queuedAt),
          lastPingWrittenAgoMs: tracked.ping?.writtenAt === undefined ? undefined : Math.max(0, now - tracked.ping.writtenAt),
          pingWriteState: tracked.ping?.state ?? "none",
        });
        socket.terminate();
        continue;
      }
      const ping: NonNullable<TrackedControlSocket["ping"]> = { queuedAt: now, state: "pending" };
      tracked.ping = ping;
      socket.ping(undefined, undefined, (error?: Error) => {
        if (this.tracked.get(socket) !== tracked || tracked.ping !== ping) return;
        ping.state = error ? "failed" : "written";
        if (!error) ping.writtenAt = this.now();
      });
    }
  }
}
