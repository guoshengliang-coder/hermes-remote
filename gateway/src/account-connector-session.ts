import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  parseWireMessage,
  type ConnectorPreflightResult,
  type WireMessage,
} from "@hermes-remote/protocol";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import type { BindingProofMaterial } from "./account/account-control-model.js";
import { accountRoutingKey } from "./connector-registry.js";
import type { GatewayPeer } from "./gateway-peer.js";
import type { AccountConnectorAdmission } from "./account-connector-admission.js";
import { silentGatewayLogger, type GatewayLogger } from "./gateway-log.js";

export const ACCOUNT_CONNECTOR_PREFLIGHT_TIMEOUT_MS = 15_000;
export const ACCOUNT_CONNECTOR_HEALTH_INTERVAL_MS = 20_000;

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
  log?: GatewayLogger;
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
    let closed = false;
    let preflightRequestId: string | undefined;
    let preflightStartedAt = 0;
    let preflightTimer: NodeJS.Timeout | undefined;
    let recheckTimer: NodeJS.Timeout | undefined;
    let healthWrite: Promise<void> | undefined;
    const log = this.options.log ?? silentGatewayLogger;
    const isOpen = () => !closed && socket.readyState === WebSocket.OPEN;
    const authTimer = setTimeout(() => socket.close(4401, "authentication timeout"), 5_000);

    const scheduleRecheck = () => {
      if (!isOpen()) return;
      recheckTimer = setTimeout(startProbe, ACCOUNT_CONNECTOR_HEALTH_INTERVAL_MS);
      recheckTimer.unref();
    };
    const recordHealth = (reachable: boolean, version?: string, timedOut = false) => {
      if (!isOpen() || !material) return;
      const initial = !peer;
      const latencyMs = Math.min(60_000, Math.max(0, Date.now() - preflightStartedAt));
      const previousReachable = peer?.hermesReachable;
      // A negative observation takes effect immediately; recovery waits for binding validation.
      if (peer && !reachable) peer.hermesReachable = false;
      healthWrite = this.options.control.recordConnectorHealth(material, {
        hermesReachable: reachable, ...(version ? { hermesVersion: version } : {}),
        gatewayLatencyMs: latencyMs, endToEndHealthy: reachable,
      }).then((recorded) => {
        if (!isOpen()) return;
        if (!recorded) {
          socket.close(4401, "binding changed during preflight");
          return;
        }
        if (!peer) {
          peer = {
            socket, role: "connector", deviceId: material!.deviceId,
            routingKey: accountRoutingKey(material!.id), mode: "account",
            accountId: material!.accountId, binding: material!, hermesReachable: reachable,
          };
          this.options.register(peer);
          phase = "ready";
          this.options.send(socket, {
            type: "connector.ready", version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
            bindingId: material!.id, generation: material!.generation, deviceId: material!.deviceId,
            bindingStatus: material!.status, routingEnabled: material!.status === "active",
          });
        } else {
          peer.hermesReachable = reachable;
        }
        if (initial || previousReachable !== reachable || timedOut) {
          log.info("connector.hermes_health", {
            device: material!.deviceId, reachable, timedOut, durationMs: latencyMs,
          });
        }
        scheduleRecheck();
      }).catch((error) => {
        if (!isOpen()) return;
        this.options.reportFailure("Account Connector health update failed", error);
        if (initial) socket.close(1011, "health update failed");
        else {
          peer!.hermesReachable = false;
          scheduleRecheck();
        }
      });
    };
    const finishProbe = (result?: ConnectorPreflightResult) => {
      if (!preflightRequestId) return;
      preflightRequestId = undefined;
      if (preflightTimer) clearTimeout(preflightTimer);
      preflightTimer = undefined;
      if (!peer) phase = "processing";
      recordHealth(result?.hermesReachable ?? false, result?.hermesVersion, !result);
    };
    const startProbe = () => {
      if (!isOpen() || !material || preflightRequestId) return;
      preflightRequestId = randomUUID();
      preflightStartedAt = Date.now();
      if (!peer) phase = "preflight";
      preflightTimer = setTimeout(() => finishProbe(), ACCOUNT_CONNECTOR_PREFLIGHT_TIMEOUT_MS);
      this.options.send(socket, {
        type: "connector.preflight.request", version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        requestId: preflightRequestId, sentAt: new Date(preflightStartedAt).toISOString(),
      });
    };

    socket.on("message", (data) => {
      if (!isOpen()) return;
      let message: WireMessage;
      try {
        message = parseWireMessage(data.toString());
      } catch {
        socket.close(1008, "invalid message");
        return;
      }
      if (material && message.type === "connector.preflight.result") {
        // Old Connectors can answer their 60s probe after the Gateway's deadline. A stale reply
        // never revives health, and must not tear down this otherwise authenticated connection.
        if (message.requestId === preflightRequestId) finishProbe(message);
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
          if (!isOpen()) return;
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
          if (!isOpen()) return;
          material = authenticated;
          admissionLease.release();
          clearTimeout(authTimer);
          startProbe();
        }).catch(() => socket.close(4401, "binding proof failed"));
        return;
      }

      socket.close(1008, "unexpected handshake message");
    });

    socket.on("close", () => {
      closed = true;
      clearTimeout(authTimer);
      if (preflightTimer) clearTimeout(preflightTimer);
      if (recheckTimer) clearTimeout(recheckTimer);
      preflightRequestId = undefined;
      admissionLease.release();
      const shouldRecordDisconnected = peer
        ? this.options.unregister(peer)
        : Boolean(material);
      if (material && shouldRecordDisconnected) {
        // A write already in flight cannot be cancelled. Order offline after it so it cannot
        // leave a closed socket looking online in the persisted device snapshot.
        void (healthWrite ?? Promise.resolve()).then(() => this.options.control.recordConnectorDisconnected(material!)).catch((error) => {
          this.options.reportFailure("Unable to record account Connector disconnect", error);
        });
      }
    });
  }
}
