import assert from "node:assert/strict";
import test from "node:test";
import { WebSocket } from "ws";
import { AppWebSocketAuthorizer } from "./app-websocket-authorizer.js";
import { InMemoryConnectorRegistry } from "./connector-registry.js";
import type { GatewayPeer } from "./gateway-peer.js";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import { AccountModeError, type AccountPrincipal } from "./account/model.js";
import type { AccountDevice, BindingProofMaterial } from "./account/account-control-model.js";
import { LinkHeartbeat } from "@hermes-remote/protocol";
import { AccountTransportSessions } from "./account-transport-sessions.js";

test("degraded Hermes returns retryable CONN-006 for new traffic, keeps authorization valid, and recovers on the same peer", async () => {
  const binding = { id: "binding", accountId: "owner", generation: 1,
    deviceId: "mac", publicKeyFingerprint: "fingerprint", status: "active" } as BindingProofMaterial;
  const principal = { account: { id: "owner" }, installation: { id: "phone" }, sessionId: "session" } as AccountPrincipal;
  const peer: GatewayPeer = { socket: { readyState: WebSocket.OPEN } as WebSocket,
    role: "connector", mode: "account", deviceId: "mac", routingKey: "account:binding",
    accountId: "owner", binding, hermesReachable: false };
  const registry = new InMemoryConnectorRegistry<GatewayPeer>();
  registry.replaceAccount(binding.id, peer);
  let sessionLive = true;
  const control = {
    authenticate: async () => principal, isSessionLive: async () => sessionLive,
    resolveDevice: async () => binding as unknown as AccountDevice,
  } as unknown as AccountGatewayControl;
  const authorizer = new AppWebSocketAuthorizer({ accountControl: control, connectorRegistry: registry,
    appToken: "fixture-app", defaultDeviceId: "mac", tokensEqual: (a, b) => a === b });
  const unavailable = (error: unknown) => {
    assert.ok(error instanceof AccountModeError);
    assert.equal(error.code, "HR-CONN-006"); assert.equal(error.status, 502);
    assert.equal(error.retryable, true); assert.equal(error.recoveryAction, "retry");
    assert.match(error.message, /Hermes 没有响应/); assert.match(error.message, /Hermes on it isn't responding/);
    assert.equal(error.message.includes("fixture-app"), false);
    return true;
  };
  await assert.rejects(authorizer.resolveAccountConnector("fixture-authorization"), unavailable);
  await assert.rejects(authorizer.connectorFor(principal, "mac", undefined, true), unavailable);
  assert.equal(registry.accountCount, 1, "degradation must not unregister the control connection");
  assert.equal(await authorizer.revalidateAccountConnector(principal, "mac"), peer,
    "an existing WS revalidates access independently of local Hermes health");
  peer.hermesReachable = true;
  assert.equal(await authorizer.resolveAccountConnector("fixture-authorization"), peer);
  delete peer.hermesReachable;
  assert.equal(await authorizer.resolveAccountConnector("fixture-authorization"), peer,
    "legacy/internal peers without this optional metadata retain their behavior");
  sessionLive = false;
  await assert.rejects(authorizer.revalidateAccountConnector(principal), { code: "HR-AUTH-004" });
  peer.binding = { ...binding, generation: 2 };
  await assert.rejects(authorizer.resolveAccountConnector("fixture-authorization"), { code: "HR-CONN-005" });
});

test("negotiated congestion/unknown health do not gate new chat; explicit local failure and revocation still do", async () => {
  const binding = { id: "binding", accountId: "owner", generation: 1, deviceId: "mac", publicKeyFingerprint: "fingerprint", status: "active" } as BindingProofMaterial;
  const principal = { account: { id: "owner" }, installation: { id: "phone" }, sessionId: "session" } as AccountPrincipal;
  const peer: GatewayPeer = { socket: { readyState: WebSocket.OPEN } as WebSocket, role: "connector", mode: "account",
    deviceId: "mac", routingKey: "account:binding", accountId: "owner", binding, hermesReachable: false };
  const registry = new InMemoryConnectorRegistry<GatewayPeer>();
  const sessions = new AccountTransportSessions((value) => registry.replaceAccount(binding.id, value), () => true);
  const member = { peer, offer: { version: 1 as const, role: "business" as const, instanceId: "a", generation: 1 },
    heartbeat: new LinkHeartbeat(() => {}, () => {}, () => {}) };
  sessions.register(member);
  const authorizer = new AppWebSocketAuthorizer({ accountControl: {
    authenticate: async () => principal, resolveDevice: async () => binding,
  } as unknown as AccountGatewayControl, connectorRegistry: registry, appToken: "fixture", defaultDeviceId: "mac", tokensEqual: (a, b) => a === b });
  for (const [sequence, state] of ["unknown", "degraded", "healthy"].entries()) {
    sessions.health(member, { state: state as "unknown" | "degraded" | "healthy", reason: "timeout", ageMs: 0, sequence });
    assert.equal(await authorizer.resolveAccountConnector("fixture"), peer);
  }
  sessions.health(member, { state: "unhealthy", reason: "auth", ageMs: 0, sequence: 3 });
  await assert.rejects(authorizer.resolveAccountConnector("fixture"), { code: "HR-CONN-006" });
  sessions.health(member, { state: "unhealthy", reason: "auth", ageMs: 60_000, sequence: 4 });
  assert.equal(await authorizer.resolveAccountConnector("fixture"), peer, "expired failure becomes unknown, allowing a real retry");
  peer.binding = { ...binding, generation: 2 };
  await assert.rejects(authorizer.resolveAccountConnector("fixture"), { code: "HR-CONN-005" });
});
