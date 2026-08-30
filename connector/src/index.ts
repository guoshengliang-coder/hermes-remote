import { WebSocket } from "ws";
import { constants, readFileSync } from "node:fs";
import { mkdir, open, readdir, realpath, stat, unlink } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, extname, isAbsolute, resolve, sep } from "node:path";
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
import { randomUUID } from "node:crypto";

const gatewayUrl = process.env.GATEWAY_URL ?? "ws://127.0.0.1:8787/v1/connect";
const connectorToken = requireSecret("CONNECTOR_TOKEN");
const deviceId = process.env.DEVICE_ID ?? "mac-mini";
const hermesMode = process.env.HERMES_MODE ?? "mock";
const hermesBaseUrl = (process.env.HERMES_BASE_URL ?? "http://127.0.0.1:9119").replace(/\/$/, "");
const hermesChatUrl = process.env.HERMES_CHAT_URL ?? `${hermesBaseUrl}/api/chat`;
const filesRoot = resolve(process.env.FILES_ROOT ?? homedir());
const uploadRoot = resolve(process.env.UPLOAD_ROOT ?? resolve(filesRoot, ".hermes-remote", "uploads"));
const maxUploadBytes = positiveIntEnv("MAX_UPLOAD_BYTES", 6 * 1024 * 1024);
const maxFileBytes = positiveIntEnv("MAX_FILE_BYTES", 100 * 1024 * 1024);
const maxUploadCacheBytes = positiveIntEnv("MAX_UPLOAD_CACHE_BYTES", 512 * 1024 * 1024);
const maxUploadCacheFiles = positiveIntEnv("MAX_UPLOAD_CACHE_FILES", 200, 10_000);
const uploadRetentionMs = positiveIntEnv("UPLOAD_RETENTION_HOURS", 7 * 24, 24 * 365) * 60 * 60 * 1000;
const controlHeartbeatMs = positiveIntEnv("CONTROL_HEARTBEAT_MS", 15_000);
const localRequestTimeoutMs = positiveIntEnv("LOCAL_REQUEST_TIMEOUT_MS", 60_000);
const chatRequestTimeoutMs = positiveIntEnv("CHAT_REQUEST_TIMEOUT_MS", 10 * 60_000);
const localSocketConnectTimeoutMs = positiveIntEnv("LOCAL_WS_CONNECT_TIMEOUT_MS", 15_000);
const maxWirePayloadBytes = positiveIntEnv("MAX_WIRE_PAYLOAD_BYTES", 20 * 1024 * 1024);
const maxLocalSocketPayloadBytes = positiveIntEnv("MAX_LOCAL_WS_PAYLOAD_BYTES", 12 * 1024 * 1024);
const maxPendingSocketFrames = positiveIntEnv("MAX_PENDING_WS_FRAMES", 256);
const maxControlBufferedBytes = positiveIntEnv("MAX_CONTROL_BUFFERED_BYTES", 24 * 1024 * 1024);
const maxLocalBufferedBytes = positiveIntEnv("MAX_LOCAL_WS_BUFFERED_BYTES", 24 * 1024 * 1024);
const httpResponseChunkBytes = Math.min(
  positiveIntEnv("HTTP_RESPONSE_CHUNK_BYTES", 256 * 1024),
  384 * 1024,
);
const httpResponseChunkAckTimeoutMs = positiveIntEnv("HTTP_RESPONSE_CHUNK_ACK_TIMEOUT_MS", 30_000);
const localSockets = new Map<string, WebSocket>();
const pendingSocketFrames = new Map<string, TunnelSocketFrame[]>();
const responseChunkWaiters = new Map<string, { resolve: () => void; reject: (error: Error) => void; timer: NodeJS.Timeout }>();
let retryMs = 1_000;
let controlSocket: WebSocket | undefined;

