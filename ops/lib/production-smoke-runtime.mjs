import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { spawn as nodeSpawn } from "node:child_process";
import { createServer } from "node:http";
import { chmod, mkdir, mkdtemp, realpath, rm } from "node:fs/promises";
import { rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { WebSocketServer } from "ws";
import { OpsError } from "./errors.mjs";

const MAXIMUM_REQUEST_BYTES = 16 * 1024;

export async function withProductionSmokeRuntime(callback, options = {}) {
  const runtime = await startProductionSmokeRuntime(options);
  let result;
  let failure;
  try {
    result = await callback(runtime);
  } catch (error) {
    failure = error;
  }
  try {
    await runtime.close();
  } catch (error) {
    if (!failure) failure = error;
  }
  if (failure) throw failure;
  return result;
}

export async function startProductionSmokeRuntime({
  connectorEntry,
  baseEnvironment = process.env,
} = {}) {
  let root;
  let server;
  let sockets;
  let exitCleanup;
  const children = new Set();
  try {
    if (!connectorEntry || !path.isAbsolute(connectorEntry) || path.normalize(connectorEntry) !== connectorEntry) {
      fail("production_smoke_connector_entry_invalid");
    }
    root = await realpath(await mkdtemp(path.join(tmpdir(), "hermes-r5d-smoke-")));
    await chmod(root, 0o700);
    const filesRoot = path.join(root, "files");
    const uploadRoot = path.join(filesRoot, "uploads");
    await mkdir(uploadRoot, { recursive: true, mode: 0o700 });

    const username = randomBytes(32).toString("base64url");
    const password = randomBytes(32).toString("base64url");
    const sessionCookie = randomBytes(32).toString("base64url");
    const tickets = new Set();
    sockets = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });
    server = createServer((request, response) => {
      void handleRequest(request, response, { username, password, sessionCookie, tickets });
    });
    server.on("upgrade", (request, socket, head) => {
      const url = new URL(request.url ?? "/", "http://127.0.0.1");
      const ticket = url.searchParams.get("ticket");
      if (url.pathname !== "/api/ws" || !ticket || !tickets.delete(ticket)) {
        socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
        socket.destroy();
        return;
      }
      sockets.handleUpgrade(request, socket, head, (webSocket) => sockets.emit("connection", webSocket));
    });
    sockets.on("connection", (socket) => {
      socket.send(JSON.stringify({
        jsonrpc: "2.0",
        method: "event",
        params: { type: "gateway.ready", payload: {} },
      }));
      socket.on("message", (raw) => {
        try {
          const request = JSON.parse(raw.toString());
          socket.send(JSON.stringify({
            jsonrpc: "2.0",
            id: request.id,
            result: request.method === "session.active_list"
              ? { sessions: [] }
              : { ok: true, method: request.method },
          }));
        } catch {
          socket.close(1003, "invalid request");
        }
      });
    });

    await listen(server);
    const address = server.address();
    if (!address || typeof address === "string" || address.address !== "127.0.0.1" || address.port < 1) {
      fail("production_smoke_listener_not_loopback");
    }
    const origin = `http://127.0.0.1:${address.port}`;
    const trackedSpawn = (...args) => {
      const child = nodeSpawn(...args);
      children.add(child);
      child.once("exit", () => children.delete(child));
      return child;
    };
    exitCleanup = () => {
      terminateChildren(children);
      rmSync(root, { recursive: true, force: true });
    };
    process.once("exit", exitCleanup);
    let closed = false;

    return Object.freeze({
      origin,
      root,
      spawn: trackedSpawn,
      environment: Object.freeze({
        ...safeBaseEnvironment(baseEnvironment),
        HERMES_SMOKE_CONNECTOR_ENTRY: connectorEntry,
        HERMES_MODE: "live",
        HERMES_BASE_URL: origin,
        HERMES_BASIC_AUTH_USERNAME: username,
        HERMES_BASIC_AUTH_PASSWORD: password,
        FILES_ROOT: filesRoot,
        UPLOAD_ROOT: uploadRoot,
        SESSION_OBSERVER_ENABLED: "0",
      }),
      close: async () => {
        if (closed) return;
        closed = true;
        process.removeListener("exit", exitCleanup);
        await closeRuntime(server, sockets, root, children);
      },
    });
  } catch (error) {
    if (exitCleanup) process.removeListener("exit", exitCleanup);
    await closeRuntime(server, sockets, root, children).catch(() => {});
    if (error instanceof OpsError) throw error;
    fail(error instanceof Error ? error.message : error);
  }
}

