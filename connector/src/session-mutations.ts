import { WebSocket } from "ws";
import { boundedResponseBody, type HermesAuth } from "./hermes-auth.js";

const ID = /^[A-Za-z0-9_.:-]{1,128}$/;
const PROFILE = /^[\p{L}\p{N}_. -]{1,64}$/u;
const pending = new Set<string>();
const counter = (value: unknown): boolean => Number.isSafeInteger(value) && Number(value) >= 0;
const requestId = (value: unknown): boolean => typeof value === "string" && value.trim().length > 0 &&
  value.length <= 128 && !/[\u0000-\u001f\u007f]/.test(value);
const queryCounter = (value: string): boolean => /^(0|[1-9][0-9]*)$/.test(value) && counter(Number(value));
class MutationError extends Error {
  constructor(readonly refused = false) { super("session mutation did not complete"); }
}
const failure = (refused: boolean) => new Response(JSON.stringify({ error: {
  code: refused ? "HR-SESS-028" : "HR-SESS-027", retryable: false,
  message: refused ? "会话操作被拒绝，请刷新状态。 / Session operation refused. Refresh state."
    : "操作结果尚未确认，请刷新状态。 / Operation outcome unconfirmed. Refresh state.",
} }), { status: refused ? 409 : 502, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const quote = (value: string) => `'${value.replaceAll("'", `'"'"'`)}'`;

/** One authenticated local connection. Never log tickets, commands, titles, or response bodies. */
export async function localSessionSocket<T>(auth: Pick<HermesAuth, "websocketUrl" | "consoleWebsocketUrl" | "request">, path: "/api/ws" | "/api/console",
  profile: string, signal: AbortSignal, run: (send: (value: object) => void, receive: () => Promise<Record<string, unknown>>, connectionSignal: AbortSignal) => Promise<T>): Promise<T> {
  const url = new URL(await (path === "/api/console" ? auth.consoleWebsocketUrl(signal) : auth.websocketUrl(path, signal)));
  signal.throwIfAborted();
  if (path === "/api/console") url.searchParams.set("profile", profile);
  const socket = new WebSocket(url, { handshakeTimeout: 10_000, maxPayload: 1024 * 1024 });
  const queue: Record<string, unknown>[] = [];
  let waiter: { resolve(value: Record<string, unknown>): void; reject(error: unknown): void } | undefined;
  let stopped = false;
  const lifecycle = new AbortController();
  const connectionSignal = AbortSignal.any([signal, lifecycle.signal]);
  const stop = () => {
    if (stopped) return;
    stopped = true; lifecycle.abort();
    waiter?.reject(new MutationError()); waiter = undefined; socket.terminate();
  };
  const timer = setTimeout(stop, 30_000);
  signal.addEventListener("abort", stop, { once: true });
  socket.on("error", stop); socket.on("close", stop);
  socket.on("message", (data, binary) => {
    try {
      if (binary) throw new MutationError();
      for (const line of data.toString().split("\n").filter(Boolean)) {
        const frame: unknown = JSON.parse(line);
        if (!frame || typeof frame !== "object" || Array.isArray(frame)) throw new MutationError();
        if (waiter) { const current = waiter; waiter = undefined; current.resolve(frame as Record<string, unknown>); }
        else if (queue.length < 64) queue.push(frame as Record<string, unknown>);
        else throw new MutationError();
      }
    } catch { stop(); }
  });
  const receive = async () => {
    signal.throwIfAborted();
    if (stopped) throw new MutationError();
    return queue.shift() ?? new Promise<Record<string, unknown>>((resolve, reject) => { waiter = { resolve, reject }; });
  };
  try { return await run((value) => { connectionSignal.throwIfAborted(); if (stopped || socket.readyState !== WebSocket.OPEN) throw new MutationError(); socket.send(JSON.stringify(value)); }, receive, connectionSignal); }
  finally { lifecycle.abort(); clearTimeout(timer); signal.removeEventListener("abort", stop); socket.removeListener("error", stop); socket.on("error", () => {}); socket.close(); }
}

/** Only existing rename/delete HTTP contracts are adapted; never a generic RPC/console proxy. */
export async function sessionMutationResponse(method: string, path: string, body: Buffer | undefined,
  auth: Pick<HermesAuth, "websocketUrl" | "consoleWebsocketUrl" | "request">, signal: AbortSignal): Promise<Response | undefined> {
  if (!path.startsWith("/api/sessions/")) return undefined;
  const url = new URL(path, "http://connector.local");
  if (url.pathname !== path.split(/[?#]/, 1)[0] || url.hash) return undefined;
  const match = /^\/api\/sessions\/([A-Za-z0-9_.:-]{1,128})$/.exec(url.pathname);
  if (!match || !["DELETE", "PATCH"].includes(method)) return undefined;
  if ([...url.searchParams.keys()].some((key) => url.searchParams.getAll(key).length !== 1)) return failure(true);
  for (const key of ["expected_revision", "expected_generation"]) {
    if (url.searchParams.has(key) && !queryCounter(url.searchParams.get(key)!)) return failure(true);
  }
  if (url.searchParams.has("request_id") && !requestId(url.searchParams.get("request_id"))) return failure(true);
  let profile = url.searchParams.get("profile") || "default";
  let title: string | undefined;
  if (method === "PATCH") {
    if (url.searchParams.size) return undefined;
    let value: Record<string, unknown>;
    try { value = JSON.parse(body?.toString() || "null"); } catch { return undefined; }
    if (!value || typeof value !== "object" || Array.isArray(value) || typeof value.title !== "string" ||
      !Object.keys(value).every((key) => ["title", "profile", "request_id", "expected_revision", "expected_generation"].includes(key))) return undefined;
    if (value.profile !== undefined && typeof value.profile !== "string" ||
        value.request_id !== undefined && !requestId(value.request_id) ||
        value.expected_revision !== undefined && !counter(value.expected_revision) ||
        value.expected_generation !== undefined && !counter(value.expected_generation)) return failure(true);
    profile = (value.profile as string) || "default";
    title = value.title.trim();
    if (!title.trim() || title.length > 200 || /[\u0000-\u001f\u007f]/.test(title)) return failure(true);
  } else if (body?.length || [...url.searchParams.keys()].some((key) => !["profile", "request_id", "expected_revision", "expected_generation"].includes(key))) return undefined;
  const id = match[1]!;
  if (!ID.test(id) || !PROFILE.test(profile) || profile.trim() !== profile || [".", ".."].includes(profile)) return failure(true);
  const key = JSON.stringify([profile, id]);
  if (pending.has(key)) return failure(true);
  pending.add(key);
  try {
    return await localSessionSocket(auth, "/api/ws", profile, signal, async (send, receive, connectionSignal) => {
      let ready: Record<string, unknown> | undefined;
      for (let n = 0; n < 64; n++) {
        const frame = await receive();
        const params = frame.params as Record<string, unknown> | undefined;
        if (frame.method === "event" && params?.type === "gateway.ready") { ready = params.payload as Record<string, unknown>; break; }
      }
      if (!ready || typeof ready !== "object" || Array.isArray(ready)) throw new MutationError();
      if (ready.session_authority === true) {
        // New upstream also requires execution generation on delete. Fill only a missing
        // counter from an authenticated same-target snapshot; never replace a stale fence.
        if (method !== "DELETE" || url.searchParams.has("expected_generation") ||
            !url.searchParams.get("request_id") || !url.searchParams.has("expected_revision")) return undefined;
        const snapshotResponse = await auth.request(`/api/sessions/${id}/mutation-snapshot?profile=${encodeURIComponent(profile)}`, { method: "GET", signal: connectionSignal });
        if (!snapshotResponse.ok) throw new MutationError();
        const snapshot = JSON.parse(await boundedResponseBody(snapshotResponse, 16 * 1024)) as Record<string, unknown>;
        const generation = snapshot.runtime_generation;
        if (snapshot.session_id === id && snapshot.exists === false) return undefined;
        if (snapshot.session_id !== id || snapshot.exists !== true || !Number.isSafeInteger(generation) || Number(generation) < 0 ||
            snapshot.runtime_revision !== Number(url.searchParams.get("expected_revision"))) throw new MutationError(true);
        url.searchParams.set("expected_generation", String(generation));
        const result = await auth.request(url.pathname + url.search, { method: "DELETE", signal: connectionSignal });
        // This connection owns the cancellation scope. Drain before disposing it so the
        // caller does not inherit a Response whose body was just aborted by our cleanup.
        const payload = await boundedResponseBody(result, 1024 * 1024);
        return new Response(result.status === 204 ? null : payload, { status: result.status,
          headers: { "content-type": result.headers.get("content-type") || "application/json", "cache-control": "no-store" } });
      }
      if (ready.session_authority !== undefined && ready.session_authority !== false) throw new MutationError(true);
      if (title !== undefined) {
        // The curated command resolves aliases/prefixes. Prove the exact stored ID exists
        // before allowing it to resolve anything; a missing target is not a rename alias.
        const response = await auth.request(`/api/sessions/${id}/mutation-snapshot?profile=${encodeURIComponent(profile)}`, { method: "GET", signal: connectionSignal });
        if (!response.ok) throw new MutationError(response.status >= 400 && response.status < 500 && response.status !== 408);
        const snapshot = JSON.parse(await boundedResponseBody(response, 16 * 1024)) as Record<string, unknown>;
        if (snapshot.session_id !== id || snapshot.exists !== true) throw new MutationError(true);
        const expectedRevision = body ? (JSON.parse(body.toString()) as { expected_revision?: unknown }).expected_revision : undefined;
        if (expectedRevision !== undefined && (!Number.isSafeInteger(expectedRevision) || Number(expectedRevision) < 0 ||
            snapshot.runtime_revision !== expectedRevision)) throw new MutationError(true);
        const command = `sessions rename ${quote(id)} ${quote(title)}`;
        return localSessionSocket(auth, "/api/console", profile, connectionSignal, async (sendConsole, receiveConsole) => {
          let sent = false, confirmed = false;
          let confirmationId: number | undefined;
          let acknowledgementId: number | undefined;
          for (let n = 0; n < 64; n++) {
            const frame = await receiveConsole();
            if (frame.type === "ready" && !sent) { sent = true; sendConsole({ type: "command", line: command }); }
            else if (frame.type === "complete" && frame.status === "confirm_required" && frame.command === command && sent && !confirmed && Number.isSafeInteger(frame.id)) {
              confirmationId = Number(frame.id);
              confirmed = true; sendConsole({ type: "confirm", command });
            } else if (frame.type === "output" && frame.command === command && confirmed && frame.stream === "stdout" &&
                typeof frame.data === "string" && frame.data.trimEnd() === `Session '${id}' renamed to: ${title}` &&
                Number.isSafeInteger(frame.id) && frame.id !== confirmationId) {
              acknowledgementId = Number(frame.id);
            } else if (frame.type === "complete" && frame.command === command && sent) {
              if (frame.status !== "ok" || !confirmed || acknowledgementId === undefined || frame.id !== acknowledgementId) throw new MutationError();
              return Response.json({ ok: true, session_id: id, title });
            }
          }
          throw new MutationError();
        });
      }
      send({ jsonrpc: "2.0", id: 1, method: "session.delete", params: { session_id: id, profile } });
      for (let n = 0; n < 64; n++) {
        const frame = await receive();
        if (frame.id !== 1) continue;
        const error = frame.error as { code?: number } | undefined;
        if (error) throw new MutationError(typeof error.code === "number" && error.code >= 4000 && error.code < 5000);
        if ((frame.result as { deleted?: unknown })?.deleted !== id) throw new MutationError();
        return Response.json({ ok: true, deleted: id });
      }
      throw new MutationError();
    });
  } catch (error) { signal.throwIfAborted(); return failure(error instanceof MutationError && error.refused); }
  finally { pending.delete(key); }
}
