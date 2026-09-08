import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { resolve } from "node:path";
import { test } from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import { ACCOUNT_WEB_APP_JS } from "./account/account-web-shell.js";
import type { AccountControlService } from "./account/account-control-service.js";
import type { AccountSharingService } from "./account/account-sharing-service.js";
import type { AccountService, AccountSessionResponse } from "./account/account-service.js";
import type { EmailOtpService } from "./account/email-otp-service.js";
import { accountErrors, type AccountPrincipal, type VerifiedExternalIdentity } from "./account/model.js";
import { WebSessionSecurity, WEB_COOKIE_NAMES } from "./account/web-session-security.js";

const ORIGIN = "https://accounts.example.test";
const GOOGLE_WEB_CLIENT_ID = "1234567890-hermes.apps.googleusercontent.com";
const IDEMPOTENCY_KEY = "9e0a2044-94fc-44d0-a81c-498ea343d085";
const CHALLENGE_ID = "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea";
const ACCESS = `hga_${"a".repeat(43)}`;
const REFRESH = `hgr_${"b".repeat(43)}`;
const INVITATION_ID = "51ae394a-bbdd-441e-9a43-d20395d3f58b";
const GRANT_ID = "61ae394a-bbdd-441e-9a43-d20395d3f58b";
const IDENTITY_ID = "71ae394a-bbdd-441e-9a43-d20395d3f58b";
const CURRENT_IDENTITY_ID = "81ae394a-bbdd-441e-9a43-d20395d3f58b";
const OTHER_INSTALLATION_ID = "91ae394a-bbdd-441e-9a43-d20395d3f58b";
const INVITATION_TOKEN = `hsi_${"s".repeat(43)}`;

test("Web sessions remain independently default-off", async () => {
  const response = await call(
    new AccountHttpController(true, {} as AccountService),
    "GET",
    "/v2/web/session",
  );
  assert.equal(response.status, 503);
  assert.equal((response.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-010");
});

test("Web-session failures keep stable bilingual and recovery contracts", async () => {
  const rejected = accountErrors.webRequestRejected();
  assert.equal(rejected.status, 403);
  assert.equal(rejected.retryable, false);
  assert.equal(rejected.recoveryAction, "sign_in");
  const disabled = accountErrors.webSessionFeatureDisabled();
  assert.equal(disabled.status, 503);
  assert.equal(disabled.retryable, false);
  assert.equal(disabled.recoveryAction, "none");
  const lastIdentity = accountErrors.lastIdentityRequired();
  assert.equal(lastIdentity.status, 409);
  assert.equal(lastIdentity.retryable, false);
  assert.equal(lastIdentity.recoveryAction, "none");
  const registry = await readFile(resolve("../docs/ERROR_HANDLING.md"), "utf8");
  for (const code of [
    "HR-AUTH-012",
    "HR-AUTH-013",
    "HR-ACCOUNT-010",
    "HR-ACCOUNT-011",
    "HR-ACCOUNT-012",
  ]) {
    const row = registry.split("\n").find((line) => line.includes(`\`${code}\``));
    assert(row, `missing ${code}`);
    assert.match(row, /[\u3400-\u9fff]/);
    assert.match(row, /[A-Za-z]{3,}/);
  }
});

test("Web bootstrap issues host-only browser and CSRF cookies without an account bearer", async () => {
  const response = await call(controller().controller, "GET", "/v2/web/session");
  assert.equal(response.status, 200);
  assert.deepEqual((response.json() as { session: unknown }).session, { authenticated: false });
  assert.deepEqual(
    (response.json() as { authentication: unknown }).authentication,
    { google: { clientId: GOOGLE_WEB_CLIENT_ID } },
  );
  assert.deepEqual(
    (response.json() as { features: unknown }).features,
    { accountDeletion: false },
  );
  const cookies = response.cookies();
  assert.equal(cookies.length, 2);
  assert.match(cookieValue(cookies, WEB_COOKIE_NAMES.installation), /^[0-9a-f-]{36}$/);
  assert.match(cookieValue(cookies, WEB_COOKIE_NAMES.csrf), /^hgc_[A-Za-z0-9_-]{43}$/);
  assert.match(cookieLine(cookies, WEB_COOKIE_NAMES.installation), /; Secure; HttpOnly; SameSite=Strict$/);
  assert.match(cookieLine(cookies, WEB_COOKIE_NAMES.csrf), /; Secure; SameSite=Strict$/);
  assert.doesNotMatch(response.body, /hga_|hgr_/);
});

test("Web bootstrap clears stale credentials and explains an already-pending permanent deletion", async () => {
  const service = {
    authenticate: async () => { throw accountErrors.accountDeletionPending(); },
  } as unknown as AccountService;
  const response = await call(
    new AccountHttpController(true, service, {
      webSessionEnabled: true,
      webAccountCenterEnabled: true,
      webSessionSecurity: new WebSessionSecurity(ORIGIN),
    }),
    "GET",
    "/v2/web/session",
    { cookie: `${WEB_COOKIE_NAMES.access}=${ACCESS}` },
  );
  assert.equal(response.status, 200);
  assert.deepEqual((response.json() as { session: unknown }).session, {
    authenticated: false,
    accountDeletionPending: true,
  });
  assert.equal(response.cookies().length, 6);
  assert.equal(response.cookies().filter((value) => value.includes("Max-Age=0")).length, 4);
  assert.match(ACCOUNT_WEB_APP_JS, /showDeletionPending/);
});

test("email-first Web mode omits Google configuration, routes, and third-party CSP", async () => {
  const fixture = controller(false);
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  assert.equal(bootstrap.status, 200);
  assert.deepEqual(
    (bootstrap.json() as { authentication: unknown }).authentication,
    { google: null },
  );

  const shell = await call(fixture.controller, "GET", "/account");
  assert.equal(shell.status, 200);
  assert.equal(shell.headers["cross-origin-opener-policy"], "same-origin");
  assert.doesNotMatch(String(shell.headers["content-security-policy"]), /accounts\.google\.com/);

  const googleExchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/google/exchange",
    {},
    {},
  );
  assert.equal(googleExchange.status, 404);
  assert.equal(
    (googleExchange.json() as { error: { code: string } }).error.code,
    "HR-ACCOUNT-004",
  );
  assert.equal(fixture.calls.some((entry) => entry.operation === "google_exchange"), false);
});

test("Web mutations fail closed for missing or foreign origin, cross-site fetch, and CSRF mismatch", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const cookieHeader = requestCookies(bootstrap.cookies());
  const csrf = cookieValue(bootstrap.cookies(), WEB_COOKIE_NAMES.csrf);
  const attempts: Array<Record<string, string>> = [
    {},
    { origin: "https://attacker.example", "x-hermes-csrf": csrf },
    { origin: ORIGIN, "sec-fetch-site": "cross-site", "x-hermes-csrf": csrf },
    { origin: ORIGIN, "sec-fetch-site": "same-origin", "x-hermes-csrf": `${csrf}x` },
  ];
  for (const headers of attempts) {
    const response = await call(
      fixture.controller,
      "POST",
      "/v2/web/auth/email/challenges",
      { ...headers, cookie: cookieHeader },
      { email: "person@example.com" },
    );
    assert.equal(response.status, 403);
    assert.equal((response.json() as { error: { code: string } }).error.code, "HR-AUTH-012");
  }
  assert.equal(fixture.calls.length, 0);

  const duplicate = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/challenges",
    {
      origin: ORIGIN,
      "sec-fetch-site": "same-origin",
      "x-hermes-csrf": csrf,
      cookie: `${cookieHeader}; ${WEB_COOKIE_NAMES.csrf}=${csrf}`,
    },
    { email: "person@example.com" },
  );
  assert.equal(duplicate.status, 403);
  assert.equal((duplicate.json() as { error: { code: string } }).error.code, "HR-AUTH-012");
});

