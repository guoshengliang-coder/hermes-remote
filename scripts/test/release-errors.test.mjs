import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  RELEASE_ERROR_DEFINITIONS,
  createReleaseError,
  serializeReleaseError,
} from "../lib/release-errors.mjs";

test("Gateway release errors keep stable bilingual and retry contracts", async () => {
  const codes = Object.values(RELEASE_ERROR_DEFINITIONS).map((definition) => definition.code);
  assert.deepEqual(codes, ["HR-RELEASE-001", "HR-RELEASE-002", "HR-RELEASE-003"]);
  for (const definition of Object.values(RELEASE_ERROR_DEFINITIONS)) {
    assert.match(definition.summaryZh, /[\u3400-\u9fff]/);
    assert.match(definition.summaryEn, /^[A-Z]/);
    assert.equal(typeof definition.retryable, "boolean");
    assert.equal(definition.recoveryAction, "inspect_details_and_retry");
  }

  const registry = await readFile("docs/ERROR_HANDLING.md", "utf8");
  for (const code of codes) {
    assert.equal(registry.includes("| `" + code + "` |"), true, `${code} missing from registry`);
  }
});

test("Gateway release error serialization redacts credentials and signed query values", () => {
  const serialized = serializeReleaseError(
    "candidate",
    "authorization: Bearer Bearer-secret token=abc password:xyz cookie=session;csrf=csrf-secret ticket=qwerty&signature=deadbeef",
  );
  const parsed = JSON.parse(serialized);
  assert.equal(parsed.code, "HR-RELEASE-002");
  assert.equal(parsed.retryable, true);
  assert.equal(serialized.includes("Bearer-secret"), false);
  assert.equal(serialized.includes("abc"), false);
  assert.equal(serialized.includes("xyz"), false);
  assert.equal(serialized.includes("session"), false);
  assert.equal(serialized.includes("csrf-secret"), false);
  assert.equal(serialized.includes("qwerty"), false);
  assert.equal(serialized.includes("deadbeef"), false);
  assert.match(parsed.technicalCause, /\[REDACTED\]/);

  const fallback = createReleaseError("unknown", "token=nope");
  assert.equal(fallback.code, "HR-RELEASE-003");
  assert.equal(fallback.stage, "gateway_oci_smoke");
});
