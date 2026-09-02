import { randomUUID, timingSafeEqual } from "node:crypto";
import type { ServerResponse } from "node:http";
import { WebSocket } from "ws";
import { encodeWireMessage, type WireMessage } from "@hermes-remote/protocol";
import { AccountConnectorAdmission } from "./account-connector-admission.js";
import { AccountConnectorSessionHandler } from "./account-connector-session.js";
import { AppWebSocketAuthorizer } from "./app-websocket-authorizer.js";
import { CommandBroker } from "./command-broker.js";
import { loadGatewayConfig } from "./config.js";
import { InMemoryConnectorRegistry } from "./connector-registry.js";
import { GatewayHttpRouter } from "./gateway-http-router.js";
import type { GatewayPeer } from "./gateway-peer.js";
import { GatewayPeerCoordinator } from "./gateway-peer-coordinator.js";
import { GatewayServer } from "./gateway-server.js";
import { HttpTunnelBroker } from "./http-tunnel-broker.js";
import { firstHeader, sendJson } from "./http-utils.js";
import { LegacyControlSessionHandler } from "./legacy-control-session.js";
import { LifecycleEventStore } from "./lifecycle-event-store.js";
import { LifecycleMessageHandler } from "./lifecycle-message-handler.js";
import { WebSocketTunnelBroker } from "./websocket-tunnel-broker.js";
import { rejectUpgrade } from "./websocket-utils.js";
import { createAccountRuntime } from "./account/account-runtime.js";
import { AccountModeError, accountErrors } from "./account/model.js";

export function createGatewayRuntime(environment: NodeJS.ProcessEnv): GatewayServer<GatewayPeer> {
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
  } = loadGatewayConfig(environment);
  const lifecycleEvents = new LifecycleEventStore(lifecycleEventStoreFile, maxLifecycleEvents);
  const accountRuntime = createAccountRuntime(environment);
  const connectorRegistry = new InMemoryConnectorRegistry<GatewayPeer>();
  const accountConnectorAdmission = new AccountConnectorAdmission(
    maxUnauthenticatedAccountConnectors,
    maxUnauthenticatedAccountConnectorsPerIp,
  );
  const commands = new CommandBroker<GatewayPeer, GatewayPeer>(
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
  const webSocketTunnels = new WebSocketTunnelBroker<GatewayPeer>(
    maxWebSocketTunnels,
    maxSocketBufferedBytes,
    send,
    (routingKey) => connectorRegistry.getByRoutingKey(routingKey),
  );
  const lifecycleMessages = new LifecycleMessageHandler(
    lifecycleEvents,
    accountRuntime.gatewayControl,
    send,
    reportFailure,
  );
  const peers = new GatewayPeerCoordinator(
    connectorRegistry,
    commands,
    httpTunnels,
    webSocketTunnels,
    lifecycleMessages,
    send,
  );
  const appWebSocketAuthorizer = new AppWebSocketAuthorizer({
    accountControl: accountRuntime.gatewayControl,
    connectorRegistry,
    appToken,
    defaultDeviceId,
    tokensEqual: safeEqual,
  });
  const legacyControlSessions = new LegacyControlSessionHandler({
    appToken,
    connectorToken,
    send,
    tokensEqual: safeEqual,
    register: (peer) => peers.registerLegacy(peer),
    unregister: (peer) => peers.unregisterLegacy(peer),
    route: (peer, message) => peers.route(peer, message),
    legacyDeviceIds: () => connectorRegistry.legacyDeviceIds(),
    reportFailure,
  });
  const accountConnectorSessions = accountRuntime.gatewayControl
    ? new AccountConnectorSessionHandler({
        control: accountRuntime.gatewayControl,
        admission: accountConnectorAdmission,
        send,
        route: (peer, message) => peers.route(peer, message),
        register: (peer) => peers.registerAccount(peer),
        unregister: (peer) => peers.unregisterAccount(peer),
        reportFailure,
      })
    : undefined;
  const httpRouter = new GatewayHttpRouter({
    accountController: accountRuntime.controller,
    accountControl: accountRuntime.gatewayControl,
    appToken,
    defaultDeviceId,
    maxBodyBytes,
    connectorRegistry,
    lifecycleEvents,
    httpTunnels,
    resolveAccountConnector: (authorization) => (
      appWebSocketAuthorizer.resolveAccountConnector(authorization)
    ),
    sendAccountError: sendAccountHttpError,
    tokensEqual: safeEqual,
  });

  return new GatewayServer<GatewayPeer>({
    port,
    host,
    tlsCertFile,
    tlsKeyFile,
    requestTimeoutMs,
    maxControlConnections,
    maxWirePayloadBytes,
    maxAppPayloadBytes,
    accountConnectorEnabled: Boolean(accountConnectorSessions),
    accountConnectorAdmission,
    handleHttp: (request, response) => httpRouter.handle(request, response),
    authorizeAppWebSocket: (request, url) => appWebSocketAuthorizer.authorize(request, url),
    rejectAppUpgrade: rejectAccountUpgrade,
    atWebSocketCapacity: () => webSocketTunnels.atCapacity,
    attachLegacyControl: (socket) => legacyControlSessions.attach(socket),
    attachAccountConnector: (socket, sourceIp) => {
      if (accountConnectorSessions) accountConnectorSessions.attach(socket, sourceIp);
    },
    openAppWebSocket: (socket, request, connector) => {
      const authorization = connector.mode === "account"
        ? firstHeader(request, "authorization")
        : undefined;
      webSocketTunnels.open(
        socket,
        connector,
        authorization
          ? () => appWebSocketAuthorizer.resolveAccountConnector(authorization)
          : undefined,
      );
    },
    closeDependencies: () => accountRuntime.close(),
    reportFailure,
  });

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

  function safeEqual(actual: string, expected: string): boolean {
    const left = Buffer.from(actual);
    const right = Buffer.from(expected);
    return left.length === right.length && timingSafeEqual(left, right);
  }

  function reportFailure(message: string, error: unknown): void {
    console.error(message, error instanceof Error ? error.message : String(error));
  }
}
