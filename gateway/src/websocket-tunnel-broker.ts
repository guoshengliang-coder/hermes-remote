import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";
import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
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
      this.send(current.socket, {
        type: "tunnel.ws.frame",
        version: PROTOCOL_VERSION,
        id,
        dataBase64: rawDataToBuffer(data).toString("base64"),
        binary: isBinary,
      });
    });

    socket.on("close", (code, reason) => {
      if (revalidationTimer) clearInterval(revalidationTimer);
      this.tunnels.delete(id);
      const current = this.resolveConnector(connector.routingKey);
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
        if (tunnel.socket.bufferedAmount + data.length > this.maxSocketBufferedBytes) {
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
      tunnel.socket.close(safeCloseCode(message.code), message.reason?.slice(0, 120));
      return true;
    }

    return false;
  }

  failRouting(routingKey: string): void {
    for (const [id, tunnel] of this.tunnels) {
      if (tunnel.routingKey !== routingKey) continue;
      this.tunnels.delete(id);
      tunnel.socket.close(1013, "Mac connector disconnected");
    }
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

  private closeAccountTunnels(
    matches: (access: NonNullable<AppTunnel<TConnector>["accountAccess"]>) => boolean,
    reason: string,
  ): void {
    for (const [id, tunnel] of this.tunnels) {
      if (!tunnel.accountAccess || !matches(tunnel.accountAccess)) continue;
      this.tunnels.delete(id);
      tunnel.socket.close(4403, reason);
    }
  }
}