if (!isWithinRoot(uploadRoot, filesRoot)) {
  throw new Error("UPLOAD_ROOT must be inside FILES_ROOT so uploaded attachments remain downloadable");
}

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
    rejectResponseChunkWaiters(new Error("control_socket_closed"));
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
    case "tunnel.http.response.ack":
      acknowledgeResponseChunk(message.requestId, message.sequence);
      return;
    default:
      return;
  }
}

async function handleTunnelHttp(socket: WebSocket, request: TunnelHttpRequest): Promise<void> {
  if (request.targetDeviceId !== deviceId) return;
  if (request.path.startsWith("/api/files")) {
    await handleFileRequest(socket, request);
    return;
  }
  let streamStarted = false;
  try {
    const response = await hermesAuth.request(request.path, {
      method: request.method,
      headers: request.headers,
      body: request.bodyBase64 ? Buffer.from(request.bodyBase64, "base64") : undefined,
    });
    sendControl(socket, {
      type: "tunnel.http.response.start",
      version: PROTOCOL_VERSION,
      requestId: request.id,
      status: response.status,
      headers: selectResponseHeaders(response.headers),
    });
    streamStarted = true;
    let sequence = 0;
    if (response.body) {
      const reader = response.body.getReader();
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        const buffer = Buffer.from(value);
        for (let offset = 0; offset < buffer.length; offset += httpResponseChunkBytes) {
          const chunk = buffer.subarray(offset, Math.min(offset + httpResponseChunkBytes, buffer.length));
          await sendResponseChunk(socket, request.id, sequence++, chunk);
        }
      }
    }
    sendControl(socket, {
      type: "tunnel.http.response.end",
      version: PROTOCOL_VERSION,
      requestId: request.id,
    });
  } catch (error) {
    console.error("Local Hermes HTTP error", safeError(error));
    if (streamStarted) {
      sendControl(socket, {
        type: "tunnel.http.response.end",
        version: PROTOCOL_VERSION,
        requestId: request.id,
        error: "hermes_stream_failed",
      });
      return;
    }
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

function sendResponseChunk(
  socket: WebSocket,
  requestId: string,
  sequence: number,
  data: Buffer,
): Promise<void> {
  if (socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error("control_socket_closed"));
  const key = `${requestId}:${sequence}`;
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      responseChunkWaiters.delete(key);
      reject(new Error("response_chunk_ack_timeout"));
    }, httpResponseChunkAckTimeoutMs);
    responseChunkWaiters.set(key, { resolve, reject, timer });
    sendControl(socket, {
      type: "tunnel.http.response.chunk",
      version: PROTOCOL_VERSION,
      requestId,
      sequence,
      dataBase64: data.toString("base64"),
    });
  });
}

function acknowledgeResponseChunk(requestId: string, sequence: number): void {
  const key = `${requestId}:${sequence}`;
  const waiter = responseChunkWaiters.get(key);
  if (!waiter) return;
  responseChunkWaiters.delete(key);
  clearTimeout(waiter.timer);
  waiter.resolve();
}

function rejectResponseChunkWaiters(error: Error): void {
  for (const waiter of responseChunkWaiters.values()) {
    clearTimeout(waiter.timer);
    waiter.reject(error);
  }
  responseChunkWaiters.clear();
}

