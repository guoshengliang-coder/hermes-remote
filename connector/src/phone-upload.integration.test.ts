import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomUUID, createHash } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readFile, readdir, realpath, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { WebSocketServer, type WebSocket } from "ws";

test("Connector accepts a file above the old 6 MiB limit without losing its bytes", {
  timeout: 15_000,
}, async (t) => {
  const root = await realpath(await mkdtemp(join(tmpdir(), "connector-upload-")));
  t.after(() => rm(root, { recursive: true, force: true }));
  const server = createServer((_request, response) => response.writeHead(404).end());
  const wss = new WebSocketServer({ server });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const payload = Buffer.alloc(Number(process.env.HR_TEST_UPLOAD_BYTES ?? 7 * 1024 * 1024), 0x7f);
  const requestId = randomUUID();
  let output = "";
  const seen: string[] = [];
  let gatewaySocket: WebSocket | undefined;
  const response = new Promise<{ status: number; body: Record<string, unknown> }>((resolve, reject) => {
    setTimeout(() => reject(new Error(`Upload response timed out; frames=${seen.join(",")}; connector=${output}`)), 8_000).unref();
    wss.on("connection", (socket) => {
      gatewaySocket = socket;
      let offset = 0;
      let sequence = 0;
      const sendNext = () => {
        if (offset >= payload.length) {
          socket.send(JSON.stringify({ type: "tunnel.http.request.end", version: 1, requestId }));
          return;
        }
        const bytes = payload.subarray(offset, Math.min(offset + 256 * 1024, payload.length));
        socket.send(JSON.stringify({
          type: "tunnel.http.request.chunk", version: 1, requestId,
          sequence: sequence++, dataBase64: bytes.toString("base64"),
        }));
        offset += bytes.length;
      };
      socket.on("message", (raw) => {
        const message = JSON.parse(raw.toString()) as Record<string, unknown>;
        if (message.type !== "tunnel.http.request.ack") seen.push(String(message.type));
        if (message.type === "hello") {
          socket.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "test-mac" }), (error) => {
            if (error) { reject(error); return; }
            seen.push("ack-sent");
            socket.send(JSON.stringify({
              type: "tunnel.http.request.start", version: 1, id: requestId, targetDeviceId: "test-mac",
              method: "POST", path: "/api/files/upload?name=fixture.pdf",
              headers: { "content-type": "application/pdf" },
            }), (sendError) => {
              if (sendError) reject(sendError);
              else seen.push("start-sent");
            });
          });
        }
        if (message.type === "tunnel.http.request.ack" && message.requestId === requestId) sendNext();
        if (message.type === "tunnel.http.response" && message.requestId === requestId) {
          resolve({
            status: message.status as number,
            body: JSON.parse(Buffer.from(message.bodyBase64 as string, "base64").toString()) as Record<string, unknown>,
          });
        }
      });
      socket.on("error", reject);
      socket.on("close", (code) => seen.push(`closed:${code}`));
    });
  });
  const child = spawn(process.execPath, [new URL("./index.js", import.meta.url).pathname], {
    env: {
      PATH: process.env.PATH, HOME: root,
      CONNECTOR_MODE: "legacy", HERMES_MODE: "mock", SESSION_OBSERVER_ENABLED: "0",
      DEVICE_ID: "test-mac",
      GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`,
      CONNECTOR_TOKEN: "fixture-token", HERMES_SESSION_TOKEN: "fixture-session-token",
      HERMES_BASE_URL: `http://127.0.0.1:${address.port}`,
      FILES_ROOT: root, UPLOAD_ROOT: join(root, "uploads"),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
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
  const result = await Promise.race([
    response,
    once(child, "exit").then(() => { throw new Error(`Connector exited before upload: ${output}`); }),
  ]);
  assert.equal(result.status, 201, output);
  assert.equal(result.body.size, payload.length);
  const uploaded = await readFile(result.body.path as string);
  assert.equal(createHash("sha256").update(uploaded).digest("hex"), createHash("sha256").update(payload).digest("hex"));
  assert.deepEqual((await readdir(join(root, "uploads"))).filter((name) => name.endsWith(".part")), []);

  // A dropped phone request must remove the partially written Mac file.
  assert.ok(gatewaySocket);
  const cancelledId = randomUUID();
  const nextAck = (sequence: number) => new Promise<void>((resolve) => {
    const listener = (raw: Buffer) => {
      const message = JSON.parse(raw.toString()) as Record<string, unknown>;
      if (message.type === "tunnel.http.request.ack" && message.requestId === cancelledId
          && message.sequence === sequence) {
        gatewaySocket?.off("message", listener);
        resolve();
      }
    };
    gatewaySocket?.on("message", listener);
  });
  const startAck = nextAck(-1);
  gatewaySocket.send(JSON.stringify({
    type: "tunnel.http.request.start", version: 1, id: cancelledId, targetDeviceId: "test-mac",
    method: "POST", path: "/api/files/upload?name=cancel.pdf", headers: {},
  }));
  await startAck;
  const chunkAck = nextAck(0);
  gatewaySocket.send(JSON.stringify({
    type: "tunnel.http.request.chunk", version: 1, requestId: cancelledId,
    sequence: 0, dataBase64: Buffer.alloc(256 * 1024).toString("base64"),
  }));
  await chunkAck;
  gatewaySocket.send(JSON.stringify({
    type: "tunnel.http.cancel", version: 1, requestId: cancelledId, reason: "client_aborted",
  }));
  let partials: string[] = [];
  for (let attempt = 0; attempt < 50; attempt++) {
    partials = (await readdir(join(root, "uploads"))).filter((name) => name.endsWith(".part"));
    if (partials.length === 0) break;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.deepEqual(partials, []);

  // A new Connector still accepts the old Gateway's single-frame uploads during rollout.
  const legacyId = randomUUID();
  const legacyResponse = new Promise<number>((resolve) => {
    const listener = (raw: Buffer) => {
      const message = JSON.parse(raw.toString()) as Record<string, unknown>;
      if (message.type === "tunnel.http.response" && message.requestId === legacyId) {
        gatewaySocket?.off("message", listener);
        resolve(message.status as number);
      }
    };
    gatewaySocket?.on("message", listener);
  });
  gatewaySocket.send(JSON.stringify({
    type: "tunnel.http.request", version: 1, id: legacyId, targetDeviceId: "test-mac",
    method: "POST", path: "/api/files/upload?name=legacy.txt", headers: {},
    bodyBase64: Buffer.from("legacy").toString("base64"),
  }));
  assert.equal(await legacyResponse, 201);
});
