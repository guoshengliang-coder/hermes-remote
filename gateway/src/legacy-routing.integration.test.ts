import assert from "node:assert/strict";
import { spawn, execFileSync, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, rm, writeFile, open } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
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

    // HG-222: HEAD keeps the authenticated tunnel and returns metadata without file bytes.
    const metadataRequest = nextMessage(connector, "tunnel.http.request");
    const metadataResponse = fetch(`${gateway.origin}/api/files?path=%2Ftmp%2Fvideo.mp4`, {
      method: "HEAD", headers: { "x-hermes-session-token": gateway.appToken },
    });
    const inspected = await metadataRequest;
    assert.equal(inspected.method, "HEAD");
    connector.send(encodeWireMessage({
      type: "tunnel.http.response", version: PROTOCOL_VERSION, requestId: inspected.id,
      status: 200, headers: {
        "content-length": "132120576", "x-hermes-max-file-bytes": "104857600", "cache-control": "no-store",
      },
    }));
    const info = await metadataResponse;
    assert.equal(info.status, 200);
    assert.equal(info.headers.get("content-length"), "132120576");
    assert.equal(info.headers.get("x-hermes-max-file-bytes"), "104857600");
    assert.equal((await info.arrayBuffer()).byteLength, 0);

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

test("legacy file upload streams bounded chunks and leaves the control connection usable", {
  ...networkTestOptions, timeout: 20_000,
}, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);
    const size = Number(process.env.HR_TEST_UPLOAD_BYTES ?? 7 * 1024 * 1024);
    const body = Buffer.alloc(size, 0x59);
    const digest = createHash("sha256");
    let chunks = 0;
    const complete = new Promise<void>((resolve, reject) => {
      connector.on("message", (raw) => {
        let message: WireMessage;
        try { message = parseWireMessage(raw.toString()); } catch (error) { reject(error); return; }
        if (message.type === "tunnel.http.request.start") {
          connector.send(encodeWireMessage({
            type: "tunnel.http.request.ack", version: PROTOCOL_VERSION,
            requestId: message.id, sequence: -1,
          }));
        }
        if (message.type === "tunnel.http.request.chunk") {
          chunks++;
          digest.update(Buffer.from(message.dataBase64, "base64"));
          connector.send(encodeWireMessage({
            type: "tunnel.http.request.ack", version: PROTOCOL_VERSION,
            requestId: message.requestId, sequence: message.sequence,
          }));
        }
        if (message.type === "tunnel.http.request.end") {
          connector.send(encodeWireMessage({
            type: "tunnel.http.response", version: PROTOCOL_VERSION,
            requestId: message.requestId, status: 201, headers: {},
          }));
          resolve();
        }
      });
    });
    const result = await fetch(`${gateway.origin}/api/files/upload?name=large.pdf`, {
      method: "POST", headers: { "x-hermes-session-token": gateway.appToken }, body,
    });
    await complete;
    assert.equal(result.status, 201);
    assert.equal(chunks, Math.ceil(size / (256 * 1024)));
    assert.equal(digest.digest("hex"), createHash("sha256").update(body).digest("hex"));
    assert.equal(connector.readyState, WebSocket.OPEN);

    const statusRequest = nextMessage(connector, "tunnel.http.request");
    const statusResponse = fetch(`${gateway.origin}/api/status`, {
      headers: { "x-hermes-session-token": gateway.appToken },
    });
    const status = await statusRequest;
    connector.send(encodeWireMessage({
      type: "tunnel.http.response", version: PROTOCOL_VERSION,
      requestId: status.id, status: 200, headers: {},
    }));
    assert.equal((await statusResponse).status, 200);
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
    // HG-104: only the Connector hop negotiates permessage-deflate; the app hop never does.
    assert.match(connector.extensions, /^permessage-deflate\b/);
    assert.equal(app.extensions, "");
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