async function handleFileRequest(socket: WebSocket, request: TunnelHttpRequest): Promise<void> {
  let streamStarted = false;
  const fail = (status: number, error: string, extraHeaders: Record<string, string> = {}): void => {
    sendFileError(socket, request.id, status, error, extraHeaders);
  };
  let url: URL;
  try {
    url = new URL(request.path, "http://connector.local");
  } catch {
    fail(400, "invalid_path");
    return;
  }
  if (url.pathname === "/api/files/upload") {
    await handleUploadRequest(socket, request, url);
    return;
  }
  if (request.method.toUpperCase() !== "GET") {
    fail(405, "method_not_allowed", { allow: "GET" });
    return;
  }
  let requestedPath: string;
  try {
    if (url.pathname !== "/api/files") { fail(404, "not_found"); return; }
    const rawPath = rawQueryParameter(request.path, "path");
    if (rawPath === undefined || rawPath.length === 0) {
      fail(400, "invalid_path");
      return;
    }
    requestedPath = decodeURIComponent(rawPath.replace(/\+/g, " "));
  } catch {
    fail(400, "invalid_path");
    return;
  }

  if (requestedPath.includes("\0")
      || !isAbsolute(requestedPath)
      || requestedPath.split(/[\\/]+/).includes("..")) {
    fail(400, "invalid_path");
    return;
  }

  try {
    const canonicalRoot = await realpath(filesRoot);
    const resolvedPath = resolve(requestedPath);
    if (!isWithinRoot(resolvedPath, filesRoot)) {
      fail(403, "forbidden");
      return;
    }

    const canonicalPath = await realpath(resolvedPath);
    if (!isWithinRoot(canonicalPath, canonicalRoot)) {
      fail(403, "forbidden");
      return;
    }

    const noFollow = constants.O_NOFOLLOW ?? 0;
    const handle = await open(canonicalPath, constants.O_RDONLY | noFollow);
    try {
      const metadata = await handle.stat();
      if (!metadata.isFile()) { fail(400, "invalid_file"); return; }
      if (metadata.size > maxFileBytes) { fail(413, "file_too_large"); return; }

      sendControl(socket, {
        type: "tunnel.http.response.start",
        version: PROTOCOL_VERSION,
        requestId: request.id,
        status: 200,
        headers: {
          "content-type": contentTypeFor(canonicalPath),
          "content-length": String(metadata.size),
          "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(basename(canonicalPath))}`,
        },
      });
      streamStarted = true;
      let sequence = 0;
      while (true) {
        const chunk = Buffer.allocUnsafe(httpResponseChunkBytes);
        const { bytesRead } = await handle.read(chunk, 0, chunk.length, null);
        if (bytesRead === 0) break;
        await sendResponseChunk(socket, request.id, sequence++, chunk.subarray(0, bytesRead));
      }
      sendControl(socket, {
        type: "tunnel.http.response.end",
        version: PROTOCOL_VERSION,
        requestId: request.id,
      });
    } finally {
      await handle.close();
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (streamStarted) {
      sendControl(socket, {
        type: "tunnel.http.response.end",
        version: PROTOCOL_VERSION,
        requestId: request.id,
        error: "file_read_failed",
      });
      return;
    }
    if (code === "ENOENT" || code === "ENOTDIR") { fail(404, "not_found"); return; }
    if (code === "EACCES" || code === "EPERM") { fail(403, "forbidden"); return; }
    console.error("File request failed", code ?? "unknown_error");
    fail(500, "file_read_failed");
  }
}

async function handleUploadRequest(socket: WebSocket, request: TunnelHttpRequest, url: URL): Promise<void> {
  if (request.method.toUpperCase() !== "POST") {
    sendFileError(socket, request.id, 405, "method_not_allowed", { allow: "POST" });
    return;
  }
  const bytes = request.bodyBase64 ? Buffer.from(request.bodyBase64, "base64") : Buffer.alloc(0);
  if (bytes.length === 0) {
    sendFileError(socket, request.id, 400, "empty_file");
    return;
  }
  if (bytes.length > maxUploadBytes) {
    sendFileError(socket, request.id, 413, "file_too_large");
    return;
  }
  const requestedName = (url.searchParams.get("name") ?? "attachment")
    .replace(/[\u0000-\u001f\u007f/\\]/g, "_")
    .slice(0, 160) || "attachment";
  try {
    await mkdir(uploadRoot, { recursive: true, mode: 0o700 });
    const storedName = `${randomUUID()}-${requestedName}`;
    const path = resolve(uploadRoot, storedName);
    if (!isWithinRoot(path, uploadRoot)) throw new Error("invalid_upload_path");
    const handle = await open(path, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
    try {
      await handle.writeFile(bytes);
    } finally {
      await handle.close();
    }
    sendJsonResponse(socket, request.id, 201, {
      path,
      name: requestedName,
      size: bytes.length,
    });
    void trimUploadDirectory(path).catch((error) => {
      console.error("Unable to trim upload cache", safeError(error));
    });
  } catch (error) {
    console.error("File upload failed", safeError(error));
    sendFileError(socket, request.id, 500, "file_upload_failed");
  }
}

/** Keep transient phone uploads bounded without ever removing the file just written. */
async function trimUploadDirectory(protectedPath: string): Promise<void> {
  const now = Date.now();
  const entries = await readdir(uploadRoot);
  const files = (await Promise.all(entries.map(async (name) => {
    const path = resolve(uploadRoot, name);
    if (!isWithinRoot(path, uploadRoot)) return undefined;
    const metadata = await stat(path).catch(() => undefined);
    return metadata?.isFile() ? { path, size: metadata.size, modifiedAt: metadata.mtimeMs } : undefined;
  }))).filter((entry): entry is { path: string; size: number; modifiedAt: number } => entry !== undefined)
    .sort((left, right) => right.modifiedAt - left.modifiedAt);

  let retainedBytes = 0;
  let retainedFiles = 0;
  for (const file of files) {
    if (file.path === protectedPath) {
      retainedBytes += file.size;
      retainedFiles += 1;
      continue;
    }
    const expired = now - file.modifiedAt > uploadRetentionMs;
    const overCapacity = retainedFiles >= maxUploadCacheFiles
      || retainedBytes + file.size > maxUploadCacheBytes;
    if (expired || overCapacity) {
      await unlink(file.path).catch(() => undefined);
    } else {
      retainedBytes += file.size;
      retainedFiles += 1;
    }
  }
}

function rawQueryParameter(path: string, name: string): string | undefined {
  const query = path.split("?", 2)[1]?.split("#", 1)[0];
  if (query === undefined) return undefined;
  for (const part of query.split("&")) {
    const separator = part.indexOf("=");
    const rawName = separator >= 0 ? part.slice(0, separator) : part;
    if (decodeURIComponent(rawName.replace(/\+/g, " ")) === name) {
      return separator >= 0 ? part.slice(separator + 1) : "";
    }
  }
  return undefined;
}

function isWithinRoot(candidate: string, root: string): boolean {
  return candidate === root || candidate.startsWith(`${root}${sep}`);
}

function sendFileError(
  socket: WebSocket,
  requestId: string,
  status: number,
  error: string,
  extraHeaders: Record<string, string> = {},
): void {
  sendJsonResponse(socket, requestId, status, { error }, extraHeaders);
}

function sendJsonResponse(
  socket: WebSocket,
  requestId: string,
  status: number,
  value: Record<string, unknown>,
  extraHeaders: Record<string, string> = {},
): void {
  const body = Buffer.from(JSON.stringify(value));
  sendControl(socket, {
    type: "tunnel.http.response",
    version: PROTOCOL_VERSION,
    requestId,
    status,
    headers: {
      "content-type": "application/json",
      "content-length": String(body.length),
      ...extraHeaders,
    },
    bodyBase64: body.toString("base64"),
  });
}

function contentTypeFor(path: string): string {
  const types: Record<string, string> = {
    ".txt": "text/plain; charset=utf-8",
    ".md": "text/markdown; charset=utf-8",
    ".csv": "text/csv; charset=utf-8",
    ".log": "text/plain; charset=utf-8",
    ".json": "application/json",
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
    ".mp3": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".wav": "audio/wav",
    ".mp4": "video/mp4",
    ".mov": "video/quicktime",
    ".doc": "application/msword",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xls": "application/vnd.ms-excel",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".zip": "application/zip",
  };
  return types[extname(path).toLowerCase()] ?? "application/octet-stream";
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
  for (const name of ["content-type", "content-length", "content-disposition", "cache-control"]) {
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
