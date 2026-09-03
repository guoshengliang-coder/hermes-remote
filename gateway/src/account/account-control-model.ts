import type {
  AccountPrincipal,
  IdempotencyMaterial,
  PublicInstallation,
} from "./model.js";
import type { SessionLifecycleEvent } from "@hermes-remote/protocol";
import type { LifecycleEventPage } from "../lifecycle-event-store.js";

export interface ManagedInstallation extends PublicInstallation {
  lastSeenAt: string;
  status: "active" | "revoked";
  current: boolean;
}

export interface BindingCandidate {
  id: string;
  generation: number;
  deviceId: string;
  displayName: string;
  publicKeyFingerprint: string;
  state: "binding_pending";
  expiresAt: string;
  keyProved: boolean;
  healthVerified: boolean;
}

export interface ActiveBinding {
  id: string;
  generation: number;
  deviceId: string;
  desktopDisplayName: string;
  publicKeyFingerprint: string;
  connector: { online: boolean; lastSeenAt?: string };
  hermes: { reachable: boolean | null; version?: string };
  gateway: { latencyMs?: number };
  endToEnd: { healthy: boolean | null; checkedAt?: string };
}

export interface ReplacementRequest {
  id: string;
  state: "replacement_pending";
  expiresAt: string;
  previousBinding: ActiveBinding;
  candidate: BindingCandidate;
}

export type BindingState =
  | { state: "no_binding" }
  | BindingCandidate
  | { state: "bound"; binding: ActiveBinding }
  | ReplacementRequest
  | { state: "revoked"; generation: number };

export interface BindingProofMaterial {
  id: string;
  accountId: string;
  deviceId: string;
  generation: number;
  publicKey: Buffer;
  publicKeyFingerprint: string;
  status: "pending" | "active";
  expiresAt?: Date;
}

export type CreateBindingResult =
  | { status: "created"; binding: BindingCandidate }
  | { status: "replayed"; binding: BindingCandidate }
  | { status: "conflict" | "installation_invalid" | "idempotency_conflict" };

export type ConfirmBindingResult =
  | { status: "activated"; binding: ActiveBinding }
  | { status: "replayed"; binding: ActiveBinding }
  | {
      status:
        | "not_found"
        | "expired"
        | "proof_required"
        | "conflict"
        | "idempotency_conflict";
    };

export type RevokeInstallationResult =
  | { status: "completed" | "replayed" }
  | {
      status: "not_found" | "invalid_target" | "authorization_failed" | "idempotency_conflict";
    };

export type CreateReplacementResult =
  | { status: "created" | "replayed"; request: ReplacementRequest }
  | {
      status:
        | "not_found"
        | "conflict"
        | "installation_invalid"
        | "reauthentication_failed"
        | "idempotency_conflict";
    };

export type ConfirmReplacementResult =
  | { status: "activated" | "replayed"; binding: ActiveBinding }
  | {
      status:
        | "not_found"
        | "expired"
        | "proof_required"
        | "conflict"
        | "installation_invalid"
        | "idempotency_conflict";
    };

export type UnbindResult =
  | { status: "completed" | "replayed" }
  | {
      status:
        | "not_found"
        | "installation_invalid"
        | "reauthentication_failed"
        | "idempotency_conflict";
    };

export type CurrentInstallationRevocationResult =
  | { status: "completed" | "replayed" }
  | {
      status:
        | "invalid"
        | "expired"
        | "revoked"
        | "account_disabled"
        | "invalid_target"
        | "idempotency_conflict";
    };

export type AccountLifecycleIngestResult =
  | { status: "stored" | "duplicate" }
  | { status: "binding_invalid" | "event_id_conflict" };

export interface AccountControlRepository {
  listInstallations(principal: AccountPrincipal): Promise<ManagedInstallation[]>;
  revokePhoneInstallation(
    principal: AccountPrincipal,
    targetInstallationId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<RevokeInstallationResult>;
  revokeCurrentPhoneInstallation(
    accessTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<CurrentInstallationRevocationResult>;
  getBinding(principal: AccountPrincipal): Promise<BindingState>;
  createPendingBinding(
    principal: AccountPrincipal,
    input: {
      bindingId: string;
      displayName: string;
      deviceId: string;
      publicKey: Buffer;
      publicKeyFingerprint: string;
      expiresAt: Date;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateBindingResult>;
  confirmPendingBinding(
    principal: AccountPrincipal,
    bindingId: string,
    generation: number,
    idempotency: IdempotencyMaterial,
  ): Promise<ConfirmBindingResult>;
  createReplacementRequest(
    principal: AccountPrincipal,
    input: {
      requestId: string;
      bindingId: string;
      displayName: string;
      deviceId: string;
      publicKey: Buffer;
      publicKeyFingerprint: string;
      expiresAt: Date;
      grantTokenHash: string;
    },
    idempotency: IdempotencyMaterial,
  ): Promise<CreateReplacementResult>;
  confirmReplacementRequest(
    principal: AccountPrincipal,
    requestId: string,
    idempotency: IdempotencyMaterial,
  ): Promise<ConfirmReplacementResult>;
  unbindConnector(
    principal: AccountPrincipal,
    grantTokenHash: string,
    idempotency: IdempotencyMaterial,
  ): Promise<UnbindResult>;
  loadBindingProofMaterial(
    bindingId: string,
    generation: number,
    fingerprint: string,
  ): Promise<BindingProofMaterial | undefined>;
  recordBindingKeyProof(bindingId: string, generation: number, fingerprint: string): Promise<boolean>;
  recordBindingHealth(
    bindingId: string,
    generation: number,
    health: {
      hermesReachable: boolean;
      hermesVersion?: string;
      gatewayLatencyMs: number;
      endToEndHealthy: boolean;
    },
  ): Promise<boolean>;
  recordBindingDisconnected(bindingId: string, generation: number, fingerprint: string): Promise<boolean>;
  ingestAccountLifecycleEvent(
    material: BindingProofMaterial,
    event: SessionLifecycleEvent,
  ): Promise<AccountLifecycleIngestResult>;
  listAccountLifecycleEvents(
    principal: AccountPrincipal,
    after: number,
    limit: number,
  ): Promise<LifecycleEventPage>;
  markAccountLifecycleEvents(
    principal: AccountPrincipal,
    eventIds: string[],
    field: "delivered" | "read",
  ): Promise<number>;
}
