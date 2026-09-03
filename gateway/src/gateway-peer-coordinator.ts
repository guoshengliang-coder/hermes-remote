import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
import type { WebSocket } from "ws";
import type { CommandBroker } from "./command-broker.js";
import type { ConnectorRegistry } from "./connector-registry.js";
import type { GatewayPeer } from "./gateway-peer.js";
import type { HttpTunnelBroker } from "./http-tunnel-broker.js";
import type { LifecycleMessageHandler } from "./lifecycle-message-handler.js";
import type { WebSocketTunnelBroker } from "./websocket-tunnel-broker.js";

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;

export class GatewayPeerCoordinator {
  private readonly apps = new Set<GatewayPeer>();

  constructor(
    private readonly connectorRegistry: ConnectorRegistry<GatewayPeer>,
    private readonly commands: CommandBroker<GatewayPeer, GatewayPeer>,
    private readonly httpTunnels: HttpTunnelBroker,
    private readonly webSocketTunnels: WebSocketTunnelBroker<GatewayPeer>,
    private readonly lifecycleMessages: LifecycleMessageHandler,
    private readonly send: SendWireMessage,
  ) {}

  route(peer: GatewayPeer, message: WireMessage): void {
    if (peer.role === "app") {
      if (this.commands.handleAppMessage(peer, message)) return;
      this.send(peer.socket, errorMessage("forbidden_message", "Message is not valid for this peer"));
      return;
    }

    if (this.lifecycleMessages.handle(peer, message)) return;
    if (this.commands.handleConnectorMessage(peer, message)) return;
    if (this.httpTunnels.handleConnectorMessage(peer, message)) return;
    if (this.webSocketTunnels.handleConnectorMessage(peer, message)) return;

    this.send(peer.socket, errorMessage("forbidden_message", "Message is not valid for this peer"));
  }

  registerLegacy(peer: GatewayPeer): void {
    if (peer.role === "connector") {
      this.connectorRegistry.getLegacy(peer.deviceId)?.socket.close(
        4409,
        "replaced by a new connection",
      );
      this.connectorRegistry.setLegacy(peer.deviceId, peer);
      this.broadcastStatus(peer.deviceId, true);
    } else {
      this.apps.add(peer);
    }
  }

  unregisterLegacy(peer: GatewayPeer): void {
    if (peer.role === "connector"
        && this.connectorRegistry.deleteLegacyIfCurrent(peer.deviceId, peer)) {
      this.broadcastStatus(peer.deviceId, false);
      this.failRouting(peer.routingKey);
    } else {
      this.apps.delete(peer);
    }
    this.commands.unregisterApp(peer);
  }

  registerAccount(peer: GatewayPeer): void {
    const bindingId = peer.binding?.id;
    if (!bindingId) throw new Error("account Connector has no binding");
    const previous = this.connectorRegistry.replaceAccount(bindingId, peer);
    if (previous && previous !== peer) {
      this.failRouting(previous.routingKey);
      previous.socket.close(4409, "replaced by a new connection");
    }
  }

  unregisterAccount(peer: GatewayPeer): boolean {
    const bindingId = peer.binding?.id;
    if (!bindingId || !this.connectorRegistry.deleteAccountIfCurrent(bindingId, peer)) return false;
    this.failRouting(peer.routingKey);
    return true;
  }

  private failRouting(routingKey: string): void {
    this.httpTunnels.failRouting(routingKey);
    this.webSocketTunnels.failRouting(routingKey);
    this.commands.failRouting(routingKey);
  }

  private broadcastStatus(deviceId: string, online: boolean): void {
    for (const app of this.apps) {
      this.send(app.socket, {
        type: "device_status",
        version: PROTOCOL_VERSION,
        deviceId,
        online,
      });
    }
  }
}

function errorMessage(code: string, message: string): WireMessage {
  return { type: "error", version: PROTOCOL_VERSION, code, message };
}
