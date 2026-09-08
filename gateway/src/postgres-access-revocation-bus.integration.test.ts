import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { Pool } from "pg";
import {
  encodeAccountAccessRevocation,
  parseAccountAccessRevocation,
  PostgresAccountAccessRevocationSubscriber,
  publishAccountAccessRevocation,
  type AccountAccessRevocation,
} from "./account/postgres-access-revocation-bus.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;

test("account access revocation payloads are exact, bounded, and UUID-only", () => {
  const event: AccountAccessRevocation = {
    kind: "installation",
    accountId: "10000000-0000-4000-8000-000000000001",
    installationId: "20000000-0000-4000-8000-000000000002",
  };
  assert.deepEqual(parseAccountAccessRevocation(encodeAccountAccessRevocation(event)), event);
  for (const invalid of [
    "",
    "not-json",
    JSON.stringify({ ...event, secret: "must-not-pass" }),
    JSON.stringify({ ...event, accountId: "account-a" }),
    JSON.stringify({ kind: "session", accountId: event.accountId }),
    "x".repeat(1_025),
  ]) {
    assert.equal(parseAccountAccessRevocation(invalid), undefined);
  }
});

test("PostgreSQL propagates committed revocations between Gateway connections and drops rollbacks", {
  skip: databaseUrl ? false : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
}, async () => {
  assert(databaseUrl);
  const publisherPool = new Pool({ connectionString: databaseUrl, max: 1 });
  const subscriberPool = new Pool({ connectionString: databaseUrl, max: 1 });
  const subscriber = new PostgresAccountAccessRevocationSubscriber(subscriberPool, 25);
  const rolledBack: AccountAccessRevocation = {
    kind: "account",
    accountId: randomUUID(),
  };
  const committed: AccountAccessRevocation = {
    kind: "binding",
    accountId: randomUUID(),
    bindingId: randomUUID(),
  };
  const received: AccountAccessRevocation[] = [];
  const expectedAccountIds = new Set([rolledBack.accountId, committed.accountId]);
  const unsubscribeThrowingListener = subscriber.subscribe((event) => {
    if (expectedAccountIds.has(event.accountId)) throw new Error("listener failure");
  });
  const unsubscribe = subscriber.subscribe((event) => {
    if (expectedAccountIds.has(event.accountId)) received.push(event);
  });
  await subscriber.start();
  const publisher = await publisherPool.connect();
  try {
    await publisher.query("BEGIN");
    await publishAccountAccessRevocation(publisher, rolledBack);
    await publisher.query("ROLLBACK");
    await delay(50);
    assert.deepEqual(received, []);

    await publisher.query("BEGIN");
    await publishAccountAccessRevocation(publisher, committed);
    await publisher.query("COMMIT");
    await waitFor(() => received.length === 1);
    assert.deepEqual(received, [committed]);
  } finally {
    publisher.release();
    unsubscribeThrowingListener();
    unsubscribe();
    await subscriber.close();
    await Promise.all([publisherPool.end(), subscriberPool.end()]);
  }
});

async function waitFor(condition: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!condition()) {
    if (Date.now() >= deadline) throw new Error("timed out waiting for revocation notification");
    await delay(10);
  }
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
