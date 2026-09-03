import assert from "node:assert/strict";
import test from "node:test";
import { AccountConnectorAdmission } from "./account-connector-admission.js";

test("Account Connector admission bounds total and per-IP unauthenticated sessions", () => {
  const admission = new AccountConnectorAdmission(2, 1);
  const first = admission.acquire("198.51.100.1");
  assert.equal(admission.atCapacity("198.51.100.1"), true);
  assert.equal(admission.atCapacity("198.51.100.2"), false);

  const second = admission.acquire("198.51.100.2");
  assert.equal(admission.atCapacity("198.51.100.3"), true);
  first.release();
  first.release();
  assert.equal(admission.atCapacity("198.51.100.1"), false);
  assert.equal(admission.atCapacity("198.51.100.2"), true);
  second.release();
  assert.equal(admission.atCapacity("198.51.100.2"), false);
});
