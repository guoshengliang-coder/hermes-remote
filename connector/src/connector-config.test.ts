import assert from "node:assert/strict";
import test from "node:test";
import { resolveHermesMode } from "./connector-config.js";

test("an account Connector without HERMES_MODE runs live (HG-101)", () => {
  assert.equal(resolveHermesMode({}, "account"), "live");
});

test("a legacy Connector without HERMES_MODE keeps the mock default", () => {
  assert.equal(resolveHermesMode({}, "legacy"), "mock");
});

test("an explicit HERMES_MODE wins in either mode", () => {
  assert.equal(resolveHermesMode({ HERMES_MODE: "mock" }, "account"), "mock");
  assert.equal(resolveHermesMode({ HERMES_MODE: "live" }, "legacy"), "live");
  assert.equal(resolveHermesMode({ HERMES_MODE: "hermes" }, "account"), "hermes");
});

test("a blank HERMES_MODE counts as unset", () => {
  assert.equal(resolveHermesMode({ HERMES_MODE: "" }, "account"), "live");
  assert.equal(resolveHermesMode({ HERMES_MODE: "  " }, "legacy"), "mock");
});
