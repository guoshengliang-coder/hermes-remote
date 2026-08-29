import { randomUUID, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { WebSocket, WebSocketServer } from "ws";
import {
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type HelloMessage,
  type WireMessage,
} from "@hermes-remote/protocol";

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "0.0.0.0";
const defaultDeviceId = process.env.DEFAULT_DEVICE_ID ?? "mac-mini";
const appToken = requireSecret("APP_TOKEN");
const connectorToken = requireSecret("CONNECTOR_TOKEN");
const tlsCertFile = process.env.TLS_CERT_FILE;
const tlsKeyFile = process.env.TLS_KEY_FILE;
const maxBodyBytes = Number(process.env.MAX_BODY_BYTES ?? 10 * 1024 * 1024);
const requestTimeoutMs = Number(process.env.REQUEST_TIMEOUT_MS ?? 60_000);
const maxPendingRequests = Number(process.env.MAX_PENDING_REQUESTS ?? 128);
const maxWebSocketTunnels = Number(process.env.MAX_WS_TUNNELS ?? 32);

type Peer = {
  socket: WebSocket;
  role: HelloMessage["role"];
  deviceId: string;
};

type PendingHttp = {
  response: ServerResponse;
  deviceId: string;
  timer: NodeJS.Timeout;
};

type AppTunnel = {
  socket: WebSocket;
  deviceId: string;
};

const connectors = new Map<string, Peer>();
const apps = new Set<Peer>();
const requestOwners = new Map<string, Peer>();
const pendingHttp = new Map<string, PendingHttp>();
const appTunnels = new Map<string, AppTunnel>();

const requestHandler = (request: IncomingMessage, response: ServerResponse): void => {
  void handleHttpRequest(request, response).catch((error) => {
    console.error("HTTP relay failure", error);
    if (!response.headersSent) sendHttpError(response, 500, "relay_error");
    else response.end();
  });
};

if (Boolean(tlsCertFile) !== Boolean(tlsKeyFile)) {
  throw new Error("TLS_CERT_FILE and TLS_KEY_FILE must be configured together");
}

const server = tlsCertFile && tlsKeyFile
  ? createHttpsServer(
      { cert: readFileSync(tlsCertFile), key: readFileSync(tlsKeyFile) },
      requestHandler,
    )
  : createServer(requestHandler);

const controlWss = new WebSocketServer({ noServer: true });
const appWss = new WebSocketServer({ noServer: true });

server.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  if (url.pathname === "/v1/connect") {
    controlWss.handleUpgrade(request, socket, head, (webSocket) => {
      controlWss.emit("connection", webSocket, request);
    });
    return;
  }

  if (url.pathname === "/api/ws") {
    const token = url.searchParams.get("token") ?? firstHeader(request, "x-hermes-session-token");
    if (!token || !safeEqual(token, appToken)) {
      rejectUpgrade(socket, 401, "Unauthorized");
      return;
    }
    const deviceId = url.searchParams.get("device_id") ?? defaultDeviceId;
    if (!connectors.has(deviceId)) {
      rejectUpgrade(socket, 503, "Mac connector offline");
      return;
    }
    if (appTunnels.size >= maxWebSocketTunnels) {
      rejectUpgrade(socket, 503, "Tunnel capacity reached");
      return;
    }
    appWss.handleUpgrade(request, socket, head, (webSocket) => {
      appWss.emit("connection", webSocket, request, deviceId);
    });
    return;
  }

  rejectUpgrade(socket, 404, "Not Found");
});

controlWss.on("connection", (socket) => {
  let peer: Peer | undefined;
  const authTimer = setTimeout(() => socket.close(4401, "authentication timeout"), 5_000);

  socket.on("message", (data) => {
    let message: WireMessage;
    try {
      message = parseWireMessage(data.toString());
    } catch (error) {
      send(socket, errorMessage("bad_message", String(error)));
      return;
    }

    if (!peer) {
      if (message.type !== "hello" || !authenticate(message)) {
        socket.close(4401, "unauthorized");
        return;
      }
      clearTimeout(authTimer);
      peer = { socket, role: message.role, deviceId: message.deviceId };
      register(peer);
      send(socket, {
        type: "hello_ack",
        version: PROTOCOL_VERSION,
        deviceId: message.deviceId,
      });
      if (peer.role === "app") {
        for (const deviceId of connectors.keys()) sendStatus(peer, deviceId, true);
      }
      return;
    }

    route(peer, message);
  });

  socket.on("close", () => {
    clearTimeout(authTimer);
    if (!peer) return;
    unregister(peer);
  });
});

