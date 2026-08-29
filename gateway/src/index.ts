import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { WebSocket, WebSocketServer } from "ws";
import {
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type ChatCommand,
  type HelloMessage,
  type RelayEvent,
  type WireMessage,
} from "@hermes-remote/protocol";

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "0.0.0.0";
const appToken = requireSecret("APP_TOKEN");
const connectorToken = requireSecret("CONNECTOR_TOKEN");
const tlsCertFile = process.env.TLS_CERT_FILE;
const tlsKeyFile = process.env.TLS_KEY_FILE;

type Peer = {
  socket: WebSocket;
  role: HelloMessage["role"];
  deviceId: string;
};

const connectors = new Map<string, Peer>();
const apps = new Set<Peer>();
const requestOwners = new Map<string, Peer>();

const requestHandler = (request: IncomingMessage, response: ServerResponse): void => {
  if (request.url === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ ok: true, connectors: connectors.size }));
    return;
  }
  response.writeHead(404).end();
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

const wss = new WebSocketServer({ server, path: "/v1/connect" });

wss.on("connection", (socket) => {
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
    if (peer.role === "connector" && connectors.get(peer.deviceId) === peer) {
      connectors.delete(peer.deviceId);
      broadcastStatus(peer.deviceId, false);
    } else {
      apps.delete(peer);
    }
    for (const [requestId, owner] of requestOwners) {
      if (owner === peer) requestOwners.delete(requestId);
    }
  });
});

server.listen(port, host, () => {
  const scheme = tlsCertFile ? "https/wss" : "http/ws";
  console.log(`Hermes Remote Gateway listening on ${scheme}://${host}:${port}`);
});

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

  if (peer.role === "connector" && message.type === "event") {
    const owner = requestOwners.get(message.requestId);
    if (!owner) return;
    send(owner.socket, message);
    if (message.event === "complete" || message.event === "error") {
      requestOwners.delete(message.requestId);
    }
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
