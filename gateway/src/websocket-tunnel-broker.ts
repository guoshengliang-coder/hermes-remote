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
  openedAt: number;
  // Relayed frames are opaque here; counting them is what tells an incident reader whether the
  // app socket was still attached and flowing when a run ended on the Mac.
  framesToApp: number;
  bytesToApp: number;
  framesFromApp: number;
  bytesFromApp: number;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;
type ResolveConnector<TConnector extends WebSocketConnector> = (
  routingKey: string,
) => TConnector | undefined;
type RevalidateConnector<TConnector extends WebSocketConnector> = () => Promise<TConnector>;

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
  ): void {
    const revalidationTimer = revalidateConnector
      ? setInterval(() => {
          void revalidateConnector().then((current) => {
            if (current !== connector) socket.close(4403, "account binding changed");
          }).catch(() => socket.close(4403, "account authorization changed"));
        }, 5_000)
      : undefined;
    revalidationTimer?.unref();

    const id = randomUUID();
    this.tunnels.set(id, {
      socket,
      routingKey: connector.routingKey,
      connector,
      openedAt: Date.now(),
      framesToApp: 0,
      bytesToApp: 0,
      framesFromApp: 0,
      bytesFromApp: 0,
    });
    this.log.info("app.tunnel.open", {
      tunnel: id,
      device: connector.deviceId,
      routingKey: connector.routingKey,
      tunnels: this.tunnels.size,
    });
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
      const tunnel = this.tunnels.get(id);
      if (tunnel) {
        tunnel.framesFromApp += 1;
        tunnel.bytesFromApp += buffer.length;
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
      closed += 1;
      tunnel.socket.close(1013, "Mac connector disconnected");
    }
    if (closed > 0) this.log.info("app.tunnel.fail_routing", { routingKey, closed });
  }
}
