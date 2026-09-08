import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { test } from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import { AccountService } from "./account/account-service.js";
import { createAccountRuntime } from "./account/account-runtime.js";
import { GoogleIdentityVerifier } from "./account/google-identity-verifier.js";
import { ProtectedResponseCodec } from "./account/protected-response-codec.js";
import type {
  AccessAuthenticationResult,
  AccountPrincipal,
  AccountRepository,
  IdentityLinkResult,
  InstallationInput,
  IdempotencyMaterial,
  PublicExternalIdentity,
  RotationMaterial,
  ReauthenticationMaterial,
  ReauthenticationOperation,
  ReauthenticationResult,
  RevokeAllResult,
  SessionCreationResult,
  SessionCreationOperation,
  SessionMaterial,
  SessionMutationResult,
  SessionRotationResult,
  VerifiedExternalIdentity,
} from "./account/model.js";
import { TokenCodec } from "./account/token-codec.js";

const HASH_KEY = "test-only-key-that-is-more-than-thirty-two-bytes";
const INSTALLATION_ID = "fdaed25e-f143-4e3c-b92b-0d881df13630";
const RESEND_WEBHOOK_SECRET = `whsec_${Buffer.from("account-mode-resend-webhook-secret").toString("base64")}`;

test("TokenCodec issues typed opaque tokens and only accepts their exact format", () => {
  const codec = new TokenCodec(HASH_KEY);
  const access = codec.issueAccessToken();
  const refresh = codec.issueRefreshToken();

  assert.match(access, /^hga_[A-Za-z0-9_-]{43}$/);
  assert.match(refresh, /^hgr_[A-Za-z0-9_-]{43}$/);
  const grant = codec.issueReauthenticationGrant();
  const invitation = codec.issueShareInvitationToken("invitation-id");
  assert.match(grant, /^hgg_[A-Za-z0-9_-]{43}$/);
  assert.match(invitation, /^hsi_[A-Za-z0-9_-]{43}$/);
  assert.match(codec.hashAccessToken(access) ?? "", /^[a-f0-9]{64}$/);
  assert.match(codec.hashRefreshToken(refresh) ?? "", /^[a-f0-9]{64}$/);
  assert.match(codec.hashReauthenticationGrant(grant) ?? "", /^[a-f0-9]{64}$/);
  assert.match(codec.hashShareInvitationToken(invitation) ?? "", /^[a-f0-9]{64}$/);
  assert.equal(codec.issueShareInvitationToken("invitation-id"), invitation);
  assert.notEqual(codec.issueShareInvitationToken("other-id"), invitation);
  assert.equal(codec.hashAccessToken(refresh), undefined);
  assert.equal(codec.hashRefreshToken(`${refresh}x`), undefined);
  assert.notEqual(codec.hashAccessToken(access), new TokenCodec(`${HASH_KEY}-other`).hashAccessToken(access));
});

test("ProtectedResponseCodec authenticates encrypted idempotency responses and their context", () => {
  const tokens = new TokenCodec(HASH_KEY);
  const codec = new ProtectedResponseCodec(tokens.deriveSubkey("test-response"));
  const protectedValue = codec.seal("auth.refresh", { value: "secret-response" });
  assert.equal(protectedValue.includes("secret-response"), false);
  assert.deepEqual(codec.open("auth.refresh", protectedValue), { value: "secret-response" });
  assert.throws(() => codec.open("auth.google.exchange", protectedValue));

  const parts = protectedValue.split(".");
  parts[3] = `${parts[3][0] === "A" ? "B" : "A"}${parts[3].slice(1)}`;
  const tampered = parts.join(".");
  assert.throws(() => codec.open("auth.refresh", tampered));
});

test("GoogleIdentityVerifier binds the exact platform audience and client nonce", async () => {
  let receivedAudience: string | string[] | undefined;
  const verifier = new GoogleIdentityVerifier(
    { android: "android-client", macos: "macos-client" },
    {
      verifyIdToken: async (options) => {
        receivedAudience = options.audience;
        return {
          getPayload: () => ({
            iss: "https://accounts.google.com",
            sub: "google-subject-1",
            aud: "android-client",
            iat: 1,
            exp: 4_000_000_000,
            nonce: "1234567890abcdef",
            email: "person@example.com",
            name: "Person",
            picture: "https://example.com/avatar.png",
          }),
        } as never;
      },
    },
  );

  const identity = await verifier.verify({
    platform: "android",
    idToken: "provider-proof",
    nonce: "1234567890abcdef",
  });
  assert.equal(receivedAudience, "android-client");
  assert.deepEqual(identity, {
    provider: "google",
    issuer: "https://accounts.google.com",
    subject: "google-subject-1",
    email: "person@example.com",
    displayName: "Person",
    avatarUrl: "https://example.com/avatar.png",
  });

  await verifier.verify({
    platform: "macos",
    idToken: "provider-proof",
    nonce: "1234567890abcdef",
  });
  assert.equal(receivedAudience, "macos-client");

  await assert.rejects(
    verifier.verify({ platform: "android", idToken: "provider-proof", nonce: "wrong-nonce-value" }),
    (error: unknown) => isErrorCode(error, "HR-AUTH-002"),
  );
});

test("GoogleIdentityVerifier uses a distinct Web OAuth audience and fails closed when absent", async () => {
  let receivedAudience: string | string[] | undefined;
  const client = {
    verifyIdToken: async (options: { audience?: string | string[] }) => {
      receivedAudience = options.audience;
      return {
        getPayload: () => ({
          iss: "https://accounts.google.com",
          sub: "web-subject",
          nonce: "1234567890abcdef",
        }),
      } as never;
    },
  };
  await new GoogleIdentityVerifier(
    { android: "android-client", macos: "macos-client", web: "web-client" },
    client,
  ).verify({ platform: "web", idToken: "proof", nonce: "1234567890abcdef" });
  assert.equal(receivedAudience, "web-client");
  await assert.rejects(
    new GoogleIdentityVerifier({ android: "android-client", macos: "macos-client" }, client)
      .verify({ platform: "web", idToken: "proof", nonce: "1234567890abcdef" }),
    (error: unknown) => isErrorCode(error, "HR-AUTH-002"),
  );
});

