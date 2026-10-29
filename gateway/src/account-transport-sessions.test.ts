import assert from "node:assert/strict";
import test from "node:test";
import { LinkHeartbeat } from "@hermes-remote/protocol";
import { AccountTransportSessions, connectionSnapshot, type TransportMember } from "./account-transport-sessions.js";
import type { GatewayPeer } from "./gateway-peer.js";

function fixture() {
  const registered: GatewayPeer[] = [], removed: GatewayPeer[] = [], closed: string[] = [];
  let now = 0;
  const sessions = new AccountTransportSessions((peer) => registered.push(peer), (peer) => { removed.push(peer); return true; }, () => now);
  const member = (role: "control" | "business", generation = 1, instanceId = "instance-a"): TransportMember => ({
    offer: { version: 1, role, generation, instanceId },
    heartbeat: new LinkHeartbeat(() => {}, () => {}, () => {}, () => now),
    peer: { socket: { close: () => closed.push(`${instanceId}:${role}:${generation}`) },
      binding: { id: "binding", generation: 1 }, accountId: "account" } as unknown as GatewayPeer,
  });
  return { sessions, member, registered, removed, closed, time: (value: number) => { now = value; } };
}

test("control replacement and disconnect preserve the exact routable business peer and fresh local health", () => {
  const f = fixture(); const business = f.member("business"), control = f.member("control"), next = f.member("control", 2);
  assert.equal(f.sessions.register(control), false);
  assert.equal(f.sessions.register(business), true);
  assert.equal(f.sessions.register(control), true);
  f.sessions.health(control, { state: "healthy", reason: "ok", ageMs: 5, sequence: 1 });
  assert.equal(f.sessions.register(next), true);
  assert.deepEqual(f.registered, [business.peer]);
  assert.equal(f.sessions.remove(control), false);
  f.sessions.health(control, { state: "unhealthy", reason: "auth", ageMs: 0, sequence: 99 });
  assert.equal(connectionSnapshot(business.peer, 0)?.local, "healthy");
  assert.equal(f.sessions.remove(next), false);
  assert.equal(connectionSnapshot(business.peer, 0)?.control, "lost");
  assert.equal(connectionSnapshot(business.peer, 60_000)?.local, "unknown");
  assert.deepEqual(f.removed, []);
});

test("role generations and retired instances are fenced, and revocation shuts every lane", () => {
  const f = fixture(); const business = f.member("business"), control = f.member("control");
  f.sessions.register(business); f.sessions.register(control);
  assert.equal(f.sessions.register(f.member("control")), false);
  assert.equal(f.sessions.register(f.member("control", 2, "intruder")), false);
  const replacement = f.member("business", 1, "instance-b");
  assert.equal(f.sessions.register(replacement), true);
  assert.equal(f.sessions.current(control), false);
  assert.equal(f.sessions.register(f.member("business", 100)), false);
  assert.equal(f.sessions.remove(business), false);
  f.sessions.register(f.member("control", 1, "instance-b"));
  f.sessions.revoke("account", "different-binding");
  assert.equal(f.sessions.current(replacement), true);
  f.sessions.revoke("account", "binding");
  assert.equal(f.sessions.current(replacement), false);
  assert.equal(f.closed.length, 4);
});

test("business failure remains visible while the independent control lane stays online", () => {
  const f = fixture(); const business = f.member("business"), control = f.member("control");
  f.sessions.register(business); f.sessions.register(control); f.sessions.remove(business);
  assert.deepEqual(f.removed, [business.peer]);
  assert.equal(connectionSnapshot(control.peer, 0)?.business, "lost");
  assert.equal(connectionSnapshot(control.peer, 0)?.control, "normal");
  assert.equal(f.sessions.register(f.member("business", 2)), true);
  assert.equal(f.sessions.current(control), true);
});

test("disconnecting all lanes retains the generation fence for delayed callbacks", () => {
  const f = fixture(); const business = f.member("business", 3);
  f.sessions.register(business); assert.equal(f.sessions.remove(business), true);
  assert.equal(f.sessions.register(f.member("business", 2)), false);
  assert.equal(f.sessions.register(f.member("business", 4)), true);
});
