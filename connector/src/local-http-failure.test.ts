import assert from "node:assert/strict";
import { test } from "node:test";
import { hermesUnavailableBody, localHttpFailureFields, localHttpRoute } from "./local-http-failure.js";

test("local transport failure has a stable retryable envelope and request correlation", () => {
  assert.deepEqual(JSON.parse(hermesUnavailableBody("request-123")), { error: {
    code: "HR-CONN-006", message: "Hermes service unavailable", retryable: true,
    recoveryAction: "retry", correlationId: "request-123",
  } });
});

test("diagnostics retain only known transport codes, never host-supplied messages", () => {
  const refused = new TypeError("fetch failed for http://local/private?token=secret", {
    cause: Object.assign(new Error("secret"), { code: "ECONNREFUSED" }),
  });
  assert.deepEqual(localHttpFailureFields(refused), { errorType: "TypeError", causeCode: "ECONNREFUSED" });
  const unknown = new Error("password=secret", { cause: { code: "secret-value" } });
  assert.deepEqual(localHttpFailureFields(unknown), { errorType: "Other", causeCode: "unknown" });
  assert.equal(localHttpRoute("/api/sessions/s1/messages?limit=100"), "history");
  assert.equal(localHttpRoute("/api/status"), "other");
});
