import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, realpath, rename, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { WebSocketServer } from "ws";

test("real Connector refuses new HTTP, upload, WebSocket and chat admission during durable maintenance", { timeout: 10_000 }, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "hg199-maintenance-")));
  const runID = randomUUID();
  await writeRequest(root, JSON.stringify({ schema: 1, mode: "pause", runID,
    sourceIdentity: "a".repeat(64) }), { mode: 0o600 });
  let localCalls = 0;
  const server = createServer((_request, response) => { localCalls++; response.writeHead(200).end("{}"); });
  const wss = new WebSocketServer({ server });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); assert.ok(address && typeof address !== "string");
  const answers: Record<string, unknown>[] = [];
  let completed!: () => void;
  const result = new Promise<void>((resolve) => { completed = resolve; });
  wss.on("connection", (socket) => {
    socket.on("message", (bytes) => {
      const message = JSON.parse(bytes.toString());
      if (message.type === "hello") {
        socket.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "test-mac" }));
        const base = { version: 1, targetDeviceId: "test-mac" };
        for (const request of [
          { ...base, type: "tunnel.http.request", id: "http", method: "POST", path: "/api/test", headers: {} },
          { ...base, type: "tunnel.http.request.start", id: "upload", method: "POST", path: "/api/files/upload", headers: {} },
          { ...base, type: "tunnel.ws.open", id: "ws", path: "/api/ws" },
          { ...base, type: "command", id: "chat", payload: { kind: "chat", input: "synthetic" } },
        ]) socket.send(JSON.stringify(request));
      } else if (["tunnel.http.response", "tunnel.ws.close", "event"].includes(message.type)) {
        answers.push(message);
        if (answers.length >= 4) completed();
      }
    });
  });
  const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
    env: { PATH: process.env.PATH, HOME: root, CONNECTOR_MODE: "legacy", HERMES_MODE: "mock",
      DEVICE_ID: "test-mac", SESSION_OBSERVER_ENABLED: "0", GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`,
      CONNECTOR_TOKEN: "fixture-token", HERMES_BASE_URL: `http://127.0.0.1:${address.port}`,
      HERMES_GO_MAINTENANCE_DIRECTORY: root }, stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  child.stdout.on("data", (data) => { output += data.toString(); });
  child.stderr.on("data", (data) => { output += data.toString(); });
  t.after(async () => {
    if (child.exitCode === null) { const done = once(child, "exit"); child.kill("SIGTERM"); await done; }
    for (const socket of wss.clients) socket.terminate();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  });
  await Promise.race([result, once(child, "exit").then(() => { throw new Error(`fixture exited: ${output}`); })]);
  const http = answers.filter((a) => a.type === "tunnel.http.response");
  assert.equal(http.length, 2);
  for (const response of http) {
    assert.equal(response.status, 503);
    const body = JSON.parse(Buffer.from(response.bodyBase64 as string, "base64").toString());
    assert.equal(body.error.code, "HR-HERMESUPDATE-005"); assert.equal(body.error.retryable, false);
  }
  assert.equal(answers.find((a) => a.type === "tunnel.ws.close")?.reason, "HR-HERMESUPDATE-005");
  assert.equal((answers.find((a) => a.type === "event")?.data as { code: string })?.code, "HR-HERMESUPDATE-005");
  assert.equal(localCalls, 0, "even startup status/contract probes must be paused");
  assert.equal(output.includes("fixture-token"), false);
});

