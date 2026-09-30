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
import { WebDeviceAccess } from "./account/web-device-access.js";
import { WebSessionSecurity } from "./account/web-session-security.js";
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
  // HG-140: only a definitive authorization end-state may close as 4403 from the recheck. A
  // transient error (a plain database hiccup, a connector blink) is tolerated and audited
  // instead — covered in websocket-revalidation.test.ts.
  broker.open(app.socket, connector, async () => {
    throw accountErrors.sessionExpired();
  }, {
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
    principal: grantee,
  });
  const voiceRequest = {
    headers: { authorization: "Bearer grantee-access" },
  } as unknown as IncomingMessage;
  assert.equal(await authorizer.authorize(
    voiceRequest,
    new URL("https://gateway.example/v2/devices/hermes-office/voice"),
  ), connector);
  assert.equal(authorizer.consumeAccountAccess(voiceRequest)?.bindingId, "binding-owner");
  const origin = "https://web.example.test";
  const browser = { ...grantee, installation: { ...grantee.installation, kind: "browser" as const, platform: "web" as const } };
  const webAuthorizer = new AppWebSocketAuthorizer({
    accountControl: { ...control, webDeviceAccess: new WebDeviceAccess({
      security: new WebSessionSecurity(origin), authenticate: async () => browser, isSessionLive: async () => true,
    }) } as AccountGatewayControl,
    connectorRegistry: registry, appToken: "legacy", defaultDeviceId: "legacy", tokensEqual: (a, b) => a === b,
  });
  const cookie = `__Host-hermes_go_access=hga_${"a".repeat(43)}`;
  const voiceUrl = new URL(`${origin}/v2/devices/hermes-office/voice`);
  const browserRequest = { headers: { cookie, origin } } as unknown as IncomingMessage;
  assert.equal(await webAuthorizer.authorize(browserRequest, voiceUrl), connector);
  assert.equal(webAuthorizer.consumeAccountAccess(browserRequest)?.web, true);
  assert.equal(webAuthorizer.consumeAccountAccess(browserRequest), undefined, "upgrade identity is consumed once");
  for (const headers of [{ cookie }, { cookie, origin: "https://evil.example.test" }, { cookie, origin, authorization: "Bearer another" }]) {
    await assert.rejects(webAuthorizer.authorize({ headers } as IncomingMessage, voiceUrl));
  }
  await assert.rejects(webAuthorizer.authorize({ headers: { cookie, origin } } as IncomingMessage, new URL(`${voiceUrl}?token=secret`)));
  permitted = false;
  await assert.rejects(
    authorizer.authorize(
      { headers: { authorization: "Bearer grantee-access" } } as unknown as IncomingMessage,
      new URL("https://gateway.example/v2/devices/hermes-office/voice"),
    ),
    (error: unknown) => typeof error === "object" && error !== null
      && "code" in error && (error as { code: unknown }).code === "HR-BIND-011",
  );
});

test("phone tunnel follows its session across access rotation and expiry, but closes on revocation", async () => {
  const socket = fakeSocket();
  const binding = {
    id: "binding-1", accountId: "account-1", deviceId: "mac-1", generation: 1,
    publicKey: Buffer.alloc(32), publicKeyFingerprint: "f".repeat(64), status: "active" as const,
  };
  const connector: GatewayPeer = {
    socket: socket.socket, role: "connector", deviceId: "mac-1", routingKey: "account:binding-1",
    mode: "account", accountId: "account-1", binding,
  };
  const registry = new InMemoryConnectorRegistry<GatewayPeer>();
  registry.replaceAccount(binding.id, connector);
  const principal: AccountPrincipal = {
    account: { id: "account-1" },
    installation: { id: "install-1", kind: "phone", platform: "android", displayName: "Phone" },
    sessionId: "session-1", refreshFamilyId: "family-1",
  };
  const acceptedBearers = new Set(["Bearer original", "Bearer unrotated"]);
  let sessionLive = true;
  const control = {
    authenticate: async (authorization: string | undefined) => {
      if (!authorization || !acceptedBearers.has(authorization)) throw accountErrors.sessionExpired();
      return principal;
    },
    isSessionLive: async (candidate: AccountPrincipal) => {
      assert.equal(candidate.sessionId, principal.sessionId);
      assert.equal(candidate.installation.id, principal.installation.id);
      return sessionLive;
    },
    resolveDevice: async () => ({
      id: binding.id, generation: binding.generation, deviceId: binding.deviceId,
      publicKeyFingerprint: binding.publicKeyFingerprint,
    }),
  } as unknown as AccountGatewayControl;
  const authorizer = new AppWebSocketAuthorizer({
    accountControl: control, connectorRegistry: registry, appToken: "legacy",
    defaultDeviceId: "legacy", tokensEqual: (a, b) => a === b,
  });
  const request = { headers: { authorization: "Bearer original" } } as unknown as IncomingMessage;
  assert.equal(await authorizer.authorize(request, new URL("https://gateway.test/v2/devices/mac-1/ws")), connector);
  const access = authorizer.consumeAccountAccess(request);
  assert.ok(access);
  const app = fakeSocket();
  const broker = new WebSocketTunnelBroker(10, 1024 * 1024, () => {}, () => connector);
  broker.open(app.socket, connector, () => authorizer.revalidateAccountConnector(principal, "mac-1"), access);
  await new Promise((resolve) => setImmediate(resolve));
  acceptedBearers.delete("Bearer original"); // refresh replaced the original hash immediately.
  await assert.rejects(authorizer.resolveAccountConnector("Bearer original", "mac-1"), {
    code: "HR-AUTH-003",
  });
  assert.equal(await authorizer.revalidateAccountConnector(principal, "mac-1"), connector);
  const expiryRequest = { headers: { authorization: "Bearer unrotated" } } as unknown as IncomingMessage;
  assert.equal(await authorizer.authorize(expiryRequest, new URL("https://gateway.test/v2/devices/mac-1/ws")), connector);
  const expiryAccess = authorizer.consumeAccountAccess(expiryRequest);
  assert.ok(expiryAccess);
  const expiryApp = fakeSocket();
  broker.open(expiryApp.socket, connector,
    () => authorizer.revalidateAccountConnector(principal, "mac-1"), expiryAccess);
  await new Promise((resolve) => setImmediate(resolve));
  acceptedBearers.delete("Bearer unrotated"); // natural expiry, with no refresh rotation.
  await assert.rejects(authorizer.resolveAccountConnector("Bearer unrotated", "mac-1"), {
    code: "HR-AUTH-003",
  });
  assert.equal(await authorizer.revalidateAccountConnector(principal, "mac-1"), connector);
  assert.deepEqual(app.closes, []);
  assert.deepEqual(expiryApp.closes, []);
  sessionLive = false; // idle beyond the session-liveness bound, or a missed revoke event.
  await assert.rejects(authorizer.revalidateAccountConnector(principal, "mac-1"), {
    code: "HR-AUTH-004",
  });
  broker.revokeAccountSession("account-1", "session-1");
  assert.deepEqual(app.closes, [{ code: 4403, reason: "session access revoked" }]);
  assert.deepEqual(expiryApp.closes, [{ code: 4403, reason: "session access revoked" }]);
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
