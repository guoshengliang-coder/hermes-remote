import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { encodePathSegment, GatewayClient, GatewayHttpError, GatewayNetworkError, hermesPaths, paths, supportsWebDeviceAccess, type GatewayCapabilities } from "./gateway";

interface Call {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
  credentials: RequestCredentials | undefined;
}

type Responder = (call: Call) => Response | Promise<Response>;

function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

const CSRF = "hgc_" + "a".repeat(43);

function fake(responder: Responder, cookie = `x=1; __Host-hermes_go_csrf=${CSRF}`) {
  const calls: Call[] = [];
  let n = 0;
  const client = new GatewayClient({
    fetch: (async (input: RequestInfo | URL, init?: RequestInit) => {
      const call: Call = {
        url: String(input),
        method: init?.method ?? "GET",
        headers: { ...(init?.headers as Record<string, string>) },
        body: typeof init?.body === "string" ? JSON.parse(init.body) : init?.body,
        credentials: init?.credentials,
      };
      calls.push(call);
      return responder(call);
    }) as typeof fetch,
    readCookie: () => cookie,
    uuid: () => `00000000-0000-4000-8000-${String(++n).padStart(12, "0")}`,
  });
  return { client, calls };
}

const future = (ms: number) => new Date(Date.now() + ms).toISOString();

describe("paths", () => {
  it("encodes device ids like Java URLEncoder with + → %20", () => {
    expect(encodePathSegment("mac mini+1/(x)!'~*")).toBe("mac%20mini%2B1%2F%28x%29%21%27%7E*");
    expect(paths.deviceApi("dev 1", "sessions")).toBe("/v2/devices/dev%201/api/sessions");
    expect(paths.deviceApi("d", "/api/sessions/abc/messages")).toBe("/v2/devices/d/api/sessions/abc/messages");
    expect(paths.deviceApi("d", hermesPaths.messages("20260918_204034_16def7"))).toBe("/v2/devices/d/api/sessions/20260918_204034_16def7/messages");
  });

  it("builds the device WebSocket URL from the page scheme and host", () => {
    expect(paths.deviceWs("d/1", { protocol: "https:", host: "go.example" })).toBe("wss://go.example/v2/devices/d%2F1/ws");
    expect(paths.deviceWs("d", { protocol: "http:", host: "localhost:8443" })).toBe("ws://localhost:8443/v2/devices/d/ws");
  });

  it("builds inbox, account and search paths", () => {
    expect(paths.inbox(12, 100)).toBe("/api/mobile/events?after=12&limit=100");
    expect(paths.selectDefault("a b")).toBe("/v2/web/devices/a%20b/select-default");
    expect(hermesPaths.search('"部署" x')).toBe("sessions/search?q=%22%E9%83%A8%E7%BD%B2%22%20x");
  });

  it("detects webDeviceAccess under accountAuth, where the Gateway advertises it", () => {
    const base = { version: 1, accountAuth: {}, binding: {}, legacy: { appTokenAccepted: true, connectorTokenAccepted: true } } as unknown as GatewayCapabilities;
    expect(supportsWebDeviceAccess(base)).toBe(false);
    expect(supportsWebDeviceAccess({ ...base, accountAuth: { ...base.accountAuth, webDeviceAccess: true } })).toBe(true);
  });
});

