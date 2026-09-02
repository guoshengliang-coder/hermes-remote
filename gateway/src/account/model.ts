export type AccountPlatform = "android" | "macos";
export type InstallationKind = "phone" | "desktop";

export interface VerifiedExternalIdentity {
  provider: "google";
  issuer: string;
  subject: string;
  email?: string;
  displayName?: string;
  avatarUrl?: string;
}

export interface InstallationInput {
  platform: AccountPlatform;
  kind: InstallationKind;
  clientInstallationId: string;
  displayName: string;
  appVersion: string;
}

export interface SessionMaterial {
  sessionId: string;
  refreshFamilyId: string;
  accessTokenHash: string;
  accessExpiresAt: Date;
  refreshTokenId: string;
  refreshTokenHash: string;
  refreshExpiresAt: Date;
}

export interface RotationMaterial {
  accessTokenHash: string;
  accessExpiresAt: Date;
  refreshTokenId: string;
  refreshTokenHash: string;
  refreshExpiresAt: Date;
}

export interface IdempotencyMaterial {
  key: string;
  requestHash: string;
  responseCiphertext: string;
  expiresAt: Date;
}

export type SessionMutationResult =
  | { status: "completed" | "replayed" }
  | {
      status:
        | "invalid"
        | "expired"
        | "revoked"
        | "account_disabled"
        | "idempotency_conflict";
    };

export type ReauthenticationScope =
  | "connector.replace"
  | "connector.unbind"
  | "account.revoke_all";

export interface ReauthenticationMaterial {
  grantId: string;
  grantTokenHash: string;
  scope: ReauthenticationScope;
  expiresAt: Date;
}

export type ReauthenticationResult =
  | { status: "created" }
  | { status: "replayed"; responseCiphertext: string }
  | { status: "identity_mismatch" | "account_disabled" | "session_revoked" | "idempotency_conflict" };

export interface RevokeAllResult {
  status:
    | "completed"
    | "replayed"
    | "invalid"
    | "expired"
    | "used"
    | "revoked"
    | "account_disabled"
    | "idempotency_conflict";
}

export interface PublicAccount {
  id: string;
  displayName?: string;
  email?: string;
  avatarUrl?: string;
}

export interface PublicInstallation {
  id: string;
  kind: InstallationKind;
  platform: AccountPlatform;
  displayName: string;
}

export type SessionCreationResult =
  | {
      status: "created";
      account: PublicAccount;
      installation: PublicInstallation;
    }
  | {
      status: "replayed";
      account: PublicAccount;
      installation: PublicInstallation;
      responseCiphertext: string;
    }
  | { status: "revoked" | "idempotency_conflict" };

export type SessionRotationResult =
  | { status: "rotated" }
  | { status: "replayed"; responseCiphertext: string }
  | { status: "invalid" | "expired" | "revoked" | "reused" | "account_disabled" | "idempotency_conflict" };

export interface AccountPrincipal {
  account: PublicAccount;
  installation: PublicInstallation;
  sessionId: string;
  refreshFamilyId: string;
}

export type AccessAuthenticationResult =
  | { status: "active"; principal: AccountPrincipal }
  | { status: "invalid" | "expired" | "revoked" | "account_disabled" };

