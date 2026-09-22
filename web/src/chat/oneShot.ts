import { paths, type GatewayClient } from "../api/gateway";
import { HermesSocket } from "../hermes/client";
import { sessionWorkspaceMove } from "../hermes/params";
import type { JsonObject } from "../hermes/types";

// One RPC from a page that holds no conversation socket (the session list's "move to project"):
// open a device WebSocket, make the call, close it. Android does the same through its shared
// ChatRepository connection.

export async function withSocket<T>(client: GatewayClient, deviceId: string, fn: (socket: HermesSocket) => Promise<T>): Promise<T> {
  // Never upgrade while the access cookie is being rotated: the upgrade would carry the old one.
  await client.settled();
  const socket = new HermesSocket({ url: paths.deviceWs(deviceId) });
  socket.connect();
  try {
    return await fn(socket);
  } finally {
    socket.close();
  }
}

export function moveSessionTo(client: GatewayClient, deviceId: string, storedSessionId: string, cwd: string, profile?: string | null): Promise<JsonObject> {
  return withSocket(client, deviceId, (socket) => {
    const { method, params } = sessionWorkspaceMove(storedSessionId, cwd, profile);
    return socket.call<JsonObject>(method, params, { timeoutMs: 30_000 });
  });
}
