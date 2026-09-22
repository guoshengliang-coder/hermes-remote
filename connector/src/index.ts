import { WebSocket } from "ws";
import { constants, readFileSync } from "node:fs";
import { mkdir, open, readdir, realpath, stat, unlink } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, extname, isAbsolute, resolve, sep } from "node:path";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type ChatCommand,
  type RelayEvent,
  type SessionLifecycleEvent,
  type TunnelHttpRequest,
  type TunnelSocketFrame,
  type TunnelSocketOpen,
  type WireMessage,
} from "@hermes-remote/protocol";
import { randomUUID } from "node:crypto";
import {
  AccountConnectorAuthenticator,
  loadAccountConnectorCredential,
} from "./account-connector.js";
import {
  HermesSessionObserver,
  type ObserverSocket,
} from "./session-observer-runner.js";
import { createConnectorLogger, parseConnectorLogLevel, summarizeHermesFrame } from "./connector-log.js";
import { ObserverStateStore } from "./session-observer.js";
import { loadHermesSessionToken } from "./hermes-session-token.js";
import { describeRejectedPath } from "./file-log.js";
import { tunnelCloseForApp } from "./tunnel-close.js";
import { decideOversizedFrame } from "./oversized-frame.js";
import { InFlightHttpRequests, ResponseChunkWaiters } from "./http-request-lifecycle.js";
import { displayVersion } from "./hermes-contract.js";
import { HermesAuth, boundedResponseBody, fetchHermesOpenApi } from "./hermes-auth.js";
import { contractReportResponse, tunnelHttpRoute } from "./tunnel-routes.js";
import { HermesContractMonitor } from "./hermes-contract-monitor.js";
import { resolveHermesMode, type ConnectorMode } from "./connector-config.js";

// Launchd captures stdout/stderr without timestamps, which made the 2026-09-01
// reconnect-churn investigation impossible to correlate with server-side events.
// Prefix every console line with an ISO timestamp at the single choke point.
for (const level of ["log", "warn", "error"] as const) {
  const original = console[level].bind(console);
  console[level] = (...args: unknown[]) => original(new Date().toISOString(), ...args);
}

const connectorMode = connectorModeFromEnvironment();
const gatewayUrl = process.env.GATEWAY_URL
  ?? (connectorMode === "account" ? "ws://127.0.0.1:8787/v2/connect" : "ws://127.0.0.1:8787/v1/connect");
const connectorToken = connectorMode === "legacy" ? requireSecret("CONNECTOR_TOKEN") : undefined;
const accountCredential = connectorMode === "account"
  ? loadAccountConnectorCredential(requireEnvironment("ACCOUNT_CONNECTOR_CREDENTIAL_FILE"))
  : undefined;
const accountAuthenticator = accountCredential
  ? new AccountConnectorAuthenticator(accountCredential, gatewayUrl)
  : undefined;
let deviceId = process.env.DEVICE_ID ?? "mac-mini";
const hermesMode = resolveHermesMode(process.env, connectorMode);
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
/**
 * Ceiling on one frame the Connector will **forward** from the local Hermes. Deliberately below
 * [maxWirePayloadBytes]: every frame is base64'd onto the control channel, so 12 MiB here is
 * ~16 MiB on the wire — raising it to 20 would not carry a bigger frame, it would move the failure
 * one hop later and take the whole control channel with it instead of one tunnel.
 *
 * Exceeding it is not fatal any more: see [maxLocalSocketReceiveBytes] and `oversized-frame.ts`.
 */
const maxLocalSocketPayloadBytes = positiveIntEnv("MAX_LOCAL_WS_PAYLOAD_BYTES", 12 * 1024 * 1024);
/**
 * How large a frame `ws` will accept from the local Hermes before it treats the frame as a protocol
 * violation and destroys the socket. Deliberately far above [maxLocalSocketPayloadBytes], which is
 * the *forwarding* limit: receiving an oversized frame and dropping it costs one buffer, while
 * refusing to receive it costs the whole tunnel — every other conversation, the stop button, and
 * new sessions — and costs it again on every reconnect (HG-65).
 */
