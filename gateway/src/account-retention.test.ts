import assert from "node:assert/strict";
import test from "node:test";
import {
  AccountRetentionScheduler,
  type AccountRetentionCounts,
  type AccountRetentionStore,
} from "./account/account-retention.js";

const oneDeletionEach: AccountRetentionCounts = {
  idempotencyRecords: 1,
  emailWebhookReceipts: 1,
  emailOtpChallenges: 1,
  deviceShareInvitations: 1,
  connectorReplacementRequests: 1,
  reauthenticationGrants: 1,
  refreshTokens: 1,
  accountSessions: 1,
  lifecycleEvents: 1,
  auditEvents: 1,
  accountDeletionRows: 1,
  accountsAnonymized: 1,
};

test("account retention scheduler coalesces sweeps and exposes aggregate-only progress", async () => {
  let resolveSweep: ((counts: AccountRetentionCounts) => void) | undefined;
  let calls = 0;
  let current = new Date("2026-09-08T01:00:00.000Z");
  const store: AccountRetentionStore = {
    sweep: async () => {
      calls += 1;
      return new Promise<AccountRetentionCounts>((resolve) => { resolveSweep = resolve; });
    },
  };
  const scheduler = new AccountRetentionScheduler(store, {
    now: () => current,
    initialDelayMilliseconds: 60_000,
    intervalMilliseconds: 60_000,
  });
  scheduler.start();
  const first = scheduler.runNow();
  const second = scheduler.runNow();
  assert.equal(first, second);
  assert.equal(calls, 1);
  assert.deepEqual(scheduler.snapshot(), {
    observedAt: "2026-09-08T01:00:00.000Z",
    running: true,
    lastAttemptAt: "2026-09-08T01:00:00.000Z",
    lastSuccessAt: null,
    lastFailureAt: null,
    deletedSinceStart: {
      idempotencyRecords: 0,
      emailWebhookReceipts: 0,
      emailOtpChallenges: 0,
      deviceShareInvitations: 0,
      connectorReplacementRequests: 0,
      reauthenticationGrants: 0,
      refreshTokens: 0,
      accountSessions: 0,
      lifecycleEvents: 0,
      auditEvents: 0,
      accountDeletionRows: 0,
      accountsAnonymized: 0,
    },
  });
  current = new Date("2026-09-08T01:00:01.000Z");
  resolveSweep!(oneDeletionEach);
  assert.deepEqual(await first, oneDeletionEach);
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.deepEqual(scheduler.snapshot(), {
    observedAt: "2026-09-08T01:00:01.000Z",
    running: false,
    lastAttemptAt: "2026-09-08T01:00:00.000Z",
    lastSuccessAt: "2026-09-08T01:00:01.000Z",
    lastFailureAt: null,
    deletedSinceStart: oneDeletionEach,
  });
  await scheduler.close();
});

test("account retention scheduler records a redacted failure and closes cleanly", async () => {
  let failures = 0;
  const scheduler = new AccountRetentionScheduler(
    { sweep: async () => { throw new Error("database detail that must not be reported"); } },
    {
      now: () => new Date("2026-09-08T02:00:00.000Z"),
      intervalMilliseconds: 60_000,
      reportFailure: () => { failures += 1; },
    },
  );
  await assert.rejects(scheduler.runNow(), /database detail/);
  await new Promise<void>((resolve) => setImmediate(resolve));
  const metrics = scheduler.snapshot();
  assert.equal(metrics.running, false);
  assert.equal(metrics.lastAttemptAt, "2026-09-08T02:00:00.000Z");
  assert.equal(metrics.lastSuccessAt, null);
  assert.equal(metrics.lastFailureAt, "2026-09-08T02:00:00.000Z");
  assert.equal(JSON.stringify(metrics).includes("database detail"), false);
  assert.equal(failures, 1);
  await scheduler.close();
});

test("account retention scheduler catches up bounded deletion work before returning to its interval", async () => {
  let calls = 0;
  const store: AccountRetentionStore = {
    sweep: async () => {
      calls += 1;
      return calls === 1 ? oneDeletionEach : {
        ...oneDeletionEach,
        idempotencyRecords: 0,
        emailWebhookReceipts: 0,
        emailOtpChallenges: 0,
        deviceShareInvitations: 0,
        connectorReplacementRequests: 0,
        reauthenticationGrants: 0,
        refreshTokens: 0,
        accountSessions: 0,
        lifecycleEvents: 0,
        auditEvents: 0,
        accountDeletionRows: 0,
        accountsAnonymized: 0,
      };
    },
  };
  const scheduler = new AccountRetentionScheduler(store, {
    batchSize: 1,
    initialDelayMilliseconds: 60_000,
    intervalMilliseconds: 60_000,
    catchUpDelayMilliseconds: 10,
  });

  await scheduler.runNow();
  await waitFor(() => calls === 2);
  await new Promise<void>((resolve) => setTimeout(resolve, 30));
  assert.equal(calls, 2);
  await scheduler.close();
});

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("retention catch-up did not run");
    await new Promise<void>((resolve) => setTimeout(resolve, 10));
  }
}
