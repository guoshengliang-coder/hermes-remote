import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test, { type TestContext } from "node:test";
import { WebSocket } from "ws";
import { encodeWireMessage, type WireMessage, type TransportOffer } from "@hermes-remote/protocol";
import { AccountConnectorSessionHandler, ACCOUNT_CONNECTOR_PREFLIGHT_TIMEOUT_MS, ACCOUNT_CONNECTOR_HEALTH_INTERVAL_MS } from "./account-connector-session.js";
import { AccountConnectorAdmission } from "./account-connector-admission.js";
import type { AccountGatewayControl } from "./account/account-runtime.js";
import type { BindingProofMaterial } from "./account/account-control-model.js";
import type { GatewayPeer } from "./gateway-peer.js";

const material: BindingProofMaterial = {
  id: "10000000-0000-4000-8000-000000000001", accountId: "account", deviceId: "test-mac",
  generation: 1, publicKey: Buffer.alloc(32), publicKeyFingerprint: "a".repeat(64), status: "active",
};
const settle = () => new Promise<void>((resolve) => setImmediate(resolve));

function fixture(t: TestContext, overrides: Partial<AccountGatewayControl> = {}, offer?: TransportOffer) {
  const socket = Object.assign(new EventEmitter(), {
    readyState: WebSocket.OPEN as number,
    ping(_nonce: string, _mask: unknown, written: () => void) { written(); },
    terminate() { socket.close(1006, "terminated"); },
    close(code: number, reason: string) {
      closes.push({ code, reason });
      Object.assign(socket, { readyState: WebSocket.CLOSED });
      socket.emit("close");
    },
  }) as unknown as WebSocket;
  const closes: Array<{ code: number; reason: string }> = [];
  const sent: WireMessage[] = [];
  const registered: GatewayPeer[] = [];
  const routed: WireMessage[] = [];
  const health: Array<{ hermesReachable: boolean; endToEndHealthy: boolean }> = [];
  const control = {
    issueConnectorChallenge: async () => ({
      bindingId: material.id, generation: 1, publicKeyFingerprint: material.publicKeyFingerprint,
      challenge: Buffer.alloc(32).toString("base64url"), connectionNonce: Buffer.alloc(24).toString("base64url"),
      serverTime: new Date().toISOString(), expiresAt: new Date(Date.now() + 5_000).toISOString(),
    }),
    authenticateConnector: async () => material,
    recordConnectorHealth: async (_material: BindingProofMaterial, value: typeof health[number]) => {
      health.push(value); return true;
    },
    recordConnectorDisconnected: async () => true,
    ...overrides,
  } as unknown as AccountGatewayControl;
  const handler = new AccountConnectorSessionHandler({
    control, admission: new AccountConnectorAdmission(2, 1),
    send: (_socket, message) => sent.push(message),
    route: (_peer, message) => routed.push(message), register: (peer) => registered.push(peer),
    unregister: () => true, reportFailure: () => {},
  });
  handler.attach(socket, "127.0.0.1", offer);
  t.after(() => socket.close(1000, "test complete"));
  const message = (value: WireMessage) => socket.emit("message", Buffer.from(encodeWireMessage(value)));
  const authenticate = async () => {
    message({ type: "connector.identify", version: 2, bindingId: material.id,
      generation: 1, publicKeyFingerprint: material.publicKeyFingerprint });
    await settle();
    message({ type: "connector.authenticate", version: 2, bindingId: material.id,
      generation: 1, publicKeyFingerprint: material.publicKeyFingerprint,
      connectionNonce: Buffer.alloc(24).toString("base64url"), signature: Buffer.alloc(64).toString("base64url") });
    await settle();
  };
  const probe = () => {
    const request = sent.filter((value) => value.type === "connector.preflight.request").at(-1);
    assert.ok(request?.type === "connector.preflight.request");
    return request;
  };
  const reply = (reachable: boolean, requestId = probe().requestId) => message({
    type: "connector.preflight.result", version: 2, requestId, hermesReachable: reachable,
    ...(reachable ? { hermesVersion: "0.21.3" } : {}),
  });
  return { socket, closes, sent, registered, routed, health, authenticate, probe, reply, message, handler };
}

const transport: TransportOffer = { version: 1, role: "business", instanceId: "20000000-0000-4000-8000-000000000001", generation: 1 };
test("negotiated missing health report stays unknown and storage failure cannot turn local healthy into failed", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"] });
  let writes = 0;
  const f = fixture(t, { recordConnectorHealth: async () => { if (++writes > 1) throw new Error("database unavailable"); return true; } }, transport);
  await f.authenticate();
  t.mock.timers.tick(15_000); await settle();
  assert.equal(f.handler.transports.snapshot(material.id, 1)?.local, "unknown");
  t.mock.timers.tick(5_000); await settle();
  f.message({ type: "connector.preflight.result", version: 2, requestId: f.probe().requestId, hermesReachable: true,
    health: { state: "healthy", reason: "ok", ageMs: 0, sequence: 1 } });
  await settle();
  assert.equal(f.handler.transports.snapshot(material.id, 1)?.local, "healthy");
  assert.equal(f.registered.length, 1); assert.equal(f.closes.length, 0);
});