test("email login, refresh, account read, and sign-out keep bearer credentials in HttpOnly cookies", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const browserCookies = bootstrap.cookies();
  const csrf = cookieValue(browserCookies, WEB_COOKIE_NAMES.csrf);
  const browserHeaders = {
    origin: ORIGIN,
    "sec-fetch-site": "same-origin",
    "x-hermes-csrf": csrf,
    cookie: requestCookies(browserCookies),
    "idempotency-key": IDEMPOTENCY_KEY,
  };

  const challenge = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/challenges",
    browserHeaders,
    { email: "person@example.com" },
  );
  assert.equal(challenge.status, 202);

  const exchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/exchange",
    browserHeaders,
    {
      challengeId: CHALLENGE_ID,
      email: "person@example.com",
      code: "012345",
      displayName: "Safari on Mac",
    },
  );
  assert.equal(exchange.status, 200);
  assert.equal(exchange.body.includes(ACCESS), false);
  assert.equal(exchange.body.includes(REFRESH), false);
  assert.match(cookieLine(exchange.cookies(), WEB_COOKIE_NAMES.access), /; Secure; HttpOnly; SameSite=Strict$/);
  assert.match(cookieLine(exchange.cookies(), WEB_COOKIE_NAMES.refresh), /; Secure; HttpOnly; SameSite=Strict$/);
  assert.deepEqual(fixture.calls.slice(0, 3).map(({ operation }) => operation), [
    "challenge", "verify", "exchange",
  ]);
  const exchanged = fixture.calls[2].input as { platform: string; clientInstallationId: string };
  assert.equal(exchanged.platform, "web");
  assert.equal(exchanged.clientInstallationId, cookieValue(browserCookies, WEB_COOKIE_NAMES.installation));

  const authenticatedCookies = requestCookies(exchange.cookies());
  const account = await call(fixture.controller, "GET", "/v2/web/account", {
    cookie: authenticatedCookies,
    authorization: "Bearer caller-supplied-value-must-be-ignored",
  });
  assert.equal(account.status, 200);
  assert.equal((account.json() as { installation: { kind: string } }).installation.kind, "browser");
  assert.equal(fixture.calls.at(-1)?.operation, "authenticate");
  assert.equal(fixture.calls.at(-1)?.input, `Bearer ${ACCESS}`);

  const refresh = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/refresh",
    { ...browserHeaders, cookie: authenticatedCookies },
  );
  assert.equal(refresh.status, 200);
  assert.equal(refresh.body.includes(ACCESS), false);
  assert.equal(refresh.body.includes(REFRESH), false);
  assert.equal(fixture.calls.at(-1)?.operation, "refresh");

  const signOut = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/sign-out",
    { ...browserHeaders, cookie: authenticatedCookies },
  );
  assert.equal(signOut.status, 204);
  assert.equal(signOut.cookies().length, 4);
  assert.equal(signOut.cookies().every((value) => value.includes("Max-Age=0")), true);
  assert.equal(fixture.calls.at(-1)?.operation, "sign_out");
});

