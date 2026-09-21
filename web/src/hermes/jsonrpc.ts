import type { JsonObject, JsonValue, ServerEvent } from "./types";

// Port of android data/network/JsonRpc.kt + ServerEvent.kt.

export interface RpcErrorObject {
  code: number;
  message: string;
  data?: JsonValue;
}

export type RpcInbound =
  | { kind: "event"; event: ServerEvent }
  /** Server→client request. `id` is kept as the exact primitive it arrived as. */
  | { kind: "request"; id: string | number; method: string; params: JsonObject }
  /** Response to one of our calls. Every id this client mints is numeric; anything else is -1. */
  | { kind: "result"; id: number; result: JsonValue }
  | { kind: "error"; id: number; error: RpcErrorObject }
  | { kind: "unreadable"; reason: string };

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function objOrEmpty(value: unknown): JsonObject {
  return isObject(value) ? value : {};
}

/** Primitive content as Kotlin's `contentOrNull` sees it: strings, numbers and booleans as text. */
function primitiveContent(value: unknown): string | null {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return null;
}

const SESSION_ID_KEYS = ["stored_session_id", "storedSessionId", "session_id", "sessionId"] as const;

/** `ServerEvent.from`: durable id first, payload before params, never throw on odd shapes. */
export function serverEventFrom(params: JsonObject): ServerEvent {
  const type = primitiveContent(params.type) ?? "unknown";
  const payload = objOrEmpty(params.payload);
  let sessionId: string | null = null;
  for (const key of SESSION_ID_KEYS) {
    for (const source of [payload, params]) {
      const value = primitiveContent(source[key]);
      if (value && value.trim()) {
        sessionId = value;
        break;
      }
    }
    if (sessionId) break;
  }
  return { type, sessionId, payload };
}

/** A WebSocket message may carry several newline-separated frames. */
export function splitFrames(text: string): string[] {
  return text.split(/\r?\n/).filter((line) => line.trim() !== "");
}

function responseId(raw: unknown): number {
  if (typeof raw === "number" && Number.isInteger(raw)) return raw;
  // kotlinx `longOrNull` also reads a numeric string.
  if (typeof raw === "string" && /^-?\d+$/.test(raw)) return Number(raw);
  return -1;
}

export function parseInbound(line: string): RpcInbound {
  let obj: unknown;
  try {
    obj = JSON.parse(line);
  } catch {
    return { kind: "unreadable", reason: "not a JSON object" };
  }
  if (!isObject(obj)) return { kind: "unreadable", reason: "not a JSON object" };
  const method = typeof obj.method === "string" ? obj.method : null;
  if (method === "event") return { kind: "event", event: serverEventFrom(objOrEmpty(obj.params)) };
  const rawId = obj.id;
  if (method !== null) {
    // A request needs an id to be answerable; one without is a notification we do not know.
    if (typeof rawId !== "string" && typeof rawId !== "number") {
      return { kind: "unreadable", reason: `notification ${method}` };
    }
    return { kind: "request", id: rawId, method, params: objOrEmpty(obj.params) };
  }
  const id = responseId(rawId);
  if ("error" in obj && obj.error !== undefined) {
    const e = objOrEmpty(obj.error);
    const codeText = primitiveContent(e.code);
    const code = codeText !== null && /^-?\d+$/.test(codeText) ? Number(codeText) : 0;
    const message = primitiveContent(e.message) ?? "error";
    return { kind: "error", id, error: e.data !== undefined ? { code, message, data: e.data } : { code, message } };
  }
  return { kind: "result", id, result: obj.result ?? null };
}

/** Parse one WebSocket message (possibly several frames). */
export function parseMessage(text: string): RpcInbound[] {
  return splitFrames(text).map(parseInbound);
}

export function encodeRequest(id: number, method: string, params: JsonObject): string {
  return JSON.stringify({ jsonrpc: "2.0", id, method, params });
}

/** The error frame answering server request `id`: upstream reads any error as "no answer". */
export function encodeServerError(id: string | number, code: number, message: string): string {
  return JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } });
}
