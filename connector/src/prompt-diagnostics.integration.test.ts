import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, realpath, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { WebSocketServer } from "ws";

for (const outcome of ["delivered", "lost", "disconnected"] as const) {
  const lostReply = outcome != "delivered";
  test(`real Connector traces a prompt with ${outcome} acknowledgement without replay or content`, { timeout: 12_000 }, async (t) => {
    const root = await realpath(await mkdtemp(join(tmpdir(), "prompt-trace-")));
    t.after(() => rm(root, { recursive: true, force: true }));
    const server = createServer((_request, response) => response.writeHead(200).end('{"ok":true}'));
    const relay = new WebSocketServer({ noServer: true });
    const hermes = new WebSocketServer({ noServer: true });
    server.on("upgrade", (request, socket, head) => {
      const target = request.url === "/v1/connect" ? relay : hermes;
      target.handleUpgrade(request, socket, head, (ws) => target.emit("connection", ws, request));
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address(); assert.ok(address && typeof address !== "string");
    const tunnel = "fixture-prompt-tunnel";
    let submissions = 0;
    let finish!: () => void;
    const finished = new Promise<void>((resolve) => { finish = resolve; });
    hermes.on("connection", (socket) => {
      socket.send(JSON.stringify({ jsonrpc: "2.0", method: "event", params: { type: "gateway.ready", payload: {} } }));
      socket.on("message", (raw) => {
        const request = JSON.parse(raw.toString());
        if (request.method !== "prompt.submit") return;
        submissions++;
        if (!lostReply) socket.send(JSON.stringify({ jsonrpc: "2.0", id: request.id, result: { private: "private-result" } }));
        else if (outcome === "disconnected") socket.terminate();
        else setTimeout(() => {
          for (const peer of relay.clients) peer.send(JSON.stringify({ type: "tunnel.ws.close", version: 1, id: tunnel, code: 1000 }));
        }, 50);
      });
    });
    relay.on("connection", (socket) => socket.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.type === "hello") {
        socket.send(JSON.stringify({ type: "hello_ack", version: 1, deviceId: "fixture-mac" }));
        socket.send(JSON.stringify({ type: "tunnel.ws.open", version: 1, id: tunnel, targetDeviceId: "fixture-mac", path: "/api/ws" }));
      }
      if (message.type === "tunnel.ws.frame") {
        const frame = JSON.parse(Buffer.from(message.dataBase64, "base64").toString());
        if (frame.params?.type === "gateway.ready") socket.send(JSON.stringify({
          type: "tunnel.ws.frame", version: 1, id: tunnel, binary: false,
          dataBase64: Buffer.from(JSON.stringify({ id: 7, method: "prompt.submit", params: { text: "private-prompt".repeat(700) } })).toString("base64"),
        }));
        if (frame.id === 7) finish();
      }
      if (lostReply && message.type === "tunnel.ws.close") finish();
    }));
    const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
      env: { PATH: process.env.PATH, HOME: root, CONNECTOR_MODE: "legacy", HERMES_MODE: "mock", SESSION_OBSERVER_ENABLED: "0",
        DEVICE_ID: "fixture-mac", GATEWAY_URL: `ws://127.0.0.1:${address.port}/v1/connect`, CONNECTOR_TOKEN: "private-connector-token",
        HERMES_SESSION_TOKEN: "private-local-token", HERMES_BASE_URL: `http://127.0.0.1:${address.port}` },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.on("data", (data) => { output += data.toString(); });
    child.stderr.on("data", (data) => { output += data.toString(); });
    t.after(async () => {
      if (child.exitCode === null) { const exited = once(child, "exit"); child.kill("SIGTERM"); await exited; }
      for (const ws of [...relay.clients, ...hermes.clients]) ws.terminate();
      await Promise.all([new Promise<void>((resolve) => relay.close(() => resolve())), new Promise<void>((resolve) => hermes.close(() => resolve()))]);
      await new Promise<void>((resolve) => server.close(() => resolve()));
    });
    await Promise.race([finished, once(child, "exit").then(() => { throw new Error("Connector exited before fixture finished"); })]);
    // stdout delivery may follow the relayed frame on the other pipe.
    await new Promise<void>((resolve) => setTimeout(resolve, 50));
    const events = output.split("\n").filter((line) => line.startsWith("{")).map((line) => JSON.parse(line));
    assert.equal(submissions, 1);
    assert.ok(events.some((row) => row.kind === "tunnel.rpc.local_sent" && row.rpcId === 7 && row.method === "prompt.submit"));
    assert.ok(events.some((row) => row.kind === (lostReply ? "tunnel.rpc.unanswered" : "tunnel.rpc.local_answer") && row.rpcId === 7));
    assert.doesNotMatch(output, /private-prompt|private-result|private-connector-token|private-local-token/);
  });
}