test("Web account deletion is hidden by default and clears all session cookies after explicit confirmation", async () => {
  const hidden = controller();
  const hiddenResponse = await call(hidden.controller, "DELETE", "/v2/web/account");
  assert.equal(hiddenResponse.status, 404);
  assert.equal(hidden.calls.some(({ operation }) => operation === "account_delete"), false);

  const fixture = controller(true, true);
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  assert.deepEqual(
    (bootstrap.json() as { features: unknown }).features,
    { accountDeletion: true },
  );
  const initialCookies = bootstrap.cookies();
  const csrf = cookieValue(initialCookies, WEB_COOKIE_NAMES.csrf);
  const exchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/exchange",
    mutationHeaders(requestCookies(initialCookies), csrf),
    {
      challengeId: CHALLENGE_ID,
      email: "person@example.com",
      code: "012345",
      displayName: "Safari on Mac",
    },
  );
  const cookies = requestCookies(exchange.cookies());
  const headers = mutationHeaders(cookies, csrf);

  const missingConfirmation = await call(
    fixture.controller,
    "DELETE",
    "/v2/web/account",
    headers,
    { grant: `hgg_${"g".repeat(43)}` },
  );
  assert.equal(missingConfirmation.status, 400);
  assert.equal(
    (missingConfirmation.json() as { error: { code: string } }).error.code,
    "HR-ACCOUNT-004",
  );

  const deleted = await call(
    fixture.controller,
    "DELETE",
    "/v2/web/account",
    headers,
    {
      grant: `hgg_${"g".repeat(43)}`,
      acknowledgedPermanentCloudDeletion: true,
    },
  );
  assert.equal(deleted.status, 204);
  assert.equal(deleted.cookies().length, 4);
  assert.equal(deleted.cookies().every((value) => value.includes("Max-Age=0")), true);
  assert.deepEqual(fixture.calls.at(-1), {
    operation: "account_delete",
    input: {
      authorization: `Bearer ${ACCESS}`,
      grant: `hgg_${"g".repeat(43)}`,
      idempotencyKey: IDEMPOTENCY_KEY,
      acknowledgedPermanentCloudDeletion: true,
    },
  });
});

test("Google Web exchange uses the browser installation and never returns provider or Hermes credentials", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const cookies = bootstrap.cookies();
  const providerProof = "google-provider-proof-must-not-return";
  const response = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/google/exchange",
    {
      origin: ORIGIN,
      "sec-fetch-site": "same-origin",
      "x-hermes-csrf": cookieValue(cookies, WEB_COOKIE_NAMES.csrf),
      cookie: requestCookies(cookies),
      "idempotency-key": IDEMPOTENCY_KEY,
    },
    { idToken: providerProof, nonce: "1234567890abcdef" },
  );
  assert.equal(response.status, 200);
  assert.equal(response.body.includes(providerProof), false);
  assert.equal(fixture.calls.at(-1)?.operation, "google_exchange");
  assert.equal((fixture.calls.at(-1)?.input as { platform: string }).platform, "web");
});