test("GoogleIdentityVerifier collapses unsafe provider failures into one sanitized error", async () => {
  const invalidPayloads = [
    {
      iss: "https://attacker.example.invalid",
      sub: "subject",
      nonce: "1234567890abcdef",
    },
    {
      iss: "https://accounts.google.com",
      sub: "",
      nonce: "1234567890abcdef",
    },
    {
      iss: "accounts.google.com",
      sub: "x".repeat(256),
      nonce: "1234567890abcdef",
    },
    {
      iss: "accounts.google.com",
      sub: "subject",
    },
  ];
  for (const payload of invalidPayloads) {
    const verifier = new GoogleIdentityVerifier(
      { android: "android-client", macos: "macos-client" },
      { verifyIdToken: async () => ({ getPayload: () => payload }) as never },
    );
    await assert.rejects(
      verifier.verify({
        platform: "android",
        idToken: "provider-secret-that-must-not-escape",
        nonce: "1234567890abcdef",
      }),
      (error: unknown) => isErrorCode(error, "HR-AUTH-002")
        && !(error as Error).message.includes("provider-secret"),
    );
  }

  for (const providerDetail of ["invalid signature", "wrong audience", "expired token"]) {
    const providerFailure = new GoogleIdentityVerifier(
      { android: "android-client", macos: "macos-client" },
      { verifyIdToken: async () => { throw new Error(`${providerDetail}: provider-secret`); } },
    );
    await assert.rejects(
      providerFailure.verify({
        platform: "android",
        idToken: "provider-secret-that-must-not-escape",
        nonce: "1234567890abcdef",
      }),
      (error: unknown) => isErrorCode(error, "HR-AUTH-002")
        && !(error as Error).message.includes("provider-secret")
        && !(error as Error).message.includes(providerDetail),
    );
  }
});

test("AccountService exchanges a proof without persisting raw bearer values", async () => {
  const repository = new FakeAccountRepository();
  const fixedNow = new Date("2026-09-02T04:00:00.000Z");
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    new TokenCodec(HASH_KEY),
    () => fixedNow,
  );

  const input = {
    platform: "android",
    idToken: "google-id-token-must-not-be-stored",
    nonce: "1234567890abcdef",
    clientInstallationId: INSTALLATION_ID,
    displayName: "Pixel",
    appVersion: "0.2.0",
    idempotencyKey: "4f699b5c-b21b-40e4-99dc-92ff468fe998",
  } as const;
  const response = await service.exchangeGoogleProof(input);

  assert.equal(response.account.id, "account-1");
  assert.equal(response.installation.kind, "phone");
  assert.equal(response.session.accessExpiresAt, "2026-09-02T04:15:00.000Z");
  assert.equal(response.session.refreshExpiresAt, "2026-10-02T04:00:00.000Z");
  assert.match(response.session.accessToken, /^hga_/);
  assert.match(response.session.refreshToken, /^hgr_/);
  assert.match(repository.createdMaterial?.accessTokenHash ?? "", /^[a-f0-9]{64}$/);
  assert.match(repository.createdMaterial?.refreshTokenHash ?? "", /^[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(repository).includes("google-id-token-must-not-be-stored"), false);

  repository.sessionCreationMode = "replayed";
  assert.deepEqual(await service.exchangeGoogleProof(input), response);
});

test("AccountService maps refresh reuse and revoked access to stable errors", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    codec,
  );
  repository.rotationResult = { status: "reused" };
  await assert.rejects(
    service.refresh({
      refreshToken: codec.issueRefreshToken(),
      clientInstallationId: INSTALLATION_ID,
      idempotencyKey: "e72b9f6d-8404-4fd3-9383-d518fba46db0",
    }),
    (error: unknown) => isErrorCode(error, "HR-AUTH-005"),
  );

  repository.accessResult = { status: "revoked" };
  await assert.rejects(
    service.authenticate(`Bearer ${codec.issueAccessToken()}`),
    (error: unknown) => isErrorCode(error, "HR-AUTH-004"),
  );
});

test("normal sign-out revokes only the authenticated current session", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    codec,
  );

  const accessToken = codec.issueAccessToken();
  const idempotencyKey = "a84a8ba7-42d8-4ab0-896b-52930355fc6d";
  await service.signOut(`Bearer ${accessToken}`, idempotencyKey);
  assert.equal(repository.revokedAccessTokenHash, codec.hashAccessToken(accessToken));
  assert.equal(repository.signOutIdempotency?.key, idempotencyKey);

  repository.signOutStatus = { status: "replayed" };
  await service.signOut(`Bearer ${accessToken}`, idempotencyKey);
});

test("refresh retries return the protected committed response for the same idempotency key", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    codec,
    () => new Date("2026-09-02T04:00:00.000Z"),
  );
  const refreshToken = codec.issueRefreshToken();
  const input = {
    refreshToken,
    clientInstallationId: INSTALLATION_ID,
    idempotencyKey: "1f8b89fe-643d-44a8-b0e4-c26870661c7b",
  };
  const first = await service.refresh(input);
  assert(repository.refreshIdempotencyMaterial);
  assert.equal(repository.refreshIdempotencyMaterial.responseCiphertext.includes(first.accessToken), false);

  repository.rotationResult = {
    status: "replayed",
    responseCiphertext: repository.refreshIdempotencyMaterial.responseCiphertext,
  };
  const replay = await service.refresh(input);
  assert.deepEqual(replay, first);
});

