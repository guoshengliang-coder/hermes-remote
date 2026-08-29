import assert from "node:assert/strict";
import test from "node:test";
import { PROTOCOL_VERSION, encodeWireMessage, parseWireMessage } from "./index.js";

test("round-trips a device status message", () => {
  const message = {
    type: "device_status" as const,
    version: PROTOCOL_VERSION,
    deviceId: "mac-mini",
    online: true,
  };
  assert.deepEqual(parseWireMessage(encodeWireMessage(message)), message);
});

test("rejects a mismatched protocol version", () => {
  assert.throws(
    () => parseWireMessage('{"type":"hello","version":99}'),
    /unsupported_version/,
  );
});
