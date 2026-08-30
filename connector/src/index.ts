import { WebSocket } from "ws";
import { readFileSync } from "node:fs";
import {
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type ChatCommand,
  type RelayEvent,
  type TunnelHttpRequest,
  type TunnelSocketFrame,
  type TunnelSocketOpen,
  type WireMessage,
} from "@hermes-remote/protocol";

const gatewayUrl = process.env.GATEWAY_URL ?? "ws://127.0.0.1:8787/v1/connect";
const connectorToken = requireSecret("CONNECTOR_TOKEN");
const deviceId = process.env.DEVICE_ID ?? "mac-mini";
const hermesMode = process.env.HERMES_MODE ?? "mock";
const hermesBaseUrl = (process.env.HERMES_BASE_URL ?? "http://127.0.0.1:9119").replace(/\/$/, "");
const hermesChatUrl = process.env.HERMES_CHAT_URL ?? `${hermesBaseUrl}/api/chat`;
const controlHeartbeatMs = positiveIntEnv("CONTROL_HEARTBEAT_MS", 15_000);
const localRequestTimeoutMs = positiveIntEnv("LOCAL_REQUEST_TIMEOUT_MS", 60_000);
const chatRequestTimeoutMs = positiveIntEnv("CHAT_REQUEST_TIMEOUT_MS", 10 * 60_000);
const localSocketConnectTimeoutMs = positiveIntEnv("LOCAL_WS_CONNECT_TIMEOUT_MS", 15_000);
const maxWirePayloadBytes = positiveIntEnv("MAX_WIRE_PAYLOAD_BYTES", 20 * 1024 * 1024);
const maxLocalSocketPayloadBytes = positiveIntEnv("MAX_LOCAL_WS_PAYLOAD_BYTES", 12 * 1024 * 1024);
const maxPendingSocketFrames = positiveIntEnv("MAX_PENDING_WS_FRAMES", 256);
const maxControlBufferedBytes = positiveIntEnv("MAX_CONTROL_BUFFERED_BYTES", 24 * 1024 * 1024);
const maxLocalBufferedBytes = positiveIntEnv("MAX_LOCAL_WS_BUFFERED_BYTES", 24 * 1024 * 1024);
const localSockets = new Map<string, WebSocket>();
const pendingSocketFrames = new Map<string, TunnelSocketFrame[]>();
let retryMs = 1_000;
let controlSocket: WebSocket | undefined;

function connect(): void {
  const socket = new WebSocket(gatewayUrl, {
    handshakeTimeout: localSocketConnectTimeoutMs,
    maxPayload: maxWirePayloadBytes,
  });
  let heartbeatTimer: NodeJS.Timeout | undefined;
  let awaitingPong = false;
  controlSocket = socket;

  socket.on("open", () => {
    retryMs = 1_000;
    socket.send(encodeWireMessage({
      type: "hello",
      version: PROTOCOL_VERSION,
      role: "connector",
      deviceId,
      token: connectorToken,
    }));

    // A Mac sleep/wake or network switch can leave a TCP socket looking OPEN locally after the
    // Relay has already discarded it. Without an application heartbeat the Connector then stays
    // in a false "Connected" state forever. WebSocket ping/pong gives that half-open connection
    // one interval to answer, then terminates it so the normal reconnect path takes over.
    heartbeatTimer = setInterval(() => {
      if (controlSocket !== socket || socket.readyState !== WebSocket.OPEN) return;
      if (awaitingPong) {
        console.log("Control heartbeat timed out; forcing reconnect");
        socket.terminate();
        return;
      }
      awaitingPong = true;
      socket.ping();
    }, controlHeartbeatMs);
  });

  socket.on("pong", () => {
    awaitingPong = false;
  });

  socket.on("message", (raw) => {
    void handleGatewayMessage(socket, raw.toString()).catch((error) => {
      console.error("Unable to handle gateway message", safeError(error));
    });
  });

  socket.on("close", () => {
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    closeLocalSockets();
    if (controlSocket === socket) controlSocket = undefined;
    scheduleReconnect();
  });
  socket.on("error", (error) => console.error("Gateway connection error", error.message));
}

