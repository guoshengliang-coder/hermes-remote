import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

const VERSION = "v1";
const NONCE_BYTES = 12;

export class ProtectedResponseCodec {
  constructor(private readonly key: Buffer) {
    if (key.byteLength !== 32) throw new Error("protected response key must contain 32 bytes");
  }

  seal(context: string, value: unknown): string {
    const nonce = randomBytes(NONCE_BYTES);
    const cipher = createCipheriv("aes-256-gcm", this.key, nonce);
    cipher.setAAD(Buffer.from(context, "utf8"));
    const ciphertext = Buffer.concat([
      cipher.update(JSON.stringify(value), "utf8"),
      cipher.final(),
    ]);
    const tag = cipher.getAuthTag();
    return [VERSION, nonce.toString("base64url"), ciphertext.toString("base64url"), tag.toString("base64url")].join(".");
  }

  open(context: string, protectedValue: string): unknown {
    const [version, encodedNonce, encodedCiphertext, encodedTag, extra] = protectedValue.split(".");
    if (version !== VERSION || !encodedNonce || !encodedCiphertext || !encodedTag || extra !== undefined) {
      throw new Error("invalid protected response envelope");
    }
    const nonce = Buffer.from(encodedNonce, "base64url");
    const ciphertext = Buffer.from(encodedCiphertext, "base64url");
    const tag = Buffer.from(encodedTag, "base64url");
    if (nonce.byteLength !== NONCE_BYTES || tag.byteLength !== 16 || ciphertext.byteLength > 16_384) {
      throw new Error("invalid protected response bounds");
    }
    const decipher = createDecipheriv("aes-256-gcm", this.key, nonce, { authTagLength: 16 });
    decipher.setAAD(Buffer.from(context, "utf8"));
    decipher.setAuthTag(tag);
    return JSON.parse(Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8"));
  }
}
