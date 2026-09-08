import { createHmac, randomBytes } from "node:crypto";

const TOKEN_BYTES = 32;

export class TokenCodec {
  private readonly hashKey: Buffer;

  constructor(hashKey: string | Buffer) {
    this.hashKey = Buffer.isBuffer(hashKey) ? Buffer.from(hashKey) : Buffer.from(hashKey, "utf8");
    if (this.hashKey.byteLength < 32) {
      throw new Error("ACCOUNT_TOKEN_HASH_KEY must contain at least 32 bytes");
    }
  }

  issueAccessToken(): string {
    return `hga_${randomBytes(TOKEN_BYTES).toString("base64url")}`;
  }

  issueRefreshToken(): string {
    return `hgr_${randomBytes(TOKEN_BYTES).toString("base64url")}`;
  }

  issueReauthenticationGrant(): string {
    return `hgg_${randomBytes(TOKEN_BYTES).toString("base64url")}`;
  }

  issueShareInvitationToken(invitationId: string): string {
    const payload = createHmac("sha256", this.hashKey)
      .update(`device-share-invitation-v1\u0000${invitationId}`, "utf8")
      .digest("base64url");
    return `hsi_${payload}`;
  }

  hashAccessToken(token: string): string | undefined {
    return this.hashPrefixedToken(token, "hga_");
  }

  hashRefreshToken(token: string): string | undefined {
    return this.hashPrefixedToken(token, "hgr_");
  }

  hashReauthenticationGrant(token: string): string | undefined {
    return this.hashPrefixedToken(token, "hgg_");
  }

  hashShareInvitationToken(token: string): string | undefined {
    return this.hashPrefixedToken(token, "hsi_");
  }

  hashContext(context: string): string {
    return createHmac("sha256", this.hashKey).update(`context\u0000${context}`, "utf8").digest("hex");
  }

  deriveSubkey(context: string): Buffer {
    return createHmac("sha256", this.hashKey).update(`subkey\u0000${context}`, "utf8").digest();
  }

  private hashPrefixedToken(
    token: string,
    prefix: "hga_" | "hgr_" | "hgg_" | "hsi_",
  ): string | undefined {
    if (!token.startsWith(prefix)) return undefined;
    const encoded = token.slice(prefix.length);
    if (!/^[A-Za-z0-9_-]{43}$/.test(encoded)) return undefined;
    return createHmac("sha256", this.hashKey).update(token, "utf8").digest("hex");
  }
}
