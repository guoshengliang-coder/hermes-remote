import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, generateKeyPairSync, randomUUID, verify } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readFile, realpath, rename, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { WebSocketServer, type WebSocket } from "ws";
import { parseWireMessage, type WireMessage, type ConnectorChallengeMessage } from "@hermes-remote/protocol";
import { canonicalConnectorChallenge } from "./account-connector.js";

test("the real Connector reports degraded/recovered health on one authenticated socket and cancels local work on close", { timeout: 10_000 }, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "connector-health-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const keys = generateKeyPairSync("ed25519");
  const fingerprint = createHash("sha256").update(keys.publicKey.export({ format: "der", type: "spki" }).subarray(-32)).digest("hex");
  const bindingId = randomUUID();
  const credentialPath = join(root, "credential.json");
  await writeFile(credentialPath, JSON.stringify({ schemaVersion: 1, bindingId, generation: 1,
    publicKeyFingerprint: fingerprint,
    privateKey: keys.privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32).toString("base64url"),
  }), { mode: 0o600 });
  let credentialsHealthy = false;
  let healthCalls = 0;
  let stalled = false;
  let stalledStarted!: () => void;
  let stalledClosed!: () => void;
  const started = new Promise<void>((resolve) => { stalledStarted = resolve; });
  const cancelled = new Promise<void>((resolve) => { stalledClosed = resolve; });
  const server = createServer((request, response) => {
    request.resume();
    if (request.url === "/api/health") {
      healthCalls++;
      response.writeHead(200).end(JSON.stringify({ ok: true, version: "0.21.3" }));
    } else if (request.url === "/api/profiles/active") {
      assert.equal(request.headers["x-hermes-session-token"], "fixture-local-token");
      if (stalled) {
        response.once("close", stalledClosed);
        response.writeHead(200, { "content-type": "application/json" });
        response.write("{"); // Headers arrived, but the body must still obey probe cancellation.
        stalledStarted();
        return;
      }
      response.writeHead(credentialsHealthy ? 200 : 401).end(JSON.stringify({ active: "default" }));
    } else if (request.url === "/api/fixture") {
      response.writeHead(200, { "content-type": "application/json" }).end('{"ok":true}');
    } else response.writeHead(404).end("{}");
  });
  const wss = new WebSocketServer({ server });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); assert.ok(address && typeof address !== "string");
  const origin = `http://127.0.0.1:${address.port}`;
  const gatewayUrl = `ws://127.0.0.1:${address.port}/v2/connect`;
  const received: WireMessage[] = [];
  let control!: WebSocket;
  let connections = 0;
  const waits = new Set<() => void>();
  wss.on("connection", (socket) => {
    control = socket; connections += 1;
    const challenge: ConnectorChallengeMessage = {
      type: "connector.challenge", version: 2, bindingId, generation: 1, publicKeyFingerprint: fingerprint,
      challenge: Buffer.alloc(32, 1).toString("base64url"), connectionNonce: Buffer.alloc(24, 2).toString("base64url"),
      serverTime: new Date().toISOString(), expiresAt: new Date(Date.now() + 5_000).toISOString(),
    };
    socket.on("message", (data) => {
      const message = parseWireMessage(data.toString()); received.push(message);
      if (message.type === "connector.identify") socket.send(JSON.stringify(challenge));
      if (message.type === "connector.authenticate") {
        assert.equal(verify(null, canonicalConnectorChallenge(origin, challenge), keys.publicKey,
          Buffer.from(message.signature, "base64url")), true);
        socket.send(JSON.stringify(probe()));
      }
      if (message.type === "tunnel.http.response.chunk") socket.send(JSON.stringify({
        type: "tunnel.http.response.ack", version: 1, requestId: message.requestId, sequence: message.sequence,
      }));
      for (const wake of waits) wake();
    });
  });
  const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
    env: { PATH: process.env.PATH, HOME: root, CONNECTOR_MODE: "account", HERMES_MODE: "real",
      SESSION_OBSERVER_ENABLED: "0", GATEWAY_URL: gatewayUrl, ACCOUNT_CONNECTOR_CREDENTIAL_FILE: credentialPath,
      HERMES_BASE_URL: origin, HERMES_SESSION_TOKEN: "fixture-local-token", HERMES_GO_MAINTENANCE_DIRECTORY: root },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  child.stdout.on("data", (data) => { output += data.toString(); });
  child.stderr.on("data", (data) => { output += data.toString(); });
  t.after(async () => {
    if (child.exitCode === null) {
      const exited = once(child, "exit"); child.kill("SIGTERM");
      const force = setTimeout(() => child.kill("SIGKILL"), 2_000);
      await exited; clearTimeout(force);
    }
    for (const socket of wss.clients) socket.terminate();
    server.closeAllConnections();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  const waitFor = (predicate: () => boolean) => new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => { waits.delete(wake); reject(new Error("fixture message timeout")); }, 3_000);
    const wake = () => { if (predicate()) { clearTimeout(timer); waits.delete(wake); resolve(); } };
    waits.add(wake); wake();
  });
  await waitFor(() => received.some((message) => message.type === "connector.preflight.result"));
  const first = received.find((message) => message.type === "connector.preflight.result");
  assert.ok(first?.type === "connector.preflight.result"); assert.equal(first.hermesReachable, false);
  control.send(JSON.stringify({ type: "connector.ready", version: 2, bindingId, generation: 1,
    deviceId: "test-mac", bindingStatus: "active", routingEnabled: true }));
  credentialsHealthy = true;
  const retry = probe(); control.send(JSON.stringify(retry));
  await waitFor(() => received.some((message) => message.type === "connector.preflight.result" && message.requestId === retry.requestId));
  const recovered = received.find((message) => message.type === "connector.preflight.result" && message.requestId === retry.requestId);
  assert.ok(recovered?.type === "connector.preflight.result"); assert.equal(recovered.hermesReachable, true);
  assert.equal(recovered.hermesVersion, "0.21.3"); assert.equal(connections, 1);
  const id = randomUUID();
  control.send(JSON.stringify({ type: "tunnel.http.request", version: 1, id, targetDeviceId: "test-mac",
    method: "GET", path: "/api/fixture", headers: {} }));
  await waitFor(() => received.some((message) => message.type === "tunnel.http.response.end" && message.requestId === id));
  const response = received.find((message) => message.type === "tunnel.http.response.start" && message.requestId === id);
  assert.ok(response?.type === "tunnel.http.response.start"); assert.equal(response.status, 200);
  // Recovery also queues a separate contract refresh. Wait for that owner's work before stalling
  // the control-owned probe, otherwise the fixture can observe the wrong upstream request.
  const contractId = randomUUID();
  control.send(JSON.stringify({ type: "tunnel.http.request", version: 1, id: contractId,
    targetDeviceId: "test-mac", method: "GET", path: "/api/hermes-remote/contract", headers: {} }));
  await waitFor(() => received.some((message) => message.type === "tunnel.http.response" && message.requestId === contractId));
  const maintenance = { schema: 1, runID: randomUUID(), sourceIdentity: "a".repeat(64) };
  await writeRequest(root, JSON.stringify({ ...maintenance, mode: "pause" }), { mode: 0o600 });
  const callsBeforePause = healthCalls;
  const paused = probe(); control.send(JSON.stringify(paused));
  await waitFor(() => received.some((message) => message.type === "connector.preflight.result" && message.requestId === paused.requestId));
  const blocked = received.find((message) => message.type === "connector.preflight.result" && message.requestId === paused.requestId);
  assert.ok(blocked?.type === "connector.preflight.result"); assert.equal(blocked.hermesReachable, false);
  assert.equal(healthCalls, callsBeforePause, "maintenance must refuse new health calls too");
  await writeRequest(root, JSON.stringify({ ...maintenance, mode: "release" }), { mode: 0o600 });
  stalled = true;
  const last = probe(); control.send(JSON.stringify(last));
  await started;
  await writeRequest(root, JSON.stringify({ ...maintenance, mode: "pause" }), { mode: 0o600 });
  // Trigger a fresh mailbox observation while the admitted health body is still stalled.
  const refused = randomUUID();
  control.send(JSON.stringify({ type: "tunnel.http.request", version: 1, id: refused, targetDeviceId: "test-mac",
    method: "GET", path: "/api/fixture", headers: {} }));
  await waitFor(() => received.some((message) => message.type === "tunnel.http.response" && message.requestId === refused));
  const ack = JSON.parse(await readFile(join(root, "ack.json"), "utf8"));
  assert.equal(ack.paused, true);
  assert.ok(ack.activeOperations > 0, "an admitted health response remains busy until its body is closed");
  control.close(1001, "fixture-control-closed");
  await Promise.race([cancelled, new Promise((_, reject) => {
    const timer = setTimeout(() => reject(new Error("local health request was not cancelled")), 1_000);
    timer.unref(); cancelled.finally(() => clearTimeout(timer));
  })]);
  assert.equal(received.some((message) => message.type === "connector.preflight.result" && message.requestId === last.requestId), false);
  assert.equal(output.includes("fixture-local-token"), false);
  assert.equal(output.includes("fixture-control-closed"), false);
});

function probe() {
  return { type: "connector.preflight.request", version: 2, requestId: randomUUID(), sentAt: new Date().toISOString() };
}

// Match Desktop's atomic mailbox publication: concurrent readers must not see fixture half-writes.
async function writeRequest(directory: string, body: string, options: { mode: number }): Promise<void> {
  const temporary = join(directory, `.${randomUUID()}.request.tmp`);
  await writeFile(temporary, body, options);
  await rename(temporary, join(directory, "request.json"));
}