test("reauthentication grants are identity-bound, operation-scoped, and required for revoke-all", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    codec,
    () => new Date("2026-09-02T04:00:00.000Z"),
  );
  const principal = testPrincipal();

  const proof = await service.reauthenticateGoogle(principal, {
    idToken: "fresh-google-proof",
    nonce: "1234567890abcdef",
    scope: "account.revoke_all",
    idempotencyKey: "895e8239-cf01-4375-a666-adfe60213f23",
  });
  assert.match(proof.grant, /^hgg_[A-Za-z0-9_-]{43}$/);
  assert.equal(proof.scope, "account.revoke_all");
  assert.equal(proof.expiresAt, "2026-09-02T04:10:00.000Z");
  assert.equal(repository.reauthenticationMaterial?.scope, "account.revoke_all");
  assert.match(repository.reauthenticationMaterial?.grantTokenHash ?? "", /^[a-f0-9]{64}$/);

  repository.reauthenticationMode = "replayed";
  const replayedProof = await service.reauthenticateGoogle(principal, {
    idToken: "fresh-google-proof",
    nonce: "1234567890abcdef",
    scope: "account.revoke_all",
    idempotencyKey: "895e8239-cf01-4375-a666-adfe60213f23",
  });
  assert.deepEqual(replayedProof, proof);

  for (const scope of ["account.identity.unlink", "account.installation.revoke"] as const) {
    repository.reauthenticationMode = "created";
    const scopedIdempotencyKey = randomUUID();
    const scopedProof = await service.reauthenticateGoogle(principal, {
      idToken: "fresh-google-proof",
      nonce: "1234567890abcdef",
      scope,
      idempotencyKey: scopedIdempotencyKey,
    });
    repository.reauthenticationMode = "replayed";
    assert.deepEqual(await service.reauthenticateGoogle(principal, {
      idToken: "fresh-google-proof",
      nonce: "1234567890abcdef",
      scope,
      idempotencyKey: scopedIdempotencyKey,
    }), scopedProof);
  }

  const accessToken = codec.issueAccessToken();
  const revokeKey = "2934eb72-d10c-4f5f-ad4c-a02ba40228e6";
  await service.revokeAllSessions(`Bearer ${accessToken}`, proof.grant, revokeKey);
  assert.equal(repository.revokeAllCall?.accessTokenHash, codec.hashAccessToken(accessToken));
  assert.equal(repository.revokeAllCall?.idempotency.key, revokeKey);

  repository.reauthenticationMode = "identity_mismatch";
  await assert.rejects(
    service.reauthenticateGoogle(principal, {
      idToken: "wrong-account-proof",
      nonce: "1234567890abcdef",
      scope: "connector.replace",
      idempotencyKey: "d7eec8ca-3bf8-43fd-a8ca-42d565ef4429",
    }),
    (error: unknown) => isErrorCode(error, "HR-AUTH-006"),
  );

  await assert.rejects(
    service.revokeAllSessions(`Bearer ${accessToken}`, "not-a-grant", randomUUID()),
    (error: unknown) => isErrorCode(error, "HR-AUTH-006"),
  );

  repository.reauthenticationMode = "created";
  const deletionProof = await service.reauthenticateGoogle(principal, {
    idToken: "fresh-google-proof",
    nonce: "1234567890abcdef",
    scope: "account.delete",
    idempotencyKey: randomUUID(),
  });
  const deletionKey = randomUUID();
  await assert.rejects(
    service.requestAccountDeletion(
      `Bearer ${accessToken}`,
      deletionProof.grant,
      deletionKey,
      false,
    ),
    (error: unknown) => isErrorCode(error, "HR-ACCOUNT-004"),
  );
  await service.requestAccountDeletion(
    `Bearer ${accessToken}`,
    deletionProof.grant,
    deletionKey,
    true,
  );
  assert.equal(repository.deletionCall?.accessTokenHash, codec.hashAccessToken(accessToken));
  assert.equal(repository.deletionCall?.deletionDueAt.toISOString(), "2026-10-02T04:00:00.000Z");
  assert.equal(repository.deletionCall?.idempotency.key, deletionKey);
});

test("email reauthentication and explicit identity linking use one scoped grant and safe replay", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    codec,
    () => new Date("2026-09-07T04:00:00.000Z"),
  );
  const principal = testPrincipal();
  const emailIdentity: VerifiedExternalIdentity = {
    provider: "email_otp",
    issuer: "https://mrlgs.net",
    subject: "e".repeat(64),
    email: "person@example.com",
  };
  const reauthentication = await service.reauthenticateEmailIdentity(principal, emailIdentity, {
    scope: "account.identity.link",
    idempotencyKey: "a7be8434-d225-4a4d-b4c6-dce4519e726f",
  });
  assert.equal(repository.reauthenticationOperation, "auth.reauth.email");
  assert.equal(reauthentication.scope, "account.identity.link");

  const linkInput = {
    grant: reauthentication.grant,
    idempotencyKey: "70de6e6b-2799-4bda-86a7-2d0f17ae3db9",
  };
  const linked = await service.linkEmailIdentity(principal, emailIdentity, linkInput);
  assert.equal(linked.provider, "email_otp");
  assert.equal(linked.email, "person@example.com");
  assert.match(linked.id, /^[0-9a-f-]{36}$/);
  assert(repository.identityLinkIdempotency);
  assert.equal(
    repository.identityLinkIdempotency.responseCiphertext.includes("person@example.com"),
    false,
  );

  repository.identityLinkResult = {
    status: "replayed",
    responseCiphertext: repository.identityLinkIdempotency.responseCiphertext,
  };
  assert.deepEqual(await service.linkEmailIdentity(principal, emailIdentity, linkInput), linked);

  repository.identityLinkResult = { status: "identity_conflict" };
  await assert.rejects(
    service.linkEmailIdentity(principal, emailIdentity, {
      ...linkInput,
      idempotencyKey: randomUUID(),
    }),
    (error: unknown) => isErrorCode(error, "HR-ACCOUNT-008"),
  );
});

