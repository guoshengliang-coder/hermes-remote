import { readFileSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { WebSocket, WebSocketServer } from "ws";
import type { AccountConnectorAdmission } from "./account-connector-admission.js";
import { sendHttpError } from "./http-utils.js";
import { rejectUpgrade } from "./websocket-utils.js";

interface GatewayServerOptions<TConnector> {
  port: number;
  host: string;
  tlsCertFile?: string;
  tlsKeyFile?: string;
  requestTimeoutMs: number;
  maxControlConnections: number;
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
}

export class GatewayServer<TConnector> {
  private readonly controlWss: WebSocketServer;
  private readonly accountControlWss: WebSocketServer;
  private readonly appWss: WebSocketServer;
  private readonly server: ReturnType<typeof createServer>;

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

    this.controlWss = new WebSocketServer({
      noServer: true,
      maxPayload: options.maxWirePayloadBytes,
    });
    this.accountControlWss = new WebSocketServer({
      noServer: true,
      maxPayload: options.maxWirePayloadBytes,
    });
    this.appWss = new WebSocketServer({
      noServer: true,
      maxPayload: options.maxAppPayloadBytes,
    });
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

      if (url.pathname === "/api/ws") {
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