test("real streamed HTTP drains after pause without aborting or accepting a second operation", { timeout: 10_000 }, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "hg199-drain-")));
  let finishResponse!: () => void;
  let started!: () => void; const firstStarted = new Promise<void>((r) => { started = r; });
  const server = createServer((request, response) => {
    if (request.url === "/api/first") {
      response.writeHead(200); response.write("synthetic-first-part");
      finishResponse = () => response.end("synthetic-last-part"); started();
    } else response.writeHead(200).end("{}");
  });
  const wss = new WebSocketServer({ server });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); assert.ok(address && typeof address !== "string");
  let gateway!: (typeof wss.clients extends Set<infer T> ? T : never);
  let refused!: () => void; const secondRefused = new Promise<void>((r) => { refused = r; });
  let ended!: () => void; const firstEnded = new Promise<void>((r) => { ended = r; });
  wss.on("connection", (socket) => {
    gateway = socket;
    socket.on("message", (bytes) => {
      const message = JSON.parse(bytes.toString());
      if (message.type === "hello") {
        socket.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "test-mac" }));
        socket.send(JSON.stringify({ type: "tunnel.http.request", version: 1, targetDeviceId: "test-mac",
          id: "first", method: "POST", path: "/api/first", headers: {} }));
      } else if (message.type === "tunnel.http.response.chunk") {
        socket.send(JSON.stringify({ type: "tunnel.http.response.ack", version: 1,
          requestId: message.requestId, sequence: message.sequence }));
      } else if (message.type === "tunnel.http.response" && message.requestId === "second") {
        assert.equal(message.status, 503); refused();
      } else if (message.type === "tunnel.http.response.end" && message.requestId === "first") {
        assert.equal(message.error, undefined); ended();
      }
    });
  });
  const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
    env: { PATH: process.env.PATH, HOME: root, DEVICE_ID: "test-mac", CONNECTOR_MODE: "legacy", HERMES_MODE: "live",
      SESSION_OBSERVER_ENABLED: "0", GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`,
      CONNECTOR_TOKEN: "fixture-token", HERMES_SESSION_TOKEN: "fixture-hermes-token",
      HERMES_BASE_URL: `http://127.0.0.1:${address.port}`, HERMES_GO_MAINTENANCE_DIRECTORY: root },
    stdio: "ignore",
  });
  t.after(async () => {
    finishResponse?.();
    if (child.exitCode === null) { const done = once(child, "exit"); child.kill("SIGTERM"); await done; }
    for (const socket of wss.clients) socket.terminate();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  });
  await firstStarted;
  const runID = randomUUID();
  await writeRequest(root, JSON.stringify({ schema: 1, mode: "pause", runID, sourceIdentity: "a".repeat(64) }), { mode: 0o600 });
  gateway.send(JSON.stringify({ type: "tunnel.http.request", version: 1, targetDeviceId: "test-mac",
    id: "second", method: "POST", path: "/api/second", headers: {} }));
  await secondRefused;
  const busy = JSON.parse(await readFile(join(root, "ack.json"), "utf8"));
  assert.equal(busy.paused, true); assert.equal(busy.pid, child.pid); assert.equal(busy.runID, runID);
  assert.ok(busy.activeOperations >= 1, "a streamed body still holds its admission");
  finishResponse(); await firstEnded;
  const deadline = performance.now() + 3000;
  while (true) {
    const ack = JSON.parse(await readFile(join(root, "ack.json"), "utf8"));
    if (ack.activeOperations === 0) { assert.equal(ack.paused, true); break; }
    assert.ok(performance.now() < deadline, "admitted stream did not drain");
    await delay(20);
  }
});

