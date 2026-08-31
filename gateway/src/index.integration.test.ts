import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createServer } from "node:net";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { WebSocket } from "ws";
import {
  encodeWireMessage,
  parseWireMessage,
  PROTOCOL_VERSION,
  type WireMessage,
} from "@hermes-remote/protocol";

test("Relay durably acknowledges and serves Connector lifecycle events", {
  skip: process.env.RUN_NETWORK_TESTS !== "1" ? "set RUN_NETWORK_TESTS=1 for loopback integration" : false,
}, async () => {
  const port = await unusedPort();
  const root = await mkdtemp(join(tmpdir(), "hermes-gateway-events-"));
  const appToken = "integration-app-token";
  const connectorToken = "integration-connector-token";
  const child = spawn(process.execPath, ["dist/index.js"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      APP_TOKEN: appToken,
      CONNECTOR_TOKEN: connectorToken,
      LIFECYCLE_EVENT_STORE_FILE: join(root, "events.json"),
    },
    stdio: "pipe",
  });

  try {
    await waitForGateway(child);
    const connector = new WebSocket(`ws://127.0.0.1:${port}/v1/connect`);
    await new Promise<void>((resolve, reject) => {
      connector.once("open", resolve);
      connector.once("error", reject);
    });
    connector.send(encodeWireMessage({
      type: "hello",
      version: PROTOCOL_VERSION,
      role: "connector",
      deviceId: "mac-mini",
      token: connectorToken,
    }));
    await nextMessage(connector, "hello_ack");

    const event = {
      type: "session.lifecycle" as const,
      version: PROTOCOL_VERSION,
      eventId: "event-integration-1",
      deviceId: "mac-mini",
      runtimeSessionId: "runtime-1",
      storedSessionId: "stored-1",
      event: "run.completed" as const,
      state: "idle" as const,
      occurredAt: "2026-08-31T01:02:03.000Z",
      title: "Background task",
    };
    connector.send(encodeWireMessage(event));
    const ack = await nextMessage(connector, "session.lifecycle.ack");
    assert.equal(ack.eventId, event.eventId);

    // Resend after a hypothetical lost ACK: persistence is idempotent and still ACKs.
    connector.send(encodeWireMessage(event));
    assert.equal((await nextMessage(connector, "session.lifecycle.ack")).eventId, event.eventId);

    const headers = { "x-hermes-session-token": appToken, "content-type": "application/json" };
    const pageResponse = await fetch(`http://127.0.0.1:${port}/api/mobile/events?after=0&limit=20`, { headers });
    assert.equal(pageResponse.status, 200);
    const page = await pageResponse.json() as { events: Array<{ event: { eventId: string } }> };
    assert.deepEqual(page.events.map((item) => item.event.eventId), [event.eventId]);

    for (const action of ["ack", "read"]) {
      const response = await fetch(`http://127.0.0.1:${port}/api/mobile/events/${action}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ event_ids: [event.eventId] }),
      });
      assert.equal(response.status, 200);
      assert.equal((await response.json() as { changed: number }).changed, 1);
    }
    connector.close();
  } finally {
    child.kill("SIGTERM");
    await new Promise<void>((resolve) => {
      if (child.exitCode !== null) resolve();
      else child.once("exit", () => resolve());
    });
  }
});

function nextMessage<T extends WireMessage["type"]>(
  socket: WebSocket,
  expectedType: T,
): Promise<Extract<WireMessage, { type: T }>> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for ${expectedType}`)), 2_000);
    const onMessage = (raw: WebSocket.RawData): void => {
      let message;
      try {
        message = parseWireMessage(raw.toString());
      } catch (error) {
        clearTimeout(timer);
        reject(error);
        return;
      }
      if (message.type !== expectedType) return;
      clearTimeout(timer);
      socket.off("message", onMessage);
      resolve(message as Extract<WireMessage, { type: T }>);
    };
    socket.on("message", onMessage);
  });
}

function unusedPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("unable to allocate test port"));
        return;
      }
      server.close((error) => error ? reject(error) : resolve(address.port));
    });
  });
}

function waitForGateway(child: ChildProcessWithoutNullStreams): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("gateway did not start")), 3_000);
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`gateway exited early with ${code}: ${child.stderr.read()?.toString() ?? ""}`));
    });
    child.stdout.on("data", (chunk: Buffer) => {
      if (!chunk.toString().includes("Hermes Remote Gateway listening")) return;
      clearTimeout(timer);
      resolve();
    });
  });
}
