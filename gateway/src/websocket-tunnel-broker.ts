import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";
import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
import { silentGatewayLogger, type GatewayLogger } from "./gateway-log.js";
import { rawDataToBuffer, safeCloseCode } from "./websocket-utils.js";

interface WebSocketConnector {
  socket: WebSocket;
  deviceId: string;
  routingKey: string;
}

interface AppTunnel<TConnector extends WebSocketConnector> {
  socket: WebSocket;
  routingKey: string;
  connector: TConnector;
  accountAccess?: {
    accountId: string;
    bindingId: string;
    installationId: string;
    sessionId: string;
  };
  openedAt: number;
  // Relayed frames are opaque here; counting them is what tells an incident reader whether the
  // app socket was still attached and flowing when a run ended on the Mac.
  framesToApp: number;
  bytesToApp: number;
  framesFromApp: number;
  bytesFromApp: number;
  pendingForegroundRpcs: Map<number, { method: string; startedAt: number }>;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;
type ResolveConnector<TConnector extends WebSocketConnector> = (
  routingKey: string,
) => TConnector | undefined;
type RevalidateConnector<TConnector extends WebSocketConnector> = () => Promise<TConnector>;
/** Decides per app frame whether it may reach Hermes (browser tunnels only). */
export type ScreenAppFrame = (data: Buffer, isBinary: boolean) => {
  forward: boolean;
  replies: string[];
  violation?: string;
};

/** Only metadata for the two foreground RPCs whose missing replies strand a spinner. */
export function trackedForegroundRpcRequest(data: Buffer, isBinary: boolean): { id: number; method: string } | null {
  if (isBinary || data.length > 4096) return null;
  try {
    const frame: unknown = JSON.parse(data.toString("utf8"));
    if (typeof frame !== "object" || frame === null || Array.isArray(frame)) return null;
    const request = frame as Record<string, unknown>;
    if (request.method !== "session.create" && request.method !== "slash.exec") return null;
    if (typeof request.id !== "number" || !Number.isSafeInteger(request.id)) return null;
    return { id: request.id, method: request.method };
  } catch {
    return null;
  }
}

function trackedForegroundRpcResponse(data: Buffer, isBinary: boolean): { id: number; ok: boolean } | null {
  if (isBinary || data.length > 1024 * 1024) return null;
  try {
    const frame: unknown = JSON.parse(data.toString("utf8"));
    if (typeof frame !== "object" || frame === null || Array.isArray(frame)) return null;
    const response = frame as Record<string, unknown>;
    if (typeof response.id !== "number" || !Number.isSafeInteger(response.id)) return null;
    if (!("result" in response) && !("error" in response)) return null;
    return { id: response.id, ok: !("error" in response) };
  } catch {
    return null;
  }
}

export class WebSocketTunnelBroker<TConnector extends WebSocketConnector> {
  private readonly tunnels = new Map<string, AppTunnel<TConnector>>();

  constructor(
    private readonly maxTunnels: number,
    private readonly maxSocketBufferedBytes: number,
    private readonly send: SendWireMessage,
    private readonly resolveConnector: ResolveConnector<TConnector>,
    private readonly log: GatewayLogger = silentGatewayLogger,
  ) {}

  get atCapacity(): boolean {
    return this.tunnels.size >= this.maxTunnels;
  }

