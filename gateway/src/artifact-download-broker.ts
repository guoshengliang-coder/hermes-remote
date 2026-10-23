import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { WebSocket } from "ws";
import {
  ARTIFACT_DOWNLOADS_PATH, ARTIFACT_ID, ARTIFACT_RETURN_PATH, PROTOCOL_VERSION,
  artifactFailure, type ArtifactErrorCode, type WireMessage,
} from "@hermes-remote/protocol";

interface Connector { socket: WebSocket; deviceId: string; routingKey: string }
interface Transfer {
  response: ServerResponse; connector: Connector; ticket: string; returning?: IncomingMessage;
  timer: NodeJS.Timeout; accessTimer?: NodeJS.Timeout; cleanup(): void;
  revalidate?: () => Promise<void>; checking?: Promise<boolean>;
}

export function sendArtifactError(response: ServerResponse, status: number, code: ArtifactErrorCode): void {
  if (response.destroyed || response.writableEnded) return;
  if (response.headersSent) { response.destroy(); return; }
  response.writeHead(status, { "content-type": "application/json", "cache-control": "private, no-store" });
  response.end(JSON.stringify(artifactFailure(code)));
}

/** One bounded binary response per Mac. No complete-file buffers or temporary files. */
export class ArtifactDownloadBroker {
  private readonly pending = new Map<string, Transfer>();
  private readonly active = new Set<WebSocket>();
  constructor(
    private readonly send: (socket: WebSocket, message: WireMessage) => unknown,
    private readonly idleMs = 30_000,
    private readonly capacity = 64,
  ) {}

  handles(url: URL): boolean {
    return url.pathname.startsWith(`${ARTIFACT_DOWNLOADS_PATH}/`);
  }

  forward(
    request: IncomingMessage, response: ServerResponse, url: URL, connector: Connector,
    revalidate?: () => Promise<void>,
  ): void {
    const artifactId = url.pathname.slice(ARTIFACT_DOWNLOADS_PATH.length + 1);
    const etag = request.headers["if-match"];
    const range = request.headers.range;
    const match = typeof range === "string" ? /^bytes=(\d+)-(\d+)$/.exec(range) : undefined;
    if (request.method !== "GET" || !ARTIFACT_ID.test(artifactId) || url.search
        || typeof etag !== "string" || !/^"[a-f0-9]{64}"$/.test(etag)
        || !match || !Number.isSafeInteger(Number(match[2]))
        || Number(match[2]) < Number(match[1]) || Number(match[2]) - Number(match[1]) >= 8 * 1024 * 1024) {
      sendArtifactError(response, 400, "HR-FILE-006"); return;
    }
    if (this.active.has(connector.socket) || this.pending.size >= this.capacity) {
      sendArtifactError(response, 503, "HR-FILE-013"); return;
    }
    if (connector.socket.readyState !== WebSocket.OPEN) {
      sendArtifactError(response, 503, "HR-FILE-006"); return;
    }
    const id = randomUUID(), ticket = randomBytes(32).toString("hex");
    const closed = () => this.stop(id);
    const transfer: Transfer = {
      response, connector, ticket, revalidate,
      timer: setTimeout(() => this.fail(id), this.idleMs),
      cleanup: () => {
        connector.socket.off("close", closed);
        request.off("aborted", closed);
        response.off("close", closed);
      },
    };
    this.pending.set(id, transfer);
    this.active.add(connector.socket);
    connector.socket.once("close", closed);
    request.once("aborted", closed);
    response.once("close", closed);
    if (revalidate) transfer.accessTimer = setInterval(() => { void this.checkAccess(id); }, 15_000);
    if (this.send(connector.socket, {
      type: "tunnel.http.request", version: PROTOCOL_VERSION, id, targetDeviceId: connector.deviceId,
      method: "GET", path: `${ARTIFACT_DOWNLOADS_PATH}/${artifactId}`,
      headers: { "if-match": etag, ...(range ? { range } : {}),
        "x-hermes-file-return": id, "x-hermes-file-ticket": ticket },
    }) === false) this.fail(id);
  }

