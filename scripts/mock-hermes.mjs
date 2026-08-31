import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { WebSocketServer } from "ws";

const port = Number(process.env.MOCK_HERMES_PORT ?? 9120);
const expectedUsername = process.env.MOCK_HERMES_USERNAME ?? "demo";
const expectedPassword = process.env.MOCK_HERMES_PASSWORD ?? "secret";
const sessionCookie = "mock-session";
const tickets = new Set();

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  if (request.method === "POST" && url.pathname === "/auth/password-login") {
    const payload = JSON.parse((await readBody(request)).toString("utf8") || "{}");
    if (payload.provider !== "basic" || payload.username !== expectedUsername || payload.password !== expectedPassword) {
      response.writeHead(401).end();
      return;
    }
    response.writeHead(200, {
      "content-type": "application/json",
      "set-cookie": `hermes_session_at=${sessionCookie}; HttpOnly; Path=/`,
    });
    response.end(JSON.stringify({ ok: true }));
    return;
  }

  if (!request.headers.cookie?.includes(`hermes_session_at=${sessionCookie}`)) {
    response.writeHead(401, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: "unauthorized" }));
    return;
  }

  if (request.method === "POST" && url.pathname === "/api/auth/ws-ticket") {
    const ticket = randomUUID();
    tickets.add(ticket);
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ ticket }));
    return;
  }

  if (request.method === "GET" && url.pathname === "/api/status") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ status: "ok", version: "mock-hermes" }));
    return;
  }

  response.writeHead(404, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: "not_found" }));
});

const wss = new WebSocketServer({ noServer: true });
server.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
  const ticket = url.searchParams.get("ticket");
  if (url.pathname !== "/api/ws" || !ticket || !tickets.delete(ticket)) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(request, socket, head, (webSocket) => wss.emit("connection", webSocket));
});

wss.on("connection", (socket) => {
  socket.send(JSON.stringify({
    jsonrpc: "2.0",
    method: "event",
    params: { type: "gateway.ready", payload: {} },
  }));
  socket.on("message", (raw) => {
    const request = JSON.parse(raw.toString());
    socket.send(JSON.stringify({
      jsonrpc: "2.0",
      id: request.id,
      result: request.method === "session.active_list"
        ? { sessions: [] }
        : { ok: true, method: request.method },
    }));
  });
});

server.listen(port, "127.0.0.1", () => console.log(`Mock Hermes listening on 127.0.0.1:${port}`));

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}
