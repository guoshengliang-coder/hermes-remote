import { appError } from "../errors";
import { paths, type GatewayClient } from "../api/gateway";
import { HermesSocket, HermesSocketError } from "../hermes/client";
import { archiveFailure, archiveWithBackend } from "../hermes/archive";
import type { ProfileSessionsResponse } from "../hermes/types";
import { sessionArchive, sessionWorkspaceMove } from "../hermes/params";
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

// Only one explicit write per target at a time; never queue a stale inverse intent.
const archiving = new Set<string>();
export async function archiveSession(client: GatewayClient, deviceId: string, storedSessionId: string,
  archived: boolean, profile?: string | null, revision?: number, rpcAvailable = true, signal?: AbortSignal): Promise<void> {
  const key = JSON.stringify([deviceId, profile ?? "default", storedSessionId]);
  if (archiving.has(key)) throw appError("HR-SESS-026", "archive already in progress");
  archiving.add(key);
  const controller = new AbortController();
  let cancelled = false;
  let activeSocket: HermesSocket | null = null;
  const cancel = () => { cancelled = true; controller.abort(); activeSocket?.close(); };
  const unsubscribe = client.onSignedOut(cancel);
  signal?.addEventListener("abort", cancel, { once: true });
  const current = () => {
    if (cancelled || signal?.aborted) throw new HermesSocketError("not-connected", "archive owner or route changed");
  };
  const read = async (): Promise<boolean | null> => {
    for (let offset = 0; offset < 100_000; offset += 500) {
      current();
      const page = await client.deviceApi<ProfileSessionsResponse>(deviceId, "GET",
        `profiles/sessions?limit=500&offset=${offset}&archived=include&profile=${encodeURIComponent(profile || "default")}`, { signal: controller.signal });
      current();
      if (page.errors?.length) return null;
      const row = page.sessions.find((s) => s.id === storedSessionId && (s.profile || "default") === (profile || "default"));
      if (row) return typeof row.archived === "boolean" ? row.archived : null;
      if (page.sessions.length < 500 || page.total !== undefined && offset + 500 >= page.total) return null;
    }
    return null;
  };
  const rest = () => { current(); return client.updateSession(deviceId, storedSessionId, { archived }, profile, revision, controller.signal); };
  try {
    current();
    if (!rpcAvailable) {
      // Older Gateway: retain its existing REST contract; do not try a withheld browser RPC.
      try { await rest(); current(); return; }
      catch (error) {
        const failure = archiveFailure(error);
        if (failure.code === "HR-SESS-025") {
          try { if (await read() === archived) return; } catch { /* preserve unknown */ }
        }
        throw failure;
      }
    }
    await withSocket(client, deviceId, async (socket) => {
      activeSocket = socket;
      current();
      await archiveWithBackend({
        mode: await socket.archiveBackend(), sessionId: storedSessionId, archived,
        rpc: () => {
          current();
          const { method, params } = sessionArchive(storedSessionId, archived, profile);
          return socket.call<JsonObject>(method, params);
        },
        rest,
        read,
      });
      current();
    });
  } finally { unsubscribe(); signal?.removeEventListener("abort", cancel); archiving.delete(key); }
}
