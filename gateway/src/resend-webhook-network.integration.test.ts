import assert from "node:assert/strict";
import {
  createServer,
  request as httpRequest,
  type IncomingHttpHeaders,
  type Server,
} from "node:http";
import { test } from "node:test";
import { Webhook } from "svix";
import type { EmailDeliveryEvent, EmailDeliveryEventStore } from "./account/email-delivery.js";
import { ResendWebhookController } from "./account/resend-webhook-controller.js";
import { GatewayHttpRouter } from "./gateway-http-router.js";

const SECRET = `whsec_${Buffer.from("resend-network-test-secret-32bytes").toString("base64")}`;
const NOW = new Date();
const MESSAGE_ID = "c4766a37-b1d6-42b4-b6de-2a6ebd744c15";

test("Resend callback preserves raw HTTP body and duplicate-header boundaries", {
  skip: process.env.RUN_NETWORK_TESTS !== "1"
    ? "set RUN_NETWORK_TESTS=1 for loopback integration"
    : false,
}, async () => {
  const recorded: EmailDeliveryEvent[] = [];
  const controller = new ResendWebhookController(SECRET, store(recorded), () => NOW);
  const router = new GatewayHttpRouter({
    serverRelease: { handle: async () => false },
    resendWebhook: controller,
    accountController: { handle: async () => { throw new Error("unexpected account route"); } },
  } as never);
  const server = createServer((incoming, response) => {
    void router.handle(incoming, response).catch(() => {
      if (!response.headersSent) response.writeHead(500);
      response.end();
    });
  });

  try {
    const port = await listen(server);
    const body = trackedBody();
    const validHeaders = signedHeaders("evt_network_01", body);
    const accepted = await post(port, body, validHeaders);
    assert.equal(accepted.status, 200);
    assert.deepEqual(JSON.parse(accepted.body), { received: true });
    assert.equal(recorded.length, 1);
    assert.equal(recorded[0].webhookId, "evt_network_01");

    const tampered = await post(port, `${body} `, validHeaders);
    assert.equal(tampered.status, 400);
    assert.deepEqual(JSON.parse(tampered.body), { error: "invalid_webhook" });

    const signature = new Webhook(SECRET).sign("evt_network_02", NOW, body);
    const duplicateHeaders = [
      "Host", `127.0.0.1:${port}`,
      "Content-Type", "application/json",
      "Content-Length", String(Buffer.byteLength(body)),
      "Svix-Id", "evt_network_02",
      "Svix-Timestamp", String(Math.floor(NOW.getTime() / 1_000)),
      "Svix-Signature", signature,
      "Svix-Signature", signature,
    ];
    const duplicate = await post(port, body, duplicateHeaders);
    assert.equal(duplicate.status, 400);
    assert.deepEqual(JSON.parse(duplicate.body), { error: "invalid_webhook" });
    assert.equal(recorded.length, 1);
  } finally {
    await close(server);
  }
});

function trackedBody(): string {
  return JSON.stringify({
    type: "email.delivered",
    created_at: NOW.toISOString(),
    data: {
      email_id: "provider_network_01",
      tags: { hermes_kind: "email_otp", hermes_message_id: MESSAGE_ID },
    },
  });
}

function signedHeaders(webhookId: string, body: string): IncomingHttpHeaders {
  return {
    "content-type": "application/json",
    "svix-id": webhookId,
    "svix-timestamp": String(Math.floor(NOW.getTime() / 1_000)),
    "svix-signature": new Webhook(SECRET).sign(webhookId, NOW, body),
  };
}

function store(recorded: EmailDeliveryEvent[]): EmailDeliveryEventStore {
  return {
    record: async (event) => {
      recorded.push(event);
      return "applied";
    },
  };
}

function listen(server: Server): Promise<number> {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      const address = server.address();
      if (!address || typeof address === "string") {
        reject(new Error("unable to determine loopback port"));
        return;
      }
      resolve(address.port);
    });
  });
}

function post(
  port: number,
  body: string,
  headers: IncomingHttpHeaders | readonly string[],
): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const outgoing = httpRequest({
      host: "127.0.0.1",
      port,
      method: "POST",
      path: "/v2/webhooks/resend",
      headers,
    }, (response) => {
      const chunks: Buffer[] = [];
      response.on("data", (chunk: Buffer) => chunks.push(Buffer.from(chunk)));
      response.on("end", () => resolve({
        status: response.statusCode ?? 0,
        body: Buffer.concat(chunks).toString("utf8"),
      }));
    });
    outgoing.once("error", reject);
    outgoing.end(body);
  });
}

function close(server: Server): Promise<void> {
  if (!server.listening) return Promise.resolve();
  return new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
  });
}
