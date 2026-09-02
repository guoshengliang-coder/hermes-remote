import { randomUUID, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { WebSocket, WebSocketServer } from "ws";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type HelloMessage,
  type WireMessage,
} from "@hermes-remote/protocol";
import { LifecycleEventStore } from "./lifecycle-event-store.js";
import { CommandBroker } from "./command-broker.js";
import { loadGatewayConfig } from "./config.js";
import {
  accountRoutingKey,
  InMemoryConnectorRegistry,
  legacyRoutingKey,
} from "./connector-registry.js";
import {
  firstHeader,
  sendHttpError,
  sendJson,
} from "./http-utils.js";
import { GatewayHttpRouter } from "./gateway-http-router.js";
import { HttpTunnelBroker } from "./http-tunnel-broker.js";
import { WebSocketTunnelBroker } from "./websocket-tunnel-broker.js";
import { rejectUpgrade } from "./websocket-utils.js";
import { createAccountRuntime } from "./account/account-runtime.js";
import { AccountModeError, accountErrors } from "./account/model.js";
import type { BindingProofMaterial } from "./account/account-control-model.js";

const {
  port,
  host,
  defaultDeviceId,
  appToken,
  connectorToken,
  tlsCertFile,
  tlsKeyFile,
  maxBodyBytes,
  requestTimeoutMs,
  maxPendingRequests,
  maxWebSocketTunnels,
  maxControlConnections,
  maxUnauthenticatedAccountConnectors,
  maxUnauthenticatedAccountConnectorsPerIp,
  maxWirePayloadBytes,
  maxAppPayloadBytes,
  maxSocketBufferedBytes,
  lifecycleEventStoreFile,
  maxLifecycleEvents,
} = loadGatewayConfig(process.env);
const lifecycleEvents = new LifecycleEventStore(lifecycleEventStoreFile, maxLifecycleEvents);
const accountRuntime = createAccountRuntime(process.env);

type Peer = {
  socket: WebSocket;
  role: HelloMessage["role"];
  deviceId: string;
  routingKey: string;
  mode: "legacy" | "account";
  accountId?: string;
  binding?: BindingProofMaterial;
};

const connectorRegistry = new InMemoryConnectorRegistry<Peer>();
const apps = new Set<Peer>();
const unauthenticatedAccountConnectorsByIp = new Map<string, number>();
const commands = new CommandBroker<Peer, Peer>(
  maxPendingRequests,
  requestTimeoutMs,
  send,
  (deviceId) => connectorRegistry.getLegacy(deviceId),
);
const httpTunnels = new HttpTunnelBroker(
  maxBodyBytes,
  maxPendingRequests,
  requestTimeoutMs,
  send,
);
const webSocketTunnels = new WebSocketTunnelBroker(
  maxWebSocketTunnels,
  maxSocketBufferedBytes,
  send,
  routingPeer,
);
const httpRouter = new GatewayHttpRouter({
  accountController: accountRuntime.controller,
  accountControl: accountRuntime.gatewayControl,
  appToken,
  defaultDeviceId,
  maxBodyBytes,
  connectorRegistry,
  lifecycleEvents,
  httpTunnels,
  resolveAccountConnector,
  sendAccountError: sendAccountHttpError,
  tokensEqual: safeEqual,
});

const requestHandler = (request: IncomingMessage, response: ServerResponse): void => {
  void httpRouter.handle(request, response).catch((error) => {
    console.error("HTTP relay failure", error);
    if (!response.headersSent) sendHttpError(response, 500, "relay_error");
    else response.end();
  });
};

const server = tlsCertFile && tlsKeyFile
  ? createHttpsServer(
      { cert: readFileSync(tlsCertFile), key: readFileSync(tlsKeyFile) },
      requestHandler,
    )
  : createServer(requestHandler);

server.headersTimeout = 15_000;
server.requestTimeout = requestTimeoutMs + 5_000;
server.keepAliveTimeout = 5_000;
server.maxHeadersCount = 64;

const controlWss = new WebSocketServer({ noServer: true, maxPayload: maxWirePayloadBytes });
const accountControlWss = new WebSocketServer({ noServer: true, maxPayload: maxWirePayloadBytes });
const appWss = new WebSocketServer({ noServer: true, maxPayload: maxAppPayloadBytes });

