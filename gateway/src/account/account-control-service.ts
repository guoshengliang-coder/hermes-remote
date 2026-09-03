import { createHash, randomUUID } from "node:crypto";
import {
  accountErrors,
  type AccountPrincipal,
  type IdempotencyMaterial,
} from "./model.js";
import type {
  AccountControlRepository,
  ActiveBinding,
  BindingCandidate,
  BindingState,
  ManagedInstallation,
  ReplacementRequest,
} from "./account-control-model.js";
import { ProtectedResponseCodec } from "./protected-response-codec.js";
import { TokenCodec } from "./token-codec.js";
import type { LifecycleEventPage } from "../lifecycle-event-store.js";

const PENDING_BINDING_LIFETIME_MS = 10 * 60 * 1_000;
const MUTATION_IDEMPOTENCY_LIFETIME_MS = 24 * 60 * 60 * 1_000;

export class AccountControlService {
  private readonly protectedResponses: ProtectedResponseCodec;

  constructor(
    private readonly repository: AccountControlRepository,
    private readonly tokens: TokenCodec,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.protectedResponses = new ProtectedResponseCodec(
      tokens.deriveSubkey("account-control-idempotency-response-v1"),
    );
  }

  async listInstallations(principal: AccountPrincipal): Promise<ManagedInstallation[]> {
    requireDesktop(principal);
    return this.repository.listInstallations(principal);
  }

  async revokePhoneInstallation(
    principal: AccountPrincipal,
    targetInstallationId: string,
    idempotencyKey: string,
  ): Promise<void> {
    requireDesktop(principal);
    const idempotency = this.mutationIdempotency(
      "installation.revoke",
      idempotencyKey,
      `${principal.sessionId}\u0000${targetInstallationId}`,
    );
    const result = await this.repository.revokePhoneInstallation(
      principal,
      targetInstallationId,
      idempotency,
    );
    switch (result.status) {
      case "completed":
      case "replayed": return;
      case "not_found": throw accountErrors.resourceNotFound();
      case "invalid_target": throw accountErrors.invalidRequest("Only phone installations can be revoked here.");
      case "authorization_failed": throw accountErrors.sessionRevoked();
      case "idempotency_conflict": throw accountErrors.idempotencyConflict();
    }
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

  async getBinding(principal: AccountPrincipal): Promise<BindingState> {
    return this.repository.getBinding(principal);
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
