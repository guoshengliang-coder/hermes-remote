import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
} from "./index.js";

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

test("round-trips the account Connector identify, challenge, preflight, and ready handshake", () => {
  const bindingId = "10000000-0000-4000-8000-000000000001";
  const fingerprint = "a".repeat(64);
  const messages = [
    {
      type: "connector.identify" as const,
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId,
      generation: 2,
      publicKeyFingerprint: fingerprint,
    },
    {
      type: "connector.challenge" as const,
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId,
      generation: 2,
      publicKeyFingerprint: fingerprint,
      challenge: Buffer.alloc(32, 1).toString("base64url"),
      connectionNonce: Buffer.alloc(24, 2).toString("base64url"),
      serverTime: "2026-09-02T08:00:00.000Z",
      expiresAt: "2026-09-02T08:00:05.000Z",
    },
    {
      type: "connector.authenticate" as const,
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId,
      generation: 2,
      publicKeyFingerprint: fingerprint,
      connectionNonce: Buffer.alloc(24, 2).toString("base64url"),
      signature: Buffer.alloc(64, 3).toString("base64url"),
    },
    {
      type: "connector.preflight.request" as const,
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      requestId: "20000000-0000-4000-8000-000000000002",
      sentAt: "2026-09-02T08:00:01.000Z",
    },
    {
      type: "connector.preflight.result" as const,
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      requestId: "20000000-0000-4000-8000-000000000002",
      hermesReachable: true,
      hermesVersion: "0.20.6",
    },
    {
      type: "connector.ready" as const,
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId,
      generation: 2,
      deviceId: `hermes-${bindingId}`,
      bindingStatus: "active" as const,
      routingEnabled: true,
    },
  ];
  for (const message of messages) {
    assert.deepEqual(parseWireMessage(encodeWireMessage(message)), message);
  }
});

test("committed account Connector fixtures remain accepted by the strict parser", () => {
  for (const name of [
    "connector-identify.json",
    "connector-challenge.json",
    "connector-authenticate.json",
    "connector-preflight-request.json",
    "connector-preflight-result.json",
    "connector-ready.json",
  ]) {
    const raw = readFileSync(resolve("fixtures", "account-mode", name), "utf8");
    assert.doesNotThrow(() => parseWireMessage(raw), name);
  }
});

test("rejects malformed or version-confused account Connector handshake fields", () => {
  const identify = {
    type: "connector.identify",
    version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
    bindingId: "10000000-0000-4000-8000-000000000001",
    generation: 1,
    publicKeyFingerprint: "a".repeat(64),
  };
  assert.throws(
    () => parseWireMessage(JSON.stringify({ ...identify, version: PROTOCOL_VERSION })),
    /unsupported_version/,
  );
  assert.throws(
    () => parseWireMessage(JSON.stringify({ ...identify, publicKeyFingerprint: "A".repeat(64) })),
    /invalid_public_key_fingerprint/,
  );
  assert.throws(
    () => parseWireMessage(JSON.stringify({ ...identify, token: "must-not-exist" })),
    /invalid_connector_identify_fields/,
  );
  assert.throws(
    () => parseWireMessage(JSON.stringify({
      type: "connector.authenticate",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId: identify.bindingId,
      generation: 1,
      publicKeyFingerprint: identify.publicKeyFingerprint,
      connectionNonce: Buffer.alloc(24).toString("base64url"),
      signature: "not-a-signature",
    })),
    /invalid_connector_signature/,
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

test("round-trips a sanitized session lifecycle event and acknowledgement", () => {
  const event = {
    type: "session.lifecycle" as const,
    version: PROTOCOL_VERSION,
    eventId: "life-1",
    deviceId: "mac-mini",
    profile: "default",
    runtimeSessionId: "runtime-1",
    storedSessionId: "stored-1",
    event: "run.waiting" as const,
    state: "waiting" as const,
    occurredAt: "2026-08-31T08:30:00.000Z",
    title: "Research",
  };
  const ack = {
    type: "session.lifecycle.ack" as const,
    version: PROTOCOL_VERSION,
    eventId: event.eventId,
  };
  assert.deepEqual(parseWireMessage(encodeWireMessage(event)), event);
  assert.deepEqual(parseWireMessage(encodeWireMessage(ack)), ack);
});

test("rejects unsafe or malformed lifecycle fields", () => {
  const valid = {
    type: "session.lifecycle",
    version: PROTOCOL_VERSION,
    eventId: "life-1",
    deviceId: "mac-mini",
    runtimeSessionId: "runtime-1",
    storedSessionId: "stored-1",
    event: "run.completed",
    state: "idle",
    occurredAt: "2026-08-31T08:30:00.000Z",
  };
  assert.throws(
    () => parseWireMessage(JSON.stringify({ ...valid, event: "message.delta" })),
    /invalid_lifecycle_event/,
  );
  assert.throws(
    () => parseWireMessage(JSON.stringify({ ...valid, occurredAt: "not-a-date" })),
    /invalid_occurred_at/,
  );
  assert.throws(
    () => parseWireMessage(JSON.stringify({ ...valid, prompt: "secret" })),
    /invalid_lifecycle_fields/,
  );
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
