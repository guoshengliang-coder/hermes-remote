import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { WebSocket } from "ws";
import { MAX_PHONE_UPLOAD_BYTES, PHONE_UPLOAD_CHUNK_BYTES, PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
import { silentGatewayLogger, type GatewayLogger } from "./gateway-log.js";
import {
  readRequestBody,
  selectRequestHeaders,
  selectResponseHeaders,
  sendHttpError,
} from "./http-utils.js";

interface HttpConnector {
  socket: WebSocket;
  deviceId: string;
  routingKey: string;
}

interface PendingHttp {
  response: ServerResponse;
  connectorSocket: WebSocket;
  routingKey: string;
  timer: NodeJS.Timeout;
  started: boolean;
  nextSequence: number;
  startedAt: number;
  /** Response status from `tunnel.http.response` or `.start`, once known. */
  status?: number;
  /** Decoded body bytes handed to the client response so far. */
  bytesWritten: number;
  chunkCount: number;
  /** Milliseconds from forwarding the request to the first response message. */
  ttfbMs?: number;
  method: string;
  path: string;
  device: string;
  upload: boolean;
  responseHeaders(headers: Record<string, string>): Record<string, string>;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => unknown;
type UploadAckWaiter = { sequence: number; resolve(): void; reject(error: Error): void; timer: NodeJS.Timeout };
class UploadLimitExceeded extends Error {}
class UploadAckTimeout extends Error {}
class UploadSocketBusy extends Error {}
const LEGACY_UPLOAD_BYTES = 6 * 1024 * 1024;

export class HttpTunnelBroker {
  private readonly pending = new Map<string, PendingHttp>();
  /** A file upload occupies one sequential stream on this Mac. */
  private readonly activeUploads = new Set<string>();
  private readonly uploadAcks = new Map<string, UploadAckWaiter>();
  private readonly uploadSupport = new WeakMap<WebSocket, boolean>();

  constructor(
    private readonly maxBodyBytes: number,
    private readonly maxPendingRequests: number,
    private readonly requestTimeoutMs: number,
    private readonly send: SendWireMessage,
    private readonly log: GatewayLogger = silentGatewayLogger,
    private readonly uploadStartAckTimeoutMs = 10_000,
  ) {}

  async forward(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
    connector: HttpConnector,
    responseHeaders: (headers: Record<string, string>) => Record<string, string> = (headers) => headers,
    /** A body the caller already read (and checked); otherwise it is read here. */
    preReadBody?: Buffer,
  ): Promise<void> {
    const upload = request.method === "POST" && url.pathname === "/api/files/upload";
    if (this.pending.size >= this.maxPendingRequests) {
      sendHttpError(response, 503, "relay_capacity_reached");
      return;
    }
    if (upload) {
      await this.forwardUpload(request, response, url, connector, responseHeaders, preReadBody);
      return;
    }

    const limit = this.maxBodyBytes;
    const declared = Number(request.headers["content-length"]);
    if (Number.isFinite(declared) && declared > limit) {
      sendHttpError(response, 413, "request_too_large");
      return;
    }

    let body: Buffer;
    if (preReadBody) {
      if (preReadBody.length > limit) {
        sendHttpError(response, 413, "request_too_large");
        return;
      }
      body = preReadBody;
    } else {
      try {
        body = await readRequestBody(request, limit);
      } catch {
        sendHttpError(response, 413, "request_too_large");
        return;
      }
    }

    const id = randomUUID();
    const timer = setTimeout(() => this.expire(id), this.requestTimeoutMs);
    this.pending.set(id, {
      response,
      connectorSocket: connector.socket,
      routingKey: connector.routingKey,
      timer,
      started: false,
      nextSequence: 0,
      startedAt: Date.now(),
      bytesWritten: 0,
      chunkCount: 0,
      method: request.method ?? "GET",
      path: `${url.pathname}${url.search}`,
      device: connector.deviceId,
      upload: false,
      responseHeaders,
    });
    request.on("aborted", () => this.abort(id));
    response.on("close", () => this.abort(id));

    this.send(connector.socket, {
      type: "tunnel.http.request",
      version: PROTOCOL_VERSION,
      id,
      targetDeviceId: connector.deviceId,
      method: request.method ?? "GET",
      path: `${url.pathname}${url.search}`,
      headers: selectRequestHeaders(request),
      bodyBase64: body.length > 0 ? body.toString("base64") : undefined,
    });
  }

  private async forwardUpload(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
    connector: HttpConnector,
    responseHeaders: (headers: Record<string, string>) => Record<string, string>,
    preReadBody?: Buffer,
  ): Promise<void> {
    if (this.activeUploads.has(connector.routingKey)) {
      sendHttpError(response, 503, "HR-FILE-009");
      return;
    }
    if (this.uploadSupport.get(connector.socket) === false) {
      await this.forwardLegacyUpload(request, response, url, connector, responseHeaders, preReadBody);
      return;
    }
    const declared = Number(request.headers["content-length"]);
    if (Number.isFinite(declared) && declared > MAX_PHONE_UPLOAD_BYTES) {
      sendHttpError(response, 413, "HR-FILE-008");
      return;
    }
    this.activeUploads.add(connector.routingKey);
    const id = randomUUID();
    const timer = setTimeout(() => this.expire(id), 10 * 60_000);
    const pending: PendingHttp = {
      response, connectorSocket: connector.socket, routingKey: connector.routingKey, timer,
      started: false, nextSequence: 0, startedAt: Date.now(), bytesWritten: 0, chunkCount: 0,
      method: "POST", path: `${url.pathname}${url.search}`, device: connector.deviceId,
      upload: true, responseHeaders,
    };
    this.pending.set(id, pending);
    request.on("aborted", () => this.abort(id));
    response.on("close", () => this.abort(id));
    try {
      await this.sendUploadFrameAndWait(id, -1, connector.socket, {
        type: "tunnel.http.request.start", version: PROTOCOL_VERSION, id,
        targetDeviceId: connector.deviceId, method: "POST", path: pending.path,
        headers: selectRequestHeaders(request),
      }, this.uploadStartAckTimeoutMs);
      const frame = Buffer.allocUnsafe(PHONE_UPLOAD_CHUNK_BYTES);
      let filled = 0;
      let sequence = 0;
      let total = 0;
      const source = preReadBody ? [preReadBody] : request;
      for await (const incoming of source) {
        if (!this.pending.has(id)) return;
        const chunk = Buffer.from(incoming);
        total += chunk.length;
        if (total > MAX_PHONE_UPLOAD_BYTES) throw new UploadLimitExceeded();
        for (let offset = 0; offset < chunk.length;) {
          const count = Math.min(PHONE_UPLOAD_CHUNK_BYTES - filled, chunk.length - offset);
          chunk.copy(frame, filled, offset, offset + count);
          filled += count;
          offset += count;
          if (filled === PHONE_UPLOAD_CHUNK_BYTES) {
            await this.sendUploadChunk(id, sequence++, connector.socket, frame);
            filled = 0;
          }
        }
      }
      if (total === 0) {
        this.cancel(id, "gateway_rejected");
        sendHttpError(response, 400, "empty_file");
        return;
      }
      if (filled > 0) await this.sendUploadChunk(id, sequence, connector.socket, frame.subarray(0, filled));
      if (this.send(connector.socket, {
        type: "tunnel.http.request.end", version: PROTOCOL_VERSION, requestId: id,
      }) === false) throw new UploadSocketBusy();
    } catch (error) {
      if (!this.pending.has(id)) return;
      this.cancel(id, "gateway_rejected");
      if (error instanceof UploadAckTimeout && this.uploadSupport.get(connector.socket) !== true) {
        // Old Connectors ignore the new start frame. Preserve their existing <=6 MiB uploads;
        // Android sees HR-FILE-010 for larger files until that Mac updates.
        this.uploadSupport.set(connector.socket, false);
        await this.forwardLegacyUpload(request, response, url, connector, responseHeaders, preReadBody);
      } else if (error instanceof UploadLimitExceeded) sendHttpError(response, 413, "HR-FILE-008");
      else if (error instanceof UploadAckTimeout) sendHttpError(response, 503, "HR-FILE-009");
      else sendHttpError(response, 503, "HR-FILE-009");
    }
  }

  private async forwardLegacyUpload(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
    connector: HttpConnector,
    responseHeaders: (headers: Record<string, string>) => Record<string, string>,
    preReadBody?: Buffer,
  ): Promise<void> {
    const declared = Number(request.headers["content-length"]);
    if ((Number.isFinite(declared) && declared > LEGACY_UPLOAD_BYTES)
        || (preReadBody && preReadBody.length > LEGACY_UPLOAD_BYTES)) {
      sendHttpError(response, 413, "HR-FILE-010");
      return;
    }
    this.activeUploads.add(connector.routingKey);
    let body: Buffer;
    try {
      body = preReadBody ?? await readRequestBody(request, LEGACY_UPLOAD_BYTES);
    } catch {
      this.activeUploads.delete(connector.routingKey);
      sendHttpError(response, 413, "HR-FILE-010");
      return;
    }
    const id = randomUUID();
    const timer = setTimeout(() => this.expire(id), 10 * 60_000);
    this.pending.set(id, {
      response, connectorSocket: connector.socket, routingKey: connector.routingKey, timer,
      started: false, nextSequence: 0, startedAt: Date.now(), bytesWritten: 0, chunkCount: 0,
      method: "POST", path: `${url.pathname}${url.search}`, device: connector.deviceId,
      upload: true, responseHeaders,
    });
    request.on("aborted", () => this.abort(id));
    response.on("close", () => this.abort(id));
    if (this.send(connector.socket, {
      type: "tunnel.http.request", version: PROTOCOL_VERSION, id,
      targetDeviceId: connector.deviceId, method: "POST", path: `${url.pathname}${url.search}`,
      headers: selectRequestHeaders(request), bodyBase64: body.toString("base64"),
    }) === false) {
      this.clear(id);
      sendHttpError(response, 503, "HR-FILE-009");
    }
  }

  private sendUploadChunk(id: string, sequence: number, socket: WebSocket, bytes: Buffer): Promise<void> {
    return this.sendUploadFrameAndWait(id, sequence, socket, {
      type: "tunnel.http.request.chunk", version: PROTOCOL_VERSION, requestId: id,
      sequence, dataBase64: bytes.toString("base64"),
    }, 30_000);
  }

  private sendUploadFrameAndWait(
    id: string, sequence: number, socket: WebSocket, message: WireMessage, timeoutMs: number,
  ): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.uploadAcks.delete(id);
        reject(new UploadAckTimeout());
      }, timeoutMs);
      this.uploadAcks.set(id, { sequence, resolve, reject, timer });
      if (this.send(socket, message) === false) {
        clearTimeout(timer);
        this.uploadAcks.delete(id);
        reject(new UploadSocketBusy());
      }
    });
  }

  handleConnectorMessage(connector: HttpConnector, message: WireMessage): boolean {
    if (message.type === "tunnel.http.request.ack") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey || !pending.upload) return true;
      const waiter = this.uploadAcks.get(message.requestId);
      if (!waiter || waiter.sequence !== message.sequence) return true;
      clearTimeout(waiter.timer);
      this.uploadAcks.delete(message.requestId);
      this.refreshTimeout(message.requestId, pending);
      if (message.sequence === -1) this.uploadSupport.set(connector.socket, true);
      waiter.resolve();
      return true;
    }
    if (message.type === "tunnel.http.response") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey) return true;
      this.clear(message.requestId);
      if (pending.started) {
        pending.response.destroy(new Error("mixed_http_response_modes"));
        return true;
      }
      const body = message.bodyBase64 ? Buffer.from(message.bodyBase64, "base64") : Buffer.alloc(0);
      markFirstByte(pending);
      pending.status = message.status;
      pending.bytesWritten = body.length;
      this.logOutcome(pending, "response");
      pending.response.writeHead(
        message.status,
        pending.responseHeaders(selectResponseHeaders(message.headers)),
      );
      pending.response.end(body);
      return true;
    }

    if (message.type === "tunnel.http.response.start") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey || pending.started) return true;
      pending.started = true;
      markFirstByte(pending);
      pending.status = message.status;
      this.refreshTimeout(message.requestId, pending);
      pending.response.writeHead(
        message.status,
        pending.responseHeaders(selectResponseHeaders(message.headers)),
      );
      return true;
    }

    if (message.type === "tunnel.http.response.chunk") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey || !pending.started) return true;
      if (message.sequence !== pending.nextSequence) {
        this.cancel(message.requestId, "gateway_rejected");
        this.logOutcome(pending, "error:invalid_response_chunk_sequence");
        pending.response.destroy(new Error("invalid_response_chunk_sequence"));
        return true;
      }
      pending.nextSequence += 1;
      this.refreshTimeout(message.requestId, pending);
      const chunk = Buffer.from(message.dataBase64, "base64");
      pending.chunkCount += 1;
      pending.bytesWritten += chunk.length;
      pending.response.write(chunk, () => {
        const current = this.pending.get(message.requestId);
        if (current !== pending) return;
        this.send(connector.socket, {
          type: "tunnel.http.response.ack",
          version: PROTOCOL_VERSION,
          requestId: message.requestId,
          sequence: message.sequence,
        });
      });
      return true;
    }

    if (message.type === "tunnel.http.response.end") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey) return true;
      this.clear(message.requestId);
      markFirstByte(pending);
      if (message.error) {
        if (!pending.started) pending.status = 502;
        this.logOutcome(pending, `error:${message.error}`);
        if (!pending.started) sendHttpError(pending.response, 502, message.error);
        else pending.response.destroy(new Error(message.error));
      } else {
        if (!pending.started) pending.status = 204;
        this.logOutcome(pending, pending.started ? "streamed" : "empty");
        if (!pending.started) pending.response.writeHead(204);
        pending.response.end();
      }
      return true;
    }

    return false;
  }

  failRouting(routingKey: string): void {
    for (const [id, pending] of this.pending) {
      if (pending.routingKey !== routingKey) continue;
      this.clear(id);
      if (!pending.started) pending.status = 502;
      this.logOutcome(pending, "connector_disconnected");
      sendHttpError(pending.response, 502, "connector_disconnected");
    }
  }

  /**
   * One line per tunnelled request. `status` is what the client received (for a stream, the status
   * of `response.start`, even if it later failed); `bytes` counts decoded body bytes handed to the
   * client response, before any edge compression; `chunks` is the number of streamed chunks (0 for
   * a buffered response); `ttfbMs` is the time from forwarding to the Connector's first response
   * message, absent when none arrived.
   */
  private logOutcome(pending: PendingHttp, outcome: string): void {
    this.log.info("http.tunnel", {
      method: pending.method,
      path: pending.path,
      device: pending.device,
      outcome,
      status: pending.status,
      bytes: pending.bytesWritten,
      chunks: pending.chunkCount,
      ttfbMs: pending.ttfbMs,
      durationMs: Date.now() - pending.startedAt,
    });
  }

  private abort(id: string): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.cancel(id, "client_aborted");
    this.logOutcome(pending, "client_aborted");
  }

  private clear(id: string): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    const waiter = this.uploadAcks.get(id);
    if (waiter) {
      clearTimeout(waiter.timer);
      this.uploadAcks.delete(id);
      waiter.reject(new Error("upload_cancelled"));
    }
    if (pending.upload) this.activeUploads.delete(pending.routingKey);
    this.pending.delete(id);
  }

  private cancel(
    id: string,
    reason: "client_aborted" | "gateway_timeout" | "gateway_rejected",
  ): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.clear(id);
    this.send(pending.connectorSocket, {
      type: "tunnel.http.cancel",
      version: PROTOCOL_VERSION,
      requestId: id,
      reason,
    });
  }

  private refreshTimeout(id: string, pending: PendingHttp): void {
    clearTimeout(pending.timer);
    pending.timer = setTimeout(() => this.expire(id), pending.upload ? 10 * 60_000 : this.requestTimeoutMs);
  }

  private expire(id: string): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.cancel(id, "gateway_timeout");
    if (!pending.response.headersSent) pending.status = 504;
    this.logOutcome(pending, "connector_timeout");
    if (pending.response.headersSent) pending.response.destroy(new Error("connector_timeout"));
    else sendHttpError(pending.response, 504, "connector_timeout");
  }
}

function markFirstByte(pending: PendingHttp): void {
  pending.ttfbMs ??= Date.now() - pending.startedAt;
}