test("interactive account shell uses same-origin assets and a restrictive executable-content policy", async () => {
  const fixture = controller();
  const shell = await call(fixture.controller, "GET", "/account");
  assert.equal(shell.status, 200);
  assert.match(shell.body, /src="\/account\/assets\/account\.js"/);
  assert.match(shell.body, /href="\/account\/assets\/account\.css"/);
  assert.doesNotMatch(shell.body, /<script(?![^>]+src=)/);
  assert.equal(shell.headers["content-security-policy"],
    "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' https://accounts.google.com/gsi/style; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
  assert.equal(shell.headers["cross-origin-opener-policy"], "same-origin-allow-popups");
  assert.equal(shell.headers["permissions-policy"],
    "camera=(), microphone=(), geolocation=(), payment=(), usb=()");

  const script = await call(fixture.controller, "GET", "/account/assets/account.js");
  assert.equal(script.status, 200);
  assert.equal(script.headers["content-type"], "text/javascript; charset=utf-8");
  assert.match(script.body, /\/v2\/web\/devices/);
  assert.match(script.body, /https:\/\/accounts\.google\.com\/gsi\/client/);
  assert.match(script.body, /nonce: state\.googleNonce/);
  assert.match(script.body, /\/v2\/web\/auth\/google\/exchange/);
  assert.match(script.body, /\/v2\/web\/auth\/reauth\/google/);
  assert.match(script.body, /\/v2\/web\/identities\/google/);
  assert.match(script.body, /scope: 'device\.share'/);
  assert.match(script.body, /scope: 'account\.installation\.revoke'/);
  assert.match(script.body, /scope: 'account\.delete'/);
  assert.match(script.body, /云端账号删除已提交 \/ Cloud account deletion submitted/);
  assert.match(script.body, /删除已提交 \/ Deletion submitted/);
  assert.match(shell.body, /id="deletion-new-account" type="button" hidden>使用其他邮箱账号 \/ Use another email account/);
  assert.match(script.body, /deletion-new-account'\)\.addEventListener\('click', showSignIn\)/);
  assert.match(script.body, /show\('deletion-new-account'\)/);
  assert.doesNotMatch(script.body, /账号已注销 \/ Account deleted/);
  assert.match(script.body, /此 Hermes GO 账号正在永久删除/);
  assert.match(shell.body, /id="account-deletion-acknowledgement" type="checkbox" required/);
  assert.match(script.body, /account-deletion-acknowledgement'\)\.checked !== true/);
  assert.match(script.body, /acknowledgedPermanentCloudDeletion: byId\('account-deletion-acknowledgement'\)\.checked === true/);
  const deletionFunction = script.body.match(/async function completeAccountDeletion\(grant\) \{[\s\S]*?\n  \}/)?.[0] ?? "";
  assert.match(deletionFunction, /account-deletion-dialog'\)\.close\(\); showDeletionPending\(\)/);
  assert.doesNotMatch(deletionFunction, /location\.replace/);
  assert.match(script.body, /value !== 'DELETE'/);
  assert.match(script.body, /prepareGoogleButton\('share-google-button', 'reauth_share'\)/);
  assert.match(script.body, /prepareGoogleButton\('installation-google-button', 'reauth_installation'\)/);
  assert.match(script.body, /prepareGoogleButton\('account-deletion-google-button', 'reauth_account_delete'\)/);
  assert.doesNotMatch(script.body, new RegExp(GOOGLE_WEB_CLIENT_ID));
  assert.doesNotMatch(script.body, /innerHTML|outerHTML|document\.write|localStorage|sessionStorage/);
  assert.doesNotMatch(script.body, /hga_[A-Za-z0-9_-]{43}|hgr_[A-Za-z0-9_-]{43}/);
});

test("Web management routes use only cookie authentication and require CSRF on every mutation", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const initialCookies = bootstrap.cookies();
  const csrf = cookieValue(initialCookies, WEB_COOKIE_NAMES.csrf);
  const exchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/exchange",
    {
      origin: ORIGIN,
      "sec-fetch-site": "same-origin",
      "x-hermes-csrf": csrf,
      cookie: requestCookies(initialCookies),
      "idempotency-key": IDEMPOTENCY_KEY,
    },
    { challengeId: CHALLENGE_ID, email: "person@example.com", code: "012345" },
  );
  const cookies = requestCookies(exchange.cookies());

  const identities = await call(fixture.controller, "GET", "/v2/web/identities", {
    cookie: cookies,
    authorization: "Bearer ignored-browser-header",
  });
  assert.equal(identities.status, 200);
  assert.equal((identities.json() as { items: unknown[] }).items.length, 1);
  assert.equal(fixture.calls.at(-1)?.operation, "identities");

  const devices = await call(fixture.controller, "GET", "/v2/web/devices", { cookie: cookies });
  assert.equal(devices.status, 200);
  assert.equal((devices.json() as { items: unknown[] }).items.length, 1);

  const rejected = await call(
    fixture.controller,
    "POST",
    "/v2/web/devices/hermes-office/select-default",
    { origin: ORIGIN, cookie: cookies, "idempotency-key": IDEMPOTENCY_KEY },
  );
  assert.equal(rejected.status, 403);
  assert.equal((rejected.json() as { error: { code: string } }).error.code, "HR-AUTH-012");

  const selected = await call(
    fixture.controller,
    "POST",
    "/v2/web/devices/hermes-office/select-default",
    mutationHeaders(cookies, csrf),
  );
  assert.equal(selected.status, 200);
  assert.equal(fixture.calls.at(-1)?.operation, "select_default");
  assert.equal((fixture.calls.at(-1)?.input as { principal: AccountPrincipal }).principal.installation.kind,
    "browser");
});

test("Web sharing routes cover recent email verification and the complete invitation lifecycle", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const initialCookies = bootstrap.cookies();
  const csrf = cookieValue(initialCookies, WEB_COOKIE_NAMES.csrf);
  const exchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/exchange",
    mutationHeaders(requestCookies(initialCookies), csrf),
    { challengeId: CHALLENGE_ID, email: "person@example.com", code: "012345" },
  );
  const cookies = requestCookies(exchange.cookies());
  const headers = mutationHeaders(cookies, csrf);

  const reauthChallenge = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/reauth/email/challenges",
    headers,
    { email: "person@example.com" },
  );
  assert.equal(reauthChallenge.status, 202);
  const verified = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/reauth/email",
    headers,
    {
      challengeId: CHALLENGE_ID,
      email: "person@example.com",
      code: "012345",
      scope: "device.share",
    },
  );
  assert.equal(verified.status, 200);
  assert.equal((verified.json() as { grant: string }).grant, `hgg_${"g".repeat(43)}`);

  const listed = await call(
    fixture.controller,
    "GET",
    "/v2/web/devices/hermes-office/shares",
    { cookie: cookies },
  );
  assert.equal(listed.status, 200);

  const created = await call(
    fixture.controller,
    "POST",
    "/v2/web/devices/hermes-office/share-invitations",
    headers,
    {
      email: "guest@example.com",
      grant: `hgg_${"g".repeat(43)}`,
      acknowledgedWholeDeviceAccess: true,
    },
  );
  assert.equal(created.status, 202);

  const cancelled = await call(
    fixture.controller,
    "DELETE",
    `/v2/web/devices/hermes-office/share-invitations/${INVITATION_ID}`,
    headers,
  );
  assert.equal(cancelled.status, 204);
  const revoked = await call(
    fixture.controller,
    "DELETE",
    `/v2/web/devices/hermes-office/shares/${GRANT_ID}`,
    headers,
  );
  assert.equal(revoked.status, 204);
  const accepted = await call(
    fixture.controller,
    "POST",
    `/v2/web/share-invitations/${INVITATION_TOKEN}/accept`,
    headers,
    { acknowledgedWholeDeviceAccess: true },
  );
  assert.equal(accepted.status, 200);
  const left = await call(
    fixture.controller,
    "POST",
    "/v2/web/devices/hermes-office/leave",
    headers,
  );
  assert.equal(left.status, 204);
  assert.deepEqual(fixture.calls.map(({ operation }) => operation).filter((operation) => (
    operation === "shares" || operation.startsWith("share_")
  )), [
    "shares", "share_create", "share_cancel", "share_revoke", "share_accept", "share_leave",
  ]);
});

