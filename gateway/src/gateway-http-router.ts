import type { IncomingMessage, ServerResponse } from "node:http";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { accountErrors } from "./account/model.js";
import type { ConnectorRegistry } from "./connector-registry.js";
import type { HttpTunnelBroker } from "./http-tunnel-broker.js";
import { firstHeader, sendHttpError } from "./http-utils.js";
import type { LifecycleEventStore } from "./lifecycle-event-store.js";
import { handleAccountMobileEvents, handleLegacyMobileEvents } from "./mobile-event-handler.js";
import type { ServerReleaseController } from "./server-release.js";
import { RESEND_WEBHOOK_PATH } from "./account/resend-webhook-controller.js";

interface HttpConnector {
  socket: import("ws").WebSocket;
  deviceId: string;
  routingKey: string;
}

interface AccountHttpController {
  handle(request: IncomingMessage, response: ServerResponse, url: URL): Promise<void>;
}

interface ResendWebhookController {
  handle(request: IncomingMessage, response: ServerResponse): Promise<void>;
}

interface GatewayHttpRouterOptions<TConnector extends HttpConnector> {
  accountController: AccountHttpController;
  resendWebhook?: ResendWebhookController;
  accountControl?: AccountGatewayControl;
  appToken: string;
  defaultDeviceId: string;
  maxBodyBytes: number;
  connectorRegistry: ConnectorRegistry<TConnector>;
  lifecycleEvents: LifecycleEventStore;
  httpTunnels: HttpTunnelBroker;
  resolveAccountConnector(authorization: string, deviceId?: string): Promise<TConnector>;
  sendAccountError(response: ServerResponse, error: unknown): void;
  tokensEqual(actual: string, expected: string): boolean;
  serverRelease: ServerReleaseController;
}

export class GatewayHttpRouter<TConnector extends HttpConnector> {
  constructor(private readonly options: GatewayHttpRouterOptions<TConnector>) {}

  async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (await this.options.serverRelease.handle(request, response, url)) return;
    if (url.pathname === RESEND_WEBHOOK_PATH && this.options.resendWebhook) {
      await this.options.resendWebhook.handle(request, response);
      return;
    }
    let deviceApiRoute: ReturnType<typeof accountDeviceApiRoute>;
    try {
      deviceApiRoute = accountDeviceApiRoute(url);
    } catch (error) {
      this.options.sendAccountError(response, error);
      return;
    }
    if (deviceApiRoute) {
      const authorization = firstHeader(request, "authorization");
      if (!authorization) {
        this.options.sendAccountError(response, accountErrors.sessionExpired());
        return;
      }
      if (firstHeader(request, "x-hermes-session-token")) {
        this.options.sendAccountError(response, accountErrors.invalidRequest(
          "Device-scoped routes require account authorization only.",
        ));
        return;
      }
      try {
        const connector = await this.options.resolveAccountConnector(
          authorization,
          deviceApiRoute.deviceId,
        );
        await this.options.httpTunnels.forward(request, response, deviceApiRoute.targetUrl, connector);
      } catch (error) {
        this.options.sendAccountError(response, error);
      }
      return;
    }
    if (url.pathname.startsWith("/v2/") || url.pathname === "/account"
        || url.pathname.startsWith("/account/")) {
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

function accountDeviceApiRoute(url: URL): { deviceId: string; targetUrl: URL } | undefined {
  const match = /^\/v2\/devices\/([^/]+)\/api(\/.*)?$/.exec(url.pathname);
  if (!match) return undefined;
  try {
    const deviceId = decodeURIComponent(match[1]);
    if (deviceId.length < 1 || deviceId.length > 128 || /[\u0000-\u001f\u007f]/.test(deviceId)) {
      throw new Error("invalid_device_id");
    }
    const targetUrl = new URL(url);
    targetUrl.pathname = `/api${match[2] ?? ""}`;
    return { deviceId, targetUrl };
  } catch {
    throw accountErrors.invalidRequest("deviceId is invalid.");
  }
}
