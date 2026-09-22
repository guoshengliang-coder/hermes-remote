import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { WebSocket, WebSocketServer, type ServerOptions } from "ws";
import type { AccountConnectorAdmission } from "./account-connector-admission.js";
import { ControlHeartbeat } from "./control-heartbeat.js";
import type { GatewayLogger } from "./gateway-log.js";
import { sendHttpError } from "./http-utils.js";
import { rejectUpgrade } from "./websocket-utils.js";

interface GatewayServerOptions<TConnector> {
  port: number;
  host: string;
  tlsCertFile?: string;
  tlsKeyFile?: string;
  requestTimeoutMs: number;
  maxControlConnections: number;
  controlHeartbeatIntervalMs: number;
  controlHeartbeatTimeoutMs: number;
  maxWirePayloadBytes: number;
  maxAppPayloadBytes: number;
  accountConnectorEnabled: boolean;
  accountConnectorAdmission: AccountConnectorAdmission;
  handleHttp(request: IncomingMessage, response: ServerResponse): Promise<void>;
  authorizeAppWebSocket(request: IncomingMessage, url: URL): Promise<TConnector>;
  rejectAppUpgrade(socket: NodeJS.WritableStream, error: unknown): void;
  atWebSocketCapacity(): boolean;
  attachLegacyControl(socket: WebSocket): void;
  attachAccountConnector(socket: WebSocket, sourceIp: string): void;
  openAppWebSocket(
    socket: WebSocket,
    request: IncomingMessage,
    connector: TConnector,
  ): void;
  closeDependencies(): Promise<void>;
  reportFailure(message: string, error: unknown): void;
  log: GatewayLogger;
}

/**
 * Minimum message size, in bytes, before a tunnel WebSocket message is deflated. Smaller frames
 * (hello, acks, heartbeats, short tunnel frames) cost more CPU than they save.
 */
export const TUNNEL_DEFLATE_THRESHOLD_BYTES = 1024;

/**
 * Options for the WebSocketServers Connectors dial (`/v1/connect`, `/v2/connect`).
 *
 * HG-104: tunnelled REST bodies travel base64-encoded inside JSON wire frames, so the Mac's uplink
 * carries ~4/3 of every session list and message history page. permessage-deflate recovers that
 * (and more) on the Connector hop; ws enforces `maxPayload` on the inflated size, so compression does
 * not widen the payload bound. Both no-context-takeover flags reset the zlib window after every
 * message: a Connector connection is long-lived, and a per-connection sliding window would pin
 * deflate + inflate state for its whole life. Resetting keeps the steady-state cost to the transient
 * buffers of the message being processed; the ratio loss is small because the payloads that matter
 * are individual multi-kilobyte JSON bodies, not streams of similar small frames. `memLevel: 7`
 * (instead of zlib's 8) halves the deflate hash memory for a negligible ratio change. The negotiation
 * only succeeds when the client offers the extension — ws clients (the Connector) do by default;
 * clients that do not offer it keep working uncompressed.
 */
export function connectorWebSocketServerOptions(maxPayload: number): ServerOptions {
  return {
    noServer: true,
    maxPayload,
    perMessageDeflate: {
      threshold: TUNNEL_DEFLATE_THRESHOLD_BYTES,
      serverNoContextTakeover: true,
      clientNoContextTakeover: true,
      zlibDeflateOptions: { level: 6, memLevel: 7 },
    },
  };
}

/**
 * Options for the app-facing WebSocketServer (`/api/ws`, `/v2/devices/<id>/ws`). No compression:
 * they are mostly small streaming events relayed to and from Hermes, and enabling it here would add
 * per-socket zlib state for every open phone and browser tab. Kept explicit so a ws default change
 * cannot turn it on silently.
 */
export function appWebSocketServerOptions(maxPayload: number): ServerOptions {
  return { noServer: true, maxPayload, perMessageDeflate: false };
}

export class GatewayServer<TConnector> {
  private readonly controlWss: WebSocketServer;
  private readonly accountControlWss: WebSocketServer;
  private readonly appWss: WebSocketServer;
  private readonly server: ReturnType<typeof createServer>;
  private readonly controlHeartbeat: ControlHeartbeat;