test("maintenance on an existing real RPC channel refuses a prompt and preserves answers and interrupt", { timeout: 10_000 }, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "hg199-rpc-drain-")));
  const server = createServer((_request, response) => response.writeHead(200).end("{}"));
  const wss = new WebSocketServer({ server });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); assert.ok(address && typeof address !== "string");
  let gateway!: (typeof wss.clients extends Set<infer T> ? T : never);
  let connected!: () => void; const localConnected = new Promise<void>((r) => { connected = r; });
  let first!: () => void; const firstAccepted = new Promise<void>((r) => { first = r; });
  let drained!: () => void; const answersDrained = new Promise<void>((r) => { drained = r; });
  let refused!: () => void; const promptRefused = new Promise<void>((r) => { refused = r; });
  const localMethods: string[] = [];
  const send = (id: number, method: string) => gateway.send(JSON.stringify({ type: "tunnel.ws.frame", version: 1,
    id: "rpc", binary: false, dataBase64: Buffer.from(JSON.stringify({ jsonrpc: "2.0", id, method,
      params: { id: "existing-server-request", result: { answer: "synthetic\nanswer" } } })).toString("base64") }));
  wss.on("connection", (socket, request) => {
    if (!request.url?.startsWith("/v1/connect")) {
      socket.on("message", (bytes) => {
        const rpc = JSON.parse(bytes.toString()); localMethods.push(rpc.method);
        if (rpc.id === 1) first();
        if (localMethods.includes("request.answer") && localMethods.includes("session.interrupt")) drained();
        socket.send(JSON.stringify({ jsonrpc: "2.0", id: rpc.id, result: { status: "ok" } }));
      });
      connected(); return;
    }
    gateway = socket;
    socket.on("message", (bytes) => {
      const message = JSON.parse(bytes.toString());
      if (message.type === "hello") {
        socket.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "test-mac" }));
        socket.send(JSON.stringify({ type: "tunnel.ws.open", version: 1, targetDeviceId: "test-mac", id: "rpc", path: "/api/ws" }));
      } else if (message.type === "tunnel.ws.frame") {
        const rpc = JSON.parse(Buffer.from(message.dataBase64, "base64").toString());
        if (rpc.id === 2) {
          assert.equal(rpc.error.code, -32002); assert.equal(rpc.error.message, "HR-HERMESUPDATE-005");
          assert.equal(rpc.error.data.retryable, false); refused();
        }
      }
    });
  });
  const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
    env: { PATH: process.env.PATH, HOME: root, DEVICE_ID: "test-mac", CONNECTOR_MODE: "legacy", HERMES_MODE: "live",
      SESSION_OBSERVER_ENABLED: "0", GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`,
      CONNECTOR_TOKEN: "fixture-token", HERMES_SESSION_TOKEN: "fixture-hermes-token",
      HERMES_BASE_URL: `http://127.0.0.1:${address.port}`, HERMES_GO_MAINTENANCE_DIRECTORY: root }, stdio: "ignore",
  });
  t.after(async () => {
    if (child.exitCode === null) { const done = once(child, "exit"); child.kill("SIGTERM"); await done; }
    for (const socket of wss.clients) socket.terminate();
    await new Promise<void>((resolve) => wss.close(() => resolve()));
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(root, { recursive: true, force: true });
  });
  await localConnected; send(1, "session.prompt"); await firstAccepted;
  await writeRequest(root, JSON.stringify({ schema: 1, mode: "pause", runID: randomUUID(),
    sourceIdentity: "a".repeat(64) }), { mode: 0o600 });
  send(2, "session.prompt"); send(3, "request.answer"); send(4, "session.interrupt");
  await Promise.all([promptRefused, answersDrained]);
  assert.deepEqual(localMethods, ["session.prompt", "request.answer", "session.interrupt"]);
  const busy = JSON.parse(await readFile(join(root, "ack.json"), "utf8"));
  assert.equal(busy.paused, true); assert.equal(busy.activeOperations, 1, "an open channel still prevents service quiescing");
  gateway.send(JSON.stringify({ type: "tunnel.ws.close", version: 1, id: "rpc", code: 1000, reason: "fixture finished" }));
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) {
    const ack = JSON.parse(await readFile(join(root, "ack.json"), "utf8"));
    if (ack.activeOperations === 0) return;
    await delay(20);
  }
  assert.fail("closed RPC channel did not drain");
});

// Match Desktop's atomic mailbox publication: concurrent readers must not see fixture half-writes.
async function writeRequest(directory: string, body: string, options: { mode: number }): Promise<void> {
  const temporary = join(directory, `.${randomUUID()}.request.tmp`);
  await writeFile(temporary, body, options);
  await rename(temporary, join(directory, "request.json"));
}
