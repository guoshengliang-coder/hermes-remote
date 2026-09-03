import { OAuth2Client } from "google-auth-library";
import {
  accountErrors,
  type AccountPlatform,
  type ExternalIdentityVerifier,
  type VerifiedExternalIdentity,
} from "./model.js";

type OAuthVerifier = Pick<OAuth2Client, "verifyIdToken">;

export class GoogleIdentityVerifier implements ExternalIdentityVerifier {
  private readonly client: OAuthVerifier;

  constructor(
    private readonly audiences: Record<AccountPlatform, string>,
    client: OAuthVerifier = new OAuth2Client(),
  ) {
    this.client = client;
  }

  async verify(input: {
    platform: AccountPlatform;
    idToken: string;
    nonce: string;
  }): Promise<VerifiedExternalIdentity> {
    try {
      const ticket = await this.client.verifyIdToken({
        idToken: input.idToken,
        audience: this.audiences[input.platform],
      });
      const payload = ticket.getPayload();
      if (!payload) throw new Error("missing_payload");
      if (payload.iss !== "accounts.google.com" && payload.iss !== "https://accounts.google.com") {
        throw new Error("invalid_issuer");
      }
      if (!payload.sub || payload.sub.length > 255) throw new Error("invalid_subject");
      const nonce = (payload as typeof payload & { nonce?: unknown }).nonce;
      if (typeof nonce !== "string" || nonce !== input.nonce) throw new Error("invalid_nonce");

      return {
        provider: "google",
        issuer: payload.iss,
        subject: payload.sub,
        ...(boundedOptional(payload.email, 320) ? { email: payload.email } : {}),
        ...(boundedOptional(payload.name, 160) ? { displayName: payload.name } : {}),
        ...(boundedHttpsUrl(payload.picture) ? { avatarUrl: payload.picture } : {}),
      };
    } catch {
      throw accountErrors.invalidGoogleProof();
    }
  }
}

function boundedOptional(value: unknown, maxLength: number): value is string {
  return typeof value === "string"
    && value.length > 0
    && value.length <= maxLength
    && !/[\u0000-\u001f\u007f]/.test(value);
}

function boundedHttpsUrl(value: unknown): value is string {
  if (!boundedOptional(value, 2_048)) return false;
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}