describe("GatewayClient requests", () => {
  it("GET: same-origin credentials, no CSRF header, parses JSON", async () => {
    const { client, calls } = fake(() => json(200, { sessions: [] }));
    await expect(client.sessions("dev")).resolves.toEqual({ sessions: [] });
    expect(calls[0]).toMatchObject({ url: "/v2/devices/dev/api/sessions", method: "GET", credentials: "same-origin" });
    expect(calls[0]!.headers["X-Hermes-CSRF"]).toBeUndefined();
  });

  it("non-GET: adds X-Hermes-CSRF from the cookie and JSON body", async () => {
    const { client, calls } = fake(() => json(200, { ok: true, changed: 2 }));
    await client.ackEvents(["e1", "e2", "e1"]);
    expect(calls[0]).toMatchObject({ url: "/api/mobile/events/ack", method: "POST", body: { event_ids: ["e1", "e2"] } });
    expect(calls[0]!.headers["X-Hermes-CSRF"]).toBe(CSRF);
    expect(calls[0]!.headers["content-type"]).toBe("application/json");
    await client.readEvents(["e3"]);
    expect(calls[1]!.url).toBe("/api/mobile/events/read");
  });

  it("validates inbox bounds", async () => {
    const { client, calls } = fake(() => json(200, { events: [], nextCursor: 0, hasMore: false }));
    await client.lifecycleEvents(5, 50);
    expect(calls[0]!.url).toBe("/api/mobile/events?after=5&limit=50");
    expect(() => client.lifecycleEvents(-1)).toThrow(RangeError);
    expect(() => client.lifecycleEvents(0, 501)).toThrow(RangeError);
    expect(() => client.ackEvents(Array.from({ length: 501 }, (_, i) => `e${i}`))).toThrow(RangeError);
  });

  it("throws GatewayHttpError with the structured body, and GatewayNetworkError when fetch rejects", async () => {
    const { client } = fake(() => json(403, { error: { code: "HR-WEB-001", message: "no" } }));
    const error = await client.deviceApi("d", "GET", "config").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(GatewayHttpError);
    expect(error).toMatchObject({ status: 403, code: "HR-WEB-001" });
    const offline = fake(() => {
      throw new TypeError("Failed to fetch");
    });
    await expect(offline.client.sessions("d")).rejects.toBeInstanceOf(GatewayNetworkError);
  });

  it("falls back to the CSRF token from /v2/web/session when the cookie is unreadable", async () => {
    const { client, calls } = fake(
      (c) => (c.url === paths.webSession ? json(200, { session: { authenticated: false }, csrfToken: CSRF, authentication: { google: null }, features: { accountDeletion: false } }) : json(202, { challenge: {} })),
      "",
    );
    await client.webSession();
    await client.requestEmailChallenge("a@b.example");
    expect(calls[1]).toMatchObject({ url: paths.emailChallenges, body: { email: "a@b.example" } });
    expect(calls[1]!.headers["X-Hermes-CSRF"]).toBe(CSRF);
  });

  it("sends an Idempotency-Key where the Gateway requires one", async () => {
    const { client, calls } = fake((c) => {
      if (c.url === paths.emailExchange) return json(200, { account: { id: "a" }, installation: {}, session: { authenticated: true, accessExpiresAt: future(900_000), refreshExpiresAt: future(9e8) } });
      if (c.url === paths.signOut) return new Response(null, { status: 204 });
      return json(200, { device: {} });
    });
    await client.exchangeEmail({ challengeId: "c", email: "a@b.example", code: "123456" });
    await client.selectDefaultDevice("d1");
    await client.signOut();
    for (const call of calls) expect(call.headers["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(calls[0]!.body).toEqual({ challengeId: "c", email: "a@b.example", code: "123456" });
    expect(calls[1]!.url).toBe("/v2/web/devices/d1/select-default");
  });
});

describe("single-flight refresh", () => {
  it("on 401 refreshes once for concurrent callers and retries each once", async () => {
    let authed = false;
    let refreshes = 0;
    const { client, calls } = fake(async (c) => {
      if (c.url === paths.refresh) {
        refreshes++;
        await new Promise((r) => setTimeout(r, 5));
        authed = true;
        return json(200, { session: { authenticated: true, accessExpiresAt: future(900_000), refreshExpiresAt: future(9e8) }, csrfToken: CSRF });
      }
      return authed ? json(200, { ok: c.url }) : json(401, { error: { code: "HR-AUTH-003" } });
    });
    const results = await Promise.all([client.sessions("d"), client.profileSessions("d"), client.messages("d", "s1")]);
    expect(refreshes).toBe(1);
    expect(results.map((r) => (r as unknown as { ok: string }).ok)).toEqual([
      "/v2/devices/d/api/sessions",
      "/v2/devices/d/api/profiles/sessions",
      "/v2/devices/d/api/sessions/s1/messages",
    ]);
    const refreshCall = calls.find((c) => c.url === paths.refresh)!;
    expect(refreshCall.method).toBe("POST");
    expect(refreshCall.headers["X-Hermes-CSRF"]).toBe(CSRF);
    expect(refreshCall.headers["Idempotency-Key"]).toBeDefined();
    client.stopKeepAlive();
  });

  it("emits signed-out once when the refresh is refused, and rethrows the original 401", async () => {
    const { client, calls } = fake((c) => (c.url === paths.refresh ? json(401, { error: { code: "HR-AUTH-005" } }) : json(401, { error: { code: "HR-AUTH-003" } })));
    const signedOut = vi.fn();
    client.onSignedOut(signedOut);
    const errors = await Promise.all([client.sessions("d").catch((e) => e), client.devices().catch((e) => e)]);
    expect(errors.map((e) => (e as GatewayHttpError).code)).toEqual(["HR-AUTH-003", "HR-AUTH-003"]);
    expect(signedOut).toHaveBeenCalledTimes(1);
    expect((signedOut.mock.calls[0]![0] as GatewayHttpError).code).toBe("HR-AUTH-005");
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(1);
  });

  it("does not sign out when the refresh fails for a network or server reason", async () => {
    const { client } = fake((c) => (c.url === paths.refresh ? json(503, {}) : json(401, {})));
    const signedOut = vi.fn();
    client.onSignedOut(signedOut);
    await expect(client.sessions("d")).rejects.toMatchObject({ status: 401 });
    expect(signedOut).not.toHaveBeenCalled();
    client.stopKeepAlive();
  });

  it("never refreshes for auth routes themselves", async () => {
    const { client, calls } = fake(() => json(401, { error: { code: "HR-AUTH-009" } }));
    await expect(client.exchangeEmail({ challengeId: "c", email: "e@x.example", code: "000000" })).rejects.toMatchObject({ status: 401 });
    expect(calls.map((c) => c.url)).toEqual([paths.emailExchange]);
  });

  it("retries the original only once", async () => {
    const { client, calls } = fake((c) => (c.url === paths.refresh
      ? json(200, { session: { authenticated: true, accessExpiresAt: future(900_000), refreshExpiresAt: future(9e8) }, csrfToken: CSRF })
      : json(401, {})));
    await expect(client.sessions("d")).rejects.toMatchObject({ status: 401 });
    expect(calls.map((c) => c.url)).toEqual(["/v2/devices/d/api/sessions", paths.refresh, "/v2/devices/d/api/sessions"]);
    client.stopKeepAlive();
  });
});

describe("proactive refresh (keep-alive)", () => {
  beforeEach(() => vi.useFakeTimers({ now: new Date("2026-09-21T12:00:00Z") }));
  afterEach(() => vi.useRealTimers());

  const refreshBody = (ms: number) => json(200, { session: { authenticated: true, accessExpiresAt: future(ms), refreshExpiresAt: future(9e8) }, csrfToken: CSRF });

  it("refreshes about two minutes before accessExpiresAt after sign-in, and keeps doing so", async () => {
    const { client, calls } = fake((c) => {
      if (c.url === paths.emailExchange) return json(200, { account: { id: "a" }, installation: {}, session: { authenticated: true, accessExpiresAt: future(15 * 60_000), refreshExpiresAt: future(9e8) } });
      if (c.url === paths.refresh) return refreshBody(15 * 60_000);
      return json(200, {});
    });
    await client.exchangeEmail({ challengeId: "c", email: "a@b.example", code: "123456" });
    await vi.advanceTimersByTimeAsync(13 * 60_000 - 1);
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(1);
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(13 * 60_000);
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(2);
    client.stopKeepAlive();
  });

  it("an authenticated /v2/web/session (no expiry) refreshes now to learn it", async () => {
    const { client, calls } = fake((c) => (c.url === paths.webSession
      ? json(200, { session: { authenticated: true, account: { id: "a" }, installation: {} }, csrfToken: CSRF, authentication: { google: null }, features: { accountDeletion: false } })
      : refreshBody(15 * 60_000)));
    await client.webSession();
    await vi.advanceTimersByTimeAsync(0);
    expect(calls.map((c) => c.url)).toEqual([paths.webSession, paths.refresh]);
    client.stopKeepAlive();
  });

  it("retries later after a transient failure and stops on sign-out", async () => {
    let fail = true;
    const { client, calls } = fake((c) => {
      if (c.url === paths.refresh) return fail ? json(503, {}) : refreshBody(15 * 60_000);
      if (c.url === paths.signOut) return new Response(null, { status: 204 });
      return json(200, {});
    });
    client.keepAlive(future(2 * 60_000));
    await vi.advanceTimersByTimeAsync(0);
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(1);
    fail = false;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(2);
    await client.signOut();
    await vi.advanceTimersByTimeAsync(60 * 60_000);
    expect(calls.filter((c) => c.url === paths.refresh)).toHaveLength(2);
  });

  it("stops after the refresh is refused", async () => {
    const { client, calls } = fake(() => json(401, { error: { code: "HR-AUTH-004" } }));
    const signedOut = vi.fn();
    client.onSignedOut(signedOut);
    client.keepAlive(future(60_000));
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(60 * 60_000);
    expect(calls).toHaveLength(1);
    expect(signedOut).toHaveBeenCalledTimes(1);
  });
});

describe("raw uploads and expiry tracking", () => {
  it("sends upload bytes unchanged with the caller's content-type, CSRF and the name in the query", async () => {
    const { client, calls } = fake(() => json(200, { path: "/Users/me/.hermes/uploads/a.png", name: "a b.png", size: 3 }));
    const blob = new Blob([new Uint8Array([1, 2, 3])], { type: "image/png" });
    const result = await client.uploadFile("dev", "a b.png", blob, "image/png");
    expect(result.path).toBe("/Users/me/.hermes/uploads/a.png");
    expect(calls[0]!.url).toBe("/v2/devices/dev/api/files/upload?name=a%20b.png");
    expect(calls[0]!.method).toBe("POST");
    expect(calls[0]!.body).toBe(blob);
    expect(calls[0]!.headers["content-type"]).toBe("image/png");
    expect(calls[0]!.headers["X-Hermes-CSRF"]).toBe(CSRF);
  });

  it("falls back to application/octet-stream when the file has no type", async () => {
    const { client, calls } = fake(() => json(200, { path: "/x" }));
    await client.uploadFile("dev", "notes", new Blob(["x"]), "");
    expect(calls[0]!.headers["content-type"]).toBe("application/octet-stream");
  });

  it("remembers the access expiry from keepAlive and forgets an unknown one", () => {
    const { client } = fake(() => json(200, {}));
    expect(client.accessExpiresAt).toBeNull();
    const at = future(600_000);
    client.keepAlive(at);
    expect(client.accessExpiresAt).toBe(Date.parse(at));
    client.stopKeepAlive();
    client.keepAlive(null);
    expect(client.accessExpiresAt).toBeNull();
    client.stopKeepAlive();
  });
});
