import assert from "node:assert/strict";
import test from "node:test";
import { LinkHealth, LocalHealth, parseTransportOffer } from "./transport-health.js";
import { parseWireMessage } from "./index.js";

test("queued ping and missing pong degrade before loss; stale or duplicate replies cannot revive it", () => {
  const link = new LinkHealth(0);
  assert.equal(link.probe(0), "1");
  assert.equal(link.probe(1), undefined);
  assert.equal(link.tick(2_001), "suspect");
  assert.equal(link.diagnostics(2_001).cause, "write_queue");
  link.written("1", 3_000);
  assert.equal(link.pong("wrong", 4_000), false);
  assert.equal(link.tick(15_001), "suspect");
  assert.equal(link.diagnostics(15_001).cause, "round_trip_missing");
  assert.equal(link.tick(45_000), "lost");
  assert.equal(link.pong("1", 45_001), false);
});

test("negotiated health and lane fields stay strict while legacy messages remain unchanged", () => {
  const ready = { type: "connector.ready", version: 2, bindingId: "10000000-0000-4000-8000-000000000001",
    generation: 1, deviceId: "mac", bindingStatus: "active", routingEnabled: true };
  assert.deepEqual(parseWireMessage(JSON.stringify(ready)), ready);
  const transport = { version: 1, role: "control", instanceId: ready.bindingId, generation: 2 };
  assert.deepEqual(parseWireMessage(JSON.stringify({ ...ready, transport })), { ...ready, transport });
  for (const invalid of [{ ...transport, generation: "2" }, { ...transport, role: "file" }, { ...transport, extra: true }]) {
    assert.throws(() => parseWireMessage(JSON.stringify({ ...ready, transport: invalid })), /transport/);
  }
  const result = { type: "connector.preflight.result", version: 2, requestId: ready.bindingId, hermesReachable: false };
  const health = { state: "degraded", reason: "timeout", ageMs: 10, sequence: 3 };
  assert.deepEqual(parseWireMessage(JSON.stringify({ ...result, health })), { ...result, health });
  for (const invalid of [{ ...health, ageMs: -1 }, { ...health, sequence: 1.5 }, { ...health, state: "offline" }, { ...health, extra: true }]) {
    assert.throws(() => parseWireMessage(JSON.stringify({ ...result, health: invalid })), /health/);
  }
});

test("recovery needs three timely, matched round trips; one-way activity never extends deadline", () => {
  const link = new LinkHealth(0);
  link.probe(0); link.written("1", 0); link.tick(15_000);
  assert.equal(link.pong("1", 16_000), true);
  assert.equal(link.state, "suspect");
  for (let i = 2; i <= 4; i++) {
    const now = 16_000 + i * 5_000;
    const id = link.probe(now)!;
    link.written(id, now);
    link.pong(id, now + 100);
    assert.equal(link.pong(id, now + 101), false);
    assert.equal(link.state, i === 4 ? "normal" : "recovering");
  }
  assert.equal(link.tick(81_100), "lost");
});

test("local timeouts need three failures over 30s; missing reports become unknown", () => {
  const health = new LocalHealth();
  assert.equal(health.snapshot(0).state, "unknown");
  health.observe(false, "timeout", 10_000);
  health.observe(false, "timeout", 25_000);
  assert.equal(health.snapshot(25_000).state, "degraded");
  health.observe(false, "timeout", 40_000);
  assert.equal(health.snapshot(40_000).state, "unhealthy");
  assert.equal(health.snapshot(100_000).state, "unknown");
  health.observe(true, "ok", 100_001);
  assert.equal(health.snapshot(100_001).state, "healthy");
  health.observe(false, "auth", 100_002);
  assert.equal(health.snapshot(100_002).state, "unhealthy");
});

test("capability offer is strictly bounded and is never inferred for legacy clients", () => {
  assert.equal(parseTransportOffer(undefined), undefined);
  assert.equal(parseTransportOffer("1;control;bad;1"), undefined);
  assert.equal(parseTransportOffer("1;control;12345678-1234-1234-1234-123456789abc;0"), undefined);
  assert.deepEqual(parseTransportOffer("1;business;12345678-1234-1234-1234-123456789abc;1"),
    { version: 1, role: "business", instanceId: "12345678-1234-1234-1234-123456789abc", generation: 1 });
});