  open(
    socket: WebSocket,
    connector: TConnector,
    revalidateConnector?: RevalidateConnector<TConnector>,
    accountAccess?: {
      accountId: string;
      bindingId: string;
      installationId: string;
      sessionId: string;
    },
    screenAppFrame?: ScreenAppFrame,
    clientConnectionId?: string,
  ): void {
    const revalidate = revalidateConnector
      ? () => {
          void revalidateConnector().then((current) => {
            if (current !== connector) socket.close(4403, "account binding changed");
          }).catch(() => socket.close(4403, "account authorization changed"));
        }
      : undefined;
    const revalidationTimer = revalidate
      ? setInterval(() => {
          revalidate();
        }, 5_000)
      : undefined;
    revalidationTimer?.unref();

    const id = randomUUID();
    this.tunnels.set(id, {
      socket,
      routingKey: connector.routingKey,
      connector,
      ...(accountAccess ? { accountAccess } : {}),
      openedAt: Date.now(),
      framesToApp: 0,
      bytesToApp: 0,
      framesFromApp: 0,
      bytesFromApp: 0,
      pendingForegroundRpcs: new Map(),
    });
    this.log.info("app.tunnel.open", {
      tunnel: id,
      clientConnectionId: clientConnectionId && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(clientConnectionId)
        ? clientConnectionId : undefined,
      device: connector.deviceId,
      routingKey: connector.routingKey,
      tunnels: this.tunnels.size,
    });
    revalidate?.();
    this.send(connector.socket, {
      type: "tunnel.ws.open",
      version: PROTOCOL_VERSION,
      id,
      targetDeviceId: connector.deviceId,
      path: "/api/ws",
    });

    socket.on("message", (data, isBinary) => {
      const current = this.resolveConnector(connector.routingKey);
      if (current !== connector) {
        socket.close(1013, "Mac connector offline");
        return;
      }
      const buffer = rawDataToBuffer(data);
      if (screenAppFrame) {
        const screen = screenAppFrame(buffer, isBinary);
        if (screen.violation) {
          socket.close(1008, screen.violation);
          return;
        }
        for (const reply of screen.replies) socket.send(reply);
        if (!screen.forward) return;
      }
      const tunnel = this.tunnels.get(id);
      if (tunnel) {
        tunnel.framesFromApp += 1;
        tunnel.bytesFromApp += buffer.length;
      }
      const rpc = trackedForegroundRpcRequest(buffer, isBinary);
      if (rpc) {
        tunnel?.pendingForegroundRpcs.set(rpc.id, { method: rpc.method, startedAt: Date.now() });
        this.log.info("app.rpc.received", { tunnel: id, rpcId: rpc.id, method: rpc.method });
      }
      this.send(current.socket, {
        type: "tunnel.ws.frame",
        version: PROTOCOL_VERSION,
        id,
        dataBase64: buffer.toString("base64"),
        binary: isBinary,
      });
    });

    socket.on("close", (code, reason) => {
      if (revalidationTimer) clearInterval(revalidationTimer);
      const tunnel = this.tunnels.get(id);
      this.tunnels.delete(id);
      if (tunnel) this.logUnansweredRpcs(id, tunnel, "app-close");
      const current = this.resolveConnector(connector.routingKey);
      this.log.info("app.tunnel.close", {
        tunnel: id,
        device: connector.deviceId,
        code,
        reason: reason.toString(),
        durationMs: tunnel ? Date.now() - tunnel.openedAt : undefined,
        framesToApp: tunnel?.framesToApp,
        bytesToApp: tunnel?.bytesToApp,
        framesFromApp: tunnel?.framesFromApp,
        bytesFromApp: tunnel?.bytesFromApp,
        unansweredForegroundRpcs: tunnel?.pendingForegroundRpcs.size,
        connectorOnline: current === connector,
        tunnels: this.tunnels.size,
      });
      if (current === connector) {
        this.send(current.socket, {
          type: "tunnel.ws.close",
          version: PROTOCOL_VERSION,
          id,
          code,
          reason: reason.toString(),
        });
      }
    });
  }