server.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  if (url.pathname === "/v1/connect") {
    if (controlWss.clients.size + accountControlWss.clients.size >= maxControlConnections) {
      rejectUpgrade(socket, 503, "Control connection capacity reached");
      return;
    }
    controlWss.handleUpgrade(request, socket, head, (webSocket) => {
      controlWss.emit("connection", webSocket, request);
    });
    return;
  }

  if (url.pathname === "/v2/connect") {
    const sourceIp = request.socket.remoteAddress ?? "unknown";
    const unauthenticatedTotal = [...unauthenticatedAccountConnectorsByIp.values()]
      .reduce((sum, count) => sum + count, 0);
    if (!accountRuntime.gatewayControl) {
      rejectUpgrade(socket, 503, "Account Connector is disabled");
      return;
    }
    if (controlWss.clients.size + accountControlWss.clients.size >= maxControlConnections
        || unauthenticatedTotal >= maxUnauthenticatedAccountConnectors
        || (unauthenticatedAccountConnectorsByIp.get(sourceIp) ?? 0)
          >= maxUnauthenticatedAccountConnectorsPerIp) {
      rejectUpgrade(socket, 503, "Control connection capacity reached");
      return;
    }
    accountControlWss.handleUpgrade(request, socket, head, (webSocket) => {
      accountControlWss.emit("connection", webSocket, request, sourceIp);
    });
    return;
  }

  if (url.pathname === "/api/ws") {
    void authorizeAppWebSocket(request, url).then((connector) => {
      if (webSocketTunnels.atCapacity) {
        rejectUpgrade(socket, 503, "Tunnel capacity reached");
        return;
      }
      appWss.handleUpgrade(request, socket, head, (webSocket) => {
        appWss.emit("connection", webSocket, request, connector);
      });
    }).catch((error) => rejectAccountUpgrade(socket, error));
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
      socket.close(1008, "invalid message");
      return;
    }

    try {
      if (!peer) {
        if (message.type !== "hello" || !authenticate(message)) {
          socket.close(4401, "unauthorized");
          return;
        }
        clearTimeout(authTimer);
        peer = {
          socket,
          role: message.role,
          deviceId: message.deviceId,
          routingKey: legacyRoutingKey(message.deviceId),
          mode: "legacy",
        };
        register(peer);
        send(socket, {
          type: "hello_ack",
          version: PROTOCOL_VERSION,
          deviceId: message.deviceId,
        });
        if (peer.role === "app") {
          for (const deviceId of connectorRegistry.legacyDeviceIds()) sendStatus(peer, deviceId, true);
        }
        return;
      }

      route(peer, message);
    } catch (error) {
      console.error("Control message failure", safeError(error));
      send(socket, errorMessage("bad_message", "Unable to process message"));
      socket.close(1008, "invalid message");
    }
  });

  socket.on("close", () => {
    clearTimeout(authTimer);
    if (!peer) return;
    unregister(peer);
  });
});

