import assert from "node:assert/strict";
import test from "node:test";
import { ControlReconnectBackoff } from "./control-reconnect.js";

test("short authenticated connections retain backoff even when they answer a ping", () => {
  let now = 0;
  const backoff = new ControlReconnectBackoff(15_000, () => now, () => 0);
  for (const delay of [1_000, 2_000, 4_000, 8_000, 15_000, 15_000]) {
    backoff.beginAttempt();
    backoff.markReady();
    now += 4_000;
    backoff.markPong();
    now += 1_000;
    const decision = backoff.nextDelay();
    assert.equal(decision.stable, false);
    assert.equal(decision.delayMs, delay);
  }
});

test("an upgrade without routing readiness never earns a reset, even with pongs", () => {
  let now = 0;
  const backoff = new ControlReconnectBackoff(15_000, () => now, () => 0);
  for (const delay of [1_000, 2_000, 4_000]) {
    backoff.beginAttempt();
    now += 60_000;
    backoff.markPong();
    const decision = backoff.nextDelay();
    assert.equal(decision.delayMs, delay);
    assert.equal(decision.readyForMs, 0);
    assert.equal(decision.stable, false);
  }
});

test("only a ready connection lasting 30 seconds with a recent pong resets the wait", () => {
  let now = 0;
  const backoff = new ControlReconnectBackoff(15_000, () => now, () => 0);
  backoff.nextDelay();
  backoff.beginAttempt();
  backoff.markReady();
  now = 15_000;
  backoff.markPong();
  backoff.markReady(); // Duplicate ready must not postpone stability.
  now = 29_999;
  assert.equal(backoff.nextDelay().stable, false);
  now = 30_000;
  assert.deepEqual(backoff.nextDelay(), {
    attempt: 1, delayMs: 1_000, stable: true, readyForMs: 30_000, lastPongAgoMs: 15_000,
  });
  backoff.beginAttempt();
  now += 1_000;
  assert.equal(backoff.nextDelay().delayMs, 2_000);
});

test("a long-lived but silent socket does not reset backoff", () => {
  let now = 0;
  const backoff = new ControlReconnectBackoff(15_000, () => now, () => 0);
  backoff.nextDelay();
  backoff.beginAttempt();
  backoff.markReady();
  now = 60_000;
  assert.equal(backoff.nextDelay().delayMs, 2_000); // Never answered.
  backoff.markPong();
  now += 15_001;
  const decision = backoff.nextDelay();
  assert.equal(decision.stable, false);
  assert.equal(decision.delayMs, 4_000);
});

test("previous-connection pongs cannot qualify a new connection", () => {
  let now = 0;
  const backoff = new ControlReconnectBackoff(15_000, () => now, () => 0);
  backoff.markReady();
  backoff.markPong();
  backoff.nextDelay();
  backoff.beginAttempt();
  backoff.markReady();
  now = 30_000;
  assert.equal(backoff.nextDelay().stable, false);
});

test("jitter stays bounded and remains spread at the cap", () => {
  let random = 0;
  const backoff = new ControlReconnectBackoff(15_000, () => 0, () => random);
  assert.equal(backoff.nextDelay().delayMs, 1_000);
  random = 0.999999;
  assert.equal(backoff.nextDelay().delayMs, 3_999);
  backoff.nextDelay();
  backoff.nextDelay();
  assert.equal(backoff.nextDelay().delayMs, 29_999);
  random = 0;
  assert.equal(backoff.nextDelay().delayMs, 15_000);
});
