import type { IncomingMessage } from "node:http";
import { WebSocket } from "ws";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { AccountModeError, accountErrors } from "./account/model.js";
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

export class AppWebSocketAuthorizer {
  private readonly accountAccess = new WeakMap<IncomingMessage, {
    accountId: string;
    bindingId: string;
    installationId: string;
    sessionId: string;
  }>();

  constructor(private readonly options: AppWebSocketAuthorizerOptions) {}

  async authorize(request: IncomingMessage, url: URL): Promise<GatewayPeer> {
    const authorization = firstHeader(request, "authorization");
    const headerLegacyToken = firstHeader(request, "x-hermes-session-token");
    const queryLegacyToken = url.searchParams.get("token");
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

    if (accountWebSocketDeviceId(url.pathname)) {
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
      });
    }
    return connector;
  }

  consumeAccountAccess(request: IncomingMessage): {
    accountId: string;
    bindingId: string;
    installationId: string;
    sessionId: string;
  } | undefined {
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
