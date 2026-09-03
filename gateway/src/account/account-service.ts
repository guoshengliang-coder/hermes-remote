import { randomUUID } from "node:crypto";
import {
  accountErrors,
  type AccountPlatform,
  type AccountPrincipal,
  type AccountRepository,
  type ExternalIdentityVerifier,
  type IdempotencyMaterial,
  type InstallationInput,
  type PublicAccount,
  type PublicInstallation,
  type ReauthenticationScope,
} from "./model.js";
import { TokenCodec } from "./token-codec.js";
import { ProtectedResponseCodec } from "./protected-response-codec.js";

const ACCESS_LIFETIME_MS = 15 * 60 * 1_000;
const REFRESH_LIFETIME_MS = 30 * 24 * 60 * 60 * 1_000;
const REAUTHENTICATION_LIFETIME_MS = 10 * 60 * 1_000;
const MUTATION_IDEMPOTENCY_LIFETIME_MS = 24 * 60 * 60 * 1_000;

export interface AccountSessionResponse {
  account: PublicAccount;
  installation: PublicInstallation;
  session: {
    accessToken: string;
    accessExpiresAt: string;
    refreshToken: string;
    refreshExpiresAt: string;
  };
}

export interface ReauthenticationResponse {
  grant: string;
  scope: ReauthenticationScope;
  expiresAt: string;
}

export class AccountService {
  private readonly protectedResponses: ProtectedResponseCodec;

  constructor(
    private readonly verifier: ExternalIdentityVerifier,
    private readonly repository: AccountRepository,
    private readonly tokens: TokenCodec,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.protectedResponses = new ProtectedResponseCodec(
      tokens.deriveSubkey("account-idempotency-response-v1"),
    );
  }

  async exchangeGoogleProof(input: {
    platform: AccountPlatform;
    idToken: string;
    nonce: string;
    clientInstallationId: string;
    displayName: string;
    appVersion: string;
    idempotencyKey: string;
  }): Promise<AccountSessionResponse> {
    const identity = await this.verifier.verify(input);
    const installation: InstallationInput = {
      platform: input.platform,
      kind: input.platform === "android" ? "phone" : "desktop",
      clientInstallationId: input.clientInstallationId,
      displayName: input.displayName,
      appVersion: input.appVersion,
    };
    const issued = this.issueMaterial();
    const session = {
      accessToken: issued.accessToken,
      accessExpiresAt: issued.material.accessExpiresAt.toISOString(),
      refreshToken: issued.refreshToken,
      refreshExpiresAt: issued.material.refreshExpiresAt.toISOString(),
    };
    const created = await this.repository.createSession(
      identity,
      installation,
      issued.material,
      {
        key: input.idempotencyKey,
        requestHash: this.tokens.hashContext([
          "auth.google.exchange",
          identity.provider,
          identity.issuer,
          identity.subject,
          input.platform,
          input.clientInstallationId,
          input.displayName,
          input.appVersion,
        ].join("\u0000")),
        responseCiphertext: this.protectedResponses.seal("auth.google.exchange", session),
        expiresAt: issued.material.refreshExpiresAt,
      },
    );
    switch (created.status) {
      case "created": return { account: created.account, installation: created.installation, session };
      case "replayed": return {
        account: created.account,
        installation: created.installation,
        session: parseProtectedSessionResponse(
          this.protectedResponses.open("auth.google.exchange", created.responseCiphertext),
        ),
      };
      case "revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async refresh(input: {
    refreshToken: string;
    clientInstallationId: string;
    idempotencyKey: string;
  }): Promise<AccountSessionResponse["session"]> {
    const currentHash = this.tokens.hashRefreshToken(input.refreshToken);
    if (!currentHash) throw accountErrors.sessionExpired();
    const issued = this.issueRotation();
    const response = {
      accessToken: issued.accessToken,
      accessExpiresAt: issued.material.accessExpiresAt.toISOString(),
      refreshToken: issued.refreshToken,
      refreshExpiresAt: issued.material.refreshExpiresAt.toISOString(),
    };
    const result = await this.repository.rotateSession(
      currentHash,
      input.clientInstallationId,
      issued.material,
      {
        key: input.idempotencyKey,
        requestHash: this.tokens.hashContext(
          `auth.refresh\u0000${currentHash}\u0000${input.clientInstallationId}`,
        ),
        responseCiphertext: this.protectedResponses.seal("auth.refresh", response),
        expiresAt: issued.material.refreshExpiresAt,
      },
    );
    switch (result.status) {
      case "rotated": return response;
      case "replayed": return parseProtectedSessionResponse(
        this.protectedResponses.open("auth.refresh", result.responseCiphertext),
      );
      case "reused": throw accountErrors.refreshReused();
      case "revoked": throw accountErrors.sessionRevoked();
      case "account_disabled": throw accountErrors.accountDisabled();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "expired":
      case "invalid": throw accountErrors.sessionExpired();
    }
  }

