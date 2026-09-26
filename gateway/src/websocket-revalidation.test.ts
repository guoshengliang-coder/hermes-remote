import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { test } from "node:test";
import { WebSocket } from "ws";
import { createGatewayLogger } from "./gateway-log.js";
import { accountErrors } from "./account/model.js";
import {
  classifyRevalidationFailure,
  RevalidationFailurePolicy,
  WebSocketTunnelBroker,
} from "./websocket-tunnel-broker.js";

// Until 2026-09-26 every revalidation rejection — including a plain database hiccup — closed the
// app socket as `4403 "account authorization changed"`, which the phone could only answer with a
// full re-authenticate/reconnect cycle (HG-140). These tests pin the split that replaced it:
// definitive authorization/binding end-states still close immediately; transient failures are
// tolerated up to a budget and audited in the log.

test("a transient revalidation failure keeps the tunnel open and is audited, not treated as revocation", async () => {
  const lines: Record<string, unknown>[] = [];
  const log = createGatewayLogger("info", (line) => lines.push(JSON.parse(line) as Record<string, unknown>));
  const connector = {
    socket: fakeSocket().socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker<typeof connector>(
    10,
    1024 * 1024,
    () => {},
    () => connector,
    log,
  );
  const app = fakeSocket();
  broker.open(app.socket, connector, async () => {
    throw new Error("connection terminated");
  }, {
    accountId: "grantee-a",
    bindingId: "binding-1",
    installationId: "installation-a",
    sessionId: "session-a",
  });
  await settle();

  assert.deepEqual(app.closes, []);
  const failure = lines.find((line) => line.kind === "app.tunnel.revalidation_failed");
  assert.ok(failure, "expected an app.tunnel.revalidation_failed audit line");
  assert.equal(failure.failureKind, "transient");
  assert.equal(failure.error, "connection terminated");
  assert.equal(failure.accountErrorCode, undefined);
});

test("a definitive authorization failure during the immediate recheck still closes with 4403", async () => {
  const lines: Record<string, unknown>[] = [];
  const log = createGatewayLogger("info", (line) => lines.push(JSON.parse(line) as Record<string, unknown>));
  const connector = {
    socket: fakeSocket().socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker<typeof connector>(
    10,
    1024 * 1024,
    () => {},
    () => connector,
    log,
  );
  const app = fakeSocket();
  broker.open(app.socket, connector, async () => {
    throw accountErrors.sessionExpired();
  }, {
    accountId: "grantee-a",
    bindingId: "binding-1",
    installationId: "installation-a",
    sessionId: "session-a",
  });
  await settle();

  assert.deepEqual(app.closes, [{ code: 4403, reason: "account authorization changed" }]);
  const failure = lines.find((line) => line.kind === "app.tunnel.revalidation_failed");
  assert.ok(failure);
  assert.equal(failure.failureKind, "authorization");
  assert.equal(failure.accountErrorCode, "HR-AUTH-003");
});

test("a binding end-state closes as a binding change, not an authorization change", async () => {
  const connector = {
    socket: fakeSocket().socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker<typeof connector>(10, 1024 * 1024, () => {}, () => connector);
  const app = fakeSocket();
  broker.open(app.socket, connector, async () => {
    throw accountErrors.deviceNotFound();
  }, {
    accountId: "grantee-a",
    bindingId: "binding-1",
    installationId: "installation-a",
    sessionId: "session-a",
  });
  await settle();

  assert.deepEqual(app.closes, [{ code: 4403, reason: "account binding changed" }]);
});

test("transient failures beyond the budget close as 1013 service unavailable, and success resets the budget", async () => {
  const lines: Record<string, unknown>[] = [];
  const log = createGatewayLogger("info", (line) => lines.push(JSON.parse(line) as Record<string, unknown>));
  const connector = {
    socket: fakeSocket().socket,
    deviceId: "hermes-office",
    routingKey: "account:binding-1",
  };
  const broker = new WebSocketTunnelBroker<typeof connector>(
    10,
    1024 * 1024,
    () => {},
    () => connector,
    log,
    1,
  );
  const app = fakeSocket();
  broker.open(app.socket, connector, async () => {
    throw accountErrors.connectorOffline();
  }, {
    accountId: "grantee-a",
    bindingId: "binding-1",
    installationId: "installation-a",
    sessionId: "session-a",
  });
  await settle();

  assert.deepEqual(app.closes, [{ code: 1013, reason: "account service unavailable" }]);
  const exhausted = lines.find((line) => line.kind === "app.tunnel.revalidation_exhausted");
  assert.ok(exhausted);
  assert.equal(exhausted.failureKind, "transient");
  assert.equal(exhausted.accountErrorCode, "HR-CONN-005");
});

test("RevalidationFailurePolicy tolerates transient failures up to its budget and resets on success", () => {
  const policy = new RevalidationFailurePolicy(3);
  assert.deepEqual(policy.recordFailure(new Error("ECONNREFUSED")), { action: "keep-open" });
  assert.deepEqual(policy.recordFailure(new Error("ECONNREFUSED")), { action: "keep-open" });
  policy.recordSuccess();
  assert.equal(policy.failures, 0);
  assert.deepEqual(policy.recordFailure(new Error("ECONNREFUSED")), { action: "keep-open" });
  assert.deepEqual(policy.recordFailure(new Error("ECONNREFUSED")), { action: "keep-open" });
  assert.deepEqual(policy.recordFailure(new Error("ECONNREFUSED")), {
    action: "close",
    code: 1013,
    reason: "account service unavailable",
  });
});

test("classifyRevalidationFailure separates authorization, binding, configuration and transient errors", () => {
  assert.equal(classifyRevalidationFailure(new Error("postgres went away")), "transient");
  assert.equal(classifyRevalidationFailure(accountErrors.connectorOffline()), "transient");
  assert.equal(classifyRevalidationFailure(accountErrors.unavailable()), "transient");
  assert.equal(classifyRevalidationFailure(accountErrors.rateLimited()), "transient");
  assert.equal(classifyRevalidationFailure(accountErrors.bindingMissing()), "transient");
  assert.equal(classifyRevalidationFailure(accountErrors.sessionExpired()), "authorization");
  assert.equal(classifyRevalidationFailure(accountErrors.sessionRevoked()), "authorization");
  assert.equal(classifyRevalidationFailure(accountErrors.refreshReused()), "authorization");
  assert.equal(classifyRevalidationFailure(accountErrors.accountDisabled()), "authorization");
  assert.equal(classifyRevalidationFailure(accountErrors.accountDeletionPending()), "authorization");
  assert.equal(classifyRevalidationFailure(accountErrors.deviceNotFound()), "binding");
  assert.equal(classifyRevalidationFailure(accountErrors.bindingRevoked()), "binding");
  assert.equal(classifyRevalidationFailure(accountErrors.featureDisabled()), "configuration");
  assert.equal(classifyRevalidationFailure(accountErrors.bindingFeatureDisabled()), "configuration");
});

function settle(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

function fakeSocket(): {
  socket: WebSocket;
  closes: Array<{ code: number; reason: string }>;
} {
  const events = new EventEmitter();
  const closes: Array<{ code: number; reason: string }> = [];
  const socket = Object.assign(events, {
    readyState: WebSocket.OPEN,
    bufferedAmount: 0,
    send: () => {},
    close: (code: number, reason: string) => {
      closes.push({ code, reason });
      events.emit("close", code, Buffer.from(reason));
    },
  }) as unknown as WebSocket;
  return { socket, closes };
}
