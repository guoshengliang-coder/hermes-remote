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
  type PublicExternalIdentity,
  type PublicInstallation,
  type ReauthenticationOperation,
  type ReauthenticationScope,
  type SessionCreationOperation,
  type VerifiedExternalIdentity,
} from "./model.js";
import { TokenCodec } from "./token-codec.js";
import { ProtectedResponseCodec } from "./protected-response-codec.js";

const ACCESS_LIFETIME_MS = 15 * 60 * 1_000;
const REFRESH_LIFETIME_MS = 30 * 24 * 60 * 60 * 1_000;
const REAUTHENTICATION_LIFETIME_MS = 10 * 60 * 1_000;
const MUTATION_IDEMPOTENCY_LIFETIME_MS = 24 * 60 * 60 * 1_000;
const ACCOUNT_DELETION_DELAY_MS = 30 * 24 * 60 * 60 * 1_000;

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

export interface IdentityUnlinkResponse {
  identity: PublicExternalIdentity;
  currentSessionRevoked: boolean;
}

export class AccountService {
  private readonly protectedResponses: ProtectedResponseCodec;

  constructor(
    private readonly verifier: ExternalIdentityVerifier | undefined,
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
    const identity = await this.verifyGoogleIdentity(input);
    return this.exchangeIdentity(identity, input, "auth.google.exchange");
  }

  async exchangeEmailIdentity(
    identity: VerifiedExternalIdentity,
    input: {
      platform: AccountPlatform;
      clientInstallationId: string;
      displayName: string;
      appVersion: string;
      idempotencyKey: string;
    },
  ): Promise<AccountSessionResponse> {
    if (identity.provider !== "email_otp") throw accountErrors.invalidEmailCode();
    return this.exchangeIdentity(identity, input, "auth.email.exchange");
  }

