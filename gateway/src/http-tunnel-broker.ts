import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { WebSocket } from "ws";
import { PROTOCOL_VERSION, type WireMessage } from "@hermes-remote/protocol";
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
  routingKey: string;
  timer: NodeJS.Timeout;
  started: boolean;
  nextSequence: number;
}

type SendWireMessage = (socket: WebSocket, message: WireMessage) => void;

export class HttpTunnelBroker {
  private readonly pending = new Map<string, PendingHttp>();

  constructor(
    private readonly maxBodyBytes: number,
    private readonly maxPendingRequests: number,
    private readonly requestTimeoutMs: number,
    private readonly send: SendWireMessage,
  ) {}

  async forward(
    request: IncomingMessage,
    response: ServerResponse,
    url: URL,
    connector: HttpConnector,
  ): Promise<void> {
    if (this.pending.size >= this.maxPendingRequests) {
      sendHttpError(response, 503, "relay_capacity_reached");
      return;
    }

    let body: Buffer;
    try {
      body = await readRequestBody(request, this.maxBodyBytes);
    } catch {
      sendHttpError(response, 413, "request_too_large");
      return;
    }

    const id = randomUUID();
    const timer = setTimeout(() => this.expire(id), this.requestTimeoutMs);
    this.pending.set(id, {
      response,
      routingKey: connector.routingKey,
      timer,
      started: false,
      nextSequence: 0,
    });
    request.on("aborted", () => this.clear(id));
    response.on("close", () => this.clear(id));

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
      pending.response.writeHead(message.status, selectResponseHeaders(message.headers));
      pending.response.end(body);
      return true;
    }

    if (message.type === "tunnel.http.response.start") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey || pending.started) return true;
      pending.started = true;
      this.refreshTimeout(message.requestId, pending);
      pending.response.writeHead(message.status, selectResponseHeaders(message.headers));
      return true;
    }

    if (message.type === "tunnel.http.response.chunk") {
      const pending = this.pending.get(message.requestId);
      if (!pending || pending.routingKey !== connector.routingKey || !pending.started) return true;
      if (message.sequence !== pending.nextSequence) {
        this.clear(message.requestId);
        pending.response.destroy(new Error("invalid_response_chunk_sequence"));
        return true;
      }
      pending.nextSequence += 1;
      this.refreshTimeout(message.requestId, pending);
      const chunk = Buffer.from(message.dataBase64, "base64");
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
      if (message.error) {
        if (!pending.started) sendHttpError(pending.response, 502, message.error);
        else pending.response.destroy(new Error(message.error));
      } else {
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
      sendHttpError(pending.response, 502, "connector_disconnected");
    }
  }

  private clear(id: string): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
  }

  private refreshTimeout(id: string, pending: PendingHttp): void {
    clearTimeout(pending.timer);
    pending.timer = setTimeout(() => this.expire(id), this.requestTimeoutMs);
  }

  private expire(id: string): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    this.pending.delete(id);
    clearTimeout(pending.timer);
    if (pending.response.headersSent) pending.response.destroy(new Error("connector_timeout"));
    else sendHttpError(pending.response, 504, "connector_timeout");
  }
}
