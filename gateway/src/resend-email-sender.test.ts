import assert from "node:assert/strict";
import { test } from "node:test";
import { ResendEmailSender } from "./account/resend-email-sender.js";

const API_KEY = "re_test_key_that_is_long_enough";
const MESSAGE_ID = "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea";

test("Resend adapter sends a bounded plaintext OTP with provider idempotency", async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const sender = new ResendEmailSender(
    API_KEY,
    "Hermes GO <login@auth.mrlgs.net>",
    "hermes-go-gateway/0.4.0",
    async (input, init) => {
      requests.push({ url: String(input), init });
      return new Response(JSON.stringify({ id: "49a3999c-0ce1-4ea6-ab68-afcd6dc2e794" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    },
  );

  const receipt = await sender.sendLoginCode({
    messageId: MESSAGE_ID,
    recipient: "person@example.com",
    code: "012345",
    purpose: "sign_in",
    expiresInMinutes: 10,
  });

  assert.equal(requests.length, 1);
  assert.deepEqual(receipt, { providerMessageId: "49a3999c-0ce1-4ea6-ab68-afcd6dc2e794" });
  assert.equal(requests[0].url, "https://api.resend.com/emails");
  assert.equal(requests[0].init?.method, "POST");
  const headers = requests[0].init?.headers as Record<string, string>;
  assert.equal(headers.authorization, `Bearer ${API_KEY}`);
  assert.equal(headers["idempotency-key"], `email-otp/${MESSAGE_ID}`);
  assert.equal(headers["user-agent"], "hermes-go-gateway/0.4.0");
  const body = JSON.parse(String(requests[0].init?.body)) as Record<string, unknown>;
  assert.deepEqual(body.to, ["person@example.com"]);
  assert.equal(body.from, "Hermes GO <login@auth.mrlgs.net>");
  assert.deepEqual(body.tags, [
    { name: "hermes_kind", value: "email_otp" },
    { name: "hermes_message_id", value: MESSAGE_ID },
  ]);
  assert.equal(typeof body.html, "undefined");
  assert.match(String(body.text), /012345/);
  assert.match(String(body.text), /10 minutes/);
});

test("Resend adapter collapses provider bodies and transport failures", async () => {
  const providerSecret = "provider-secret-diagnostic";
  for (const fetchImpl of [
    async () => new Response(providerSecret, { status: 422 }),
    async () => { throw new Error(providerSecret); },
    async () => new Response(JSON.stringify({ unexpected: providerSecret }), { status: 200 }),
  ]) {
    const sender = new ResendEmailSender(
      API_KEY,
      "Hermes GO <login@auth.mrlgs.net>",
      "hermes-go-gateway/test",
      fetchImpl,
    );
    await assert.rejects(
      sender.sendLoginCode({
        messageId: MESSAGE_ID,
        recipient: "person@example.com",
        code: "123456",
        purpose: "sign_in",
        expiresInMinutes: 10,
      }),
      (error: unknown) => error instanceof Error
        && error.message === "transactional_email_request_failed"
        && !error.message.includes(providerSecret),
    );
  }
});

test("Resend adapter sends an idempotent bilingual whole-device warning without HTML", async () => {
  const requests: Array<{ init?: RequestInit }> = [];
  const sender = new ResendEmailSender(
    API_KEY,
    "Hermes GO <login@auth.mrlgs.net>",
    "hermes-go-gateway/0.4.0",
    async (_input, init) => {
      requests.push({ init });
      return new Response(JSON.stringify({ id: "share-provider-id" }), { status: 200 });
    },
  );
  const receipt = await sender.sendDeviceShareInvitation({
    messageId: MESSAGE_ID,
    recipient: "guest@example.com",
    ownerDisplayName: "Owner",
    deviceDisplayName: "Office Mac",
    acceptUrl: "https://accounts.example.com/account#share-invitation=hsi_redacted",
    expiresInHours: 72,
  });
  const headers = requests[0].init?.headers as Record<string, string>;
  const body = JSON.parse(String(requests[0].init?.body)) as Record<string, unknown>;
  assert.deepEqual(receipt, { providerMessageId: "share-provider-id" });
  assert.equal(headers["idempotency-key"], `device-share/${MESSAGE_ID}`);
  assert.equal(typeof body.html, "undefined");
  assert.deepEqual(body.tags, [
    { name: "hermes_kind", value: "device_share" },
    { name: "hermes_message_id", value: MESSAGE_ID },
  ]);
  assert.match(String(body.subject), /Whole-device sharing invitation/);
  assert.match(String(body.text), /整台设备/);
  assert.match(String(body.text), /existing sessions, files/);
  assert.match(String(body.text), /72 hours/);
});

test("Resend adapter rejects unsafe configuration and malformed message input", async () => {
  assert.throws(
    () => new ResendEmailSender("bad-key", "login@example.com", "hermes-go/test"),
    /ACCOUNT_RESEND_API_KEY/,
  );
  assert.throws(
    () => new ResendEmailSender(API_KEY, "login@example.com\nBcc: victim@example.com", "hermes-go/test"),
    /ACCOUNT_EMAIL_FROM/,
  );
  const sender = new ResendEmailSender(
    API_KEY,
    "login@example.com",
    "hermes-go/test",
    async () => { throw new Error("must not send"); },
  );
  await assert.rejects(
    sender.sendLoginCode({
      messageId: "not-a-uuid",
      recipient: "person@example.com",
      code: "123456",
      purpose: "sign_in",
      expiresInMinutes: 10,
    }),
    /transactional email input is invalid/,
  );
});