test("proof from a different socket nonce never reaches the authentication service", async (t) => {
  let proofs = 0;
  const f = fixture(t, { authenticateConnector: async () => { proofs++; return material; } });
  f.message({ type: "connector.identify", version: 2, bindingId: material.id, generation: 1, publicKeyFingerprint: material.publicKeyFingerprint });
  await settle();
  f.message({ type: "connector.authenticate", version: 2, bindingId: material.id, generation: 1, publicKeyFingerprint: material.publicKeyFingerprint,
    connectionNonce: Buffer.alloc(24, 1).toString("base64url"), signature: Buffer.alloc(64).toString("base64url") });
  await settle(); assert.equal(proofs, 0); assert.equal(f.closes[0]?.code, 4401);
});

test("an authenticated Connector can finish health preflight after five seconds", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"] });
  const f = fixture(t);
  await f.authenticate();
  t.mock.timers.tick(6_000);
  assert.deepEqual(f.closes, []);
  f.reply(true);
  await settle();
  assert.equal(f.registered.length, 1);
  assert.equal(f.sent.at(-1)?.type, "connector.ready");
});

test("a 15s health timeout registers degraded control, ignores late old replies, then recovers in place", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"] });
  const f = fixture(t);
  await f.authenticate();
  const expired = f.probe().requestId;
  t.mock.timers.tick(ACCOUNT_CONNECTOR_PREFLIGHT_TIMEOUT_MS);
  await settle();
  assert.deepEqual(f.closes, []);
  assert.equal(f.registered.length, 1);
  const peer = f.registered[0];
  assert.equal(peer.hermesReachable, false);
  assert.equal(f.health[0].endToEndHealthy, false);
  f.reply(true, expired); await settle();
  assert.equal(peer.hermesReachable, false);
  t.mock.timers.tick(ACCOUNT_CONNECTOR_HEALTH_INTERVAL_MS); await settle();
  assert.notEqual(f.probe().requestId, expired);
  f.reply(true, expired); await settle();
  assert.equal(peer.hermesReachable, false);
  f.reply(true); await settle();
  assert.equal(peer.hermesReachable, true);
  assert.equal(f.registered.length, 1);
  assert.equal(f.sent.filter((message) => message.type === "connector.ready").length, 1);
  assert.deepEqual(f.closes, []);
});

test("periodic health can fail and recover without stopping lifecycle/control routing or overlapping probes", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"] });
  const f = fixture(t);
  await f.authenticate(); f.reply(true); await settle();
  t.mock.timers.tick(ACCOUNT_CONNECTOR_HEALTH_INTERVAL_MS);
  f.reply(false); await settle();
  assert.equal(f.registered[0].hermesReachable, false);
  const ack: WireMessage = { type: "tunnel.http.response.end", version: 1, requestId: "pending" };
  f.socket.emit("message", Buffer.from(encodeWireMessage(ack)));
  assert.deepEqual(f.routed, [ack]);
  t.mock.timers.tick(ACCOUNT_CONNECTOR_HEALTH_INTERVAL_MS);
  const probeCount = f.sent.filter((message) => message.type === "connector.preflight.request").length;
  t.mock.timers.tick(10_000); await settle();
  assert.equal(f.sent.filter((message) => message.type === "connector.preflight.request").length, probeCount);
  f.reply(true); await settle();
  assert.equal(f.registered[0].hermesReachable, true);
  assert.deepEqual(f.closes, []);
});

test("unauthenticated sockets still expire at five seconds", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const f = fixture(t);
  t.mock.timers.tick(5_000);
  assert.equal(f.closes[0].code, 4401);
  assert.equal(f.registered.length, 0);
  assert.equal(f.health.length, 0);
});

test("a socket closed during authentication or a health write cannot register or schedule rechecks", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let finish!: (value: boolean) => void;
  const order: string[] = [];
  const f = fixture(t, {
    recordConnectorHealth: async () => new Promise<boolean>((resolve) => {
      finish = (value) => { order.push("health"); resolve(value); };
    }),
    recordConnectorDisconnected: async () => { order.push("offline"); return true; },
  });
  await f.authenticate(); f.reply(true);
  f.socket.close(1000, "fixture closed");
  finish(true); await settle();
  t.mock.timers.tick(60_000); await settle();
  assert.equal(f.registered.length, 0);
  assert.equal(f.sent.filter((message) => message.type === "connector.preflight.request").length, 1);
  assert.deepEqual(order, ["health", "offline"]);

  let authenticated!: (material: BindingProofMaterial) => void;
  const closedEarly = fixture(t, {
    authenticateConnector: async () => new Promise((resolve) => { authenticated = resolve; }),
  });
  await closedEarly.authenticate();
  closedEarly.socket.close(1000, "fixture closed during auth");
  authenticated(material); await settle();
  assert.equal(closedEarly.sent.some((message) => message.type === "connector.preflight.request"), false);
});

test("a revoked binding still closes immediately when a health result cannot validate its identity", async (t) => {
  const f = fixture(t, { recordConnectorHealth: async () => false });
  await f.authenticate(); f.reply(true); await settle();
  assert.equal(f.closes[0].code, 4401);
  assert.equal(f.registered.length, 0);
});