test("Web identity routes reauthenticate/link email or Google and clear Cookies after current-session unlink", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const initialCookies = bootstrap.cookies();
  const csrf = cookieValue(initialCookies, WEB_COOKIE_NAMES.csrf);
  const exchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/exchange",
    mutationHeaders(requestCookies(initialCookies), csrf),
    { challengeId: CHALLENGE_ID, email: "person@example.com", code: "012345" },
  );
  const cookies = requestCookies(exchange.cookies());
  const headers = mutationHeaders(cookies, csrf);

  for (const path of ["/v2/web/auth/reauth/google", "/v2/web/identities/google"]) {
    const rejected = await call(
      fixture.controller,
      "POST",
      path,
      { origin: ORIGIN, cookie: cookies, "idempotency-key": IDEMPOTENCY_KEY },
      { idToken: "must-not-reach-service", nonce: "1234567890abcdef", grant: "ignored", scope: "account.identity.link" },
    );
    assert.equal(rejected.status, 403);
    assert.equal((rejected.json() as { error: { code: string } }).error.code, "HR-AUTH-012");
  }

  const challenge = await call(
    fixture.controller,
    "POST",
    "/v2/web/identities/email/challenges",
    headers,
    { email: "second@example.com" },
  );
  assert.equal(challenge.status, 202);
  assert.equal((fixture.calls.at(-1)?.input as { purpose: string }).purpose, "link_identity");

  const linked = await call(
    fixture.controller,
    "POST",
    "/v2/web/identities/email",
    headers,
    {
      challengeId: CHALLENGE_ID,
      email: "second@example.com",
      code: "012345",
      grant: `hgg_${"g".repeat(43)}`,
    },
  );
  assert.equal(linked.status, 200);
  assert.equal(fixture.calls.at(-1)?.operation, "link_email");

  const googleReauthentication = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/reauth/google",
    headers,
    {
      idToken: "current-google-proof",
      nonce: "1234567890abcdef",
      scope: "account.identity.link",
    },
  );
  assert.equal(googleReauthentication.status, 200);
  assert.equal(fixture.calls.at(-1)?.operation, "reauthenticate_google");
  assert.equal(googleReauthentication.body.includes("current-google-proof"), false);

  const linkedGoogle = await call(
    fixture.controller,
    "POST",
    "/v2/web/identities/google",
    headers,
    {
      idToken: "target-google-proof",
      nonce: "1234567890abcdef",
      grant: `hgg_${"g".repeat(43)}`,
    },
  );
  assert.equal(linkedGoogle.status, 200);
  assert.equal(fixture.calls.at(-1)?.operation, "link_google");
  assert.equal(linkedGoogle.body.includes("target-google-proof"), false);

  const unlinked = await call(
    fixture.controller,
    "DELETE",
    `/v2/web/identities/${IDENTITY_ID}`,
    headers,
    { grant: `hgg_${"g".repeat(43)}` },
  );
  assert.equal(unlinked.status, 200);
  assert.equal((unlinked.json() as { currentSessionRevoked: boolean }).currentSessionRevoked, false);
  assert.equal(unlinked.cookies().every((value) => !value.includes("Max-Age=0")), true);

  const current = await call(
    fixture.controller,
    "DELETE",
    `/v2/web/identities/${CURRENT_IDENTITY_ID}`,
    headers,
    { grant: `hgg_${"g".repeat(43)}` },
  );
  assert.equal(current.status, 200);
  assert.equal((current.json() as { currentSessionRevoked: boolean }).currentSessionRevoked, true);
  assert.equal(current.cookies().length, 4);
  assert.equal(current.cookies().every((value) => value.includes("Max-Age=0")), true);
});

