import type { IncomingMessage } from "node:http";
import type { AccountPrincipal } from "./model.js";
import { accountErrors } from "./model.js";
import type { WebSessionSecurity } from "./web-session-security.js";

export interface WebDeviceAccessOptions {
  security: WebSessionSecurity;
  authenticate(authorization: string): Promise<AccountPrincipal>;
  isSessionLive(principal: AccountPrincipal): Promise<boolean>;
}

// Lets the same-origin Web app (served at /app/) use the device API, the device WebSocket and the
// lifecycle inbox with its HttpOnly session cookie. Browsers cannot put an Authorization header on
// a WebSocket upgrade, but they do send same-origin cookies, so the cookie is the credential and an
// exact Origin match is what stops cross-site WebSocket hijacking.
export class WebDeviceAccess {
  constructor(private readonly options: WebDeviceAccessOptions) {}

  presents(request: IncomingMessage): boolean {
    return this.options.security.hasAccessCookie(request);
  }

  async authenticateRequest(request: IncomingMessage): Promise<AccountPrincipal> {
    const method = request.method ?? "GET";
    if (method === "GET" || method === "HEAD") {
      this.requireSameOriginRead(request);
    } else {
      this.options.security.requireMutation(request);
    }
    return this.authenticateBrowser(request);
  }

  async authenticateUpgrade(request: IncomingMessage): Promise<AccountPrincipal> {
    if (header(request, "origin") !== this.options.security.origin) {
      throw accountErrors.webRequestRejected();
    }
    return this.authenticateBrowser(request);
  }

  // Revalidates an open browser WebSocket by its session rather than by the access token it was
  // opened with: that token rotates every 15 minutes, while sign-out, revocation and refresh-token
  // reuse all end the session itself.
  async revalidate(principal: AccountPrincipal): Promise<void> {
    if (!await this.options.isSessionLive(principal)) throw accountErrors.sessionRevoked();
  }

  private requireSameOriginRead(request: IncomingMessage): void {
    const fetchSite = header(request, "sec-fetch-site");
    if (fetchSite === "same-origin") return;
    if (fetchSite === undefined && header(request, "origin") === this.options.security.origin) return;
    throw accountErrors.webRequestRejected();
  }

  private async authenticateBrowser(request: IncomingMessage): Promise<AccountPrincipal> {
    const principal = await this.options.authenticate(this.options.security.authorization(request));
    if (principal.installation.kind !== "browser" || principal.installation.platform !== "web") {
      throw accountErrors.webRequestRejected();
    }
    return principal;
  }
}

// Session ids are Hermes' own (`20260918_204034_16def7`, UUIDs). A strict charset keeps an encoded
// separator (`abc%2Fexport`) from matching here and then being decoded into a sibling route upstream.
const SESSION_ID = "[A-Za-z0-9_.:-]{1,128}";
const PROFILE = /^[\p{L}\p{N}_. -]{1,64}$/u;

/** What the Gateway does with one allowed browser route. */
export interface BrowserRoute {
  method: string;
  path: RegExp;
  /** When set, the only query keys allowed; anything else refuses the request. */
  query?: readonly string[];
  /** When set, the JSON body is read first and must pass this check before it is forwarded. */
  body?: (value: unknown) => boolean;
  /** Logged as a session-management action (audit): who did what to which session. */
  audit?: "session.update" | "session.delete";
}

/** PATCH /api/sessions/{id}: title, archived and profile only (rename / archive / unarchive). */
function sessionPatch(value: unknown): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const body = value as Record<string, unknown>;
  const keys = Object.keys(body);
  if (keys.length === 0 || !keys.every((key) => key === "title" || key === "archived" || key === "profile")) return false;
  if ("title" in body && (typeof body.title !== "string" || body.title.trim() === "" || body.title.length > 200)) return false;
  if ("archived" in body && typeof body.archived !== "boolean") return false;
  if ("profile" in body && (typeof body.profile !== "string" || !PROFILE.test(body.profile))) return false;
  return "title" in body || "archived" in body;
}

