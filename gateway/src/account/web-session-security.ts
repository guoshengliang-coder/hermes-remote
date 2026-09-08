import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";
import { accountErrors } from "./model.js";

export const WEB_COOKIE_NAMES = Object.freeze({
  access: "__Host-hermes_go_access",
  refresh: "__Host-hermes_go_refresh",
  csrf: "__Host-hermes_go_csrf",
  installation: "__Host-hermes_go_installation",
});

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CSRF_PATTERN = /^hgc_[A-Za-z0-9_-]{43}$/;
const ACCESS_PATTERN = /^hga_[A-Za-z0-9_-]{43}$/;
const REFRESH_PATTERN = /^hgr_[A-Za-z0-9_-]{43}$/;

export interface WebBootstrapState {
  installationId: string;
  csrfToken: string;
  cookies: string[];
}

export class WebSessionSecurity {
  constructor(private readonly origin: string) {}

  bootstrap(request: IncomingMessage): WebBootstrapState {
    const cookies = parseCookies(request.headers.cookie);
    const installationId = valid(cookies.get(WEB_COOKIE_NAMES.installation), UUID_PATTERN)
      ?? randomUUID();
    const csrfToken = valid(cookies.get(WEB_COOKIE_NAMES.csrf), CSRF_PATTERN)
      ?? `hgc_${randomBytes(32).toString("base64url")}`;
    return {
      installationId,
      csrfToken,
      cookies: [
        cookie(WEB_COOKIE_NAMES.installation, installationId, true, 365 * 24 * 60 * 60),
        cookie(WEB_COOKIE_NAMES.csrf, csrfToken, false, 24 * 60 * 60),
      ],
    };
  }

  requireMutation(request: IncomingMessage): WebBootstrapState {
    if (header(request, "origin") !== this.origin) throw accountErrors.webRequestRejected();
    const fetchSite = header(request, "sec-fetch-site");
    if (fetchSite !== undefined && fetchSite !== "same-origin") {
      throw accountErrors.webRequestRejected();
    }
    const state = this.bootstrap(request);
    const supplied = header(request, "x-hermes-csrf");
    if (!supplied || !safeEqual(supplied, state.csrfToken)) {
      throw accountErrors.webRequestRejected();
    }
    return state;
  }

  authorization(request: IncomingMessage): string {
    const access = valid(parseCookies(request.headers.cookie).get(WEB_COOKIE_NAMES.access), ACCESS_PATTERN);
    if (!access) throw accountErrors.sessionExpired();
    return `Bearer ${access}`;
  }

  refreshToken(request: IncomingMessage): string {
    const refresh = valid(parseCookies(request.headers.cookie).get(WEB_COOKIE_NAMES.refresh), REFRESH_PATTERN);
    if (!refresh) throw accountErrors.sessionExpired();
    return refresh;
  }

  sessionCookies(session: {
    accessToken: string;
    accessExpiresAt: string;
    refreshToken: string;
    refreshExpiresAt: string;
  }): string[] {
    if (!ACCESS_PATTERN.test(session.accessToken) || !REFRESH_PATTERN.test(session.refreshToken)) {
      throw accountErrors.unavailable();
    }
    return [
      expiringCookie(WEB_COOKIE_NAMES.access, session.accessToken, session.accessExpiresAt),
      expiringCookie(WEB_COOKIE_NAMES.refresh, session.refreshToken, session.refreshExpiresAt),
    ];
  }

  clearCookies(): string[] {
    return Object.values(WEB_COOKIE_NAMES).map((name) => (
      `${name}=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Strict`
    ));
  }
}

function parseCookies(raw: string | undefined): Map<string, string> {
  if (!raw) return new Map();
  if (Buffer.byteLength(raw, "utf8") > 4096) throw accountErrors.webRequestRejected();
  const result = new Map<string, string>();
  for (const part of raw.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 1) throw accountErrors.webRequestRejected();
    const name = part.slice(0, separator).trim();
    const value = part.slice(separator + 1).trim();
    if (!name || result.has(name)) throw accountErrors.webRequestRejected();
    result.set(name, value);
  }
  return result;
}

function valid(value: string | undefined, pattern: RegExp): string | undefined {
  return value && pattern.test(value) ? value : undefined;
}

function safeEqual(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left);
  const rightBytes = Buffer.from(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function expiringCookie(name: string, value: string, expiresAt: string): string {
  const expires = new Date(expiresAt);
  if (!Number.isFinite(expires.getTime())) throw accountErrors.unavailable();
  const maxAge = Math.max(0, Math.floor((expires.getTime() - Date.now()) / 1000));
  return `${name}=${value}; Path=/; Max-Age=${maxAge}; Expires=${expires.toUTCString()}; Secure; HttpOnly; SameSite=Strict`;
}

function cookie(name: string, value: string, httpOnly: boolean, maxAge: number): string {
  return `${name}=${value}; Path=/; Max-Age=${maxAge}; Secure${httpOnly ? "; HttpOnly" : ""}; SameSite=Strict`;
}

function header(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}
