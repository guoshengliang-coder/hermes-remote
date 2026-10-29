import assert from "node:assert/strict";
import test from "node:test";
import { LocalHealthMonitor } from "./local-health-monitor.js";

test("cached health is single flight, does not reset freshness on read and expires to unknown", async (t) => {
  let now = 0; let probes = 0; let finish!: (value: { reachable: boolean }) => void;
  const monitor = new LocalHealthMonitor(() => { probes++; return new Promise((resolve) => { finish = resolve; }); }, () => now);
  t.after(() => monitor.stop());
  const first = monitor.start(); const second = monitor.start();
  assert.equal(first, second); assert.equal(probes, 1);
  finish({ reachable: true }); await first;
  assert.equal(monitor.snapshot().state, "healthy");
  now = 60_000; assert.equal(monitor.snapshot().state, "unknown");
  assert.equal(monitor.snapshot().sequence, 1);
});

test("probe deadlines remain bounded and explicit auth failure is distinct", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let now = 0; let auth = false;
  const monitor = new LocalHealthMonitor(async () => auth ? { reachable: false, reason: "auth" } : new Promise(() => {}), () => now, 10);
  t.after(() => monitor.stop());
  const pending = monitor.start(); now = 10; t.mock.timers.tick(10); await pending;
  assert.equal(monitor.snapshot().state, "degraded");
  assert.equal(monitor.snapshot().reason, "timeout");
  auth = true; await monitor.start();
  assert.equal(monitor.snapshot().state, "unhealthy");
  assert.equal(monitor.snapshot().reason, "auth");
});
