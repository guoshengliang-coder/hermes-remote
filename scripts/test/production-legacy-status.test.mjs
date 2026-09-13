import assert from "node:assert/strict";
import test from "node:test";
import { verifyPreservedLegacyStatus } from "../../ops/lib/production-legacy-status.mjs";

test("legacy status recognizes the healthy and intentionally retired Connector states", async () => {
  const healthy = await verifyPreservedLegacyStatus({
    fetchImpl: async () => jsonResponse({ overall: "ok", gateway_running: true }),
    url: "https://gateway.example.com/api/status",
    appToken: "app-token",
    sleep: async () => {},
  });
  assert.equal(healthy, "healthy");

  const offline = await verifyPreservedLegacyStatus({
    fetchImpl: async () => jsonResponse({ error: "device_offline" }, {}, 503),
    url: "https://gateway.example.com/api/status",
    appToken: "app-token",
    sleep: async () => {},
  });
  assert.equal(offline, "device_offline");
});

test("legacy status pins the preflight state across a rollout", async () => {
  let attempts = 0;
  const recovered = await verifyPreservedLegacyStatus({
    fetchImpl: async () => {
      attempts += 1;
      return attempts === 1
        ? jsonResponse({ error: "device_offline" }, {}, 503)
        : jsonResponse({ status: "ok" });
    },
    url: "https://gateway.example.com/api/status",
    appToken: "app-token",
    sleep: async () => {},
    expectedState: "healthy",
  });
  assert.equal(recovered, "healthy");
  assert.equal(attempts, 2);

  const drifted = await verifyPreservedLegacyStatus({
    fetchImpl: async () => jsonResponse({ status: "ok" }),
    url: "https://gateway.example.com/api/status",
    appToken: "app-token",
    sleep: async () => {},
    expectedState: "device_offline",
  });
  assert.equal(drifted, null);
});

function jsonResponse(value, headers = {}, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}
