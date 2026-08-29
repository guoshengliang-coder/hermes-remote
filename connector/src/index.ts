import { WebSocket } from "ws";
import {
  PROTOCOL_VERSION,
  encodeWireMessage,
  parseWireMessage,
  type ChatCommand,
  type RelayEvent,
} from "@hermes-remote/protocol";

const gatewayUrl = process.env.GATEWAY_URL ?? "ws://127.0.0.1:8787/v1/connect";
const connectorToken = requireSecret("CONNECTOR_TOKEN");
const deviceId = process.env.DEVICE_ID ?? "mac-mini";
const hermesMode = process.env.HERMES_MODE ?? "mock";
const hermesChatUrl = process.env.HERMES_CHAT_URL ?? "http://127.0.0.1:9119/api/chat";

let retryMs = 1_000;

connect();

function connect(): void {
  const socket = new WebSocket(gatewayUrl);

  socket.on("open", () => {
    retryMs = 1_000;
    socket.send(encodeWireMessage({
      type: "hello",
      version: PROTOCOL_VERSION,
      role: "connector",
      deviceId,
      token: connectorToken,
    }));
  });

  socket.on("message", async (raw) => {
    try {
      const message = parseWireMessage(raw.toString());
      if (message.type === "hello_ack") {
        console.log(`Connected to gateway as ${message.deviceId}`);
      } else if (message.type === "command") {
        await handleCommand(socket, message);
      }
    } catch (error) {
      console.error("Unable to handle gateway message", error);
    }
  });

  socket.on("close", () => scheduleReconnect());
  socket.on("error", (error) => console.error("Gateway connection error", error.message));
}

async function handleCommand(socket: WebSocket, command: ChatCommand): Promise<void> {
  emit(socket, command.id, "accepted");
  try {
    if (hermesMode === "mock") {
      emit(socket, command.id, "delta", { text: `Echo from ${deviceId}: ` });
      emit(socket, command.id, "delta", { text: command.payload.input });
      emit(socket, command.id, "complete", { sessionId: command.sessionId ?? command.id });
      return;
    }

    const response = await fetch(hermesChatUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        message: command.payload.input,
        session_id: command.sessionId,
      }),
    });

    if (!response.ok) throw new Error(`Hermes returned HTTP ${response.status}`);
    const contentType = response.headers.get("content-type") ?? "";
    if (contentType.includes("text/event-stream") && response.body) {
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        emit(socket, command.id, "delta", { text: decoder.decode(value, { stream: true }) });
      }
      emit(socket, command.id, "complete");
      return;
    }

    const body = await response.text();
    emit(socket, command.id, "complete", { contentType, body });
  } catch (error) {
    emit(socket, command.id, "error", { message: String(error) });
  }
}

function emit(
  socket: WebSocket,
  requestId: string,
  event: RelayEvent["event"],
  data?: unknown,
): void {
  socket.send(encodeWireMessage({
    type: "event",
    version: PROTOCOL_VERSION,
    requestId,
    event,
    data,
  }));
}

function scheduleReconnect(): void {
  const delay = retryMs + Math.floor(Math.random() * 500);
  console.log(`Disconnected; reconnecting in ${delay}ms`);
  setTimeout(connect, delay);
  retryMs = Math.min(retryMs * 2, 30_000);
}

function requireSecret(name: string): string {
  const value = process.env[name];
  if (!value || value.length < 8) throw new Error(`${name} must contain at least 8 characters`);
  return value;
}
