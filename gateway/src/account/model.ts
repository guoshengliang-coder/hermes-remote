export type AccountPlatform = "android" | "macos" | "web";
export type InstallationKind = "phone" | "desktop" | "browser";
export type AccountStatus = "active" | "disabled" | "pending_deletion" | "deleted";

export interface VerifiedExternalIdentity {
  provider: "google" | "email_otp";
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

export type SessionCreationOperation = "auth.google.exchange" | "auth.email.exchange";

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
  | "account.revoke_all"
  | "account.identity.link"
  | "account.identity.unlink"
  | "account.installation.revoke"
  | "account.delete"
  | "device.share";

export type ReauthenticationOperation = "auth.reauth.google" | "auth.reauth.email";

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

export type AccountDeletionResult = RevokeAllResult;

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
  | { status: "invalid" | "expired" | "revoked" | "reused" | "account_disabled" | "account_deletion_pending" | "idempotency_conflict" };

export interface AccountPrincipal {
  account: PublicAccount;
  installation: PublicInstallation;
  sessionId: string;
  refreshFamilyId: string;
}

export type AccessAuthenticationResult =
  | { status: "active"; principal: AccountPrincipal }
  | { status: "invalid" | "expired" | "revoked" | "account_disabled" | "account_deletion_pending" };

export interface PublicExternalIdentity {
  id: string;
  provider: VerifiedExternalIdentity["provider"];
  email?: string;
  displayName?: string;
  avatarUrl?: string;
  verifiedAt: string;
}

export type IdentityLinkResult =
  | { status: "linked" | "already_linked"; identity: PublicExternalIdentity }
  | { status: "replayed"; responseCiphertext: string }
  | {
      status:
        | "identity_conflict"
        | "invalid_grant"
        | "expired_grant"
        | "used_grant"
        | "session_revoked"
        | "account_disabled"
        | "idempotency_conflict";
    };

export type IdentityUnlinkResult =
  | {
      status: "unlinked";
      identity: PublicExternalIdentity;
      currentSessionRevoked: boolean;
    }
  | { status: "replayed"; responseCiphertext: string }
  | {
      status:
        | "not_found"
        | "last_identity"
        | "invalid_grant"
        | "expired_grant"
        | "used_grant"
        | "session_revoked"
        | "account_disabled"
        | "idempotency_conflict";
    };

export interface AccountRepository {
  createSession(
    identity: VerifiedExternalIdentity,
    installation: InstallationInput,
    material: SessionMaterial,
    idempotency: IdempotencyMaterial,
    operation: SessionCreationOperation,
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
    operation: ReauthenticationOperation,
  ): Promise<ReauthenticationResult>;
  listExternalIdentities(accountId: string): Promise<PublicExternalIdentity[]>;
  linkExternalIdentity(
    accountId: string,
    installationId: string,
    currentSessionId: string,
    identity: VerifiedExternalIdentity,
    publicIdentity: PublicExternalIdentity,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<IdentityLinkResult>;
  unlinkExternalIdentity?(
    accountId: string,
    installationId: string,
    currentSessionId: string,
    identityId: string,
    grantTokenHash: string,
    protectResponse: (identity: PublicExternalIdentity, currentSessionRevoked: boolean) => string,
    idempotency: IdempotencyMaterial,
  ): Promise<IdentityUnlinkResult>;
  revokeAllSessions(
    accessTokenHash: string,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeAllResult>;
  requestAccountDeletion(
    accessTokenHash: string,
    grantTokenHash: string,
    deletionDueAt: Date,
    idempotency: IdempotencyMaterial,
  ): Promise<AccountDeletionResult>;
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
  | "request_code"
  | "reauthenticate"
  | "verify_and_replace"
  | "select_device"
  | "open_sharing"
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
    "Verify your sign-in identity again to confirm it's you.",
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
  accountDeletionPending: () => new AccountModeError(
    403,
    "HR-ACCOUNT-012",
    "This Hermes GO account is being permanently deleted and can no longer sign in.",
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
  invalidEmailCode: () => new AccountModeError(
    401,
    "HR-AUTH-009",
    "The email code is invalid or expired. Request a new code.",
    false,
    "request_code",
  ),
  emailDeliveryFailed: () => new AccountModeError(
    503,
    "HR-AUTH-010",
    "The sign-in email couldn't be sent. Try again shortly.",
    true,
    "retry",
  ),
  emailFeatureDisabled: () => new AccountModeError(
    503,
    "HR-AUTH-011",
    "Email sign-in isn't enabled on this Gateway yet. Use another available method or the legacy connection.",
    false,
    "sign_in",
  ),
  webRequestRejected: () => new AccountModeError(
    403,
    "HR-AUTH-012",
    "This browser request couldn't be verified. Reload the account page and try again.",
    false,
    "sign_in",
  ),
  identityConflict: () => new AccountModeError(
    409,
    "HR-ACCOUNT-008",
    "That sign-in identity already belongs to another Hermes GO account.",
    false,
    "none",
  ),
  identityFeatureDisabled: () => new AccountModeError(
    503,
    "HR-ACCOUNT-009",
    "Identity management isn't enabled on this Gateway yet.",
    false,
    "none",
  ),
  lastIdentityRequired: () => new AccountModeError(
    409,
    "HR-ACCOUNT-011",
    "Keep at least one sign-in identity on this Hermes GO account.",
    false,
    "none",
  ),
  webSessionFeatureDisabled: () => new AccountModeError(
    503,
    "HR-ACCOUNT-010",
    "Secure Web account sessions aren't enabled on this Gateway yet.",
    false,
    "none",
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
  deviceSelectionRequired: () => new AccountModeError(
    409,
    "HR-BIND-009",
    "Choose which Mac to use before opening this content.",
    false,
    "select_device",
  ),
  deviceCapacityReached: () => new AccountModeError(
    409,
    "HR-BIND-010",
    "This account already owns the maximum of three Macs. Remove one before adding another.",
    false,
    "none",
  ),
  deviceNotFound: () => new AccountModeError(
    404,
    "HR-BIND-011",
    "That Mac is no longer available to this account. Choose another device.",
    false,
    "select_device",
  ),
  sharingFeatureDisabled: () => new AccountModeError(
    503,
    "HR-SHARE-001",
    "Device sharing isn't enabled on this Gateway yet.",
    false,
    "none",
  ),
  sharingCapacityReached: () => new AccountModeError(
    409,
    "HR-SHARE-002",
    "This Mac already has five people with access. Revoke one before inviting another.",
    false,
    "open_sharing",
  ),
  sharedDeviceCapacityReached: () => new AccountModeError(
    409,
    "HR-SHARE-003",
    "This account already has access to ten shared Macs. Leave one before accepting another.",
    false,
    "open_sharing",
  ),
  shareInvitationInvalid: () => new AccountModeError(
    404,
    "HR-SHARE-004",
    "This sharing invitation is invalid or expired. Ask the owner for a new invitation.",
    false,
    "none",
  ),
  shareEmailMismatch: () => new AccountModeError(
    403,
    "HR-SHARE-005",
    "Sign in with the verified email address that received this invitation.",
    false,
    "sign_in",
  ),
  wholeDeviceAcknowledgementRequired: () => new AccountModeError(
    400,
    "HR-SHARE-006",
    "Confirm that this share provides access to the whole Hermes device.",
    false,
    "none",
  ),
  shareConflict: () => new AccountModeError(
    409,
    "HR-SHARE-007",
    "This device is already shared with that account or the invitation is no longer available.",
    false,
    "open_sharing",
  ),
  shareDeliveryFailed: () => new AccountModeError(
    503,
    "HR-SHARE-008",
    "The sharing invitation email couldn't be sent. Try again shortly.",
    true,
    "retry",
  ),
} as const;