test("Web security routes list installations and redacted audit events, then revoke another login", async () => {
  const fixture = controller();
  const bootstrap = await call(fixture.controller, "GET", "/v2/web/session");
  const initialCookies = bootstrap.cookies();
  const csrf = cookieValue(initialCookies, WEB_COOKIE_NAMES.csrf);
  const exchange = await call(
    fixture.controller,
    "POST",
    "/v2/web/auth/email/exchange",
    mutationHeaders(requestCookies(initialCookies), csrf),
    { challengeId: CHALLENGE_ID, email: "person@example.com", code: "012345" },
  );
  const cookies = requestCookies(exchange.cookies());
  const headers = mutationHeaders(cookies, csrf);

  const installations = await call(
    fixture.controller,
    "GET",
    "/v2/web/installations",
    { cookie: cookies },
  );
  assert.equal(installations.status, 200);
  assert.equal((installations.json() as { items: unknown[] }).items.length, 2);

  const events = await call(
    fixture.controller,
    "GET",
    "/v2/web/audit-events",
    { cookie: cookies },
  );
  assert.equal(events.status, 200);
  assert.equal(events.body.includes("metadata"), false);
  assert.equal(events.body.includes("secret"), false);

  const revoked = await call(
    fixture.controller,
    "DELETE",
    `/v2/web/installations/${OTHER_INSTALLATION_ID}`,
    headers,
    { grant: `hgg_${"g".repeat(43)}` },
  );
  assert.equal(revoked.status, 204);
  assert.equal(fixture.calls.at(-1)?.operation, "installation_revoke");
  assert.deepEqual(fixture.calls.at(-1)?.input, {
    installationId: OTHER_INSTALLATION_ID,
    grant: `hgg_${"g".repeat(43)}`,
    idempotencyKey: IDEMPOTENCY_KEY,
  });
});

