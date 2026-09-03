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
  constructor(private readonly options: AppWebSocketAuthorizerOptions) {}

  async authorize(request: IncomingMessage, url: URL): Promise<GatewayPeer> {
    const authorization = firstHeader(request, "authorization");
    const headerLegacyToken = firstHeader(request, "x-hermes-session-token");
    const queryLegacyToken = url.searchParams.get("token");
    if (authorization) {
      if (headerLegacyToken || queryLegacyToken || url.searchParams.has("device_id")) {
        throw accountErrors.invalidRequest(
          "Account WebSockets cannot include legacy credentials or select a device in the URL.",
        );
      }
      return this.resolveAccountConnector(authorization);
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

  async resolveAccountConnector(authorization: string): Promise<GatewayPeer> {
    const control = this.options.accountControl;
    if (!control) throw accountErrors.featureDisabled();
    const principal = await control.authenticate(authorization);
    const state = await control.getBinding(principal);
    if (state.state !== "bound") throw accountErrors.bindingMissing();
    const binding = state.binding;
    const connector = this.options.connectorRegistry.getAccount(binding.id);
    if (!connector
        || connector.accountId !== principal.account.id
        || connector.binding?.generation !== binding.generation
        || connector.binding.publicKeyFingerprint !== binding.publicKeyFingerprint
        || connector.socket.readyState !== WebSocket.OPEN) {
      throw accountErrors.connectorOffline();
    }
    return connector;
  }
}
