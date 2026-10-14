import assert from "node:assert/strict";
import test from "node:test";
import { randomUUID } from "node:crypto";
import type { ConnectorPreflightResult } from "@hermes-remote/protocol";
import { ConnectorHealthResponder, probeHermesHealth, CONNECTOR_HEALTH_PROBE_TIMEOUT_MS } from "./connector-health.js";

const request = () => ({ type: "connector.preflight.request" as const, version: 2 as const,
  requestId: randomUUID(), sentAt: new Date().toISOString() });
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const settle = () => new Promise<void>((resolve) => setImmediate(resolve));

test("lightweight health verifies credentials on a protected read and never calls full status", async () => {
  const seen: string[] = [];
  const signal = new AbortController().signal;
  const result = await probeHermesHealth({ hasCredentials: true, request: async (path, init) => {
    seen.push(path); assert.equal(init.signal, signal);
    return path === "/api/health" ? json({ ok: true, version: "0.21.3", auth_required: false })
      : json({ active: "default", current: "default" });
  } }, signal);
  assert.deepEqual(result, { reachable: true, version: "0.21.3" });
  assert.deepEqual(seen, ["/api/health", "/api/profiles/active"]);
});

test("a public 200 health/status cannot turn invalid credentials into healthy", async () => {
  for (const missingHealth of [false, true]) {
    const paths: string[] = [];
    const result = await probeHermesHealth({ hasCredentials: true, request: async (path) => {
      paths.push(path);
      if (path === "/api/health") return missingHealth ? json({}, 404) : json({ ok: true, version: "0.21.3" });
      if (path === "/api/status") return json({ version: "0.20.6" });
      return json({ detail: "fixture-secret must stay on Mac" }, 401);
    } }, new AbortController().signal);
    assert.deepEqual(result, { reachable: false });
    assert.equal(paths.includes("/api/sessions?limit=1"), false, "401 must not fall back to another path");
    assert.equal(JSON.stringify(result).includes("fixture-secret"), false);
  }
});

test("older upstreams missing optional health/profile reads use bounded existing routes", async () => {
  for (const missing of [404, 405]) {
    const seen: string[] = [];
    const result = await probeHermesHealth({ hasCredentials: true, request: async (path) => {
      seen.push(path);
      if (path === "/api/status") return json({ version: "0.20.6" });
      if (path === "/api/sessions?limit=1") return json({ sessions: [] });
      return json({}, missing);
    } }, new AbortController().signal);
    assert.deepEqual(result, { reachable: true, version: "0.20.6" });
    assert.deepEqual(seen, ["/api/health", "/api/status", "/api/profiles/active", "/api/sessions?limit=1"]);
  }
});

test("upstream server failures do not hide behind compatibility fallbacks", async () => {
  for (const failHealth of [true, false]) {
    const seen: string[] = [];
    assert.deepEqual(await probeHermesHealth({ hasCredentials: true, request: async (path) => {
      seen.push(path);
      return path === "/api/health" && !failHealth ? json({ ok: true }) : json({}, 503);
    } }, new AbortController().signal), { reachable: false });
    assert.deepEqual(seen, failHealth ? ["/api/health"] : ["/api/health", "/api/profiles/active"]);
  }
});

test("errors, malformed/oversized health, missing credentials and expired shared deadline fail closed", async () => {
  for (const response of [json({ ok: false }), new Response("not JSON"), new Response(" ".repeat(70_000))]) {
    assert.deepEqual(await probeHermesHealth({ hasCredentials: false, request: async () => response },
      new AbortController().signal), { reachable: false });
  }
  assert.deepEqual(await probeHermesHealth({ hasCredentials: false,
    request: async () => json({ ok: true, auth_required: true }) }, new AbortController().signal), { reachable: false });
  const controller = new AbortController();
  assert.deepEqual(await probeHermesHealth({ hasCredentials: true, request: async (path) => {
    if (path === "/api/health") return json({ ok: true });
    controller.abort(); return json({ active: "default" });
  } }, controller.signal), { reachable: false });
});

test("a stalled probe expires at 10s and returns degraded health while the responder stays usable", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const sent: ConnectorPreflightResult[] = [];
  let signal!: AbortSignal;
  let healthy = false;
  const responder = new ConnectorHealthResponder(async (s) => {
    signal = s;
    return healthy ? { reachable: true, version: "0.21.3" } : new Promise(() => {});
  }, (result) => sent.push(result));
  t.after(() => responder.stop());
  const first = request();
  const waiting = responder.respond(first);
  t.mock.timers.tick(CONNECTOR_HEALTH_PROBE_TIMEOUT_MS - 1);
  await settle(); assert.equal(sent.length, 0);
  t.mock.timers.tick(1); await waiting;
  assert.equal(signal.aborted, true);
  assert.deepEqual(sent, [{ type: "connector.preflight.result", version: 2,
    requestId: first.requestId, hermesReachable: false }]);
  healthy = true;
  await responder.respond(request());
  assert.equal(sent[1].hermesReachable, true);
});

test("closing or replacing a socket probe cancels its work and suppresses late replies", async () => {
  const sent: ConnectorPreflightResult[] = [];
  const signals: AbortSignal[] = [];
  const finishes: Array<(value: { reachable: boolean }) => void> = [];
  const responder = new ConnectorHealthResponder((signal) => {
    signals.push(signal); return new Promise((resolve) => finishes.push(resolve));
  }, (result) => sent.push(result));
  const first = responder.respond(request());
  const secondRequest = request();
  const second = responder.respond(secondRequest);
  assert.equal(signals[0].aborted, true);
  finishes[0]({ reachable: true }); await first;
  assert.equal(sent.length, 0);
  finishes[1]({ reachable: true }); await second;
  assert.equal(sent[0].requestId, secondRequest.requestId);
  const third = responder.respond(request());
  responder.stop(); await third;
  finishes[2]({ reachable: true }); await settle();
  assert.equal(signals[2].aborted, true);
  assert.equal(sent.length, 1);
  await responder.respond(request());
  assert.equal(signals.length, 3);
});
