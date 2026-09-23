import type { LifecycleEventPage, MessagesResponse, ProfileSessionsResponse, SessionListResponse } from "../hermes/types";

// Same-origin Gateway client for the web app. The session lives in HttpOnly cookies set by the
// Gateway (gateway/src/account/web-session-security.ts); mutations must echo the readable CSRF
// cookie in `X-Hermes-CSRF`, and some need an `Idempotency-Key` UUID
// (gateway/src/account/account-http-controller.ts).

export const CSRF_COOKIE = "__Host-hermes_go_csrf";
const REFRESH_PATH = "/v2/web/auth/refresh";

// ---- Wire types (account-http-controller.ts, model.ts, account-control-model.ts) ----------------

export type AccountPlatform = "android" | "macos" | "web";
export type InstallationKind = "phone" | "desktop" | "browser";

export interface PublicAccount {
  id: string;
  displayName?: string;
  email?: string;
  avatarUrl?: string;
}

export interface PublicInstallation {
  id: string;
  kind: InstallationKind;
  platform: AccountPlatform;
  displayName: string;
}

/** GET /v2/web/session — always 200; bootstraps the installation + CSRF cookies. */
export interface WebSessionResponse {
  session:
    | { authenticated: true; account: PublicAccount; installation: PublicInstallation }
    | { authenticated: false; accountDeletionPending?: true };
  csrfToken: string;
  authentication: { google: { clientId: string } | null };
  features: { accountDeletion: boolean };
}

/** POST /v2/web/auth/email/challenges → 202. */
export interface EmailChallengeResponse {
  challenge: { challengeId: string; expiresAt: string; resendAfter: string };
}

export interface EmailExchangeRequest {
  challengeId: string;
  email: string;
  /** Exactly six characters. */
  code: string;
  displayName?: string;
}

/** POST /v2/web/auth/email/exchange (and google/exchange) → 200. */
export interface WebSignInResponse {
  account: PublicAccount;
  installation: PublicInstallation;
  session: { authenticated: true; accessExpiresAt: string; refreshExpiresAt: string };
}

/** POST /v2/web/auth/refresh → 200. */
export interface WebRefreshResponse {
  session: { authenticated: true; accessExpiresAt: string; refreshExpiresAt: string };
  csrfToken: string;
}

export interface AccountDevice {
  id: string;
  generation: number;
  deviceId: string;
  desktopDisplayName: string;
  publicKeyFingerprint: string;
  connector: { online: boolean; lastSeenAt?: string };
  hermes: { reachable: boolean | null; version?: string };
  gateway: { latencyMs?: number };
  endToEnd: { healthy: boolean | null; checkedAt?: string };
  access: "owner" | "operator";
  isDefault: boolean;
}

/** GET /v2/web/devices. */
export interface WebDevicesResponse {
  items: AccountDevice[];
  maxOwnedDevices: number;
}

/** POST /v2/web/devices/{deviceId}/select-default. */
export interface SelectDefaultDeviceResponse {
  device: AccountDevice;
}

/** GET /v2/capabilities (`capabilities()` in account-http-controller.ts). */
export interface GatewayCapabilities {
  version: 1;
  accountAuth: {
    enabled: boolean;
    providers: Array<"google" | "email_otp">;
    android: boolean;
    macos: boolean;
    identityManagement: boolean;
    accountDeletion?: true;
    webAccountCenter: boolean;
    webSessions?: true;
    /** ACCOUNT_WEB_DEVICE_ACCESS_ENABLED: the cookie device routes this app needs are served. */
    webDeviceAccess?: true;
    /** Web batch 4: what the Gateway admits beyond chat; absent on older Gateways. */
    webDeviceFeatures?: string[];
  };
  binding: {
    enabled: boolean;
    replacement: boolean;
    maxActiveConnectorsPerAccount: 1 | 3;
    supportsDeviceSelection?: true;
    supportsDeviceSharing?: true;
    maxSharedDevices?: 10;
    maxGranteesPerDevice?: 5;
  };
  legacy: { appTokenAccepted: boolean; connectorTokenAccepted: boolean };
  desktopBootstrap?: { runtimeContract: "hermes-serve-v1"; componentManifestSchemaVersion?: 2 };
  server?: { version: string; protocolVersions: unknown; minimumClients: unknown };
}

