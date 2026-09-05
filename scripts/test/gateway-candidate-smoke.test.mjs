import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import test from "node:test";
import {
  GatewayCandidateSmokeError,
  waitForGatewayForwarding,
} from "../lib/gateway-candidate-smoke.mjs";
import {
  createStagingSmokeCallbacks,
  parseGatewaySmokeDiagnostic,
} from "../../ops/lib/deploy-smoke.mjs";
import { serializeReleaseError } from "../lib/release-errors.mjs";

test("candidate forwarding readiness retries transient upstream failures", async () => {
  const statuses = [503, 502, 200];
  const sleeps = [];
  const body = await waitForGatewayForwarding({
    baseUrl: "http://127.0.0.1:18787",
    appToken: "a".repeat(64),
    attempts: 5,
    fetchImpl: async () => {
      const status = statuses.shift();
      return {
        ok: status === 200,
        status,
        json: async () => ({ status: "ok", version: "mock-hermes" }),
      };
    },
    sleep: async (milliseconds) => sleeps.push(milliseconds),
  });

  assert.deepEqual(body, { status: "ok", version: "mock-hermes" });
  assert.deepEqual(sleeps, [250, 250]);
});

test("candidate forwarding readiness fails closed on auth or contract drift", async () => {
  let authCalls = 0;
  await assert.rejects(
    () => waitForGatewayForwarding({
      baseUrl: "http://127.0.0.1:18787",
      appToken: "b".repeat(64),
      fetchImpl: async () => {
        authCalls += 1;
        return { ok: false, status: 401 };
      },
      sleep: async () => {},
      attempts: 4,
    }),
    (error) => {
      return error instanceof GatewayCandidateSmokeError && error.message === "smoke_check=rest_forward_auth";
    },
  );
  assert.equal(authCalls, 1);

  const secret = "must-not-appear-in-diagnostics";
  await assert.rejects(
    () => waitForGatewayForwarding({
      baseUrl: "http://127.0.0.1:18787",
      appToken: "c".repeat(64),
      fetchImpl: async () => ({
        ok: true,
        status: 200,
        json: async () => ({ status: "wrong", detail: secret }),
      }),
      sleep: async () => {},
    }),
    (error) => error.message === "smoke_check=rest_forward_contract" && !error.message.includes(secret),
  );
});

test("public forwarding readiness accepts a nonempty live Hermes status contract", async () => {
  const liveStatus = {
    version: "0.21.0",
    gateway_running: true,
    overall: "ok",
  };
  const result = await waitForGatewayForwarding({
    baseUrl: "https://gateway.example.invalid",
    appToken: "e".repeat(64),
    statusMode: "live",
    fetchImpl: async () => ({ ok: true, status: 200, json: async () => liveStatus }),
    sleep: async () => {},
  });
  assert.deepEqual(result, liveStatus);

  await assert.rejects(
    () => waitForGatewayForwarding({
      baseUrl: "https://gateway.example.invalid",
      appToken: "f".repeat(64),
      statusMode: "unsupported",
      fetchImpl: async () => ({ ok: true, status: 200, json: async () => liveStatus }),
    }),
    (error) => error.message === "smoke_check=configuration",
  );
});

test("candidate forwarding readiness has a bounded stable timeout", async () => {
  let calls = 0;
  await assert.rejects(
    () => waitForGatewayForwarding({
      baseUrl: "http://127.0.0.1:18787",
      appToken: "d".repeat(64),
      attempts: 3,
      fetchImpl: async () => {
        calls += 1;
        throw new Error("token=must-not-escape");
      },
      sleep: async () => {},
    }),
    (error) => error.message === "smoke_check=rest_forward_ready_timeout"
      && !error.message.includes("must-not-escape"),
  );
  assert.equal(calls, 3);
});

test("deployment smoke surfaces only allowlisted structured verifier diagnostics", async (t) => {
  const root = await mkdtemp(path.join(tmpdir(), "gateway-candidate-smoke-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const inputs = path.join(root, "inputs");
  await mkdir(inputs);
  const secrets = {
    appTokenSource: path.join(inputs, "app-token"),
    connectorTokenSource: path.join(inputs, "connector-token"),
    internalStatusTokenSource: path.join(inputs, "internal-status-token"),
  };
  await Promise.all(Object.entries(secrets).map(([name, filePath], index) => (
    writeFile(filePath, String.fromCharCode(97 + index).repeat(64), { mode: 0o600 })
  )));
  const connectorEntry = path.join(inputs, "connector.mjs");
  await writeFile(connectorEntry, "export {};\n", { mode: 0o600 });
  const diagnostic = serializeReleaseError("smoke", "smoke_check=rest_forward_ready_timeout");
  let stdio;
  let verifierEnvironment;
  const smoke = await createStagingSmokeCallbacks({
    secrets,
    gateway: { defaultDeviceId: "test-device" },
    legacySource: { gatewayPort: 8444 },
    slots: { blue: { gatewayPort: 18787 }, green: { gatewayPort: 18788 } },
  }, {
    env: {
      HERMES_SMOKE_CONNECTOR_ENTRY: connectorEntry,
      HERMES_BASE_URL: "http://127.0.0.1:19001",
      HERMES_BASIC_AUTH_USERNAME: "demo",
      HERMES_BASIC_AUTH_PASSWORD: "secret",
      FILES_ROOT: root,
      UPLOAD_ROOT: inputs,
    },
    fetchImpl: async () => ({ ok: true, json: async () => ({ connectors: 1 }) }),
    spawnImpl: (_command, _arguments, options) => {
      stdio = options.stdio;
      verifierEnvironment = options.env;
      const child = new EventEmitter();
      child.stderr = new PassThrough();
      queueMicrotask(() => {
        child.stderr.end(`${diagnostic}\ntoken=must-not-escape\n`);
        child.emit("exit", 1, null);
        child.emit("close", 1, null);
      });
      return child;
    },
  });

  await assert.rejects(
    () => smoke.publicSmoke({
      gatewayUrl: "https://staging.example.invalid",
      candidateSlot: null,
      publicRoute: true,
      expectedDeviceId: "test-device",
      expectedSourceCommit: "a".repeat(40),
      expectedServerVersion: "0.4.0",
    }),
    (error) => error.technicalCause
      === "gateway_smoke_failed=1;diagnostic=HR-RELEASE-003:smoke_check=rest_forward_ready_timeout",
  );
  assert.deepEqual(stdio, ["ignore", "ignore", "pipe"]);
  assert.equal(verifierEnvironment.HERMES_STATUS_MODE, "live");
});

test("unstructured verifier output is never copied into deployment diagnostics", () => {
  const unsafe = "Error: token=secret-value at /private/operator/path.mjs";
  assert.equal(parseGatewaySmokeDiagnostic(unsafe), "unavailable");
  assert.equal(parseGatewaySmokeDiagnostic(JSON.stringify({
    code: "HR-RELEASE-003",
    stage: "gateway_oci_smoke",
    technicalCause: "smoke_check=plausible_but_not_allowlisted",
  })), "unavailable");
  const structured = serializeReleaseError("smoke", "smoke_check=websocket_forward");
  assert.equal(
    parseGatewaySmokeDiagnostic(structured),
    "HR-RELEASE-003:smoke_check=websocket_forward",
  );
});