test("default-off account runtime needs no account secrets", async () => {
  const runtime = createAccountRuntime({
    ACCOUNT_AUTH_ENABLED: "0",
    ACCOUNT_EMAIL_OTP_ENABLED: "1",
  });
  assert.equal(runtime.accountAuthEnabled, false);
  assert.equal(runtime.googleAuthEnabled, false);
  assert.equal(runtime.emailOtpEnabled, false);
  await runtime.close();
});

test("account retention day settings are bounded and do not connect eagerly", async () => {
  const base = {
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
  };
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_LIFECYCLE_RETENTION_DAYS: "0",
  }), /ACCOUNT_LIFECYCLE_RETENTION_DAYS must be an integer between 1 and 3650/);
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_AUDIT_RETENTION_DAYS: "3651",
  }), /ACCOUNT_AUDIT_RETENTION_DAYS must be an integer between 1 and 3650/);

  const runtime = createAccountRuntime({
    ...base,
    ACCOUNT_LIFECYCLE_RETENTION_DAYS: "1",
    ACCOUNT_AUDIT_RETENTION_DAYS: "3650",
  });
  await runtime.close();
});

test("account deletion stays default-off and requires identity management plus a provider", () => {
  const base = {
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_DELETION_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
  };
  assert.throws(() => createAccountRuntime(base), /ACCOUNT_DELETION_ENABLED requires/);
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
  }), /ACCOUNT_DELETION_ENABLED requires/);
});

test("multi-device runtime fails closed unless binding control is enabled", () => {
  assert.throws(() => createAccountRuntime({
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_MULTI_DEVICE_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_GOOGLE_ANDROID_CLIENT_ID: "android-client-id",
    ACCOUNT_GOOGLE_MACOS_CLIENT_ID: "macos-client-id",
  }), /ACCOUNT_MULTI_DEVICE_ENABLED requires ACCOUNT_BINDING_ENABLED=1/);
});

test("device sharing is independently default-off and fails closed without its prerequisites", async () => {
  const base = {
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_GOOGLE_ANDROID_CLIENT_ID: "android-client-id",
    ACCOUNT_GOOGLE_MACOS_CLIENT_ID: "macos-client-id",
  };
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_DEVICE_SHARING_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_SECRET: RESEND_WEBHOOK_SECRET,
  }), /ACCOUNT_DEVICE_SHARING_ENABLED requires/);

  const runtime = createAccountRuntime({
    ...base,
    ACCOUNT_BINDING_ENABLED: "1",
    ACCOUNT_MULTI_DEVICE_ENABLED: "1",
    ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
    ACCOUNT_DEVICE_SHARING_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_SECRET: RESEND_WEBHOOK_SECRET,
    ACCOUNT_GATEWAY_ORIGIN: "https://mrlgs.net",
    ACCOUNT_RESEND_API_KEY: "re_test_sending_only_key",
    ACCOUNT_EMAIL_FROM: "Hermes GO <login@auth.mrlgs.net>",
    ACCOUNT_SHARING_ACCOUNT_CENTER_ORIGIN: "https://mrlgs.net",
  });
  try {
    assert.equal(runtime.sharingEnabled, true);
    const response = new MemoryResponse();
    await runtime.controller.handle(
      memoryRequest("GET"),
      response.asServerResponse(),
      new URL("http://localhost/v2/capabilities"),
    );
    assert.deepEqual((response.json() as { binding: unknown }).binding, {
      enabled: true,
      replacement: true,
      maxActiveConnectorsPerAccount: 3,
      supportsDeviceSelection: true,
      supportsDeviceSharing: true,
      maxSharedDevices: 10,
      maxGranteesPerDevice: 5,
    });
  } finally {
    await runtime.close();
  }
});

test("email OTP runtime is independently configured and advertised without connecting eagerly", async () => {
  assert.throws(() => createAccountRuntime({
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_EMAIL_OTP_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
  }), /ACCOUNT_RESEND_WEBHOOK_ENABLED=1/);
  const runtime = createAccountRuntime({
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_EMAIL_OTP_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_SECRET: RESEND_WEBHOOK_SECRET,
    ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
    ACCOUNT_DELETION_ENABLED: "1",
    ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_EMAIL_OTP_HASH_KEY: "email-otp-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_EMAIL_OTP_ISSUER: "https://mrlgs.net",
    ACCOUNT_RESEND_API_KEY: "re_test_sending_only_key",
    ACCOUNT_EMAIL_FROM: "Hermes GO <login@auth.mrlgs.net>",
  });
  try {
    assert.equal(runtime.accountAuthEnabled, true);
    assert.equal(runtime.googleAuthEnabled, false);
    assert.equal(runtime.emailOtpEnabled, true);
    assert.equal(runtime.identityManagementEnabled, true);
    assert.equal(runtime.webAccountCenterEnabled, true);
    const response = new MemoryResponse();
    await runtime.controller.handle(
      memoryRequest("GET"),
      response.asServerResponse(),
      new URL("http://localhost/v2/capabilities"),
    );
    assert.deepEqual(
      (response.json() as { accountAuth: { providers: string[] } }).accountAuth.providers,
      ["email_otp"],
    );
    assert.deepEqual(
      (response.json() as { accountAuth: unknown }).accountAuth,
      {
        enabled: true,
        providers: ["email_otp"],
        android: true,
        macos: true,
        identityManagement: true,
        accountDeletion: true,
        webAccountCenter: true,
      },
    );
  } finally {
    await runtime.close();
  }
});