appWss.on("connection", (socket: WebSocket, _request: IncomingMessage, deviceId: string) => {
  const connector = connectors.get(deviceId);
  if (!connector) {
    socket.close(1013, "Mac connector offline");
    return;
  }
  const id = randomUUID();
  appTunnels.set(id, { socket, deviceId });
  send(connector.socket, {
    type: "tunnel.ws.open",
    version: PROTOCOL_VERSION,
    id,
    targetDeviceId: deviceId,
    path: "/api/ws",
  });

  socket.on("message", (data, isBinary) => {
    const current = connectors.get(deviceId);
    if (!current) {
      socket.close(1013, "Mac connector offline");
      return;
    }
    send(current.socket, {
      type: "tunnel.ws.frame",
      version: PROTOCOL_VERSION,
      id,
      dataBase64: rawDataToBuffer(data).toString("base64"),
      binary: isBinary,
    });
  });

  socket.on("close", (code, reason) => {
    appTunnels.delete(id);
    const current = connectors.get(deviceId);
    if (current) {
      send(current.socket, {
        type: "tunnel.ws.close",
        version: PROTOCOL_VERSION,
        id,
        code,
        reason: reason.toString(),
      });
    }
  });
});

server.listen(port, host, () => {
  const scheme = tlsCertFile ? "https/wss" : "http/ws";
  console.log(`Hermes Remote Gateway listening on ${scheme}://${host}:${port}`);
});

async function handleHttpRequest(request: IncomingMessage, response: ServerResponse): Promise<void> {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  if (url.pathname === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ ok: true, connectors: connectors.size }));
    return;
  }

  if (!url.pathname.startsWith("/api/")) {
    sendHttpError(response, 404, "not_found");
    return;
  }

  const token = firstHeader(request, "x-hermes-session-token");
  if (!token || !safeEqual(token, appToken)) {
    sendHttpError(response, 401, "unauthorized");
    return;
  }

  const deviceId = firstHeader(request, "x-hermes-device-id") ?? defaultDeviceId;
  const connector = connectors.get(deviceId);
  if (!connector) {
    sendHttpError(response, 503, "device_offline");
    return;
  }
  if (pendingHttp.size >= maxPendingRequests) {
    sendHttpError(response, 503, "relay_capacity_reached");
    return;
  }

  let body: Buffer;
  try {
    body = await readRequestBody(request, maxBodyBytes);
  } catch {
    sendHttpError(response, 413, "request_too_large");
    return;
  }

  const id = randomUUID();
  const timer = setTimeout(() => {
    const pending = pendingHttp.get(id);
    if (!pending) return;
    pendingHttp.delete(id);
    sendHttpError(pending.response, 504, "connector_timeout");
  }, requestTimeoutMs);
  pendingHttp.set(id, { response, deviceId, timer });
  request.on("aborted", () => clearPendingHttp(id));

  send(connector.socket, {
    type: "tunnel.http.request",
    version: PROTOCOL_VERSION,
    id,
    targetDeviceId: deviceId,
    method: request.method ?? "GET",
    path: `${url.pathname}${url.search}`,
    headers: selectRequestHeaders(request),
    bodyBase64: body.length > 0 ? body.toString("base64") : undefined,
  });
}

function route(peer: Peer, message: WireMessage): void {
  if (peer.role === "app" && message.type === "command") {
    const connector = connectors.get(message.targetDeviceId);
    if (!connector) {
      send(peer.socket, errorMessage("device_offline", "Target Mac is offline", message.id));
      return;
    }
    requestOwners.set(message.id, peer);
    send(connector.socket, message);
    return;
  }

  if (peer.role !== "connector") {
    send(peer.socket, errorMessage("forbidden_message", "Message is not valid for this peer"));
    return;
  }

  if (message.type === "event") {
    const owner = requestOwners.get(message.requestId);
    if (!owner) return;
    send(owner.socket, message);
    if (message.event === "complete" || message.event === "error") {
      requestOwners.delete(message.requestId);
    }
    return;
  }

  if (message.type === "tunnel.http.response") {
    const pending = pendingHttp.get(message.requestId);
    if (!pending || pending.deviceId !== peer.deviceId) return;
    clearPendingHttp(message.requestId);
    const body = message.bodyBase64 ? Buffer.from(message.bodyBase64, "base64") : Buffer.alloc(0);
    pending.response.writeHead(message.status, selectResponseHeaders(message.headers));
    pending.response.end(body);
    return;
  }

  if (message.type === "tunnel.ws.frame") {
    const tunnel = appTunnels.get(message.id);
    if (!tunnel || tunnel.deviceId !== peer.deviceId) return;
    if (tunnel.socket.readyState === WebSocket.OPEN) {
      const data = Buffer.from(message.dataBase64, "base64");
      tunnel.socket.send(message.binary ? data : data.toString("utf8"), { binary: message.binary });
    }
    return;
  }

  if (message.type === "tunnel.ws.close") {
    const tunnel = appTunnels.get(message.id);
    if (!tunnel || tunnel.deviceId !== peer.deviceId) return;
    appTunnels.delete(message.id);
    tunnel.socket.close(safeCloseCode(message.code), message.reason?.slice(0, 120));
    return;
  }

  send(peer.socket, errorMessage("forbidden_message", "Message is not valid for this peer"));
}

