import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import type { AccountDevice } from "./account/account-control-model.js";
import type {
  AccountSharingRepository,
  DeviceShareEmailSender,
} from "./account/account-sharing-model.js";
import { AccountSharingService } from "./account/account-sharing-service.js";
import type { AccountPrincipal, PublicExternalIdentity } from "./account/model.js";
import { TokenCodec } from "./account/token-codec.js";

const HASH_KEY = "account-sharing-test-key-that-is-at-least-thirty-two-bytes";
const NOW = new Date("2026-09-07T12:00:00.000Z");
const PRINCIPAL: AccountPrincipal = {
  account: { id: "10000000-0000-4000-8000-000000000001", displayName: "Owner" },
  installation: {
    id: "20000000-0000-4000-8000-000000000001",
    kind: "desktop",
    platform: "macos",
    displayName: "Owner Desktop",
  },
  sessionId: "30000000-0000-4000-8000-000000000001",
  refreshFamilyId: "40000000-0000-4000-8000-000000000001",
};

test("sharing invitation stores keyed lookups only and mails an expiring whole-device link", async () => {
  const tokens = new TokenCodec(HASH_KEY);
  let persisted: Parameters<AccountSharingRepository["createInvitation"]>[1] | undefined;
  let delivered: Parameters<DeviceShareEmailSender["sendDeviceShareInvitation"]>[0] | undefined;
  const service = sharingService({
    tokens,
    repository: fakeRepository({
      createInvitation: async (_principal, input) => {
        persisted = input;
        return {
          status: "created",
          invitation: {
            id: input.invitationId,
            deviceId: input.deviceId,
            targetEmailHint: input.targetEmailHint,
            status: "pending",
            createdAt: NOW.toISOString(),
            expiresAt: input.expiresAt.toISOString(),
          },
          deviceDisplayName: "Office Mac",
          needsDelivery: true,
        };
      },
      markInvitationDelivery: async () => true,
    }),
    sender: { sendDeviceShareInvitation: async (input) => {
      delivered = input;
      return { providerMessageId: "share-provider-id" };
    } },
    identities: ownerIdentities(),
  });
  const invitation = await service.createInvitation(PRINCIPAL, {
    deviceId: "hermes-office",
    email: " Guest@Example.COM ",
    grant: tokens.issueReauthenticationGrant(),
    acknowledgedWholeDeviceAccess: true,
    idempotencyKey: randomUUID(),
  });

  assert.equal(invitation.targetEmailHint, "g***@example.com");
  assert.match(persisted?.targetEmailLookupHash ?? "", /^[a-f0-9]{64}$/);
  assert.match(persisted?.tokenHash ?? "", /^[a-f0-9]{64}$/);
  assert.equal(JSON.stringify(persisted).includes("guest@example.com"), false);
  assert.equal(delivered?.recipient, "guest@example.com");
  assert.equal(delivered?.expiresInHours, 72);
  assert.match(delivered?.acceptUrl ?? "", /^https:\/\/accounts\.example\.com\/account#share-invitation=hsi_/);
  assert.equal(delivered?.acceptUrl.includes("guest@example.com"), false);
  assert.equal(delivered?.deviceDisplayName, "Office Mac");
});

test("sharing requires explicit whole-device acknowledgement and rejects self-invites", async () => {
  const service = sharingService({ identities: ownerIdentities() });
  await assert.rejects(
    service.createInvitation(PRINCIPAL, {
      deviceId: "hermes-office",
      email: "guest@example.com",
      grant: new TokenCodec(HASH_KEY).issueReauthenticationGrant(),
      acknowledgedWholeDeviceAccess: false,
      idempotencyKey: randomUUID(),
    }),
    errorCode("HR-SHARE-006"),
  );
  await assert.rejects(
    service.createInvitation(PRINCIPAL, {
      deviceId: "hermes-office",
      email: "OWNER@example.com",
      grant: new TokenCodec(HASH_KEY).issueReauthenticationGrant(),
      acknowledgedWholeDeviceAccess: true,
      idempotencyKey: randomUUID(),
    }),
    errorCode("HR-SHARE-007"),
  );
});

test("invitation acceptance binds the token to one of the grantee's verified mailboxes", async () => {
  const tokens = new TokenCodec(HASH_KEY);
  const token = tokens.issueShareInvitationToken("50000000-0000-4000-8000-000000000001");
  let receivedHashes: string[] = [];
  const device = accountDevice("operator");
  const service = sharingService({
    tokens,
    identities: [
      { id: randomUUID(), provider: "google", email: "Grantee@Example.com", verifiedAt: NOW.toISOString() },
      { id: randomUUID(), provider: "email_otp", email: "second@example.com", verifiedAt: NOW.toISOString() },
    ],
    repository: fakeRepository({
      acceptInvitation: async (_principal, tokenHash, hashes) => {
        assert.equal(tokenHash, tokens.hashShareInvitationToken(token));
        receivedHashes = hashes;
        return { status: "accepted", device };
      },
    }),
  });
  assert.equal(await service.acceptInvitation(PRINCIPAL, token, true, randomUUID()), device);
  assert.equal(receivedHashes.length, 2);
  assert(receivedHashes.every((hash) => /^[a-f0-9]{64}$/.test(hash)));
});

test("sharing capacity outcomes use distinct stable errors", async () => {
  const tokens = new TokenCodec(HASH_KEY);
  const token = tokens.issueShareInvitationToken(randomUUID());
  for (const [status, code] of [
    ["device_capacity_reached", "HR-SHARE-002"],
    ["account_capacity_reached", "HR-SHARE-003"],
    ["email_mismatch", "HR-SHARE-005"],
    ["not_found", "HR-SHARE-004"],
  ] as const) {
    const service = sharingService({
      identities: ownerIdentities(),
      repository: fakeRepository({ acceptInvitation: async () => ({ status }) }),
    });
    await assert.rejects(
      service.acceptInvitation(PRINCIPAL, token, true, randomUUID()),
      errorCode(code),
    );
  }
});

test("grant revoke and grantee leave publish exact account-binding invalidations once", async () => {
  const revoked = {
    bindingId: "50000000-0000-4000-8000-000000000001",
    deviceId: "hermes-office",
    granteeAccountId: "60000000-0000-4000-8000-000000000001",
    authorizationGeneration: 2,
  };
  const service = sharingService({
    repository: fakeRepository({
      revokeGrant: async () => ({ status: "completed", revokedAccess: revoked }),
      leaveDevice: async () => ({ status: "completed", revokedAccess: { ...revoked, authorizationGeneration: 3 } }),
    }),
  });
  const events: typeof revoked[] = [];
  const unsubscribe = service.subscribeRevocations((event) => events.push(event));
  await service.revokeGrant(PRINCIPAL, "hermes-office", randomUUID(), randomUUID());
  await service.leaveDevice(PRINCIPAL, "hermes-office", randomUUID());
  unsubscribe();
  await service.leaveDevice(PRINCIPAL, "hermes-office", randomUUID());
  assert.deepEqual(events, [revoked, { ...revoked, authorizationGeneration: 3 }]);
});

function sharingService(options: {
  tokens?: TokenCodec;
  repository?: AccountSharingRepository;
  sender?: DeviceShareEmailSender;
  identities?: PublicExternalIdentity[];
} = {}): AccountSharingService {
  return new AccountSharingService(
    options.repository ?? fakeRepository(),
    options.tokens ?? new TokenCodec(HASH_KEY),
    options.sender ?? {
      sendDeviceShareInvitation: async () => ({ providerMessageId: "share-provider-id" }),
    },
    async () => options.identities ?? [],
    "https://accounts.example.com",
    () => NOW,
  );
}

function fakeRepository(
  overrides: Partial<AccountSharingRepository> = {},
): AccountSharingRepository {
  return {
    listSharedDevices: async () => [],
    getSharedDevice: async () => undefined,
    selectAccessibleDefaultDevice: async () => ({ status: "not_found" }),
    listShares: async () => ({ invitations: [], grants: [], maxGranteesPerDevice: 5 }),
    createInvitation: async () => ({ status: "not_found" }),
    markInvitationDelivery: async () => true,
    cancelInvitation: async () => ({ status: "not_found" }),
    acceptInvitation: async () => ({ status: "not_found" }),
    revokeGrant: async () => ({ status: "not_found" }),
    leaveDevice: async () => ({ status: "not_found" }),
    ...overrides,
  };
}

function ownerIdentities(): PublicExternalIdentity[] {
  return [{
    id: "70000000-0000-4000-8000-000000000001",
    provider: "google",
    email: "owner@example.com",
    verifiedAt: NOW.toISOString(),
  }];
}

function accountDevice(access: AccountDevice["access"]): AccountDevice {
  return {
    id: "50000000-0000-4000-8000-000000000001",
    generation: 1,
    deviceId: "hermes-office",
    desktopDisplayName: "Office Mac",
    publicKeyFingerprint: "f".repeat(64),
    connector: { online: true },
    hermes: { reachable: true },
    gateway: {},
    endToEnd: { healthy: true },
    access,
    isDefault: false,
  };
}

function errorCode(code: string): (error: unknown) => boolean {
  return (error) => typeof error === "object" && error !== null
    && "code" in error && (error as { code: unknown }).code === code;
}
