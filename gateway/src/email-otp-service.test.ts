import assert from "node:assert/strict";
import { test } from "node:test";
import { EmailOtpSecurity } from "./account/email-otp.js";
import {
  EmailOtpService,
  secureHashEquals,
  type EmailOtpChallengeRecord,
  type EmailOtpChallengeStore,
  type EmailOtpVerificationResult,
} from "./account/email-otp-service.js";

const SECRET = "test-email-otp-service-secret-with-thirty-two-bytes";
const START = new Date("2026-09-07T00:00:00.000Z");

class MemoryChallengeStore implements EmailOtpChallengeStore {
  readonly challenges = new Map<string, EmailOtpChallengeRecord & {
    delivery: "pending" | "sent" | "failed" | "suppressed";
    attempts: number;
    consumed: boolean;
    exchangeKeyHash?: string;
    providerMessageId?: string;
  }>();

  constructor(private readonly suppressCreation = false) {}

  async createChallenge(challenge: EmailOtpChallengeRecord) {
    this.challenges.set(challenge.id, {
      ...challenge,
      delivery: this.suppressCreation ? "suppressed" : "pending",
      attempts: 0,
      consumed: false,
    });
    return { status: this.suppressCreation ? "suppressed" as const : "created" as const };
  }

  async markDelivery(
    challengeId: string,
    submission: Parameters<EmailOtpChallengeStore["markDelivery"]>[1],
  ): Promise<void> {
    const challenge = this.challenges.get(challengeId);
    assert(challenge);
    challenge.delivery = submission.status === "accepted" ? "sent" : "failed";
    if (submission.status === "accepted") challenge.providerMessageId = submission.providerMessageId;
  }

  async verifyChallenge(input: {
    challengeId: string;
    emailLookupHash: string;
    candidateCodeHash?: string;
    exchangeKeyHash: string;
    requesterPlatform: "android" | "macos" | "web";
    clientInstallationId?: string;
    now: Date;
  }): Promise<EmailOtpVerificationResult> {
    const challenge = this.challenges.get(input.challengeId);
    if (!challenge || challenge.emailLookupHash !== input.emailLookupHash
        || challenge.requesterPlatform !== input.requesterPlatform
        || challenge.clientInstallationId !== input.clientInstallationId) return { status: "invalid" };
    if (challenge.delivery !== "sent") return { status: "delivery_failed" };
    if (challenge.consumed) {
      return { status: challenge.exchangeKeyHash === input.exchangeKeyHash ? "replayed" : "consumed" };
    }
    if (challenge.expiresAt.getTime() <= input.now.getTime()) return { status: "expired" };
    if (!secureHashEquals(challenge.codeHash, input.candidateCodeHash)) {
      challenge.attempts += 1;
      return { status: challenge.attempts >= challenge.maxAttempts ? "attempts_exceeded" : "invalid" };
    }
    challenge.consumed = true;
    challenge.exchangeKeyHash = input.exchangeKeyHash;
    return { status: "verified" };
  }
}

