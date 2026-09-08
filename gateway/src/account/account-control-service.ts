import { createHash, randomUUID } from "node:crypto";
import {
  accountErrors,
  type AccountPrincipal,
  type IdempotencyMaterial,
} from "./model.js";
import type {
  AccountControlRepository,
  AccountDevice,
  AccountSecurityInstallation,
  ActiveBinding,
  BindingCandidate,
  BindingState,
  ManagedInstallation,
  PublicAccountAuditEvent,
  ReplacementRequest,
} from "./account-control-model.js";
import { ProtectedResponseCodec } from "./protected-response-codec.js";
import { TokenCodec } from "./token-codec.js";
import type { LifecycleEventPage } from "../lifecycle-event-store.js";
import type { AccountDeviceAccessRepository } from "./account-sharing-model.js";

const PENDING_BINDING_LIFETIME_MS = 10 * 60 * 1_000;
const MUTATION_IDEMPOTENCY_LIFETIME_MS = 24 * 60 * 60 * 1_000;

export class AccountControlService {
  private readonly protectedResponses: ProtectedResponseCodec;

  constructor(
    private readonly repository: AccountControlRepository,
    private readonly tokens: TokenCodec,
    private readonly now: () => Date = () => new Date(),
    private readonly maxOwnedDevices = 1,
    private readonly sharedAccess?: AccountDeviceAccessRepository,
  ) {
    if (!Number.isSafeInteger(maxOwnedDevices) || maxOwnedDevices < 1 || maxOwnedDevices > 3) {
      throw new Error("maxOwnedDevices must be an integer between 1 and 3");
    }
    this.protectedResponses = new ProtectedResponseCodec(
      tokens.deriveSubkey("account-control-idempotency-response-v1"),
    );
  }

  async listInstallations(principal: AccountPrincipal): Promise<ManagedInstallation[]> {
    requireDesktop(principal);
    return this.repository.listInstallations(principal);
  }

  async revokeCurrentPhoneInstallation(
    authorization: string | undefined,
    idempotencyKey: string,
  ): Promise<void> {
    const accessTokenHash = this.accessTokenHash(authorization);
    const result = await this.repository.revokeCurrentPhoneInstallation(
      accessTokenHash,
      this.mutationIdempotency(
        "installation.revoke.current",
        idempotencyKey,
        accessTokenHash,
      ),
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "account_disabled": throw accountErrors.accountDisabled();
      case "revoked": throw accountErrors.sessionRevoked();
      case "invalid_target": throw accountErrors.invalidRequest("Only phone installations can revoke themselves.");
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
      case "expired":
      case "invalid": throw accountErrors.sessionExpired();
    }
  }

  async listAccountInstallations(
    principal: AccountPrincipal,
  ): Promise<AccountSecurityInstallation[]> {
    if (!this.repository.listAccountInstallations) throw accountErrors.identityFeatureDisabled();
    return this.repository.listAccountInstallations(principal);
  }