accountControlWss.on("connection", (
  socket: WebSocket,
  _request: IncomingMessage,
  sourceIp: string,
) => {
  const control = accountRuntime.gatewayControl;
  if (!control) {
    socket.close(1013, "account Connector disabled");
    return;
  }
  incrementCount(unauthenticatedAccountConnectorsByIp, sourceIp);
  let countedAsUnauthenticated = true;
  let phase: "identify" | "authenticate" | "preflight" | "ready" | "processing" = "identify";
  let challenge: Awaited<ReturnType<typeof control.issueConnectorChallenge>> | undefined;
  let material: BindingProofMaterial | undefined;
  let peer: Peer | undefined;
  let preflightRequestId: string | undefined;
  let preflightStartedAt = 0;
  let preflightTimer: NodeJS.Timeout | undefined;
  const authTimer = setTimeout(() => socket.close(4401, "authentication timeout"), 5_000);

  const releaseUnauthenticatedSlot = (): void => {
    if (!countedAsUnauthenticated) return;
    countedAsUnauthenticated = false;
    decrementCount(unauthenticatedAccountConnectorsByIp, sourceIp);
  };

  socket.on("message", (data) => {
    let message: WireMessage;
    try {
      message = parseWireMessage(data.toString());
    } catch {
      socket.close(1008, "invalid message");
      return;
    }
    if (phase === "ready" && peer) {
      try {
        route(peer, message);
      } catch (error) {
        console.error("Account control message failure", safeError(error));
        socket.close(1008, "invalid message");
      }
      return;
    }
    if (phase === "processing") {
      socket.close(1008, "unexpected concurrent handshake message");
      return;
    }

    if (phase === "identify" && message.type === "connector.identify") {
      phase = "processing";
      void control.issueConnectorChallenge(message).then((issued) => {
        challenge = issued;
        phase = "authenticate";
        send(socket, {
          type: "connector.challenge",
          version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
          ...issued,
        });
      }).catch(() => socket.close(4401, "binding proof failed"));
      return;
    }

    if (phase === "authenticate" && challenge && message.type === "connector.authenticate") {
      phase = "processing";
      void control.authenticateConnector(message).then((authenticated) => {
        material = authenticated;
        releaseUnauthenticatedSlot();
        clearTimeout(authTimer);
        preflightRequestId = randomUUID();
        preflightStartedAt = Date.now();
        phase = "preflight";
        preflightTimer = setTimeout(() => socket.close(4408, "preflight timeout"), 5_000);
        send(socket, {
          type: "connector.preflight.request",
          version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
          requestId: preflightRequestId,
          sentAt: new Date(preflightStartedAt).toISOString(),
        });
      }).catch(() => socket.close(4401, "binding proof failed"));
      return;
    }

    if (phase === "preflight" && material && message.type === "connector.preflight.result"
        && message.requestId === preflightRequestId) {
      phase = "processing";
      if (preflightTimer) clearTimeout(preflightTimer);
      const latencyMs = Math.min(60_000, Math.max(0, Date.now() - preflightStartedAt));
      void control.recordConnectorHealth(material, {
        hermesReachable: message.hermesReachable,
        ...(message.hermesVersion ? { hermesVersion: message.hermesVersion } : {}),
        gatewayLatencyMs: latencyMs,
        endToEndHealthy: message.hermesReachable,
      }).then((recorded) => {
        if (!recorded) {
          socket.close(4401, "binding changed during preflight");
          return;
        }
        peer = {
          socket,
          role: "connector",
          deviceId: material!.deviceId,
          routingKey: accountRoutingKey(material!.id),
          mode: "account",
          accountId: material!.accountId,
          binding: material!,
        };
        registerAccountConnector(peer);
        phase = "ready";
        send(socket, {
          type: "connector.ready",
          version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
          bindingId: material!.id,
          generation: material!.generation,
          deviceId: material!.deviceId,
          bindingStatus: material!.status,
          routingEnabled: material!.status === "active",
        });
      }).catch(() => socket.close(1011, "health update failed"));
      return;
    }

    socket.close(1008, "unexpected handshake message");
  });

  socket.on("close", () => {
    clearTimeout(authTimer);
    if (preflightTimer) clearTimeout(preflightTimer);
    releaseUnauthenticatedSlot();
    const shouldRecordDisconnected = peer
      ? unregisterAccountConnector(peer)
      : Boolean(material);
    if (material && shouldRecordDisconnected) {
      void control.recordConnectorDisconnected(material).catch((error) => {
        console.error("Unable to record account Connector disconnect", safeError(error));
      });
    }
  });
});

appWss.on("connection", (socket: WebSocket, _request: IncomingMessage, connector: Peer) => {
  const authorization = connector.mode === "account"
    ? firstHeader(_request, "authorization")
    : undefined;
  webSocketTunnels.open(
    socket,
    connector,
    authorization ? () => resolveAccountConnector(authorization) : undefined,
  );
});

server.listen(port, host, () => {
  const scheme = tlsCertFile ? "https/wss" : "http/ws";
  console.log(`Hermes Remote Gateway listening on ${scheme}://${host}:${port}`);
});