test("email OTP service exposes plaintext only to the mail adapter and verifies once", async () => {
  const store = new MemoryChallengeStore();
  const deliveries: Array<{ recipient: string; code: string }> = [];
  const service = new EmailOtpService(
    store,
    { sendLoginCode: async ({ recipient, code }) => {
      deliveries.push({ recipient, code });
      return { providerMessageId: "otp-provider-id" };
    } },
    new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 123456),
    () => START,
  );

  const challenge = await service.requestChallenge({
    email: "Person+test@Example.com",
    purpose: "sign_in",
    platform: "web",
    source: "203.0.113.10",
  });
  assert.deepEqual(deliveries, [{ recipient: "person+test@example.com", code: "123456" }]);
  assert.equal(challenge.expiresAt, "2026-09-07T00:10:00.000Z");
  assert.equal(challenge.resendAfter, "2026-09-07T00:01:00.000Z");
  assert.equal(store.challenges.get(challenge.challengeId)?.providerMessageId, "otp-provider-id");

  const persisted = JSON.stringify([...store.challenges.values()]);
  for (const plaintext of ["Person+test@Example.com", "person+test@example.com", "123456", "203.0.113.10"]) {
    assert.equal(persisted.includes(plaintext), false);
  }

  const identity = await service.verifyChallenge({
    challengeId: challenge.challengeId,
    email: "person+test@example.com",
    code: "123456",
    purpose: "sign_in",
    platform: "web",
    exchangeIdempotencyKey: "exchange-request-1",
  });
  assert.equal(identity.provider, "email_otp");
  assert.equal(identity.email, "person+test@example.com");
  assert.deepEqual(await service.verifyChallenge({
    challengeId: challenge.challengeId,
    email: "person+test@example.com",
    code: "123456",
    purpose: "sign_in",
    platform: "web",
    exchangeIdempotencyKey: "exchange-request-1",
  }), identity);
  await assert.rejects(service.verifyChallenge({
    challengeId: challenge.challengeId,
    email: "person+test@example.com",
    code: "123456",
    purpose: "sign_in",
    platform: "web",
    exchangeIdempotencyKey: "exchange-request-2",
  }), hasCode("HR-AUTH-009"));
});

test("email OTP service invalidates an undelivered challenge without leaking provider details", async () => {
  const store = new MemoryChallengeStore();
  const service = new EmailOtpService(
    store,
    { sendLoginCode: async () => { throw new Error("provider-secret-response"); } },
    new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 654321),
    () => START,
  );
  await assert.rejects(
    service.requestChallenge({
      email: "person@example.com",
      purpose: "sign_in",
      platform: "macos",
      source: "198.51.100.4",
    }),
    (error: unknown) => hasCode("HR-AUTH-010")(error)
      && !(error as Error).message.includes("provider-secret"),
  );
  assert.equal([...store.challenges.values()][0].delivery, "failed");
});

test("email OTP service returns the neutral challenge contract without mailing a deleting identity", async () => {
  const store = new MemoryChallengeStore(true);
  let deliveryAttempts = 0;
  const service = new EmailOtpService(
    store,
    { sendLoginCode: async () => {
      deliveryAttempts += 1;
      return { providerMessageId: "must-not-be-used" };
    } },
    new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 222222),
    () => START,
  );

  const challenge = await service.requestChallenge({
    email: "deleting@example.com",
    purpose: "sign_in",
    platform: "web",
    source: "192.0.2.8",
  });
  assert.equal(deliveryAttempts, 0);
  assert.equal(challenge.expiresAt, "2026-09-07T00:10:00.000Z");
  assert.equal(challenge.resendAfter, "2026-09-07T00:01:00.000Z");
  await assert.rejects(service.verifyChallenge({
    challengeId: challenge.challengeId,
    email: "deleting@example.com",
    code: "222222",
    purpose: "sign_in",
    platform: "web",
    exchangeIdempotencyKey: "suppressed-exchange",
  }), hasCode("HR-AUTH-009"));
});

test("email OTP service rejects invalid mailbox and code shapes with stable errors", async () => {
  const store = new MemoryChallengeStore();
  const service = new EmailOtpService(
    store,
    { sendLoginCode: async () => ({ providerMessageId: "otp-provider-id" }) },
    new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 111111),
    () => START,
  );
  await assert.rejects(
    service.requestChallenge({
      email: "not-an-email",
      purpose: "sign_in",
      platform: "web",
      source: "192.0.2.1",
    }),
    hasCode("HR-ACCOUNT-004"),
  );
  const challenge = await service.requestChallenge({
    email: "person@example.com",
    purpose: "sign_in",
    platform: "web",
    source: "192.0.2.1",
  });
  await assert.rejects(
    service.verifyChallenge({
      challengeId: challenge.challengeId,
      email: "person@example.com",
      code: "11x111",
      purpose: "sign_in",
      platform: "web",
      exchangeIdempotencyKey: "invalid-code-request",
    }),
    hasCode("HR-AUTH-009"),
  );
});

function hasCode(code: string): (error: unknown) => boolean {
  return (error) => typeof error === "object" && error !== null && "code" in error
    && (error as { code: unknown }).code === code;
}
