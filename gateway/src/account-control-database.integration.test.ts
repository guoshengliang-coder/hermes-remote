import assert from "node:assert/strict";
import { generateKeyPairSync, randomBytes, randomUUID, sign } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import { AccountControlService } from "./account/account-control-service.js";
import { AccountService, type AccountSessionResponse } from "./account/account-service.js";
import {
  ConnectorProofCoordinator,
  canonicalConnectorChallenge,
} from "./account/connector-proof-coordinator.js";
import type { AccountPlatform, VerifiedExternalIdentity } from "./account/model.js";
import { PostgresAccountControlRepository } from "./account/postgres-account-control-repository.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { TokenCodec } from "./account/token-codec.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("PostgreSQL enforces one Connector and isolates independently revocable phones", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const schema = `control_test_${randomUUID().replaceAll("-", "")}`;
  const admin = new Pool({ connectionString: databaseUrl, max: 1 });
  await admin.query(`CREATE SCHEMA "${schema}"`);
  let accountRepository: PostgresAccountRepository | undefined;
  try {
    const setupPool = new Pool({
      connectionString: databaseUrl,
      max: 1,
      options: `-c search_path=${schema}`,
    });
    const migrationFiles = (await readdir(resolve("migrations")))
      .filter((name) => /^\d{3}_[a-z0-9_]+\.sql$/.test(name))
      .sort();
    for (const migrationFile of migrationFiles) {
      await setupPool.query(await readFile(resolve("migrations", migrationFile), "utf8"));
    }
    await setupPool.end();

    const pool = new Pool({
      connectionString: databaseUrl,
      max: 8,
      options: `-c search_path=${schema}`,
    });
    accountRepository = new PostgresAccountRepository(pool);
    const controlRepository = new PostgresAccountControlRepository(pool);
    const codec = new TokenCodec("control-integration-key-with-at-least-thirty-two-bytes");
    let identity: VerifiedExternalIdentity = {
      provider: "google",
      issuer: "https://accounts.google.com",
      subject: `account-a-${randomUUID()}`,
      email: "account-a@example.invalid",
      displayName: "Account A",
    };
    const accounts = new AccountService({ verify: async () => identity }, accountRepository, codec);
    const control = new AccountControlService(controlRepository, codec);

    const desktopA = await signIn(accounts, "macos", "Desktop A");
    const desktopB = await signIn(accounts, "macos", "Desktop B");
    const phoneA = await signIn(accounts, "android", "Phone A");
    const phoneB = await signIn(accounts, "android", "Phone B");
    const desktopAPrincipal = await accounts.authenticate(`Bearer ${desktopA.session.accessToken}`);
    const desktopBPrincipal = await accounts.authenticate(`Bearer ${desktopB.session.accessToken}`);
    const phoneAPrincipal = await accounts.authenticate(`Bearer ${phoneA.session.accessToken}`);
    const phoneBPrincipal = await accounts.authenticate(`Bearer ${phoneB.session.accessToken}`);

    const connectorKeys = [generateKeyPairSync("ed25519"), generateKeyPairSync("ed25519")];
    const createInputs = [
      {
        desktopInstallationId: desktopAPrincipal.installation.id,
        displayName: "Desktop A",
        connectorPublicKey: rawEd25519PublicKey(connectorKeys[0].publicKey),
        keyAlgorithm: "Ed25519",
        idempotencyKey: randomUUID(),
      },
      {
        desktopInstallationId: desktopBPrincipal.installation.id,
        displayName: "Desktop B",
        connectorPublicKey: rawEd25519PublicKey(connectorKeys[1].publicKey),
        keyAlgorithm: "Ed25519",
        idempotencyKey: randomUUID(),
      },
    ];
    const attempts = await Promise.allSettled([
      control.createPendingBinding(desktopAPrincipal, createInputs[0]),
      control.createPendingBinding(desktopBPrincipal, createInputs[1]),
    ]);
    assert.equal(attempts.filter(({ status }) => status === "fulfilled").length, 1);
    assert.equal(attempts.filter(({ status }) => status === "rejected").length, 1);
    const winnerIndex = attempts[0].status === "fulfilled" ? 0 : 1;
    const winner = attempts[winnerIndex];
    assert.equal(winner.status, "fulfilled");
    if (winner.status !== "fulfilled") throw new Error("binding race produced no winner");
    const winnerPrincipal = winnerIndex === 0 ? desktopAPrincipal : desktopBPrincipal;
    const loser = attempts[winnerIndex === 0 ? 1 : 0];
    assert(loser.status === "rejected" && errorCode(loser.reason) === "HR-BIND-002");

    const replayedCandidate = await control.createPendingBinding(
      winnerPrincipal,
      createInputs[winnerIndex],
    );
    assert.deepEqual(replayedCandidate, winner.value);
    await assert.rejects(
      control.createPendingBinding(winnerPrincipal, {
        ...createInputs[winnerIndex],
        displayName: "Changed retry input",
      }),
      (error: unknown) => errorCode(error) === "HR-ACCOUNT-005",
    );
    assert.deepEqual(await control.getBinding(phoneAPrincipal), { state: "no_binding" });

    const confirmKey = randomUUID();
    await assert.rejects(
      control.confirmPendingBinding(winnerPrincipal, {
        bindingId: winner.value.id,
        generation: winner.value.generation,
        idempotencyKey: confirmKey,
      }),
      (error: unknown) => errorCode(error) === "HR-BIND-005",
    );
    assert.equal(await controlRepository.recordBindingHealth(
      winner.value.id,
      winner.value.generation,
      {
        hermesReachable: true,
        hermesVersion: "0.0.0-test",
        gatewayLatencyMs: 1,
        endToEndHealthy: true,
      },
    ), false);
    const proof = new ConnectorProofCoordinator(controlRepository, "https://mrlgs.net");
    const challenge = await proof.issue({
      bindingId: winner.value.id,
      generation: winner.value.generation,
      publicKeyFingerprint: winner.value.publicKeyFingerprint,
    });
    await proof.authenticate({
      bindingId: winner.value.id,
      generation: winner.value.generation,
      publicKeyFingerprint: winner.value.publicKeyFingerprint,
      connectionNonce: challenge.connectionNonce,
      signature: sign(
        null,
        canonicalConnectorChallenge("https://mrlgs.net", challenge),
        connectorKeys[winnerIndex].privateKey,
      ).toString("base64url"),
    });
    assert.equal(await controlRepository.recordBindingHealth(
      winner.value.id,
      winner.value.generation,
      {
        hermesReachable: false,
        gatewayLatencyMs: 1,
        endToEndHealthy: false,
      },
    ), true);
    await assert.rejects(
      control.confirmPendingBinding(winnerPrincipal, {
        bindingId: winner.value.id,
        generation: winner.value.generation,
        idempotencyKey: confirmKey,
      }),
      (error: unknown) => errorCode(error) === "HR-BIND-005",
    );
    assert.equal(await controlRepository.recordBindingHealth(
      winner.value.id,
      winner.value.generation,
      {
        hermesReachable: true,
        hermesVersion: "0.0.0-test",
        gatewayLatencyMs: 1,
        endToEndHealthy: true,
      },
    ), true);

    const activated = await control.confirmPendingBinding(winnerPrincipal, {
      bindingId: winner.value.id,
      generation: winner.value.generation,
      idempotencyKey: confirmKey,
    });
    assert.equal((await control.confirmPendingBinding(winnerPrincipal, {
      bindingId: winner.value.id,
      generation: winner.value.generation,
      idempotencyKey: confirmKey,
    })).id, activated.id);
    const phoneBinding = await control.getBinding(phoneBPrincipal);
    assert(phoneBinding.state === "bound" && phoneBinding.binding.id === activated.id);

    await assert.rejects(
      control.createPendingBinding(
        winnerIndex === 0 ? desktopBPrincipal : desktopAPrincipal,
        createInputs[winnerIndex === 0 ? 1 : 0],
      ),
      (error: unknown) => errorCode(error) === "HR-BIND-002",
    );

    const replacementPrincipal = winnerIndex === 0 ? desktopBPrincipal : desktopAPrincipal;
    const replacementKeys = generateKeyPairSync("ed25519");
    const wrongScopeGrant = await accounts.reauthenticateGoogle(replacementPrincipal, {
      idToken: "fresh-google-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "connector.unbind",
      idempotencyKey: randomUUID(),
    });
    const replacementInput = {
      desktopInstallationId: replacementPrincipal.installation.id,
      displayName: "Replacement Desktop",
      connectorPublicKey: rawEd25519PublicKey(replacementKeys.publicKey),
      keyAlgorithm: "Ed25519",
    };
    await assert.rejects(
      control.createReplacementRequest(replacementPrincipal, {
        ...replacementInput,
        grant: wrongScopeGrant.grant,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-AUTH-006",
    );
    const replaceGrant = await accounts.reauthenticateGoogle(replacementPrincipal, {
      idToken: "fresh-google-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "connector.replace",
      idempotencyKey: randomUUID(),
    });
    const replacementCreateKey = randomUUID();
    const replacement = await control.createReplacementRequest(replacementPrincipal, {
      ...replacementInput,
      grant: replaceGrant.grant,
      idempotencyKey: replacementCreateKey,
    });
    assert.deepEqual(
      await control.createReplacementRequest(replacementPrincipal, {
        ...replacementInput,
        grant: replaceGrant.grant,
        idempotencyKey: replacementCreateKey,
      }),
      replacement,
    );
    const requesterView = await control.getBinding(replacementPrincipal);
    assert(requesterView.state === "replacement_pending");
    assert.equal(requesterView.previousBinding.id, activated.id);
    assert.equal(requesterView.candidate.id, replacement.candidate.id);
    const phoneDuringReplacement = await control.getBinding(phoneBPrincipal);
    assert(phoneDuringReplacement.state === "bound");
    assert.equal(phoneDuringReplacement.binding.id, activated.id);

    const replacementConfirmKey = randomUUID();
    await assert.rejects(
      control.confirmReplacementRequest(replacementPrincipal, {
        requestId: replacement.id,
        idempotencyKey: replacementConfirmKey,
      }),
      (error: unknown) => errorCode(error) === "HR-BIND-005",
    );
    const replacementProof = new ConnectorProofCoordinator(controlRepository, "https://mrlgs.net");
    const replacementChallenge = await replacementProof.issue({
      bindingId: replacement.candidate.id,
      generation: replacement.candidate.generation,
      publicKeyFingerprint: replacement.candidate.publicKeyFingerprint,
    });
    await replacementProof.authenticate({
      bindingId: replacement.candidate.id,
      generation: replacement.candidate.generation,
      publicKeyFingerprint: replacement.candidate.publicKeyFingerprint,
      connectionNonce: replacementChallenge.connectionNonce,
      signature: sign(
        null,
        canonicalConnectorChallenge("https://mrlgs.net", replacementChallenge),
        replacementKeys.privateKey,
      ).toString("base64url"),
    });
    assert.equal(await controlRepository.recordBindingHealth(
      replacement.candidate.id,
      replacement.candidate.generation,
      {
        hermesReachable: false,
        gatewayLatencyMs: 2,
        endToEndHealthy: false,
      },
    ), true);
    await assert.rejects(
      control.confirmReplacementRequest(replacementPrincipal, {
        requestId: replacement.id,
        idempotencyKey: replacementConfirmKey,
      }),
      (error: unknown) => errorCode(error) === "HR-BIND-005",
    );
    const stillOld = await control.getBinding(phoneBPrincipal);
    assert(stillOld.state === "bound" && stillOld.binding.id === activated.id);
    assert.equal(await controlRepository.recordBindingHealth(
      replacement.candidate.id,
      replacement.candidate.generation,
      {
        hermesReachable: true,
        hermesVersion: "0.0.0-replacement-test",
        gatewayLatencyMs: 2,
        endToEndHealthy: true,
      },
    ), true);
    const replacementConfirmations = await Promise.all([
      control.confirmReplacementRequest(replacementPrincipal, {
        requestId: replacement.id,
        idempotencyKey: replacementConfirmKey,
      }),
      control.confirmReplacementRequest(replacementPrincipal, {
        requestId: replacement.id,
        idempotencyKey: replacementConfirmKey,
      }),
    ]);
    assert.equal(replacementConfirmations[0].id, replacement.candidate.id);
    assert.equal(replacementConfirmations[1].id, replacement.candidate.id);
    await assert.rejects(
      control.confirmReplacementRequest(replacementPrincipal, {
        requestId: replacement.id,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-BIND-003",
    );
    assert.equal(await controlRepository.loadBindingProofMaterial(
      activated.id,
      activated.generation,
      activated.publicKeyFingerprint,
    ), undefined);
    const replacedOwnerView = await control.getBinding(winnerPrincipal);
    assert(replacedOwnerView.state === "revoked" && replacedOwnerView.generation === activated.generation);
    const newOwnerView = await control.getBinding(replacementPrincipal);
    assert(newOwnerView.state === "bound" && newOwnerView.binding.id === replacement.candidate.id);
    const counts = await pool.query<{ status: string; count: string }>(
      `SELECT status, COUNT(*)::text AS count
         FROM connector_bindings
        GROUP BY status
        ORDER BY status`,
    );
    assert.deepEqual(counts.rows, [
      { status: "active", count: "1" },
      { status: "replaced", count: "1" },
    ]);

    const installations = await control.listInstallations(desktopAPrincipal);
    assert.equal(installations.length, 4);
    assert.equal(installations.filter(({ kind }) => kind === "phone").length, 2);
    const revokeCurrentKey = randomUUID();
    await control.revokeCurrentPhoneInstallation(
      `Bearer ${phoneA.session.accessToken}`,
      revokeCurrentKey,
    );
    await control.revokeCurrentPhoneInstallation(
      `Bearer ${phoneA.session.accessToken}`,
      revokeCurrentKey,
    );
    await assert.rejects(
      accounts.authenticate(`Bearer ${phoneA.session.accessToken}`),
      (error: unknown) => errorCode(error) === "HR-AUTH-004",
    );
    assert.equal(
      (await accounts.authenticate(`Bearer ${phoneB.session.accessToken}`)).installation.id,
      phoneBPrincipal.installation.id,
    );
    assert.equal((await control.getBinding(phoneBPrincipal)).state, "bound");

    const secondPendingKeys = generateKeyPairSync("ed25519");
    const secondReplaceGrant = await accounts.reauthenticateGoogle(replacementPrincipal, {
      idToken: "fresh-google-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "connector.replace",
      idempotencyKey: randomUUID(),
    });
    const secondReplacement = await control.createReplacementRequest(replacementPrincipal, {
      desktopInstallationId: replacementPrincipal.installation.id,
      displayName: "Pending replacement",
      connectorPublicKey: rawEd25519PublicKey(secondPendingKeys.publicKey),
      keyAlgorithm: "Ed25519",
      grant: secondReplaceGrant.grant,
      idempotencyKey: randomUUID(),
    });
    await pool.query(
      "UPDATE connector_replacement_requests SET expires_at = now() - interval '1 second' WHERE id = $1",
      [secondReplacement.id],
    );
    await assert.rejects(
      control.confirmReplacementRequest(replacementPrincipal, {
        requestId: secondReplacement.id,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-BIND-003",
    );
    const activeAfterExpiredReplacement = await control.getBinding(phoneBPrincipal);
    assert(activeAfterExpiredReplacement.state === "bound");
    assert.equal(activeAfterExpiredReplacement.binding.id, replacement.candidate.id);
    const thirdPendingKeys = generateKeyPairSync("ed25519");
    const thirdReplaceGrant = await accounts.reauthenticateGoogle(replacementPrincipal, {
      idToken: "fresh-google-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "connector.replace",
      idempotencyKey: randomUUID(),
    });
    const thirdReplacement = await control.createReplacementRequest(replacementPrincipal, {
      desktopInstallationId: replacementPrincipal.installation.id,
      displayName: "Pending replacement before unbind",
      connectorPublicKey: rawEd25519PublicKey(thirdPendingKeys.publicKey),
      keyAlgorithm: "Ed25519",
      grant: thirdReplaceGrant.grant,
      idempotencyKey: randomUUID(),
    });
    await assert.rejects(
      control.unbindConnector(replacementPrincipal, {
        grant: secondReplaceGrant.grant,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-AUTH-006",
    );
    const unbindGrant = await accounts.reauthenticateGoogle(replacementPrincipal, {
      idToken: "fresh-google-proof-not-persisted",
      nonce: randomBytes(16).toString("hex"),
      scope: "connector.unbind",
      idempotencyKey: randomUUID(),
    });
    const unbindKey = randomUUID();
    await control.unbindConnector(replacementPrincipal, {
      grant: unbindGrant.grant,
      idempotencyKey: unbindKey,
    });
    await control.unbindConnector(replacementPrincipal, {
      grant: unbindGrant.grant,
      idempotencyKey: unbindKey,
    });
    const cancelledCandidate = await pool.query<{ status: string }>(
      "SELECT status FROM connector_bindings WHERE id = $1",
      [thirdReplacement.candidate.id],
    );
    assert.equal(cancelledCandidate.rows[0].status, "revoked");
    const cancelledRequest = await pool.query<{ status: string }>(
      "SELECT status FROM connector_replacement_requests WHERE id = $1",
      [thirdReplacement.id],
    );
    assert.equal(cancelledRequest.rows[0].status, "cancelled");
    assert.equal((await control.getBinding(replacementPrincipal)).state, "revoked");
    assert.equal(
      (await accounts.authenticate(`Bearer ${phoneB.session.accessToken}`)).installation.id,
      phoneBPrincipal.installation.id,
    );
    const revokeKey = randomUUID();
    await control.revokePhoneInstallation(replacementPrincipal, phoneBPrincipal.installation.id, revokeKey);
    await control.revokePhoneInstallation(replacementPrincipal, phoneBPrincipal.installation.id, revokeKey);
    await assert.rejects(
      accounts.authenticate(`Bearer ${phoneB.session.accessToken}`),
      (error: unknown) => errorCode(error) === "HR-AUTH-004",
    );
    assert.equal(
      (await accounts.authenticate(`Bearer ${winnerIndex === 0 ? desktopB.session.accessToken : desktopA.session.accessToken}`))
        .installation.id,
      replacementPrincipal.installation.id,
    );

    identity = {
      provider: "google",
      issuer: "https://accounts.google.com",
      subject: `account-b-${randomUUID()}`,
      email: "account-b@example.invalid",
      displayName: "Account B",
    };
    const otherPhone = await signIn(accounts, "android", "Other account phone");
    const otherDesktop = await signIn(accounts, "macos", "Other account Desktop");
    const otherPrincipal = await accounts.authenticate(`Bearer ${otherPhone.session.accessToken}`);
    const otherDesktopPrincipal = await accounts.authenticate(`Bearer ${otherDesktop.session.accessToken}`);
    assert.deepEqual(await control.getBinding(otherPrincipal), { state: "no_binding" });
    await assert.rejects(
      control.confirmReplacementRequest(otherDesktopPrincipal, {
        requestId: replacement.id,
        idempotencyKey: randomUUID(),
      }),
      (error: unknown) => errorCode(error) === "HR-ACCOUNT-006",
    );
    await assert.rejects(
      control.revokePhoneInstallation(desktopAPrincipal, otherPrincipal.installation.id, randomUUID()),
      (error: unknown) => errorCode(error) === "HR-ACCOUNT-006",
    );
  } finally {
    await accountRepository?.close().catch(() => {});
    await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
    await admin.end();
  }
});

async function signIn(
  service: AccountService,
  platform: AccountPlatform,
  displayName: string,
): Promise<AccountSessionResponse> {
  return service.exchangeGoogleProof({
    platform,
    idToken: "integration-provider-proof-not-persisted",
    nonce: randomBytes(16).toString("hex"),
    clientInstallationId: randomUUID(),
    displayName,
    appVersion: "0.0.0-test",
    idempotencyKey: randomUUID(),
  });
}

function errorCode(error: unknown): unknown {
  return typeof error === "object" && error !== null && "code" in error
    ? (error as { code: unknown }).code
    : undefined;
}

function rawEd25519PublicKey(publicKey: ReturnType<typeof generateKeyPairSync>["publicKey"]): string {
  const der = publicKey.export({ format: "der", type: "spki" });
  return der.subarray(der.byteLength - 32).toString("base64url");
}