test("aborting a legacy HTTP client cancels the Connector request", networkTestOptions, async () => {
  const gateway = await startGateway();
  const sockets: WebSocket[] = [];
  try {
    const connector = await openLegacyPeer(gateway, "connector");
    sockets.push(connector);
    const requestMessage = nextMessage(connector, "tunnel.http.request");
    const controller = new AbortController();
    const responsePromise = fetch(`${gateway.origin}/api/slow`, {
      headers: { "x-hermes-session-token": gateway.appToken },
      signal: controller.signal,
    });
    const request = await requestMessage;
    const cancellation = nextMessage(connector, "tunnel.http.cancel");

    controller.abort();
    await assert.rejects(responsePromise, { name: "AbortError" });
    assert.deepEqual(await cancellation, {
      type: "tunnel.http.cancel",
      version: PROTOCOL_VERSION,
      requestId: request.id,
      reason: "client_aborted",
    });
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

test("HG-222: real Mac process returns binary ranges outside the control channel", {
  ...networkTestOptions, timeout: 90_000,
}, async (t) => {
  const gateway = await startGateway({ REQUEST_TIMEOUT_MS: "5000" });
  t.after(() => stopGateway(gateway));
  const file = join(gateway.dataRoot, "video.mp4");
  const bytes = Buffer.alloc(2 * 1024 * 1024 + 7, 37);
  await writeFile(file, bytes);
  let output = "";
  const mac = spawn(process.execPath, [fileURLToPath(new URL("../../connector/dist/index.js", import.meta.url))], {
    env: { PATH: process.env.PATH, HOME: gateway.dataRoot, TMPDIR: gateway.dataRoot,
      CONNECTOR_MODE: "legacy", HERMES_MODE: "mock", SESSION_OBSERVER_ENABLED: "0",
      DEVICE_ID: gateway.deviceId, GATEWAY_URL: `${gateway.wsOrigin}/v1/connect`,
      CONNECTOR_TOKEN: gateway.connectorToken, HERMES_SESSION_TOKEN: "fixture-local-only",
      HERMES_BASE_URL: gateway.origin, FILES_ROOT: gateway.dataRoot,
      MAX_FILE_BYTES: "1048576", FILE_DOWNLOAD_BYTES_PER_SECOND: "134217728" }, stdio: "pipe",
  });
  mac.stdout.on("data", (chunk) => { output += chunk.toString(); });
  mac.stderr.on("data", (chunk) => { output += chunk.toString(); });
  t.after(async () => {
    const exited = new Promise<void>((resolve) => mac.exitCode !== null ? resolve() : mac.once("exit", () => resolve()));
    mac.kill("SIGTERM"); await exited;
  });
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => { clearInterval(poll); reject(new Error("Mac startup timeout")); }, 5000);
    const poll = setInterval(() => {
      if (output.includes("Connected to gateway")) { clearInterval(poll); clearTimeout(timer); resolve(); }
      if (mac.exitCode !== null) { clearInterval(poll); clearTimeout(timer); reject(new Error("Mac exited")); }
    }, 20);
  });
  const headers = { "x-hermes-session-token": gateway.appToken };
  const metadata = await fetch(`${gateway.origin}/api/files?path=${encodeURIComponent(file)}`, { method: "HEAD", headers });
  assert.equal(metadata.headers.get("x-hermes-file-channel"), "1");
  assert.equal((await fetch(`${gateway.origin}/api/files?path=${encodeURIComponent(file)}`, { headers })).status, 413,
    "old GET keeps its old cap");
  const prepared = await fetch(`${gateway.origin}/api/files/downloads`, {
    method: "POST", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify({ path: file }),
  });
  assert.equal(prepared.status, 200);
  assert.equal(prepared.headers.get("x-hermes-file-channel"), "1");
  const descriptor = await prepared.json() as { artifactId: string; etag: string; sizeBytes: number };
  assert.equal(descriptor.sizeBytes, bytes.length);
  assert.match(descriptor.artifactId, /^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(descriptor).includes(file));
  const target = `${gateway.origin}/api/files/downloads/${descriptor.artifactId}`;
  assert.equal((await fetch(target, { headers: { "if-match": descriptor.etag } })).status, 401);
  const part = await fetch(target, { headers: { ...headers, "if-match": descriptor.etag, range: "bytes=100-999999" } });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get("content-range"), `bytes 100-999999/${bytes.length}`);
  assert.equal(part.headers.get("etag"), descriptor.etag);
  assert.deepEqual(Buffer.from(await part.arrayBuffer()), bytes.subarray(100, 1000000));
  const rest = await fetch(target, { headers: { ...headers, "if-match": descriptor.etag, range: `bytes=1000000-${bytes.length - 1}` } });
  assert.equal(rest.status, 206);
  assert.deepEqual(Buffer.from(await rest.arrayBuffer()), bytes.subarray(1000000));
  const unsatisfiable = await fetch(target, { headers: { ...headers, "if-match": descriptor.etag, range: `bytes=${bytes.length}-${bytes.length}` } });
  assert.equal(unsatisfiable.status, 416); await unsatisfiable.text();
  await writeFile(file, Buffer.alloc(bytes.length, 12));
  const changed = await fetch(target, { headers: { ...headers, "if-match": descriptor.etag, range: "bytes=0-100" } });
  assert.equal(changed.status, 412);
  assert.equal((await changed.json() as { error: { code: string } }).error.code, "HR-FILE-011");
  assert.ok(!output.includes(file), "Mac logs omit original paths");

  // A sparse source exercises a real 1 GiB transfer without allocating a 1 GiB fixture buffer.
  const big = join(gateway.dataRoot, "big.bin"), total = 1024 * 1024 * 1024;
  const fd = await open(big, "w"); await fd.truncate(total); await fd.close();
  const bigResponse = await fetch(`${gateway.origin}/api/files/downloads`, {
    method: "POST", headers: { ...headers, "content-type": "application/json" }, body: JSON.stringify({ path: big }),
  });
  const info = await bigResponse.json() as typeof descriptor;
  assert.equal(info.sizeBytes, total);
  const app = await openLegacyPeer(gateway, "app");
  t.after(() => app.close());
  const latencies: number[] = [];
  let peakMacKiB = 0, peakGatewayKiB = 0, received = 0;
  const started = performance.now();
  for (let offset = 0; offset < total; offset += 8 * 1024 * 1024) {
    const response = await fetch(`${gateway.origin}/api/files/downloads/${info.artifactId}`, {
      headers: { ...headers, "if-match": info.etag, range: `bytes=${offset}-${offset + 8 * 1024 * 1024 - 1}` },
    });
    assert.equal(response.status, 206);
    const reader = response.body!.getReader();
    let first = true;
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      received += value.length;
      if (first && offset % (128 * 1024 * 1024) === 0) {
        first = false;
        const events = nextWireMessages(app, 4), time = performance.now();
        app.send(encodeWireMessage({ type: "command", version: 1, id: `chat-${offset}`, targetDeviceId: gateway.deviceId,
          payload: { kind: "chat", input: "control channel remains responsive" } }));
        const replies = await events;
        assert.equal(replies.at(-1)!.type, "event");
        latencies.push(performance.now() - time);
      }
    }
    const rss = (pid: number | undefined) => Number(execFileSync("ps", ["-o", "rss=", "-p", String(pid)], { encoding: "utf8" }).trim());
    peakMacKiB = Math.max(peakMacKiB, rss(mac.pid));
    peakGatewayKiB = Math.max(peakGatewayKiB, rss(gateway.child.pid));
  }
  assert.equal(received, total);
  assert.ok(peakMacKiB < 256 * 1024, "Mac memory remains below 256 MiB during 1 GiB transfer");
  assert.ok(peakGatewayKiB < 256 * 1024, "Gateway memory remains below 256 MiB during 1 GiB transfer");
  assert.ok(Math.max(...latencies) < 2000, "control commands respond during file transfer");
  console.log(JSON.stringify({ bytes: received, durationMs: Math.round(performance.now() - started), peakMacKiB, peakGatewayKiB,
    maxMockChatLatencyMs: Math.round(Math.max(...latencies)) }));
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
