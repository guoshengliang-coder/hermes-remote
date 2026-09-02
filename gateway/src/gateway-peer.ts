import type { WebSocket } from "ws";
import type { HelloMessage } from "@hermes-remote/protocol";
import type { BindingProofMaterial } from "./account/account-control-model.js";

export interface GatewayPeer {
  socket: WebSocket;
  role: HelloMessage["role"];
  deviceId: string;
  routingKey: string;
  mode: "legacy" | "account";
  accountId?: string;
  binding?: BindingProofMaterial;
}
