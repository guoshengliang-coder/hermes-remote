import {
  createPublicKey,
  randomBytes,
  verify as verifySignature,
} from "node:crypto";
import { accountErrors } from "./model.js";
import type { AccountControlRepository, BindingProofMaterial } from "./account-control-model.js";

const CHALLENGE_LIFETIME_MS = 5_000;
const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

export interface ConnectorChallenge {
  bindingId: string;
  generation: number;
  publicKeyFingerprint: string;
  challenge: string;
  connectionNonce: string;
  serverTime: string;
  expiresAt: string;
}

interface PendingChallenge extends ConnectorChallenge {
  publicKey: Buffer;
  material: BindingProofMaterial;
}

export class ConnectorProofCoordinator {
  private readonly pending = new Map<string, PendingChallenge>();

  constructor(
    private readonly repository: AccountControlRepository,
    private readonly expectedGatewayOrigin: string,
    private readonly now: () => Date = () => new Date(),
    private readonly maximumPending = 256,
  ) {}

  async issue(input: {
    bindingId: string;
    generation: number;
    publicKeyFingerprint: string;
  }): Promise<ConnectorChallenge> {
    const material = await this.repository.loadBindingProofMaterial(
      input.bindingId,
      input.generation,
      input.publicKeyFingerprint,
    );
    if (!material) throw accountErrors.bindingProofFailed();
    const now = this.now();
    this.compact(now);
    if (this.pending.size >= this.maximumPending) throw accountErrors.bindingProofFailed();
    const connectionNonce = randomBytes(24).toString("base64url");
    const challenge: PendingChallenge = {
      bindingId: material.id,
      generation: material.generation,
      publicKeyFingerprint: material.publicKeyFingerprint,
      challenge: randomBytes(32).toString("base64url"),
      connectionNonce,
      serverTime: now.toISOString(),
      expiresAt: new Date(now.getTime() + CHALLENGE_LIFETIME_MS).toISOString(),
      publicKey: material.publicKey,
      material,
    };
    this.pending.set(connectionNonce, challenge);
    return publicChallenge(challenge);
  }

  async authenticate(input: {
    bindingId: string;
    generation: number;
    publicKeyFingerprint: string;
    connectionNonce: string;
    signature: string;
  }): Promise<BindingProofMaterial> {
    const challenge = this.pending.get(input.connectionNonce);
    this.pending.delete(input.connectionNonce);
    if (!challenge
        || Date.parse(challenge.expiresAt) <= this.now().getTime()
        || challenge.bindingId !== input.bindingId
        || challenge.generation !== input.generation
        || challenge.publicKeyFingerprint !== input.publicKeyFingerprint) {
      throw accountErrors.bindingProofFailed();
    }
    const signature = decodeSignature(input.signature);
    const publicKey = createPublicKey({
      key: Buffer.concat([ED25519_SPKI_PREFIX, challenge.publicKey]),
      format: "der",
      type: "spki",
    });
    const verified = verifySignature(
      null,
      canonicalConnectorChallenge(this.expectedGatewayOrigin, challenge),
      publicKey,
      signature,
    );
    if (!verified || !await this.repository.recordBindingKeyProof(
      challenge.bindingId,
      challenge.generation,
      challenge.publicKeyFingerprint,
    )) {
      throw accountErrors.bindingProofFailed();
    }
    return challenge.material;
  }

  private compact(now: Date): void {
    for (const [nonce, challenge] of this.pending) {
      if (Date.parse(challenge.expiresAt) <= now.getTime()) this.pending.delete(nonce);
    }
  }
}

export function canonicalConnectorChallenge(
  expectedGatewayOrigin: string,
  challenge: ConnectorChallenge,
): Buffer {
  const fields = [
    "hermes-go-connector-v2",
    expectedGatewayOrigin,
    challenge.bindingId,
    String(challenge.generation),
    challenge.publicKeyFingerprint,
    challenge.challenge,
    challenge.connectionNonce,
    challenge.serverTime,
    challenge.expiresAt,
  ];
  const encoded: Buffer[] = [];
  for (const field of fields) {
    const bytes = Buffer.from(field, "utf8");
    const length = Buffer.allocUnsafe(4);
    length.writeUInt32BE(bytes.byteLength);
    encoded.push(length, bytes);
  }
  return Buffer.concat(encoded);
}

function publicChallenge(value: PendingChallenge): ConnectorChallenge {
  return {
    bindingId: value.bindingId,
    generation: value.generation,
    publicKeyFingerprint: value.publicKeyFingerprint,
    challenge: value.challenge,
    connectionNonce: value.connectionNonce,
    serverTime: value.serverTime,
    expiresAt: value.expiresAt,
  };
}

function decodeSignature(value: string): Buffer {
  if (!/^[A-Za-z0-9_-]{86}$/.test(value)) throw accountErrors.bindingProofFailed();
  const decoded = Buffer.from(value, "base64url");
  if (decoded.byteLength !== 64 || decoded.toString("base64url") !== value) {
    throw accountErrors.bindingProofFailed();
  }
  return decoded;
}
