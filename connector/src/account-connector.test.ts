import assert from "node:assert/strict";
import {
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  verify,
} from "node:crypto";
import { chmod, mkdtemp, realpath, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { ACCOUNT_CONNECTOR_PROTOCOL_VERSION, type ConnectorChallengeMessage } from "@hermes-remote/protocol";
import {
  AccountConnectorAuthenticator,
  accountGatewayOrigin,
  canonicalConnectorChallenge,
  loadAccountConnectorCredential,
  parseAccountConnectorCredential,
} from "./account-connector.js";

const PKCS8_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");

test("account credential is strict, key-bound, and never accepted from unsafe files", async () => {
  const fixture = credentialFixture();
  const parsed = parseAccountConnectorCredential(fixture.data);
  assert.equal(parsed.bindingId, fixture.bindingId);
  assert.equal(parsed.privateKey.equals(fixture.privateSeed), true);

  assert.throws(() => parseAccountConnectorCredential(Buffer.from(JSON.stringify({
    ...JSON.parse(fixture.data.toString("utf8")),
    publicKeyFingerprint: "0".repeat(64),
  }))));
  assert.throws(() => parseAccountConnectorCredential(Buffer.from(JSON.stringify({
    ...JSON.parse(fixture.data.toString("utf8")),
    extra: true,
  }))));

  const root = await mkdtemp(join(tmpdir(), "hermes-account-credential-"));
  const path = join(await realpath(root), "credential.json");
  await writeFile(path, fixture.data, { mode: 0o600 });
  assert.equal(loadAccountConnectorCredential(path).publicKeyFingerprint, fixture.fingerprint);
  await chmod(path, 0o644);
  assert.throws(() => loadAccountConnectorCredential(path), /HR-MIGRATE-001/);
  const link = join(root, "credential-link.json");
  await symlink(path, link);
  assert.throws(() => loadAccountConnectorCredential(link), /HR-MIGRATE-001/);
});

test("v2 challenge signature matches the Gateway canonical byte contract", () => {
  const fixture = credentialFixture();
  const credential = parseAccountConnectorCredential(fixture.data);
  const now = new Date("2026-09-07T08:00:02.000Z");
  const authenticator = new AccountConnectorAuthenticator(
    credential,
    "wss://relay.example/v2/connect",
    () => now,
  );
  const challenge = challengeFixture(fixture, "2026-09-07T08:00:00.000Z", "2026-09-07T08:00:05.000Z");

  const identify = authenticator.identify();
  const authentication = authenticator.authenticate(challenge);
  const privateKey = createPrivateKey({
    key: Buffer.concat([PKCS8_PREFIX, fixture.privateSeed]),
    format: "der",
    type: "pkcs8",
  });

  assert.equal(identify.version, ACCOUNT_CONNECTOR_PROTOCOL_VERSION);
  assert.equal(authentication.connectionNonce, challenge.connectionNonce);
  assert.equal(verify(
    null,
    canonicalConnectorChallenge("https://relay.example", challenge),
    createPublicKey(privateKey),
    Buffer.from(authentication.signature, "base64url"),
  ), true);
});

test("challenge mismatch, expiry, skew, and ready identity fail closed", () => {
  const fixture = credentialFixture();
  const authenticator = new AccountConnectorAuthenticator(
    parseAccountConnectorCredential(fixture.data),
    "wss://relay.example/v2/connect",
    () => new Date("2026-09-07T08:00:02.000Z"),
  );
  assert.throws(() => authenticator.authenticate({
    ...challengeFixture(fixture, "2026-09-07T08:00:00.000Z", "2026-09-07T08:00:01.000Z"),
  }), /HR-BIND-005/);
  assert.throws(() => authenticator.authenticate({
    ...challengeFixture(fixture, "2026-09-07T09:00:00.000Z", "2026-09-07T09:00:05.000Z"),
  }), /HR-BIND-005/);
  assert.throws(() => authenticator.authenticate({
    ...challengeFixture(fixture, "2026-09-07T08:00:00.000Z", "2026-09-07T08:00:05.000Z"),
    generation: 2,
  }), /HR-BIND-005/);
  assert.throws(() => authenticator.requireReady({
    type: "connector.ready",
    version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
    bindingId: fixture.bindingId,
    generation: 2,
    deviceId: "hermes-test",
    bindingStatus: "active",
    routingEnabled: true,
  }), /HR-BIND-005/);
});

test("account Gateway origin is derived only from an exact connector URL", () => {
  assert.equal(accountGatewayOrigin("wss://relay.example/v2/connect"), "https://relay.example");
  assert.equal(accountGatewayOrigin("ws://127.0.0.1:8787/v2/connect"), "http://127.0.0.1:8787");
  for (const value of [
    "https://relay.example/v2/connect",
    "wss://relay.example/v1/connect",
    "wss://user:password@relay.example/v2/connect",
    "wss://relay.example/v2/connect?token=secret",
  ]) assert.throws(() => accountGatewayOrigin(value), /HR-MIGRATE-001/);
});

function credentialFixture(): {
  data: Buffer;
  bindingId: string;
  fingerprint: string;
  privateSeed: Buffer;
} {
  const pair = generateKeyPairSync("ed25519");
  const privateDer = pair.privateKey.export({ format: "der", type: "pkcs8" });
  const publicDer = pair.publicKey.export({ format: "der", type: "spki" });
  const privateSeed = privateDer.subarray(-32);
  const fingerprint = createHash("sha256").update(publicDer.subarray(-32)).digest("hex");
  const bindingId = "10000000-0000-4000-8000-000000000001";
  return {
    bindingId,
    fingerprint,
    privateSeed,
    data: Buffer.from(JSON.stringify({
      schemaVersion: 1,
      bindingId,
      generation: 1,
      publicKeyFingerprint: fingerprint,
      privateKey: privateSeed.toString("base64url"),
    })),
  };
}

function challengeFixture(
  fixture: ReturnType<typeof credentialFixture>,
  serverTime: string,
  expiresAt: string,
): ConnectorChallengeMessage {
  return {
    type: "connector.challenge",
    version: ACCOUNT_CONNECTOR_PROTOCOL_VERSION,
    bindingId: fixture.bindingId,
    generation: 1,
    publicKeyFingerprint: fixture.fingerprint,
    challenge: Buffer.alloc(32, 1).toString("base64url"),
    connectionNonce: Buffer.alloc(24, 2).toString("base64url"),
    serverTime,
    expiresAt,
  };
}