  /** Called before ordinary app/Connector authentication; the one-use capability is the authority. */
  async accept(request: IncomingMessage, response: ServerResponse, url: URL): Promise<boolean> {
    if (!url.pathname.startsWith(ARTIFACT_RETURN_PATH)) return false;
    const id = url.pathname.slice(ARTIFACT_RETURN_PATH.length);
    const entry = this.pending.get(id);
    const authorization = request.headers.authorization ?? "";
    const expected = `Bearer ${entry?.ticket ?? ""}`;
    const actual = Buffer.from(authorization), wanted = Buffer.from(expected);
    if (request.method !== "PUT" || url.search || !entry || entry.returning
        || actual.length !== wanted.length || !timingSafeEqual(actual, wanted)) {
      sendArtifactError(response, 403, "HR-FILE-003"); return true;
    }
    // Reserve before any await, so concurrent use of the same capability cannot race.
    entry.returning = request;
    if (!await this.checkAccess(id)) { sendArtifactError(response, 403, "HR-FILE-003"); return true; }
    if (this.pending.get(id) !== entry) { response.destroy(); return true; }
    const status = Number(request.headers["x-hermes-file-status"]);
    const size = Number(request.headers["content-length"]);
    if (![200, 206, 400, 403, 404, 410, 412, 413, 416, 500, 503].includes(status)
        || !Number.isSafeInteger(size) || size < 0 || size > 8 * 1024 * 1024
        || (status >= 400 && size > 4096)) {
      this.fail(id); sendArtifactError(response, 400, "HR-FILE-006"); return true;
    }
    const headers: Record<string, string> = { "cache-control": "private, no-store", "x-content-type-options": "nosniff" };
    for (const name of ["content-type", "content-length", "etag", "content-range", "accept-ranges"]) {
      const value = request.headers[name];
      if (typeof value === "string" && value.length <= 256) headers[name] = value;
    }
    entry.response.writeHead(status, headers);
    let received = 0;
    try {
      for await (const chunk of request) {
        if (this.pending.get(id) !== entry) throw new Error("transfer_cancelled");
        received += chunk.length;
        if (received > size) throw new Error("transfer_length");
        this.touch(entry);
        if (!entry.response.write(chunk)) {
          await waitForDrain(entry.response);
          this.touch(entry);
        }
      }
      if (received !== size) throw new Error("transfer_length");
      this.finish(id);
      entry.response.end();
      response.writeHead(204); response.end();
    } catch {
      this.stop(id);
      response.destroy();
    }
    return true;
  }

  revalidateAll(): void { for (const id of this.pending.keys()) void this.checkAccess(id); }
  close(): void { for (const id of [...this.pending.keys()]) this.stop(id); }

  private async checkAccess(id: string): Promise<boolean> {
    const entry = this.pending.get(id);
    if (!entry) return false;
    if (!entry.revalidate) return true;
    if (entry.checking) return entry.checking;
    entry.checking = (async () => {
      try { await entry.revalidate!(); return this.pending.get(id) === entry; }
      catch { this.fail(id); return false; }
      finally { entry.checking = undefined; }
    })();
    return entry.checking;
  }
  private touch(entry: Transfer): void { entry.timer.refresh(); }
  private fail(id: string): void {
    const entry = this.pending.get(id);
    if (!entry) return;
    sendArtifactError(entry.response, 502, "HR-FILE-006");
    this.stop(id);
  }
  private stop(id: string): void {
    const entry = this.pending.get(id);
    if (!entry) return;
    this.finish(id);
    entry.returning?.destroy();
    if (!entry.response.writableEnded) entry.response.destroy();
    if (entry.connector.socket.readyState === WebSocket.OPEN) this.send(entry.connector.socket, {
      type: "tunnel.http.cancel", version: PROTOCOL_VERSION, requestId: id, reason: "client_aborted",
    });
  }
  private finish(id: string): void {
    const entry = this.pending.get(id);
    if (!entry) return;
    this.pending.delete(id);
    this.active.delete(entry.connector.socket);
    clearTimeout(entry.timer); clearInterval(entry.accessTimer);
    entry.cleanup();
  }
}

function waitForDrain(response: ServerResponse): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      response.off("drain", drained); response.off("close", closed); response.off("error", closed);
    };
    const drained = () => { cleanup(); resolve(); };
    const closed = () => { cleanup(); reject(new Error("download_closed")); };
    response.once("drain", drained); response.once("close", closed); response.once("error", closed);
    if (response.destroyed) closed();
  });
}