  async authenticate(authorization: string | undefined): Promise<AccountPrincipal> {
    const match = /^Bearer ([^ ]+)$/.exec(authorization ?? "");
    const tokenHash = match ? this.tokens.hashAccessToken(match[1]) : undefined;
    if (!tokenHash) throw accountErrors.sessionExpired();
    const result = await this.repository.authenticateAccessToken(tokenHash);
    switch (result.status) {
      case "active": return result.principal;
      case "revoked": throw accountErrors.sessionRevoked();
      case "account_disabled": throw accountErrors.accountDisabled();
      case "expired":
      case "invalid": throw accountErrors.sessionExpired();
    }
  }

  async signOut(authorization: string | undefined, idempotencyKey: string): Promise<void> {
    const accessTokenHash = this.accessTokenHash(authorization);
    const result = await this.repository.revokeSession(
      accessTokenHash,
      this.mutationIdempotency("auth.sign_out", idempotencyKey, accessTokenHash),
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "account_disabled": throw accountErrors.accountDisabled();
      case "revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "expired":
      case "invalid": throw accountErrors.sessionExpired();
    }
  }

  async reauthenticateGoogle(
    principal: AccountPrincipal,
    input: {
      idToken: string;
      nonce: string;
      scope: ReauthenticationScope;
      idempotencyKey: string;
    },
  ): Promise<ReauthenticationResponse> {
    const identity = await this.verifier.verify({
      platform: principal.installation.platform,
      idToken: input.idToken,
      nonce: input.nonce,
    });
    const grant = this.tokens.issueReauthenticationGrant();
    const now = this.now();
    const expiresAt = new Date(now.getTime() + REAUTHENTICATION_LIFETIME_MS);
    const response = { grant, scope: input.scope, expiresAt: expiresAt.toISOString() };
    const result = await this.repository.createReauthenticationGrant(
      principal.account.id,
      principal.installation.id,
      principal.sessionId,
      identity,
      {
        grantId: randomUUID(),
        grantTokenHash: requiredHash(this.tokens.hashReauthenticationGrant(grant)),
        scope: input.scope,
        expiresAt,
      },
      {
        key: input.idempotencyKey,
        requestHash: this.tokens.hashContext([
          "auth.reauth.google",
          principal.account.id,
          principal.installation.id,
          principal.sessionId,
          identity.provider,
          identity.issuer,
          identity.subject,
          input.scope,
        ].join("\u0000")),
        responseCiphertext: this.protectedResponses.seal("auth.reauth.google", response),
        expiresAt,
      },
    );
    switch (result.status) {
      case "created": return response;
      case "replayed": return parseProtectedReauthenticationResponse(
        this.protectedResponses.open("auth.reauth.google", result.responseCiphertext),
      );
      case "account_disabled": throw accountErrors.accountDisabled();
      case "identity_mismatch": throw accountErrors.reauthenticationRequired();
      case "session_revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async revokeAllSessions(
    authorization: string | undefined,
    grant: string,
    idempotencyKey: string,
  ): Promise<void> {
    const accessTokenHash = this.accessTokenHash(authorization);
    const grantTokenHash = this.tokens.hashReauthenticationGrant(grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const result = await this.repository.revokeAllSessions(
      accessTokenHash,
      grantTokenHash,
      this.mutationIdempotency(
        "auth.revoke_all",
        idempotencyKey,
        `${accessTokenHash}\u0000${grantTokenHash}`,
      ),
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "account_disabled": throw accountErrors.accountDisabled();
      case "revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "invalid":
      case "expired":
      case "used": throw accountErrors.reauthenticationRequired();
    }
  }

  async close(): Promise<void> {
    await this.repository.close();
  }

  private issueMaterial(): {
    accessToken: string;
    refreshToken: string;
    material: Parameters<AccountRepository["createSession"]>[2];
  } {
    const now = this.now();
    const accessToken = this.tokens.issueAccessToken();
    const refreshToken = this.tokens.issueRefreshToken();
    return {
      accessToken,
      refreshToken,
      material: {
        sessionId: randomUUID(),
        refreshFamilyId: randomUUID(),
        accessTokenHash: requiredHash(this.tokens.hashAccessToken(accessToken)),
        accessExpiresAt: new Date(now.getTime() + ACCESS_LIFETIME_MS),
        refreshTokenId: randomUUID(),
        refreshTokenHash: requiredHash(this.tokens.hashRefreshToken(refreshToken)),
        refreshExpiresAt: new Date(now.getTime() + REFRESH_LIFETIME_MS),
      },
    };
  }

  private issueRotation(): {
    accessToken: string;
    refreshToken: string;
    material: Parameters<AccountRepository["rotateSession"]>[2];
  } {
    const now = this.now();
    const accessToken = this.tokens.issueAccessToken();
    const refreshToken = this.tokens.issueRefreshToken();
    return {
      accessToken,
      refreshToken,
      material: {
        accessTokenHash: requiredHash(this.tokens.hashAccessToken(accessToken)),
        accessExpiresAt: new Date(now.getTime() + ACCESS_LIFETIME_MS),
        refreshTokenId: randomUUID(),
        refreshTokenHash: requiredHash(this.tokens.hashRefreshToken(refreshToken)),
        refreshExpiresAt: new Date(now.getTime() + REFRESH_LIFETIME_MS),
      },
    };
  }

  private accessTokenHash(authorization: string | undefined): string {
    const match = /^Bearer ([^ ]+)$/.exec(authorization ?? "");
    const tokenHash = match ? this.tokens.hashAccessToken(match[1]) : undefined;
    if (!tokenHash) throw accountErrors.sessionExpired();
    return tokenHash;
  }

  private mutationIdempotency(
    operation: "auth.sign_out" | "auth.revoke_all",
    key: string,
    requestIdentity: string,
  ): IdempotencyMaterial {
    const now = this.now();
    return {
      key,
      requestHash: this.tokens.hashContext(`${operation}\u0000${requestIdentity}`),
      responseCiphertext: this.protectedResponses.seal(operation, { status: "completed" }),
      expiresAt: new Date(now.getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
    };
  }
}

function requiredHash(value: string | undefined): string {
  if (!value) throw new Error("issued_token_failed_validation");
  return value;
}

function parseProtectedSessionResponse(value: unknown): AccountSessionResponse["session"] {
  if (!isRecord(value)
      || !isToken(value.accessToken, "hga_")
      || !isToken(value.refreshToken, "hgr_")
      || !isIsoDate(value.accessExpiresAt)
      || !isIsoDate(value.refreshExpiresAt)) {
    throw new Error("invalid protected refresh response");
  }
  return {
    accessToken: value.accessToken,
    accessExpiresAt: value.accessExpiresAt,
    refreshToken: value.refreshToken,
    refreshExpiresAt: value.refreshExpiresAt,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isToken(value: unknown, prefix: "hga_" | "hgr_" | "hgg_"): value is string {
  return typeof value === "string"
    && value.startsWith(prefix)
    && /^[A-Za-z0-9_-]{43}$/.test(value.slice(prefix.length));
}

function isIsoDate(value: unknown): value is string {
  return typeof value === "string"
    && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(value)
    && Number.isFinite(Date.parse(value));
}

function parseProtectedReauthenticationResponse(value: unknown): ReauthenticationResponse {
  if (!isRecord(value)
      || !isToken(value.grant, "hgg_")
      || !isReauthenticationScope(value.scope)
      || !isIsoDate(value.expiresAt)) {
    throw new Error("invalid protected reauthentication response");
  }
  return { grant: value.grant, scope: value.scope, expiresAt: value.expiresAt };
}

function isReauthenticationScope(value: unknown): value is ReauthenticationScope {
  return value === "connector.replace" || value === "connector.unbind" || value === "account.revoke_all";
}