const maxLocalSocketReceiveBytes = positiveIntEnv(
  "MAX_LOCAL_WS_RECEIVE_BYTES",
  64 * 1024 * 1024,
);
const maxPendingSocketFrames = positiveIntEnv("MAX_PENDING_WS_FRAMES", 256);
const maxControlBufferedBytes = positiveIntEnv("MAX_CONTROL_BUFFERED_BYTES", 24 * 1024 * 1024);
const maxLocalBufferedBytes = positiveIntEnv("MAX_LOCAL_WS_BUFFERED_BYTES", 24 * 1024 * 1024);
const httpResponseChunkBytes = Math.min(
  positiveIntEnv("HTTP_RESPONSE_CHUNK_BYTES", 256 * 1024),
  384 * 1024,
);
const httpResponseChunkAckTimeoutMs = positiveIntEnv("HTTP_RESPONSE_CHUNK_ACK_TIMEOUT_MS", 30_000);
const sessionObserverEnabled = process.env.SESSION_OBSERVER_ENABLED !== "0" && hermesMode !== "mock";
const sessionObserverStateFile = resolve(
  process.env.OBSERVER_STATE_FILE ?? resolve(homedir(), ".hermes-remote", "observer-state.json"),
);
const sessionObserverActivePollMs = positiveIntEnv("OBSERVER_ACTIVE_POLL_MS", 2_000);
const sessionObserverIdlePollMs = positiveIntEnv("OBSERVER_IDLE_POLL_MS", 20_000);
const sessionObserverRpcTimeoutMs = positiveIntEnv("OBSERVER_RPC_TIMEOUT_MS", 10_000);
const log = createConnectorLogger(parseConnectorLogLevel(process.env.CONNECTOR_LOG_LEVEL));
const localSockets = new Map<string, WebSocket>();
const pendingSocketFrames = new Map<string, TunnelSocketFrame[]>();
// Per app tunnel: when it opened and how many Hermes frames it carried, so a close line can say
// whether the phone was still attached when a run's terminal event went by.
const tunnelStats = new Map<string, { openedAt: number; framesToApp: number; framesFromApp: number; lastTerminal?: string }>();
/** The last local-socket error per tunnel, so its close can say what actually killed it. */
const localErrors = new Map<string, string>();
const inFlightHttpRequests = new InFlightHttpRequests();
const responseChunkWaiters = new ResponseChunkWaiters();
let retryMs = 1_000;
let controlSocket: WebSocket | undefined;
let controlAuthenticated = false;
let stopping = false;
let lifecycleObserver: HermesSessionObserver | undefined;

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
  controlAuthenticated = false;

  socket.on("open", () => {
    retryMs = 1_000;
    if (accountAuthenticator) {
      socket.send(encodeWireMessage(accountAuthenticator.identify()));
    } else {
      socket.send(encodeWireMessage({
        type: "hello",
        version: PROTOCOL_VERSION,
        role: "connector",
        deviceId,
        token: connectorToken!,
      }));
    }

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
    inFlightHttpRequests.abortAll("control_socket_closed");
    responseChunkWaiters.rejectAll(new Error("control_socket_closed"));
    closeLocalSockets();
    if (controlSocket === socket) controlSocket = undefined;
    if (controlSocket === undefined) controlAuthenticated = false;
    scheduleReconnect();
  });
  socket.on("error", (error) => console.error("Gateway connection error", error.message));
}

