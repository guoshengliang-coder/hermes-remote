import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { WebSocket, WebSocketServer, type ServerOptions } from "ws";
import {
  appWebSocketServerOptions,
  connectorWebSocketServerOptions,
  TUNNEL_DEFLATE_THRESHOLD_BYTES,
} from "./gateway-server.js";

test("Connector WebSocketServers negotiate bounded permessage-deflate", async () => {
  const options = connectorWebSocketServerOptions(64 * 1024);
  assert.equal(options.maxPayload, 64 * 1024);
  assert.deepEqual(options.perMessageDeflate, {
    threshold: TUNNEL_DEFLATE_THRESHOLD_BYTES,
    serverNoContextTakeover: true,
    clientNoContextTakeover: true,
    zlibDeflateOptions: { level: 6, memLevel: 7 },
  });

  const harness = await startHarness(options);
  try {
    // A default ws client, exactly as the Connector constructs it, offers the extension.
    const client = await harness.connect();
    assert.equal(client.extensions, "permessage-deflate");
    const negotiated = harness.negotiated.at(-1) ?? "";
    assert.match(negotiated, /^permessage-deflate\b/);
    assert.match(negotiated, /server_no_context_takeover/);
    assert.match(negotiated, /client_no_context_takeover/);

    // A compressible body larger than the threshold survives the round trip intact.
    const body = JSON.stringify({ sessions: Array.from({ length: 200 }, (_, i) => ({ id: `s-${i}`, title: "t" })) });
    const echoed = new Promise<string>((resolve) => client.once("message", (data) => resolve(data.toString())));
    client.send(body);
    assert.equal(await echoed, body);
    client.close();
  } finally {
    await harness.close();
  }
});

test("Connector WebSocketServers bound the inflated size, not the compressed size", async () => {
  const harness = await startHarness(connectorWebSocketServerOptions(2048));
  try {
    const client = await harness.connect();
    const closed = new Promise<number>((resolve) => client.once("close", (code) => resolve(code)));
    // ~8 KB of repeated text deflates far below 2 KB on the wire, but must still be refused.
    client.send("x".repeat(8 * 1024));
    assert.equal(await closed, 1009);
  } finally {
    await harness.close();
  }
});

test("Connector WebSocketServers still accept clients that do not offer deflate", async () => {
  const harness = await startHarness(connectorWebSocketServerOptions(4096));
  try {
    const client = await harness.connect({ perMessageDeflate: false });
    assert.equal(client.extensions, "");
    assert.equal(harness.negotiated.at(-1), undefined);
    client.close();
  } finally {
    await harness.close();
  }
});

test("app WebSocketServer never negotiates permessage-deflate", async () => {
  const options = appWebSocketServerOptions(2048);
  assert.equal(options.maxPayload, 2048);
  assert.equal(options.perMessageDeflate, false);
  const harness = await startHarness(options);
  try {
    const client = await harness.connect();
    assert.equal(client.extensions, "");
    assert.equal(harness.negotiated.at(-1), undefined);
    client.close();
  } finally {
    await harness.close();
  }
});

async function startHarness(options: ServerOptions): Promise<{
  connect(clientOptions?: { perMessageDeflate?: boolean }): Promise<WebSocket>;
  /** The `Sec-WebSocket-Extensions` response header of each handshake, as the client saw it. */
  negotiated: Array<string | undefined>;
  close(): Promise<void>;
}> {
  const wss = new WebSocketServer(options);
  wss.on("connection", (socket) => {
    socket.on("error", () => {});
    socket.on("message", (data, binary) => socket.send(data, { binary }));
  });
  const server: Server = createServer();
  server.on("upgrade", (request, socket, head) => {
    wss.handleUpgrade(request, socket, head, (webSocket) => wss.emit("connection", webSocket, request));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const clients: WebSocket[] = [];
  const negotiated: Array<string | undefined> = [];
  return {
    negotiated,
    connect: (clientOptions = {}) => new Promise((resolve, reject) => {
      const client = new WebSocket(`ws://127.0.0.1:${port}/`, clientOptions);
      clients.push(client);
      client.once("upgrade", (response) => {
        const header = response.headers["sec-websocket-extensions"];
        negotiated.push(Array.isArray(header) ? header.join(", ") : header);
      });
      client.once("open", () => resolve(client));
      client.once("error", reject);
    }),
    close: async () => {
      clients.forEach((client) => client.terminate());
      for (const socket of wss.clients) socket.terminate();
      await new Promise<void>((resolve) => wss.close(() => resolve()));
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}
