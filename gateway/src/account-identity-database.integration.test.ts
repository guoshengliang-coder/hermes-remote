import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { AccountService } from "./account/account-service.js";
import { EmailOtpSecurity } from "./account/email-otp.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { TokenCodec } from "./account/token-codec.js";
import type { VerifiedExternalIdentity } from "./account/model.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL identity linking and unlinking are explicit, conflict-safe, and race-serialized", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `identity_link_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  const pool = new Pool({
    connectionString: databaseUrl,
    max: 6,
    options: `-c search_path=${schema}`,
  });
  try {
    for (const migrationFile of (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort()) {
      await pool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }

    const tokens = new TokenCodec("identity-link-test-token-key-with-at-least-thirty-two-bytes");
    const repository = new PostgresAccountRepository(pool, tokens);
    const now = () => new Date();
    const googleA = googleIdentity("google-account-a", "owner-a@example.com");
    const googleB = googleIdentity("google-account-b", "owner-b@example.com");
    const serviceA = new AccountService({ verify: async () => googleA }, repository, tokens, now);
    const serviceB = new AccountService({ verify: async () => googleB }, repository, tokens, now);
    const sessionA = await serviceA.exchangeGoogleProof(sessionInput(randomUUID(), randomUUID()));
    const sessionB = await serviceB.exchangeGoogleProof(sessionInput(randomUUID(), randomUUID()));
    const principalA = await serviceA.authenticate(`Bearer ${sessionA.session.accessToken}`);
    const principalB = await serviceB.authenticate(`Bearer ${sessionB.session.accessToken}`);
    const grantA = await serviceA.reauthenticateGoogle(principalA, {
      idToken: "fresh-google-a",
      nonce: "1234567890abcdef",
      scope: "account.identity.link",
      idempotencyKey: randomUUID(),
    });
    const grantB = await serviceB.reauthenticateGoogle(principalB, {
      idToken: "fresh-google-b",
      nonce: "1234567890abcdef",
      scope: "account.identity.link",
      idempotencyKey: randomUUID(),
    });

    const target = new EmailOtpSecurity(
      "identity-link-test-email-key-with-at-least-thirty-two-bytes",
      "https://mrlgs.net",
    ).resolveIdentity("shared-target@example.com").identity;
    const keyA = randomUUID();
    const keyB = randomUUID();
    const outcomes = await Promise.allSettled([
      serviceA.linkEmailIdentity(principalA, target, { grant: grantA.grant, idempotencyKey: keyA }),
      serviceB.linkEmailIdentity(principalB, target, { grant: grantB.grant, idempotencyKey: keyB }),
    ]);
    assert.equal(outcomes.filter(({ status }) => status === "fulfilled").length, 1);
    assert.equal(outcomes.filter(({ status }) => status === "rejected").length, 1);
    const rejected = outcomes.find(({ status }) => status === "rejected");
    assert(rejected?.status === "rejected");
    assert.equal(errorCode(rejected.reason), "HR-ACCOUNT-008");

    const winner = outcomes[0].status === "fulfilled"
      ? { service: serviceA, principal: principalA, session: sessionA, grant: grantA, key: keyA }
      : { service: serviceB, principal: principalB, session: sessionB, grant: grantB, key: keyB };
    const fulfilled = outcomes.find(({ status }) => status === "fulfilled");
    assert(fulfilled?.status === "fulfilled");
    const linked = fulfilled.value;
    assert.equal(linked.email, "shared-target@example.com");
    assert.deepEqual(
      await winner.service.linkEmailIdentity(winner.principal, target, {
        grant: winner.grant.grant,
        idempotencyKey: winner.key,
      }),
      linked,
    );

    const identities = await winner.service.listExternalIdentities(winner.principal);
    assert.deepEqual(identities.map(({ provider }) => provider).sort(), ["email_otp", "google"]);
    assert.equal(identities.some(({ id }) => id === linked.id), true);
    const emailReauthentication = await winner.service.reauthenticateEmailIdentity(
      winner.principal,
      target,
      { scope: "account.identity.link", idempotencyKey: randomUUID() },
    );
    assert.match(emailReauthentication.grant, /^hgg_[A-Za-z0-9_-]{43}$/);

    const emailClientInstallationId = randomUUID();
    const emailSession = await winner.service.exchangeEmailIdentity(
      target,
      sessionInput(emailClientInstallationId, randomUUID()),
    );
    assert.equal(emailSession.account.id, winner.session.account.id);
    const webClientInstallationId = randomUUID();
    const webSession = await winner.service.exchangeEmailIdentity(target, {
      platform: "web",
      clientInstallationId: webClientInstallationId,
      displayName: "Test browser",
      appVersion: "0.4.0-test",
      idempotencyKey: randomUUID(),
    });
    assert.equal(webSession.installation.kind, "browser");
    assert.equal(webSession.installation.platform, "web");

    const unlinkGrant = await winner.service.reauthenticateGoogle(winner.principal, {
      idToken: "fresh-google-unlink",
      nonce: "1234567890abcdef",
      scope: "account.identity.unlink",
      idempotencyKey: randomUUID(),
    });
    const unlinkKey = randomUUID();
    const unlinked = await winner.service.unlinkIdentity(winner.principal, {
      identityId: linked.id,
      grant: unlinkGrant.grant,
      idempotencyKey: unlinkKey,
    });
    assert.equal(unlinked.identity.id, linked.id);
    assert.equal(unlinked.currentSessionRevoked, false);
    assert.deepEqual(await winner.service.unlinkIdentity(winner.principal, {
      identityId: linked.id,
      grant: unlinkGrant.grant,
      idempotencyKey: unlinkKey,
    }), unlinked);
    assert.deepEqual(
      (await winner.service.listExternalIdentities(winner.principal)).map(({ provider }) => provider),
      ["google"],
    );
    for (const [removedIdentitySession, clientInstallationId] of [
      [emailSession, emailClientInstallationId],
      [webSession, webClientInstallationId],
    ] as const) {
      await assert.rejects(
        winner.service.authenticate(`Bearer ${removedIdentitySession.session.accessToken}`),
        (error: unknown) => errorCode(error) === "HR-AUTH-004",
      );
      await assert.rejects(
        winner.service.refresh({
          refreshToken: removedIdentitySession.session.refreshToken,
          clientInstallationId,
          idempotencyKey: randomUUID(),
        }),
        (error: unknown) => errorCode(error) === "HR-AUTH-004",
      );
    }

    const lastIdentity = (await winner.service.listExternalIdentities(winner.principal))[0];
    const lastIdentityGrant = await winner.service.reauthenticateGoogle(winner.principal, {
      idToken: "fresh-google-last-identity",
      nonce: "1234567890abcdef",
      scope: "account.identity.unlink",
      idempotencyKey: randomUUID(),
    });
    await assert.rejects(
      winner.service.unlinkIdentity(winner.principal, {
        identityId: lastIdentity.id,
        grant: lastIdentityGrant.grant,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-ACCOUNT-011",
    );

    const googleC = googleIdentity("google-account-c", "owner-c@example.com");
    const serviceC = new AccountService({ verify: async () => googleC }, repository, tokens, now);
    const googleClientId = randomUUID();
    const googleSession = await serviceC.exchangeGoogleProof(sessionInput(googleClientId, randomUUID()));
    const googlePrincipal = await serviceC.authenticate(`Bearer ${googleSession.session.accessToken}`);
    const linkGrant = await serviceC.reauthenticateGoogle(googlePrincipal, {
      idToken: "fresh-google-c-link",
      nonce: "1234567890abcdef",
      scope: "account.identity.link",
      idempotencyKey: randomUUID(),
    });
    const emailC = new EmailOtpSecurity(
      "identity-race-email-key-with-at-least-thirty-two-bytes",
      "https://mrlgs.net",
    ).resolveIdentity("owner-c-secondary@example.com").identity;
    const linkedEmailC = await serviceC.linkEmailIdentity(googlePrincipal, emailC, {
      grant: linkGrant.grant,
      idempotencyKey: randomUUID(),
    });
    const emailClientId = randomUUID();
    const emailSessionC = await serviceC.exchangeEmailIdentity(
      emailC,
      sessionInput(emailClientId, randomUUID()),
    );
    const emailPrincipal = await serviceC.authenticate(`Bearer ${emailSessionC.session.accessToken}`);
    const googleIdentityC = (await serviceC.listExternalIdentities(googlePrincipal))
      .find(({ provider }) => provider === "google");
    assert(googleIdentityC);
    const removeEmailGrant = await serviceC.reauthenticateGoogle(googlePrincipal, {
      idToken: "fresh-google-c-unlink",
      nonce: "1234567890abcdef",
      scope: "account.identity.unlink",
      idempotencyKey: randomUUID(),
    });
    const removeGoogleGrant = await serviceC.reauthenticateEmailIdentity(emailPrincipal, emailC, {
      scope: "account.identity.unlink",
      idempotencyKey: randomUUID(),
    });
    const unlinkRace = await Promise.allSettled([
      serviceC.unlinkIdentity(googlePrincipal, {
        identityId: linkedEmailC.id,
        grant: removeEmailGrant.grant,
        idempotencyKey: randomUUID(),
      }),
      serviceC.unlinkIdentity(emailPrincipal, {
        identityId: googleIdentityC.id,
        grant: removeGoogleGrant.grant,
        idempotencyKey: randomUUID(),
      }),
    ]);
    assert.equal(unlinkRace.filter(({ status }) => status === "fulfilled").length, 1);
    assert.equal(unlinkRace.filter(({ status }) => status === "rejected").length, 1);
    const raceFailure = unlinkRace.find(({ status }) => status === "rejected");
    assert(raceFailure?.status === "rejected");
    assert.equal(errorCode(raceFailure.reason), "HR-AUTH-004");
    const survivingPrincipal = unlinkRace[0].status === "fulfilled" ? googlePrincipal : emailPrincipal;
    assert.equal((await serviceC.listExternalIdentities(survivingPrincipal)).length, 1);

    assert.equal((await pool.query<{ version: number }>(
      "SELECT version FROM gateway_schema_state WHERE singleton = true",
    )).rows[0].version, 15);
  } finally {
    await pool.end();
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

function googleIdentity(subject: string, email: string): VerifiedExternalIdentity {
  return {
    provider: "google",
    issuer: "https://accounts.google.com",
    subject,
    email,
  };
}

function sessionInput(clientInstallationId: string, idempotencyKey: string) {
  return {
    platform: "macos" as const,
    idToken: "test-google-proof",
    nonce: "1234567890abcdef",
    clientInstallationId,
    displayName: "Identity Test Mac",
    appVersion: "0.4.0-test",
    idempotencyKey,
  };
}

function errorCode(error: unknown): unknown {
  return typeof error === "object" && error !== null && "code" in error
    ? (error as { code: unknown }).code
    : undefined;
}
