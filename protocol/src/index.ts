export const PROTOCOL_VERSION = 1 as const;
export const ACCOUNT_CONNECTOR_PROTOCOL_VERSION = 2 as const;

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

export interface ConnectorIdentify {
  type: "connector.identify";
  version: typeof ACCOUNT_CONNECTOR_PROTOCOL_VERSION;
  bindingId: string;
  generation: number;
  publicKeyFingerprint: string;
}

export interface ConnectorChallengeMessage {
  type: "connector.challenge";
  version: typeof ACCOUNT_CONNECTOR_PROTOCOL_VERSION;
  bindingId: string;
  generation: number;
  publicKeyFingerprint: string;
  challenge: string;
  connectionNonce: string;
  serverTime: string;
  expiresAt: string;
}

export interface ConnectorAuthenticate {
  type: "connector.authenticate";
  version: typeof ACCOUNT_CONNECTOR_PROTOCOL_VERSION;
  bindingId: string;
  generation: number;
  publicKeyFingerprint: string;
  connectionNonce: string;
  signature: string;
}

export interface ConnectorPreflightRequest {
  type: "connector.preflight.request";
  version: typeof ACCOUNT_CONNECTOR_PROTOCOL_VERSION;
  requestId: string;
  sentAt: string;
}

export interface ConnectorPreflightResult {
  type: "connector.preflight.result";
  version: typeof ACCOUNT_CONNECTOR_PROTOCOL_VERSION;
  requestId: string;
  hermesReachable: boolean;
  hermesVersion?: string;
}

export interface ConnectorReady {
  type: "connector.ready";
  version: typeof ACCOUNT_CONNECTOR_PROTOCOL_VERSION;
  bindingId: string;
  generation: number;
  deviceId: string;
  bindingStatus: "pending" | "active";
  routingEnabled: boolean;
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

export type SessionLifecycleEventKind =
  | "run.started"
  | "run.waiting"
  | "run.resumed"
  | "run.completed"
  | "run.interrupted"
  | "run.unknown";

export type SessionLifecycleState = "starting" | "working" | "waiting" | "idle" | "unknown";

/**
 * A small, sanitized lifecycle transition observed through Hermes' read-only
 * `session.active_list` RPC. It deliberately carries no prompt, model output,
 * tool result, command, file path, credential, or approval payload.
 */
export interface SessionLifecycleEvent {
  type: "session.lifecycle";
  version: typeof PROTOCOL_VERSION;
  eventId: string;
  deviceId: string;
  profile?: string;
  runtimeSessionId: string;
  storedSessionId: string;
  event: SessionLifecycleEventKind;
  state: SessionLifecycleState;
  occurredAt: string;
  title?: string;
}

/** Relay acknowledgement: the event is durable and the Connector may drop it from its outbox. */
export interface SessionLifecycleAck {
  type: "session.lifecycle.ack";
  version: typeof PROTOCOL_VERSION;
  eventId: string;
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

/** Streaming HTTP response metadata. Large bodies follow as acknowledged chunks. */
export interface TunnelHttpResponseStart {
  type: "tunnel.http.response.start";
  version: typeof PROTOCOL_VERSION;
  requestId: string;
  status: number;
  headers: Record<string, string>;
}

export interface TunnelHttpResponseChunk {
  type: "tunnel.http.response.chunk";
  version: typeof PROTOCOL_VERSION;
  requestId: string;
  sequence: number;
  dataBase64: string;
}

export interface TunnelHttpResponseAck {
  type: "tunnel.http.response.ack";
  version: typeof PROTOCOL_VERSION;
  requestId: string;
  sequence: number;
}

export interface TunnelHttpResponseEnd {
  type: "tunnel.http.response.end";
  version: typeof PROTOCOL_VERSION;
  requestId: string;
  error?: string;
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
  | ConnectorIdentify
  | ConnectorChallengeMessage
  | ConnectorAuthenticate
  | ConnectorPreflightRequest
  | ConnectorPreflightResult
  | ConnectorReady
  | ChatCommand
  | RelayEvent
  | DeviceStatus
  | SessionLifecycleEvent
  | SessionLifecycleAck
  | ErrorMessage
  | TunnelHttpRequest
  | TunnelHttpResponse
  | TunnelHttpResponseStart
  | TunnelHttpResponseChunk
  | TunnelHttpResponseAck
  | TunnelHttpResponseEnd
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
  const accountConnectorType = value.type.startsWith("connector.");
  if (value.version !== (accountConnectorType ? ACCOUNT_CONNECTOR_PROTOCOL_VERSION : PROTOCOL_VERSION)) {
    throw new Error("unsupported_version");
  }

