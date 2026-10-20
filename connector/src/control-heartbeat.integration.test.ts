import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, realpath, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { WebSocket, WebSocketServer } from "ws";

test("real Connector diagnoses a written ping without pong, retains incoming activity, then reconnects", {
  timeout: 10_000,
}, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "connector-heartbeat-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const server = createServer((_request, response) => response.writeHead(404).end());
  const wss = new WebSocketServer({ server, autoPong: false });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  let connections = 0;
  let reconnected!: () => void;
  const reconnect = new Promise<void>((resolve) => { reconnected = resolve; });
  wss.on("connection", (socket) => {
    if (++connections === 2) reconnected();
    socket.on("error", () => {});
    socket.once("message", () => {
      socket.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "fixture-mac" }));
      const incoming = setInterval(() => {
        if (socket.readyState !== WebSocket.OPEN) return;
        socket.ping("private-ping-payload");
        socket.send(JSON.stringify({ type: "tunnel.http.response.ack", version: 1,
          requestId: "fixture-request", sequence: 0 }));
      }, 30);
      socket.once("close", () => clearInterval(incoming));
    });
  });
  const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
    env: {
      PATH: process.env.PATH, HOME: root, CONNECTOR_MODE: "legacy", HERMES_MODE: "mock",
      SESSION_OBSERVER_ENABLED: "0", CONTROL_HEARTBEAT_MS: "300",
      CONNECTOR_TOKEN: "private-connector-token", HERMES_SESSION_TOKEN: "private-session-token",
      GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`,
      HERMES_BASE_URL: `http://127.0.0.1:${address.port}`,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  child.stdout.on("data", (data) => { output += data.toString(); });
  child.stderr.on("data", (data) => { output += data.toString(); });
  t.after(async () => {
    if (child.exitCode === null) {
      const exited = once(child, "exit");
      child.kill("SIGTERM");
      const force = setTimeout(() => child.kill("SIGKILL"), 2_000);
      await exited;
      clearTimeout(force);
    }
    for (const socket of wss.clients) socket.terminate();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
  await Promise.race([reconnect,
    once(child, "exit").then(() => { throw new Error("Connector exited before reconnect"); })]);
  const events = output.split("\n").filter((line) => line.startsWith("{"))
    .map((line) => JSON.parse(line) as Record<string, unknown>);
  const failure = events.find((event) => event.kind === "relay.heartbeat_timeout");
  assert.ok(failure, "must diagnose the local heartbeat decision, not only generic disconnect");
  assert.equal(failure.mode, "legacy");
  assert.equal(failure.pingWriteState, "written");
  assert.equal(failure.lastPongAgoMs, undefined);
  assert.equal(typeof failure.lastMessageAgoMs, "number");
  assert.equal(typeof failure.lastPingReceivedAgoMs, "number");
  assert.equal(typeof failure.lastPingWrittenAgoMs, "number");
  assert.equal(typeof failure.tickDelayMs, "number");
  assert.equal(typeof failure.bufferedBytes, "number");
  const disconnected = events.find((event) => event.kind === "relay.disconnected");
  assert.equal(disconnected?.controlConnectionId, failure.controlConnectionId);
  assert.doesNotMatch(output, /private-ping-payload|private-connector-token|private-session-token/);
});