  private async exchangeIdentity(
    identity: VerifiedExternalIdentity,
    input: {
      platform: AccountPlatform;
      clientInstallationId: string;
      displayName: string;
      appVersion: string;
      idempotencyKey: string;
    },
    operation: SessionCreationOperation,
  ): Promise<AccountSessionResponse> {
    const installation: InstallationInput = {
      platform: input.platform,
      kind: input.platform === "android"
        ? "phone"
        : input.platform === "macos" ? "desktop" : "browser",
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
          operation,
          identity.provider,
          identity.issuer,
          identity.subject,
          input.platform,
          input.clientInstallationId,
          input.displayName,
          input.appVersion,
        ].join("\u0000")),
        responseCiphertext: this.protectedResponses.seal(operation, session),
        expiresAt: issued.material.refreshExpiresAt,
      },
      operation,
    );
    switch (created.status) {
      case "created": return { account: created.account, installation: created.installation, session };
      case "replayed": return {
        account: created.account,
        installation: created.installation,
        session: parseProtectedSessionResponse(
          this.protectedResponses.open(operation, created.responseCiphertext),
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
      case "account_deletion_pending": throw accountErrors.accountDeletionPending();
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
      case "account_deletion_pending": throw accountErrors.accountDeletionPending();
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
    const identity = await this.verifyGoogleIdentity({
      platform: principal.installation.platform,
      idToken: input.idToken,
      nonce: input.nonce,
    });
    return this.reauthenticateIdentity(principal, identity, input, "auth.reauth.google");
  }

  async reauthenticateEmailIdentity(
    principal: AccountPrincipal,
    identity: VerifiedExternalIdentity,
    input: {
      scope: ReauthenticationScope;
      idempotencyKey: string;
    },
  ): Promise<ReauthenticationResponse> {
    if (identity.provider !== "email_otp") throw accountErrors.reauthenticationRequired();
    return this.reauthenticateIdentity(principal, identity, input, "auth.reauth.email");
  }

  async listExternalIdentities(principal: AccountPrincipal): Promise<PublicExternalIdentity[]> {
    return this.repository.listExternalIdentities(principal.account.id);
  }

  async linkGoogleIdentity(
    principal: AccountPrincipal,
    input: {
      idToken: string;
      nonce: string;
      grant: string;
      idempotencyKey: string;
    },
  ): Promise<PublicExternalIdentity> {
    const identity = await this.verifyGoogleIdentity({
      platform: principal.installation.platform,
      idToken: input.idToken,
      nonce: input.nonce,
    });
    return this.linkIdentity(principal, identity, input.grant, input.idempotencyKey);
  }

  private async verifyGoogleIdentity(input: {
    platform: AccountPlatform;
    idToken: string;
    nonce: string;
  }): Promise<VerifiedExternalIdentity> {
    if (!this.verifier) throw accountErrors.invalidGoogleProof();
    return this.verifier.verify(input);
  }

  async linkEmailIdentity(
    principal: AccountPrincipal,
    identity: VerifiedExternalIdentity,
    input: { grant: string; idempotencyKey: string },
  ): Promise<PublicExternalIdentity> {
    if (identity.provider !== "email_otp") throw accountErrors.invalidEmailCode();
    return this.linkIdentity(principal, identity, input.grant, input.idempotencyKey);
  }

  async unlinkIdentity(
    principal: AccountPrincipal,
    input: { identityId: string; grant: string; idempotencyKey: string },
  ): Promise<IdentityUnlinkResponse> {
    if (!this.repository.unlinkExternalIdentity) throw accountErrors.identityFeatureDisabled();
    const grantTokenHash = this.tokens.hashReauthenticationGrant(input.grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const operation = "account.identity.unlink";
    const now = this.now();
    const result = await this.repository.unlinkExternalIdentity(
      principal.account.id,
      principal.installation.id,
      principal.sessionId,
      input.identityId,
      grantTokenHash,
      (identity, currentSessionRevoked) => this.protectedResponses.seal(operation, {
        identity,
        currentSessionRevoked,
      }),
      {
        key: input.idempotencyKey,
        requestHash: this.tokens.hashContext([
          operation,
          principal.account.id,
          principal.installation.id,
          principal.sessionId,
          input.identityId,
          grantTokenHash,
        ].join("\u0000")),
        responseCiphertext: "selected-by-repository",
        expiresAt: new Date(now.getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
      },
    );
    switch (result.status) {
      case "unlinked": return {
        identity: result.identity,
        currentSessionRevoked: result.currentSessionRevoked,
      };
      case "replayed": return parseProtectedIdentityUnlinkResponse(
        this.protectedResponses.open(operation, result.responseCiphertext),
      );
      case "not_found": throw accountErrors.resourceNotFound();
      case "last_identity": throw accountErrors.lastIdentityRequired();
      case "account_disabled": throw accountErrors.accountDisabled();
      case "session_revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "invalid_grant":
      case "expired_grant":
      case "used_grant": throw accountErrors.reauthenticationRequired();
    }
  }

  private async reauthenticateIdentity(
    principal: AccountPrincipal,
    identity: VerifiedExternalIdentity,
    input: { scope: ReauthenticationScope; idempotencyKey: string },
    operation: ReauthenticationOperation,
  ): Promise<ReauthenticationResponse> {
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
          operation,
          principal.account.id,
          principal.installation.id,
          principal.sessionId,
          identity.provider,
          identity.issuer,
          identity.subject,
          input.scope,
        ].join("\u0000")),
        responseCiphertext: this.protectedResponses.seal(operation, response),
        expiresAt,
      },
      operation,
    );
    switch (result.status) {
      case "created": return response;
      case "replayed": return parseProtectedReauthenticationResponse(
        this.protectedResponses.open(operation, result.responseCiphertext),
      );
      case "account_disabled": throw accountErrors.accountDisabled();
      case "identity_mismatch": throw accountErrors.reauthenticationRequired();
      case "session_revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  private async linkIdentity(
    principal: AccountPrincipal,
    identity: VerifiedExternalIdentity,
    grant: string,
    idempotencyKey: string,
  ): Promise<PublicExternalIdentity> {
    const grantTokenHash = this.tokens.hashReauthenticationGrant(grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const operation = `account.identity.link:${identity.provider}`;
    const now = this.now();
    const publicIdentity: PublicExternalIdentity = {
      id: randomUUID(),
      provider: identity.provider,
      ...(identity.email ? { email: identity.email } : {}),
      ...(identity.displayName ? { displayName: identity.displayName } : {}),
      ...(identity.avatarUrl ? { avatarUrl: identity.avatarUrl } : {}),
      verifiedAt: now.toISOString(),
    };
    const result = await this.repository.linkExternalIdentity(
      principal.account.id,
      principal.installation.id,
      principal.sessionId,
      identity,
      publicIdentity,
      grantTokenHash,
      {
        key: idempotencyKey,
        requestHash: this.tokens.hashContext([
          operation,
          principal.account.id,
          principal.installation.id,
          principal.sessionId,
          identity.provider,
          identity.issuer,
          identity.subject,
          grantTokenHash,
        ].join("\u0000")),
        responseCiphertext: this.protectedResponses.seal(operation, publicIdentity),
        expiresAt: new Date(now.getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
      },
    );
    switch (result.status) {
      case "linked":
      case "already_linked": return result.identity;
      case "replayed": return parseProtectedExternalIdentity(
        this.protectedResponses.open(operation, result.responseCiphertext),
      );
      case "identity_conflict": throw accountErrors.identityConflict();
      case "account_disabled": throw accountErrors.accountDisabled();
      case "session_revoked": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "invalid_grant":
      case "expired_grant":
      case "used_grant": throw accountErrors.reauthenticationRequired();
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

  async requestAccountDeletion(
    authorization: string | undefined,
    grant: string,
    idempotencyKey: string,
    acknowledgedPermanentCloudDeletion: boolean,
  ): Promise<void> {
    if (!acknowledgedPermanentCloudDeletion) {
      throw accountErrors.invalidRequest(
        "Permanent Cloud account deletion must be explicitly acknowledged.",
      );
    }
    const accessTokenHash = this.accessTokenHash(authorization);
    const grantTokenHash = this.tokens.hashReauthenticationGrant(grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const result = await this.repository.requestAccountDeletion(
      accessTokenHash,
      grantTokenHash,
      new Date(this.now().getTime() + ACCOUNT_DELETION_DELAY_MS),
      this.mutationIdempotency(
        "account.delete",
        idempotencyKey,
        `${accessTokenHash}\u0000${grantTokenHash}\u0000permanent-cloud-deletion-v1`,
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
    operation: "auth.sign_out" | "auth.revoke_all" | "account.delete",
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

function parseProtectedIdentityUnlinkResponse(value: unknown): IdentityUnlinkResponse {
  if (!isRecord(value) || typeof value.currentSessionRevoked !== "boolean") {
    throw new Error("invalid protected identity unlink response");
  }
  return {
    identity: parseProtectedExternalIdentity(value.identity),
    currentSessionRevoked: value.currentSessionRevoked,
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
  return value === "connector.replace"
    || value === "connector.unbind"
    || value === "account.revoke_all"
    || value === "account.identity.link"
    || value === "account.identity.unlink"
    || value === "account.installation.revoke"
    || value === "account.delete"
    || value === "device.share";
}

function parseProtectedExternalIdentity(value: unknown): PublicExternalIdentity {
  if (!isRecord(value)
      || typeof value.id !== "string"
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.id)
      || (value.provider !== "google" && value.provider !== "email_otp")
      || !isIsoDate(value.verifiedAt)
      || !isOptionalString(value.email, 254)
      || !isOptionalString(value.displayName, 256)
      || !isOptionalString(value.avatarUrl, 2048)) {
    throw new Error("invalid protected external identity response");
  }
  return {
    id: value.id,
    provider: value.provider,
    ...(typeof value.email === "string" ? { email: value.email } : {}),
    ...(typeof value.displayName === "string" ? { displayName: value.displayName } : {}),
    ...(typeof value.avatarUrl === "string" ? { avatarUrl: value.avatarUrl } : {}),
    verifiedAt: value.verifiedAt,
  };
}

function isOptionalString(value: unknown, maximum: number): boolean {
  return value === undefined || (typeof value === "string" && value.length >= 1 && value.length <= maximum);
}
