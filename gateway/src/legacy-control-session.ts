import { WebSocket } from "ws";
import {
  PROTOCOL_VERSION,
  parseWireMessage,
  type HelloMessage,
  type WireMessage,
} from "@hermes-remote/protocol";
import type { GatewayPeer } from "./gateway-peer.js";
import { legacyRoutingKey } from "./connector-registry.js";

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;
type ReportFailure = (message: string, error: unknown) => void;

interface LegacyControlSessionOptions {
  appToken: string;
  connectorToken: string;
  send: SendWireMessage;
  tokensEqual(actual: string, expected: string): boolean;
  register(peer: GatewayPeer): void;
  unregister(peer: GatewayPeer): void;
  route(peer: GatewayPeer, message: WireMessage): void;
  legacyDeviceIds(): IterableIterator<string>;
  reportFailure: ReportFailure;
}

export class LegacyControlSessionHandler {
  constructor(private readonly options: LegacyControlSessionOptions) {}

  attach(socket: WebSocket): void {
    let peer: GatewayPeer | undefined;
    const authTimer = setTimeout(() => socket.close(4401, "authentication timeout"), 5_000);

    socket.on("message", (data) => {
      let message: WireMessage;
      try {
        message = parseWireMessage(data.toString());
      } catch (error) {
        this.options.send(socket, errorMessage("bad_message", String(error)));
        socket.close(1008, "invalid message");
        return;
      }

      try {
        if (!peer) {
          if (message.type !== "hello" || !this.authenticate(message)) {
            socket.close(4401, "unauthorized");
            return;
          }
          clearTimeout(authTimer);
          peer = {
            socket,
            role: message.role,
            deviceId: message.deviceId,
            routingKey: legacyRoutingKey(message.deviceId),
            mode: "legacy",
          };
          this.options.register(peer);
          this.options.send(socket, {
            type: "hello_ack",
            version: PROTOCOL_VERSION,
            deviceId: message.deviceId,
          });
          if (peer.role === "app") {
            for (const deviceId of this.options.legacyDeviceIds()) {
              this.options.send(peer.socket, {
                type: "device_status",
                version: PROTOCOL_VERSION,
                deviceId,
                online: true,
              });
            }
          }
          return;
        }

        this.options.route(peer, message);
      } catch (error) {
        this.options.reportFailure("Control message failure", error);
        this.options.send(socket, errorMessage("bad_message", "Unable to process message"));
        socket.close(1008, "invalid message");
      }
    });

    socket.on("close", () => {
      clearTimeout(authTimer);
      if (peer) this.options.unregister(peer);
    });
  }

  private authenticate(message: HelloMessage): boolean {
    const expected = message.role === "app"
      ? this.options.appToken
      : this.options.connectorToken;
    return this.options.tokensEqual(message.token, expected) && message.deviceId.length > 0;
  }
}

function errorMessage(code: string, message: string): WireMessage {
  return { type: "error", version: PROTOCOL_VERSION, code, message };
}