async function handleGatewayMessage(socket: WebSocket, raw: string): Promise<void> {
  const message = parseWireMessage(raw);
  switch (message.type) {
    case "hello_ack":
      console.log(`Connected to gateway as ${message.deviceId}`);
      return;
    case "command":
      await handleCommand(socket, message);
      return;
    case "tunnel.http.request":
      await handleTunnelHttp(socket, message);
      return;
    case "tunnel.ws.open":
      await openTunnelSocket(socket, message);
      return;
    case "tunnel.ws.frame":
      forwardTunnelFrame(message);
      return;
    case "tunnel.ws.close":
      closeTunnelSocket(message.id, message.code, message.reason);
      return;
    default:
      return;
  }
}

async function handleTunnelHttp(socket: WebSocket, request: TunnelHttpRequest): Promise<void> {
  if (request.targetDeviceId !== deviceId) return;
  try {
    const response = await hermesAuth.request(request.path, {
      method: request.method,
      headers: request.headers,
      body: request.bodyBase64 ? Buffer.from(request.bodyBase64, "base64") : undefined,
    });
    const body = Buffer.from(await response.arrayBuffer());
    sendControl(socket, {
      type: "tunnel.http.response",
      version: PROTOCOL_VERSION,
      requestId: request.id,
      status: response.status,
      headers: selectResponseHeaders(response.headers),
      bodyBase64: body.length > 0 ? body.toString("base64") : undefined,
    });
  } catch (error) {
    console.error("Local Hermes HTTP error", safeError(error));
    sendControl(socket, {
      type: "tunnel.http.response",
      version: PROTOCOL_VERSION,
      requestId: request.id,
      status: 502,
      headers: { "content-type": "application/json" },
      bodyBase64: Buffer.from(JSON.stringify({ error: "hermes_unreachable" }))
        .toString("base64"),
    });
  }
}

async function openTunnelSocket(socket: WebSocket, request: TunnelSocketOpen): Promise<void> {
  if (request.targetDeviceId !== deviceId) return;
  closeTunnelSocket(request.id, 1000, "replaced");
  try {
    const localUrl = await hermesAuth.websocketUrl(request.path);
    const local = new WebSocket(localUrl, {
      handshakeTimeout: localSocketConnectTimeoutMs,
      maxPayload: maxLocalSocketPayloadBytes,
    });
    localSockets.set(request.id, local);
    pendingSocketFrames.set(request.id, []);

    local.on("open", () => {
      const queued = pendingSocketFrames.get(request.id) ?? [];
      pendingSocketFrames.delete(request.id);
      for (const frame of queued) sendLocalFrame(local, frame);
    });
    local.on("message", (data, isBinary) => {
      sendControl(socket, {
        type: "tunnel.ws.frame",
        version: PROTOCOL_VERSION,
        id: request.id,
        dataBase64: rawDataToBuffer(data).toString("base64"),
        binary: isBinary,
      });
    });
    local.on("close", (code, reason) => {
      if (localSockets.get(request.id) === local) localSockets.delete(request.id);
      pendingSocketFrames.delete(request.id);
      sendControl(socket, {
        type: "tunnel.ws.close",
        version: PROTOCOL_VERSION,
        id: request.id,
        code,
        reason: reason.toString(),
      });
    });
    local.on("error", (error) => {
      console.error("Local Hermes WebSocket error", error.message);
    });
  } catch (error) {
    sendControl(socket, {
      type: "tunnel.ws.close",
      version: PROTOCOL_VERSION,
      id: request.id,
      code: 1011,
      reason: safeError(error).slice(0, 120),
    });
  }
}

function forwardTunnelFrame(frame: TunnelSocketFrame): void {
  const local = localSockets.get(frame.id);
  if (!local) return;
  if (local.readyState === WebSocket.CONNECTING) {
    const pending = pendingSocketFrames.get(frame.id);
    if (!pending) return;
    if (pending.length >= maxPendingSocketFrames) {
      closeTunnelSocket(frame.id, 1009, "pending frame capacity reached");
      return;
    }
    pending.push(frame);
    return;
  }
  sendLocalFrame(local, frame);
}

