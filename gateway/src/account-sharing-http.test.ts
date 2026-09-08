import assert from "node:assert/strict";
import type { IncomingMessage, ServerResponse } from "node:http";
import { test } from "node:test";
import { AccountHttpController } from "./account/account-http-controller.js";
import type { AccountService } from "./account/account-service.js";
import type { AccountSharingService } from "./account/account-sharing-service.js";
import type { AccountPrincipal } from "./account/model.js";

const IDEMPOTENCY_KEY = "9e0a2044-94fc-44d0-a81c-498ea343d085";
const INVITATION_ID = "7fdf6591-bf2d-49c8-9694-21f0ad71c9ea";
const GRANT_ID = "58963e51-4a75-4a9d-b18d-dc7e3610c0d5";
const TOKEN = `hsi_${"s".repeat(43)}`;

test("sharing HTTP surface remains independently default-off", async () => {
  const response = await call(
    new AccountHttpController(true, {} as AccountService),
    "GET",
    "/v2/devices/hermes-office/shares",
  );
  assert.equal(response.status, 503);
  assert.equal((response.json() as { error: { code: string } }).error.code, "HR-SHARE-001");
});

test("sharing HTTP routes preserve bounded owner and grantee contracts", async () => {
  const calls: Array<{ operation: string; values: unknown[] }> = [];
  const principal = testPrincipal();
  const invitation = {
    id: INVITATION_ID,
    deviceId: "hermes-office",
    targetEmailHint: "g***@example.com",
    status: "pending" as const,
    expiresAt: "2026-09-10T00:00:00.000Z",
    createdAt: "2026-09-07T00:00:00.000Z",
  };
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
    access: "operator" as const,
    isDefault: false,
  };
  const service = {
    authenticate: async () => principal,
  } as unknown as AccountService;
  const sharing = {
    listShares: async (...values: unknown[]) => {
      calls.push({ operation: "list", values });
      return { invitations: [invitation], grants: [], maxGranteesPerDevice: 5 };
    },
    createInvitation: async (...values: unknown[]) => {
      calls.push({ operation: "create", values });
      return invitation;
    },
    cancelInvitation: async (...values: unknown[]) => { calls.push({ operation: "cancel", values }); },
    revokeGrant: async (...values: unknown[]) => { calls.push({ operation: "revoke", values }); },
    acceptInvitation: async (...values: unknown[]) => {
      calls.push({ operation: "accept", values });
      return device;
    },
    leaveDevice: async (...values: unknown[]) => { calls.push({ operation: "leave", values }); },
  } as unknown as AccountSharingService;
  const controller = new AccountHttpController(true, service, {
    sharingEnabled: true,
    sharingService: sharing,
  });

  const listed = await call(controller, "GET", "/v2/devices/hermes-office/shares");
  assert.equal(listed.status, 200);
  assert.equal(JSON.stringify(listed.json()).includes(TOKEN), false);

  const created = await call(controller, "POST", "/v2/devices/hermes-office/share-invitations", {
    email: "guest@example.com",
    grant: `hgg_${"g".repeat(43)}`,
    acknowledgedWholeDeviceAccess: true,
  });
  assert.equal(created.status, 202);
  assert.deepEqual(created.json(), { invitation });
  assert.deepEqual((calls.at(-1)?.values[1] as Record<string, unknown>), {
    deviceId: "hermes-office",
    email: "guest@example.com",
    grant: `hgg_${"g".repeat(43)}`,
    acknowledgedWholeDeviceAccess: true,
    idempotencyKey: IDEMPOTENCY_KEY,
  });

  assert.equal((await call(
    controller,
    "DELETE",
    `/v2/devices/hermes-office/share-invitations/${INVITATION_ID}`,
  )).status, 204);
  assert.equal((await call(
    controller,
    "DELETE",
    `/v2/devices/hermes-office/shares/${GRANT_ID}`,
  )).status, 204);
  const accepted = await call(controller, "POST", `/v2/share-invitations/${TOKEN}/accept`, {
    acknowledgedWholeDeviceAccess: true,
  });
  assert.equal(accepted.status, 200);
  assert.deepEqual(accepted.json(), { device });
  assert.equal((await call(controller, "POST", "/v2/devices/hermes-office/leave", {})).status, 204);
  assert.deepEqual(calls.map(({ operation }) => operation), [
    "list", "create", "cancel", "revoke", "accept", "leave",
  ]);
});

test("sharing HTTP rejects a missing disclosure acknowledgement before mutation", async () => {
  const controller = new AccountHttpController(true, {
    authenticate: async () => testPrincipal(),
  } as unknown as AccountService, {
    sharingEnabled: true,
    sharingService: {} as AccountSharingService,
  });
  const response = await call(
    controller,
    "POST",
    "/v2/devices/hermes-office/share-invitations",
    { email: "guest@example.com", grant: `hgg_${"g".repeat(43)}` },
  );
  assert.equal(response.status, 400);
  assert.equal((response.json() as { error: { code: string } }).error.code, "HR-ACCOUNT-004");
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
      ...(body !== undefined ? { "content-type": "application/json" } : {}),
      ...(method !== "GET" ? { "idempotency-key": IDEMPOTENCY_KEY } : {}),
    }, body === undefined ? "" : JSON.stringify(body)),
    response.asServerResponse(),
    new URL(`http://localhost${path}`),
  );
  return response;
}

function testPrincipal(): AccountPrincipal {
  return {
    account: { id: "account-owner", email: "owner@example.com" },
    installation: {
      id: "fdaed25e-f143-4e3c-b92b-0d881df13630",
      kind: "desktop",
      platform: "macos",
      displayName: "Mac mini",
    },
    sessionId: "session-owner",
    refreshFamilyId: "family-owner",
  };
}

function memoryRequest(
  method: string,
  headers: Record<string, string>,
  body: string,
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