export interface AccountRepository {
  createSession(
    identity: VerifiedExternalIdentity,
    installation: InstallationInput,
    material: SessionMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionCreationResult>;
  rotateSession(
    refreshTokenHash: string,
    clientInstallationId: string,
    material: RotationMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionRotationResult>;
  authenticateAccessToken(accessTokenHash: string): Promise<AccessAuthenticationResult>;
  createReauthenticationGrant(
    accountId: string,
    installationId: string,
    currentSessionId: string,
    identity: VerifiedExternalIdentity,
    material: ReauthenticationMaterial,
    idempotency: IdempotencyMaterial,
  ): Promise<ReauthenticationResult>;
  revokeAllSessions(
    accessTokenHash: string,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeAllResult>;
  revokeSession(
    accessTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<SessionMutationResult>;
  close(): Promise<void>;
}

export interface ExternalIdentityVerifier {
  verify(input: {
    platform: AccountPlatform;
    idToken: string;
    nonce: string;
  }): Promise<VerifiedExternalIdentity>;
}

export type RecoveryAction =
  | "retry"
  | "sign_in"
  | "reauthenticate"
  | "verify_and_replace"
  | "continue_legacy"
  | "none";

export class AccountModeError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable: boolean,
    readonly recoveryAction: RecoveryAction,
  ) {
    super(message);
    this.name = "AccountModeError";
  }
}

export const accountErrors = {
  invalidGoogleProof: () => new AccountModeError(
    401,
    "HR-AUTH-002",
    "Couldn't verify the Google sign-in. Sign in again.",
    false,
    "sign_in",
  ),
  sessionExpired: () => new AccountModeError(
    401,
    "HR-AUTH-003",
    "Your session expired. Sign in again.",
    false,
    "sign_in",
  ),
  sessionRevoked: () => new AccountModeError(
    401,
    "HR-AUTH-004",
    "This device's session was revoked. Sign in again.",
    false,
    "sign_in",
  ),
  refreshReused: () => new AccountModeError(
    401,
    "HR-AUTH-005",
    "Reuse of a sign-in credential was detected, so this device was signed out for safety.",
    false,
    "sign_in",
  ),
  reauthenticationRequired: () => new AccountModeError(
    403,
    "HR-AUTH-006",
    "Verify your Google account again to confirm it's you.",
    false,
    "reauthenticate",
  ),
  accountDisabled: () => new AccountModeError(
    403,
    "HR-ACCOUNT-001",
    "This Hermes GO account is currently unavailable. Contact support.",
    false,
    "none",
  ),
  unavailable: () => new AccountModeError(
    503,
    "HR-ACCOUNT-002",
    "The account service is temporarily unavailable. Try again shortly.",
    true,
    "retry",
  ),
  featureDisabled: () => new AccountModeError(
    503,
    "HR-ACCOUNT-003",
    "Account sign-in isn't enabled on this Gateway yet. Continue with the legacy connection.",
    false,
    "continue_legacy",
  ),
  invalidRequest: (message = "The account request is invalid.") => new AccountModeError(
    400,
    "HR-ACCOUNT-004",
    message,
    false,
    "none",
  ),
  rateLimited: () => new AccountModeError(
    429,
    "HR-AUTH-007",
    "Too many sign-in requests. Wait a moment and try again.",
    true,
    "retry",
  ),
  idempotencyConflict: () => new AccountModeError(
    409,
    "HR-ACCOUNT-005",
    "That retry key was already used for a different account request.",
    false,
    "none",
  ),
  resourceNotFound: () => new AccountModeError(
    404,
    "HR-ACCOUNT-006",
    "The requested account resource was not found.",
    false,
    "none",
  ),
  desktopRequired: () => new AccountModeError(
    403,
    "HR-ACCOUNT-007",
    "This operation is available only from Hermes Go Desktop.",
    false,
    "none",
  ),
  bindingConflict: () => new AccountModeError(
    409,
    "HR-BIND-002",
    "This account already has a Desktop binding or binding request.",
    false,
    "verify_and_replace",
  ),
  bindingMissing: () => new AccountModeError(
    409,
    "HR-BIND-001",
    "This account has no Desktop connection yet. Open Hermes Go Desktop on the Mac.",
    true,
    "retry",
  ),
  connectorOffline: () => new AccountModeError(
    503,
    "HR-CONN-005",
    "The Mac is offline. Start Hermes Go Desktop.",
    true,
    "retry",
  ),
  bindingExpired: () => new AccountModeError(
    410,
    "HR-BIND-003",
    "The Desktop binding request expired. Start again.",
    true,
    "retry",
  ),
  bindingProofFailed: () => new AccountModeError(
    409,
    "HR-BIND-005",
    "The Desktop Connector has not completed identity and health verification.",
    true,
    "retry",
  ),
  bindingRevoked: () => new AccountModeError(
    409,
    "HR-BIND-006",
    "This Mac is no longer bound to the account. Bind it again or use the current Mac.",
    false,
    "verify_and_replace",
  ),
  bindingReplacementFailed: () => new AccountModeError(
    409,
    "HR-BIND-007",
    "Couldn't replace the Mac. The original connection is still working.",
    true,
    "retry",
  ),
  bindingFeatureDisabled: () => new AccountModeError(
    503,
    "HR-BIND-008",
    "Desktop binding isn't enabled on this Gateway yet. Continue with the legacy connection.",
    false,
    "continue_legacy",
  ),
} as const;