async function handleGatewayMessage(socket: WebSocket, raw: string): Promise<void> {
  const message = parseWireMessage(raw);
  if (accountAuthenticator) {
    switch (message.type) {
      case "connector.challenge":
        sendControl(socket, accountAuthenticator.authenticate(message));
        return;
      case "connector.preflight.request": {
        const result = await accountConnectorPreflight();
        sendControl(socket, {
          type: "connector.preflight.result",
          version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
          requestId: message.requestId,
          hermesReachable: result.reachable,
          ...(result.version ? { hermesVersion: result.version } : {}),
        });
        return;
      }
      case "connector.ready":
        accountAuthenticator.requireReady(message);
        deviceId = message.deviceId;
        controlAuthenticated = message.routingEnabled;
        console.log(`Connected to gateway as ${deviceId} in account mode (${message.bindingStatus})`);
        void contractMonitor.ensureFresh("relay_connected");
        if (message.routingEnabled) startLifecycleObserver();
        else setTimeout(() => socket.close(1012, "pending binding activation"), 250);
        return;
      case "error":
        throw new Error(`Gateway rejected account Connector (${message.code})`);
      default:
        if (!controlAuthenticated) throw new Error("Unexpected account Connector handshake message");
        break;
    }
  }
  switch (message.type) {
    case "hello_ack":
      console.log(`Connected to gateway as ${message.deviceId}`);
      log.info("relay.connected", { device: message.deviceId, tunnels: localSockets.size });
      controlAuthenticated = true;
      lifecycleObserver?.relayConnected();
      void contractMonitor.ensureFresh("relay_connected");
      return;
    case "session.lifecycle.ack":
      log.info("lifecycle.acked", { eventId: message.eventId });
      lifecycleObserver?.acknowledge(message.eventId);
      return;
    case "command":
      await handleCommand(socket, message);
      return;
    case "tunnel.http.request":
      await handleTunnelHttp(socket, message);
      return;
    case "tunnel.http.cancel":
      if (inFlightHttpRequests.cancel(message.requestId, message.reason)) {
        log.info("http.cancelled", { requestId: message.requestId, reason: message.reason });
      }
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
  const controller = inFlightHttpRequests.begin(request.id);
  let streamStarted = false;
  try {
    switch (tunnelHttpRoute(request.path)) {
      case "contract":
        await handleContractRequest(socket, request, controller.signal);
        return;
      case "files":
        await handleFileRequest(socket, request, controller.signal);
        return;
      case "hermes":
        break;
    }
    const response = await hermesAuth.request(request.path, {
      method: request.method,
      headers: request.headers,
      body: request.bodyBase64 ? Buffer.from(request.bodyBase64, "base64") : undefined,
      signal: controller.signal,
    });
    controller.signal.throwIfAborted();
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
          await sendResponseChunk(socket, request.id, sequence++, chunk, controller.signal);
        }
      }
    }
    sendControl(socket, {
      type: "tunnel.http.response.end",
      version: PROTOCOL_VERSION,
      requestId: request.id,
    });
  } catch (error) {
    if (controller.signal.aborted) return;
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
  } finally {
    inFlightHttpRequests.finish(request.id, controller);
  }
}

function sendResponseChunk(
  socket: WebSocket,
  requestId: string,
  sequence: number,
  data: Buffer,
  signal: AbortSignal,
): Promise<void> {
  if (socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error("control_socket_closed"));
  const waiting = responseChunkWaiters.wait(
    requestId,
    sequence,
    httpResponseChunkAckTimeoutMs,
    signal,
  );
  if (signal.aborted) return waiting;
  sendControl(socket, {
    type: "tunnel.http.response.chunk",
    version: PROTOCOL_VERSION,
    requestId,
    sequence,
    dataBase64: data.toString("base64"),
  });
  return waiting;
}

function acknowledgeResponseChunk(requestId: string, sequence: number): void {
  responseChunkWaiters.acknowledge(requestId, sequence);
}

/**
 * Connector-owned, like `/api/files`: never forwarded to Hermes. Serves the cached upstream
 * contract report so the phone can name an incompatible Hermes with a registered HR-COMPAT code
 * instead of failing later and vaguely (docs/HERMES_CONTRACT.md, "Connector contract check").
 */
async function handleContractRequest(
  socket: WebSocket,
  request: TunnelHttpRequest,
  signal: AbortSignal,
): Promise<void> {
  signal.throwIfAborted();
  const response = await contractReportResponse(
    request.method,
    () => contractMonitor.ensureFresh("app_request"),
  );
  signal.throwIfAborted();
  sendJsonResponse(socket, request.id, response.status, response.body, response.headers);
}

