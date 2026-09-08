import assert from "node:assert/strict";
import type { IncomingMessage, ServerResponse } from "node:http";
import { Readable } from "node:stream";
import { test } from "node:test";
import { Webhook } from "svix";
import type { EmailDeliveryEvent, EmailDeliveryEventStore } from "./account/email-delivery.js";
import { ResendWebhookController } from "./account/resend-webhook-controller.js";

const SECRET = `whsec_${Buffer.from("resend-webhook-test-secret-32bytes!").toString("base64")}`;
const NOW = new Date();
const WEBHOOK_ID = "msg_webhook_01";
const MESSAGE_ID = "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea";

test("Resend webhook rejects a malformed signing secret at startup", () => {
  assert.throws(
    () => new ResendWebhookController("not-a-secret", store([]), () => NOW),
    /ACCOUNT_RESEND_WEBHOOK_SECRET/,
  );
});

test("verified Resend delivery stores only the bounded Hermes correlation fields", async () => {
  const recorded: EmailDeliveryEvent[] = [];
  const body = JSON.stringify({
    type: "email.delivered",
    created_at: new Date(NOW.getTime() - 1_000).toISOString(),
    data: {
      email_id: "provider_email_01",
      from: "login@example.com",
      to: ["private-person@example.com"],
      subject: "private subject",
      tags: { hermes_kind: "email_otp", hermes_message_id: MESSAGE_ID },
    },
  });
  const response = await invoke(
    new ResendWebhookController(SECRET, store(recorded), () => NOW),
    signedRequest(body),
  );

  assert.equal(response.status, 200);
  assert.deepEqual(response.json(), { received: true });
  assert.deepEqual(recorded, [{
    webhookId: WEBHOOK_ID,
    providerMessageId: "provider_email_01",
    messageKind: "email_otp",
    messageId: MESSAGE_ID,
    eventType: "email.delivered",
    eventCreatedAt: new Date(NOW.getTime() - 1_000),
    receivedAt: NOW,
  }]);
  assert.equal(JSON.stringify(recorded).includes("private-person@example.com"), false);
  assert.equal(response.body.includes("private"), false);
});

test("signature verification uses the exact raw body and rejects duplicate signature headers", async () => {
  const recorded: EmailDeliveryEvent[] = [];
  const body = trackedBody("email.bounced");
  const mutated = `${body} `;
  const invalidSignature = signedRequest(mutated, body);
  const invalidResponse = await invoke(
    new ResendWebhookController(SECRET, store(recorded), () => NOW),
    invalidSignature,
  );
  assert.equal(invalidResponse.status, 400);

  const duplicate = signedRequest(body);
  duplicate.rawHeaders.push("Svix-Signature", "v1,duplicate");
  const duplicateResponse = await invoke(
    new ResendWebhookController(SECRET, store(recorded), () => NOW),
    duplicate,
  );
  assert.equal(duplicateResponse.status, 400);
  assert.deepEqual(recorded, []);
});

test("signed unrelated events are acknowledged without touching Hermes state", async () => {
  const recorded: EmailDeliveryEvent[] = [];
  const body = JSON.stringify({ type: "domain.created", created_at: NOW.toISOString(), data: {} });
  const response = await invoke(
    new ResendWebhookController(SECRET, store(recorded), () => NOW),
    signedRequest(body),
  );
  assert.equal(response.status, 200);
  assert.deepEqual(recorded, []);
});

test("tracked events reject stale payload time, malformed tags, and oversized raw bodies", async () => {
  const stale = JSON.stringify({
    type: "email.delivered",
    created_at: new Date(NOW.getTime() - 32 * 24 * 60 * 60 * 1_000).toISOString(),
    data: {
      email_id: "provider_email_01",
      tags: { hermes_kind: "email_otp", hermes_message_id: MESSAGE_ID },
    },
  });
  const malformedTag = JSON.stringify({
    type: "email.delivered",
    created_at: NOW.toISOString(),
    data: {
      email_id: "provider_email_01",
      tags: { hermes_kind: "email_otp", hermes_message_id: "not-a-uuid" },
    },
  });
  const oversized = JSON.stringify({
    type: "email.delivered",
    created_at: NOW.toISOString(),
    data: {
      email_id: "provider_email_01",
      tags: { hermes_kind: "email_otp", hermes_message_id: MESSAGE_ID },
      ignored: "x".repeat(65 * 1024),
    },
  });
  for (const body of [stale, malformedTag, oversized]) {
    const response = await invoke(
      new ResendWebhookController(SECRET, store([]), () => NOW),
      signedRequest(body),
    );
    assert.equal(response.status, 400);
    assert.deepEqual(response.json(), { error: "invalid_webhook" });
  }
});

test("valid events are retried by Resend when durable recording fails", async () => {
  const failingStore: EmailDeliveryEventStore = {
    record: async () => { throw new Error("database-private-diagnostic"); },
  };
  const response = await invoke(
    new ResendWebhookController(SECRET, failingStore, () => NOW),
    signedRequest(trackedBody("email.failed")),
  );
  assert.equal(response.status, 500);
  assert.deepEqual(response.json(), { error: "webhook_unavailable" });
  assert.equal(response.body.includes("database-private"), false);
});

test("temporarily unmatched Hermes events request a provider retry", async () => {
  const unmatched: EmailDeliveryEventStore = { record: async () => "unmatched" };
  const response = await invoke(
    new ResendWebhookController(SECRET, unmatched, () => NOW),
    signedRequest(trackedBody("email.delivered")),
  );
  assert.equal(response.status, 500);
  assert.deepEqual(response.json(), { error: "webhook_unavailable" });
});

function trackedBody(type: EmailDeliveryEvent["eventType"]): string {
  return JSON.stringify({
    type,
    created_at: NOW.toISOString(),
    data: {
      email_id: "provider_email_01",
      tags: { hermes_kind: "email_otp", hermes_message_id: MESSAGE_ID },
    },
  });
}

function signedRequest(body: string, signedBody = body): IncomingMessage {
  const signature = new Webhook(SECRET).sign(WEBHOOK_ID, NOW, signedBody);
  const rawHeaders = [
    "Content-Type", "application/json",
    "Svix-Id", WEBHOOK_ID,
    "Svix-Timestamp", String(Math.floor(NOW.getTime() / 1_000)),
    "Svix-Signature", signature,
  ];
  const request = Readable.from([Buffer.from(body)]) as IncomingMessage;
  request.method = "POST";
  request.headers = {
    "content-type": "application/json",
    "svix-id": WEBHOOK_ID,
    "svix-timestamp": String(Math.floor(NOW.getTime() / 1_000)),
    "svix-signature": signature,
  };
  request.rawHeaders = rawHeaders;
  return request;
}

function store(recorded: EmailDeliveryEvent[]): EmailDeliveryEventStore {
  return {
    record: async (event) => {
      recorded.push(event);
      return "applied";
    },
  };
}

async function invoke(
  controller: ResendWebhookController,
  request: IncomingMessage,
): Promise<MemoryResponse> {
  const response = new MemoryResponse();
  await controller.handle(request, response.asServerResponse());
  return response;
}

class MemoryResponse {
  status = 0;
  headers: Record<string, string> = {};
  body = "";

  asServerResponse(): ServerResponse {
    return {
      writeHead: (status: number, headers: Record<string, string>) => {
        this.status = status;
        this.headers = headers;
      },
      end: (body?: string) => { this.body = body ?? ""; },
    } as unknown as ServerResponse;
  }

  json(): unknown {
    return JSON.parse(this.body);
  }
}
