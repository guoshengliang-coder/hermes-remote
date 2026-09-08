import { randomUUID } from "node:crypto";
import {
  DEVICE_SHARE_INVITATION_LIFETIME_MS,
  type AccountSharingRepository,
  type DeviceAccessGrant,
  type DeviceAccessRevocationListener,
  type DeviceShareEmailSender,
  type DeviceShareInvitation,
  type DeviceShareManagement,
} from "./account-sharing-model.js";
import type { AccountDevice } from "./account-control-model.js";
import { normalizeEmailAddress } from "./email-otp.js";
import {
  accountErrors,
  type AccountPrincipal,
  type IdempotencyMaterial,
  type PublicExternalIdentity,
} from "./model.js";
import { ProtectedResponseCodec } from "./protected-response-codec.js";
import { TokenCodec } from "./token-codec.js";

const MUTATION_IDEMPOTENCY_LIFETIME_MS = 24 * 60 * 60 * 1_000;

export class AccountSharingService {
  private readonly protectedResponses: ProtectedResponseCodec;
  private readonly revocationListeners = new Set<DeviceAccessRevocationListener>();

  constructor(
    readonly repository: AccountSharingRepository,
    private readonly tokens: TokenCodec,
    private readonly sender: DeviceShareEmailSender,
    private readonly listIdentities: (principal: AccountPrincipal) => Promise<PublicExternalIdentity[]>,
    private readonly accountCenterOrigin: string,
    private readonly now: () => Date = () => new Date(),
  ) {
    const origin = new URL(accountCenterOrigin);
    if (origin.protocol !== "https:" || origin.origin !== accountCenterOrigin || origin.pathname !== "/") {
      throw new Error("device sharing account-center origin must be an exact HTTPS origin");
    }
    this.protectedResponses = new ProtectedResponseCodec(
      tokens.deriveSubkey("account-sharing-idempotency-response-v1"),
    );
  }

  subscribeRevocations(listener: DeviceAccessRevocationListener): () => void {
    this.revocationListeners.add(listener);
    return () => this.revocationListeners.delete(listener);
  }

  async listShares(principal: AccountPrincipal, deviceId: string): Promise<DeviceShareManagement> {
    validateDeviceId(deviceId);
    const result = await this.repository.listShares(principal, deviceId);
    if (!result) throw accountErrors.deviceNotFound();
    return result;
  }