async function handleRequest(request, response, credentials) {
  try {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (request.method === "POST" && url.pathname === "/auth/password-login") {
      const payload = JSON.parse((await readBoundedBody(request)).toString("utf8") || "{}");
      if (payload.provider !== "basic"
          || !equalSecret(payload.username, credentials.username)
          || !equalSecret(payload.password, credentials.password)) {
        response.writeHead(401).end();
        return;
      }
      response.writeHead(200, {
        "content-type": "application/json",
        "set-cookie": `hermes_session_at=${credentials.sessionCookie}; HttpOnly; SameSite=Strict; Path=/`,
      });
      response.end(JSON.stringify({ ok: true }));
      return;
    }

    if (!hasCookie(request.headers.cookie, "hermes_session_at", credentials.sessionCookie)) {
      writeJson(response, 401, { error: "unauthorized" });
      return;
    }
    if (request.method === "POST" && url.pathname === "/api/auth/ws-ticket") {
      const ticket = randomUUID();
      credentials.tickets.add(ticket);
      writeJson(response, 200, { ticket });
      return;
    }
    if (request.method === "GET" && url.pathname === "/api/status") {
      writeJson(response, 200, { status: "ok", version: "mock-hermes" });
      return;
    }
    writeJson(response, 404, { error: "not_found" });
  } catch {
    writeJson(response, 400, { error: "invalid_request" });
  }
}

function listen(server) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.removeListener("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.removeListener("error", onError);
      resolve();
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen(0, "127.0.0.1");
  });
}

async function closeRuntime(server, sockets, root, children = new Set()) {
  let failure;
  try {
    terminateChildren(children);
    if (sockets) {
      for (const socket of sockets.clients) socket.terminate();
      await new Promise((resolve) => sockets.close(() => resolve()));
    }
    if (server?.listening) {
      server.closeAllConnections?.();
      await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    }
  } catch (error) {
    failure = error;
  }
  if (root) await rm(root, { recursive: true, force: true });
  if (failure) fail(failure instanceof Error ? failure.message : failure);
}

function terminateChildren(children) {
  for (const child of children) {
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }
  children.clear();
}

function safeBaseEnvironment(environment) {
  const safe = {};
  for (const name of ["PATH", "LANG", "LC_ALL", "TZ", "NODE_EXTRA_CA_CERTS"]) {
    if (typeof environment[name] === "string" && environment[name]) safe[name] = environment[name];
  }
  return safe;
}

function readBoundedBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    request.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > MAXIMUM_REQUEST_BYTES) {
        reject(new Error("request_too_large"));
        request.destroy();
      } else {
        chunks.push(Buffer.from(chunk));
      }
    });
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

function equalSecret(actual, expected) {
  if (typeof actual !== "string") return false;
  const actualBytes = Buffer.from(actual);
  const expectedBytes = Buffer.from(expected);
  return actualBytes.length === expectedBytes.length && timingSafeEqual(actualBytes, expectedBytes);
}

function hasCookie(header, name, expected) {
  if (typeof header !== "string") return false;
  return header.split(";").some((entry) => {
    const [key, ...value] = entry.trim().split("=");
    return key === name && equalSecret(value.join("="), expected);
  });
}

function writeJson(response, status, value) {
  if (response.headersSent) return;
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify(value));
}

function fail(cause) {
  throw new OpsError("managedBaseline", cause, "managed_baseline_smoke_runtime");
}