function sendLocalFrame(local: WebSocket, frame: TunnelSocketFrame): void {
  if (local.readyState !== WebSocket.OPEN) return;
  const data = Buffer.from(frame.dataBase64, "base64");
  if (local.bufferedAmount + data.length > maxLocalBufferedBytes) {
    local.close(1013, "backpressure limit reached");
    return;
  }
  local.send(frame.binary ? data : data.toString("utf8"), { binary: frame.binary });
}

function closeTunnelSocket(id: string, code?: number, reason?: string): void {
  pendingSocketFrames.delete(id);
  const local = localSockets.get(id);
  if (!local) return;
  localSockets.delete(id);
  if (local.readyState === WebSocket.OPEN || local.readyState === WebSocket.CONNECTING) {
    local.close(safeCloseCode(code), reason?.slice(0, 120));
  }
}

function closeLocalSockets(): void {
  for (const id of localSockets.keys()) closeTunnelSocket(id, 1012, "relay reconnecting");
}

async function handleCommand(socket: WebSocket, command: ChatCommand): Promise<void> {
  emit(socket, command.id, "accepted");
  try {
    if (hermesMode === "mock") {
      emit(socket, command.id, "delta", { text: `Echo from ${deviceId}: ` });
      emit(socket, command.id, "delta", { text: command.payload.input });
      emit(socket, command.id, "complete", { sessionId: command.sessionId ?? command.id });
      return;
    }

    const response = await fetch(hermesChatUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ message: command.payload.input, session_id: command.sessionId }),
      signal: AbortSignal.timeout(chatRequestTimeoutMs),
    });
    if (!response.ok) throw new Error(`Hermes returned HTTP ${response.status}`);
    const contentType = response.headers.get("content-type") ?? "";
    if (contentType.includes("text/event-stream") && response.body) {
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        emit(socket, command.id, "delta", { text: decoder.decode(value, { stream: true }) });
      }
      emit(socket, command.id, "complete");
      return;
    }
    const body = await response.text();
    emit(socket, command.id, "complete", { contentType, body });
  } catch (error) {
    emit(socket, command.id, "error", { message: safeError(error) });
  }
}

function emit(socket: WebSocket, requestId: string, event: RelayEvent["event"], data?: unknown): void {
  sendControl(socket, { type: "event", version: PROTOCOL_VERSION, requestId, event, data });
}

function sendControl(socket: WebSocket, message: WireMessage): void {
  if (socket.readyState !== WebSocket.OPEN) return;
  const encoded = encodeWireMessage(message);
  if (socket.bufferedAmount + Buffer.byteLength(encoded) > maxControlBufferedBytes) {
    console.error("Control socket backpressure limit reached; reconnecting");
    socket.close(1013, "backpressure limit reached");
    return;
  }
  socket.send(encoded);
}

function scheduleReconnect(): void {
  const delay = retryMs + Math.floor(Math.random() * 500);
  console.log(`Disconnected; reconnecting in ${delay}ms`);
  setTimeout(connect, delay);
  retryMs = Math.min(retryMs * 2, 30_000);
}

function selectResponseHeaders(headers: Headers): Record<string, string> {
  const selected: Record<string, string> = {};
  for (const name of ["content-type", "cache-control"]) {
    const value = headers.get(name);
    if (value) selected[name] = value;
  }
  return selected;
}

class HermesAuth {
  private readonly baseUrl: string;
  private readonly sessionToken?: string;
  private readonly username?: string;
  private readonly password?: string;
  private readonly cookies = new Map<string, string>();
  private loginInFlight?: Promise<boolean>;

  constructor(config: {
    baseUrl: string;
    sessionToken?: string;
    username?: string;
    password?: string;
  }) {
    this.baseUrl = config.baseUrl;
    this.sessionToken = config.sessionToken;
    this.username = config.username;
    this.password = config.password;
    if (Boolean(this.username) !== Boolean(this.password)) {
      throw new Error("HERMES_BASIC_AUTH_USERNAME and HERMES_BASIC_AUTH_PASSWORD must be configured together");
    }
  }

  async request(path: string, init: RequestInit): Promise<Response> {
    this.assertApiPath(path);
    if (this.username && this.cookies.size === 0) await this.login();
    let response = await fetch(new URL(path, this.baseUrl), this.withAuth(init));
    this.captureCookies(response.headers);
    if (response.status === 401 && this.username) {
      await this.login(true);
      response = await fetch(new URL(path, this.baseUrl), this.withAuth(init));
      this.captureCookies(response.headers);
    }
    return response;
  }