function route(peer: Peer, message: WireMessage): void {
  if (peer.role === "app") {
    if (commands.handleAppMessage(peer, message)) return;
    send(peer.socket, errorMessage("forbidden_message", "Message is not valid for this peer"));
    return;
  }

  if (message.type === "session.lifecycle") {
    if (peer.mode === "account") {
      const control = accountRuntime.gatewayControl;
      const material = peer.binding;
      if (!control || !material || message.deviceId !== peer.deviceId) {
        send(peer.socket, errorMessage("device_mismatch", "Lifecycle event does not match Connector binding"));
        return;
      }
      void control.ingestLifecycleEvent(material, message).then((status) => {
        if (status === "stored" || status === "duplicate") {
          send(peer.socket, {
            type: "session.lifecycle.ack",
            version: PROTOCOL_VERSION,
            eventId: message.eventId,
          });
          return;
        }
        send(peer.socket, errorMessage(
          status,
          status === "event_id_conflict"
            ? "Lifecycle event ID was reused for different content"
            : "Connector binding is no longer active",
        ));
        peer.socket.close(status === "binding_invalid" ? 4403 : 1008, status);
      }).catch((error) => {
        console.error("Unable to persist account lifecycle event", safeError(error));
        send(peer.socket, errorMessage("lifecycle_store_failed", "Unable to persist lifecycle event"));
      });
      return;
    }
    if (message.deviceId !== peer.deviceId) {
      send(peer.socket, errorMessage("device_mismatch", "Lifecycle event device does not match Connector"));
      return;
    }
    // ACK only after the transition is durable. If the socket drops first, the Connector retains
    // the event in its local outbox and resends it; ingest() deduplicates by the stable event ID.
    void lifecycleEvents.ingest(message).then(() => {
      send(peer.socket, {
        type: "session.lifecycle.ack",
        version: PROTOCOL_VERSION,
        eventId: message.eventId,
      });
    }).catch((error) => {
      console.error("Unable to persist lifecycle event", safeError(error));
      send(peer.socket, errorMessage("lifecycle_store_failed", "Unable to persist lifecycle event"));
    });
    return;
  }

  if (commands.handleConnectorMessage(peer, message)) return;
  if (httpTunnels.handleConnectorMessage(peer, message)) return;
  if (webSocketTunnels.handleConnectorMessage(peer, message)) return;

  send(peer.socket, errorMessage("forbidden_message", "Message is not valid for this peer"));
}

function authenticate(message: HelloMessage): boolean {
  const expected = message.role === "app" ? appToken : connectorToken;
  return safeEqual(message.token, expected) && message.deviceId.length > 0;
}

function register(peer: Peer): void {
  if (peer.role === "connector") {
    connectorRegistry.getLegacy(peer.deviceId)?.socket.close(4409, "replaced by a new connection");
    connectorRegistry.setLegacy(peer.deviceId, peer);
    broadcastStatus(peer.deviceId, true);
  } else {
    apps.add(peer);
  }
}

function registerAccountConnector(peer: Peer): void {
  const bindingId = peer.binding?.id;
  if (!bindingId) throw new Error("account Connector has no binding");
  const previous = connectorRegistry.replaceAccount(bindingId, peer);
  if (previous && previous !== peer) {
    failRoutingRequests(previous.routingKey);
    previous.socket.close(4409, "replaced by a new connection");
  }
}

function unregisterAccountConnector(peer: Peer): boolean {
  const bindingId = peer.binding?.id;
  if (!bindingId || !connectorRegistry.deleteAccountIfCurrent(bindingId, peer)) return false;
  failRoutingRequests(peer.routingKey);
  return true;
}

function unregister(peer: Peer): void {
  if (peer.role === "connector"
      && connectorRegistry.deleteLegacyIfCurrent(peer.deviceId, peer)) {
    broadcastStatus(peer.deviceId, false);
    failRoutingRequests(peer.routingKey);
  } else {
    apps.delete(peer);
  }
  commands.unregisterApp(peer);
}

function failRoutingRequests(routingKey: string): void {
  httpTunnels.failRouting(routingKey);
  webSocketTunnels.failRouting(routingKey);
  commands.failRouting(routingKey);
}

function broadcastStatus(deviceId: string, online: boolean): void {
  for (const app of apps) sendStatus(app, deviceId, online);
}

function sendStatus(peer: Peer, deviceId: string, online: boolean): void {
  send(peer.socket, { type: "device_status", version: PROTOCOL_VERSION, deviceId, online });
}

