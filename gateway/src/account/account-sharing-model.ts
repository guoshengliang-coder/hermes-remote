import type { AccountDevice } from "./account-control-model.js";
import type { AccountPrincipal, IdempotencyMaterial } from "./model.js";
import type { EmailProviderReceipt, EmailProviderSubmission } from "./email-delivery.js";

export const MAX_GRANTEES_PER_DEVICE = 5;
export const MAX_SHARED_DEVICES_PER_ACCOUNT = 10;
export const DEVICE_SHARE_INVITATION_LIFETIME_MS = 72 * 60 * 60 * 1_000;

export interface DeviceShareInvitation {
  id: string;
  deviceId: string;
  targetEmailHint: string;
  status: "pending";
  expiresAt: string;
  createdAt: string;
}

export interface DeviceAccessGrant {
  id: string;
  deviceId: string;
  granteeEmailHint: string;
  role: "operator";
  status: "active";
  grantedAt: string;
}

export interface DeviceShareManagement {
  invitations: DeviceShareInvitation[];
  grants: DeviceAccessGrant[];
  maxGranteesPerDevice: typeof MAX_GRANTEES_PER_DEVICE;
}

export interface RevokedDeviceAccess {
  bindingId: string;
  deviceId: string;
  granteeAccountId: string;
  authorizationGeneration: number;
}

export type CreateShareInvitationResult =
  | {
      status: "created" | "replayed";
      invitation: DeviceShareInvitation;
      deviceDisplayName: string;
      needsDelivery: boolean;
    }
  | {
      status:
        | "not_found"
        | "reauthentication_failed"
        | "idempotency_conflict"
        | "invitation_conflict";
    };

export type ShareMutationResult =
  | { status: "completed" | "replayed"; revokedAccess?: RevokedDeviceAccess }
  | { status: "not_found" | "idempotency_conflict" };

export type AcceptShareInvitationResult =
  | { status: "accepted" | "replayed"; device: AccountDevice }
  | {
      status:
        | "not_found"
        | "email_mismatch"
        | "conflict"
        | "device_capacity_reached"
        | "account_capacity_reached"
        | "idempotency_conflict";
    };

export interface AccountDeviceAccessRepository {
  listSharedDevices(principal: AccountPrincipal): Promise<AccountDevice[]>;
  getSharedDevice(principal: AccountPrincipal, deviceId: string): Promise<AccountDevice | undefined>;
  selectAccessibleDefaultDevice(
    principal: AccountPrincipal,
    deviceId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<{ status: "completed" | "replayed"; device: AccountDevice }
    | { status: "not_found" | "idempotency_conflict" }>;
}

export interface AccountSharingRepository extends AccountDeviceAccessRepository {
  listShares(principal: AccountPrincipal, deviceId: string): Promise<DeviceShareManagement | undefined>;
  createInvitation(
    principal: AccountPrincipal,
    input: {
      invitationId: string;
      deviceId: string;
      targetEmailLookupHash: string;
      targetEmailHint: string;
      tokenHash: string;
      grantTokenHash: string;
      expiresAt: Date;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateShareInvitationResult>;
  markInvitationDelivery(
    invitationId: string,
    submission: EmailProviderSubmission,
    at: Date,
  ): Promise<boolean>;
  cancelInvitation(
    principal: AccountPrincipal,
    deviceId: string,
    invitationId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ShareMutationResult>;
  acceptInvitation(
    principal: AccountPrincipal,
    tokenHash: string,
    acceptedEmailLookupHashes: string[],
    idempotency: IdempotencyMaterial,
  ): Promise<AcceptShareInvitationResult>;
  revokeGrant(
    principal: AccountPrincipal,
    deviceId: string,
    grantId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ShareMutationResult>;
  leaveDevice(
    principal: AccountPrincipal,
    deviceId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ShareMutationResult>;
}

export interface DeviceShareEmailSender {
  sendDeviceShareInvitation(input: {
    messageId: string;
    recipient: string;
    ownerDisplayName?: string;
    deviceDisplayName: string;
    acceptUrl: string;
    expiresInHours: number;
  }): Promise<EmailProviderReceipt>;
}

export interface DeviceAccessRevocationListener {
  (access: RevokedDeviceAccess): void;
}
