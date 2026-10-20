import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { Readable } from "node:stream";
import type { IncomingMessage, ServerResponse } from "node:http";
import test from "node:test";
import { Pool } from "pg";
import {
  AccountModelSync,
  MODEL_SYNC_RETENTION_MS,
  modelRevision,
  parseModelMutation,
  type ModelMutation,
} from "./account/account-model-sync.js";
import { AccountHttpController } from "./account/account-http-controller.js";
import { AccountService } from "./account/account-service.js";
import { PostgresAccountRepository } from "./account/postgres-account-repository.js";
import { PostgresAccountRetentionStore } from "./account/account-retention.js";
import { TokenCodec } from "./account/token-codec.js";
import { accountErrors, type AccountPrincipal } from "./account/model.js";
import {
  WebSessionSecurity,
  WEB_COOKIE_NAMES,
} from "./account/web-session-security.js";

const codec = new TokenCodec("model-sync-tests-only-key-with-thirty-two-bytes");
const code = (wanted: string) => (e: unknown) =>
  !!e && typeof e === "object" && "code" in e && e.code === wanted;
const actor: AccountPrincipal = {
  account: { id: randomUUID() },
  installation: {
    id: randomUUID(),
    kind: "phone",
    platform: "android",
    displayName: "Test",
  },
  sessionId: randomUUID(),
  refreshFamilyId: randomUUID(),
};
const mutation = (
  lease = "lease",
  values: Partial<ModelMutation> = {},
): ModelMutation => ({
  opId: randomUUID(),
  lease,
  provider: "provider/a",
  model: "model/b",
  field: "favorite",
  baseRevision: "0",
  value: true,
  ...values,
});

test("model wire parser preserves uint64, structured tuples and enums without echoing rejected values", () => {
  assert.equal(modelRevision("18446744073709551615"), "18446744073709551615");
  for (const v of [0, 1, "01", "-1", "1e3", "18446744073709551616", null])
    assert.throws(() => modelRevision(v), code("HR-SYNC-014"));
  for (const bad of [
    { accountId: "other" },
    { toggle: true },
    { provider: "bad\u0000value" },
    { value: "true" },
    { baseRevision: 0 },
    { field: "reasoningPreset", value: "maximum" },
    { field: "reasoningPreset", value: 3 },
  ]) {
    assert.throws(
      () => parseModelMutation({ ...mutation(), ...bad }),
      (e) => code("HR-SYNC-014")(e) && !String(e).includes("bad"),
    );
  }
  for (const value of [
    null,
    "none",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "ultra",
  ]) {
    assert.equal(
      parseModelMutation(
        mutation("lease", {
          field: "reasoningPreset",
          value: value as ModelMutation["value"],
        }),
      ).value,
      value,
    );
  }
});

