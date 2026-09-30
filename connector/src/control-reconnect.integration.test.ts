import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, generateKeyPairSync, randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, realpath, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";

for (const mode of ["legacy", "account"] as const) {
  test(`${mode}: repeated authenticated short connections increase the actual reconnect wait`, {
    timeout: 15_000,
  }, async (t) => {
    const root = await realpath(await mkdtemp(join(tmpdir(), "connector-reconnect-")));
    t.after(() => rm(root, { recursive: true, force: true }));
    const server = createServer((_request, response) => {
      response.writeHead(404).end();
    });
    const wss = new WebSocketServer({ server });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    assert.ok(address && typeof address !== "string");
    const bindingId = randomUUID();
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const credentialPath = join(root, "credential.json");
    await writeFile(credentialPath, JSON.stringify({
      schemaVersion: 1, bindingId, generation: 1,
      privateKey: privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32).toString("base64url"),
      publicKeyFingerprint: createHash("sha256")
        .update(publicKey.export({ format: "der", type: "spki" }).subarray(-32)).digest("hex"),
    }), { mode: 0o600 });

    const connectedAt: number[] = [];
    const thirdConnection = new Promise<void>((resolve) => {
      wss.on("connection", (socket) => {
        connectedAt.push(performance.now());
        if (connectedAt.length === 3) resolve();
        socket.once("message", () => {
          socket.send(JSON.stringify(mode === "legacy"
            ? { type: "hello_ack", version: 1, deviceId: "test-mac" }
            : { type: "connector.ready", version: 2, bindingId, generation: 1,
                deviceId: "test-mac", bindingStatus: "active", routingEnabled: true }));
          setTimeout(() => socket.close(1012, "fixture-private-close-reason"), 50);
        });
      });
    });
    const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
      env: {
        PATH: process.env.PATH, HOME: root,
        CONNECTOR_MODE: mode, HERMES_MODE: "mock", SESSION_OBSERVER_ENABLED: "0",
        GATEWAY_URL: `ws://127.0.0.1:${address.port}/${mode === "legacy" ? "v1" : "v2"}/connect`,
        CONNECTOR_TOKEN: "fixture-connector-token",
        ACCOUNT_CONNECTOR_CREDENTIAL_FILE: credentialPath,
        HERMES_BASE_URL: `http://127.0.0.1:${address.port}`,
        HERMES_SESSION_TOKEN: "fixture-session-token",
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
    await Promise.race([
      thirdConnection,
      once(child, "exit").then(() => { throw new Error(`Connector exited before reconnecting: ${output}`); }),
    ]);
    assert.ok(connectedAt[2] - connectedAt[1] >= 2_000,
      `second retry must retain backoff despite WebSocket open/ready; intervals=${connectedAt.map((n) => Math.round(n-connectedAt[0]))}`);
    const decisions = output.split("\n").filter((line) => line.startsWith("{"))
      .map((line) => JSON.parse(line) as Record<string, unknown>)
      .filter((line) => line.kind === "relay.reconnect_scheduled");
    assert.deepEqual(decisions.slice(0, 2).map((line) => [line.mode, line.attempt, line.stable]),
      [[mode, 1, false], [mode, 2, false]]);
    for (const secret of ["fixture-connector-token", "fixture-session-token", "fixture-private-close-reason"]) {
      assert.equal(output.includes(secret), false, "reconnect diagnostics must not quote credentials or close reasons");
    }
  });
}
