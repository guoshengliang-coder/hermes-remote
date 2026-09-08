import assert from "node:assert/strict";
import type { IncomingMessage, ServerResponse } from "node:http";
import { test } from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import type { AccountService, AccountSessionResponse } from "./account/account-service.js";
import {
  EmailOtpService,
  type EmailOtpChallengeStore,
} from "./account/email-otp-service.js";
import { EmailOtpSecurity } from "./account/email-otp.js";
import type { VerifiedExternalIdentity } from "./account/model.js";

const INSTALLATION_ID = "fdaed25e-f143-4e3c-b92b-0d881df13630";
const CHALLENGE_ID = "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea";
const IDEMPOTENCY_KEY = "9e0a2044-94fc-44d0-a81c-498ea343d085";

test("email OTP capability and routes remain independently default-off", async () => {
  const capabilities = new MemoryResponse();
  await new AccountHttpController(true, {} as AccountService).handle(
    memoryRequest("GET"),
    capabilities.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.deepEqual(
    (capabilities.json() as { accountAuth: { providers: string[] } }).accountAuth.providers,
    [],
  );

  const disabled = new MemoryResponse();
  await new AccountHttpController(true, {} as AccountService).handle(
    memoryRequest("POST"),
    disabled.asServerResponse(),
    new URL("http://localhost/v2/auth/email/challenges"),
  );
  assert.equal(disabled.status, 503);
  assert.equal((disabled.json() as { error: { code: string } }).error.code, "HR-AUTH-011");
});

test("email OTP HTTP flow validates bounded inputs and exchanges an identity for a session", async () => {
  const calls: Array<{ operation: string; input: unknown }> = [];
  const identity: VerifiedExternalIdentity = {
    provider: "email_otp",
    issuer: "https://mrlgs.net",
    subject: "a".repeat(64),
    email: "person@example.com",
  };
  const emailOtp = {
    requestChallenge: async (input: unknown) => {
      calls.push({ operation: "challenge", input });
      return {
        challengeId: CHALLENGE_ID,
        expiresAt: "2026-09-07T00:10:00.000Z",
        resendAfter: "2026-09-07T00:01:00.000Z",
      };
    },
    verifyChallenge: async (input: unknown) => {
      calls.push({ operation: "verify", input });
      return identity;
    },
  } as EmailOtpService;
  const session: AccountSessionResponse = {
    account: { id: "account-1", email: "person@example.com" },
    installation: {
      id: "installation-1",
      kind: "desktop",
      platform: "macos",
      displayName: "Mac mini",
    },
    session: {
      accessToken: `hga_${"a".repeat(43)}`,
      accessExpiresAt: "2026-09-07T00:15:00.000Z",
      refreshToken: `hgr_${"b".repeat(43)}`,
      refreshExpiresAt: "2026-10-07T00:00:00.000Z",
    },
  };
  const accountService = {
    exchangeEmailIdentity: async (receivedIdentity: VerifiedExternalIdentity, input: unknown) => {
      calls.push({ operation: "exchange", input: { receivedIdentity, input } });
      return session;
    },
  } as AccountService;
  const controller = new AccountHttpController(true, accountService, {
    emailOtpEnabled: true,
    emailOtpService: emailOtp,
    trustLoopbackProxy: true,
  });

  const capabilities = new MemoryResponse();
  await controller.handle(
    memoryRequest("GET"),
    capabilities.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.deepEqual(
    (capabilities.json() as { accountAuth: { providers: string[] } }).accountAuth.providers,
    ["email_otp"],
  );

  const challenge = new MemoryResponse();
  await controller.handle(
    memoryRequest(
      "POST",
      { "content-type": "application/json", "x-forwarded-for": "198.51.100.12" },
      JSON.stringify({
        email: "Person@Example.com",
        platform: "macos",
        clientInstallationId: INSTALLATION_ID,
      }),
    ),
    challenge.asServerResponse(),
    new URL("http://localhost/v2/auth/email/challenges"),
  );
  assert.equal(challenge.status, 202);
  assert.equal(
    (challenge.json() as { challenge: { challengeId: string } }).challenge.challengeId,
    CHALLENGE_ID,
  );
  assert.deepEqual(calls[0], {
    operation: "challenge",
    input: {
      email: "Person@Example.com",
      purpose: "sign_in",
      platform: "macos",
      source: "198.51.100.12",
      clientInstallationId: INSTALLATION_ID,
    },
  });

  const exchange = new MemoryResponse();
  await controller.handle(
    memoryRequest(
      "POST",
      { "content-type": "application/json", "idempotency-key": IDEMPOTENCY_KEY },
      JSON.stringify({
        challengeId: CHALLENGE_ID,
        email: "person@example.com",
        code: "012345",
        platform: "macos",
        clientInstallationId: INSTALLATION_ID,
        displayName: "Mac mini",
        appVersion: "0.4.0",
      }),
    ),
    exchange.asServerResponse(),
    new URL("http://localhost/v2/auth/email/exchange"),
  );
  assert.equal(exchange.status, 200);
  assert.deepEqual(exchange.json(), session);
  assert.deepEqual(calls[1], {
    operation: "verify",
    input: {
      challengeId: CHALLENGE_ID,
      email: "person@example.com",
      code: "012345",
      purpose: "sign_in",
      platform: "macos",
      clientInstallationId: INSTALLATION_ID,
      exchangeIdempotencyKey: IDEMPOTENCY_KEY,
    },
  });
  assert.equal(calls[2].operation, "exchange");
});

test("email OTP HTTP challenge stays neutral when delivery is suppressed for deletion", async () => {
  let deliveryAttempts = 0;
  const emailOtp = new EmailOtpService(
    {
      createChallenge: async () => ({ status: "suppressed" }),
      markDelivery: async () => {
        assert.fail("a suppressed challenge must not reach delivery persistence");
      },
      verifyChallenge: async () => ({ status: "delivery_failed" }),
    } satisfies EmailOtpChallengeStore,
    {
      sendLoginCode: async () => {
        deliveryAttempts += 1;
        return { providerMessageId: "must-not-be-used" };
      },
    },
    new EmailOtpSecurity(
      "http-email-otp-test-secret-with-at-least-thirty-two-bytes",
      "https://mrlgs.net",
      () => 222222,
    ),
    () => new Date("2026-09-07T00:00:00.000Z"),
  );
  const controller = new AccountHttpController(true, {} as AccountService, {
    emailOtpEnabled: true,
    emailOtpService: emailOtp,
    trustLoopbackProxy: true,
  });

  const challenge = new MemoryResponse();
  await controller.handle(
    memoryRequest(
      "POST",
      { "content-type": "application/json", "x-forwarded-for": "198.51.100.13" },
      JSON.stringify({
        email: "Deleting@Example.com",
        platform: "macos",
        clientInstallationId: INSTALLATION_ID,
      }),
    ),
    challenge.asServerResponse(),
    new URL("http://localhost/v2/auth/email/challenges"),
  );

  assert.equal(challenge.status, 202);
  assert.equal(deliveryAttempts, 0);
  assert.deepEqual(Object.keys(challenge.json() as object), ["challenge"]);
  assert.deepEqual(Object.keys(
    (challenge.json() as { challenge: Record<string, unknown> }).challenge,
  ).sort(), ["challengeId", "expiresAt", "resendAfter"]);
  assert.equal(challenge.body.includes("suppressed"), false);
  assert.equal(challenge.body.includes("delet"), false);
  assert.equal(challenge.body.includes("Deleting@Example.com"), false);
});

function memoryRequest(
  method: string,
  headers: Record<string, string> = {},
  body = "",
  remoteAddress = "127.0.0.1",
): IncomingMessage {
  return {
    method,
    headers,
    socket: { remoteAddress },
    async *[Symbol.asyncIterator]() {
      if (body.length > 0) yield Buffer.from(body);
    },
  } as unknown as IncomingMessage;
}

class MemoryResponse {
  status = 0;
  headers: Record<string, string> = {};
  body = "";
  writableEnded = false;

  asServerResponse(): ServerResponse {
    return this as unknown as ServerResponse;
  }

  writeHead(status: number, headers: Record<string, string>): this {
    this.status = status;
    this.headers = headers;
    return this;
  }

  end(value?: string): this {
    this.body = value ?? "";
    this.writableEnded = true;
    return this;
  }

  json(): unknown {
    return JSON.parse(this.body);
  }
}
