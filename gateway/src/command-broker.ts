import type { WebSocket } from "ws";
import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";

interface CommandPeer {
  socket: WebSocket;
  routingKey: string;
}

interface LegacyConnector extends CommandPeer {
  deviceId: string;
}

interface RequestOwner<TPeer extends CommandPeer> {
  peer: TPeer;
  routingKey: string;
  timer?: NodeJS.Timeout;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;
type ResolveLegacyConnector<TConnector extends LegacyConnector> = (
  deviceId: string,
) => TConnector | undefined;

export class CommandBroker<TPeer extends CommandPeer, TConnector extends LegacyConnector> {
  private readonly owners = new Map<string, RequestOwner<TPeer>>();

  constructor(
    private readonly maxPendingRequests: number,
    private readonly requestTimeoutMs: number,
    private readonly send: SendWireMessage,
    private readonly resolveLegacyConnector: ResolveLegacyConnector<TConnector>,
  ) {}

  handleAppMessage(peer: TPeer, message: WireMessage): boolean {
    if (message.type !== "command") return false;
    const connector = this.resolveLegacyConnector(message.targetDeviceId);
    if (!connector) {
      this.send(peer.socket, errorMessage(
        "device_offline",
        "Target Mac is offline",
        message.id,
      ));
      return true;
    }
    if (this.owners.size >= this.maxPendingRequests) {
      this.send(peer.socket, errorMessage(
        "relay_capacity_reached",
        "Too many pending requests",
        message.id,
      ));
      return true;
    }
    if (this.owners.has(message.id)) {
      this.send(peer.socket, errorMessage(
        "duplicate_request_id",
        "Request ID is already pending",
        message.id,
      ));
      return true;
    }
    const owner = { peer, routingKey: connector.routingKey };
    this.owners.set(message.id, owner);
    this.armTimeout(message.id, owner);
    this.send(connector.socket, message);
    return true;
  }

  handleConnectorMessage(connector: TConnector, message: WireMessage): boolean {
    if (message.type !== "event") return false;
    const owner = this.owners.get(message.requestId);
    if (!owner || owner.routingKey !== connector.routingKey) return true;
    this.send(owner.peer.socket, message);
    if (message.event === "complete" || message.event === "error") {
      this.clear(message.requestId);
    } else {
      // This is an inactivity timeout, not a maximum generation duration. A long answer that is
      // still producing accepted/delta events must not be cut off at the fixed request deadline.
      this.armTimeout(message.requestId, owner);
    }
    return true;
  }

  unregisterApp(peer: TPeer): void {
    for (const [requestId, owner] of this.owners) {
      if (owner.peer === peer) this.clear(requestId);
    }
  }

  failRouting(routingKey: string): void {
    for (const [id, owner] of this.owners) {
      if (owner.routingKey !== routingKey) continue;
      this.send(owner.peer.socket, errorMessage(
        "connector_disconnected",
        "Mac connector disconnected",
        id,
      ));
      this.clear(id);
    }
  }

  private clear(id: string): void {
    const owner = this.owners.get(id);
    if (!owner) return;
    if (owner.timer) clearTimeout(owner.timer);
    this.owners.delete(id);
  }

  private armTimeout(id: string, owner: RequestOwner<TPeer>): void {
    if (owner.timer) clearTimeout(owner.timer);
    owner.timer = setTimeout(() => {
      if (this.owners.get(id) !== owner) return;
      this.owners.delete(id);
      this.send(owner.peer.socket, errorMessage(
        "connector_timeout",
        "Connector response timed out",
        id,
      ));
    }, this.requestTimeoutMs);
  }
}

function errorMessage(code: string, message: string, requestId?: string): WireMessage {
  return { type: "error", version: PROTOCOL_VERSION, code, message, requestId };
}