  async websocketUrl(path: string): Promise<string> {
    if (path !== "/api/ws") throw new Error("unsupported WebSocket path");
    const wsBase = this.baseUrl.replace(/^https:/, "wss:").replace(/^http:/, "ws:");
    if (this.sessionToken) {
      const url = new URL(path, wsBase);
      url.searchParams.set("token", this.sessionToken);
      return url.toString();
    }
    if (this.username) {
      const response = await this.request("/api/auth/ws-ticket", { method: "POST" });
      if (!response.ok) throw new Error(`Hermes WS ticket returned HTTP ${response.status}`);
      const payload = await response.json() as { ticket?: string };
      if (!payload.ticket) throw new Error("Hermes WS ticket response contained no ticket");
      const url = new URL(path, wsBase);
      url.searchParams.set("ticket", payload.ticket);
      return url.toString();
    }
    return new URL(path, wsBase).toString();
  }

  private async login(force = false): Promise<void> {
    if (!this.username || !this.password) return;
    if (!force && this.cookies.size > 0) return;
    if (!this.loginInFlight) {
      this.loginInFlight = this.performLogin().finally(() => {
        this.loginInFlight = undefined;
      });
    }
    const ok = await this.loginInFlight;
    if (!ok) throw new Error("Hermes Basic Auth login failed");
  }

  private async performLogin(): Promise<boolean> {
    this.cookies.clear();
    const response = await fetch(new URL("/auth/password-login", this.baseUrl), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ provider: "basic", username: this.username, password: this.password }),
      signal: AbortSignal.timeout(localRequestTimeoutMs),
    });
    this.captureCookies(response.headers);
    return response.ok;
  }

  private withAuth(init: RequestInit): RequestInit {
    const headers = new Headers(init.headers);
    headers.delete("x-hermes-session-token");
    headers.delete("cookie");
    if (this.sessionToken) headers.set("x-hermes-session-token", this.sessionToken);
    const cookie = [...this.cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
    if (cookie) headers.set("cookie", cookie);
    return {
      ...init,
      headers,
      signal: init.signal ?? AbortSignal.timeout(localRequestTimeoutMs),
    };
  }

  private captureCookies(headers: Headers): void {
    const cookieHeaders = (headers as Headers & { getSetCookie?: () => string[] }).getSetCookie?.()
      ?? (headers.get("set-cookie") ? [headers.get("set-cookie") as string] : []);
    for (const value of cookieHeaders) {
      const pair = value.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator <= 0) continue;
      this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
  }

  private assertApiPath(path: string): void {
    if (!path.startsWith("/api/")) throw new Error("unsupported Hermes path");
  }
}

const hermesAuth = new HermesAuth({
  baseUrl: hermesBaseUrl,
  sessionToken: process.env.HERMES_SESSION_TOKEN,
  username: process.env.HERMES_BASIC_AUTH_USERNAME,
  password: process.env.HERMES_BASIC_AUTH_PASSWORD,
});

connect();

function safeCloseCode(code?: number): number {
  const standard = code !== undefined && code >= 1000 && code <= 1014
    && code !== 1004 && code !== 1005 && code !== 1006;
  const application = code !== undefined && code >= 3000 && code <= 4999;
  return standard || application ? code : 1011;
}

function rawDataToBuffer(data: WebSocket.RawData): Buffer {
  if (Buffer.isBuffer(data)) return data;
  if (Array.isArray(data)) return Buffer.concat(data);
  return Buffer.from(data);
}

function safeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function requireSecret(name: string): string {
  const file = process.env[`${name}_FILE`];
  const value = process.env[name] ?? (file ? readFileSync(file, "utf8").trim() : undefined);
  if (!value || value.length < 8) throw new Error(`${name} must contain at least 8 characters`);
  return value;
}

function positiveIntEnv(name: string, fallback: number, max = 1024 * 1024 * 1024): number {
  const raw = process.env[name];
  if (raw === undefined) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0 || value > max) {
    throw new Error(`${name} must be an integer between 1 and ${max}`);
  }
  return value;
}