  async createInvitation(
    principal: AccountPrincipal,
    input: {
      deviceId: string;
      email: string;
      grant: string;
      acknowledgedWholeDeviceAccess: boolean;
      idempotencyKey: string;
    },
  ): Promise<DeviceShareInvitation> {
    validateDeviceId(input.deviceId);
    requireWholeDeviceAcknowledgement(input.acknowledgedWholeDeviceAccess);
    const email = normalizedEmail(input.email);
    const emailLookupHash = this.emailLookupHash(email);
    const ownEmailHashes = await this.identityEmailHashes(principal);
    if (ownEmailHashes.includes(emailLookupHash)) throw accountErrors.shareConflict();
    const grantTokenHash = this.tokens.hashReauthenticationGrant(input.grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();

    const invitationId = randomUUID();
    const token = this.tokens.issueShareInvitationToken(invitationId);
    const tokenHash = this.tokens.hashShareInvitationToken(token);
    if (!tokenHash) throw new Error("issued sharing invitation token did not match its format");
    const expiresAt = new Date(this.now().getTime() + DEVICE_SHARE_INVITATION_LIFETIME_MS);
    const result = await this.repository.createInvitation(principal, {
      invitationId,
      deviceId: input.deviceId,
      targetEmailLookupHash: emailLookupHash,
      targetEmailHint: maskEmail(email),
      tokenHash,
      grantTokenHash,
      expiresAt,
    }, this.idempotency(
      "device.share.invitation.create",
      input.idempotencyKey,
      [
        principal.account.id,
        principal.installation.id,
        principal.sessionId,
        input.deviceId,
        emailLookupHash,
        grantTokenHash,
        "whole-device-v1",
      ].join("\u0000"),
    ));

    switch (result.status) {
      case "not_found": throw accountErrors.deviceNotFound();
      case "reauthentication_failed": throw accountErrors.reauthenticationRequired();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "invitation_conflict": throw accountErrors.shareConflict();
      case "created":
      case "replayed": break;
    }

    if (result.needsDelivery) {
      const replayToken = this.tokens.issueShareInvitationToken(result.invitation.id);
      try {
        const receipt = await this.sender.sendDeviceShareInvitation({
          messageId: result.invitation.id,
          recipient: email,
          ...(principal.account.displayName ? { ownerDisplayName: principal.account.displayName } : {}),
          deviceDisplayName: result.deviceDisplayName,
          acceptUrl: new URL(
            `/account#share-invitation=${encodeURIComponent(replayToken)}`,
            this.accountCenterOrigin,
          ).toString(),
          expiresInHours: DEVICE_SHARE_INVITATION_LIFETIME_MS / (60 * 60 * 1_000),
        });
        if (!await this.repository.markInvitationDelivery(result.invitation.id, {
          status: "accepted",
          providerMessageId: receipt.providerMessageId,
        }, this.now())) {
          throw accountErrors.unavailable();
        }
      } catch {
        await this.repository.markInvitationDelivery(
          result.invitation.id,
          { status: "failed" },
          this.now(),
        ).catch(() => {});
        throw accountErrors.shareDeliveryFailed();
      }
    }
    return result.invitation;
  }

  async cancelInvitation(
    principal: AccountPrincipal,
    deviceId: string,
    invitationId: string,
    idempotencyKey: string,
  ): Promise<void> {
    validateDeviceId(deviceId);
    const result = await this.repository.cancelInvitation(
      principal,
      deviceId,
      invitationId,
      this.idempotency(
        "device.share.invitation.cancel",
        idempotencyKey,
        [principal.account.id, principal.sessionId, deviceId, invitationId].join("\u0000"),
      ),
    );
    mapMutationResult(result.status);
  }

  async acceptInvitation(
    principal: AccountPrincipal,
    token: string,
    acknowledgedWholeDeviceAccess: boolean,
    idempotencyKey: string,
  ): Promise<AccountDevice> {
    requireWholeDeviceAcknowledgement(acknowledgedWholeDeviceAccess);
    const tokenHash = this.tokens.hashShareInvitationToken(token);
    if (!tokenHash) throw accountErrors.shareInvitationInvalid();
    const emailHashes = await this.identityEmailHashes(principal);
    if (emailHashes.length === 0) throw accountErrors.shareEmailMismatch();
    const result = await this.repository.acceptInvitation(
      principal,
      tokenHash,
      emailHashes,
      this.idempotency(
        "device.share.invitation.accept",
        idempotencyKey,
        [principal.account.id, principal.sessionId, tokenHash, "whole-device-v1"].join("\u0000"),
      ),
    );
    switch (result.status) {
      case "accepted":
      case "replayed": return result.device;
      case "not_found": throw accountErrors.shareInvitationInvalid();
      case "email_mismatch": throw accountErrors.shareEmailMismatch();
      case "device_capacity_reached": throw accountErrors.sharingCapacityReached();
      case "account_capacity_reached": throw accountErrors.sharedDeviceCapacityReached();
      case "conflict": throw accountErrors.shareConflict();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async revokeGrant(
    principal: AccountPrincipal,
    deviceId: string,
    grantId: string,
    idempotencyKey: string,
  ): Promise<void> {
    validateDeviceId(deviceId);
    const result = await this.repository.revokeGrant(
      principal,
      deviceId,
      grantId,
      this.idempotency(
        "device.share.grant.revoke",
        idempotencyKey,
        [principal.account.id, principal.sessionId, deviceId, grantId].join("\u0000"),
      ),
    );
    mapMutationResult(result.status);
    if (result.status === "completed" && result.revokedAccess) this.publishRevocation(result.revokedAccess);
  }

  async leaveDevice(
    principal: AccountPrincipal,
    deviceId: string,
    idempotencyKey: string,
  ): Promise<void> {
    validateDeviceId(deviceId);
    const result = await this.repository.leaveDevice(
      principal,
      deviceId,
      this.idempotency(
        "device.share.grant.leave",
        idempotencyKey,
        [principal.account.id, principal.sessionId, deviceId].join("\u0000"),
      ),
    );
    mapMutationResult(result.status);
    if (result.status === "completed" && result.revokedAccess) this.publishRevocation(result.revokedAccess);
  }

  private async identityEmailHashes(principal: AccountPrincipal): Promise<string[]> {
    const identities = await this.listIdentities(principal);
    const hashes = identities.flatMap(({ email }) => {
      if (!email) return [];
      try {
        return [this.emailLookupHash(normalizeEmailAddress(email))];
      } catch {
        return [];
      }
    });
    return [...new Set(hashes)];
  }

  private emailLookupHash(email: string): string {
    return this.tokens.hashContext(`device-share-email-v1\u0000${email}`);
  }

  private idempotency(operation: string, key: string, identity: string): IdempotencyMaterial {
    return {
      key,
      requestHash: this.tokens.hashContext(`${operation}\u0000${identity}`),
      responseCiphertext: this.protectedResponses.seal(operation, { status: "completed" }),
      expiresAt: new Date(this.now().getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
    };
  }

  private publishRevocation(access: Parameters<DeviceAccessRevocationListener>[0]): void {
    for (const listener of this.revocationListeners) listener(access);
  }
}

function mapMutationResult(status: "completed" | "replayed" | "not_found" | "idempotency_conflict"): void {
  if (status === "completed" || status === "replayed") return;
  if (status === "not_found") throw accountErrors.deviceNotFound();
  throw accountErrors.idempotencyConflict();
}

function requireWholeDeviceAcknowledgement(value: boolean): void {
  if (value !== true) throw accountErrors.wholeDeviceAcknowledgementRequired();
}

function normalizedEmail(value: string): string {
  try {
    return normalizeEmailAddress(value);
  } catch {
    throw accountErrors.invalidRequest("email must be a valid mailbox address.");
  }
}

function maskEmail(email: string): string {
  const separator = email.lastIndexOf("@");
  return `${email[0]}***${email.slice(separator)}`;
}

function validateDeviceId(deviceId: string): void {
  if (deviceId.length < 1 || deviceId.length > 128 || /[\u0000-\u001f\u007f]/.test(deviceId)) {
    throw accountErrors.invalidRequest("deviceId contains unsupported characters.");
  }
}

export type { DeviceAccessGrant };
