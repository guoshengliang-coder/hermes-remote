import assert from "node:assert/strict";
import test from "node:test";
import { tunnelCloseForApp } from "./tunnel-close.js";

const MAX = 12 * 1024 * 1024;

test("an oversized local frame becomes a code the relay can forward", () => {
  const close = tunnelCloseForApp(1006, "", "Max payload size exceeded", MAX);

  // 1006 is exactly the code gateway/src/websocket-utils.ts refuses to forward, so leaving it
  // alone is what turned this into an anonymous 1011 on the phone.
  assert.equal(close.code, 1009);
  assert.match(close.reason, /12582912 bytes/);
});

test("the limit is named, so the reason survives being read six hours later", () => {
  const close = tunnelCloseForApp(1006, "", "Max payload size exceeded", 4096);

  assert.equal(close.reason, "local frame exceeds 4096 bytes");
});

test("the library's wording is matched loosely, not quoted exactly", () => {
  for (const wording of ["Max payload size exceeded", "max payload size exceeded", "WebSocket: Max payload size exceeded"]) {
    assert.equal(tunnelCloseForApp(1006, "", wording, MAX).code, 1009, wording);
  }
});

test("an ordinary close is passed through untouched", () => {
  const close = tunnelCloseForApp(1000, "client closing", undefined, MAX);

  assert.equal(close.code, 1000);
  assert.equal(close.reason, "client closing");
});

test("an unrelated local error does not become a payload complaint", () => {
  // Retrying IS the right answer to this one, and claiming otherwise would be worse than silence.
  const close = tunnelCloseForApp(1006, "", "read ECONNRESET", MAX);

  assert.equal(close.code, 1006);
  assert.equal(close.reason, "");
});
