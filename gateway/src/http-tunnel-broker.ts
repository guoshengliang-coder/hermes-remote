import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { WebSocket } from "ws";
import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
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
  responseHeaders(headers: Record<string, string>): Record<string, string>;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;

export class HttpTunnelBroker {
  private readonly pending = new Map<string, PendingHttp>();

  constructor(
    private readonly maxBodyBytes: number,
    private readonly maxPendingRequests: number,
    private readonly requestTimeoutMs: number,
    private readonly send: SendWireMessage,
    private readonly log: GatewayLogger = silentGatewayLogger,
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
    if (this.pending.size >= this.maxPendingRequests) {
      sendHttpError(response, 503, "relay_capacity_reached");
      return;
    }

    let body: Buffer;
    if (preReadBody) {
      body = preReadBody;
    } else {
      try {
        body = await readRequestBody(request, this.maxBodyBytes);
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

  handleConnectorMessage(connector: HttpConnector, message: WireMessage): boolean {
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
    pending.timer = setTimeout(() => this.expire(id), this.requestTimeoutMs);
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