test("secure Web sessions are independently default-off and require their complete HTTPS boundary", async () => {
  const base = {
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_EMAIL_OTP_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_SECRET: RESEND_WEBHOOK_SECRET,
    ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
    ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_EMAIL_OTP_HASH_KEY: "email-otp-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_EMAIL_OTP_ISSUER: "https://mrlgs.net",
    ACCOUNT_RESEND_API_KEY: "re_test_sending_only_key",
    ACCOUNT_EMAIL_FROM: "Hermes GO <login@auth.mrlgs.net>",
  };
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED: "0",
    ACCOUNT_WEB_SESSION_ENABLED: "1",
  }), /ACCOUNT_WEB_SESSION_ENABLED requires/);
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_WEB_SESSION_ENABLED: "1",
    ACCOUNT_WEB_ORIGIN: "http:\/\/accounts.example.test",
  }), /ACCOUNT_WEB_ORIGIN must be an exact HTTPS origin/);

  const runtime = createAccountRuntime({
    ...base,
    ACCOUNT_WEB_SESSION_ENABLED: "1",
    ACCOUNT_WEB_ORIGIN: "https://accounts.example.test",
  });
  try {
    assert.equal(runtime.webSessionEnabled, true);
    assert.equal(runtime.googleAuthEnabled, false);
    const response = new MemoryResponse();
    await runtime.controller.handle(
      memoryRequest("GET"),
      response.asServerResponse(),
      new URL("http://localhost/v2/capabilities"),
    );
    assert.equal(
      (response.json() as { accountAuth: { webSessions?: boolean } }).accountAuth.webSessions,
      true,
    );
    assert.deepEqual(
      (response.json() as { accountAuth: { providers: string[] } }).accountAuth.providers,
      ["email_otp"],
    );
  } finally {
    await runtime.close();
  }
});

test("Google authentication is an independently default-off deferred provider", async () => {
  const base = {
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
  };
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_GOOGLE_AUTH_ENABLED: "1",
  }), /ACCOUNT_GOOGLE_ANDROID_CLIENT_ID/);
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_GOOGLE_AUTH_ENABLED: "1",
    ACCOUNT_GOOGLE_ANDROID_CLIENT_ID: "android-client-id",
  }), /ACCOUNT_GOOGLE_MACOS_CLIENT_ID/);
  assert.throws(() => createAccountRuntime({
    ...base,
    ACCOUNT_GOOGLE_AUTH_ENABLED: "1",
    ACCOUNT_GOOGLE_ANDROID_CLIENT_ID: "android-client-id",
    ACCOUNT_GOOGLE_MACOS_CLIENT_ID: "macos-client-id",
    ACCOUNT_EMAIL_OTP_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_ENABLED: "1",
    ACCOUNT_RESEND_WEBHOOK_SECRET: RESEND_WEBHOOK_SECRET,
    ACCOUNT_IDENTITY_MANAGEMENT_ENABLED: "1",
    ACCOUNT_WEB_ACCOUNT_CENTER_ENABLED: "1",
    ACCOUNT_WEB_SESSION_ENABLED: "1",
    ACCOUNT_EMAIL_OTP_HASH_KEY: "email-otp-test-key-with-at-least-thirty-two-bytes",
    ACCOUNT_EMAIL_OTP_ISSUER: "https://mrlgs.net",
    ACCOUNT_RESEND_API_KEY: "re_test_sending_only_key",
    ACCOUNT_EMAIL_FROM: "Hermes GO <login@auth.mrlgs.net>",
  }), /ACCOUNT_GOOGLE_WEB_CLIENT_ID/);

  const runtime = createAccountRuntime({
    ...base,
    ACCOUNT_GOOGLE_AUTH_ENABLED: "1",
    ACCOUNT_GOOGLE_ANDROID_CLIENT_ID: "android-client-id",
    ACCOUNT_GOOGLE_MACOS_CLIENT_ID: "macos-client-id",
  });
  try {
    assert.equal(runtime.googleAuthEnabled, true);
    const capabilitiesResponse = new MemoryResponse();
    await runtime.controller.handle(
      memoryRequest("GET"),
      capabilitiesResponse.asServerResponse(),
      new URL("http://localhost/v2/capabilities"),
    );
    assert.deepEqual(
      (capabilitiesResponse.json() as { accountAuth: { providers: string[] } }).accountAuth.providers,
      ["google"],
    );
  } finally {
    await runtime.close();
  }
});

test("disabled Google provider is omitted from capabilities and its exchange route", async () => {
  let verificationCalls = 0;
  const controller = new AccountHttpController(
    true,
    new AccountService(
      { verify: async () => { verificationCalls += 1; return verifiedIdentity(); } },
      new FakeAccountRepository(),
      new TokenCodec(HASH_KEY),
    ),
    { emailOtpEnabled: true, googleAuthEnabled: false },
  );
  const capabilitiesResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("GET"),
    capabilitiesResponse.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.deepEqual(
    (capabilitiesResponse.json() as { accountAuth: { providers: string[] } }).accountAuth.providers,
    ["email_otp"],
  );

  const exchangeResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", { "content-type": "application/json" }, "{}"),
    exchangeResponse.asServerResponse(),
    new URL("http://localhost/v2/auth/google/exchange"),
  );
  assert.equal(exchangeResponse.status, 404);
  assert.equal(
    (exchangeResponse.json() as { error: { code: string } }).error.code,
    "HR-ACCOUNT-004",
  );
  assert.equal(verificationCalls, 0);
});

test("default-off HTTP surface advertises legacy compatibility and rejects account exchange", async () => {
  const controller = new AccountHttpController(false);
  const capabilitiesResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("GET"),
    capabilitiesResponse.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.equal(capabilitiesResponse.status, 200);
  assert.deepEqual(capabilitiesResponse.json(), {
    version: 1,
    accountAuth: {
      enabled: false,
      providers: [],
      android: true,
      macos: true,
      identityManagement: false,
      webAccountCenter: false,
    },
    binding: { enabled: false, replacement: false, maxActiveConnectorsPerAccount: 1 },
    legacy: { appTokenAccepted: true, connectorTokenAccepted: true },
  });

  const exchangeResponse = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", { "content-type": "application/json" }),
    exchangeResponse.asServerResponse(),
    new URL("http://localhost/v2/auth/google/exchange"),
  );
  assert.equal(exchangeResponse.status, 503);
  const error = exchangeResponse.json() as { error: { code: string; recoveryAction: string } };
  assert.equal(error.error.code, "HR-ACCOUNT-003");
  assert.equal(error.error.recoveryAction, "continue_legacy");
});

