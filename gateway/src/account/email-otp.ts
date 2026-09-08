import { createHmac, randomInt } from "node:crypto";
import { domainToASCII } from "node:url";
import type { VerifiedExternalIdentity } from "./model.js";

export const EMAIL_OTP_CODE_DIGITS = 6;
export const EMAIL_OTP_EXPIRES_MS = 10 * 60 * 1_000;
export const EMAIL_OTP_RESEND_COOLDOWN_MS = 60 * 1_000;
export const EMAIL_OTP_MAX_ATTEMPTS = 5;

const EMAIL_OTP_PATTERN = /^\d{6}$/;
const DOMAIN_LABEL_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const LOCAL_PART_PATTERN = /^[\p{L}\p{N}!#$%&'*+/=?^_`{|}~.-]+$/u;

export interface EmailOtpIdentity {
  normalizedEmail: string;
  identity: VerifiedExternalIdentity;
}

/**
 * Owns the cryptographic boundary for email OTPs. Callers may send the plaintext code to the
 * transactional provider, but only these keyed derivatives may cross the persistence boundary.
 */
export class EmailOtpSecurity {
  private readonly key: Buffer;

  constructor(
    secret: string | Buffer,
    private readonly issuer: string,
    private readonly codeGenerator: () => number = () => randomInt(0, 1_000_000),
  ) {
    this.key = Buffer.isBuffer(secret) ? Buffer.from(secret) : Buffer.from(secret, "utf8");
    if (this.key.byteLength < 32) {
      throw new Error("ACCOUNT_EMAIL_OTP_HASH_KEY must contain at least 32 bytes");
    }
    const parsedIssuer = new URL(issuer);
    if (parsedIssuer.protocol !== "https:" || parsedIssuer.origin !== issuer || parsedIssuer.pathname !== "/") {
      throw new Error("email OTP issuer must be an exact HTTPS origin");
    }
  }

  issueCode(): string {
    const value = this.codeGenerator();
    if (!Number.isSafeInteger(value) || value < 0 || value >= 1_000_000) {
      throw new Error("email OTP code generator returned an out-of-range value");
    }
    return String(value).padStart(EMAIL_OTP_CODE_DIGITS, "0");
  }

  hashCode(challengeId: string, code: string): string | undefined {
    if (!EMAIL_OTP_PATTERN.test(code)) return undefined;
    return this.hash("code", `${challengeId}\u0000${code}`);
  }

  hashSource(source: string): string {
    return this.hash("source", source);
  }

  hashExchangeKey(challengeId: string, idempotencyKey: string): string {
    return this.hash("exchange", `${challengeId}\u0000${idempotencyKey}`);
  }

  resolveIdentity(email: string): EmailOtpIdentity {
    const normalizedEmail = normalizeEmailAddress(email);
    return {
      normalizedEmail,
      identity: {
        provider: "email_otp",
        issuer: this.issuer,
        subject: this.hash("subject", normalizedEmail),
        email: normalizedEmail,
      },
    };
  }

  private hash(context: "code" | "exchange" | "source" | "subject", value: string): string {
    return createHmac("sha256", this.key)
      .update(`email-otp-v1\u0000${context}\u0000${value}`, "utf8")
      .digest("hex");
  }
}

export function normalizeEmailAddress(input: string): string {
  const value = input.trim();
  if (value.length === 0 || Buffer.byteLength(value, "utf8") > 254
      || /[\u0000-\u0020\u007f]/.test(value)) {
    throw new Error("invalid_email");
  }

  const separator = value.lastIndexOf("@");
  if (separator <= 0 || separator !== value.indexOf("@") || separator === value.length - 1) {
    throw new Error("invalid_email");
  }
  const rawLocal = value.slice(0, separator);
  const rawDomain = value.slice(separator + 1);
  if (Buffer.byteLength(rawLocal, "utf8") > 64 || !LOCAL_PART_PATTERN.test(rawLocal)
      || rawLocal.startsWith(".")
      || rawLocal.endsWith(".") || rawLocal.includes("..")) {
    throw new Error("invalid_email");
  }

  const domain = domainToASCII(rawDomain).toLowerCase();
  if (domain.length === 0 || domain.length > 253 || domain.endsWith(".")
      || domain.split(".").some((label) => !DOMAIN_LABEL_PATTERN.test(label))) {
    throw new Error("invalid_email");
  }

  const local = rawLocal.replace(/[A-Z]/g, (character) => character.toLowerCase());
  return `${local}@${domain}`;
}
