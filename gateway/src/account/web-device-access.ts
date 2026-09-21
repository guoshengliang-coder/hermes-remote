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
// The Web app is a chat client, not a Mac administration console: it reaches only the routes it
// renders. Everything else (env, config, cron, skills, messaging, gateway restart, …) stays with the
// Android and Desktop apps.
const BROWSER_ROUTES: ReadonlyArray<{ method: string; path: RegExp }> = [
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
];

export function browserRouteAllowed(method: string | undefined, apiPath: string): boolean {
  const normalized = method === "HEAD" ? "GET" : method ?? "GET";
  return BROWSER_ROUTES.some((route) => route.method === normalized && route.path.test(apiPath));
}

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
