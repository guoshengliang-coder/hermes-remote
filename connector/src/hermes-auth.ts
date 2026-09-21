import { HERMES_OPENAPI_PATH, type OpenApiFetchResult } from "./hermes-contract.js";

/**
 * How the Connector talks to the local Hermes: session token, or Basic Auth → cookie session, and
 * single-use WS tickets. Split out of index.ts so the one non-`/api/` read — the contract check's
 * `/openapi.json` — can be tested with the same credentials the relay path uses.
 */
export class HermesAuth {
  private readonly baseUrl: string;
  private readonly sessionToken?: string;
  private readonly username?: string;
  private readonly password?: string;
  private readonly cookies = new Map<string, string>();
  private loginInFlight?: Promise<boolean>;
  private readonly requestTimeoutMs: number;

  constructor(config: {
    baseUrl: string;
    sessionToken?: string;
    username?: string;
    password?: string;
    requestTimeoutMs?: number;
  }) {
    this.requestTimeoutMs = config.requestTimeoutMs ?? 60_000;
    this.baseUrl = config.baseUrl;
    this.sessionToken = config.sessionToken;
    this.username = config.username;
    this.password = config.password;
    if (Boolean(this.username) !== Boolean(this.password)) {
      throw new Error("HERMES_BASIC_AUTH_USERNAME and HERMES_BASIC_AUTH_PASSWORD must be configured together");
    }
  }

  async request(path: string, init: RequestInit): Promise<Response> {
    this.assertApiPath(path);
    return this.send(path, init);
  }

  /**
   * Hermes' own OpenAPI document. Outside `/api/`, so [request] refuses it; this is the one
   * non-API path the Connector reads, and only for the contract check — never on the phone's behalf.
   */
  async openApiDocument(): Promise<Response> {
    return this.send(HERMES_OPENAPI_PATH, { method: "GET" });
  }

  private async send(path: string, init: RequestInit): Promise<Response> {
    if (this.username && this.cookies.size === 0) await this.login();
    let response = await fetch(new URL(path, this.baseUrl), this.withAuth(init));
    this.captureCookies(response.headers);
    if (response.status === 401 && this.username) {
      await this.login(true);
      response = await fetch(new URL(path, this.baseUrl), this.withAuth(init));
      this.captureCookies(response.headers);
    }
    return response;
  }

  async websocketUrl(path: string): Promise<string> {
    if (path !== "/api/ws") throw new Error("unsupported WebSocket path");
    const wsBase = this.baseUrl.replace(/^https:/, "wss:").replace(/^http:/, "ws:");
    if (this.sessionToken) {
      const url = new URL(path, wsBase);
      url.searchParams.set("token", this.sessionToken);
      return url.toString();
    }
    if (this.username) {
      const response = await this.request("/api/auth/ws-ticket", { method: "POST" });
      if (!response.ok) throw new Error(`Hermes WS ticket returned HTTP ${response.status}`);
      const payload = await response.json() as { ticket?: string };
      if (!payload.ticket) throw new Error("Hermes WS ticket response contained no ticket");
      const url = new URL(path, wsBase);
      url.searchParams.set("ticket", payload.ticket);
      return url.toString();
    }
    return new URL(path, wsBase).toString();
  }

  private async login(force = false): Promise<void> {
    if (!this.username || !this.password) return;
    if (!force && this.cookies.size > 0) return;
    if (!this.loginInFlight) {
      this.loginInFlight = this.performLogin().finally(() => {
        this.loginInFlight = undefined;
      });
    }
    const ok = await this.loginInFlight;
    if (!ok) throw new Error("Hermes Basic Auth login failed");
  }

  private async performLogin(): Promise<boolean> {
    this.cookies.clear();
    const response = await fetch(new URL("/auth/password-login", this.baseUrl), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ provider: "basic", username: this.username, password: this.password }),
      signal: AbortSignal.timeout(this.requestTimeoutMs),
    });
    this.captureCookies(response.headers);
    return response.ok;
  }

  private withAuth(init: RequestInit): RequestInit {
    const headers = new Headers(init.headers);
    headers.delete("x-hermes-session-token");
    headers.delete("cookie");
    if (this.sessionToken) headers.set("x-hermes-session-token", this.sessionToken);
    const cookie = [...this.cookies.entries()].map(([name, value]) => `${name}=${value}`).join("; ");
    if (cookie) headers.set("cookie", cookie);
    return {
      ...init,
      headers,
      signal: init.signal ?? AbortSignal.timeout(this.requestTimeoutMs),
    };
  }

  private captureCookies(headers: Headers): void {
    const cookieHeaders = (headers as Headers & { getSetCookie?: () => string[] }).getSetCookie?.()
      ?? (headers.get("set-cookie") ? [headers.get("set-cookie") as string] : []);
    for (const value of cookieHeaders) {
      const pair = value.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator <= 0) continue;
      this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
  }

  private assertApiPath(path: string): void {
    if (!path.startsWith("/api/")) throw new Error("unsupported Hermes path");
  }
}

/** 0.21.3 publishes ~250 KB; the ceiling only stops a runaway body, it is not a size contract. */
export const MAX_OPENAPI_BYTES = 8 * 1024 * 1024;

export async function fetchHermesOpenApi(
  auth: Pick<HermesAuth, "openApiDocument">,
  maximumBytes = MAX_OPENAPI_BYTES,
): Promise<OpenApiFetchResult> {
  let response: Response;
  try {
    response = await auth.openApiDocument();
  } catch {
    return { kind: "unreachable" };
  }
  if (!response.ok) {
    await response.body?.cancel().catch(() => undefined);
    return { kind: "http_status", status: response.status };
  }
  try {
    return { kind: "ok", body: await boundedResponseBody(response, maximumBytes) };
  } catch (error) {
    return error instanceof Error && error.message === RESPONSE_TOO_LARGE ? { kind: "too_large" } : { kind: "unreachable" };
  }
}

export const RESPONSE_TOO_LARGE = "Hermes response too large";

export async function boundedResponseBody(response: Response, maximumBytes: number): Promise<string> {
  const declared = Number(response.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > maximumBytes) throw new Error(RESPONSE_TOO_LARGE);
  if (!response.body) return "";
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > maximumBytes) {
      await reader.cancel();
      throw new Error(RESPONSE_TOO_LARGE);
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks).toString("utf8");
}
