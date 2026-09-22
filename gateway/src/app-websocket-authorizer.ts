import type { IncomingMessage } from "node:http";
import { WebSocket } from "ws";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { AccountModeError, accountErrors, type AccountPrincipal } from "./account/model.js";
import type { ConnectorRegistry } from "./connector-registry.js";
import type { GatewayPeer } from "./gateway-peer.js";
import { firstHeader } from "./http-utils.js";

interface AppWebSocketAuthorizerOptions {
  accountControl?: AccountGatewayControl;
  connectorRegistry: ConnectorRegistry<GatewayPeer>;
  appToken: string;
  defaultDeviceId: string;
  tokensEqual(actual: string, expected: string): boolean;
}

export interface AccountWebSocketAccess {
  accountId: string;
  bindingId: string;
  installationId: string;
  sessionId: string;
  // Set when the upgrade was authorized by the Web app's session cookie rather than a bearer
  // header; the tunnel is then revalidated by session instead of by that one access token.
  webPrincipal?: AccountPrincipal;
}

export class AppWebSocketAuthorizer {
  private readonly accountAccess = new WeakMap<IncomingMessage, AccountWebSocketAccess>();

  constructor(private readonly options: AppWebSocketAuthorizerOptions) {}

  async authorize(request: IncomingMessage, url: URL): Promise<GatewayPeer> {
    const authorization = firstHeader(request, "authorization");
    const headerLegacyToken = firstHeader(request, "x-hermes-session-token");
    const queryLegacyToken = url.searchParams.get("token");
    const web = this.options.accountControl?.webDeviceAccess;
    const webCookie = web?.presents(request) ?? false;
    if (authorization && webCookie) {
      throw accountErrors.invalidRequest(
        "Account authorization and a Web session cookie cannot be used together.",
      );
    }
    if (authorization) {
      if (headerLegacyToken || queryLegacyToken || url.searchParams.has("device_id")) {
        throw accountErrors.invalidRequest(
          "Account WebSockets cannot include legacy credentials or legacy device query parameters.",
        );
      }
      return this.resolveAccountConnector(
        authorization,
        accountWebSocketDeviceId(url.pathname),
        request,
      );
    }

    const accountDeviceId = accountWebSocketDeviceId(url.pathname);
    if (accountDeviceId && web && webCookie) {
      // The cookie is the only credential here; a query string could only carry a token that
      // would end up in logs, so none is accepted.
      if (headerLegacyToken || url.search) {
        throw accountErrors.invalidRequest(
          "Web WebSockets cannot include legacy credentials or query parameters.",
        );
      }
      const principal = await web.authenticateUpgrade(request);
      return this.connectorFor(principal, accountDeviceId, request, true);
    }
    if (accountDeviceId) {
      throw accountErrors.sessionExpired();
    }

    const token = queryLegacyToken ?? headerLegacyToken;
    if (!token || !this.options.tokensEqual(token, this.options.appToken)) {
      throw new AccountModeError(401, "unauthorized", "Unauthorized", false, "none");
    }
    const deviceId = url.searchParams.get("device_id") ?? this.options.defaultDeviceId;
    const connector = this.options.connectorRegistry.getLegacy(deviceId);
    if (!connector) {
      throw new AccountModeError(
        503,
        "device_offline",
        "Mac connector offline",
        true,
        "retry",
      );
    }
    return connector;
  }

  async resolveAccountConnector(
    authorization: string,
    deviceId?: string,
    request?: IncomingMessage,
  ): Promise<GatewayPeer> {
    const control = this.options.accountControl;
    if (!control) throw accountErrors.featureDisabled();
    const principal = await control.authenticate(authorization);
    return this.connectorFor(principal, deviceId, request, false);
  }

  async connectorFor(
    principal: AccountPrincipal,
    deviceId?: string,
    request?: IncomingMessage,
    web = false,
  ): Promise<GatewayPeer> {
    const control = this.options.accountControl;
    if (!control) throw accountErrors.featureDisabled();
    const binding = await control.resolveDevice(principal, deviceId);
    const connector = this.options.connectorRegistry.getAccount(binding.id);
    if (!connector
        || connector.binding?.id !== binding.id
        || connector.accountId !== connector.binding.accountId
        || connector.binding?.generation !== binding.generation
        || connector.binding.publicKeyFingerprint !== binding.publicKeyFingerprint
        || connector.socket.readyState !== WebSocket.OPEN) {
      throw accountErrors.connectorOffline();
    }
    if (request) {
      this.accountAccess.set(request, {
        accountId: principal.account.id,
        bindingId: binding.id,
        installationId: principal.installation.id,
        sessionId: principal.sessionId,
        ...(web ? { webPrincipal: principal } : {}),
      });
    }
    return connector;
  }

  consumeAccountAccess(request: IncomingMessage): AccountWebSocketAccess | undefined {
    const access = this.accountAccess.get(request);
    this.accountAccess.delete(request);
    return access;
  }
}

function accountWebSocketDeviceId(pathname: string): string | undefined {
  const match = /^\/v2\/devices\/([^/]+)\/ws$/.exec(pathname);
  if (!match) return undefined;
  try {
    const deviceId = decodeURIComponent(match[1]);
    if (deviceId.length < 1 || deviceId.length > 128 || /[\u0000-\u001f\u007f]/.test(deviceId)) {
      throw new Error("invalid_device_id");
    }
    return deviceId;
  } catch {
    throw accountErrors.invalidRequest("deviceId is invalid.");
  }
}