function authenticate(message: HelloMessage): boolean {
  const expected = message.role === "app" ? appToken : connectorToken;
  return safeEqual(message.token, expected) && message.deviceId.length > 0;
}

function register(peer: Peer): void {
  if (peer.role === "connector") {
    connectors.get(peer.deviceId)?.socket.close(4409, "replaced by a new connection");
    connectors.set(peer.deviceId, peer);
    broadcastStatus(peer.deviceId, true);
  } else {
    apps.add(peer);
  }
}

function unregister(peer: Peer): void {
  if (peer.role === "connector" && connectors.get(peer.deviceId) === peer) {
    connectors.delete(peer.deviceId);
    broadcastStatus(peer.deviceId, false);
    failDeviceRequests(peer.deviceId);
  } else {
    apps.delete(peer);
  }
  for (const [requestId, owner] of requestOwners) {
    if (owner === peer) requestOwners.delete(requestId);
  }
}

function failDeviceRequests(deviceId: string): void {
  for (const [id, pending] of pendingHttp) {
    if (pending.deviceId !== deviceId) continue;
    clearPendingHttp(id);
    sendHttpError(pending.response, 502, "connector_disconnected");
  }
  for (const [id, tunnel] of appTunnels) {
    if (tunnel.deviceId !== deviceId) continue;
    appTunnels.delete(id);
    tunnel.socket.close(1013, "Mac connector disconnected");
  }
}

function clearPendingHttp(id: string): void {
  const pending = pendingHttp.get(id);
  if (!pending) return;
  clearTimeout(pending.timer);
  pendingHttp.delete(id);
}

function broadcastStatus(deviceId: string, online: boolean): void {
  for (const app of apps) sendStatus(app, deviceId, online);
}

function sendStatus(peer: Peer, deviceId: string, online: boolean): void {
  send(peer.socket, { type: "device_status", version: PROTOCOL_VERSION, deviceId, online });
}

function send(socket: WebSocket, message: WireMessage): void {
  if (socket.readyState === WebSocket.OPEN) socket.send(encodeWireMessage(message));
}

function errorMessage(code: string, message: string, requestId?: string): WireMessage {
  return { type: "error", version: PROTOCOL_VERSION, code, message, requestId };
}

function selectRequestHeaders(request: IncomingMessage): Record<string, string> {
  const selected: Record<string, string> = {};
  for (const name of ["accept", "content-type"]) {
    const value = firstHeader(request, name);
    if (value) selected[name] = value;
  }
  return selected;
}

function selectResponseHeaders(headers: Record<string, string>): Record<string, string> {
  const selected: Record<string, string> = {};
  for (const name of ["content-type", "cache-control"]) {
    const value = headers[name];
    if (value) selected[name] = value;
  }
  return selected;
}

function firstHeader(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function readRequestBody(request: IncomingMessage, limit: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    request.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("request_too_large"));
        request.destroy();
        return;
      }
      chunks.push(Buffer.from(chunk));
    });
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

function sendHttpError(response: ServerResponse, status: number, code: string): void {
  if (response.writableEnded) return;
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: code }));
}

function rejectUpgrade(socket: NodeJS.WritableStream, status: number, message: string): void {
  socket.write(`HTTP/1.1 ${status} ${message}\r\nConnection: close\r\n\r\n`);
  if ("destroy" in socket && typeof socket.destroy === "function") socket.destroy();
}

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

function safeEqual(actual: string, expected: string): boolean {
  const left = Buffer.from(actual);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

function requireSecret(name: string): string {
  const value = process.env[name];
  if (!value || value.length < 8) throw new Error(`${name} must contain at least 8 characters`);
  return value;
}
