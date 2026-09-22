import { JWT } from "google-auth-library";

export type PushProviderName = "fcm";

/**
 * A data-only wake hint. It names the lifecycle event so the phone can fold it if its inbox fetch
 * fails, but it never carries the session title, prompt, output, or approval payload: the durable
 * Relay inbox stays the source of truth and the push provider sees identifiers only.
 */
export interface PushWakeHint {
  eventId: string;
  event: string;
  state: string;
  deviceId: string;
  storedSessionId: string;
  runtimeSessionId: string;
  profile?: string;
  occurredAt: string;
}

export type PushSendResult = "sent" | "token_invalid" | "failed";

export interface PushProvider {
  readonly name: PushProviderName;
  send(token: string, hint: PushWakeHint): Promise<PushSendResult>;
}

export interface AccessTokenSource {
  getAccessToken(): Promise<string>;
}

export interface FcmServiceAccount {
  projectId: string;
  clientEmail: string;
  privateKey: string;
}

const FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const PROJECT_ID_PATTERN = /^[a-z][a-z0-9-]{4,29}$/;

export function parseFcmServiceAccount(json: string): FcmServiceAccount {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("ACCOUNT_FCM_SERVICE_ACCOUNT must be a service-account JSON document");
  }
  const record = parsed as Record<string, unknown> | null;
  const projectId = record?.project_id;
  const clientEmail = record?.client_email;
  const privateKey = record?.private_key;
  if (record?.type !== "service_account"
      || typeof projectId !== "string" || !PROJECT_ID_PATTERN.test(projectId)
      || typeof clientEmail !== "string" || !clientEmail.includes("@")
      || typeof privateKey !== "string" || !privateKey.includes("PRIVATE KEY")) {
    throw new Error("ACCOUNT_FCM_SERVICE_ACCOUNT must be a service-account JSON document");
  }
  return { projectId, clientEmail, privateKey };
}

export function fcmAccessTokenSource(account: FcmServiceAccount): AccessTokenSource {
  const client = new JWT({ email: account.clientEmail, key: account.privateKey, scopes: [FCM_SCOPE] });
  return {
    async getAccessToken() {
      const { token } = await client.getAccessToken();
      if (!token) throw new Error("fcm_access_token_missing");
      return token;
    },
  };
}

/** Builds the FCM HTTP v1 request body. Exported so tests can pin the privacy contract. */
export function fcmMessage(token: string, hint: PushWakeHint): Record<string, unknown> {
  const data: Record<string, string> = {
    type: "hermes.lifecycle",
    eventId: hint.eventId,
    event: hint.event,
    state: hint.state,
    deviceId: hint.deviceId,
    storedSessionId: hint.storedSessionId,
    runtimeSessionId: hint.runtimeSessionId,
    occurredAt: hint.occurredAt,
  };
  if (hint.profile) data.profile = hint.profile;
  return {
    message: {
      token,
      data,
      android: {
        priority: "HIGH",
        // A hint older than this is stale: the phone's periodic inbox sync will have caught up.
        ttl: "600s",
        collapse_key: hint.storedSessionId.slice(0, 64),
      },
    },
  };
}

export class FcmPushProvider implements PushProvider {
  readonly name = "fcm" as const;

  constructor(
    private readonly projectId: string,
    private readonly accessTokens: AccessTokenSource,
    private readonly fetchImpl: typeof fetch = fetch,
    private readonly timeoutMs = 10_000,
  ) {
    if (!PROJECT_ID_PATTERN.test(projectId)) throw new Error("FCM project id is invalid");
  }

  async send(token: string, hint: PushWakeHint): Promise<PushSendResult> {
    try {
      const accessToken = await this.accessTokens.getAccessToken();
      const response = await this.fetchImpl(
        `https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`,
        {
          method: "POST",
          headers: {
            authorization: `Bearer ${accessToken}`,
            "content-type": "application/json",
          },
          body: JSON.stringify(fcmMessage(token, hint)),
          signal: AbortSignal.timeout(this.timeoutMs),
        },
      );
      if (response.ok) return "sent";
      if (response.status === 404) return "token_invalid";
      if (response.status === 400) {
        const body = await response.json().catch(() => undefined) as {
          error?: { details?: Array<{ errorCode?: string }> };
        } | undefined;
        const codes = body?.error?.details?.map((detail) => detail.errorCode) ?? [];
        // INVALID_ARGUMENT can also mean a malformed message; only UNREGISTERED proves the token dead.
        if (codes.includes("UNREGISTERED")) return "token_invalid";
      }
      return "failed";
    } catch {
      return "failed";
    }
  }
}
