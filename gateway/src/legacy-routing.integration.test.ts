import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { WebSocket } from "ws";
import {
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type WireMessage,
} from "@hermes-remote/protocol";

const networkTestOptions = {
  skip: process.env.RUN_NETWORK_TESTS !== "1"
    ? "set RUN_NETWORK_TESTS=1 for loopback integration"
    : false,
  timeout: 10_000,
};

test("legacy HTTP routing preserves authentication, header filtering, and complete responses", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const unauthorized = await fetch(`${gateway.origin}/api/status`);
    assert.equal(unauthorized.status, 401);
    assert.deepEqual(await unauthorized.json(), { error: "unauthorized" });

    const offline = await fetch(`${gateway.origin}/api/status`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    assert.equal(offline.status, 503);
    assert.deepEqual(await offline.json(), { error: "device_offline" });

    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);
    const requestMessage = nextMessage(connector, "tunnel.http.request");
    const responsePromise = fetch(`${gateway.origin}/api/example?view=full`, {
      method: "POST",
      headers: {
        "x-hermes-session-token": gateway.appToken,
        accept: "application/json",
        "content-type": "application/json",
        "x-must-not-forward": "private",
      },
      body: JSON.stringify({ input: "hello" }),
    });

    const tunneled = await requestMessage;
    assert.equal(tunneled.targetDeviceId, gateway.deviceId);
    assert.equal(tunneled.method, "POST");
    assert.equal(tunneled.path, "/api/example?view=full");
    assert.deepEqual(tunneled.headers, {
      accept: "application/json",
      "content-type": "application/json",
    });
    assert.deepEqual(JSON.parse(Buffer.from(tunneled.bodyBase64 ?? "", "base64").toString()), {
      input: "hello",
    });

    connector.send(encodeWireMessage({
      type: "tunnel.http.response",
      version: PROTOCOL_VERSION,
      requestId: tunneled.id,
      status: 201,
      headers: {
        "content-type": "text/plain",
        "cache-control": "private, max-age=5",
        "set-cookie": "must-not-reach-the-phone=1",
      },
      bodyBase64: Buffer.from("created").toString("base64"),
    }));

    const response = await responsePromise;
    assert.equal(response.status, 201);
    assert.equal(response.headers.get("content-type"), "text/plain");
    assert.equal(response.headers.get("cache-control"), "private, max-age=5");
    assert.equal(response.headers.get("set-cookie"), null);
    assert.equal(await response.text(), "created");
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

test("legacy HTTP streaming acknowledges ordered chunks after writing them", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);
    const requestMessage = nextMessage(connector, "tunnel.http.request");
    const responsePromise = fetch(`${gateway.origin}/api/files?path=report.txt`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    const tunneled = await requestMessage;

    connector.send(encodeWireMessage({
      type: "tunnel.http.response.start",
      version: PROTOCOL_VERSION,
      requestId: tunneled.id,
      status: 200,
      headers: {
        "content-type": "application/octet-stream",
        "content-length": "6",
      },
    }));

    for (const [sequence, data] of ["abc", "def"].entries()) {
      const acknowledgement = nextMessage(connector, "tunnel.http.response.ack");
      connector.send(encodeWireMessage({
        type: "tunnel.http.response.chunk",
        version: PROTOCOL_VERSION,
        requestId: tunneled.id,
        sequence,
        dataBase64: Buffer.from(data).toString("base64"),
      }));
      const ack = await acknowledgement;
      assert.equal(ack.requestId, tunneled.id);
      assert.equal(ack.sequence, sequence);
    }

    connector.send(encodeWireMessage({
      type: "tunnel.http.response.end",
      version: PROTOCOL_VERSION,
      requestId: tunneled.id,
    }));
    const response = await responsePromise;
    assert.equal(response.status, 200);
    assert.equal(Buffer.from(await response.arrayBuffer()).toString(), "abcdef");
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

test("legacy WebSocket routing preserves frames and Connector close details", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    assert.equal(await rejectedUpgradeStatus(`${gateway.wsOrigin}/api/ws`), 401);
    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);

    const openMessage = nextMessage(connector, "tunnel.ws.open");
    const app = await openSocket(`${gateway.wsOrigin}/api/ws`, {
      "x-hermes-session-token": gateway.appToken,
    });
    sockets.push(app);
    const opened = await openMessage;
    assert.equal(opened.targetDeviceId, gateway.deviceId);
    assert.equal(opened.path, "/api/ws");

    const frameMessage = nextMessage(connector, "tunnel.ws.frame");
    app.send("phone-to-mac");
    const frame = await frameMessage;
    assert.equal(frame.id, opened.id);
    assert.equal(frame.binary, false);
    assert.equal(Buffer.from(frame.dataBase64, "base64").toString(), "phone-to-mac");

    const appFrame = nextRawMessage(app);
    connector.send(encodeWireMessage({
      type: "tunnel.ws.frame",
      version: PROTOCOL_VERSION,
      id: opened.id,
      dataBase64: Buffer.from("mac-to-phone").toString("base64"),
      binary: false,
    }));
    assert.equal((await appFrame).toString(), "mac-to-phone");

    const appClose = nextClose(app);
    connector.send(encodeWireMessage({
      type: "tunnel.ws.close",
      version: PROTOCOL_VERSION,
      id: opened.id,
      code: 1000,
      reason: "remote complete",
    }));
    assert.deepEqual(await appClose, { code: 1000, reason: "remote complete" });
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

test("disconnecting a legacy Connector fails its pending HTTP request", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);
    const requestMessage = nextMessage(connector, "tunnel.http.request");
    const responsePromise = fetch(`${gateway.origin}/api/status`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    await requestMessage;
    connector.close();

    const response = await responsePromise;
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: "connector_disconnected" });
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

test("legacy HTTP routing enforces pending capacity and request timeout", networkTestOptions, async () => {
  const gateway = await startGateway({
    MAX_PENDING_REQUESTS: "1",
    REQUEST_TIMEOUT_MS: "150",
  });
  const sockets: WebSocket[] = [];
  try {
    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);
    const requestMessage = nextMessage(connector, "tunnel.http.request");
    const pendingResponse = fetch(`${gateway.origin}/api/slow`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    await requestMessage;

    const capacityResponse = await fetch(`${gateway.origin}/api/second`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    assert.equal(capacityResponse.status, 503);
    assert.deepEqual(await capacityResponse.json(), { error: "relay_capacity_reached" });

    const timeoutResponse = await pendingResponse;
    assert.equal(timeoutResponse.status, 504);
    assert.deepEqual(await timeoutResponse.json(), { error: "connector_timeout" });
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

test("legacy control routing preserves command ownership and disconnect errors", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const connector = await openLegacyPeer(gateway, "connector");
    const app = await openLegacyPeer(gateway, "app");
    sockets.push(connector, app);

    const forwardedCommand = nextMessage(connector, "command");
    app.send(encodeWireMessage({
      type: "command",
      version: PROTOCOL_VERSION,
      id: "command-1",
      targetDeviceId: gateway.deviceId,
      payload: { kind: "chat", input: "hello" },
    }));
    assert.deepEqual(await forwardedCommand, {
      type: "command",
      version: PROTOCOL_VERSION,
      id: "command-1",
      targetDeviceId: gateway.deviceId,
      payload: { kind: "chat", input: "hello" },
    });

    for (const event of ["accepted", "delta", "complete"] as const) {
      const appEvent = nextMessage(app, "event");
      connector.send(encodeWireMessage({
        type: "event",
        version: PROTOCOL_VERSION,
        requestId: "command-1",
        event,
        data: { event },
      }));
      assert.deepEqual(await appEvent, {
        type: "event",
        version: PROTOCOL_VERSION,
        requestId: "command-1",
        event,
        data: { event },
      });
    }

    const pendingCommand = nextMessage(connector, "command");
    app.send(encodeWireMessage({
      type: "command",
      version: PROTOCOL_VERSION,
      id: "command-2",
      targetDeviceId: gateway.deviceId,
      payload: { kind: "chat", input: "wait" },
    }));
    await pendingCommand;
    const disconnectError = nextMessage(app, "error");
    connector.close();
    const error = await disconnectError;
    assert.equal(error.code, "connector_disconnected");
    assert.equal(error.requestId, "command-2");
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

test("legacy control preserves authentication, status, and Connector replacement", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const unauthorized = await openSocket(`${gateway.wsOrigin}/v1/connect`);
    sockets.push(unauthorized);
    const unauthorizedClose = nextClose(unauthorized);
    unauthorized.send(encodeWireMessage({
      type: "hello",
      version: PROTOCOL_VERSION,
      role: "connector",
      deviceId: gateway.deviceId,
      token: "incorrect-connector-token",
    }));
    assert.deepEqual(await unauthorizedClose, { code: 4401, reason: "unauthorized" });

    const firstConnector = await openLegacyPeer(gateway, "connector");
    sockets.push(firstConnector);

    const app = await openSocket(`${gateway.wsOrigin}/v1/connect`);
    sockets.push(app);
    const initialMessages = nextWireMessages(app, 2);
    app.send(encodeWireMessage({
      type: "hello",
      version: PROTOCOL_VERSION,
      role: "app",
      deviceId: gateway.deviceId,
      token: gateway.appToken,
    }));
    const [acknowledgement, initialStatus] = await initialMessages;
    assert.equal(acknowledgement.type, "hello_ack");
    assert.deepEqual(initialStatus, {
      type: "device_status",
      version: PROTOCOL_VERSION,
      deviceId: gateway.deviceId,
      online: true,
    });

    const replacementStatus = nextMessage(app, "device_status");
    const replacedClose = nextClose(firstConnector);
    const replacement = await openLegacyPeer(gateway, "connector");
    sockets.push(replacement);
    assert.deepEqual(await replacedClose, {
      code: 4409,
      reason: "replaced by a new connection",
    });
    assert.equal((await replacementStatus).online, true);

    const requestMessage = nextMessage(replacement, "tunnel.http.request");
    const responsePromise = fetch(`${gateway.origin}/api/status`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    const request = await requestMessage;
    replacement.send(encodeWireMessage({
      type: "tunnel.http.response",
      version: PROTOCOL_VERSION,
      requestId: request.id,
      status: 200,
      headers: { "content-type": "application/json" },
      bodyBase64: Buffer.from('{"ok":true}').toString("base64"),
    }));
    assert.equal((await responsePromise).status, 200);
  } finally {
    sockets.forEach((socket) => socket.close());
    await stopGateway(gateway);
  }
});

interface GatewayProcess {
  child: ChildProcessWithoutNullStreams;
  origin: string;
  wsOrigin: string;
  appToken: string;
  connectorToken: string;
  deviceId: string;
  dataRoot: string;
}

async function startGateway(overrides: NodeJS.ProcessEnv = {}): Promise<GatewayProcess> {
  const port = await unusedPort();
  const dataRoot = await mkdtemp(join(tmpdir(), "hermes-gateway-legacy-"));
  const appToken = "legacy-integration-app-token";
  const connectorToken = "legacy-integration-connector-token";
  const deviceId = "legacy-test-mac";
  const child = spawn(process.execPath, ["dist/index.js"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      APP_TOKEN: appToken,
      CONNECTOR_TOKEN: connectorToken,
      DEFAULT_DEVICE_ID: deviceId,
      LIFECYCLE_EVENT_STORE_FILE: join(dataRoot, "events.json"),
      REQUEST_TIMEOUT_MS: "1000",
      ...overrides,
    },
    stdio: "pipe",
  });
  await waitForGateway(child);
  return {
    child,
    origin: `http://127.0.0.1:${port}`,
    wsOrigin: `ws://127.0.0.1:${port}`,
    appToken,
    connectorToken,
    deviceId,
    dataRoot,
  };
}

async function stopGateway(gateway: GatewayProcess): Promise<void> {
  gateway.child.kill("SIGTERM");
  await new Promise<void>((resolveExit) => {
    if (gateway.child.exitCode !== null) resolveExit();
    else gateway.child.once("exit", () => resolveExit());
  });
  await rm(gateway.dataRoot, { recursive: true, force: true });
}

async function openLegacyPeer(
  gateway: GatewayProcess,
  role: "app" | "connector",
): Promise<WebSocket> {
  const socket = await openSocket(`${gateway.wsOrigin}/v1/connect`);
  const acknowledgement = nextMessage(socket, "hello_ack");
  socket.send(encodeWireMessage({
    type: "hello",
    version: PROTOCOL_VERSION,
    role,
    deviceId: gateway.deviceId,
    token: role === "app" ? gateway.appToken : gateway.connectorToken,
  }));
  await acknowledgement;
  return socket;
}

function nextMessage<T extends WireMessage["type"]>(
  socket: WebSocket,
  expectedType: T,
): Promise<Extract<WireMessage, { type: T }>> {
  return new Promise((resolveMessage, reject) => {
    const timer = setTimeout(() => reject(new Error(`timed out waiting for ${expectedType}`)), 2_000);
    const listener = (raw: WebSocket.RawData): void => {
      let message: WireMessage;
      try {
        message = parseWireMessage(raw.toString());
      } catch (error) {
        clearTimeout(timer);
        socket.off("message", listener);
        reject(error);
        return;
      }
      if (message.type !== expectedType) return;
      clearTimeout(timer);
      socket.off("message", listener);
      resolveMessage(message as Extract<WireMessage, { type: T }>);
    };
    socket.on("message", listener);
  });
}

function nextWireMessages(socket: WebSocket, count: number): Promise<WireMessage[]> {
  return new Promise((resolveMessages, reject) => {
    const messages: WireMessage[] = [];
    const timer = setTimeout(() => reject(new Error("timed out waiting for messages")), 2_000);
    const listener = (raw: WebSocket.RawData): void => {
      try {
        messages.push(parseWireMessage(raw.toString()));
      } catch (error) {
        clearTimeout(timer);
        socket.off("message", listener);
        reject(error);
        return;
      }
      if (messages.length !== count) return;
      clearTimeout(timer);
      socket.off("message", listener);
      resolveMessages(messages);
    };
    socket.on("message", listener);
  });
}

function nextRawMessage(socket: WebSocket): Promise<Buffer> {
  return new Promise((resolveMessage, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for WebSocket frame")), 2_000);
    socket.once("message", (raw) => {
      clearTimeout(timer);
      resolveMessage(Buffer.isBuffer(raw) ? raw : Buffer.from(raw as ArrayBuffer));
    });
  });
}

function nextClose(socket: WebSocket): Promise<{ code: number; reason: string }> {
  return new Promise((resolveClose, reject) => {
    const timer = setTimeout(() => reject(new Error("timed out waiting for WebSocket close")), 2_000);
    socket.once("close", (code, reason) => {
      clearTimeout(timer);
      resolveClose({ code, reason: reason.toString() });
    });
  });
}

function openSocket(url: string, headers?: Record<string, string>): Promise<WebSocket> {
  return new Promise((resolveSocket, reject) => {
    const socket = new WebSocket(url, { headers });
    socket.once("open", () => resolveSocket(socket));
    socket.once("error", reject);
  });
}

function rejectedUpgradeStatus(url: string): Promise<number> {
  return new Promise((resolveStatus, reject) => {
    const socket = new WebSocket(url);
    socket.once("unexpected-response", (_request, response) => {
      response.resume();
      resolveStatus(response.statusCode ?? 0);
    });
    socket.once("open", () => {
      socket.close();
      reject(new Error("WebSocket upgrade unexpectedly succeeded"));
    });
    socket.once("error", (error) => {
      if ((error as Error & { code?: string }).code !== "ECONNRESET") reject(error);
    });
  });
}

function unusedPort(): Promise<number> {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("unable to allocate port"));
        return;
      }
      server.close((error) => error ? reject(error) : resolvePort(address.port));
    });
  });
}

function waitForGateway(child: ChildProcessWithoutNullStreams): Promise<void> {
  return new Promise((resolveReady, reject) => {
    const timer = setTimeout(() => reject(new Error("gateway did not start")), 3_000);
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`gateway exited early with ${code}: ${child.stderr.read()?.toString() ?? ""}`));
    });
    child.stdout.on("data", (chunk: Buffer) => {
      if (!chunk.toString().includes("Hermes Remote Gateway listening")) return;
      clearTimeout(timer);
      resolveReady();
    });
  });
}