test("model HTTP capability is independent/default-off; native/cookie/CSRF/expected-account boundaries stay separate", async () => {
  const access = `hga_${"a".repeat(43)}`,
    csrf = `hgc_${"b".repeat(43)}`;
  const seen: string[] = [];
  const service = {
    authenticate: async (auth: string) => {
      seen.push(auth);
      if (!auth) throw accountErrors.sessionExpired();
      return actor;
    },
  } as unknown as AccountService;
  const sync = {
    snapshot: async () => ({ rows: [] }),
    resolve: async () => ({ row: {} }),
    mutate: async () => ({ status: "accepted" }),
    changes: async () => ({ rows: [] }),
  } as unknown as AccountModelSync;
  const controller = new AccountHttpController(true, service, {
    modelSync: sync,
    webSessionEnabled: true,
    webSessionSecurity: new WebSessionSecurity("https://models.invalid"),
  });
  async function call(
    path: string,
    method = "GET",
    headers: Record<string, string> = {},
    body?: unknown,
    target = controller,
  ) {
    const request = Readable.from(
      body === undefined ? [] : [JSON.stringify(body)],
    ) as IncomingMessage;
    request.method = method;
    request.headers = headers;
    let status = 0;
    let data = "";
    let responseHeaders: Record<string, string> = {};
    const response = {
      writableEnded: false,
      writeHead: (s: number, h: Record<string, string>) => {
        status = s;
        responseHeaders = h;
      },
      end: (v: string) => {
        data = v;
      },
    } as unknown as ServerResponse;
    await target.handle(
      request,
      response,
      new URL(path, "https://models.invalid"),
    );
    return { status, body: JSON.parse(data), headers: responseHeaders };
  }
  const disabled = new AccountHttpController(true, service);
  assert.equal(
    (await call("/v2/capabilities", "GET", {}, undefined, disabled)).body
      .accountAuth.modelPreferencesSync,
    undefined,
  );
  assert.deepEqual(
    (await call("/v2/capabilities")).body.accountAuth.modelPreferencesSync,
    { version: 1, maxModels: 500, retentionDays: 90 },
  );
  assert.equal(
    (await call("/v2/capabilities")).body.accountAuth.accountDataSync,
    undefined,
  );
  assert.equal(
    (
      await call(
        "/v2/account-data/models/snapshot",
        "GET",
        {},
        undefined,
        disabled,
      )
    ).status,
    503,
  );
  const cookie = `${WEB_COOKIE_NAMES.access}=${access}; ${WEB_COOKIE_NAMES.csrf}=${csrf}`;
  await call("/v2/account-data/models/snapshot", "GET", {
    cookie,
    authorization: "Bearer native",
  });
  assert.equal(seen.at(-1), "Bearer native");
  const safe = {
    cookie,
    "x-hermes-expected-account": actor.account.id,
    origin: "https://models.invalid",
    "x-hermes-csrf": csrf,
    "content-type": "application/json",
  };
  const snapshot = await call(
    "/v2/web/account-data/models/snapshot",
    "GET",
    safe,
  );
  assert.equal(seen.at(-1), `Bearer ${access}`);
  assert.match(snapshot.headers["cache-control"], /no-store/);
  assert.equal(
    (
      await call("/v2/web/account-data/models/snapshot", "GET", {
        authorization: "Bearer native",
      })
    ).status,
    401,
  );
  assert.equal(
    (await call("/v2/web/account-data/models/snapshot", "GET", { cookie })).body
      .error.code,
    "HR-SYNC-013",
  );
  assert.equal(
    (
      await call(
        "/v2/web/account-data/models/resolve",
        "POST",
        { cookie, "content-type": "application/json" },
        {},
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await call(
        "/v2/web/account-data/models/mutations",
        "POST",
        safe,
        mutation(),
      )
    ).status,
    200,
  );
  assert.equal(
    (
      await call("/v2/account-data/models/changes", "GET", {
        authorization: "Bearer native",
      })
    ).body.error.code,
    "HR-SYNC-013",
  );
});

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
test(
  "PostgreSQL model sync: CAS, ordered pages, minimal cancellation, retention, quota and deletion",
  {
    skip: databaseUrl
      ? false
      : "set ACCOUNT_TEST_DATABASE_URL to disposable PostgreSQL 18",
  },
  async (t) => {
    const schema = `model_sync_${randomUUID().replaceAll("-", "")}`;
    const admin = new Pool({ connectionString: databaseUrl, max: 1 });
    const pool = new Pool({
      connectionString: databaseUrl,
      max: 8,
      options: `-c search_path=${schema}`,
    });
    await admin.query(`CREATE SCHEMA "${schema}"`);
    try {
      for (const file of (await readdir(resolve("migrations")))
        .filter((f) => /^\d{3}_.*\.sql$/.test(f))
        .sort())
        await pool.query(await readFile(resolve("migrations", file), "utf8"));
      let subject = "account-a";
      const repository = new PostgresAccountRepository(pool, codec);
      const auth = new AccountService(
        {
          verify: async () => ({
            provider: "google",
            issuer: "https://accounts.google.com",
            subject,
            email: `${subject}@example.invalid`,
          }),
        },
        repository,
        codec,
      );
      async function signIn(clientInstallationId = randomUUID()) {
        const result = await auth.exchangeGoogleProof({
          platform: "android",
          idToken: "test-proof",
          nonce: "1234567890abcdef",
          clientInstallationId,
          displayName: "Synthetic",
          appVersion: "0.0.0-test",
          idempotencyKey: randomUUID(),
        });
        return auth.authenticate(`Bearer ${result.session.accessToken}`);
      }
      const a = await signIn(),
        phone = await signIn();
      subject = "account-b";
      const b = await signIn();
      let now = Date.now();
      const sync = new AccountModelSync(pool, codec, () => now);
      const initial = await sync.snapshot(a),
        other = await sync.snapshot(b),
        phoneSnapshot = await sync.snapshot(phone);
      const lease = initial.lease;
      const revokedReplay = mutation(phoneSnapshot.lease, {
        model: "revocation",
      });
      const initialOperation = mutation(lease);
      let last = await sync.mutate(a, initialOperation);
      await t.test(
        "independent field revisions merge, conflicting same-field writes do not",
        async () => {
          const reasoning = await sync.mutate(
            phone,
            mutation(phoneSnapshot.lease, {
              field: "reasoningPreset",
              value: "high",
            }),
          );
          assert.equal(reasoning.row.favorite, true);
          assert.equal(
            reasoning.row.favoriteRevision,
            last.row.favoriteRevision,
          );
          const left = mutation(lease, {
            value: false,
            baseRevision: last.row.favoriteRevision,
          });
          const right = mutation(phoneSnapshot.lease, {
            value: true,
            baseRevision: last.row.favoriteRevision,
          });
          const [x, y] = await Promise.all([
            sync.mutate(a, left),
            sync.mutate(phone, right),
          ]);
          assert.deepEqual([x.status, y.status].sort(), [
            "accepted",
            "conflict",
          ]);
          const accepted = x.status === "accepted" ? left : right,
            principal = x.status === "accepted" ? a : phone;
          const ack = await sync.mutate(principal, accepted);
          assert.deepEqual(
            await new AccountModelSync(pool, codec, () => now).mutate(
              principal,
              accepted,
            ),
            ack,
          );
          await assert.rejects(
            sync.mutate(principal, { ...accepted, value: !accepted.value }),
            code("HR-ACCOUNT-005"),
          );
          assert.equal((await sync.snapshot(b)).rows.length, 0);
          last = await sync.mutate(
            a,
            mutation(lease, {
              value: false,
              baseRevision: ack.row.favoriteRevision,
            }),
          );
        },
      );
      await t.test(
        "cancelled pairs erase identifiers, preserve revisions and block old import after signing-key rotation",
        async () => {
          last = await sync.mutate(
            a,
            mutation(lease, {
              field: "reasoningPreset",
              value: null,
              baseRevision: last.row.reasoningRevision,
            }),
          );
          assert.equal((await sync.snapshot(a)).rows.length, 0);
          const stored = await pool.query(
            "SELECT provider,model,favorite,reasoning FROM account_model_preferences WHERE account_id=$1",
            [a.account.id],
          );
          assert.deepEqual(stored.rows, [
            { provider: null, model: null, favorite: false, reasoning: null },
          ]);
          const rotated = new AccountModelSync(
            pool,
            new TokenCodec("rotated-model-sync-tests-only-key-with-32-bytes"),
          );
          const current = await rotated.snapshot(a);
          const replayed = await rotated.mutate(a, {
            ...initialOperation,
            lease: current.lease,
          });
          assert.equal(replayed.status, "accepted");
          assert.equal(replayed.row.favorite, true);
          assert.equal((await rotated.snapshot(a)).rows.length, 0);

          const oldImport = await rotated.mutate(a, mutation(current.lease));
          assert.equal(oldImport.status, "conflict");
          assert.equal(
            oldImport.row.favoriteRevision,
            last.row.favoriteRevision,
          );
          const resolved = await rotated.resolve(a, {
            provider: "provider/a",
            model: "model/b",
          });
          assert.equal(resolved.row.revision, last.row.revision);
          const replay = await pool.query(
            "SELECT result FROM account_model_sync_operations WHERE account_id=$1",
            [a.account.id],
          );
          assert(!JSON.stringify(replay.rows).includes("provider/a"));
          assert(!JSON.stringify(replay.rows).includes("model/b"));
        },
      );
      await t.test(
        "same concatenation on different tuple splits stays distinct",
        async () => {
          await sync.mutate(
            a,
            mutation(lease, { provider: "a/b", model: "c" }),
          );
          await sync.mutate(
            a,
            mutation(lease, { provider: "a", model: "b/c" }),
          );
          const rows = (await sync.snapshot(a)).rows;
          assert.equal(rows.length, 2);
          assert.notEqual(rows[0].key, rows[1].key);
        },
      );
      await t.test(
        "pagination freezes its upper commit boundary and subsequent polls receive later writes",
        async () => {
          const start = await sync.snapshot(a);
          let base = "0";
          for (let i = 0; i < 205; i++) {
            const r = await sync.mutate(
              a,
              mutation(lease, {
                model: "page",
                baseRevision: base,
                value: i % 2 === 0,
              }),
            );
            base = r.row.favoriteRevision;
          }
          const first = await sync.changes(a, start.cursor);
          assert.equal(first.rows.length, 200);
          assert.equal(first.hasMore, true);
          const later = await sync.mutate(
            a,
            mutation(lease, { model: "after-page" }),
          );
          const second = await sync.changes(a, first.cursor);
          assert.equal(second.rows.length, 5);
          assert.equal(second.hasMore, false);
          assert.equal(second.revision, first.revision);
          assert(!second.rows.some((r) => r.revision === later.row.revision));
          assert.equal(
            (await sync.changes(a, second.cursor)).rows[0].revision,
            later.row.revision,
          );
          assert.equal(
            new Set([...first.rows, ...second.rows].map((r) => r.revision))
              .size,
            205,
          );
          assert.equal(
            (await sync.mutate(phone, revokedReplay)).status,
            "accepted",
          );
        },
      );
      await t.test(
        "cursors and write leases are signed, scoped and expire without renewal of old operations",
        async () => {
          await assert.rejects(
            sync.changes(b, initial.cursor),
            code("HR-SYNC-013"),
          );
          await assert.rejects(
            sync.changes(a, initial.cursor + "x"),
            code("HR-SYNC-013"),
          );
          await assert.rejects(
            sync.mutate(b, mutation(lease)),
            code("HR-SYNC-013"),
          );
          await assert.rejects(
            sync.mutate(phone, mutation(lease)),
            code("HR-SYNC-013"),
          );
          now += MODEL_SYNC_RETENTION_MS + 1;
          await assert.rejects(
            sync.mutate(a, mutation(lease)),
            code("HR-SYNC-013"),
          );
          now = Date.now();
        },
      );
      await t.test(
        "bounded log/operation cleanup advances reset floor but keeps cancellation markers",
        async () => {
          await pool.query(
            "UPDATE account_model_sync_operations SET expires_at=now()-interval '1 second'",
          );
          await pool.query(
            "UPDATE account_model_sync_changes SET expires_at=now()-interval '1 second'",
          );
          const retention = new PostgresAccountRetentionStore(pool);
          let count = 0;
          for (let i = 0; i < 200; i++) {
            const r = await retention.sweep(new Date(), 7);
            assert(r.modelSyncRecords <= 7);
            count += r.modelSyncRecords;
            if (!r.modelSyncRecords) break;
          }
          assert(count > 400);
          await assert.rejects(
            sync.changes(a, initial.cursor),
            code("HR-SYNC-013"),
          );
          assert.equal(
            (await sync.mutate(a, mutation(lease))).status,
            "conflict",
          );
          const remains = await pool.query(
            "SELECT count(*) FROM account_model_preferences WHERE account_id=$1 AND provider IS NULL",
            [a.account.id],
          );
          assert(Number(remains.rows[0].count) > 0);
        },
      );
      await t.test(
        "quota counts active pairs; cancelling frees a slot without forgetting the tombstone",
        async () => {
          for (let i = 0; i < 500; i++)
            await sync.mutate(
              b,
              mutation(other.lease, { model: `quota-${i}` }),
            );
          await assert.rejects(
            sync.mutate(b, mutation(other.lease, { model: "quota-500" })),
            code("HR-SYNC-015"),
          );
          const before = (
            await sync.resolve(b, { provider: "provider/a", model: "quota-0" })
          ).row;
          await sync.mutate(
            b,
            mutation(other.lease, {
              model: "quota-0",
              value: false,
              baseRevision: before.favoriteRevision,
            }),
          );
          assert.equal(
            (
              await sync.mutate(
                b,
                mutation(other.lease, { model: "quota-500" }),
              )
            ).status,
            "accepted",
          );
          assert.equal(
            (await sync.mutate(b, mutation(other.lease, { model: "quota-0" })))
              .status,
            "conflict",
          );
          assert.equal((await sync.snapshot(b)).rows.length, 500);
        },
      );
      await t.test(
        "revoked sessions cannot replay success; account deletion drains all model tables",
        async () => {
          const current = (
            await sync.resolve(phone, {
              provider: revokedReplay.provider,
              model: revokedReplay.model,
            })
          ).row;
          revokedReplay.baseRevision = current.favoriteRevision;
          revokedReplay.opId = randomUUID();
          assert.equal(
            (await sync.mutate(phone, revokedReplay)).status,
            "accepted",
          );
          assert.equal(
            (
              await pool.query(
                "SELECT count(*) FROM account_model_sync_operations WHERE account_id=$1 AND op_id=$2",
                [phone.account.id, revokedReplay.opId],
              )
            ).rows[0].count,
            "1",
          );
          await pool.query(
            "UPDATE account_sessions SET revoked_at=now() WHERE id=$1",
            [phone.sessionId],
          );
          await assert.rejects(
            sync.mutate(phone, revokedReplay),
            code("HR-AUTH-004"),
          );
          await pool.query(
            "UPDATE accounts SET status='pending_deletion',deletion_requested_at=now()-interval '31 days',deletion_due_at=now()-interval '1 day' WHERE id=$1",
            [b.account.id],
          );
          const retention = new PostgresAccountRetentionStore(pool);
          for (let i = 0; i < 100; i++) {
            await retention.sweep(new Date(), 50);
            const r = await pool.query(
              "SELECT count(*) FROM account_model_sync_state WHERE account_id=$1",
              [b.account.id],
            );
            if (r.rows[0].count === "0") break;
          }
          for (const table of [
            "account_model_sync_state",
            "account_model_preferences",
            "account_model_sync_changes",
            "account_model_sync_operations",
          ]) {
            assert.equal(
              (
                await pool.query(
                  `SELECT count(*) FROM ${table} WHERE account_id=$1`,
                  [b.account.id],
                )
              ).rows[0].count,
              "0",
            );
          }
        },
      );
      await t.test(
        "relogin with the same installation cannot revive a residual queue lease",
        async () => {
          subject = "model-relogin-account";
          const clientId = randomUUID();
          const old = await signIn(clientId);
          const oldSnapshot = await sync.snapshot(old);
          await pool.query(
            "UPDATE account_sessions SET revoked_at=now() WHERE id=$1",
            [old.sessionId],
          );
          await pool.query(
            "UPDATE installations SET revoked_at=now() WHERE id=$1",
            [old.installation.id],
          );
          const next = await signIn(clientId);
          assert.equal(next.installation.id, old.installation.id);
          assert.notEqual(next.sessionId, old.sessionId);
          await assert.rejects(
            sync.mutate(next, mutation(oldSnapshot.lease)),
            code("HR-SYNC-013"),
          );
          const fresh = await sync.snapshot(next);
          assert.equal(fresh.rows.length, 0);
          assert.equal(
            (await sync.mutate(next, mutation(fresh.lease))).status,
            "accepted",
          );
        },
      );
      await t.test("uint64 revisions never round or wrap", async () => {
        await pool.query(
          "UPDATE account_model_sync_state SET revision=9007199254740993 WHERE account_id=$1",
          [a.account.id],
        );
        const r = await sync.mutate(a, mutation(lease, { model: "precise" }));
        assert.equal(r.row.revision, "9007199254740994");
        await pool.query(
          "UPDATE account_model_sync_state SET revision=18446744073709551615 WHERE account_id=$1",
          [a.account.id],
        );
        await assert.rejects(
          sync.mutate(a, mutation(lease, { model: "exhausted" })),
          code("HR-SYNC-013"),
        );
      });
    } finally {
      await pool.end();
      await admin.query(`DROP SCHEMA "${schema}" CASCADE`);
      await admin.end();
    }
  },
);