async function handleFileRequest(
  socket: WebSocket,
  request: TunnelHttpRequest,
  signal: AbortSignal,
): Promise<void> {
  let streamStarted = false;
  const fail = (status: number, error: string, extraHeaders: Record<string, string> = {}): void => {
    if (signal.aborted) return;
    // Log every rejection. Without this a refused download left no trace on either side: the phone
    // showed one generic message and connector.log said nothing, so diagnosing a 2026-09-05 failure
    // meant reading FILES_ROOT by hand. Never log the requested path — it would put the Mac's
    // directory layout in a file shipped with diagnostics; the extension and length are enough to
    // tell a whitelist problem from a traversal one.
    console.warn(`File request rejected status=${status} reason=${error} ${describeRejectedPath(request.path, rawQueryParameter)}`);
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
    await handleUploadRequest(socket, request, url, signal);
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
        signal.throwIfAborted();
        const chunk = Buffer.allocUnsafe(httpResponseChunkBytes);
        const { bytesRead } = await handle.read(chunk, 0, chunk.length, null);
        if (bytesRead === 0) break;
        await sendResponseChunk(socket, request.id, sequence++, chunk.subarray(0, bytesRead), signal);
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
    if (signal.aborted) return;
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

async function handleUploadRequest(
  socket: WebSocket,
  request: TunnelHttpRequest,
  url: URL,
  signal: AbortSignal,
): Promise<void> {
  if (signal.aborted) return;
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
  let writtenPath: string | undefined;
  try {
    await mkdir(uploadRoot, { recursive: true, mode: 0o700 });
    const storedName = `${randomUUID()}-${requestedName}`;
    const path = resolve(uploadRoot, storedName);
    if (!isWithinRoot(path, uploadRoot)) throw new Error("invalid_upload_path");
    const handle = await open(path, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY, 0o600);
    writtenPath = path;
    try {
      signal.throwIfAborted();
      await handle.writeFile(bytes);
    } finally {
      await handle.close();
    }
    signal.throwIfAborted();
    sendJsonResponse(socket, request.id, 201, {
      path,
      name: requestedName,
      size: bytes.length,
    });
    void trimUploadDirectory(path).catch((error) => {
      console.error("Unable to trim upload cache", safeError(error));
    });
  } catch (error) {
    if (signal.aborted) {
      if (writtenPath) await unlink(writtenPath).catch(() => undefined);
      return;
    }
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
      maxPayload: maxLocalSocketReceiveBytes,
    });
    localSockets.set(request.id, local);
    pendingSocketFrames.set(request.id, []);
    tunnelStats.set(request.id, { openedAt: Date.now(), framesToApp: 0, framesFromApp: 0 });
    log.info("tunnel.open", { tunnel: request.id, path: request.path, tunnels: localSockets.size });

    local.on("open", () => {
      const queued = pendingSocketFrames.get(request.id) ?? [];
      pendingSocketFrames.delete(request.id);
      log.debug("tunnel.local_open", { tunnel: request.id, queued: queued.length });
      for (const frame of queued) sendLocalFrame(local, frame);
    });
    local.on("message", (data, isBinary) => {
      const buffer = rawDataToBuffer(data);
      const stats = tunnelStats.get(request.id);
      if (stats) stats.framesToApp += 1;
      const decision = decideOversizedFrame(buffer, maxLocalSocketPayloadBytes);
      if (!decision.forward) {
        // The tunnel stays open. Everything else on it — other conversations, the stop button,
        // creating a new session — keeps working; only this one answer is lost, and the call that
        // asked for it gets told so instead of hanging until its 60s timeout.
        log.error("tunnel.frame_too_large", {
          tunnel: request.id,
          bytes: buffer.length,
          limitBytes: maxLocalSocketPayloadBytes,
          rpcId: decision.rpcId ?? undefined,
          answered: decision.replacement !== null,
        });
        if (decision.replacement === null) return;
        sendControl(socket, {
          type: "tunnel.ws.frame",
          version: PROTOCOL_VERSION,
          id: request.id,
          dataBase64: Buffer.from(decision.replacement, "utf8").toString("base64"),
          binary: false,
        });
        return;
      }
      if (!isBinary && log.enabled("info")) {
        // Describe the frame by its Hermes event type; the payload itself is never logged. A
        // terminal event is the line an incident reader needs: it says the run ended and which
        // tunnel (i.e. which phone socket) it was handed to.
        const summary = summarizeHermesFrame(buffer.toString("utf8"));
        if (summary.terminal) {
          if (stats) stats.lastTerminal = summary.type;
          log.info("tunnel.frame", { tunnel: request.id, type: summary.type, sessionId: summary.sessionId, bytes: buffer.length });
        } else if (summary.kind !== "other") {
          log.debug("tunnel.frame", { tunnel: request.id, type: summary.type, sessionId: summary.sessionId, bytes: buffer.length });
        }
      }
      sendControl(socket, {
        type: "tunnel.ws.frame",
        version: PROTOCOL_VERSION,
        id: request.id,
        dataBase64: buffer.toString("base64"),
        binary: isBinary,
      });
    });
    local.on("close", (code, reason) => {
      if (localSockets.get(request.id) === local) localSockets.delete(request.id);
      pendingSocketFrames.delete(request.id);
      const stats = tunnelStats.get(request.id);
      tunnelStats.delete(request.id);
      const localError = localErrors.get(request.id);
      localErrors.delete(request.id);
      // The receive ceiling, not the forward limit: a frame over the forward limit is dropped
      // above and the socket stays open, so by the time `ws` destroys a socket over payload size
      // it is the 64 MiB one that was exceeded, and naming the other number would send whoever
      // reads this close reason six hours later after the wrong limit.
      const forwarded = tunnelCloseForApp(
        code,
        reason.toString(),
        localError,
        maxLocalSocketReceiveBytes,
      );
      const forwardedCode = forwarded.code;
      const forwardedReason = forwarded.reason;
      log.info("tunnel.close", {
        tunnel: request.id,
        code: forwardedCode,
        reason: forwardedReason,
        localCode: code,
        durationMs: stats ? Date.now() - stats.openedAt : undefined,
        framesToApp: stats?.framesToApp,
        framesFromApp: stats?.framesFromApp,
        lastTerminal: stats?.lastTerminal,
        tunnels: localSockets.size,
      });
      sendControl(socket, {
        type: "tunnel.ws.close",
        version: PROTOCOL_VERSION,
        id: request.id,
        code: forwardedCode,
        reason: forwardedReason,
      });
    });
    local.on("error", (error) => {
      console.error("Local Hermes WebSocket error", error.message);
      // Keep it: `ws` destroys the socket after this, and the close that follows carries 1006 with
      // no reason — which the gateway cannot forward (safeCloseCode rejects 1006) and turns into a
      // bare 1011, an anonymous failure the phone can only reconnect into. The close handler above
      // says what happened instead.
      //
      // An oversized frame no longer reaches here: HG-65's 197 closes in 24 minutes came from one
      // 26.3 MiB `session.resume` answer against a 12 MiB ceiling, and that frame is now received
      // and dropped. What is left is a genuinely unreadable socket, or a frame over 64 MiB.
      localErrors.set(request.id, error.message);
      log.error("tunnel.local_error", {
        tunnel: request.id,
        error: error.message,
        maxPayloadBytes: maxLocalSocketReceiveBytes,
      });
    });
  } catch (error) {
    log.error("tunnel.open_failed", { tunnel: request.id, error: safeError(error) });
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
  const stats = tunnelStats.get(frame.id);
  if (stats) stats.framesFromApp += 1;
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
  // The relay (i.e. the phone's socket) went away first; the local close handler logs the summary.
  log.info("tunnel.close_by_relay", { tunnel: id, code, reason });
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

function sendControl(socket: WebSocket, message: WireMessage): boolean {
  if (socket.readyState !== WebSocket.OPEN) return false;
  const encoded = encodeWireMessage(message);
  if (socket.bufferedAmount + Buffer.byteLength(encoded) > maxControlBufferedBytes) {
    console.error("Control socket backpressure limit reached; reconnecting");
    socket.close(1013, "backpressure limit reached");
    return false;
  }
  socket.send(encoded);
  return true;
}

function scheduleReconnect(): void {
  if (stopping) return;
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

const hermesAuth = new HermesAuth({
  baseUrl: hermesBaseUrl,
  requestTimeoutMs: localRequestTimeoutMs,
  sessionToken: loadHermesSessionToken({
    inline: process.env.HERMES_SESSION_TOKEN,
    file: process.env.HERMES_SESSION_TOKEN_FILE,
  }),
  username: process.env.HERMES_BASIC_AUTH_USERNAME,
  password: process.env.HERMES_BASIC_AUTH_PASSWORD,
});

const contractMonitor = new HermesContractMonitor(
  {
    fetchOpenApi: () => fetchHermesOpenApi(hermesAuth),
    fetchStatusVersion: async () => {
      const status = await readHermesStatus();
      if (!status.reachable) throw new Error("hermes_unreachable");
      return status.version;
    },
  },
  { log: (level, kind, fields) => log[level](kind, fields) },
);

connect();
if (connectorMode === "legacy") startLifecycleObserver();
void contractMonitor.refresh("startup");

function startLifecycleObserver(): void {
  if (!sessionObserverEnabled || lifecycleObserver) return;
  lifecycleObserver = new HermesSessionObserver({
    deviceId,
    profile: process.env.HERMES_PROFILE,
    stateStore: new ObserverStateStore(sessionObserverStateFile),
    websocketUrl: () => hermesAuth.websocketUrl("/api/ws"),
    createSocket: (url) => new WebSocket(url, {
      handshakeTimeout: localSocketConnectTimeoutMs,
      maxPayload: 1024 * 1024,
    }) as unknown as ObserverSocket,
    sendLifecycle: (event: SessionLifecycleEvent): boolean => {
      const socket = controlSocket;
      const sent = socket && controlAuthenticated ? sendControl(socket, event) : false;
      log.info("lifecycle.sent", {
        eventId: event.eventId,
        kind: event.event,
        storedSessionId: event.storedSessionId,
        runtimeSessionId: event.runtimeSessionId,
        occurredAt: event.occurredAt,
        sent,
      });
      return sent;
    },
    activePollMs: sessionObserverActivePollMs,
    idlePollMs: sessionObserverIdlePollMs,
    rpcTimeoutMs: sessionObserverRpcTimeoutMs,
    log: (message) => console.log(message),
    // A fresh socket to Hermes is the only sign of a Hermes restart this process gets —
    // `hermes update` kickstarts the serve job onto new code without changing anything else here.
    onHermesConnected: () => { void contractMonitor.refresh("hermes_connected"); },
  });
  void lifecycleObserver.start().catch((error) => {
    console.error("Unable to start Hermes lifecycle observer", safeError(error));
  });
}

async function accountConnectorPreflight(): Promise<{ reachable: boolean; version?: string }> {
  const status = await readHermesStatus();
  if (status.reachable) contractMonitor.observeVersion(status.version, "preflight");
  return status;
}

async function readHermesStatus(): Promise<{ reachable: boolean; version?: string }> {
  try {
    const response = await hermesAuth.request("/api/status", { method: "GET" });
    if (!response.ok) return { reachable: false };
    const body = await boundedResponseBody(response, 64 * 1024);
    if (!body) return { reachable: true };
    const value = JSON.parse(body) as unknown;
    if (typeof value === "object" && value !== null && !Array.isArray(value)) {
      const version = displayVersion((value as Record<string, unknown>).version);
      if (version !== undefined) return { reachable: true, version };
    }
    return { reachable: true };
  } catch {
    return { reachable: false };
  }
}


function shutdown(signal: string): void {
  if (stopping) return;
  stopping = true;
  console.log(`Received ${signal}; closing Connector`);
  lifecycleObserver?.stop();
  inFlightHttpRequests.abortAll("connector_stopping");
  responseChunkWaiters.rejectAll(new Error("connector_stopping"));
  closeLocalSockets();
  controlSocket?.close(1000, "connector stopping");
  setTimeout(() => process.exit(0), 250).unref();
}

process.once("SIGTERM", () => shutdown("SIGTERM"));
process.once("SIGINT", () => shutdown("SIGINT"));

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

function requireEnvironment(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`HR-MIGRATE-001 ${name} is required`);
  return value;
}

function connectorModeFromEnvironment(): ConnectorMode {
  const value = process.env.CONNECTOR_MODE ?? "legacy";
  if (value !== "legacy" && value !== "account") {
    throw new Error("HR-MIGRATE-001 CONNECTOR_MODE must be legacy or account");
  }
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
