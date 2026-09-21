import { WebSocket } from "ws";
import type { GatewayLogger } from "./gateway-log.js";

export type ControlConnectionMode = "legacy" | "account";

type HeartbeatSocket = Pick<WebSocket, "readyState" | "ping" | "terminate" | "on" | "once">;

interface TrackedControlSocket {
  mode: ControlConnectionMode;
  lastPongAt: number;
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
    this.tracked.set(socket, { mode, lastPongAt: this.now() });
    socket.on("pong", () => {
      const tracked = this.tracked.get(socket);
      if (tracked) tracked.lastPongAt = this.now();
    });
    socket.once("close", () => this.tracked.delete(socket));
  }

  /** Public for deterministic fake-clock tests; production calls it from the interval above. */
  sweep(): void {
    const now = this.now();
    for (const [socket, tracked] of this.tracked) {
      if (socket.readyState !== WebSocket.OPEN) continue;
      const silentMs = now - tracked.lastPongAt;
      if (silentMs >= this.timeoutMs) {
        this.tracked.delete(socket);
        this.log.error("connector.heartbeat_timeout", {
          mode: tracked.mode,
          silentMs,
          timeoutMs: this.timeoutMs,
        });
        socket.terminate();
        continue;
      }
      socket.ping();
    }
  }
}
