import {
  createHash,
  createPrivateKey,
  createPublicKey,
  sign as signMessage,
} from "node:crypto";
import { lstatSync, readFileSync, realpathSync, statSync } from "node:fs";
import { isAbsolute } from "node:path";
import {
  ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
  type ConnectorAuthenticate,
  type ConnectorChallengeMessage,
  type ConnectorIdentify,
  type ConnectorReady,
} from "@hermes-remote/protocol";

const PRIVATE_KEY_PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const FINGERPRINT = /^[0-9a-f]{64}$/;
const BASE64URL_32 = /^[A-Za-z0-9_-]{43}$/;

export interface AccountConnectorCredential {
  schemaVersion: 1;
  bindingId: string;
  generation: number;
  publicKeyFingerprint: string;
  privateKey: Buffer;
}

export function loadAccountConnectorCredential(
  filePath: string,
  expectedUserId = process.getuid?.(),
): AccountConnectorCredential {
  if (!isAbsolute(filePath)) throw new Error("HR-MIGRATE-001 account credential path must be absolute");
  const link = lstatSync(filePath);
  if (!link.isFile() || link.isSymbolicLink()) {
    throw new Error("HR-MIGRATE-001 account credential must be a regular file");
  }
  const resolved = realpathSync(filePath);
  if (resolved !== filePath) throw new Error("HR-MIGRATE-001 account credential path is not canonical");
  const metadata = statSync(resolved);
  if ((metadata.mode & 0o077) !== 0 || (expectedUserId !== undefined && metadata.uid !== expectedUserId)) {
    throw new Error("HR-MIGRATE-001 account credential permissions are unsafe");
  }
  if (metadata.size < 2 || metadata.size > 1_024) {
    throw new Error("HR-MIGRATE-001 account credential size is invalid");
  }
  return parseAccountConnectorCredential(readFileSync(resolved));
}

export function parseAccountConnectorCredential(data: Buffer): AccountConnectorCredential {
  let value: unknown;
  try {
    value = JSON.parse(data.toString("utf8"));
  } catch {
    throw new Error("HR-MIGRATE-001 account credential is invalid");
  }
  if (!isRecord(value)
      || !hasExactKeys(value, [
        "schemaVersion", "bindingId", "generation", "publicKeyFingerprint", "privateKey",
      ])
      || value.schemaVersion !== 1
      || typeof value.bindingId !== "string"
      || !UUID.test(value.bindingId)
      || !Number.isSafeInteger(value.generation)
      || (value.generation as number) < 1
      || (value.generation as number) > 2_147_483_647
      || typeof value.publicKeyFingerprint !== "string"
      || !FINGERPRINT.test(value.publicKeyFingerprint)
      || typeof value.privateKey !== "string"
      || !BASE64URL_32.test(value.privateKey)) {
    throw new Error("HR-MIGRATE-001 account credential is invalid");
  }
  const privateKey = Buffer.from(value.privateKey, "base64url");
  if (privateKey.byteLength !== 32 || privateKey.toString("base64url") !== value.privateKey) {
    throw new Error("HR-MIGRATE-001 account credential is invalid");
  }
  const keyObject = createPrivateKey({
    key: Buffer.concat([PRIVATE_KEY_PKCS8_PREFIX, privateKey]),
    format: "der",
    type: "pkcs8",
  });
  const publicDer = createPublicKey(keyObject).export({ format: "der", type: "spki" });
  const fingerprint = createHash("sha256").update(publicDer.subarray(-32)).digest("hex");
  if (fingerprint !== value.publicKeyFingerprint) {
    throw new Error("HR-MIGRATE-001 account credential key does not match its fingerprint");
  }
  return {
    schemaVersion: 1,
    bindingId: value.bindingId.toLowerCase(),
    generation: value.generation as number,
    publicKeyFingerprint: value.publicKeyFingerprint,
    privateKey,
  };
}

export class AccountConnectorAuthenticator {
  private readonly gatewayOrigin: string;

  constructor(
    private readonly credential: AccountConnectorCredential,
    gatewayURL: string,
    private readonly now: () => Date = () => new Date(),
  ) {
    this.gatewayOrigin = accountGatewayOrigin(gatewayURL);
  }

  identify(): ConnectorIdentify {
    return {
      type: "connector.identify",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId: this.credential.bindingId,
      generation: this.credential.generation,
      publicKeyFingerprint: this.credential.publicKeyFingerprint,
    };
  }

  authenticate(challenge: ConnectorChallengeMessage): ConnectorAuthenticate {
    const current = this.now().getTime();
    const serverTime = Date.parse(challenge.serverTime);
    const expiresAt = Date.parse(challenge.expiresAt);
    if (challenge.bindingId !== this.credential.bindingId
        || challenge.generation !== this.credential.generation
        || challenge.publicKeyFingerprint !== this.credential.publicKeyFingerprint
        || !Number.isFinite(serverTime)
        || !Number.isFinite(expiresAt)
        || Math.abs(serverTime - current) > 5 * 60_000
        || expiresAt <= current
        || expiresAt <= serverTime
        || expiresAt - serverTime > 10_000) {
      throw new Error("HR-BIND-005 Connector challenge did not match this machine");
    }
    const key = createPrivateKey({
      key: Buffer.concat([PRIVATE_KEY_PKCS8_PREFIX, this.credential.privateKey]),
      format: "der",
      type: "pkcs8",
    });
    const signature = signMessage(null, canonicalConnectorChallenge(this.gatewayOrigin, challenge), key);
    return {
      type: "connector.authenticate",
      version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
      bindingId: this.credential.bindingId,
      generation: this.credential.generation,
      publicKeyFingerprint: this.credential.publicKeyFingerprint,
      connectionNonce: challenge.connectionNonce,
      signature: signature.toString("base64url"),
    };
  }

  requireReady(ready: ConnectorReady): void {
    if (ready.bindingId !== this.credential.bindingId
        || ready.generation !== this.credential.generation) {
      throw new Error("HR-BIND-005 Connector ready response did not match this machine");
    }
  }
}

export function accountGatewayOrigin(gatewayURL: string): string {
  let url: URL;
  try {
    url = new URL(gatewayURL);
  } catch {
    throw new Error("HR-MIGRATE-001 account Gateway URL is invalid");
  }
  if (!(["wss:", "ws:"] as string[]).includes(url.protocol)
      || url.username || url.password || url.search || url.hash
      || url.pathname !== "/v2/connect") {
    throw new Error("HR-MIGRATE-001 account Gateway URL must use the exact /v2/connect path");
  }
  url.protocol = url.protocol === "wss:" ? "https:" : "http:";
  url.pathname = "";
  return url.origin;
}

export function canonicalConnectorChallenge(
  gatewayOrigin: string,
  challenge: ConnectorChallengeMessage,
): Buffer {
  const fields = [
    "hermes-go-connector-v2",
    gatewayOrigin,
    challenge.bindingId,
    String(challenge.generation),
    challenge.publicKeyFingerprint,
    challenge.challenge,
    challenge.connectionNonce,
    challenge.serverTime,
    challenge.expiresAt,
  ];
  const output: Buffer[] = [];
  for (const field of fields) {
    const bytes = Buffer.from(field, "utf8");
    const length = Buffer.allocUnsafe(4);
    length.writeUInt32BE(bytes.byteLength);
    output.push(length, bytes);
  }
  return Buffer.concat(output);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}