  handleConnectorMessage(connector: TConnector, message: WireMessage): boolean {
    if (message.type === "tunnel.ws.frame") {
      const tunnel = this.tunnels.get(message.id);
      if (!tunnel
          || tunnel.routingKey !== connector.routingKey
          || tunnel.connector !== connector) return true;
      if (tunnel.socket.readyState === WebSocket.OPEN) {
        const data = Buffer.from(message.dataBase64, "base64");
        tunnel.framesToApp += 1;
        tunnel.bytesToApp += data.length;
        const answered = tunnel.pendingForegroundRpcs.size
          ? trackedForegroundRpcResponse(data, message.binary) : null;
        const pending = answered ? tunnel.pendingForegroundRpcs.get(answered.id) : undefined;
        if (tunnel.socket.bufferedAmount + data.length > this.maxSocketBufferedBytes) {
          this.log.info("app.tunnel.backpressure", {
            tunnel: message.id,
            device: connector.deviceId,
            buffered: tunnel.socket.bufferedAmount,
          });
          tunnel.socket.close(1013, "backpressure limit reached");
        } else {
          tunnel.socket.send(message.binary ? data : data.toString("utf8"), {
            binary: message.binary,
          });
          if (answered && pending) {
            tunnel.pendingForegroundRpcs.delete(answered.id);
            this.log.info("app.rpc.returned", {
              tunnel: message.id, rpcId: answered.id, method: pending.method,
              ok: answered.ok, durationMs: Date.now() - pending.startedAt,
            });
          }
        }
      }
      return true;
    }

    if (message.type === "tunnel.ws.close") {
      const tunnel = this.tunnels.get(message.id);
      if (!tunnel
          || tunnel.routingKey !== connector.routingKey
          || tunnel.connector !== connector) return true;
      this.tunnels.delete(message.id);
      this.logUnansweredRpcs(message.id, tunnel, "connector-close");
      this.log.info("app.tunnel.close_by_connector", {
        tunnel: message.id,
        device: connector.deviceId,
        code: message.code,
        reason: message.reason,
        framesToApp: tunnel.framesToApp,
        durationMs: Date.now() - tunnel.openedAt,
      });
      tunnel.socket.close(safeCloseCode(message.code), message.reason?.slice(0, 120));
      return true;
    }

    return false;
  }

  failRouting(routingKey: string): void {
    let closed = 0;
    for (const [id, tunnel] of this.tunnels) {
      if (tunnel.routingKey !== routingKey) continue;
      this.tunnels.delete(id);
      this.logUnansweredRpcs(id, tunnel, "connector-offline");
      closed += 1;
      tunnel.socket.close(1013, "Mac connector disconnected");
    }
    if (closed > 0) this.log.info("app.tunnel.fail_routing", { routingKey, closed });
  }

  revokeAccountBinding(accountId: string, bindingId: string): void {
    this.closeAccountTunnels(
      ({ accountId: candidateAccountId, bindingId: candidateBindingId }) => (
        candidateAccountId === accountId && candidateBindingId === bindingId
      ),
      "device access revoked",
    );
  }

  revokeAccountInstallation(accountId: string, installationId: string): void {
    this.closeAccountTunnels(
      (access) => access.accountId === accountId && access.installationId === installationId,
      "installation access revoked",
    );
  }

  revokeAccountSession(accountId: string, sessionId: string): void {
    this.closeAccountTunnels(
      (access) => access.accountId === accountId && access.sessionId === sessionId,
      "session access revoked",
    );
  }

  revokeAccount(accountId: string): void {
    this.closeAccountTunnels(
      (access) => access.accountId === accountId,
      "account access revoked",
    );
  }

  private logUnansweredRpcs(id: string, tunnel: AppTunnel<TConnector>, reason: string): void {
    for (const [rpcId, pending] of [...tunnel.pendingForegroundRpcs].slice(0, 16)) {
      this.log.info("app.rpc.unanswered", {
        tunnel: id, rpcId, method: pending.method, reason,
        ageMs: Date.now() - pending.startedAt,
      });
    }
  }

  private closeAccountTunnels(
    matches: (access: NonNullable<AppTunnel<TConnector>["accountAccess"]>) => boolean,
    reason: string,
  ): void {
    for (const [id, tunnel] of this.tunnels) {
      if (!tunnel.accountAccess || !matches(tunnel.accountAccess)) continue;
      this.tunnels.delete(id);
      this.logUnansweredRpcs(id, tunnel, "access-revoked");
      tunnel.socket.close(4403, reason);
    }
  }
}
