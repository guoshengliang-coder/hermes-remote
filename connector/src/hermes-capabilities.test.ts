import assert from "node:assert/strict";
import test from "node:test";
import { HermesCapabilityMonitor } from "./hermes-capabilities.js";
const bytes = (value: unknown) => Buffer.from(JSON.stringify(value));
test("passive evidence distinguishes unavailable APIs from faults and never crosses profiles", () => {
  const monitor = new HermesCapabilityMonitor();
  monitor.request("a", bytes({ id: 1, method: "image.attach", params: { profile: "work" } }), false);
  monitor.response("a", bytes({ id: 1, error: { code: -32601, message: "private" } }), false);
  assert.equal(monitor.report("work").features.find(x => x.feature === "images")?.state, "unknown");
  monitor.request("a", bytes({ id: 4, method: "image.attach_bytes", params: { profile: "work" } }), false);
  monitor.response("a", bytes({ id: 4, error: { code: -32601 } }), false);
  assert.equal(monitor.report("work").features.find(x => x.feature === "images")?.state, "unsupported");
  assert.ok(monitor.report().features.every(x => x.state === "unknown"));
  monitor.request("a", bytes({ id: 2, method: "prompt.submit", params: { profile: "default" } }), false);
  monitor.response("a", bytes({ id: 2, error: { code: 4000 } }), false);
  assert.equal(monitor.report().features[0].state, "fault");
  assert.ok(!JSON.stringify(monitor.report("work")).includes("private"));
});
test("RPC ids, connection generations, closed tunnels and expiry isolate observations", () => {
  let now = 1_000;
  const monitor = new HermesCapabilityMonitor(() => now, 5_000);
  monitor.request("a", bytes({ id: 1, method: "prompt.submit", params: { profile: "default" } }), false);
  monitor.response("b", bytes({ id: 1, result: {} }), false);
  assert.equal(monitor.report().features[0].state, "unknown");
  monitor.response("a", bytes({ id: 1, result: {} }), false);
  assert.equal(monitor.report().features[0].state, "supported");
  now += 5_000;
  assert.equal(monitor.report().features[0].state, "unknown");
  monitor.request("a", bytes({ id: 2, method: "prompt.submit", params: { profile: "default" } }), false);
  monitor.reset();
  monitor.response("a", bytes({ id: 2, result: {} }), false);
  assert.equal(monitor.report().features[0].state, "unknown");
  monitor.request("a", bytes({ id: 3, method: "prompt.submit", params: { profile: "default" } }), false);
  monitor.close("a");
  monitor.response("a", bytes({ id: 3, result: {} }), false);
  assert.equal(monitor.report().features[0].state, "unknown");
});
test("a missing legacy answer never disables the newer question protocol", () => {
  const monitor = new HermesCapabilityMonitor();
  monitor.request("a", bytes({ id: 1, method: "clarify.respond", params: { profile: "default" } }), false);
  monitor.response("a", bytes({ id: 1, error: { code: -32601 } }), false);
  assert.equal(monitor.report().features.find(x => x.feature === "clarify")?.state, "unknown");
  monitor.http("GET", "/api/cron/jobs?profile=work", 404);
  assert.equal(monitor.report("work").features.find(x => x.feature === "cron")?.state, "unsupported");
});

test("new server requests establish only their own question and profile's answer capability", () => {
  const monitor = new HermesCapabilityMonitor();
  monitor.response("a", bytes({ id: "srq-123", method: "approval", params: { profile: "work", private: "question" } }), false);
  monitor.request("a", bytes({ id: 3, method: "request.answer", params: { id: "srq-123", result: { choice: "once" } } }), false);
  monitor.response("a", bytes({ id: 3, result: { status: "ok" } }), false);
  assert.equal(monitor.report("work").features.find(x => x.feature === "approval")?.state, "supported");
  assert.equal(monitor.report().features.find(x => x.feature === "approval")?.state, "unknown");
  monitor.http("GET", "/api/sessions/missing/messages?profile=default", 404);
  assert.equal(monitor.report().features.find(x => x.feature === "history")?.state, "fault");
});


test("an optional cron sub-route cannot disable the available cron list", () => {
  const monitor = new HermesCapabilityMonitor();
  monitor.http("GET", "/api/cron/jobs?profile=default", 200);
  monitor.http("GET", "/api/cron/delivery-targets?profile=default", 405);
  assert.equal(monitor.report().features.find(x => x.feature === "cron")?.state, "supported");
});


test("traffic without a known profile cannot populate the default profile's evidence", () => {
  const monitor = new HermesCapabilityMonitor();
  monitor.request("a", bytes({ id: 1, method: "prompt.submit" }), false);
  monitor.response("a", bytes({ id: 1, result: {} }), false);
  monitor.http("GET", "/api/sessions/session-1/messages", 200);
  assert.ok(monitor.report("default").features.every(x => x.state === "unknown"));
});