export function supportsWebDeviceAccess(caps: GatewayCapabilities): boolean {
  return caps.accountAuth?.webDeviceAccess === true;
}

export type WebDeviceFeature = "session-manage" | "session-delete" | "workspace-move" | "model-select" | "process-list" | "session-access";

/** Features this Gateway admits for the Web app; an older Gateway lists none, so nothing extra shows. */
export function webDeviceFeatures(caps: GatewayCapabilities): ReadonlySet<WebDeviceFeature> {
  const list = caps.accountAuth?.webDeviceFeatures;
  return new Set(Array.isArray(list) ? (list.filter((f) => typeof f === "string") as WebDeviceFeature[]) : []);
}

/** `GET /api/model/options` (Android ModelOptionsDto): providers each with model-name strings. */
export interface ModelOptionsResponse {
  providers?: { slug: string; name?: string | null; is_current?: boolean; models?: string[] }[];
}

/** POST /api/mobile/events/{ack,read} → 200. */
export interface LifecycleAckResponse {
  ok: true;
  changed: number;
}

/** POST /api/files/upload answer (connector/src/index.ts handleUploadRequest). */
export interface UploadedFile {
  path: string;
  name?: string;
  size?: number;
}

// ---- Paths ----------------------------------------------------------------------------------

/**
 * Java `URLEncoder.encode(…).replace("+", "%20")`, as HermesRestApi.kt builds the device route:
 * unlike encodeURIComponent it also escapes `! ' ( ) ~`.
 */
