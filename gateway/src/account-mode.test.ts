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
  InstallationInput,
  IdempotencyMaterial,
  RotationMaterial,
  ReauthenticationMaterial,
  ReauthenticationResult,
  RevokeAllResult,
  SessionCreationResult,
  SessionMaterial,
  SessionMutationResult,
  SessionRotationResult,
  VerifiedExternalIdentity,
} from "./account/model.js";
import { TokenCodec } from "./account/token-codec.js";

const HASH_KEY = "test-only-key-that-is-more-than-thirty-two-bytes";
const INSTALLATION_ID = "fdaed25e-f143-4e3c-b92b-0d881df13630";

test("TokenCodec issues typed opaque tokens and only accepts their exact format", () => {
  const codec = new TokenCodec(HASH_KEY);
  const access = codec.issueAccessToken();
  const refresh = codec.issueRefreshToken();

  assert.match(access, /^hga_[A-Za-z0-9_-]{43}$/);
  assert.match(refresh, /^hgr_[A-Za-z0-9_-]{43}$/);
  const grant = codec.issueReauthenticationGrant();
  assert.match(grant, /^hgg_[A-Za-z0-9_-]{43}$/);
  assert.match(codec.hashAccessToken(access) ?? "", /^[a-f0-9]{64}$/);
  assert.match(codec.hashRefreshToken(refresh) ?? "", /^[a-f0-9]{64}$/);
  assert.match(codec.hashReauthenticationGrant(grant) ?? "", /^[a-f0-9]{64}$/);
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
});

test("default-off account runtime needs no account secrets", async () => {
  const runtime = createAccountRuntime({ ACCOUNT_AUTH_ENABLED: "0" });
  await runtime.close();
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
    accountAuth: { enabled: false, providers: ["google"], android: true, macos: true },
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

test("enabled HTTP surface validates and exchanges bounded JSON without legacy credentials", async () => {
  const repository = new FakeAccountRepository();
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    new TokenCodec(HASH_KEY),
  );
  const controller = new AccountHttpController(true, service);
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

test("account HTTP failures redact provider proofs and storage error details", async () => {
  const repository = new FakeAccountRepository();
  repository.createError = new Error("database-password=storage-secret");
  const service = new AccountService(
    { verify: async () => verifiedIdentity() },
    repository,
    new TokenCodec(HASH_KEY),
  );
  const controller = new AccountHttpController(true, service);
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
  sessionCreationMode: "created" | "replayed" | "revoked" | "idempotency_conflict" = "created";
  savedSessionResponseCiphertext?: string;
  accessResult: AccessAuthenticationResult = { status: "invalid" };
  reauthenticationMode: "created" | "replayed" | "identity_mismatch" | "account_disabled" | "session_revoked" | "idempotency_conflict" = "created";
  reauthenticationMaterial?: ReauthenticationMaterial;
  reauthenticationIdempotency?: IdempotencyMaterial;
  savedReauthenticationResponseCiphertext?: string;
  revokeAllStatus: RevokeAllResult["status"] = "completed";
  revokeAllCall?: {
    accessTokenHash: string;
    grantTokenHash: string;
    idempotency: IdempotencyMaterial;
  };
  signOutStatus: SessionMutationResult = { status: "completed" };
  revokedAccessTokenHash?: string;
  signOutIdempotency?: IdempotencyMaterial;

  async createSession(
    _identity: VerifiedExternalIdentity,
    installation: InstallationInput,
    material: SessionMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionCreationResult> {
    if (this.createError) throw this.createError;
    this.createdMaterial = material;
    this.sessionIdempotencyMaterial = idempotency;
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
  ): Promise<ReauthenticationResult> {
    this.reauthenticationMaterial = material;
    this.reauthenticationIdempotency = idempotency;
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

  async revokeAllSessions(
    accessTokenHash: string,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeAllResult> {
    this.revokeAllCall = { accessTokenHash, grantTokenHash, idempotency };
    return { status: this.revokeAllStatus };
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
