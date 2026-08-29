import { WebSocket } from "ws";

const url = process.env.GATEWAY_URL ?? "ws://127.0.0.1:8787/v1/connect";
const token = process.env.APP_TOKEN ?? "dev-app-token";
const socket = new WebSocket(url);
const timeout = setTimeout(() => fail("Timed out waiting for a complete event"), 5_000);

socket.on("open", () => {
  socket.send(JSON.stringify({
    type: "hello",
    version: 1,
    role: "app",
    deviceId: "android-smoke-test",
    token,
  }));
});

socket.on("message", (raw) => {
  const message = JSON.parse(raw.toString());
  console.log(JSON.stringify(message));
  if (message.type === "hello_ack") {
    socket.send(JSON.stringify({
      type: "command",
      version: 1,
      id: "smoke-request-1",
      targetDeviceId: "mac-mini",
      payload: { kind: "chat", input: "hello" },
    }));
  }
  if (message.type === "event" && message.event === "complete") {
    clearTimeout(timeout);
    socket.close();
  }
});

socket.on("error", (error) => fail(error.message));

function fail(message) {
  console.error(message);
  socket.close();
  process.exitCode = 1;
}