async function authorizeAppWebSocket(request: IncomingMessage, url: URL): Promise<Peer> {
  const authorization = firstHeader(request, "authorization");
  const headerLegacyToken = firstHeader(request, "x-hermes-session-token");
  const queryLegacyToken = url.searchParams.get("token");
  if (authorization) {
    if (headerLegacyToken || queryLegacyToken || url.searchParams.has("device_id")) {
      throw accountErrors.invalidRequest(
        "Account WebSockets cannot include legacy credentials or select a device in the URL.",
      );
    }
    return resolveAccountConnector(authorization);
  }

  const token = queryLegacyToken ?? headerLegacyToken;
  if (!token || !safeEqual(token, appToken)) {
    throw new AccountModeError(401, "unauthorized", "Unauthorized", false, "none");
  }
  const deviceId = url.searchParams.get("device_id") ?? defaultDeviceId;
  const connector = connectorRegistry.getLegacy(deviceId);
  if (!connector) throw new AccountModeError(503, "device_offline", "Mac connector offline", true, "retry");
  return connector;
}

async function resolveAccountConnector(authorization: string): Promise<Peer> {
  const control = accountRuntime.gatewayControl;
  if (!control) throw accountErrors.featureDisabled();
  const principal = await control.authenticate(authorization);
  const state = await control.getBinding(principal);
  if (state.state !== "bound") throw accountErrors.bindingMissing();
  const binding = state.binding;
  const connector = connectorRegistry.getAccount(binding.id);
  if (!connector
      || connector.accountId !== principal.account.id
      || connector.binding?.generation !== binding.generation
      || connector.binding.publicKeyFingerprint !== binding.publicKeyFingerprint
      || connector.socket.readyState !== WebSocket.OPEN) {
    throw accountErrors.connectorOffline();
  }
  return connector;
}

function routingPeer(routingKey: string): Peer | undefined {
  return connectorRegistry.getByRoutingKey(routingKey);
}

function incrementCount(counts: Map<string, number>, key: string): void {
  counts.set(key, (counts.get(key) ?? 0) + 1);
}

function decrementCount(counts: Map<string, number>, key: string): void {
  const next = (counts.get(key) ?? 1) - 1;
  if (next <= 0) counts.delete(key);
  else counts.set(key, next);
}

function send(socket: WebSocket, message: WireMessage): void {
  if (socket.readyState !== WebSocket.OPEN) return;
  const encoded = encodeWireMessage(message);
  if (socket.bufferedAmount + Buffer.byteLength(encoded) > maxSocketBufferedBytes) {
    socket.close(1013, "backpressure limit reached");
    return;
  }
  socket.send(encoded);
}

function sendAccountHttpError(response: ServerResponse, error: unknown): void {
  const mapped = error instanceof AccountModeError ? error : accountErrors.unavailable();
  sendJson(response, mapped.status, {
    error: {
      code: mapped.code,
      message: mapped.message,
      retryable: mapped.retryable,
      recoveryAction: mapped.recoveryAction,
      correlationId: randomUUID(),
    },
  });
}

function rejectAccountUpgrade(socket: NodeJS.WritableStream, error: unknown): void {
  const mapped = error instanceof AccountModeError ? error : accountErrors.unavailable();
  rejectUpgrade(socket, mapped.status, mapped.code);
}

function errorMessage(code: string, message: string, requestId?: string): WireMessage {
  return { type: "error", version: PROTOCOL_VERSION, code, message, requestId };
}

function safeEqual(actual: string, expected: string): boolean {
  const left = Buffer.from(actual);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

function safeError(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function shutdown(signal: string): void {
  console.log(`Received ${signal}; closing Gateway`);
  for (const client of controlWss.clients) client.close(1012, "gateway restarting");
  for (const client of accountControlWss.clients) client.close(1012, "gateway restarting");
  for (const client of appWss.clients) client.close(1012, "gateway restarting");
  server.close(() => {
    void accountRuntime.close().then(
      () => process.exit(0),
      () => process.exit(1),
    );
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.once("SIGTERM", () => shutdown("SIGTERM"));
process.once("SIGINT", () => shutdown("SIGINT"));
