import assert from "node:assert/strict";
import type { IncomingMessage } from "node:http";
import test from "node:test";
import type { AccountPrincipal } from "./account/model.js";
import {
  browserResponseHeaders,
  browserRouteAllowed,
  browserRouteFor,
  WebDeviceAccess,
} from "./account/web-device-access.js";
import { WebSessionSecurity } from "./account/web-session-security.js";

const ORIGIN = "https://web.example.test";
const ACCESS = `hga_${"a".repeat(43)}`;
const CSRF = `hgc_${"c".repeat(43)}`;

function principal(kind: "browser" | "phone", platform: "web" | "android"): AccountPrincipal {
  return {
    account: { id: "account-1" } as AccountPrincipal["account"],
    installation: { id: "installation-1", kind, platform, displayName: "Test" },
    sessionId: "session-1",
    refreshFamilyId: "family-1",
  };
}

function request(method: string, headers: Record<string, string>): IncomingMessage {
  return { method, headers } as unknown as IncomingMessage;
}

function access(owner = principal("browser", "web"), live = true): {
  access: WebDeviceAccess;
  authorizations: string[];
} {
  const authorizations: string[] = [];
  return {
    authorizations,
    access: new WebDeviceAccess({
      security: new WebSessionSecurity(ORIGIN),
      authenticate: async (authorization) => {
        authorizations.push(authorization);
        return owner;
      },
      isSessionLive: async () => live,
    }),
  };
}

const cookie = `__Host-hermes_go_access=${ACCESS}; __Host-hermes_go_csrf=${CSRF}`;

test("browser route allowlist covers the chat client and nothing that administers the Mac", () => {
  for (const [method, path] of [
    ["GET", "/api/status"],
    ["GET", "/api/hermes-remote/contract"],
    ["GET", "/api/sessions"],
    ["HEAD", "/api/sessions"],
    ["GET", "/api/sessions/search"],
    ["GET", "/api/sessions/stats"],
    ["GET", "/api/sessions/abc"],
    ["GET", "/api/sessions/20260918_204034_16def7"],
    ["GET", "/api/sessions/abc/messages"],
    ["GET", "/api/profiles"],
    ["GET", "/api/profiles/sessions"],
    ["GET", "/api/files"],
    ["POST", "/api/files/upload"],
    // Web batch 4: session management and the model list.
    ["PATCH", "/api/sessions/abc"],
    ["DELETE", "/api/sessions/abc"],
    ["DELETE", "/api/sessions/abc?profile=work"],
    ["GET", "/api/model/options?profile=work"],
  ] as const) {
    assert.equal(browserRouteAllowed(method, path), true, `${method} ${path}`);
  }
  for (const [method, path] of [
    ["GET", "/api/env"],
    ["PUT", "/api/env"],
    ["POST", "/api/env/reveal"],
    ["POST", "/api/gateway/restart"],
    ["GET", "/api/config"],
    ["GET", "/api/cron/jobs"],
    ["POST", "/api/sessions"],
    ["DELETE", "/api/sessions/abc?force=1"],
    ["DELETE", "/api/sessions/abc?profile=a%2Fb"],
    ["PATCH", "/api/sessions/abc?profile=work"],
    ["PATCH", "/api/sessions/abc/messages"],
    ["DELETE", "/api/sessions"],
    ["GET", "/api/model/options?include_keys=1"],
    ["PUT", "/api/model/options"],
    ["GET", "/api/sessions/abc/messages/extra"],
    ["GET", "/api/skills"],
    ["POST", "/api/files"],
    ["GET", "/api/files/upload"],
    // An encoded separator would be decoded upstream into a sibling route.
    ["GET", "/api/sessions/abc%2Fexport"],
    ["GET", "/api/sessions/abc%2F..%2F..%2Fenv/messages"],
    ["GET", "/api/sessions/"],
  ] as const) {
    assert.equal(browserRouteAllowed(method, path), false, `${method} ${path}`);
  }
});

test("files from the Mac are always downloads, and only raster images keep their type", () => {
  const html = browserResponseHeaders("/api/files", {
    "content-type": "text/html; charset=utf-8",
    "content-disposition": "inline; filename=\"page.html\"",
  });
  assert.equal(html["content-type"], "application/octet-stream");
  assert.equal(html["content-disposition"], "attachment; filename=\"page.html\"");
  assert.equal(html["x-content-type-options"], "nosniff");
  assert.equal(html["content-security-policy"], "default-src 'none'; sandbox");

  const svg = browserResponseHeaders("/api/files", { "content-type": "image/svg+xml" });
  assert.equal(svg["content-type"], "application/octet-stream");
  assert.equal(svg["content-disposition"], "attachment");

  const png = browserResponseHeaders("/api/files", {
    "content-type": "image/png",
    "content-disposition": "attachment; filename=\"a.png\"",
  });
  assert.equal(png["content-type"], "image/png");
  assert.equal(png["content-disposition"], "attachment; filename=\"a.png\"");

  const json = browserResponseHeaders("/api/sessions", { "content-type": "application/json" });
  assert.equal(json["content-type"], "application/json");
  assert.equal(json["content-disposition"], undefined);
  assert.equal(json["content-security-policy"], "default-src 'none'; sandbox");
  const cached = browserResponseHeaders("/api/sessions", { "cache-control": "public, max-age=600" });
  assert.equal(cached["cache-control"], "private, no-store");
  assert.equal(cached.vary, "Cookie");
});

