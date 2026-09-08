import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { test } from "node:test";
import { WebSocket } from "ws";
import { WebSocketTunnelBroker } from "./websocket-tunnel-broker.js";
import { AppWebSocketAuthorizer } from "./app-websocket-authorizer.js";
import { InMemoryConnectorRegistry } from "./connector-registry.js";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { accountErrors, type AccountPrincipal } from "./account/model.js";
import type { GatewayPeer } from "./gateway-peer.js";
import type { IncomingMessage } from "node:http";

test("whole-device grant revocation closes only matching live account tunnels immediately", async () => {
  const connectorSocket = fakeSocket();
  const connector = {
    socket: connectorSocket.socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker(
    10,
    1024 * 1024,
    () => {},
    () => connector,
  );
  const matching = fakeSocket();
  const otherAccount = fakeSocket();
  const otherBinding = fakeSocket();
  broker.open(matching.socket, connector, async () => connector, {
    accountId: "grantee-a",
    bindingId: "binding-1",
    installationId: "installation-a",
    sessionId: "session-a",
  });
  broker.open(otherAccount.socket, connector, async () => connector, {
    accountId: "grantee-b",
    bindingId: "binding-1",
    installationId: "installation-b",
    sessionId: "session-b",
  });
  broker.open(otherBinding.socket, connector, async () => connector, {
    accountId: "grantee-a",
    bindingId: "binding-2",
    installationId: "installation-a",
    sessionId: "session-c",
  });
  await new Promise((resolve) => setImmediate(resolve));

  broker.revokeAccountBinding("grantee-a", "binding-1");

  assert.deepEqual(matching.closes, [{ code: 4403, reason: "device access revoked" }]);
  assert.deepEqual(otherAccount.closes, []);
  assert.deepEqual(otherBinding.closes, []);
});

test("account tunnel performs an immediate authorization recheck after upgrade", async () => {
  const connectorSocket = fakeSocket();
  const connector = {
    socket: connectorSocket.socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker(
    10,
    1024 * 1024,
    () => {},
    () => connector,
  );
  const app = fakeSocket();
  broker.open(app.socket, connector, async () => { throw new Error("revoked during upgrade"); }, {
    accountId: "grantee-a",
    bindingId: "binding-1",
    installationId: "installation-a",
    sessionId: "session-a",
  });
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(app.closes, [{ code: 4403, reason: "account authorization changed" }]);
});

test("session, installation, and account revocations close only their exact account tunnels", async () => {
  const connectorSocket = fakeSocket();
  const connector = {
    socket: connectorSocket.socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker(10, 1024 * 1024, () => {}, () => connector);
  const sessionTarget = fakeSocket();
  const installationTarget = fakeSocket();
  const accountTarget = fakeSocket();
  const unrelated = fakeSocket();
  broker.open(sessionTarget.socket, connector, undefined, {
    accountId: "account-a", bindingId: "binding-1", installationId: "install-a", sessionId: "session-a",
  });
  broker.open(installationTarget.socket, connector, undefined, {
    accountId: "account-a", bindingId: "binding-1", installationId: "install-b", sessionId: "session-b",
  });
  broker.open(accountTarget.socket, connector, undefined, {
    accountId: "account-a", bindingId: "binding-2", installationId: "install-c", sessionId: "session-c",
  });
  broker.open(unrelated.socket, connector, undefined, {
    accountId: "account-b", bindingId: "binding-1", installationId: "install-b", sessionId: "session-b",
  });

  broker.revokeAccountSession("account-a", "session-a");
  broker.revokeAccountInstallation("account-a", "install-b");
  broker.revokeAccount("account-a");

  assert.deepEqual(sessionTarget.closes, [{ code: 4403, reason: "session access revoked" }]);
  assert.deepEqual(installationTarget.closes, [{ code: 4403, reason: "installation access revoked" }]);
  assert.deepEqual(accountTarget.closes, [{ code: 4403, reason: "account access revoked" }]);
  assert.deepEqual(unrelated.closes, []);
});

test("WebSocket authorization routes an active grantee to the owner's Connector without weakening isolation", async () => {
  const connectorSocket = fakeSocket();
  const binding = {
    id: "binding-owner",
    accountId: "owner-account",
    deviceId: "hermes-office",
    generation: 3,
    publicKey: Buffer.alloc(32),
    publicKeyFingerprint: "f".repeat(64),
    status: "active" as const,
  };
  const connector: GatewayPeer = {
    socket: connectorSocket.socket,
    role: "connector",
    deviceId: binding.deviceId,
    routingKey: `account:${binding.id}`,
    mode: "account",
    accountId: binding.accountId,
    binding,
  };
  const registry = new InMemoryConnectorRegistry<GatewayPeer>();
  registry.replaceAccount(binding.id, connector);
  const grantee: AccountPrincipal = {
    account: { id: "grantee-account" },
    installation: {
      id: "grantee-installation",
      kind: "phone",
      platform: "android",
      displayName: "Phone",
    },
    sessionId: "grantee-session",
    refreshFamilyId: "grantee-family",
  };
  let permitted = true;
  const control = {
    authenticate: async () => grantee,
    resolveDevice: async () => {
      if (!permitted) throw accountErrors.deviceNotFound();
      return {
        id: binding.id,
        generation: binding.generation,
        deviceId: binding.deviceId,
        desktopDisplayName: "Office Mac",
        publicKeyFingerprint: binding.publicKeyFingerprint,
        connector: { online: true },
        hermes: { reachable: true },
        gateway: {},
        endToEnd: { healthy: true },
        access: "operator" as const,
        isDefault: false,
      };
    },
  } as unknown as AccountGatewayControl;
  const authorizer = new AppWebSocketAuthorizer({
    accountControl: control,
    connectorRegistry: registry,
    appToken: "legacy-app-token",
    defaultDeviceId: "legacy-device",
    tokensEqual: (left, right) => left === right,
  });
  const request = {
    headers: { authorization: "Bearer grantee-access" },
  } as unknown as IncomingMessage;
  assert.equal(await authorizer.authorize(
    request,
    new URL("https://gateway.example/v2/devices/hermes-office/ws"),
  ), connector);
  assert.deepEqual(authorizer.consumeAccountAccess(request), {
    accountId: "grantee-account",
    bindingId: "binding-owner",
    installationId: "grantee-installation",
    sessionId: "grantee-session",
  });
  permitted = false;
  await assert.rejects(
    authorizer.authorize(
      { headers: { authorization: "Bearer grantee-access" } } as unknown as IncomingMessage,
      new URL("https://gateway.example/v2/devices/hermes-office/ws"),
    ),
    (error: unknown) => typeof error === "object" && error !== null
      && "code" in error && (error as { code: unknown }).code === "HR-BIND-011",
  );
});

function fakeSocket(): {
  socket: WebSocket;
  closes: Array<{ code: number; reason: string }>;
} {
  const events = new EventEmitter();
  const closes: Array<{ code: number; reason: string }> = [];
  const socket = Object.assign(events, {
    readyState: WebSocket.OPEN,
    bufferedAmount: 0,
    send: () => {},
    close: (code: number, reason: string) => {
      closes.push({ code, reason });
      events.emit("close", code, Buffer.from(reason));
    },
  }) as unknown as WebSocket;
  return { socket, closes };
}