function controller(googleAuthEnabled = true, accountDeletionEnabled = false): {
  controller: AccountHttpController;
  calls: Array<{ operation: string; input?: unknown }>;
} {
  const calls: Array<{ operation: string; input?: unknown }> = [];
  const principal: AccountPrincipal = {
    account: { id: "account-1", email: "person@example.com" },
    installation: {
      id: "browser-installation-1",
      kind: "browser",
      platform: "web",
      displayName: "Safari on Mac",
    },
    sessionId: "session-1",
    refreshFamilyId: "family-1",
  };
  const session: AccountSessionResponse = {
    account: principal.account,
    installation: principal.installation,
    session: {
      accessToken: ACCESS,
      accessExpiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
      refreshToken: REFRESH,
      refreshExpiresAt: new Date(Date.now() + 30 * 24 * 60 * 60_000).toISOString(),
    },
  };
  const service = {
    authenticate: async (authorization: string) => {
      calls.push({ operation: "authenticate", input: authorization });
      return principal;
    },
    exchangeEmailIdentity: async (_identity: VerifiedExternalIdentity, input: unknown) => {
      calls.push({ operation: "exchange", input });
      return session;
    },
    exchangeGoogleProof: async (input: unknown) => {
      calls.push({ operation: "google_exchange", input });
      return session;
    },
    refresh: async (input: unknown) => {
      calls.push({ operation: "refresh", input });
      return session.session;
    },
    signOut: async (authorization: string, idempotencyKey: string) => {
      calls.push({ operation: "sign_out", input: { authorization, idempotencyKey } });
    },
    requestAccountDeletion: async (
      authorization: string,
      grant: string,
      idempotencyKey: string,
      acknowledgedPermanentCloudDeletion: boolean,
    ) => {
      calls.push({
        operation: "account_delete",
        input: {
          authorization,
          grant,
          idempotencyKey,
          acknowledgedPermanentCloudDeletion,
        },
      });
    },
    listExternalIdentities: async () => {
      calls.push({ operation: "identities" });
      return [{
        id: IDENTITY_ID,
        provider: "email_otp" as const,
        email: "person@example.com",
        verifiedAt: "2026-09-07T00:00:00.000Z",
      }];
    },
    reauthenticateEmailIdentity: async (
      _principal: AccountPrincipal,
      _identity: unknown,
      input: { scope: "device.share" },
    ) => {
      calls.push({ operation: "reauthenticate", input });
      return {
        grant: `hgg_${"g".repeat(43)}`,
        scope: input.scope,
        expiresAt: "2026-09-07T00:05:00.000Z",
      };
    },
    reauthenticateGoogle: async (
      _principal: AccountPrincipal,
      input: { scope: "device.share" },
    ) => {
      calls.push({ operation: "reauthenticate_google", input });
      return {
        grant: `hgg_${"g".repeat(43)}`,
        scope: input.scope,
        expiresAt: "2026-09-07T00:05:00.000Z",
      };
    },
    linkEmailIdentity: async (_principal: AccountPrincipal, _identity: unknown, input: unknown) => {
      calls.push({ operation: "link_email", input });
      return {
        id: "91ae394a-bbdd-441e-9a43-d20395d3f58b",
        provider: "email_otp" as const,
        email: "second@example.com",
        verifiedAt: "2026-09-07T00:03:00.000Z",
      };
    },
    linkGoogleIdentity: async (_principal: AccountPrincipal, input: unknown) => {
      calls.push({ operation: "link_google", input });
      return {
        id: "a1ae394a-bbdd-441e-9a43-d20395d3f58b",
        provider: "google" as const,
        email: "google@example.com",
        verifiedAt: "2026-09-07T00:04:00.000Z",
      };
    },
    unlinkIdentity: async (_principal: AccountPrincipal, input: { identityId: string }) => {
      calls.push({ operation: "unlink_identity", input });
      return {
        identity: {
          id: input.identityId,
          provider: "email_otp" as const,
          email: "person@example.com",
          verifiedAt: "2026-09-07T00:00:00.000Z",
        },
        currentSessionRevoked: input.identityId === CURRENT_IDENTITY_ID,
      };
    },
  } as unknown as AccountService;
  const emailOtp = {
    requestChallenge: async (input: unknown) => {
      calls.push({ operation: "challenge", input });
      return {
        challengeId: CHALLENGE_ID,
        expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
        resendAfter: new Date(Date.now() + 60_000).toISOString(),
      };
    },
    verifyChallenge: async (input: unknown) => {
      calls.push({ operation: "verify", input });
      return {
        provider: "email_otp",
        issuer: ORIGIN,
        subject: "e".repeat(64),
        email: "person@example.com",
      } as VerifiedExternalIdentity;
    },
  } as EmailOtpService;
  const device = {
    id: "10000000-0000-4000-8000-000000000001",
    generation: 1,
    deviceId: "hermes-office",
    desktopDisplayName: "Office Mac",
    publicKeyFingerprint: "f".repeat(64),
    connector: { online: true },
    hermes: { reachable: true },
    gateway: {},
    endToEnd: { healthy: true },
    access: "owner" as const,
    isDefault: false,
  };
  const control = {
    listDevices: async (input: AccountPrincipal) => {
      calls.push({ operation: "devices", input });
      return [device];
    },
    selectDefaultDevice: async (input: AccountPrincipal, deviceId: string, idempotencyKey: string) => {
      calls.push({ operation: "select_default", input: { principal: input, deviceId, idempotencyKey } });
      return { ...device, isDefault: true };
    },
    listAccountInstallations: async (input: AccountPrincipal) => {
      calls.push({ operation: "installations", input });
      return [
        {
          ...input.installation,
          createdAt: "2026-09-07T00:00:00.000Z",
          lastSeenAt: "2026-09-07T00:01:00.000Z",
          status: "active" as const,
          current: true,
          activeSessionCount: 1,
        },
        {
          id: OTHER_INSTALLATION_ID,
          kind: "desktop" as const,
          platform: "macos" as const,
          displayName: "Office Mac",
          createdAt: "2026-09-06T00:00:00.000Z",
          lastSeenAt: "2026-09-07T00:00:00.000Z",
          status: "active" as const,
          current: false,
          activeSessionCount: 2,
        },
      ];
    },
    listAccountAuditEvents: async (input: AccountPrincipal) => {
      calls.push({ operation: "audit_events", input });
      return [{
        id: "a1ae394a-bbdd-441e-9a43-d20395d3f58b",
        eventType: "auth.session.created",
        occurredAt: "2026-09-07T00:00:00.000Z",
        actorInstallation: input.installation,
      }];
    },
    revokeAccountInstallation: async (
      _principal: AccountPrincipal,
      installationId: string,
      grant: string,
      idempotencyKey: string,
    ) => {
      calls.push({
        operation: "installation_revoke",
        input: { installationId, grant, idempotencyKey },
      });
    },
  } as unknown as AccountControlService;
  const sharing = {
    listShares: async (input: AccountPrincipal, deviceId: string) => {
      calls.push({ operation: "shares", input: { principal: input, deviceId } });
      return { invitations: [], grants: [], maxGranteesPerDevice: 5 };
    },
    createInvitation: async (input: AccountPrincipal, request: unknown) => {
      calls.push({ operation: "share_create", input: { principal: input, request } });
      return {
        id: INVITATION_ID,
        deviceId: "hermes-office",
        targetEmailHint: "g***@example.com",
        status: "pending" as const,
        expiresAt: "2026-09-10T00:00:00.000Z",
        createdAt: "2026-09-07T00:00:00.000Z",
      };
    },
    cancelInvitation: async (...input: unknown[]) => {
      calls.push({ operation: "share_cancel", input });
    },
    revokeGrant: async (...input: unknown[]) => {
      calls.push({ operation: "share_revoke", input });
    },
    acceptInvitation: async (...input: unknown[]) => {
      calls.push({ operation: "share_accept", input });
      return { ...device, access: "operator" as const };
    },
    leaveDevice: async (...input: unknown[]) => {
      calls.push({ operation: "share_leave", input });
    },
  } as unknown as AccountSharingService;
  return {
    calls,
    controller: new AccountHttpController(true, service, {
      emailOtpEnabled: true,
      emailOtpService: emailOtp,
      identityManagementEnabled: true,
      accountDeletionEnabled,
      controlEnabled: true,
      controlService: control,
      multiDeviceEnabled: true,
      sharingEnabled: true,
      sharingService: sharing,
      webAccountCenterEnabled: true,
      webSessionEnabled: true,
      googleAuthEnabled,
      ...(googleAuthEnabled ? { googleWebClientId: GOOGLE_WEB_CLIENT_ID } : {}),
      webSessionSecurity: new WebSessionSecurity(ORIGIN),
    }),
  };
}