test("cookie reads need same-origin fetch metadata or the exact Origin", async () => {
  const { access: web, authorizations } = access();
  await web.authenticateRequest(request("GET", { cookie, "sec-fetch-site": "same-origin" }));
  await web.authenticateRequest(request("GET", { cookie, origin: ORIGIN }));
  assert.deepEqual(authorizations, [`Bearer ${ACCESS}`, `Bearer ${ACCESS}`]);
  for (const headers of <Array<Record<string, string>>>[
    { cookie },
    { cookie, "sec-fetch-site": "cross-site" },
    { cookie, "sec-fetch-site": "same-site" },
    { cookie, "sec-fetch-site": "cross-site", origin: ORIGIN },
    { cookie, origin: "https://evil.example.test" },
  ]) {
    await assert.rejects(
      web.authenticateRequest(request("GET", headers)),
      { code: "HR-AUTH-012" },
      JSON.stringify(headers),
    );
  }
});

test("cookie writes need the exact Origin and the CSRF double submit", async () => {
  const { access: web } = access();
  await web.authenticateRequest(request("POST", {
    cookie,
    origin: ORIGIN,
    "sec-fetch-site": "same-origin",
    "x-hermes-csrf": CSRF,
  }));
  for (const headers of <Array<Record<string, string>>>[
    { cookie, origin: ORIGIN, "sec-fetch-site": "same-origin" },
    { cookie, origin: ORIGIN, "x-hermes-csrf": `hgc_${"d".repeat(43)}` },
    { cookie, origin: "https://evil.example.test", "x-hermes-csrf": CSRF },
    { cookie, origin: ORIGIN, "sec-fetch-site": "cross-site", "x-hermes-csrf": CSRF },
  ]) {
    await assert.rejects(
      web.authenticateRequest(request("POST", headers)),
      { code: "HR-AUTH-012" },
      JSON.stringify(headers),
    );
  }
});

test("a cookie session that does not belong to a browser installation is refused", async () => {
  const { access: web } = access(principal("phone", "android"));
  await assert.rejects(
    web.authenticateRequest(request("GET", { cookie, "sec-fetch-site": "same-origin" })),
    { code: "HR-AUTH-012" },
  );
  await assert.rejects(
    web.authenticateUpgrade(request("GET", { cookie, origin: ORIGIN })),
    { code: "HR-AUTH-012" },
  );
});

test("WebSocket upgrades need the exact Origin; revalidation follows the session", async () => {
  const { access: web } = access();
  await web.authenticateUpgrade(request("GET", { cookie, origin: ORIGIN }));
  await assert.rejects(web.authenticateUpgrade(request("GET", { cookie })), { code: "HR-AUTH-012" });
  await assert.rejects(
    web.authenticateUpgrade(request("GET", { cookie, origin: "https://evil.example.test" })),
    { code: "HR-AUTH-012" },
  );
  await web.revalidate(principal("browser", "web"));
  await assert.rejects(access(undefined, false).access.revalidate(principal("browser", "web")), {
    code: "HR-AUTH-004",
  });
});

test("presence of the access cookie decides whether the Web path applies", () => {
  const { access: web } = access();
  assert.equal(web.presents(request("GET", {})), false);
  assert.equal(web.presents(request("GET", { cookie: `__Host-hermes_go_csrf=${CSRF}` })), false);
  assert.equal(web.presents(request("GET", { cookie })), true);
  // Unrelated or malformed cookies never divert a (native, bearer) request onto the Web path…
  assert.equal(web.presents(request("GET", { cookie: "garbage" })), false);
  assert.equal(web.presents(request("GET", { cookie: "x__Host-hermes_go_access=1" })), false);
  // …while a malformed header that does carry the access cookie takes it and is rejected there.
  assert.equal(web.presents(request("GET", { cookie: `junk; __Host-hermes_go_access=${ACCESS}` })), true);
});

test("a session PATCH body may only rename, archive or unarchive", () => {
  const route = browserRouteFor("PATCH", new URL("http://d/api/sessions/abc"))!;
  const ok = (body: unknown) => route.body!(body);
  assert.equal(route.audit, "session.update");
  assert.equal(ok({ title: "New name", profile: "work" }), true);
  assert.equal(ok({ archived: true }), true);
  assert.equal(ok({ archived: false, profile: "工作" }), true);
  assert.equal(ok({}), false);
  assert.equal(ok({ profile: "work" }), false, "profile alone changes nothing");
  assert.equal(ok({ title: "   " }), false);
  assert.equal(ok({ title: "x".repeat(201) }), false);
  assert.equal(ok({ archived: "true" }), false);
  assert.equal(ok({ title: "t", model: "x" }), false);
  assert.equal(ok({ title: "t", profile: "../x" }), false);
  assert.equal(ok([{ title: "t" }]), false);
  assert.equal(ok("title"), false);
  assert.equal(browserRouteFor("DELETE", new URL("http://d/api/sessions/abc?profile=work"))!.audit, "session.delete");
});
