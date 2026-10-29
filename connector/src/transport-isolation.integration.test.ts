import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, generateKeyPairSync, randomUUID, verify } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, realpath, rm, writeFile, readFile, readdir } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import test from "node:test";
import { WebSocketServer, WebSocket } from "ws";
import { ARTIFACT_RETURN_PATH, BusinessWire, TRANSPORT_HEADER, parseTransportOffer, parseWireMessage, type ConnectorChallengeMessage, type WireMessage } from "@hermes-remote/protocol";
import { canonicalConnectorChallenge } from "./account-connector.js";

const soakMs = Math.max(0, Math.min(3_600_000, Number(process.env.HG244_SOAK_MS) || 0));
const heartbeatFault = process.env.HG244_DROP_CONTROL_PONG === "1";
for (const negotiate of [false, true]) test(`HG-244 real Connector ${negotiate ? "isolates control failure during bulk transfer" : "falls back without a second socket on an old Gateway"}`,
  { timeout: Math.max(heartbeatFault ? 70_000 : 20_000, soakMs + 20_000) }, async (t) => {
    const root = await realpath(await mkdtemp(join(tmpdir(), "hg244-")));
    t.after(() => rm(root, { recursive: true, force: true }));
    const keys = generateKeyPairSync("ed25519");
    const fingerprint = createHash("sha256").update(keys.publicKey.export({ format: "der", type: "spki" }).subarray(-32)).digest("hex");
    const bindingId = randomUUID(); const credential = join(root, "credential.json");
    await writeFile(credential, JSON.stringify({ schemaVersion: 1, bindingId, generation: 1, publicKeyFingerprint: fingerprint,
      privateKey: keys.privateKey.export({ format: "der", type: "pkcs8" }).subarray(-32).toString("base64url") }), { mode: 0o600 });
    const content = Buffer.alloc(2 * 1024 * 1024, 73); const file = join(root, "fixture.bin");
    await writeFile(file, content);
    const digest = createHash("sha256").update(content).digest("hex");
    type Reply = { bytes: number; hash: string; body?: Buffer };
    const returns = new Map<string, { resolve: (value: Reply) => void; reject: (error: unknown) => void }>();
    const uploadAcks = new Map<string, () => void>();
    const server = createServer(async (request, response) => {
      if (request.url?.startsWith(ARTIFACT_RETURN_PATH)) {
        const entry = returns.get(request.url.slice(ARTIFACT_RETURN_PATH.length));
        if (!entry) { response.writeHead(404).end(); return; }
        try {
          assert.equal(request.headers.authorization, `Bearer ${"c".repeat(64)}`);
          assert.equal(request.headers["x-hermes-file-status"], "206");
          let bytes = 0; const hash = createHash("sha256");
          for await (const chunk of request) { bytes += chunk.length; hash.update(chunk); }
          response.writeHead(204).end(); entry.resolve({ bytes, hash: hash.digest("hex") });
        } catch (error) { entry.reject(error); response.destroy(); }
        return;
      }
      request.resume();
      const body = request.url === "/api/health" ? { ok: true, version: "0.21.3" }
        : request.url === "/api/profiles/active" ? { active: "default" } : { ok: true };
      response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(body));
    });
    const wss = new WebSocketServer({ server, autoPong: false });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address(); assert.ok(address && typeof address !== "string");
    const origin = `http://127.0.0.1:${address.port}`;
    let business: WebSocket | undefined, control: WebSocket | undefined, connections = 0, businessConnections = 0;
    let droppedControl: WebSocket | undefined;
    let healthSequences: number[] = [];
    type Pending = { hash: ReturnType<typeof createHash>; bytes: number; body?: Buffer; resolve: (value: Reply) => void; timer: NodeJS.Timeout };
    const pending = new Map<string, Pending>();
    wss.on("connection", (socket, request) => {
      connections++;
      const offer = negotiate ? parseTransportOffer(request.headers[TRANSPORT_HEADER]) : undefined;
      if (heartbeatFault && offer?.role === "control" && !droppedControl) droppedControl = socket;
      socket.on("ping", (data) => { if (socket !== droppedControl) socket.pong(data); });
      const wire = offer?.role === "business" ? new BusinessWire((raw) => socket.send(raw), () => socket.bufferedAmount,
        () => socket.terminate()) : undefined;
      socket.on("close", () => wire?.stop());
      const challenge: ConnectorChallengeMessage = { type: "connector.challenge", version: 2, bindingId, generation: 1,
        publicKeyFingerprint: fingerprint, challenge: Buffer.alloc(32, 1).toString("base64url"),
        connectionNonce: Buffer.from(randomUUID().replaceAll("-", "").slice(0, 24)).toString("base64url"),
        serverTime: new Date().toISOString(), expiresAt: new Date(Date.now() + 5_000).toISOString(), ...(offer ? { transport: offer } : {}) };
      socket.on("message", (data) => {
        const raw = wire ? wire.receive(data.toString()) : data.toString();
        if (raw === undefined) return;
        const message = parseWireMessage(raw);
        if (offer?.role === "control") assert.ok(message.type.startsWith("connector."), "no business bytes on control socket");
        if (message.type === "connector.identify") socket.send(JSON.stringify(challenge));
        if (message.type === "connector.authenticate") {
          assert.equal(verify(null, canonicalConnectorChallenge(origin, challenge), keys.publicKey, Buffer.from(message.signature, "base64url")), true);
          socket.send(JSON.stringify({ type: "connector.preflight.request", version: 2, requestId: randomUUID(), sentAt: new Date().toISOString() }));
        }
        if (message.type === "connector.preflight.result") {
          if (negotiate) { assert.ok(message.health); healthSequences.push(message.health.sequence); healthSequences = healthSequences.slice(-100); }
          socket.send(JSON.stringify({ type: "connector.ready", version: 2, bindingId, generation: 1, deviceId: "test-mac",
            bindingStatus: "active", routingEnabled: true, ...(offer ? { transport: offer } : {}) }));
          if (offer?.role === "control") control = socket; else { business = socket; businessConnections++; }
        }
        if (!("requestId" in message) || typeof message.requestId !== "string") return;
        if (message.type === "tunnel.http.request.ack") uploadAcks.get(`${message.requestId}:${message.sequence}`)?.();
        const entry = pending.get(message.requestId); if (!entry) return;
        if (message.type === "tunnel.http.response.chunk") {
          const bytes = Buffer.from(message.dataBase64, "base64"); entry.hash.update(bytes); entry.bytes += bytes.length;
          socket.send(JSON.stringify({ type: "tunnel.http.response.ack", version: 1, requestId: message.requestId, sequence: message.sequence }));
        }
        if (message.type === "tunnel.http.response.end" || message.type === "tunnel.http.response") {
          assert.equal("error" in message && !!message.error, false);
          if (message.type === "tunnel.http.response") {
            const bytes = Buffer.from(message.bodyBase64 ?? "", "base64"); entry.hash.update(bytes); entry.bytes += bytes.length;
            entry.body = bytes;
          }
          clearTimeout(entry.timer); pending.delete(message.requestId); entry.resolve({ bytes: entry.bytes, hash: entry.hash.digest("hex"), body: entry.body });
        }
      });
    });
    const child = spawn(process.execPath, [fileURLToPath(new URL("./index.js", import.meta.url))], {
      env: { PATH: process.env.PATH, HOME: root, CONNECTOR_MODE: "account", HERMES_MODE: "real", SESSION_OBSERVER_ENABLED: "0",
        CONNECTOR_TRANSPORT_ISOLATION: "1", GATEWAY_URL: origin.replace("http:", "ws:") + "/v2/connect", FILES_ROOT: root,
        ACCOUNT_CONNECTOR_CREDENTIAL_FILE: credential, HERMES_BASE_URL: origin, HERMES_SESSION_TOKEN: "fixture-session-token",
        FILE_DOWNLOAD_BYTES_PER_SECOND: String(1024 * 1024) }, stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    for (const stream of [child.stdout, child.stderr]) stream.on("data", (data) => { output = (output + data.toString()).slice(-32_000); });
    t.after(async () => {
      for (const entry of pending.values()) clearTimeout(entry.timer);
      if (child.exitCode === null) { const exited = once(child, "exit"); child.kill("SIGTERM");
        const force = setTimeout(() => child.kill("SIGKILL"), 2_000); await exited; clearTimeout(force); }
      for (const client of wss.clients) client.terminate(); server.closeAllConnections();
      await new Promise<void>((resolve) => wss.close(() => resolve()));
      await new Promise<void>((resolve) => server.close(() => resolve()));
    });
    for (let i = 0; i < 100 && (!business || (negotiate && !control)); i++) await delay(50);
    assert.ok(business, output); if (negotiate) assert.ok(control, output);
    const original = business;
    const awaitReply = (id: string) => new Promise<Reply>((resolve, reject) => {
      pending.set(id, { hash: createHash("sha256"), bytes: 0, resolve,
        timer: setTimeout(() => { pending.delete(id); reject(new Error("request timeout: " + output)); }, 10_000) });
    });
    const request = (path: string, method = "GET", bodyBase64?: string) => {
      const id = randomUUID(); const reply = awaitReply(id);
      business!.send(JSON.stringify({ type: "tunnel.http.request", version: 1, id, targetDeviceId: "test-mac", method, path, headers: {}, bodyBase64 } satisfies WireMessage));
      return reply;
    };
    const bulk = request(`/api/files?path=${encodeURIComponent(file)}`);
    await delay(50); if (negotiate && !heartbeatFault) control!.terminate();
    const samples: number[] = [];
    const measure = async () => { const start = performance.now(); await request("/api/fixture"); samples.push(performance.now() - start); };
    await measure(); const result = await bulk;
    assert.equal(result.bytes, content.length); assert.equal(result.hash, digest);
    if (negotiate && !heartbeatFault) {
      const descriptor = JSON.parse((await request("/api/files/downloads", "POST", Buffer.from(JSON.stringify({ path: file })).toString("base64"))).body!.toString());
      const returnedId = randomUUID();
      const fileReturned = new Promise<Reply>((resolve, reject) => returns.set(returnedId, { resolve, reject }));
      business!.send(JSON.stringify({ type: "tunnel.http.request", version: 1, id: returnedId, targetDeviceId: "test-mac", method: "GET",
        path: `/api/files/downloads/${descriptor.artifactId}`, headers: { "x-hermes-file-return": returnedId, "x-hermes-file-ticket": "c".repeat(64),
          "if-match": descriptor.etag, range: "bytes=0-1048575" } } satisfies WireMessage));
      await delay(50); control!.terminate();
      const uploadId = randomUUID(); const uploaded = awaitReply(uploadId);
      const sendUpload = (message: WireMessage, sequence: number) => new Promise<void>((resolve, reject) => {
        const key = `${uploadId}:${sequence}`;
        const timer = setTimeout(() => { uploadAcks.delete(key); reject(new Error("upload ack timeout")); }, 10_000);
        uploadAcks.set(key, () => { clearTimeout(timer); uploadAcks.delete(key); resolve(); });
        business!.send(JSON.stringify(message));
      });
      await sendUpload({ type: "tunnel.http.request.start", version: 1, id: uploadId, targetDeviceId: "test-mac", method: "POST", path: "/api/files/upload?name=fixture.bin", headers: {} }, -1);
      const uploadContent = content.subarray(0, 512 * 1024);
      for (let sequence = 0; sequence < 2; sequence++) {
        await sendUpload({ type: "tunnel.http.request.chunk", version: 1, requestId: uploadId, sequence,
          dataBase64: uploadContent.subarray(sequence * 256 * 1024, (sequence + 1) * 256 * 1024).toString("base64") }, sequence);
        await measure();
      }
      business!.send(JSON.stringify({ type: "tunnel.http.request.end", version: 1, requestId: uploadId } satisfies WireMessage));
      await uploaded;
      const returned = await fileReturned; returns.delete(returnedId);
      assert.equal(returned.bytes, 1024 * 1024);
      assert.equal(returned.hash, createHash("sha256").update(content.subarray(0, 1024 * 1024)).digest("hex"));
      const uploadRoot = join(root, ".hermes-remote", "uploads");
      const uploads = await readdir(uploadRoot); assert.equal(uploads.length, 1);
      assert.deepEqual(await readFile(join(uploadRoot, uploads[0]!)), uploadContent);
    }
    if (negotiate && heartbeatFault) {
      const degradedUntil = performance.now() + 20_000;
      while (performance.now() < degradedUntil) { await measure(); await delay(250); }
      assert.equal(droppedControl!.readyState, WebSocket.OPEN, "a missed 15s heartbeat must not terminate the lane");
      const deadline = performance.now() + 35_000;
      while (control === droppedControl && performance.now() < deadline) { await measure(); await delay(250); }
      assert.notEqual(control, droppedControl, "a one-way control link must be rebuilt after the bounded 45s deadline");
      assert.equal(business, original);
    }
    const until = performance.now() + (negotiate ? soakMs : 0);
    while (performance.now() < until) {
      const transfer = request(`/api/files?path=${encodeURIComponent(file)}`);
      for (let i = 0; i < 8; i++) { await measure(); await delay(100); }
      assert.equal((await transfer).hash, digest);
    }
    assert.equal(business, original); assert.equal(businessConnections, 1);
    if (!negotiate) assert.equal(connections, 1);
    samples.sort((a, b) => a - b);
    const p95 = samples[Math.floor((samples.length - 1) * 0.95)]!;
    assert.ok(p95 <= 500, `loopback transport response P95 ${p95}ms`);
    assert.equal(output.includes("fixture-session-token"), false);
    t.diagnostic(JSON.stringify({ negotiate, soakMs: negotiate ? soakMs : 0, samples: samples.length, p95Ms: p95, businessConnections, connections, bytes: result.bytes }));
  });
