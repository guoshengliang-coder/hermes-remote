import assert from "node:assert/strict";
import type { IncomingMessage, ServerResponse } from "node:http";
import { test } from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import type { AccountService } from "./account/account-service.js";
import type { EmailOtpService } from "./account/email-otp-service.js";
import type { AccountPrincipal, PublicExternalIdentity, VerifiedExternalIdentity } from "./account/model.js";

const CHALLENGE_ID = "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea";
const IDEMPOTENCY_KEY = "9e0a2044-94fc-44d0-a81c-498ea343d085";

test("identity management and Web account shell remain independently default-off", async () => {
  const controller = new AccountHttpController(true, {} as AccountService);
  const identities = new MemoryResponse();
  await controller.handle(
    memoryRequest("GET", { authorization: "Bearer ignored" }),
    identities.asServerResponse(),
    new URL("http://localhost/v2/account/identities"),
  );
  assert.equal(identities.status, 503);
  assert.equal((identities.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-009");

  const phoneRevocation = new MemoryResponse();
  await controller.handle(
    memoryRequest("DELETE", {
      authorization: "Bearer ignored",
      "content-type": "application/json",
      "idempotency-key": IDEMPOTENCY_KEY,
    }, JSON.stringify({ grant: `hgg_${"g".repeat(43)}` })),
    phoneRevocation.asServerResponse(),
    new URL("http://localhost/v2/installations/879d7035-9ba5-456f-979a-98ab28ae89ec"),
  );
  assert.equal(phoneRevocation.status, 503);
  assert.equal(
    (phoneRevocation.json() as { error: { code: string } }).error.code,
    "HR-ACCOUNT-009",
  );

  const shell = new MemoryResponse();
  await controller.handle(
    memoryRequest("GET"),
    shell.asServerResponse(),
    new URL("http://localhost/account"),
  );
  assert.equal(shell.status, 404);
});

test("Web account shell loads only same-origin assets, remains uncacheable, and is frame-denied", async () => {
  const response = new MemoryResponse();
  await new AccountHttpController(true, {} as AccountService, {
    googleAuthEnabled: true,
    identityManagementEnabled: true,
    webAccountCenterEnabled: true,
  }).handle(
    memoryRequest("GET"),
    response.asServerResponse(),
    new URL("http://localhost/account"),
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers["cache-control"], "no-store");
  assert.equal(response.headers["x-frame-options"], "DENY");
  assert.match(response.headers["content-security-policy"], /default-src 'none'/);
  assert.match(response.headers["content-security-policy"], /script-src 'self' https:\/\/accounts\.google\.com\/gsi\/client/);
  assert.match(response.headers["content-security-policy"], /connect-src 'self' https:\/\/accounts\.google\.com\/gsi\//);
  assert.equal(response.headers["cross-origin-opener-policy"], "same-origin-allow-popups");
  assert.match(response.body, /Hermes GO 账号中心/);
  assert.match(response.body, /整台 Hermes/);
  assert.match(response.body, /<script src="\/account\/assets\/account\.js" defer><\/script>/);
  assert.match(response.body, /<form/i);
  assert.doesNotMatch(response.body, /<script(?![^>]+src=)/i);
});

test("identity HTTP flow separates reauthentication proof from the newly linked identity", async () => {
  const calls: Array<{ operation: string; input?: unknown }> = [];
  const principal = testPrincipal();
  const existingEmail: PublicExternalIdentity = {
    id: "17d90b36-9946-4f2f-8ba2-abf8ca64f01e",
    provider: "email_otp",
    email: "owner@example.com",
    verifiedAt: "2026-09-07T00:00:00.000Z",
  };
  const verifiedTarget: VerifiedExternalIdentity = {
    provider: "email_otp",
    issuer: "https://mrlgs.net",
    subject: "f".repeat(64),
    email: "new@example.com",
  };
  const linkedTarget: PublicExternalIdentity = {
    id: "58963e51-4a75-4a9d-b18d-dc7e3610c0d5",
    provider: "email_otp",
    email: "new@example.com",
    verifiedAt: "2026-09-07T00:02:00.000Z",
  };
  const service = {
    authenticate: async () => principal,
    listExternalIdentities: async () => [existingEmail],
    reauthenticateEmailIdentity: async (_principal: AccountPrincipal, _identity: VerifiedExternalIdentity, input: unknown) => {
      calls.push({ operation: "reauthenticate", input });
      return {
        grant: `hgg_${"g".repeat(43)}`,
        scope: "account.identity.link",
        expiresAt: "2026-09-07T00:10:00.000Z",
      };
    },
    linkEmailIdentity: async (_principal: AccountPrincipal, _identity: VerifiedExternalIdentity, input: unknown) => {
      calls.push({ operation: "link_email", input });
      return linkedTarget;
    },
    linkGoogleIdentity: async (_principal: AccountPrincipal, input: unknown) => {
      calls.push({ operation: "link_google", input });
      return { ...linkedTarget, provider: "google" as const };
    },
    unlinkIdentity: async (_principal: AccountPrincipal, input: unknown) => {
      calls.push({ operation: "unlink", input });
      return { identity: linkedTarget, currentSessionRevoked: false };
    },
  } as unknown as AccountService;
  const emailOtp = {
    requestChallenge: async (input: unknown) => {
      calls.push({ operation: "challenge", input });
      return {
        challengeId: CHALLENGE_ID,
        expiresAt: "2026-09-07T00:10:00.000Z",
        resendAfter: "2026-09-07T00:01:00.000Z",
      };
    },
    verifyChallenge: async (input: unknown) => {
      calls.push({ operation: "verify", input });
      return verifiedTarget;
    },
  } as EmailOtpService;
  const controller = new AccountHttpController(true, service, {
    googleAuthEnabled: true,
    identityManagementEnabled: true,
    emailOtpEnabled: true,
    emailOtpService: emailOtp,
  });

  const list = await call(controller, "GET", "/v2/account/identities");
  assert.equal(list.status, 200);
  assert.deepEqual(list.json(), { items: [existingEmail] });

  const reauthChallenge = await call(controller, "POST", "/v2/auth/reauth/email/challenges", {
    email: "Owner@Example.com",
  });
  assert.equal(reauthChallenge.status, 202);
  assert.deepEqual(calls.at(-1), {
    operation: "challenge",
    input: {
      email: "owner@example.com",
      purpose: "reauthenticate",
      platform: "macos",
      source: "127.0.0.1",
      clientInstallationId: principal.installation.id,
    },
  });

  const reauth = await call(controller, "POST", "/v2/auth/reauth/email", {
    challengeId: CHALLENGE_ID,
    email: "owner@example.com",
    code: "123456",
    scope: "account.identity.link",
  });
  assert.equal(reauth.status, 200);
  assert.equal(calls.at(-1)?.operation, "reauthenticate");

  const linkChallenge = await call(controller, "POST", "/v2/account/identities/email/challenges", {
    email: "new@example.com",
  });
  assert.equal(linkChallenge.status, 202);
  assert.equal((calls.at(-1)?.input as { purpose: string }).purpose, "link_identity");

  const linkEmail = await call(controller, "POST", "/v2/account/identities/email", {
    challengeId: CHALLENGE_ID,
    email: "new@example.com",
    code: "654321",
    grant: `hgg_${"g".repeat(43)}`,
  });
  assert.equal(linkEmail.status, 200);
  assert.deepEqual(linkEmail.json(), { identity: linkedTarget });
  assert.equal(calls.at(-1)?.operation, "link_email");

  const linkGoogle = await call(controller, "POST", "/v2/account/identities/google", {
    idToken: "fresh-google-token",
    nonce: "1234567890abcdef",
    grant: `hgg_${"g".repeat(43)}`,
  });
  assert.equal(linkGoogle.status, 200);
  assert.equal(calls.at(-1)?.operation, "link_google");

  const unlink = await call(
    controller,
    "DELETE",
    `/v2/account/identities/${linkedTarget.id}`,
    { grant: `hgg_${"g".repeat(43)}` },
  );
  assert.equal(unlink.status, 200);
  assert.deepEqual(unlink.json(), { identity: linkedTarget, currentSessionRevoked: false });
  assert.deepEqual(calls.at(-1), {
    operation: "unlink",
    input: {
      identityId: linkedTarget.id,
      grant: `hgg_${"g".repeat(43)}`,
      idempotencyKey: IDEMPOTENCY_KEY,
    },
  });
});

async function call(
  controller: AccountHttpController,
  method: string,
  path: string,
  body?: Record<string, unknown>,
): Promise<MemoryResponse> {
  const response = new MemoryResponse();
  await controller.handle(
    memoryRequest(method, {
      authorization: "Bearer test-access-token",
      ...(body ? { "content-type": "application/json" } : {}),
      ...(method === "POST" || method === "DELETE"
        ? { "idempotency-key": IDEMPOTENCY_KEY }
        : {}),
    }, body ? JSON.stringify(body) : ""),
    response.asServerResponse(),
    new URL(`http://localhost${path}`),
  );
  return response;
}

function testPrincipal(): AccountPrincipal {
  return {
    account: { id: "account-1", email: "owner@example.com" },
    installation: {
      id: "fdaed25e-f143-4e3c-b92b-0d881df13630",
      kind: "desktop",
      platform: "macos",
      displayName: "Mac mini",
    },
    sessionId: "session-1",
    refreshFamilyId: "family-1",
  };
}

function memoryRequest(
  method: string,
  headers: Record<string, string> = {},
  body = "",
): IncomingMessage {
  return {
    method,
    headers,
    socket: { remoteAddress: "127.0.0.1" },
    async *[Symbol.asyncIterator]() {
      if (body.length > 0) yield Buffer.from(body);
    },
  } as unknown as IncomingMessage;
}

class MemoryResponse {
  status = 0;
  headers: Record<string, string> = {};
  body = "";
  writableEnded = false;

  asServerResponse(): ServerResponse {
    return this as unknown as ServerResponse;
  }

  writeHead(status: number, headers: Record<string, string>): this {
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
}