  constructor(private readonly options: GatewayServerOptions<TConnector>) {
    const requestHandler = (request: IncomingMessage, response: ServerResponse): void => {
      void options.handleHttp(request, response).catch((error) => {
        options.reportFailure("HTTP relay failure", error);
        if (!response.headersSent) sendHttpError(response, 500, "relay_error");
        else response.end();
      });
    };
    this.server = options.tlsCertFile && options.tlsKeyFile
      ? createHttpsServer(
          {
            cert: readFileSync(options.tlsCertFile),
            key: readFileSync(options.tlsKeyFile),
          },
          requestHandler,
        )
      : createServer(requestHandler);
    this.server.headersTimeout = 15_000;
    this.server.requestTimeout = options.requestTimeoutMs + 5_000;
    this.server.keepAliveTimeout = 5_000;
    this.server.maxHeadersCount = 64;

    this.controlWss = new WebSocketServer(connectorWebSocketServerOptions(options.maxWirePayloadBytes));
    this.accountControlWss = new WebSocketServer(connectorWebSocketServerOptions(options.maxWirePayloadBytes));
    this.appWss = new WebSocketServer(appWebSocketServerOptions(options.maxAppPayloadBytes));
    this.controlHeartbeat = new ControlHeartbeat(
      options.controlHeartbeatIntervalMs,
      options.controlHeartbeatTimeoutMs,
      options.log,
    );
    this.controlHeartbeat.start();
    this.attachHandlers();
  }

  start(): void {
    this.server.listen(this.options.port, this.options.host, () => {
      const scheme = this.options.tlsCertFile ? "https/wss" : "http/ws";
      console.log(
        `Hermes Remote Gateway listening on ${scheme}://${this.options.host}:${this.options.port}`,
      );
    });
  }

  shutdown(signal: string): void {
    console.log(`Received ${signal}; closing Gateway`);
    this.controlHeartbeat.stop();
    for (const client of this.controlWss.clients) client.close(1012, "gateway restarting");
    for (const client of this.accountControlWss.clients) client.close(1012, "gateway restarting");
    for (const client of this.appWss.clients) client.close(1012, "gateway restarting");
    this.server.close(() => {
      void this.options.closeDependencies().then(
        () => process.exit(0),
        () => process.exit(1),
      );
    });
    setTimeout(() => process.exit(1), 10_000).unref();
  }

  private attachHandlers(): void {
    this.server.on("upgrade", (request, socket, head) => {
      const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
      if (url.pathname === "/v1/connect") {
        if (this.controlConnectionCount >= this.options.maxControlConnections) {
          rejectUpgrade(socket, 503, "Control connection capacity reached");
          return;
        }
        this.controlWss.handleUpgrade(request, socket, head, (webSocket) => {
          this.controlWss.emit("connection", webSocket, request);
        });
        return;
      }

      if (url.pathname === "/v2/connect") {
        const sourceIp = request.socket.remoteAddress ?? "unknown";
        if (!this.options.accountConnectorEnabled) {
          rejectUpgrade(socket, 503, "Account Connector is disabled");
          return;
        }
        if (this.controlConnectionCount >= this.options.maxControlConnections
            || this.options.accountConnectorAdmission.atCapacity(sourceIp)) {
          rejectUpgrade(socket, 503, "Control connection capacity reached");
          return;
        }
        this.accountControlWss.handleUpgrade(request, socket, head, (webSocket) => {
          this.accountControlWss.emit("connection", webSocket, request, sourceIp);
        });
        return;
      }

      if (url.pathname === "/api/ws"
          || /^\/v2\/devices\/[^/]+\/ws$/.test(url.pathname)) {
        void this.options.authorizeAppWebSocket(request, url).then((connector) => {
          if (this.options.atWebSocketCapacity()) {
            rejectUpgrade(socket, 503, "Tunnel capacity reached");
            return;
          }
          this.appWss.handleUpgrade(request, socket, head, (webSocket) => {
            this.appWss.emit("connection", webSocket, request, connector);
          });
        }).catch((error) => this.options.rejectAppUpgrade(socket, error));
        return;
      }

      rejectUpgrade(socket, 404, "Not Found");
    });

    this.controlWss.on("connection", (socket) => {
      this.controlHeartbeat.track(socket, "legacy");
      this.options.attachLegacyControl(socket);
    });
    this.accountControlWss.on("connection", (
      socket: WebSocket,
      _request: IncomingMessage,
      sourceIp: string,
    ) => {
      if (!this.options.accountConnectorEnabled) {
        socket.close(1013, "account Connector disabled");
        return;
      }
      this.controlHeartbeat.track(socket, "account");
      this.options.attachAccountConnector(socket, sourceIp);
    });
    this.appWss.on("connection", (
      socket: WebSocket,
      request: IncomingMessage,
      connector: TConnector,
    ) => {
      this.options.openAppWebSocket(socket, request, connector);
    });
  }

  private get controlConnectionCount(): number {
    return this.controlWss.clients.size + this.accountControlWss.clients.size;
  }
}
