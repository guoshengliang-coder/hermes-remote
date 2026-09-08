import type { WebSocket } from "ws";
import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import type { BindingProofMaterial } from "./account/account-control-model.js";
import { silentGatewayLogger, type GatewayLogger } from "./gateway-log.js";
import type { LifecycleEventStore } from "./lifecycle-event-store.js";

interface LifecyclePeer {
  socket: WebSocket;
  deviceId: string;
  mode: "legacy" | "account";
  binding?: BindingProofMaterial;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;
type ReportFailure = (message: string, error: unknown) => void;

export class LifecycleMessageHandler {
  constructor(
    private readonly legacyEvents: LifecycleEventStore,
    private readonly accountControl: AccountGatewayControl | undefined,
    private readonly send: SendWireMessage,
    private readonly reportFailure: ReportFailure,
    private readonly log: GatewayLogger = silentGatewayLogger,
  ) {}

  handle(peer: LifecyclePeer, message: WireMessage): boolean {
    if (message.type !== "session.lifecycle") return false;
    if (peer.mode === "account") {
      this.handleAccountEvent(peer, message);
    } else {
      this.handleLegacyEvent(peer, message);
    }
    return true;
  }

  private handleAccountEvent(
    peer: LifecyclePeer,
    message: Extract<WireMessage, { type: "session.lifecycle" }>,
  ): void {
    const material = peer.binding;
    if (!this.accountControl || !material || message.deviceId !== peer.deviceId) {
      this.send(peer.socket, errorMessage(
        "device_mismatch",
        "Lifecycle event does not match Connector binding",
      ));
      return;
    }
    void this.accountControl.ingestLifecycleEvent(material, message).then((status) => {
      this.log.info("lifecycle.received", { ...describe(message), mode: "account", status });
      if (status === "stored" || status === "duplicate") {
        this.send(peer.socket, {
          type: "session.lifecycle.ack",
          version: PROTOCOL_VERSION,
          eventId: message.eventId,
        });
        return;
      }
      this.send(peer.socket, errorMessage(
        status,
        status === "event_id_conflict"
          ? "Lifecycle event ID was reused for different content"
          : "Connector binding is no longer active",
      ));
      peer.socket.close(status === "binding_invalid" ? 4403 : 1008, status);
    }).catch((error) => {
      this.reportFailure("Unable to persist account lifecycle event", error);
      this.send(peer.socket, errorMessage(
        "lifecycle_store_failed",
        "Unable to persist lifecycle event",
      ));
    });
  }

  private handleLegacyEvent(
    peer: LifecyclePeer,
    message: Extract<WireMessage, { type: "session.lifecycle" }>,
  ): void {
    if (message.deviceId !== peer.deviceId) {
      this.send(peer.socket, errorMessage(
        "device_mismatch",
        "Lifecycle event device does not match Connector",
      ));
      return;
    }
    // ACK only after the transition is durable. If the socket drops first, the Connector retains
    // the event in its local outbox and resends it; ingest() deduplicates by the stable event ID.
    void this.legacyEvents.ingest(message).then((record) => {
      this.log.info("lifecycle.received", { ...describe(message), mode: "legacy", sequence: record.sequence });
      this.send(peer.socket, {
        type: "session.lifecycle.ack",
        version: PROTOCOL_VERSION,
        eventId: message.eventId,
      });
    }).catch((error) => {
      this.reportFailure("Unable to persist lifecycle event", error);
      this.send(peer.socket, errorMessage(
        "lifecycle_store_failed",
        "Unable to persist lifecycle event",
      ));
    });
  }
}

function errorMessage(code: string, message: string): WireMessage {
  return { type: "error", version: PROTOCOL_VERSION, code, message };
}

/** The identity of an observation plus how long after the Mac stamped it the Gateway got it. */
function describe(message: Extract<WireMessage, { type: "session.lifecycle" }>): Record<string, unknown> {
  const occurred = Date.parse(message.occurredAt);
  return {
    eventId: message.eventId,
    kind: message.event,
    device: message.deviceId,
    storedSessionId: message.storedSessionId,
    runtimeSessionId: message.runtimeSessionId,
    occurredAt: message.occurredAt,
    lagMs: Number.isFinite(occurred) ? Date.now() - occurred : undefined,
  };
}