  async revokeAccountInstallation(
    principal: AccountPrincipal,
    targetInstallationId: string,
    grant: string,
    idempotencyKey: string,
    requiredKind?: ManagedInstallation["kind"],
  ): Promise<void> {
    if (!this.repository.revokeAccountInstallation) throw accountErrors.identityFeatureDisabled();
    if (targetInstallationId === principal.installation.id) {
      throw accountErrors.invalidRequest("The current installation must sign out itself.");
    }
    const grantTokenHash = this.tokens.hashReauthenticationGrant(grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const result = await this.repository.revokeAccountInstallation(
      principal,
      targetInstallationId,
      grantTokenHash,
      this.mutationIdempotency(
        "account.installation.revoke",
        idempotencyKey,
        [
          principal.sessionId,
          targetInstallationId,
          grantTokenHash,
          requiredKind ?? "any",
        ].join("\u0000"),
      ),
      requiredKind,
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "not_found": throw accountErrors.resourceNotFound();
      case "invalid_target": throw accountErrors.invalidRequest(
        "Only phone installations can be revoked here.",
      );
      case "current_installation": throw accountErrors.invalidRequest(
        "The current installation must sign out itself.",
      );
      case "authorization_failed": throw accountErrors.sessionRevoked();
      case "reauthentication_failed": throw accountErrors.reauthenticationRequired();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async revokeManagedPhoneInstallation(
    principal: AccountPrincipal,
    targetInstallationId: string,
    grant: string,
    idempotencyKey: string,
  ): Promise<void> {
    requireDesktop(principal);
    await this.revokeAccountInstallation(
      principal,
      targetInstallationId,
      grant,
      idempotencyKey,
      "phone",
    );
  }

  async listAccountAuditEvents(
    principal: AccountPrincipal,
    limit = 50,
  ): Promise<PublicAccountAuditEvent[]> {
    if (!this.repository.listAccountAuditEvents) throw accountErrors.identityFeatureDisabled();
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
      throw accountErrors.invalidRequest("limit must be an integer between 1 and 100.");
    }
    return this.repository.listAccountAuditEvents(principal, limit);
  }

  async getBinding(principal: AccountPrincipal): Promise<BindingState> {
    if (this.maxOwnedDevices > 1 && principal.installation.kind === "phone") {
      const devices = await this.repository.listDevices(principal);
      if (devices.length > 1) throw accountErrors.deviceSelectionRequired();
    }
    return this.repository.getBinding(principal);
  }

  async listDevices(principal: AccountPrincipal): Promise<AccountDevice[]> {
    const owned = await this.repository.listDevices(principal);
    if (!this.sharedAccess) return owned;
    const shared = await this.sharedAccess.listSharedDevices(principal);
    return [...owned, ...shared].sort((left, right) => {
      if (left.isDefault !== right.isDefault) return left.isDefault ? -1 : 1;
      if (left.access !== right.access) return left.access === "owner" ? -1 : 1;
      return left.deviceId.localeCompare(right.deviceId);
    });
  }

  async getDevice(principal: AccountPrincipal, deviceId: string): Promise<AccountDevice> {
    validateDeviceId(deviceId);
    const device = await this.repository.getDevice(principal, deviceId)
      ?? await this.sharedAccess?.getSharedDevice(principal, deviceId);
    if (!device) throw accountErrors.deviceNotFound();
    return device;
  }

  async selectDefaultDevice(
    principal: AccountPrincipal,
    deviceId: string,
    idempotencyKey: string,
  ): Promise<AccountDevice> {
    validateDeviceId(deviceId);
    const mutation = this.mutationIdempotency(
      "device.default.select",
      idempotencyKey,
      [principal.account.id, principal.sessionId, deviceId].join("\u0000"),
    );
    const result = this.sharedAccess
      ? await this.sharedAccess.selectAccessibleDefaultDevice(principal, deviceId, mutation)
      : await this.repository.selectDefaultDevice(principal, deviceId, mutation);
    switch (result.status) {
      case "completed":
      case "replayed": return result.device;
      case "not_found": throw accountErrors.deviceNotFound();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async resolveDevice(principal: AccountPrincipal, deviceId?: string): Promise<AccountDevice> {
    if (deviceId) return this.getDevice(principal, deviceId);
    const devices = await this.listDevices(principal);
    if (devices.length === 0) throw accountErrors.bindingMissing();
    if (devices.length !== 1) throw accountErrors.deviceSelectionRequired();
    return devices[0];
  }

  async listLifecycleEvents(
    principal: AccountPrincipal,
    after: number,
    limit: number,
  ): Promise<LifecycleEventPage> {
    requirePhone(principal);
    return this.repository.listAccountLifecycleEvents(principal, after, limit);
  }

  async markLifecycleEvents(
    principal: AccountPrincipal,
    eventIds: string[],
    field: "delivered" | "read",
  ): Promise<number> {
    requirePhone(principal);
    return this.repository.markAccountLifecycleEvents(principal, eventIds, field);
  }

  async createPendingBinding(
    principal: AccountPrincipal,
    input: {
      desktopInstallationId: string;
      displayName: string;
      connectorPublicKey: string;
      keyAlgorithm: string;
      idempotencyKey: string;
    },
  ): Promise<BindingCandidate> {
    requireDesktop(principal);
    if (input.desktopInstallationId !== principal.installation.id) {
      throw accountErrors.desktopRequired();
    }
    const publicKey = validateBindingInput(input);
    const publicKeyFingerprint = createHash("sha256").update(publicKey).digest("hex");
    const bindingId = randomUUID();
    const expiresAt = new Date(this.now().getTime() + PENDING_BINDING_LIFETIME_MS);
    const idempotency: IdempotencyMaterial = {
      key: input.idempotencyKey,
      requestHash: this.tokens.hashContext([
        "connector.binding.create",
        principal.account.id,
        principal.installation.id,
        principal.sessionId,
        input.displayName,
        input.keyAlgorithm,
        input.connectorPublicKey,
      ].join("\u0000")),
      responseCiphertext: this.protectedResponses.seal(
        "connector.binding.create",
        { status: "completed" },
      ),
      expiresAt: new Date(this.now().getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
    };
    const result = await this.repository.createPendingBinding(
      principal,
      {
        bindingId,
        displayName: input.displayName,
        deviceId: `hermes-${bindingId}`,
        publicKey,
        publicKeyFingerprint,
        expiresAt,
      },
      idempotency,
    );
    switch (result.status) {
      case "created": return result.binding;
      case "replayed": return result.binding;
      case "capacity_reached": throw accountErrors.deviceCapacityReached();
      case "conflict": throw accountErrors.bindingConflict();
      case "installation_invalid": throw accountErrors.desktopRequired();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async confirmPendingBinding(
    principal: AccountPrincipal,
    input: { bindingId: string; generation: number; idempotencyKey: string },
  ): Promise<ActiveBinding> {
    requireDesktop(principal);
    const idempotency: IdempotencyMaterial = {
      key: input.idempotencyKey,
      requestHash: this.tokens.hashContext([
        "connector.binding.confirm",
        principal.account.id,
        principal.installation.id,
        principal.sessionId,
        input.bindingId,
        String(input.generation),
      ].join("\u0000")),
      responseCiphertext: this.protectedResponses.seal(
        "connector.binding.confirm",
        { status: "completed" },
      ),
      expiresAt: new Date(this.now().getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
    };
    const result = await this.repository.confirmPendingBinding(
      principal,
      input.bindingId,
      input.generation,
      idempotency,
    );
    switch (result.status) {
      case "activated": return result.binding;
      case "replayed": return result.binding;
      case "not_found": throw accountErrors.resourceNotFound();
      case "expired": throw accountErrors.bindingExpired();
      case "proof_required": throw accountErrors.bindingProofFailed();
      case "conflict": throw accountErrors.bindingConflict();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async createReplacementRequest(
    principal: AccountPrincipal,
    input: {
      desktopInstallationId: string;
      displayName: string;
      connectorPublicKey: string;
      keyAlgorithm: string;
      grant: string;
      idempotencyKey: string;
    },
  ): Promise<ReplacementRequest> {
    requireDesktop(principal);
    if (input.desktopInstallationId !== principal.installation.id) {
      throw accountErrors.desktopRequired();
    }
    const publicKey = validateBindingInput(input);
    const grantTokenHash = this.tokens.hashReauthenticationGrant(input.grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const requestId = randomUUID();
    const bindingId = randomUUID();
    const expiresAt = new Date(this.now().getTime() + PENDING_BINDING_LIFETIME_MS);
    const idempotency = this.mutationIdempotency(
      "connector.binding.replace.create",
      input.idempotencyKey,
      [
        principal.account.id,
        principal.installation.id,
        principal.sessionId,
        input.displayName,
        input.keyAlgorithm,
        input.connectorPublicKey,
        grantTokenHash,
      ].join("\u0000"),
    );
    const result = await this.repository.createReplacementRequest(
      principal,
      {
        requestId,
        bindingId,
        displayName: input.displayName,
        deviceId: `hermes-${bindingId}`,
        publicKey,
        publicKeyFingerprint: createHash("sha256").update(publicKey).digest("hex"),
        expiresAt,
        grantTokenHash,
      },
      idempotency,
    );
    switch (result.status) {
      case "created":
      case "replayed": return result.request;
      case "not_found": throw accountErrors.resourceNotFound();
      case "conflict": throw accountErrors.bindingConflict();
      case "installation_invalid": throw accountErrors.desktopRequired();
      case "reauthentication_failed": throw accountErrors.reauthenticationRequired();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async confirmReplacementRequest(
    principal: AccountPrincipal,
    input: { requestId: string; idempotencyKey: string },
  ): Promise<ActiveBinding> {
    requireDesktop(principal);
    const result = await this.repository.confirmReplacementRequest(
      principal,
      input.requestId,
      this.mutationIdempotency(
        "connector.binding.replace.confirm",
        input.idempotencyKey,
        [principal.account.id, principal.installation.id, principal.sessionId, input.requestId].join("\u0000"),
      ),
    );
    switch (result.status) {
      case "activated":
      case "replayed": return result.binding;
      case "not_found": throw accountErrors.resourceNotFound();
      case "expired": throw accountErrors.bindingExpired();
      case "proof_required": throw accountErrors.bindingProofFailed();
      case "conflict": throw accountErrors.bindingReplacementFailed();
      case "installation_invalid": throw accountErrors.desktopRequired();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async unbindConnector(
    principal: AccountPrincipal,
    input: { grant: string; idempotencyKey: string },
  ): Promise<void> {
    requireDesktop(principal);
    const grantTokenHash = this.tokens.hashReauthenticationGrant(input.grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const result = await this.repository.unbindConnector(
      principal,
      grantTokenHash,
      this.mutationIdempotency(
        "connector.binding.unbind",
        input.idempotencyKey,
        [
          principal.account.id,
          principal.installation.id,
          principal.sessionId,
          grantTokenHash,
        ].join("\u0000"),
      ),
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "not_found": throw accountErrors.resourceNotFound();
      case "installation_invalid": throw accountErrors.desktopRequired();
      case "reauthentication_failed": throw accountErrors.reauthenticationRequired();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  async unbindDevice(
    principal: AccountPrincipal,
    input: { deviceId: string; grant: string; idempotencyKey: string },
  ): Promise<void> {
    validateDeviceId(input.deviceId);
    const grantTokenHash = this.tokens.hashReauthenticationGrant(input.grant);
    if (!grantTokenHash) throw accountErrors.reauthenticationRequired();
    const result = await this.repository.unbindDevice(
      principal,
      input.deviceId,
      grantTokenHash,
      this.mutationIdempotency(
        "device.unbind",
        input.idempotencyKey,
        [
          principal.account.id,
          principal.installation.id,
          principal.sessionId,
          input.deviceId,
          grantTokenHash,
        ].join("\u0000"),
      ),
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "not_found": throw accountErrors.deviceNotFound();
      case "reauthentication_failed": throw accountErrors.reauthenticationRequired();
      case "installation_invalid": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
  }

  private mutationIdempotency(operation: string, key: string, requestIdentity: string): IdempotencyMaterial {
    return {
      key,
      requestHash: this.tokens.hashContext(`${operation}\u0000${requestIdentity}`),
      responseCiphertext: this.protectedResponses.seal(operation, { status: "completed" }),
      expiresAt: new Date(this.now().getTime() + MUTATION_IDEMPOTENCY_LIFETIME_MS),
    };
  }

  private accessTokenHash(authorization: string | undefined): string {
    const match = /^Bearer ([^ ]+)$/.exec(authorization ?? "");
    const tokenHash = match ? this.tokens.hashAccessToken(match[1]) : undefined;
    if (!tokenHash) throw accountErrors.sessionExpired();
    return tokenHash;
  }
}

function validateDeviceId(deviceId: string): void {
  if (deviceId.length < 1 || deviceId.length > 128 || /[\u0000-\u001f\u007f]/.test(deviceId)) {
    throw accountErrors.invalidRequest("deviceId contains unsupported characters.");
  }
}

function requireDesktop(principal: AccountPrincipal): void {
  if (principal.installation.kind !== "desktop" || principal.installation.platform !== "macos") {
    throw accountErrors.desktopRequired();
  }
}

function requirePhone(principal: AccountPrincipal): void {
  if (principal.installation.kind !== "phone" || principal.installation.platform !== "android") {
    throw accountErrors.invalidRequest("Lifecycle events are available only to phone installations.");
  }
}

function decodePublicKey(value: string): Buffer {
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) {
    throw accountErrors.invalidRequest("connectorPublicKey must be a 32-byte base64url value.");
  }
  const decoded = Buffer.from(value, "base64url");
  if (decoded.byteLength !== 32 || decoded.toString("base64url") !== value) {
    throw accountErrors.invalidRequest("connectorPublicKey must be a canonical 32-byte base64url value.");
  }
  return decoded;
}

function validateBindingInput(input: {
  displayName: string;
  connectorPublicKey: string;
  keyAlgorithm: string;
}): Buffer {
  if (input.keyAlgorithm !== "Ed25519") {
    throw accountErrors.invalidRequest("keyAlgorithm must be Ed25519.");
  }
  if (input.displayName.length < 1
      || input.displayName.length > 128
      || input.displayName.trim().length === 0
      || /[\u0000-\u001f\u007f]/.test(input.displayName)) {
    throw accountErrors.invalidRequest("displayName contains unsupported characters.");
  }
  return decodePublicKey(input.connectorPublicKey);
}
