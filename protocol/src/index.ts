export const PROTOCOL_VERSION = 1 as const;

export type Role = "app" | "connector";

export interface HelloMessage {
  type: "hello";
  version: typeof PROTOCOL_VERSION;
  role: Role;
  deviceId: string;
  token: string;
}

export interface HelloAckMessage {
  type: "hello_ack";
  version: typeof PROTOCOL_VERSION;
  deviceId: string;
}

export interface ChatCommand {
  type: "command";
  version: typeof PROTOCOL_VERSION;
  id: string;
  targetDeviceId: string;
  sessionId?: string;
  payload: {
    kind: "chat";
    input: string;
  };
}

export interface RelayEvent {
  type: "event";
  version: typeof PROTOCOL_VERSION;
  requestId: string;
  event: "accepted" | "delta" | "complete" | "error";
  data?: unknown;
}

export interface DeviceStatus {
  type: "device_status";
  version: typeof PROTOCOL_VERSION;
  deviceId: string;
  online: boolean;
}

export interface ErrorMessage {
  type: "error";
  version: typeof PROTOCOL_VERSION;
  code: string;
  message: string;
  requestId?: string;
}

export interface TunnelHttpRequest {
  type: "tunnel.http.request";
  version: typeof PROTOCOL_VERSION;
  id: string;
  targetDeviceId: string;
  method: string;
  path: string;
  headers: Record<string, string>;
  bodyBase64?: string;
}

export interface TunnelHttpResponse {
  type: "tunnel.http.response";
  version: typeof PROTOCOL_VERSION;
  requestId: string;
  status: number;
  headers: Record<string, string>;
  bodyBase64?: string;
}

export interface TunnelSocketOpen {
  type: "tunnel.ws.open";
  version: typeof PROTOCOL_VERSION;
  id: string;
  targetDeviceId: string;
  path: string;
}

export interface TunnelSocketFrame {
  type: "tunnel.ws.frame";
  version: typeof PROTOCOL_VERSION;
  id: string;
  dataBase64: string;
  binary: boolean;
}

export interface TunnelSocketClose {
  type: "tunnel.ws.close";
  version: typeof PROTOCOL_VERSION;
  id: string;
  code?: number;
  reason?: string;
}

export type WireMessage =
  | HelloMessage
  | HelloAckMessage
  | ChatCommand
  | RelayEvent
  | DeviceStatus
  | ErrorMessage
  | TunnelHttpRequest
  | TunnelHttpResponse
  | TunnelSocketOpen
  | TunnelSocketFrame
  | TunnelSocketClose;

export function parseWireMessage(raw: string): WireMessage {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new Error("invalid_json");
  }

  if (!isRecord(value) || typeof value.type !== "string") {
    throw new Error("invalid_message");
  }
  if (value.version !== PROTOCOL_VERSION) {
    throw new Error("unsupported_version");
  }
  return value as unknown as WireMessage;
}

export function encodeWireMessage(message: WireMessage): string {
  return JSON.stringify(message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
