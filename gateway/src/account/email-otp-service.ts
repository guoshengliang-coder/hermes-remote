import { randomUUID, timingSafeEqual } from "node:crypto";
import { accountErrors, type AccountPlatform, type VerifiedExternalIdentity } from "./model.js";
import {
  EMAIL_OTP_EXPIRES_MS,
  EMAIL_OTP_MAX_ATTEMPTS,
  EMAIL_OTP_RESEND_COOLDOWN_MS,
  EmailOtpSecurity,
} from "./email-otp.js";
import type { EmailProviderReceipt, EmailProviderSubmission } from "./email-delivery.js";

export type EmailOtpPurpose = "sign_in" | "link_identity" | "reauthenticate";
export type EmailOtpPlatform = AccountPlatform | "web";

export interface EmailOtpChallengeRecord {
  id: string;
  purpose: EmailOtpPurpose;
  emailLookupHash: string;
  codeHash: string;
  requesterSourceHash: string;
  requesterPlatform: EmailOtpPlatform;
  clientInstallationId?: string;
  createdAt: Date;
  expiresAt: Date;
  maxAttempts: number;
}

export type EmailOtpChallengeCreationResult =
  | { status: "created" }
  | { status: "suppressed" }
  | { status: "cooldown"; challengeId: string; expiresAt: Date; resendAfter: Date }
  | { status: "rate_limited" };

export type EmailOtpVerificationResult =
  | { status: "verified" | "replayed" }
  | { status: "invalid" | "expired" | "consumed" | "delivery_failed" | "attempts_exceeded" };

export interface EmailOtpChallengeStore {
  createChallenge(
    challenge: EmailOtpChallengeRecord,
    policy: {
      cooldownMs: number;
      rateWindowMs: number;
      maxRequestsPerEmail: number;
      maxRequestsPerSource: number;
    },
  ): Promise<EmailOtpChallengeCreationResult>;
  markDelivery(challengeId: string, submission: EmailProviderSubmission, now: Date): Promise<void>;
  verifyChallenge(input: {
    challengeId: string;
    purpose: EmailOtpPurpose;
    emailLookupHash: string;
    candidateCodeHash?: string;
    exchangeKeyHash: string;
    requesterPlatform: EmailOtpPlatform;
    clientInstallationId?: string;
    now: Date;
  }): Promise<EmailOtpVerificationResult>;
}

export interface TransactionalEmailSender {
  sendLoginCode(input: {
    messageId: string;
    recipient: string;
    code: string;
    purpose: EmailOtpPurpose;
    expiresInMinutes: number;
  }): Promise<EmailProviderReceipt>;
}

export interface EmailOtpChallengeResponse {
  challengeId: string;
  expiresAt: string;
  resendAfter: string;
}

export class EmailOtpService {
  constructor(
    private readonly store: EmailOtpChallengeStore,
    private readonly sender: TransactionalEmailSender,
    private readonly security: EmailOtpSecurity,
    private readonly now: () => Date = () => new Date(),
    private readonly policy = {
      rateWindowMs: 15 * 60 * 1_000,
      maxRequestsPerEmail: 5,
      maxRequestsPerSource: 20,
    },
  ) {}

  async requestChallenge(input: {
    email: string;
    purpose: EmailOtpPurpose;
    platform: EmailOtpPlatform;
    source: string;
    clientInstallationId?: string;
  }): Promise<EmailOtpChallengeResponse> {
    const resolved = this.resolveIdentity(input.email);
    const now = this.now();
    const challengeId = randomUUID();
    const code = this.security.issueCode();
    const expiresAt = new Date(now.getTime() + EMAIL_OTP_EXPIRES_MS);
    const created = await this.store.createChallenge({
      id: challengeId,
      purpose: input.purpose,
      emailLookupHash: resolved.identity.subject,
      codeHash: requiredCodeHash(this.security.hashCode(challengeId, code)),
      requesterSourceHash: this.security.hashSource(input.source),
      requesterPlatform: input.platform,
      ...(input.clientInstallationId ? { clientInstallationId: input.clientInstallationId } : {}),
      createdAt: now,
      expiresAt,
      maxAttempts: EMAIL_OTP_MAX_ATTEMPTS,
    }, {
      cooldownMs: EMAIL_OTP_RESEND_COOLDOWN_MS,
      ...this.policy,
    });

    if (created.status === "rate_limited") throw accountErrors.rateLimited();
    if (created.status === "cooldown") {
      return response(created.challengeId, created.expiresAt, created.resendAfter);
    }
    if (created.status === "suppressed") {
      return response(
        challengeId,
        expiresAt,
        new Date(now.getTime() + EMAIL_OTP_RESEND_COOLDOWN_MS),
      );
    }

    try {
      const receipt = await this.sender.sendLoginCode({
        messageId: challengeId,
        recipient: resolved.normalizedEmail,
        code,
        purpose: input.purpose,
        expiresInMinutes: EMAIL_OTP_EXPIRES_MS / 60_000,
      });
      await this.store.markDelivery(challengeId, {
        status: "accepted",
        providerMessageId: receipt.providerMessageId,
      }, this.now());
    } catch {
      await this.store.markDelivery(challengeId, { status: "failed" }, this.now()).catch(() => {});
      throw accountErrors.emailDeliveryFailed();
    }
    return response(
      challengeId,
      expiresAt,
      new Date(now.getTime() + EMAIL_OTP_RESEND_COOLDOWN_MS),
    );
  }

  async verifyChallenge(input: {
    challengeId: string;
    email: string;
    code: string;
    purpose: EmailOtpPurpose;
    platform: EmailOtpPlatform;
    clientInstallationId?: string;
    exchangeIdempotencyKey: string;
  }): Promise<VerifiedExternalIdentity> {
    const resolved = this.resolveIdentity(input.email);
    const result = await this.store.verifyChallenge({
      challengeId: input.challengeId,
      purpose: input.purpose,
      emailLookupHash: resolved.identity.subject,
      candidateCodeHash: this.security.hashCode(input.challengeId, input.code),
      exchangeKeyHash: this.security.hashExchangeKey(
        input.challengeId,
        input.exchangeIdempotencyKey,
      ),
      requesterPlatform: input.platform,
      ...(input.clientInstallationId ? { clientInstallationId: input.clientInstallationId } : {}),
      now: this.now(),
    });
    if (result.status !== "verified" && result.status !== "replayed") {
      throw accountErrors.invalidEmailCode();
    }
    return resolved.identity;
  }

  private resolveIdentity(email: string) {
    try {
      return this.security.resolveIdentity(email);
    } catch {
      throw accountErrors.invalidRequest("email must be a valid mailbox address.");
    }
  }
}

export function secureHashEquals(left: string, right: string | undefined): boolean {
  if (!right || !/^[0-9a-f]{64}$/.test(left) || !/^[0-9a-f]{64}$/.test(right)) return false;
  return timingSafeEqual(Buffer.from(left, "hex"), Buffer.from(right, "hex"));
}

function response(challengeId: string, expiresAt: Date, resendAfter: Date): EmailOtpChallengeResponse {
  return {
    challengeId,
    expiresAt: expiresAt.toISOString(),
    resendAfter: resendAfter.toISOString(),
  };
}

function requiredCodeHash(value: string | undefined): string {
  if (!value) throw new Error("generated email OTP did not match its format");
  return value;
}
