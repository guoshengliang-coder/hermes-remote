import { WebSocket } from "ws";

const baseUrl = (process.env.PUBLIC_GATEWAY_URL ?? "http://127.0.0.1:8788").replace(/\/$/, "");
const appToken = process.env.APP_TOKEN ?? "compat-app-token";

const unauthorizedResponse = await fetch(`${baseUrl}/api/status`, {
  headers: { "x-hermes-session-token": "wrong-test-token" },
});
if (unauthorizedResponse.status !== 401) {
  throw new Error(`Expected invalid relay token to return 401, got ${unauthorizedResponse.status}`);
}
console.log(JSON.stringify({ unauthorized: true }));

const statusResponse = await fetch(`${baseUrl}/api/status`, {
  headers: { "x-hermes-session-token": appToken },
});
if (!statusResponse.ok) throw new Error(`REST relay failed with HTTP ${statusResponse.status}`);
const status = await statusResponse.json();
console.log(JSON.stringify({ rest: status }));

const wsUrl = `${baseUrl.replace(/^https:/, "wss:").replace(/^http:/, "ws:")}/api/ws`;
const socket = new WebSocket(wsUrl, { headers: { "x-hermes-session-token": appToken } });
const timeout = setTimeout(() => fail("Timed out waiting for JSON-RPC response"), 10_000);
let ready = false;

socket.on("message", (raw) => {
  const message = JSON.parse(raw.toString());
  console.log(JSON.stringify({ ws: message }));
  if (message.method === "event" && message.params?.type === "gateway.ready" && !ready) {
    ready = true;
    socket.send(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "session.create", params: {} }));
  } else if (message.id === 1 && message.result && !message.error) {
    clearTimeout(timeout);
    socket.close();
  }
});

socket.on("error", (error) => fail(error.message));

function fail(message) {
  clearTimeout(timeout);
  console.error(message);
  socket.close();
  process.exitCode = 1;
}
