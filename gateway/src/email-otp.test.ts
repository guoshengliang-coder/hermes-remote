import assert from "node:assert/strict";
import { test } from "node:test";
import {
  EMAIL_OTP_CODE_DIGITS,
  EMAIL_OTP_EXPIRES_MS,
  EMAIL_OTP_MAX_ATTEMPTS,
  EMAIL_OTP_RESEND_COOLDOWN_MS,
  EmailOtpSecurity,
  normalizeEmailAddress,
} from "./account/email-otp.js";

const SECRET = "test-email-otp-secret-with-at-least-thirty-two-bytes";

test("email normalization is conservative and never applies provider-specific aliases", () => {
  assert.equal(
    normalizeEmailAddress("  User.Name+work@BÜCHER.Example  "),
    "user.name+work@xn--bcher-kva.example",
  );
  assert.equal(normalizeEmailAddress("user.name@gmail.com"), "user.name@gmail.com");
  assert.equal(normalizeEmailAddress("username@gmail.com"), "username@gmail.com");
  assert.notEqual(
    normalizeEmailAddress("user.name@gmail.com"),
    normalizeEmailAddress("username@gmail.com"),
  );

  for (const invalid of [
    "",
    "missing-at.example",
    "two@@example.com",
    ".leading@example.com",
    "trailing.@example.com",
    "double..dot@example.com",
    "space in@example.com",
    "person@example..com",
    "person@-example.com",
  ]) {
    assert.throws(() => normalizeEmailAddress(invalid), /invalid_email/);
  }
});

test("email OTP security emits six digits and persists only context-bound hashes", () => {
  const security = new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 42);
  const code = security.issueCode();
  assert.equal(code, "000042");
  assert.equal(code.length, EMAIL_OTP_CODE_DIGITS);

  const first = security.hashCode("challenge-a", code);
  assert.match(first ?? "", /^[0-9a-f]{64}$/);
  assert.equal(first?.includes(code), false);
  assert.notEqual(first, security.hashCode("challenge-b", code));
  assert.equal(security.hashCode("challenge-a", "12345"), undefined);
  assert.equal(security.hashCode("challenge-a", "12345x"), undefined);
  assert.notEqual(security.hashSource("203.0.113.10"), security.hashSource("203.0.113.11"));
});

test("email identity uses a keyed subject and cannot collide with Google by matching email", () => {
  const security = new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 123456);
  const resolved = security.resolveIdentity("Person@Example.com");
  assert.deepEqual(resolved, {
    normalizedEmail: "person@example.com",
    identity: {
      provider: "email_otp",
      issuer: "https://mrlgs.net",
      subject: resolved.identity.subject,
      email: "person@example.com",
    },
  });
  assert.match(resolved.identity.subject, /^[0-9a-f]{64}$/);
  assert.equal(resolved.identity.subject.includes("person@example.com"), false);
  assert.deepEqual(
    new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 123456)
      .resolveIdentity("person@example.com"),
    resolved,
  );
  assert.notEqual(
    new EmailOtpSecurity(`${SECRET}-different`, "https://mrlgs.net", () => 123456)
      .resolveIdentity("person@example.com").identity.subject,
    resolved.identity.subject,
  );
});

test("email OTP release policy remains explicit", () => {
  assert.equal(EMAIL_OTP_EXPIRES_MS, 10 * 60 * 1_000);
  assert.equal(EMAIL_OTP_RESEND_COOLDOWN_MS, 60 * 1_000);
  assert.equal(EMAIL_OTP_MAX_ATTEMPTS, 5);
  assert.throws(
    () => new EmailOtpSecurity("too-short", "https://mrlgs.net"),
    /ACCOUNT_EMAIL_OTP_HASH_KEY/,
  );
  assert.throws(
    () => new EmailOtpSecurity(SECRET, "http://mrlgs.net"),
    /exact HTTPS origin/,
  );
  assert.throws(
    () => new EmailOtpSecurity(SECRET, "https://mrlgs.net/path"),
    /exact HTTPS origin/,
  );
  assert.throws(
    () => new EmailOtpSecurity(SECRET, "https://mrlgs.net", () => 1_000_000).issueCode(),
    /out-of-range/,
  );
});