function mutationHeaders(cookies: string, csrf: string): Record<string, string> {
  return {
    origin: ORIGIN,
    "sec-fetch-site": "same-origin",
    "x-hermes-csrf": csrf,
    cookie: cookies,
    "idempotency-key": IDEMPOTENCY_KEY,
  };
}

async function call(
  controller: AccountHttpController,
  method: string,
  path: string,
  headers: Record<string, string> = {},
  body?: Record<string, unknown>,
): Promise<MemoryResponse> {
  const response = new MemoryResponse();
  await controller.handle(
    memoryRequest(method, {
      ...(body ? { "content-type": "application/json" } : {}),
      ...headers,
    }, body ? JSON.stringify(body) : ""),
    response.asServerResponse(),
    new URL(`https://accounts.example.test${path}`),
  );
  return response;
}

function memoryRequest(method: string, headers: Record<string, string>, body: string): IncomingMessage {
  return {
    method,
    headers,
    socket: { remoteAddress: "127.0.0.1" },
    async *[Symbol.asyncIterator]() {
      if (body) yield Buffer.from(body);
    },
  } as unknown as IncomingMessage;
}

function requestCookies(cookies: string[]): string {
  return cookies.map((value) => value.split(";", 1)[0]).join("; ");
}

function cookieLine(cookies: string[], name: string): string {
  const line = cookies.find((value) => value.startsWith(`${name}=`));
  assert(line, `missing cookie ${name}`);
  return line;
}

function cookieValue(cookies: string[], name: string): string {
  return cookieLine(cookies, name).split(";", 1)[0].slice(name.length + 1);
}

class MemoryResponse {
  status = 0;
  headers: Record<string, string | string[]> = {};
  body = "";
  writableEnded = false;

  asServerResponse(): ServerResponse {
    return this as unknown as ServerResponse;
  }

  writeHead(status: number, headers: Record<string, string | string[]>): this {
    this.status = status;
    this.headers = headers;
    return this;
  }

  end(value?: string): this {
    this.body = value ?? "";
    this.writableEnded = true;
    return this;
  }

  json(): unknown {
    return JSON.parse(this.body);
  }

  cookies(): string[] {
    const value = this.headers["set-cookie"];
    return Array.isArray(value) ? value : value ? [value] : [];
  }
}