test("binding capability is advertised only by its independent rollout flag", async () => {
  const response = new MemoryResponse();
  await new AccountHttpController(true, undefined, { controlEnabled: true }).handle(
    memoryRequest("GET"),
    response.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.equal(response.status, 200);
  const body = response.json() as {
    accountAuth: { enabled: boolean };
    binding: { enabled: boolean; replacement: boolean };
    legacy: { appTokenAccepted: boolean; connectorTokenAccepted: boolean };
  };
  assert.equal(body.accountAuth.enabled, true);
  assert.equal(body.binding.enabled, true);
  assert.equal(body.binding.replacement, true);
  assert.deepEqual(body.legacy, { appTokenAccepted: true, connectorTokenAccepted: true });
});

test("Desktop managed install is default-off and advertises only the frozen runtime contract", async () => {
  const defaultResponse = new MemoryResponse();
  await new AccountHttpController(true, undefined, { controlEnabled: true }).handle(
    memoryRequest("GET"),
    defaultResponse.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.equal(
    Object.hasOwn(defaultResponse.json() as object, "desktopBootstrap"),
    false,
  );

  const enabledResponse = new MemoryResponse();
  await new AccountHttpController(true, undefined, {
    controlEnabled: true,
    desktopManagedInstallEnabled: true,
  }).handle(
    memoryRequest("GET"),
    enabledResponse.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.deepEqual(
    (enabledResponse.json() as { desktopBootstrap?: unknown }).desktopBootstrap,
    { runtimeContract: "hermes-serve-v1" },
  );

  assert.throws(() => createAccountRuntime({
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED: "1",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
  }), /ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED requires ACCOUNT_BINDING_ENABLED=1/);

  const runtime = createAccountRuntime({
    ACCOUNT_AUTH_ENABLED: "1",
    ACCOUNT_BINDING_ENABLED: "1",
    ACCOUNT_DESKTOP_MANAGED_INSTALL_ENABLED: "1",
    ACCOUNT_GATEWAY_ORIGIN: "https://mrlgs.net",
    ACCOUNT_DATABASE_URL: "postgresql://127.0.0.1:1/not-connected-by-this-test",
    ACCOUNT_TOKEN_HASH_KEY: "account-token-test-key-with-at-least-thirty-two-bytes",
  });
  try {
    const runtimeResponse = new MemoryResponse();
    await runtime.controller.handle(
      memoryRequest("GET"),
      runtimeResponse.asServerResponse(),
      new URL("http://localhost/v2/capabilities"),
    );
    assert.deepEqual(
      (runtimeResponse.json() as { desktopBootstrap?: unknown }).desktopBootstrap,
      { runtimeContract: "hermes-serve-v1" },
    );
  } finally {
    await runtime.close();
  }
});

test("multi-device capability is independently default-off and advertises the owned-device limit", async () => {
  const response = new MemoryResponse();
  await new AccountHttpController(true, undefined, {
    controlEnabled: true,
    multiDeviceEnabled: true,
  }).handle(
    memoryRequest("GET"),
    response.asServerResponse(),
    new URL("http://localhost/v2/capabilities"),
  );
  assert.equal(response.status, 200);
  assert.deepEqual((response.json() as { binding: unknown }).binding, {
    enabled: true,
    replacement: true,
    maxActiveConnectorsPerAccount: 3,
    supportsDeviceSelection: true,
  });
});

test("enabled HTTP surface validates and exchanges bounded JSON without legacy credentials", async () => {
  const repository = new FakeAccountRepository();
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    new TokenCodec(HASH_KEY),
  );
  const controller = new AccountHttpController(true, service, { googleAuthEnabled: true });
  const response = new MemoryResponse();
  await controller.handle(
    memoryRequest(
      "POST",
      {
        "content-type": "application/json",
        "idempotency-key": "374dde86-e329-4be6-a597-74722ac80b21",
      },
      JSON.stringify({
        platform: "macos",
        idToken: "provider-proof",
        nonce: "1234567890abcdef",
        clientInstallationId: INSTALLATION_ID,
        displayName: "Mac mini",
        appVersion: "0.2.0",
      }),
    ),
    response.asServerResponse(),
    new URL("http://localhost/v2/auth/google/exchange"),
  );

  assert.equal(response.status, 200);
  const body = response.json() as {
    installation: { kind: string; platform: string };
    session: { accessToken: string; refreshToken: string };
  };
  assert.deepEqual(body.installation, {
    id: "installation-1",
    kind: "desktop",
    platform: "macos",
    displayName: "Mac mini",
  });
  assert.match(body.session.accessToken, /^hga_/);
  assert.match(body.session.refreshToken, /^hgr_/);
});

test("sign-out and revoke-all require retry keys and return no credential body", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const controller = new AccountHttpController(
    true,
    new AccountService({ verify: async () => verifiedIdentity() }, repository, codec),
  );
  const accessToken = codec.issueAccessToken();

  const missingKey = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", { authorization: `Bearer ${accessToken}` }),
    missingKey.asServerResponse(),
    new URL("http://localhost/v2/auth/sign-out"),
  );
  assert.equal(missingKey.status, 400);

  const signOut = new MemoryResponse();
  await controller.handle(
    memoryRequest("POST", {
      authorization: `Bearer ${accessToken}`,
      "idempotency-key": randomUUID(),
    }),
    signOut.asServerResponse(),
    new URL("http://localhost/v2/auth/sign-out"),
  );
  assert.equal(signOut.status, 204);
  assert.equal(signOut.body, "");

  const revokeAll = new MemoryResponse();
  await controller.handle(
    memoryRequest(
      "POST",
      {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
        "idempotency-key": randomUUID(),
      },
      JSON.stringify({ grant: codec.issueReauthenticationGrant() }),
    ),
    revokeAll.asServerResponse(),
    new URL("http://localhost/v2/auth/revoke-all"),
  );
  assert.equal(revokeAll.status, 204);
  assert.equal(revokeAll.body, "");
});

