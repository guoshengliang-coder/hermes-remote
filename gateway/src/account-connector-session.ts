import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  parseWireMessage,
  type WireMessage,
} from "@hermes-remote/protocol";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import type { BindingProofMaterial } from "./account/account-control-model.js";
import { accountRoutingKey } from "./connector-registry.js";
import type { GatewayPeer } from "./gateway-peer.js";
import type { AccountConnectorAdmission } from "./account-connector-admission.js";

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;
type ReportFailure = (message: string, error: unknown) => void;

interface AccountConnectorSessionOptions {
  control: AccountGatewayControl;
  admission: AccountConnectorAdmission;
  send: SendWireMessage;
  route(peer: GatewayPeer, message: WireMessage): void;
  register(peer: GatewayPeer): void;
  unregister(peer: GatewayPeer): boolean;
  reportFailure: ReportFailure;
}

type SessionPhase = "identify" | "authenticate" | "preflight" | "ready" | "processing";

export class AccountConnectorSessionHandler {
  constructor(private readonly options: AccountConnectorSessionOptions) {}

  attach(socket: WebSocket, sourceIp: string): void {
    const admissionLease = this.options.admission.acquire(sourceIp);
    let phase: SessionPhase = "identify";
    let challenge: Awaited<ReturnType<AccountGatewayControl["issueConnectorChallenge"]>> | undefined;
    let material: BindingProofMaterial | undefined;
    let peer: GatewayPeer | undefined;
    let preflightRequestId: string | undefined;
    let preflightStartedAt = 0;
    let preflightTimer: NodeJS.Timeout | undefined;
    const authTimer = setTimeout(() => socket.close(4401, "authentication timeout"), 5_000);

    socket.on("message", (data) => {
      let message: WireMessage;
      try {
        message = parseWireMessage(data.toString());
      } catch {
        socket.close(1008, "invalid message");
        return;
      }
      if (phase === "ready" && peer) {
        try {
          this.options.route(peer, message);
        } catch (error) {
          this.options.reportFailure("Account control message failure", error);
          socket.close(1008, "invalid message");
        }
        return;
      }
      if (phase === "processing") {
        socket.close(1008, "unexpected concurrent handshake message");
        return;
      }

      if (phase === "identify" && message.type === "connector.identify") {
        phase = "processing";
        void this.options.control.issueConnectorChallenge(message).then((issued) => {
          challenge = issued;
          phase = "authenticate";
          this.options.send(socket, {
            type: "connector.challenge",
            version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
            ...issued,
          });
        }).catch(() => socket.close(4401, "binding proof failed"));
        return;
      }

      if (phase === "authenticate" && challenge && message.type === "connector.authenticate") {
        phase = "processing";
        void this.options.control.authenticateConnector(message).then((authenticated) => {
          material = authenticated;
          admissionLease.release();
          clearTimeout(authTimer);
          preflightRequestId = randomUUID();
          preflightStartedAt = Date.now();
          phase = "preflight";
          preflightTimer = setTimeout(() => socket.close(4408, "preflight timeout"), 5_000);
          this.options.send(socket, {
            type: "connector.preflight.request",
            version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
            requestId: preflightRequestId,
            sentAt: new Date(preflightStartedAt).toISOString(),
          });
        }).catch(() => socket.close(4401, "binding proof failed"));
        return;
      }

      if (phase === "preflight" && material && message.type === "connector.preflight.result"
          && message.requestId === preflightRequestId) {
        phase = "processing";
        if (preflightTimer) clearTimeout(preflightTimer);
        const latencyMs = Math.min(60_000, Math.max(0, Date.now() - preflightStartedAt));
        void this.options.control.recordConnectorHealth(material, {
          hermesReachable: message.hermesReachable,
          ...(message.hermesVersion ? { hermesVersion: message.hermesVersion } : {}),
          gatewayLatencyMs: latencyMs,
          endToEndHealthy: message.hermesReachable,
        }).then((recorded) => {
          if (!recorded) {
            socket.close(4401, "binding changed during preflight");
            return;
          }
          peer = {
            socket,
            role: "connector",
            deviceId: material!.deviceId,
            routingKey: accountRoutingKey(material!.id),
            mode: "account",
            accountId: material!.accountId,
            binding: material!,
          };
          this.options.register(peer);
          phase = "ready";
          this.options.send(socket, {
            type: "connector.ready",
            version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
            bindingId: material!.id,
            generation: material!.generation,
            deviceId: material!.deviceId,
            bindingStatus: material!.status,
            routingEnabled: material!.status === "active",
          });
        }).catch(() => socket.close(1011, "health update failed"));
        return;
      }

      socket.close(1008, "unexpected handshake message");
    });

    socket.on("close", () => {
      clearTimeout(authTimer);
      if (preflightTimer) clearTimeout(preflightTimer);
      admissionLease.release();
      const shouldRecordDisconnected = peer
        ? this.options.unregister(peer)
        : Boolean(material);
      if (material && shouldRecordDisconnected) {
        void this.options.control.recordConnectorDisconnected(material).catch((error) => {
          this.options.reportFailure("Unable to record account Connector disconnect", error);
        });
      }
    });
  }
}
