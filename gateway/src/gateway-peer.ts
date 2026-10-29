import type { WebSocket } from "ws";
import type { HelloMessage } from "@hermes-remote/protocol";
import type { BindingProofMaterial } from "./account/account-control-model.js";

export interface GatewayPeer {
  transport?: import("./account-transport-sessions.js").PeerTransport;
  socket: WebSocket;
  role: HelloMessage["role"];
  deviceId: string;
  routingKey: string;
  mode: "legacy" | "account";
  accountId?: string;
  binding?: BindingProofMaterial;
  /** Local Hermes health is independent of this authenticated control socket's liveness. */
  hermesReachable?: boolean;
}
