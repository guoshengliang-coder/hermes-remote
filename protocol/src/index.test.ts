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

test("round-trips a tunneled HTTP request", () => {
  const message = {
    type: "tunnel.http.request" as const,
    version: PROTOCOL_VERSION,
    id: "request-1",
    targetDeviceId: "mac-mini",
    method: "GET",
    path: "/api/status",
    headers: { accept: "application/json" },
  };
  assert.deepEqual(parseWireMessage(encodeWireMessage(message)), message);
});
