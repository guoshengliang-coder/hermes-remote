import type { IncomingMessage, ServerResponse } from "node:http";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { accountErrors } from "./account/model.js";
import type { ConnectorRegistry } from "./connector-registry.js";
import type { HttpTunnelBroker } from "./http-tunnel-broker.js";
import { firstHeader, sendHttpError } from "./http-utils.js";
import type { LifecycleEventStore } from "./lifecycle-event-store.js";
import { handleAccountMobileEvents, handleLegacyMobileEvents } from "./mobile-event-handler.js";
import type { ServerReleaseController } from "./server-release.js";

interface HttpConnector {
  socket: import("ws").WebSocket;
  deviceId: string;
  routingKey: string;
}

interface AccountHttpController {
  handle(request: IncomingMessage, response: ServerResponse, url: URL): Promise<void>;
}

interface GatewayHttpRouterOptions<TConnector extends HttpConnector> {
  accountController: AccountHttpController;
  accountControl?: AccountGatewayControl;
  appToken: string;
  defaultDeviceId: string;
  maxBodyBytes: number;
  connectorRegistry: ConnectorRegistry<TConnector>;
  lifecycleEvents: LifecycleEventStore;
  httpTunnels: HttpTunnelBroker;
  resolveAccountConnector(authorization: string): Promise<TConnector>;
  sendAccountError(response: ServerResponse, error: unknown): void;
  tokensEqual(actual: string, expected: string): boolean;
  serverRelease: ServerReleaseController;
}

export class GatewayHttpRouter<TConnector extends HttpConnector> {
  constructor(private readonly options: GatewayHttpRouterOptions<TConnector>) {}

  async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (await this.options.serverRelease.handle(request, response, url)) return;
    if (url.pathname.startsWith("/v2/")) {
      await this.options.accountController.handle(request, response, url);
      return;
    }
    if (url.pathname === "/health") {
      response.writeHead(200, { "content-type": "application/json" });
      // `connectors` stays for existing probes. `devices` lists currently connected legacy Macs;
      // the Relay deliberately has no persistence for previously seen devices.
      response.end(JSON.stringify({
        ok: true,
        connectors: this.options.connectorRegistry.legacyCount,
        devices: [...this.options.connectorRegistry.legacyDeviceIds()]
          .map((deviceId) => ({ deviceId, online: true })),
      }));
      return;
    }

    if (!url.pathname.startsWith("/api/")) {
      sendHttpError(response, 404, "not_found");
      return;
    }

    const authorization = firstHeader(request, "authorization");
    const legacyToken = firstHeader(request, "x-hermes-session-token");
    let connector: TConnector;
    if (authorization) {
      if (legacyToken) {
        this.options.sendAccountError(response, accountErrors.invalidRequest(
          "Account and legacy credentials cannot be used together.",
        ));
        return;
      }
      if (url.pathname.startsWith("/api/mobile/events")) {
        await handleAccountMobileEvents(
          request,
          response,
          url,
          authorization,
          this.options.accountControl,
          this.options.maxBodyBytes,
          this.options.sendAccountError,
        );
        return;
      }
      try {
        connector = await this.options.resolveAccountConnector(authorization);
      } catch (error) {
        this.options.sendAccountError(response, error);
        return;
      }
    } else {
      if (!legacyToken || !this.options.tokensEqual(legacyToken, this.options.appToken)) {
        sendHttpError(response, 401, "unauthorized");
        return;
      }
      const deviceId = firstHeader(request, "x-hermes-device-id")
        ?? this.options.defaultDeviceId;
      const legacyConnector = this.options.connectorRegistry.getLegacy(deviceId);
      if (!legacyConnector) {
        sendHttpError(response, 503, "device_offline");
        return;
      }
      connector = legacyConnector;
    }

    // Relay-owned lifecycle endpoints remain available while the Mac is offline and are never
    // forwarded through the Connector to Hermes.
    if (url.pathname.startsWith("/api/mobile/events")) {
      await handleLegacyMobileEvents(
        request,
        response,
        url,
        this.options.lifecycleEvents,
        this.options.maxBodyBytes,
      );
      return;
    }

    await this.options.httpTunnels.forward(request, response, url, connector);
  }
}