  // TypeScript interfaces disappear at runtime. Every value crossing the public WebSocket must be
  // validated before callers touch it; a cast here previously let values such as `token: null`
  // reach Buffer.from() in the Gateway and terminate the process before authentication.
  switch (value.type) {
    case "connector.identify":
      assertOnlyKeys(value, new Set([
        "type", "version", "bindingId", "generation", "publicKeyFingerprint",
      ]), "invalid_connector_identify_fields");
      return {
        type: "connector.identify",
        version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        bindingId: uuid(value.bindingId, "invalid_binding_id"),
        generation: integer(value.generation, "invalid_binding_generation", 1, 2_147_483_647),
        publicKeyFingerprint: fingerprint(value.publicKeyFingerprint),
      };
    case "connector.challenge":
      assertOnlyKeys(value, new Set([
        "type", "version", "bindingId", "generation", "publicKeyFingerprint", "challenge",
        "connectionNonce", "serverTime", "expiresAt",
      ]), "invalid_connector_challenge_fields");
      return {
        type: "connector.challenge",
        version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        bindingId: uuid(value.bindingId, "invalid_binding_id"),
        generation: integer(value.generation, "invalid_binding_generation", 1, 2_147_483_647),
        publicKeyFingerprint: fingerprint(value.publicKeyFingerprint),
        challenge: base64url(value.challenge, "invalid_challenge", 43, 32),
        connectionNonce: base64url(value.connectionNonce, "invalid_connection_nonce", 32, 24),
        serverTime: isoTimestamp(value.serverTime),
        expiresAt: isoTimestamp(value.expiresAt),
      };
    case "connector.authenticate":
      assertOnlyKeys(value, new Set([
        "type", "version", "bindingId", "generation", "publicKeyFingerprint",
        "connectionNonce", "signature",
      ]), "invalid_connector_authenticate_fields");
      return {
        type: "connector.authenticate",
        version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        bindingId: uuid(value.bindingId, "invalid_binding_id"),
        generation: integer(value.generation, "invalid_binding_generation", 1, 2_147_483_647),
        publicKeyFingerprint: fingerprint(value.publicKeyFingerprint),
        connectionNonce: base64url(value.connectionNonce, "invalid_connection_nonce", 32, 24),
        signature: base64url(value.signature, "invalid_connector_signature", 86, 64),
      };
    case "connector.preflight.request":
      assertOnlyKeys(value, new Set([
        "type", "version", "requestId", "sentAt",
      ]), "invalid_connector_preflight_fields");
      return {
        type: "connector.preflight.request",
        version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        requestId: uuid(value.requestId, "invalid_request_id"),
        sentAt: isoTimestamp(value.sentAt),
      };
    case "connector.preflight.result": {
      assertOnlyKeys(value, new Set([
        "type", "version", "requestId", "hermesReachable", "hermesVersion",
      ]), "invalid_connector_preflight_fields");
      const hermesVersion = optionalDisplayString(value.hermesVersion, "invalid_hermes_version", 64);
      return {
        type: "connector.preflight.result",
        version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        requestId: uuid(value.requestId, "invalid_request_id"),
        hermesReachable: booleanValue(value.hermesReachable, "invalid_hermes_reachable"),
        ...(hermesVersion === undefined ? {} : { hermesVersion }),
      };
    }
    case "connector.ready":
      assertOnlyKeys(value, new Set([
        "type", "version", "bindingId", "generation", "deviceId", "bindingStatus",
        "routingEnabled",
      ]), "invalid_connector_ready_fields");
      return {
        type: "connector.ready",
        version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
        bindingId: uuid(value.bindingId, "invalid_binding_id"),
        generation: integer(value.generation, "invalid_binding_generation", 1, 2_147_483_647),
        deviceId: boundedString(value.deviceId, "invalid_device_id", 1, 128),
        bindingStatus: oneOf(value.bindingStatus, ["pending", "active"] as const, "invalid_binding_status"),
        routingEnabled: booleanValue(value.routingEnabled, "invalid_routing_enabled"),
      };
    case "hello":
      return {
        type: "hello",
        version: PROTOCOL_VERSION,
        role: oneOf(value.role, ["app", "connector"] as const, "invalid_role"),
        deviceId: boundedString(value.deviceId, "invalid_device_id", 1, 128),
        token: boundedString(value.token, "invalid_token", 1, 4096),
      };
    case "hello_ack":
      return {
        type: "hello_ack",
        version: PROTOCOL_VERSION,
        deviceId: boundedString(value.deviceId, "invalid_device_id", 1, 128),
      };
    case "command": {
      const payload = record(value.payload, "invalid_command_payload");
      if (payload.kind !== "chat") throw new Error("invalid_command_kind");
      const sessionId = optionalString(value.sessionId, "invalid_session_id", 256);
      return {
        type: "command",
        version: PROTOCOL_VERSION,
        id: boundedString(value.id, "invalid_request_id", 1, 128),
        targetDeviceId: boundedString(value.targetDeviceId, "invalid_device_id", 1, 128),
        ...(sessionId === undefined ? {} : { sessionId }),
        payload: {
          kind: "chat",
          input: boundedString(payload.input, "invalid_command_input", 0, 1_048_576),
        },
      };
    }
    case "event":
      return {
        type: "event",
        version: PROTOCOL_VERSION,
        requestId: boundedString(value.requestId, "invalid_request_id", 1, 128),
        event: oneOf(value.event, ["accepted", "delta", "complete", "error"] as const, "invalid_event"),
        ...(value.data === undefined ? {} : { data: value.data }),
      };
    case "device_status":
      return {
        type: "device_status",
        version: PROTOCOL_VERSION,
        deviceId: boundedString(value.deviceId, "invalid_device_id", 1, 128),
        online: booleanValue(value.online, "invalid_online"),
      };
    case "session.lifecycle": {
      assertOnlyKeys(value, new Set([
        "type", "version", "eventId", "deviceId", "profile", "runtimeSessionId",
        "storedSessionId", "event", "state", "occurredAt", "title",
      ]), "invalid_lifecycle_fields");
      const profile = optionalString(value.profile, "invalid_profile", 128);
      const title = optionalString(value.title, "invalid_title", 256);
      return {
        type: "session.lifecycle",
        version: PROTOCOL_VERSION,
        eventId: boundedString(value.eventId, "invalid_event_id", 1, 128),
        deviceId: boundedString(value.deviceId, "invalid_device_id", 1, 128),
        ...(profile === undefined ? {} : { profile }),
        runtimeSessionId: boundedString(value.runtimeSessionId, "invalid_runtime_session_id", 1, 256),
        storedSessionId: boundedString(value.storedSessionId, "invalid_stored_session_id", 1, 256),
        event: oneOf(
          value.event,
          ["run.started", "run.waiting", "run.resumed", "run.completed", "run.interrupted", "run.unknown"] as const,
          "invalid_lifecycle_event",
        ),
        state: oneOf(
          value.state,
          ["starting", "working", "waiting", "idle", "unknown"] as const,
          "invalid_lifecycle_state",
        ),
        occurredAt: isoTimestamp(value.occurredAt),
        ...(title === undefined ? {} : { title }),
      };
    }
    case "session.lifecycle.ack":
      return {
        type: "session.lifecycle.ack",
        version: PROTOCOL_VERSION,
        eventId: boundedString(value.eventId, "invalid_event_id", 1, 128),
      };
    case "error": {
      const requestId = optionalString(value.requestId, "invalid_request_id", 128);
      return {
        type: "error",
        version: PROTOCOL_VERSION,
        code: boundedString(value.code, "invalid_error_code", 1, 128),
        message: boundedString(value.message, "invalid_error_message", 0, 4096),
        ...(requestId === undefined ? {} : { requestId }),
      };
    }
    case "tunnel.http.request": {
      const bodyBase64 = optionalBase64(value.bodyBase64);
      return {
        type: "tunnel.http.request",
        version: PROTOCOL_VERSION,
        id: boundedString(value.id, "invalid_request_id", 1, 128),
        targetDeviceId: boundedString(value.targetDeviceId, "invalid_device_id", 1, 128),
        method: boundedString(value.method, "invalid_http_method", 1, 16),
        path: apiPath(value.path),
        headers: stringRecord(value.headers, "invalid_headers"),
        ...(bodyBase64 === undefined ? {} : { bodyBase64 }),
      };
    }
    case "tunnel.http.response": {
      const bodyBase64 = optionalBase64(value.bodyBase64);
      return {
        type: "tunnel.http.response",
        version: PROTOCOL_VERSION,
        requestId: boundedString(value.requestId, "invalid_request_id", 1, 128),
        status: integer(value.status, "invalid_http_status", 100, 599),
        headers: stringRecord(value.headers, "invalid_headers"),
        ...(bodyBase64 === undefined ? {} : { bodyBase64 }),
      };
    }
    case "tunnel.http.response.start":
      return {
        type: "tunnel.http.response.start",
        version: PROTOCOL_VERSION,
        requestId: boundedString(value.requestId, "invalid_request_id", 1, 128),
        status: integer(value.status, "invalid_http_status", 100, 599),
        headers: stringRecord(value.headers, "invalid_headers"),
      };
    case "tunnel.http.response.chunk":
      return {
        type: "tunnel.http.response.chunk",
        version: PROTOCOL_VERSION,
        requestId: boundedString(value.requestId, "invalid_request_id", 1, 128),
        sequence: integer(value.sequence, "invalid_chunk_sequence", 0, 1_000_000),
        dataBase64: boundedString(value.dataBase64, "invalid_chunk", 0, MAX_CHUNK_BASE64_CHARS),
      };
    case "tunnel.http.response.ack":
      return {
        type: "tunnel.http.response.ack",
        version: PROTOCOL_VERSION,
        requestId: boundedString(value.requestId, "invalid_request_id", 1, 128),
        sequence: integer(value.sequence, "invalid_chunk_sequence", 0, 1_000_000),
      };
    case "tunnel.http.response.end": {
      const error = optionalString(value.error, "invalid_stream_error", 256);
      return {
        type: "tunnel.http.response.end",
        version: PROTOCOL_VERSION,
        requestId: boundedString(value.requestId, "invalid_request_id", 1, 128),
        ...(error === undefined ? {} : { error }),
      };
    }
    case "tunnel.ws.open":
      return {
        type: "tunnel.ws.open",
        version: PROTOCOL_VERSION,
        id: boundedString(value.id, "invalid_tunnel_id", 1, 128),
        targetDeviceId: boundedString(value.targetDeviceId, "invalid_device_id", 1, 128),
        path: apiPath(value.path),
      };
    case "tunnel.ws.frame":
      return {
        type: "tunnel.ws.frame",
        version: PROTOCOL_VERSION,
        id: boundedString(value.id, "invalid_tunnel_id", 1, 128),
        dataBase64: boundedString(value.dataBase64, "invalid_frame", 0, MAX_BASE64_CHARS),
        binary: booleanValue(value.binary, "invalid_binary_flag"),
      };
    case "tunnel.ws.close": {
      const code = optionalInteger(value.code, "invalid_close_code", 1000, 4999);
      const reason = optionalString(value.reason, "invalid_close_reason", 120);
      return {
        type: "tunnel.ws.close",
        version: PROTOCOL_VERSION,
        id: boundedString(value.id, "invalid_tunnel_id", 1, 128),
        ...(code === undefined ? {} : { code }),
        ...(reason === undefined ? {} : { reason }),
      };
    }
    default:
      throw new Error("unsupported_message_type");
  }
}

export function encodeWireMessage(message: WireMessage): string {
  return JSON.stringify(message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const MAX_BASE64_CHARS = 16 * 1024 * 1024;
const MAX_CHUNK_BASE64_CHARS = 512 * 1024;

function record(value: unknown, error: string): Record<string, unknown> {
  if (!isRecord(value)) throw new Error(error);
  return value;
}

function boundedString(
  value: unknown,
  error: string,
  minLength: number,
  maxLength: number,
): string {
  if (typeof value !== "string" || value.length < minLength || value.length > maxLength) {
    throw new Error(error);
  }
  return value;
}

function optionalString(value: unknown, error: string, maxLength: number): string | undefined {
  if (value === undefined) return undefined;
  return boundedString(value, error, 0, maxLength);
}

function optionalDisplayString(value: unknown, error: string, maxLength: number): string | undefined {
  if (value === undefined) return undefined;
  const result = boundedString(value, error, 1, maxLength);
  if (/[\u0000-\u001f\u007f]/.test(result)) throw new Error(error);
  return result;
}

function uuid(value: unknown, error: string): string {
  const result = boundedString(value, error, 36, 36);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(result)) {
    throw new Error(error);
  }
  return result.toLowerCase();
}

function fingerprint(value: unknown): string {
  const result = boundedString(value, "invalid_public_key_fingerprint", 64, 64);
  if (!/^[0-9a-f]{64}$/.test(result)) throw new Error("invalid_public_key_fingerprint");
  return result;
}

function base64url(
  value: unknown,
  error: string,
  encodedLength: number,
  decodedLength: number,
): string {
  const result = boundedString(value, error, encodedLength, encodedLength);
  if (!/^[A-Za-z0-9_-]+$/.test(result)) throw new Error(error);
  const decoded = Uint8Array.from(Buffer.from(result, "base64url"));
  if (decoded.byteLength !== decodedLength || Buffer.from(decoded).toString("base64url") !== result) {
    throw new Error(error);
  }
  return result;
}

function optionalBase64(value: unknown): string | undefined {
  if (value === undefined) return undefined;
  return boundedString(value, "invalid_base64_body", 0, MAX_BASE64_CHARS);
}

function isoTimestamp(value: unknown): string {
  const timestamp = boundedString(value, "invalid_occurred_at", 1, 64);
  if (!Number.isFinite(Date.parse(timestamp))) throw new Error("invalid_occurred_at");
  return timestamp;
}

function assertOnlyKeys(value: Record<string, unknown>, allowed: ReadonlySet<string>, error: string): void {
  if (Object.keys(value).some((key) => !allowed.has(key))) throw new Error(error);
}

function booleanValue(value: unknown, error: string): boolean {
  if (typeof value !== "boolean") throw new Error(error);
  return value;
}

function integer(value: unknown, error: string, min: number, max: number): number {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    throw new Error(error);
  }
  return value as number;
}

function optionalInteger(
  value: unknown,
  error: string,
  min: number,
  max: number,
): number | undefined {
  if (value === undefined) return undefined;
  return integer(value, error, min, max);
}

function oneOf<const T extends readonly string[]>(
  value: unknown,
  allowed: T,
  error: string,
): T[number] {
  if (typeof value !== "string" || !allowed.includes(value)) throw new Error(error);
  return value as T[number];
}

function apiPath(value: unknown): string {
  const path = boundedString(value, "invalid_api_path", 1, 4096);
  if (!path.startsWith("/api/")) throw new Error("unsupported_api_path");
  return path;
}

function stringRecord(value: unknown, error: string): Record<string, string> {
  const object = record(value, error);
  const entries = Object.entries(object);
  if (entries.length > 64) throw new Error(error);
  const result: Record<string, string> = {};
  for (const [name, item] of entries) {
    if (name.length === 0 || name.length > 128 || typeof item !== "string" || item.length > 8192) {
      throw new Error(error);
    }
    result[name] = item;
  }
  return result;
}
