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

test("round-trips an acknowledged streaming HTTP response", () => {
  const messages = [
    {
      type: "tunnel.http.response.start" as const,
      version: PROTOCOL_VERSION,
      requestId: "request-1",
      status: 200,
      headers: { "content-type": "application/octet-stream" },
    },
    {
      type: "tunnel.http.response.chunk" as const,
      version: PROTOCOL_VERSION,
      requestId: "request-1",
      sequence: 0,
      dataBase64: "YWJj",
    },
    {
      type: "tunnel.http.response.ack" as const,
      version: PROTOCOL_VERSION,
      requestId: "request-1",
      sequence: 0,
    },
    {
      type: "tunnel.http.response.end" as const,
      version: PROTOCOL_VERSION,
      requestId: "request-1",
    },
  ];
  for (const message of messages) {
    assert.deepEqual(parseWireMessage(encodeWireMessage(message)), message);
  }
});

test("rejects an oversized streaming response chunk", () => {
  assert.throws(
    () => parseWireMessage(JSON.stringify({
      type: "tunnel.http.response.chunk",
      version: PROTOCOL_VERSION,
      requestId: "request-1",
      sequence: 0,
      dataBase64: "A".repeat(512 * 1024 + 1),
    })),
    /invalid_chunk/,
  );
});

test("rejects malformed authentication fields before the gateway uses them", () => {
  assert.throws(
    () => parseWireMessage(
      JSON.stringify({
        type: "hello",
        version: PROTOCOL_VERSION,
        role: "app",
        deviceId: "phone",
        token: null,
      }),
    ),
    /invalid_token/,
  );
  assert.throws(
    () => parseWireMessage(
      JSON.stringify({
        type: "hello",
        version: PROTOCOL_VERSION,
        role: "administrator",
        deviceId: "phone",
        token: "secret-token",
      }),
    ),
    /invalid_role/,
  );
});

test("rejects unknown message types and malformed nested payloads", () => {
  assert.throws(
    () => parseWireMessage(JSON.stringify({ type: "admin.shutdown", version: PROTOCOL_VERSION })),
    /unsupported_message_type/,
  );
  assert.throws(
    () => parseWireMessage(
      JSON.stringify({
        type: "command",
        version: PROTOCOL_VERSION,
        id: "request-1",
        targetDeviceId: "mac-mini",
        payload: { kind: "chat", input: 42 },
      }),
    ),
    /invalid_command_input/,
  );
});

test("only permits tunneled Hermes API paths", () => {
  assert.throws(
    () => parseWireMessage(
      JSON.stringify({
        type: "tunnel.http.request",
        version: PROTOCOL_VERSION,
        id: "request-1",
        targetDeviceId: "mac-mini",
        method: "GET",
        path: "/auth/password-login",
        headers: {},
      }),
    ),
    /unsupported_api_path/,
  );
});
