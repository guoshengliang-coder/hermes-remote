import assert from "node:assert/strict";
import { randomBytes, randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { AccountControlService } from "./account/account-control-service.js";
import { AccountService, type AccountSessionResponse } from "./account/account-service.js";
import { AccountSharingService } from "./account/account-sharing-service.js";
import type { AccountPlatform, AccountPrincipal, VerifiedExternalIdentity } from "./account/model.js";
import { PostgresAccountControlRepository } from "./account/postgres-account-control-repository.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { PostgresAccountSharingRepository } from "./account/postgres-account-sharing-repository.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL enforces whole-device sharing privacy, 5/10 limits, and revocation", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `device_sharing_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  let accountRepository: PostgresAccountRepository | undefined;
  try {
    const setup = new Pool({ connectionString: databaseUrl, max: 1, options: `-c search_path=${schema}` });
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await setup.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    await setup.end();

    const pool = new Pool({ connectionString: databaseUrl, max: 24, options: `-c search_path=${schema}` });
    const tokens = new TokenCodec("device-sharing-test-key-with-at-least-thirty-two-bytes");
    accountRepository = new PostgresAccountRepository(pool, tokens);
    let verifiedIdentity: VerifiedExternalIdentity = googleIdentity("owner@example.invalid");
    const accounts = new AccountService({ verify: async () => verifiedIdentity }, accountRepository, tokens);
    const sharingRepository = new PostgresAccountSharingRepository(pool);
    const sentTokens = new Map<string, string>();
    const sharing = new AccountSharingService(
      sharingRepository,
      tokens,
      {
        sendDeviceShareInvitation: async ({ messageId, recipient, acceptUrl }) => {
          sentTokens.set(recipient, new URL(acceptUrl).hash.slice("#share-invitation=".length));
          return { providerMessageId: messageId };
        },
      },
      (principal) => accounts.listExternalIdentities(principal),
      "https://accounts.example.invalid",
    );
    const control = new AccountControlService(
      new PostgresAccountControlRepository(pool, 10_000, 3),
      tokens,
      () => new Date(),
      3,
      sharingRepository,
    );

    const ownerSession = await signIn(accounts, verifiedIdentity, "macos", "Owner Desktop");
    const owner = await principal(accounts, ownerSession);
    const ownerBinding = await seedBinding(pool, owner.account.id, owner.installation.id, "owner-device");

    const grantees: Array<{ email: string; principal: AccountPrincipal }> = [];
    for (let index = 0; index < 6; index += 1) {
      const email = `${String.fromCharCode(97 + index)}-grantee@example.invalid`;
      verifiedIdentity = googleIdentity(email);
      const session = await signIn(accounts, verifiedIdentity, "android", `Grantee ${index}`);
      grantees.push({ email, principal: await principal(accounts, session) });
    }

    verifiedIdentity = googleIdentity("owner@example.invalid");
    const deletingTargetEmail = "deleting-target@example.invalid";
    const deletingTargetHash = tokens.hashContext(
      `device-share-email-v1\u0000${deletingTargetEmail}`,
    );
    await pool.query(
      `INSERT INTO account_deletion_email_hashes (account_id, hash_kind, email_lookup_hash)
       VALUES ($1, 'device_share', $2)`,
      [grantees[0].principal.account.id, deletingTargetHash],
    );
    const blockedGrant = await accounts.reauthenticateGoogle(owner, {
      idToken: "fresh-owner-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "device.share",
      idempotencyKey: randomUUID(),
    });
    await assert.rejects(
      sharing.createInvitation(owner, {
        deviceId: ownerBinding.deviceId,
        email: deletingTargetEmail,
        grant: blockedGrant.grant,
        acknowledgedWholeDeviceAccess: true,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-SHARE-007",
    );
    assert.equal((await pool.query(
      "SELECT 1 FROM device_share_invitations WHERE trim(target_email_lookup_hash) = $1",
      [deletingTargetHash],
    )).rowCount, 0);

    for (const { email } of grantees) {
      const recent = await accounts.reauthenticateGoogle(owner, {
        idToken: "fresh-owner-proof-not-persisted",
        nonce: randomBytes(16).toString("hex"),
        scope: "device.share",
        idempotencyKey: randomUUID(),
      });
      await sharing.createInvitation(owner, {
        deviceId: ownerBinding.deviceId,
        email,
        grant: recent.grant,
        acknowledgedWholeDeviceAccess: true,
        idempotencyKey: randomUUID(),
      });
    }

    const persistedInvitation = await pool.query(
      "SELECT target_email_lookup_hash, token_hash, target_email_hint FROM device_share_invitations LIMIT 1",
    );
    const persistedJson = JSON.stringify(persistedInvitation.rows[0]);
    assert.equal(persistedJson.includes("-grantee@example.invalid"), false);
    assert.equal(persistedJson.includes("hsi_"), false);
    assert.match(persistedInvitation.rows[0].target_email_lookup_hash, /^[a-f0-9]{64}$/);
    assert.match(persistedInvitation.rows[0].token_hash, /^[a-f0-9]{64}$/);

    const accepts = await Promise.allSettled(grantees.map(({ email, principal: grantee }) => (
      sharing.acceptInvitation(
        grantee,
        requiredToken(sentTokens, email),
        true,
        randomUUID(),
      )
    )));
    assert.equal(accepts.filter(({ status }) => status === "fulfilled").length, 5);
    const sixth = accepts.find(({ status }) => status === "rejected");
    assert(sixth?.status === "rejected");
    assert.equal(errorCode(sixth.reason), "HR-SHARE-002");
    assert.equal((await pool.query(
      "SELECT COUNT(*)::integer AS count FROM device_access_grants WHERE binding_id = $1 AND status = 'active'",
      [ownerBinding.bindingId],
    )).rows[0].count, 5);

    const acceptedIndex = accepts.findIndex(({ status }) => status === "fulfilled");
    const acceptedGrantee = grantees[acceptedIndex].principal;
    const accessible = await control.listDevices(acceptedGrantee);
    assert.equal(accessible.length, 1);
    assert.equal(accessible[0].access, "operator");
    assert.equal((await control.getDevice(acceptedGrantee, ownerBinding.deviceId)).id, ownerBinding.bindingId);
    await assert.rejects(
      sharing.listShares(acceptedGrantee, ownerBinding.deviceId),
      (error: unknown) => errorCode(error) === "HR-BIND-011",
    );
    const selected = await control.selectDefaultDevice(
      acceptedGrantee,
      ownerBinding.deviceId,
      randomUUID(),
    );
    assert.equal(selected.access, "operator");
    assert.equal(selected.isDefault, true);

    const ownerShares = await sharing.listShares(owner, ownerBinding.deviceId);
    const activeGrant = ownerShares.grants.find(({ granteeEmailHint }) => (
      granteeEmailHint === `${grantees[acceptedIndex].email[0]}***@example.invalid`
    ));
    assert(activeGrant);
    await sharing.revokeGrant(owner, ownerBinding.deviceId, activeGrant.id, randomUUID());
    await assert.rejects(
      control.getDevice(acceptedGrantee, ownerBinding.deviceId),
      (error: unknown) => errorCode(error) === "HR-BIND-011",
    );
    assert.equal((await pool.query(
      "SELECT 1 FROM account_device_preferences WHERE account_id = $1",
      [acceptedGrantee.account.id],
    )).rowCount, 0);

    await assert.rejects(
      pool.query(
        `INSERT INTO device_access_grants
           (id, binding_id, owner_account_id, grantee_account_id, grantee_email_hint)
         VALUES ($1, $2, $3, $3, 's***@example.invalid')`,
        [randomUUID(), ownerBinding.bindingId, owner.account.id],
      ),
    );

    const remainingGrant = (await pool.query<{ id: string }>(
      "SELECT id FROM device_access_grants WHERE binding_id = $1 AND status = 'active' LIMIT 1",
      [ownerBinding.bindingId],
    )).rows[0];
    assert(remainingGrant);
    await assert.rejects(
      pool.query(
        "UPDATE device_access_grants SET owner_account_id = grantee_account_id WHERE id = $1",
        [remainingGrant.id],
      ),
      /active device grant identity is immutable/,
    );

    const capacityIdentity = googleIdentity("capacity-target@example.invalid");
    verifiedIdentity = capacityIdentity;
    const capacitySession = await signIn(accounts, capacityIdentity, "android", "Capacity Target");
    const capacityPrincipal = await principal(accounts, capacitySession);
    const capacityHash = tokens.hashContext("device-share-email-v1\u0000capacity-target@example.invalid");
    for (let index = 0; index < 9; index += 1) {
      const seeded = await seedIndependentBinding(pool, index);
      await pool.query(
        `INSERT INTO device_access_grants
           (id, binding_id, owner_account_id, grantee_account_id, grantee_email_hint)
         VALUES ($1, $2, $3, $4, 'c***@example.invalid')`,
        [randomUUID(), seeded.bindingId, seeded.ownerAccountId, capacityPrincipal.account.id],
      );
    }
    const candidates = await Promise.all([90, 91].map(async (index) => {
      const seeded = await seedIndependentBinding(pool, index);
      const invitationId = randomUUID();
      const token = tokens.issueShareInvitationToken(invitationId);
      await pool.query(
        `INSERT INTO device_share_invitations
           (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
            token_hash, delivery_status, delivered_at, expires_at)
         VALUES ($1, $2, $3, $4, 'c***@example.invalid', $5, 'sent', now(), now() + interval '72 hours')`,
        [
          invitationId,
          seeded.bindingId,
          seeded.ownerAccountId,
          capacityHash,
          tokens.hashShareInvitationToken(token),
        ],
      );
      return token;
    }));
    const capacityResults = await Promise.allSettled(candidates.map((token) => (
      sharing.acceptInvitation(capacityPrincipal, token, true, randomUUID())
    )));
    assert.equal(capacityResults.filter(({ status }) => status === "fulfilled").length, 1);
    const eleventh = capacityResults.find(({ status }) => status === "rejected");
    assert(eleventh?.status === "rejected");
    assert.equal(errorCode(eleventh.reason), "HR-SHARE-003");
    assert.equal((await pool.query(
      "SELECT COUNT(*)::integer AS count FROM device_access_grants WHERE grantee_account_id = $1 AND status = 'active'",
      [capacityPrincipal.account.id],
    )).rows[0].count, 10);
  } finally {
    await accountRepository?.close().catch(() => {});
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

async function signIn(
  service: AccountService,
  identity: VerifiedExternalIdentity,
  platform: AccountPlatform,
  displayName: string,
): Promise<AccountSessionResponse> {
  return service.exchangeGoogleProof({
    platform,
    idToken: `proof-${identity.subject}`,
    nonce: randomBytes(16).toString("hex"),
    clientInstallationId: randomUUID(),
    displayName,
    appVersion: "0.0.0-test",
    idempotencyKey: randomUUID(),
  });
}

async function principal(
  service: AccountService,
  session: AccountSessionResponse,
): Promise<AccountPrincipal> {
  return service.authenticate(`Bearer ${session.session.accessToken}`);
}

function googleIdentity(email: string): VerifiedExternalIdentity {
  return {
    provider: "google",
    issuer: "https://accounts.google.com",
    subject: `subject-${email}`,
    email,
    displayName: email.split("@", 1)[0],
  };
}

async function seedBinding(
  pool: Pool,
  accountId: string,
  installationId: string,
  deviceId: string,
): Promise<{ bindingId: string; deviceId: string }> {
  const bindingId = randomUUID();
  await pool.query(
    `INSERT INTO connector_bindings
       (id, account_id, desktop_installation_id, display_name, device_id, public_key,
        key_algorithm, public_key_fingerprint, generation, status, activated_at)
     VALUES ($1, $2, $3, $4, $5, $6, 'Ed25519', $7, 1, 'active', now())`,
    [bindingId, accountId, installationId, `Mac ${deviceId}`, deviceId, Buffer.alloc(32, 7), "7".repeat(64)],
  );
  return { bindingId, deviceId };
}

async function seedIndependentBinding(
  pool: Pool,
  index: number,
): Promise<{ bindingId: string; ownerAccountId: string }> {
  const ownerAccountId = randomUUID();
  const installationId = randomUUID();
  await pool.query("INSERT INTO accounts (id) VALUES ($1)", [ownerAccountId]);
  await pool.query(
    `INSERT INTO installations
       (id, account_id, client_installation_id, kind, platform, display_name, app_version)
     VALUES ($1, $2, $3, 'desktop', 'macos', $4, 'test')`,
    [installationId, ownerAccountId, randomUUID(), `Seed owner ${index}`],
  );
  const { bindingId } = await seedBinding(pool, ownerAccountId, installationId, `seed-device-${index}`);
  return { bindingId, ownerAccountId };
}

function requiredToken(tokens: Map<string, string>, email: string): string {
  const token = tokens.get(email);
  assert(token);
  return token;
}

function errorCode(error: unknown): unknown {
  return typeof error === "object" && error !== null && "code" in error
    ? (error as { code: unknown }).code
    : undefined;
}
