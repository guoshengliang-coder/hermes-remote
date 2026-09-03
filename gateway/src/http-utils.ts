import type { IncomingMessage, ServerResponse } from "node:http";

export function selectRequestHeaders(request: IncomingMessage): Record<string, string> {
  const selected: Record<string, string> = {};
  for (const name of ["accept", "content-type"]) {
    const value = firstHeader(request, name);
    if (value) selected[name] = value;
  }
  return selected;
}

export function selectResponseHeaders(headers: Record<string, string>): Record<string, string> {
  const selected: Record<string, string> = {};
  for (const name of ["content-type", "content-length", "content-disposition", "cache-control"]) {
    const value = headers[name];
    if (value) selected[name] = value;
  }
  return selected;
}

export function firstHeader(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

export function readRequestBody(request: IncomingMessage, limit: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    request.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("request_too_large"));
        request.destroy();
        return;
      }
      chunks.push(Buffer.from(chunk));
    });
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

export function sendHttpError(response: ServerResponse, status: number, code: string): void {
  if (response.writableEnded) return;
  if (response.headersSent) {
    response.destroy(new Error(code));
    return;
  }
  response.writeHead(status, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: code }));
}

export function sendJson(response: ServerResponse, status: number, value: unknown): void {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(value));
}

export function parseEventIds(body: Buffer): string[] {
  const value: unknown = JSON.parse(body.toString("utf8"));
  if (!isRecord(value) || !Array.isArray(value.event_ids) || value.event_ids.length > 500) {
    throw new Error("invalid_event_ids");
  }
  return value.event_ids.map((eventId) => {
    if (typeof eventId !== "string" || eventId.length < 1 || eventId.length > 256) {
      throw new Error("invalid_event_id");
    }
    return eventId;
  });
}

export function nonNegativeIntegerQuery(
  url: URL,
  name: string,
  fallback: number,
): number | undefined {
  const raw = url.searchParams.get(name);
  if (raw === null) return fallback;
  if (!/^\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : undefined;
}

export function positiveIntegerQuery(
  url: URL,
  name: string,
  fallback: number,
  max: number,
): number | undefined {
  const value = nonNegativeIntegerQuery(url, name, fallback);
  return value !== undefined && value > 0 && value <= max ? value : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
