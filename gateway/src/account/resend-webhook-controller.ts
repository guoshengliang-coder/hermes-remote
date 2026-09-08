import type { IncomingMessage, ServerResponse } from "node:http";
import { Webhook } from "svix";
import type {
  EmailDeliveryEvent,
  EmailDeliveryEventStore,
  EmailDeliveryEventType,
  EmailDeliveryMessageKind,
} from "./email-delivery.js";

export const RESEND_WEBHOOK_PATH = "/v2/webhooks/resend";
const MAX_WEBHOOK_BYTES = 64 * 1024;
const IDENTIFIER_PATTERN = /^[A-Za-z0-9_-]+$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const TRACKED_TYPES: ReadonlySet<EmailDeliveryEventType> = new Set([
  "email.sent",
  "email.delivery_delayed",
  "email.delivered",
  "email.complained",
  "email.bounced",
  "email.failed",
  "email.suppressed",
]);

export class ResendWebhookController {
  private readonly webhook: Webhook;

  constructor(
    webhookSecret: string,
    private readonly store: EmailDeliveryEventStore,
    private readonly now: () => Date = () => new Date(),
  ) {
    if (!/^whsec_[A-Za-z0-9+/=_-]{16,}$/.test(webhookSecret)) {
      throw new Error("ACCOUNT_RESEND_WEBHOOK_SECRET must be a Resend signing secret");
    }
    this.webhook = new Webhook(webhookSecret);
  }

  async handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    if (request.method !== "POST") {
      send(response, 405, { error: "method_not_allowed" }, { allow: "POST" });
      return;
    }
    const headers = signedHeaders(request);
    if (!headers || !isJson(request.headers["content-type"])) {
      send(response, 400, { error: "invalid_webhook" });
      return;
    }

    let rawBody: Buffer;
    try {
      rawBody = await readBoundedBody(request, MAX_WEBHOOK_BYTES);
      this.webhook.verify(rawBody, headers);
    } catch {
      send(response, 400, { error: "invalid_webhook" });
      return;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(rawBody.toString("utf8"));
    } catch {
      send(response, 400, { error: "invalid_webhook" });
      return;
    }

    const receivedAt = this.now();
    let event: EmailDeliveryEvent | undefined;
    try {
      event = parseTrackedEvent(parsed, headers["svix-id"], receivedAt);
    } catch {
      send(response, 400, { error: "invalid_webhook" });
      return;
    }
    if (!event) {
      send(response, 200, { received: true });
      return;
    }

    try {
      const result = await this.store.record(event);
      if (result === "unmatched") {
        send(response, 500, { error: "webhook_unavailable" });
        return;
      }
      send(response, 200, { received: true });
    } catch {
      send(response, 500, { error: "webhook_unavailable" });
    }
  }
}

function parseTrackedEvent(
  value: unknown,
  webhookId: string,
  receivedAt: Date,
): EmailDeliveryEvent | undefined {
  if (!isRecord(value) || typeof value.type !== "string") throw new Error("invalid event");
  if (!TRACKED_TYPES.has(value.type as EmailDeliveryEventType)) return undefined;
  if (typeof value.created_at !== "string" || !isRecord(value.data)) throw new Error("invalid event");
  const createdAt = new Date(value.created_at);
  if (!Number.isFinite(createdAt.getTime())
      || createdAt.getTime() > receivedAt.getTime() + 5 * 60 * 1_000
      || createdAt.getTime() < receivedAt.getTime() - 31 * 24 * 60 * 60 * 1_000) {
    throw new Error("invalid event time");
  }
  const providerMessageId = value.data.email_id;
  if (typeof providerMessageId !== "string" || providerMessageId.length > 128
      || !IDENTIFIER_PATTERN.test(providerMessageId)) {
    throw new Error("invalid provider message id");
  }
  const tags = value.data.tags;
  if (!isRecord(tags)) return undefined;
  const kind = tags.hermes_kind;
  const messageId = tags.hermes_message_id;
  if (kind !== "email_otp" && kind !== "device_share") return undefined;
  if (typeof messageId !== "string" || !UUID_PATTERN.test(messageId)) {
    throw new Error("invalid Hermes message id");
  }
  return {
    webhookId,
    providerMessageId,
    messageKind: kind as EmailDeliveryMessageKind,
    messageId: messageId.toLowerCase(),
    eventType: value.type as EmailDeliveryEventType,
    eventCreatedAt: createdAt,
    receivedAt,
  };
}

function signedHeaders(request: IncomingMessage): {
  "svix-id": string;
  "svix-timestamp": string;
  "svix-signature": string;
} | undefined {
  const id = uniqueRawHeader(request, "svix-id", 256);
  const timestamp = uniqueRawHeader(request, "svix-timestamp", 32);
  const signature = uniqueRawHeader(request, "svix-signature", 1024);
  if (!id || !timestamp || !signature || !IDENTIFIER_PATTERN.test(id)) return undefined;
  return { "svix-id": id, "svix-timestamp": timestamp, "svix-signature": signature };
}

function uniqueRawHeader(request: IncomingMessage, name: string, maxLength: number): string | undefined {
  const matches: string[] = [];
  for (let index = 0; index < request.rawHeaders.length; index += 2) {
    if (request.rawHeaders[index]?.toLowerCase() === name) matches.push(request.rawHeaders[index + 1] ?? "");
  }
  return matches.length === 1 && matches[0].length >= 1 && matches[0].length <= maxLength
    ? matches[0]
    : undefined;
}

function readBoundedBody(request: IncomingMessage, limit: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let settled = false;
    request.on("data", (chunk: Buffer) => {
      if (settled) return;
      size += chunk.length;
      if (size > limit) {
        settled = true;
        chunks.length = 0;
        reject(new Error("request_too_large"));
        return;
      }
      chunks.push(Buffer.from(chunk));
    });
    request.on("end", () => {
      if (!settled) resolve(Buffer.concat(chunks));
    });
    request.on("error", (error) => {
      if (!settled) reject(error);
    });
  });
}

function isJson(contentType: string | string[] | undefined): boolean {
  return typeof contentType === "string" && /^application\/json(?:\s*;|$)/i.test(contentType);
}

function send(
  response: ServerResponse,
  status: number,
  body: unknown,
  extraHeaders: Record<string, string> = {},
): void {
  response.writeHead(status, {
    "content-type": "application/json",
    "cache-control": "no-store",
    ...extraHeaders,
  });
  response.end(JSON.stringify(body));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