test("native account deletion is default-off and requires an explicit permanent-deletion acknowledgement", async () => {
  const repository = new FakeAccountRepository();
  const codec = new TokenCodec(HASH_KEY);
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    codec,
  );
  const accessToken = codec.issueAccessToken();
  const grant = codec.issueReauthenticationGrant();
  const headers = {
    authorization: `Bearer ${accessToken}`,
    "content-type": "application/json",
    "idempotency-key": randomUUID(),
  };

  const hidden = new MemoryResponse();
  await new AccountHttpController(true, service).handle(
    memoryRequest("DELETE", headers, JSON.stringify({
      grant,
      acknowledgedPermanentCloudDeletion: true,
    })),
    hidden.asServerResponse(),
    new URL("http://localhost/v2/account"),
  );
  assert.equal(hidden.status, 404);
  assert.equal(Boolean(repository.deletionCall), false);

  const controller = new AccountHttpController(true, service, { accountDeletionEnabled: true });
  const missingAcknowledgement = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", headers, JSON.stringify({ grant })),
    missingAcknowledgement.asServerResponse(),
    new URL("http://localhost/v2/account"),
  );
  assert.equal(missingAcknowledgement.status, 400);
  assert.equal(
    (missingAcknowledgement.json() as { error: { code: string } }).error.code,
    "HR-ACCOUNT-004",
  );
  assert.equal(Boolean(repository.deletionCall), false);

  const deleted = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", headers, JSON.stringify({
      grant,
      acknowledgedPermanentCloudDeletion: true,
    })),
    deleted.asServerResponse(),
    new URL("http://localhost/v2/account"),
  );
  assert.equal(deleted.status, 204);
  assert.equal(deleted.body, "");
  assert.equal(repository.deletionCall?.accessTokenHash, codec.hashAccessToken(accessToken));
});