export function encodePathSegment(value: string): string {
  return encodeURIComponent(value).replace(/[!'()~]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

export const paths = {
  /** `rest` is the Hermes path after `/api/` (a leading `/api/` or `/` is tolerated). */
  deviceApi(deviceId: string, rest: string): string {
    const tail = rest.replace(/^\/?(?:api\/)?/, "");
    return `/v2/devices/${encodePathSegment(deviceId)}/api/${tail}`;
  },
  deviceWs(deviceId: string, location: { protocol: string; host: string } = globalThis.location): string {
    const scheme = location.protocol === "http:" ? "ws" : "wss";
    return `${scheme}://${location.host}/v2/devices/${encodePathSegment(deviceId)}/ws`;
  },
  inbox(after: number, limit: number): string {
    return `/api/mobile/events?after=${after}&limit=${limit}`;
  },
  inboxAck: "/api/mobile/events/ack",
  inboxRead: "/api/mobile/events/read",
  webSession: "/v2/web/session",
  emailChallenges: "/v2/web/auth/email/challenges",
  emailExchange: "/v2/web/auth/email/exchange",
  refresh: REFRESH_PATH,
  signOut: "/v2/web/auth/sign-out",
  devices: "/v2/web/devices",
  selectDefault(deviceId: string): string {
    return `/v2/web/devices/${encodePathSegment(deviceId)}/select-default`;
  },
  capabilities: "/v2/capabilities",
} as const;

/**
 * One page of `GET /api/sessions/{id}/messages` (upstream: `limit` ≤ 500, `offset`, `order`).
 * `latest` pages backwards from the newest row but still returns each page in chronological order.
 */
export interface MessagesPage {
  order: "latest" | "oldest";
  limit: number;
  offset: number;
}

/** Hermes session-scoped REST paths (relative to `/api/`). */
export const hermesPaths = {
  sessions: "sessions",
  profileSessions: "profiles/sessions",
  /** `inline_images=false` keeps base64 images out of the page (Android does the same). */
  messages: (sessionId: string, profile?: string | null, page?: MessagesPage) =>
    `sessions/${encodePathSegment(sessionId)}/messages?inline_images=false${profile ? `&profile=${encodeURIComponent(profile)}` : ""}${
      page ? `&order=${page.order}&limit=${page.limit}&offset=${page.offset}` : ""
    }`,
  /** Message search, with the non-conversation sources excluded like Android does. */
  search: (query: string, excludeSources: readonly string[] = []) =>
    `sessions/search?q=${encodeURIComponent(query)}${excludeSources.length ? `&exclude_sources=${encodeURIComponent(excludeSources.join(","))}` : ""}`,
  /**
   * A Mac file. `thumb` asks the Connector for a downscaled preview instead of the original
   * (HG-115); it is ignored for anything that is not a raster image, and the Connector falls back
   * to the original whenever a preview cannot be made, so this never changes what is shown — only
   * how many bytes it took.
   */
  file: (path: string, thumbWidth?: number) =>
    `files?path=${encodeURIComponent(path)}${thumbWidth ? `&thumb=${thumbWidth}` : ""}`,
} as const;

// ---- Client ---------------------------------------------------------------------------------

export class GatewayHttpError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
    readonly path: string,
  ) {
    const code = (body as { error?: { code?: unknown } } | null)?.error?.code;
    super(`HTTP ${status}${typeof code === "string" ? ` ${code}` : ""}`);
    this.name = "GatewayHttpError";
  }

  get code(): string | undefined {
    const code = (this.body as { error?: { code?: unknown } } | null)?.error?.code;
    return typeof code === "string" ? code : undefined;
  }
}

/** fetch rejected: no HTTP answer at all (offline, DNS, TLS, CORS, abort). */
export class GatewayNetworkError extends Error {
  constructor(override readonly cause: unknown, readonly path: string) {
    super(cause instanceof Error ? cause.message : String(cause));
    this.name = "GatewayNetworkError";
  }
}

export interface RequestOptions {
  body?: unknown;
  headers?: Record<string, string>;
  /** Adds a fresh `Idempotency-Key` unless one is given in headers. */
  idempotent?: boolean;
  /** Retry once through the single-flight refresh on 401. Default true (never for auth routes). */
  refreshOn401?: boolean;
  signal?: AbortSignal;
  /** Return the raw Response (for binary downloads) instead of parsed JSON. */
  raw?: boolean;
}

export type SignedOutListener = (error: GatewayHttpError) => void;

export interface GatewayClientOptions {
  fetch?: typeof fetch;
  /** Refresh this long before the access cookie expires (default 2 min). */
  refreshLeadMs?: number;
  /** Retry delay after a refresh that failed for a non-auth reason (default 30 s). */
  refreshRetryMs?: number;
  /** Defaults to same-origin relative URLs. */
  baseUrl?: string;
  readCookie?: () => string;
  uuid?: () => string;
}

function isRawBody(value: unknown): value is Blob | ArrayBuffer | Uint8Array {
  return (
    (typeof Blob !== "undefined" && value instanceof Blob) ||
    value instanceof ArrayBuffer ||
    value instanceof Uint8Array
  );
}

function readCookieValue(cookies: string, name: string): string | null {
  for (const part of cookies.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim() || null;
  }
  return null;
}

/** Auth routes answer 401 for their own reasons (bad code, no session) and must not trigger refresh. */
function refreshable(path: string): boolean {
  return !path.startsWith("/v2/web/auth/") || path === paths.signOut;
}

export class GatewayClient {
  private readonly fetchImpl: typeof fetch;
  private readonly baseUrl: string;
  private readonly readCookie: () => string;
  private readonly uuid: () => string;
  private refreshing: Promise<void> | null = null;
  private csrfFallback: string | null = null;
  private readonly signedOut = new Set<SignedOutListener>();
  private readonly refreshLeadMs: number;
  private readonly refreshRetryMs: number;
  private keepAliveTimer: ReturnType<typeof setTimeout> | null = null;
  private accessExpiresAtMs: number | null = null;

  constructor(options: GatewayClientOptions = {}) {
    this.refreshLeadMs = options.refreshLeadMs ?? 120_000;
    this.refreshRetryMs = options.refreshRetryMs ?? 30_000;
    this.fetchImpl = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
    this.baseUrl = options.baseUrl ?? "";
    this.readCookie = options.readCookie ?? (() => (typeof document === "undefined" ? "" : document.cookie));
    this.uuid = options.uuid ?? (() => crypto.randomUUID());
  }

  onSignedOut(listener: SignedOutListener): () => void {
    this.signedOut.add(listener);
    return () => this.signedOut.delete(listener);
  }

  csrfToken(): string | null {
    return readCookieValue(this.readCookie(), CSRF_COOKIE) ?? this.csrfFallback;
  }

  async request<T = unknown>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.send(method, path, options);
    if (response.status === 401 && (options.refreshOn401 ?? true) && refreshable(path)) {
      try {
        await this.refresh();
      } catch {
        throw await this.toHttpError(response, path);
      }
      return this.finish<T>(await this.send(method, path, options), path, options);
    }
    return this.finish<T>(response, path, options);
  }

  /**
   * Single flight: concurrent 401s share one `POST /v2/web/auth/refresh`. When the Gateway refuses
   * it (a 4xx other than 429) 'signed-out' fires once; a network failure or 5xx does not sign out.
   */
  /** Resolves once no refresh is in flight (whatever its outcome): the access cookie is settled. */
  settled(): Promise<void> {
    return this.refreshing ? this.refreshing.then(() => undefined, () => undefined) : Promise.resolve();
  }

  refresh(options: { silent?: boolean } = {}): Promise<void> {
    this.refreshing ??= (async () => {
      try {
        const body = await this.request<WebRefreshResponse>("POST", REFRESH_PATH, { idempotent: true, refreshOn401: false });
        if (body?.csrfToken) this.csrfFallback = body.csrfToken;
        this.keepAlive(body?.session?.accessExpiresAt);
      } catch (error) {
        if (error instanceof GatewayHttpError && error.status >= 400 && error.status < 500 && error.status !== 429) {
          this.stopKeepAlive();
          // A silent attempt is a question ("is there still a session?"), not a session ending:
          // the first visit of a signed-out browser must not look like being thrown out.
          if (!options.silent) this.emitSignedOut(error);
        } else {
          this.scheduleKeepAlive(this.refreshRetryMs);
        }
        throw error;
      } finally {
        this.refreshing = null;
      }
    })();
    return this.refreshing;
  }

  /**
   * Cold start: the access cookie lives 15 minutes, the refresh cookie 30 days, and
   * GET /v2/web/session only reads the access one. Reopening the app after a pause therefore
   * looks signed out until this exchanges the refresh cookie. True when a session came back.
   */
  async resume(): Promise<boolean> {
    try {
      await this.refresh({ silent: true });
      return true;
    } catch {
      return false;
    }
  }

  private async send(method: string, path: string, options: RequestOptions): Promise<Response> {
    const headers: Record<string, string> = { accept: "application/json", ...options.headers };
    if (method !== "GET" && method !== "HEAD") {
      const csrf = this.csrfToken();
      if (csrf) headers["X-Hermes-CSRF"] = csrf;
      if (options.idempotent && !Object.keys(headers).some((h) => h.toLowerCase() === "idempotency-key")) {
        headers["Idempotency-Key"] = this.uuid();
      }
    }
    let body: BodyInit | undefined;
    if (options.body !== undefined) {
      if (typeof FormData !== "undefined" && options.body instanceof FormData) {
        body = options.body;
      } else if (isRawBody(options.body)) {
        // Raw upload bytes (POST /api/files/upload): sent as-is; the caller sets content-type.
        body = options.body as BodyInit;
      } else {
        headers["content-type"] = "application/json";
        body = JSON.stringify(options.body);
      }
    }
    try {
      return await this.fetchImpl(`${this.baseUrl}${path}`, {
        method,
        headers,
        body,
        credentials: "same-origin",
        cache: "no-store",
        signal: options.signal,
      });
    } catch (cause) {
      throw new GatewayNetworkError(cause, path);
    }
  }

  private async finish<T>(response: Response, path: string, options: RequestOptions): Promise<T> {
    if (!response.ok) throw await this.toHttpError(response, path);
    if (options.raw) return response as unknown as T;
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  private async toHttpError(response: Response, path: string): Promise<GatewayHttpError> {
    let body: unknown = null;
    try {
      const text = await response.clone().text();
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    return new GatewayHttpError(response.status, body, path);
  }

  /**
   * The browser WebSocket stays authorized only while the session keeps refreshing, so while the
   * app is open the access cookie is refreshed `refreshLeadMs` before it expires, not only on 401.
   * Called with the `accessExpiresAt` of a sign-in or refresh answer; unknown expiry refreshes now.
   */
  keepAlive(accessExpiresAt?: string | null): void {
    const expires = accessExpiresAt ? Date.parse(accessExpiresAt) : Number.NaN;
    this.accessExpiresAtMs = Number.isNaN(expires) ? null : expires;
    this.scheduleKeepAlive(Number.isNaN(expires) ? 0 : Math.max(0, expires - this.refreshLeadMs - Date.now()));
  }

  /** Last known access-cookie expiry (epoch ms) from a sign-in or refresh answer; null if unknown. */
  get accessExpiresAt(): number | null {
    return this.accessExpiresAtMs;
  }

  stopKeepAlive(): void {
    if (this.keepAliveTimer) clearTimeout(this.keepAliveTimer);
    this.keepAliveTimer = null;
  }

  private scheduleKeepAlive(delayMs: number): void {
    this.stopKeepAlive();
    this.keepAliveTimer = setTimeout(() => {
      this.keepAliveTimer = null;
      this.refresh().catch(() => {
        /* rescheduled or signed out inside refresh() */
      });
    }, delayMs);
  }

  private emitSignedOut(error: GatewayHttpError): void {
    for (const listener of [...this.signedOut]) listener(error);
  }

  // ---- Web session / auth ----

  async webSession(): Promise<WebSessionResponse> {
    const result = await this.request<WebSessionResponse>("GET", paths.webSession, { refreshOn401: false });
    if (result?.csrfToken) this.csrfFallback = result.csrfToken;
    // This answer carries no expiry: refresh once now to learn it (and keep the session alive), and
    // finish that refresh before answering. Refresh rotates the access cookie; a WebSocket upgraded
    // while it is in flight still carries the old one, which the Gateway refuses — the first socket
    // after every reload failed that way about half the time, taking a message sent early with it.
    if (result?.session?.authenticated) {
      if (!this.keepAliveTimer && !this.refreshing) await this.refresh().catch(() => undefined);
    } else {
      this.stopKeepAlive();
    }
    return result;
  }

  requestEmailChallenge(email: string): Promise<EmailChallengeResponse> {
    return this.request("POST", paths.emailChallenges, { body: { email } });
  }

  async exchangeEmail(input: EmailExchangeRequest, idempotencyKey: string = this.uuid()): Promise<WebSignInResponse> {
    const body: Record<string, string> = { challengeId: input.challengeId, email: input.email, code: input.code };
    if (input.displayName) body.displayName = input.displayName;
    const result = await this.request<WebSignInResponse>("POST", paths.emailExchange, { body, headers: { "Idempotency-Key": idempotencyKey } });
    this.keepAlive(result?.session?.accessExpiresAt);
    return result;
  }

  async signOut(): Promise<void> {
    this.stopKeepAlive();
    try {
      await this.request<void>("POST", paths.signOut, { idempotent: true });
    } finally {
      // A 401 on the way may have refreshed (and rescheduled) before the retry signed out.
      this.stopKeepAlive();
    }
  }

  devices(): Promise<WebDevicesResponse> {
    return this.request("GET", paths.devices);
  }

  selectDefaultDevice(deviceId: string): Promise<SelectDefaultDeviceResponse> {
    return this.request("POST", paths.selectDefault(deviceId), { idempotent: true });
  }

  capabilities(): Promise<GatewayCapabilities> {
    return this.request("GET", paths.capabilities, { refreshOn401: false });
  }

  // ---- Device API (Hermes REST through the Gateway) ----

  deviceApi<T = unknown>(deviceId: string, method: string, rest: string, options: RequestOptions = {}): Promise<T> {
    return this.request<T>(method, paths.deviceApi(deviceId, rest), options);
  }

  sessions(deviceId: string): Promise<SessionListResponse> {
    return this.deviceApi(deviceId, "GET", hermesPaths.sessions);
  }

  profileSessions(deviceId: string): Promise<ProfileSessionsResponse> {
    return this.deviceApi(deviceId, "GET", hermesPaths.profileSessions);
  }

  messages(deviceId: string, sessionId: string, profile?: string | null, page?: MessagesPage): Promise<MessagesResponse> {
    return this.deviceApi(deviceId, "GET", hermesPaths.messages(sessionId, profile, page));
  }

  /** Raw-body upload into the Mac's files root (Connector-served). Answers `{path, name, size}`. */
  uploadFile(deviceId: string, name: string, body: Blob, contentType: string): Promise<UploadedFile> {
    return this.deviceApi(deviceId, "POST", `files/upload?name=${encodeURIComponent(name)}`, {
      body,
      headers: { "content-type": contentType || "application/octet-stream" },
    });
  }

  // ---- Session management (Web batch 4) ----

  /** Rename (title), archive / unarchive (archived). `profile` rides in the body, as on Android. */
  updateSession(deviceId: string, sessionId: string, change: { title?: string; archived?: boolean }, profile?: string | null): Promise<unknown> {
    return this.deviceApi(deviceId, "PATCH", `sessions/${encodePathSegment(sessionId)}`, {
      body: { ...change, ...(profile ? { profile } : {}) },
    });
  }

  deleteSession(deviceId: string, sessionId: string, profile?: string | null): Promise<unknown> {
    return this.deviceApi(deviceId, "DELETE", `sessions/${encodePathSegment(sessionId)}${profile ? `?profile=${encodeURIComponent(profile)}` : ""}`);
  }

  modelOptions(deviceId: string, profile?: string | null): Promise<ModelOptionsResponse> {
    return this.deviceApi(deviceId, "GET", `model/options${profile ? `?profile=${encodeURIComponent(profile)}` : ""}`);
  }

  // ---- Lifecycle inbox ----

  lifecycleEvents(after = 0, limit = 100): Promise<LifecycleEventPage> {
    if (!Number.isInteger(after) || after < 0) throw new RangeError("after must be a non-negative integer");
    if (!Number.isInteger(limit) || limit < 1 || limit > 500) throw new RangeError("limit must be between 1 and 500");
    return this.request("GET", paths.inbox(after, limit));
  }

  ackEvents(eventIds: readonly string[]): Promise<LifecycleAckResponse> {
    return this.postEventIds(paths.inboxAck, eventIds);
  }

  readEvents(eventIds: readonly string[]): Promise<LifecycleAckResponse> {
    return this.postEventIds(paths.inboxRead, eventIds);
  }

  private postEventIds(path: string, eventIds: readonly string[]): Promise<LifecycleAckResponse> {
    const ids = [...new Set(eventIds)];
    if (ids.length > 500) throw new RangeError("too many lifecycle event ids");
    return this.request("POST", path, { body: { event_ids: ids } });
  }
}