// The Web app is a chat client, not a Mac administration console: it reaches only the routes it
// renders. Everything else (env, config, cron, skills, messaging, gateway restart, …) stays with the
// Android and Desktop apps. Routes that change something are admitted in one shape only.
const BROWSER_ROUTES: ReadonlyArray<BrowserRoute> = [
  { method: "GET", path: /^\/api\/status$/ },
  { method: "GET", path: /^\/api\/hermes-remote\/contract$/ },
  { method: "GET", path: /^\/api\/sessions$/ },
  { method: "GET", path: /^\/api\/sessions\/search$/ },
  { method: "GET", path: /^\/api\/sessions\/stats$/ },
  { method: "GET", path: new RegExp(`^/api/sessions/${SESSION_ID}$`) },
  { method: "GET", path: new RegExp(`^/api/sessions/${SESSION_ID}/messages$`) },
  { method: "GET", path: /^\/api\/profiles$/ },
  { method: "GET", path: /^\/api\/profiles\/sessions$/ },
  { method: "GET", path: /^\/api\/files$/ },
  { method: "POST", path: /^\/api\/files\/upload$/ },
  // Web batch 4 (docs/ACCOUNT_MODE_SECURITY.md §4): session management and the model list.
  { method: "PATCH", path: new RegExp(`^/api/sessions/${SESSION_ID}$`), query: [], body: sessionPatch, audit: "session.update" },
  { method: "DELETE", path: new RegExp(`^/api/sessions/${SESSION_ID}$`), query: ["profile"], audit: "session.delete" },
  { method: "GET", path: /^\/api\/model\/options$/, query: ["profile"] },
];

/** The allowed route for this request, or undefined (refuse with HR-WEB-001). */
export function browserRouteFor(method: string | undefined, url: URL): BrowserRoute | undefined {
  const normalized = method === "HEAD" ? "GET" : method ?? "GET";
  const route = BROWSER_ROUTES.find((candidate) => candidate.method === normalized && candidate.path.test(url.pathname));
  if (!route) return undefined;
  if (route.query) {
    for (const [key, value] of url.searchParams) {
      if (!route.query.includes(key)) return undefined;
      if (key === "profile" && !PROFILE.test(value)) return undefined;
    }
  }
  return route;
}

export function browserRouteAllowed(method: string | undefined, apiPath: string): boolean {
  return browserRouteFor(method, new URL(apiPath, "http://device.invalid")) !== undefined;
}

/** Features the Web app may show, advertised in /v2/capabilities (Web batch 4). */
export const WEB_DEVICE_FEATURES = [
  "session-manage",
  "session-delete",
  "workspace-move",
  "model-select",
  "process-list",
  "session-access",
] as const;

const INLINE_IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

// A file from the Mac is untrusted content served from the Gateway origin. Whatever the Connector
// reports, a browser must never render it as a document there: HTML and SVG would run with the
// session's origin. Raster images keep their type so <img> can still show them.
export function browserResponseHeaders(
  apiPath: string,
  headers: Record<string, string>,
): Record<string, string> {
  const result: Record<string, string> = {
    ...headers,
    "x-content-type-options": "nosniff",
    "content-security-policy": "default-src 'none'; sandbox",
    // Cookie-authenticated conversation data must not linger in the HTTP cache of a shared computer
    // (bearer requests got that from the Authorization caching rules; cookie requests do not).
    "cache-control": "private, no-store",
    vary: "Cookie",
  };
  if (apiPath === "/api/files") {
    const type = (headers["content-type"] ?? "").split(";")[0]!.trim().toLowerCase();
    if (!INLINE_IMAGE_TYPES.has(type)) result["content-type"] = "application/octet-stream";
    result["content-disposition"] = attachmentDisposition(headers["content-disposition"]);
  }
  return result;
}

function attachmentDisposition(original: string | undefined): string {
  if (!original) return "attachment";
  return /^\s*(inline|attachment)\b/i.test(original)
    ? original.replace(/^\s*(inline|attachment)\b/i, "attachment")
    : "attachment";
}

function header(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}