test("account HTTP failures redact provider proofs and storage error details", async () => {
  const repository = new FakeAccountRepository();
  repository.createError = new Error("database-password=storage-secret");
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    new TokenCodec(HASH_KEY),
  );
  const controller = new AccountHttpController(true, service, { googleAuthEnabled: true });
  const response = new MemoryResponse();
  const captured: unknown[][] = [];
  const originalConsoleError = console.error;
  console.error = (...values: unknown[]) => { captured.push(values); };
  try {
    await controller.handle(
      memoryRequest(
        "POST",
        {
          "content-type": "application/json",
          "idempotency-key": "b47c2be6-ec6f-413c-ae09-bcbbb4fb2320",
        },
        JSON.stringify({
          platform: "android",
          idToken: "google-provider-secret",
          nonce: "1234567890abcdef",
          clientInstallationId: INSTALLATION_ID,
          displayName: "Phone",
          appVersion: "0.2.0",
        }),
      ),
      response.asServerResponse(),
      new URL("http://localhost/v2/auth/google/exchange"),
    );
  } finally {
    console.error = originalConsoleError;
  }
  assert.equal(response.status, 503);
  assert.equal((response.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-002");
  const observable = `${response.body}\n${JSON.stringify(captured)}`;
  assert.equal(observable.includes("google-provider-secret"), false);
  assert.equal(observable.includes("storage-secret"), false);
  assert.match(observable, /Account request failed/);
});

test("Google exchange rate limiting is enforced per bounded source bucket", async () => {
  const controller = new AccountHttpController(
    true,
    new AccountService(
      { verify: async () => verifiedIdentity() },
      new FakeAccountRepository(),
      new TokenCodec(HASH_KEY),
    ),
    { googleAuthEnabled: true },
  );
  for (let index = 0; index < 11; index += 1) {
    const response = new MemoryResponse();
    await controller.handle(
      memoryRequest(
        "POST",
        { "content-type": "application/json", "idempotency-key": randomUUID() },
        JSON.stringify({
          platform: "android",
          idToken: "provider-proof",
          nonce: "1234567890abcdef",
          clientInstallationId: INSTALLATION_ID,
          displayName: "Phone",
          appVersion: "0.2.0",
        }),
      ),
      response.asServerResponse(),
      new URL("http://localhost/v2/auth/google/exchange"),
    );
    if (index < 10) assert.equal(response.status, 200);
    else {
      assert.equal(response.status, 429);
      assert.equal((response.json() as { error: { code: string } }).error.code, "HR-AUTH-007");
    }
  }
});

test("Google exchange limiter fails closed at its bounded source capacity", async () => {
  const controller = new AccountHttpController(
    true,
    new AccountService(
      { verify: async () => verifiedIdentity() },
      new FakeAccountRepository(),
      new TokenCodec(HASH_KEY),
    ),
    { googleAuthEnabled: true },
  );
  for (let index = 0; index < 10_001; index += 1) {
    const response = new MemoryResponse();
    await controller.handle(
      memoryRequest(
        "POST",
        {},
        "",
        `198.51.${Math.floor(index / 256)}.${index % 256}`,
      ),
      response.asServerResponse(),
      new URL("http://localhost/v2/auth/google/exchange"),
    );
    if (index < 10_000) assert.equal(response.status, 400);
    else assert.equal(response.status, 429);
  }
});

class FakeAccountRepository implements AccountRepository {
  createdMaterial?: SessionMaterial;
  createError?: Error;
  rotationResult: SessionRotationResult = { status: "rotated" };
  refreshIdempotencyMaterial?: IdempotencyMaterial;
  sessionIdempotencyMaterial?: IdempotencyMaterial;
  sessionCreationOperation?: SessionCreationOperation;
  sessionCreationMode: "created" | "replayed" | "revoked" | "idempotency_conflict" = "created";
  savedSessionResponseCiphertext?: string;
  accessResult: AccessAuthenticationResult = { status: "invalid" };
  reauthenticationMode: "created" | "replayed" | "identity_mismatch" | "account_disabled" | "session_revoked" | "idempotency_conflict" = "created";
  reauthenticationMaterial?: ReauthenticationMaterial;
  reauthenticationIdempotency?: IdempotencyMaterial;
  reauthenticationOperation?: ReauthenticationOperation;
  savedReauthenticationResponseCiphertext?: string;
  revokeAllStatus: RevokeAllResult["status"] = "completed";
  revokeAllCall?: {
    accessTokenHash: string;
    grantTokenHash: string;
    idempotency: IdempotencyMaterial;
  };
  deletionStatus: RevokeAllResult["status"] = "completed";
  deletionCall?: {
    accessTokenHash: string;
    grantTokenHash: string;
    deletionDueAt: Date;
    idempotency: IdempotencyMaterial;
  };
  signOutStatus: SessionMutationResult = { status: "completed" };
  revokedAccessTokenHash?: string;
  signOutIdempotency?: IdempotencyMaterial;
  identities: PublicExternalIdentity[] = [];
  identityLinkResult?: IdentityLinkResult;
  identityLinkIdempotency?: IdempotencyMaterial;

  async createSession(
    _identity: VerifiedExternalIdentity,
    installation: InstallationInput,
    material: SessionMaterial,
    idempotency: IdempotencyMaterial,
    operation: SessionCreationOperation,
  ): Promise<SessionCreationResult> {
    if (this.createError) throw this.createError;
    this.createdMaterial = material;
    this.sessionIdempotencyMaterial = idempotency;
    this.sessionCreationOperation = operation;
    if (this.sessionCreationMode === "revoked" || this.sessionCreationMode === "idempotency_conflict") {
      return { status: this.sessionCreationMode };
    }
    if (this.sessionCreationMode === "replayed") {
      assert(this.savedSessionResponseCiphertext);
      return {
        status: "replayed",
        account: { id: "account-1", email: "person@example.com", displayName: "Person" },
        installation: {
          id: "installation-1",
          kind: installation.kind,
          platform: installation.platform,
          displayName: installation.displayName,
        },
        responseCiphertext: this.savedSessionResponseCiphertext,
      };
    }
    this.savedSessionResponseCiphertext = idempotency.responseCiphertext;
    return {
      status: "created",
      account: { id: "account-1", email: "person@example.com", displayName: "Person" },
      installation: {
        id: "installation-1",
        kind: installation.kind,
        platform: installation.platform,
        displayName: installation.displayName,
      },
    };
  }

  async rotateSession(
    _refreshTokenHash: string,
    _clientInstallationId: string,
    _material: RotationMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionRotationResult> {
    this.refreshIdempotencyMaterial = idempotency;
    return this.rotationResult;
  }

  async authenticateAccessToken(_accessTokenHash: string): Promise<AccessAuthenticationResult> {
    return this.accessResult;
  }

  async createReauthenticationGrant(
    _accountId: string,
    _installationId: string,
    _currentSessionId: string,
    _identity: VerifiedExternalIdentity,
    material: ReauthenticationMaterial,
    idempotency: IdempotencyMaterial,
    operation: ReauthenticationOperation,
  ): Promise<ReauthenticationResult> {
    this.reauthenticationMaterial = material;
    this.reauthenticationIdempotency = idempotency;
    this.reauthenticationOperation = operation;
    if (this.reauthenticationMode === "replayed") {
      assert(this.savedReauthenticationResponseCiphertext);
      return {
        status: "replayed",
        responseCiphertext: this.savedReauthenticationResponseCiphertext,
      };
    }
    if (this.reauthenticationMode === "created") {
      this.savedReauthenticationResponseCiphertext = idempotency.responseCiphertext;
    }
    return { status: this.reauthenticationMode };
  }

  async listExternalIdentities(_accountId: string): Promise<PublicExternalIdentity[]> {
    return this.identities;
  }

  async linkExternalIdentity(
    _accountId: string,
    _installationId: string,
    _currentSessionId: string,
    _identity: VerifiedExternalIdentity,
    publicIdentity: PublicExternalIdentity,
    _grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<IdentityLinkResult> {
    this.identityLinkIdempotency = idempotency;
    return this.identityLinkResult ?? { status: "linked", identity: publicIdentity };
  }

  async revokeAllSessions(
    accessTokenHash: string,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeAllResult> {
    this.revokeAllCall = { accessTokenHash, grantTokenHash, idempotency };
    return { status: this.revokeAllStatus };
  }

  async requestAccountDeletion(
    accessTokenHash: string,
    grantTokenHash: string,
    deletionDueAt: Date,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeAllResult> {
    this.deletionCall = { accessTokenHash, grantTokenHash, deletionDueAt, idempotency };
    return { status: this.deletionStatus };
  }

  async revokeSession(
    accessTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionMutationResult> {
    this.revokedAccessTokenHash = accessTokenHash;
    this.signOutIdempotency = idempotency;
    return this.signOutStatus;
  }
  async close(): Promise<void> {}
}

function verifiedIdentity(): VerifiedExternalIdentity {
  return {
    provider: "google",
    issuer: "https://accounts.google.com",
    subject: "google-subject-1",
    email: "person@example.com",
    displayName: "Person",
  };
}

function testPrincipal(): AccountPrincipal {
  return {
    account: { id: "account-1", email: "person@example.com", displayName: "Person" },
    installation: {
      id: "installation-1",
      kind: "phone",
      platform: "android",
      displayName: "Pixel",
    },
    sessionId: "session-1",
    refreshFamilyId: "family-1",
  };
}

function isErrorCode(error: unknown, code: string): boolean {
  return typeof error === "object" && error !== null && "code" in error
    && (error as { code: unknown }).code === code;
}

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
