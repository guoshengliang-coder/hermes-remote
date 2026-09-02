import { randomUUID, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { resolve } from "node:path";
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
import { createAccountRuntime } from "./account/account-runtime.js";
import { AccountModeError, accountErrors } from "./account/model.js";
import type { BindingProofMaterial } from "./account/account-control-model.js";

const port = positiveIntEnv("PORT", 8787, 65_535);
const host = process.env.HOST ?? "0.0.0.0";
const defaultDeviceId = process.env.DEFAULT_DEVICE_ID ?? "mac-mini";
const appToken = requireSecret("APP_TOKEN");
const connectorToken = requireSecret("CONNECTOR_TOKEN");
const tlsCertFile = process.env.TLS_CERT_FILE;
const tlsKeyFile = process.env.TLS_KEY_FILE;
const maxBodyBytes = positiveIntEnv("MAX_BODY_BYTES", 10 * 1024 * 1024);
const requestTimeoutMs = positiveIntEnv("REQUEST_TIMEOUT_MS", 60_000);
const maxPendingRequests = positiveIntEnv("MAX_PENDING_REQUESTS", 128);
const maxWebSocketTunnels = positiveIntEnv("MAX_WS_TUNNELS", 32);
const maxControlConnections = positiveIntEnv("MAX_CONTROL_CONNECTIONS", 32);
const maxUnauthenticatedAccountConnectors = positiveIntEnv(
  "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS",
  16,
  1024,
);
const maxUnauthenticatedAccountConnectorsPerIp = positiveIntEnv(
  "ACCOUNT_MAX_UNAUTHENTICATED_CONNECTORS_PER_IP",
  4,
  128,
);
const maxWirePayloadBytes = positiveIntEnv("MAX_WIRE_PAYLOAD_BYTES", 20 * 1024 * 1024);
const maxAppPayloadBytes = positiveIntEnv("MAX_APP_WS_PAYLOAD_BYTES", 12 * 1024 * 1024);
const maxSocketBufferedBytes = positiveIntEnv("MAX_SOCKET_BUFFERED_BYTES", 24 * 1024 * 1024);
const lifecycleEventStoreFile = resolve(
  process.env.LIFECYCLE_EVENT_STORE_FILE
    ?? (process.env.NODE_ENV === "production"
      ? "/var/lib/hermes-remote/lifecycle-events.json"
      : ".data/lifecycle-events.json"),
);
const maxLifecycleEvents = positiveIntEnv("MAX_LIFECYCLE_EVENTS", 10_000, 1_000_000);
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

type PendingHttp = {
  response: ServerResponse;
  routingKey: string;
  timer: NodeJS.Timeout;
  started: boolean;
  nextSequence: number;
};

type AppTunnel = {
  socket: WebSocket;
  routingKey: string;
  connector: Peer;
};

type RequestOwner = {
  peer: Peer;
  routingKey: string;
  timer?: NodeJS.Timeout;
};

const connectors = new Map<string, Peer>();
const accountConnectors = new Map<string, Peer>();
const apps = new Set<Peer>();
const requestOwners = new Map<string, RequestOwner>();
const pendingHttp = new Map<string, PendingHttp>();
const appTunnels = new Map<string, AppTunnel>();
const unauthenticatedAccountConnectorsByIp = new Map<string, number>();

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
      if (appTunnels.size >= maxWebSocketTunnels) {
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
          for (const deviceId of connectors.keys()) sendStatus(peer, deviceId, true);
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
  const deviceId = connector.deviceId;
  const authorization = connector.mode === "account"
    ? firstHeader(_request, "authorization")
    : undefined;
  const accountRevalidationTimer = authorization
    ? setInterval(() => {
        void resolveAccountConnector(authorization).then((current) => {
          if (current !== connector) socket.close(4403, "account binding changed");
        }).catch(() => socket.close(4403, "account authorization changed"));
      }, 5_000)
    : undefined;
  accountRevalidationTimer?.unref();
  const id = randomUUID();
  appTunnels.set(id, { socket, routingKey: connector.routingKey, connector });
  send(connector.socket, {
    type: "tunnel.ws.open",
    version: PROTOCOL_VERSION,
    id,
    targetDeviceId: deviceId,
    path: "/api/ws",
  });

  socket.on("message", (data, isBinary) => {
    const current = routingPeer(connector.routingKey);
    if (current !== connector) {
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
    if (accountRevalidationTimer) clearInterval(accountRevalidationTimer);
    appTunnels.delete(id);
    const current = routingPeer(connector.routingKey);
    if (current === connector) {
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
  if (url.pathname.startsWith("/v2/")) {
    await accountRuntime.controller.handle(request, response, url);
    return;
  }
  if (url.pathname === "/health") {
    response.writeHead(200, { "content-type": "application/json" });
    // `connectors` (count) is kept for existing probes; `devices` lists each connected
    // Mac connector so clients can show WHICH device is online (the card page's
    // "remote device" tile). Only currently-connected connectors appear, all online:true —
    // the relay has no persistence for previously-seen devices.
    response.end(JSON.stringify({
      ok: true,
      connectors: connectors.size,
      devices: [...connectors.keys()].map((deviceId) => ({ deviceId, online: true })),
    }));
    return;
  }

  if (!url.pathname.startsWith("/api/")) {
    sendHttpError(response, 404, "not_found");
    return;
  }

  const authorization = firstHeader(request, "authorization");
  const legacyToken = firstHeader(request, "x-hermes-session-token");
  let connector: Peer;
  if (authorization) {
    if (legacyToken) {
      sendAccountHttpError(response, accountErrors.invalidRequest(
        "Account and legacy credentials cannot be used together.",
      ));
      return;
    }
    if (url.pathname.startsWith("/api/mobile/events")) {
      await handleAccountMobileEventsRequest(request, response, url, authorization);
      return;
    }
    try {
      connector = await resolveAccountConnector(authorization);
    } catch (error) {
      sendAccountHttpError(response, error);
      return;
    }
  } else {
    if (!legacyToken || !safeEqual(legacyToken, appToken)) {
      sendHttpError(response, 401, "unauthorized");
      return;
    }
    const deviceId = firstHeader(request, "x-hermes-device-id") ?? defaultDeviceId;
    const legacyConnector = connectors.get(deviceId);
    if (!legacyConnector) {
      sendHttpError(response, 503, "device_offline");
      return;
    }
    connector = legacyConnector;
  }

  // These endpoints are owned by the Relay. They remain available while the Mac is offline and
  // must never be forwarded through the Connector to Hermes.
  if (url.pathname.startsWith("/api/mobile/events")) {
    await handleMobileEventsRequest(request, response, url);
    return;
  }

  const deviceId = connector.deviceId;
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
  const timer = setTimeout(() => expirePendingHttp(id), requestTimeoutMs);
  pendingHttp.set(id, {
    response,
    routingKey: connector.routingKey,
    timer,
    started: false,
    nextSequence: 0,
  });
  request.on("aborted", () => clearPendingHttp(id));
  response.on("close", () => clearPendingHttp(id));

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

async function handleMobileEventsRequest(
  request: IncomingMessage,
  response: ServerResponse,
  url: URL,
): Promise<void> {
  if (url.pathname === "/api/mobile/events" && request.method === "GET") {
    const after = nonNegativeIntegerQuery(url, "after", 0);
    const limit = positiveIntegerQuery(url, "limit", 100, 500);
    if (after === undefined || limit === undefined) {
      sendHttpError(response, 400, "invalid_query");
      return;
    }
    const page = await lifecycleEvents.list(after, limit);
    sendJson(response, 200, page);
    return;
  }

  if ((url.pathname === "/api/mobile/events/ack" || url.pathname === "/api/mobile/events/read")
      && request.method === "POST") {
    let body: Buffer;
    try {
      body = await readRequestBody(request, Math.min(maxBodyBytes, 256 * 1024));
    } catch {
      sendHttpError(response, 413, "request_too_large");
      return;
    }
    let eventIds: string[];
    try {
      eventIds = parseEventIds(body);
    } catch {
      sendHttpError(response, 400, "invalid_request");
      return;
    }
    const changed = url.pathname.endsWith("/read")
      ? await lifecycleEvents.markRead(eventIds)
      : await lifecycleEvents.markDelivered(eventIds);
    sendJson(response, 200, { ok: true, changed });
    return;
  }

  sendHttpError(response, 404, "not_found");
}

async function handleAccountMobileEventsRequest(
  request: IncomingMessage,
  response: ServerResponse,
  url: URL,
  authorization: string,
): Promise<void> {
  try {
    const control = accountRuntime.gatewayControl;
    if (!control) throw accountErrors.featureDisabled();
    const principal = await control.authenticate(authorization);
    if (url.pathname === "/api/mobile/events" && request.method === "GET") {
      const after = nonNegativeIntegerQuery(url, "after", 0);
      const limit = positiveIntegerQuery(url, "limit", 100, 500);
      if (after === undefined || limit === undefined) {
        throw accountErrors.invalidRequest("Lifecycle event pagination is invalid.");
      }
      sendJson(response, 200, await control.listLifecycleEvents(principal, after, limit));
      return;
    }

    if ((url.pathname === "/api/mobile/events/ack" || url.pathname === "/api/mobile/events/read")
        && request.method === "POST") {
      let body: Buffer;
      try {
        body = await readRequestBody(request, Math.min(maxBodyBytes, 256 * 1024));
      } catch {
        throw accountErrors.invalidRequest("Lifecycle event acknowledgement is too large.");
      }
      let eventIds: string[];
      try {
        eventIds = parseEventIds(body);
      } catch {
        throw accountErrors.invalidRequest("Lifecycle event acknowledgement is invalid.");
      }
      const changed = await control.markLifecycleEvents(
        principal,
        eventIds,
        url.pathname.endsWith("/read") ? "read" : "delivered",
      );
      sendJson(response, 200, { ok: true, changed });
      return;
    }

    throw new AccountModeError(
      404,
      "HR-ACCOUNT-004",
      "The account endpoint was not found.",
      false,
      "none",
    );
  } catch (error) {
    sendAccountHttpError(response, error);
  }
}

function route(peer: Peer, message: WireMessage): void {
  if (peer.role === "app" && message.type === "command") {
    const connector = connectors.get(message.targetDeviceId);
    if (!connector) {
      send(peer.socket, errorMessage("device_offline", "Target Mac is offline", message.id));
      return;
    }
    if (requestOwners.size >= maxPendingRequests) {
      send(peer.socket, errorMessage("relay_capacity_reached", "Too many pending requests", message.id));
      return;
    }
    if (requestOwners.has(message.id)) {
      send(peer.socket, errorMessage("duplicate_request_id", "Request ID is already pending", message.id));
      return;
    }
    const owner = { peer, routingKey: connector.routingKey };
    requestOwners.set(message.id, owner);
    armRequestOwnerTimeout(message.id, owner);
    send(connector.socket, message);
    return;
  }

  if (peer.role !== "connector") {
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

  if (message.type === "event") {
    const owner = requestOwners.get(message.requestId);
    if (!owner || owner.routingKey !== peer.routingKey) return;
    send(owner.peer.socket, message);
    if (message.event === "complete" || message.event === "error") {
      clearRequestOwner(message.requestId);
    } else {
      // This is an inactivity timeout, not a maximum generation duration. A long answer that is
      // still producing accepted/delta events must not be cut off at the fixed request deadline.
      armRequestOwnerTimeout(message.requestId, owner);
    }
    return;
  }

  if (message.type === "tunnel.http.response") {
    const pending = pendingHttp.get(message.requestId);
    if (!pending || pending.routingKey !== peer.routingKey) return;
    clearPendingHttp(message.requestId);
    if (pending.started) {
      pending.response.destroy(new Error("mixed_http_response_modes"));
      return;
    }
    const body = message.bodyBase64 ? Buffer.from(message.bodyBase64, "base64") : Buffer.alloc(0);
    pending.response.writeHead(message.status, selectResponseHeaders(message.headers));
    pending.response.end(body);
    return;
  }

  if (message.type === "tunnel.http.response.start") {
    const pending = pendingHttp.get(message.requestId);
    if (!pending || pending.routingKey !== peer.routingKey || pending.started) return;
    pending.started = true;
    refreshPendingHttpTimeout(message.requestId, pending);
    pending.response.writeHead(message.status, selectResponseHeaders(message.headers));
    return;
  }

  if (message.type === "tunnel.http.response.chunk") {
    const pending = pendingHttp.get(message.requestId);
    if (!pending || pending.routingKey !== peer.routingKey || !pending.started) return;
    if (message.sequence !== pending.nextSequence) {
      clearPendingHttp(message.requestId);
      pending.response.destroy(new Error("invalid_response_chunk_sequence"));
      return;
    }
    pending.nextSequence += 1;
    refreshPendingHttpTimeout(message.requestId, pending);
    const chunk = Buffer.from(message.dataBase64, "base64");
    pending.response.write(chunk, () => {
      const current = pendingHttp.get(message.requestId);
      if (current !== pending) return;
      send(peer.socket, {
        type: "tunnel.http.response.ack",
        version: PROTOCOL_VERSION,
        requestId: message.requestId,
        sequence: message.sequence,
      });
    });
    return;
  }

  if (message.type === "tunnel.http.response.end") {
    const pending = pendingHttp.get(message.requestId);
    if (!pending || pending.routingKey !== peer.routingKey) return;
    clearPendingHttp(message.requestId);
    if (message.error) {
      if (!pending.started) sendHttpError(pending.response, 502, message.error);
      else pending.response.destroy(new Error(message.error));
    } else {
      if (!pending.started) pending.response.writeHead(204);
      pending.response.end();
    }
    return;
  }

  if (message.type === "tunnel.ws.frame") {
    const tunnel = appTunnels.get(message.id);
    if (!tunnel || tunnel.routingKey !== peer.routingKey || tunnel.connector !== peer) return;
    if (tunnel.socket.readyState === WebSocket.OPEN) {
      const data = Buffer.from(message.dataBase64, "base64");
      if (tunnel.socket.bufferedAmount + data.length > maxSocketBufferedBytes) {
        tunnel.socket.close(1013, "backpressure limit reached");
      } else {
        tunnel.socket.send(message.binary ? data : data.toString("utf8"), { binary: message.binary });
      }
    }
    return;
  }

  if (message.type === "tunnel.ws.close") {
    const tunnel = appTunnels.get(message.id);
    if (!tunnel || tunnel.routingKey !== peer.routingKey || tunnel.connector !== peer) return;
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

function registerAccountConnector(peer: Peer): void {
  const bindingId = peer.binding?.id;
  if (!bindingId) throw new Error("account Connector has no binding");
  const previous = accountConnectors.get(bindingId);
  accountConnectors.set(bindingId, peer);
  if (previous && previous !== peer) {
    failRoutingRequests(previous.routingKey);
    previous.socket.close(4409, "replaced by a new connection");
  }
}

function unregisterAccountConnector(peer: Peer): boolean {
  const bindingId = peer.binding?.id;
  if (!bindingId || accountConnectors.get(bindingId) !== peer) return false;
  accountConnectors.delete(bindingId);
  failRoutingRequests(peer.routingKey);
  return true;
}

function unregister(peer: Peer): void {
  if (peer.role === "connector" && connectors.get(peer.deviceId) === peer) {
    connectors.delete(peer.deviceId);
    broadcastStatus(peer.deviceId, false);
    failRoutingRequests(peer.routingKey);
  } else {
    apps.delete(peer);
  }
  for (const [requestId, owner] of requestOwners) {
    if (owner.peer === peer) clearRequestOwner(requestId);
  }
}

function failRoutingRequests(routingKey: string): void {
  for (const [id, pending] of pendingHttp) {
    if (pending.routingKey !== routingKey) continue;
    clearPendingHttp(id);
    sendHttpError(pending.response, 502, "connector_disconnected");
  }
  for (const [id, tunnel] of appTunnels) {
    if (tunnel.routingKey !== routingKey) continue;
    appTunnels.delete(id);
    tunnel.socket.close(1013, "Mac connector disconnected");
  }
  for (const [id, owner] of requestOwners) {
    if (owner.routingKey !== routingKey) continue;
    send(owner.peer.socket, errorMessage("connector_disconnected", "Mac connector disconnected", id));
    clearRequestOwner(id);
  }
}

function clearRequestOwner(id: string): void {
  const owner = requestOwners.get(id);
  if (!owner) return;
  if (owner.timer) clearTimeout(owner.timer);
  requestOwners.delete(id);
}

function armRequestOwnerTimeout(id: string, owner: RequestOwner): void {
  if (owner.timer) clearTimeout(owner.timer);
  owner.timer = setTimeout(() => {
    if (requestOwners.get(id) !== owner) return;
    requestOwners.delete(id);
    send(owner.peer.socket, errorMessage("connector_timeout", "Connector response timed out", id));
  }, requestTimeoutMs);
}

function clearPendingHttp(id: string): void {
  const pending = pendingHttp.get(id);
  if (!pending) return;
  clearTimeout(pending.timer);
  pendingHttp.delete(id);
}

function refreshPendingHttpTimeout(id: string, pending: PendingHttp): void {
  clearTimeout(pending.timer);
  pending.timer = setTimeout(() => expirePendingHttp(id), requestTimeoutMs);
}

function expirePendingHttp(id: string): void {
  const pending = pendingHttp.get(id);
  if (!pending) return;
  pendingHttp.delete(id);
  clearTimeout(pending.timer);
  if (pending.response.headersSent) pending.response.destroy(new Error("connector_timeout"));
  else sendHttpError(pending.response, 504, "connector_timeout");
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
  const connector = connectors.get(deviceId);
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
  const connector = accountConnectors.get(binding.id);
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
  if (routingKey.startsWith("legacy:")) return connectors.get(routingKey.slice("legacy:".length));
  if (routingKey.startsWith("account:")) return accountConnectors.get(routingKey.slice("account:".length));
  return undefined;
}

function legacyRoutingKey(deviceId: string): string {
  return `legacy:${deviceId}`;
}

function accountRoutingKey(bindingId: string): string {
  return `account:${bindingId}`;
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
  for (const name of ["content-type", "content-length", "content-disposition", "cache-control"]) {
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
  if (response.headersSent) {
    response.destroy(new Error(code));
    return;
  }
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: code }));
}

function sendJson(response: ServerResponse, status: number, value: unknown): void {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(value));
}

function parseEventIds(body: Buffer): string[] {
  const value: unknown = JSON.parse(body.toString("utf8"));
  if (!isRecord(value) || !Array.isArray(value.event_ids) || value.event_ids.length > 500) {
    throw new Error("invalid_event_ids");
  }
  return value.event_ids.map((eventId) => {
    if (typeof eventId !== "string" || eventId.length < 1 || eventId.length > 256) {
      throw new Error("invalid_event_id");
    }
    return eventId;
  });
}

function nonNegativeIntegerQuery(url: URL, name: string, fallback: number): number | undefined {
  const raw = url.searchParams.get(name);
  if (raw === null) return fallback;
  if (!/^\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : undefined;
}

function positiveIntegerQuery(
  url: URL,
  name: string,
  fallback: number,
  max: number,
): number | undefined {
  const value = nonNegativeIntegerQuery(url, name, fallback);
  return value !== undefined && value > 0 && value <= max ? value : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
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
