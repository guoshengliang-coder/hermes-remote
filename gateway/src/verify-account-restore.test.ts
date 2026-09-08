import test from "node:test";
import assert from "node:assert/strict";
import { verifyAccountRestore } from "./ops/verify-account-restore.js";

test("restore verifier proves PostgreSQL, exact schema, account relations, and rolls back", async () => {
  const queries: Array<{ text: string; values?: unknown[] }> = [];
  const client = {
    async query(text: string, values?: unknown[]) {
      queries.push({ text, values });
      if (text === "SHOW server_version_num") return { rowCount: 1, rows: [{ server_version_num: "180001" }] };
      if (text.startsWith("SELECT version")) return { rowCount: 1, rows: [{ version: 7 }] };
      if (text.startsWith("SELECT a.status")) {
        return { rowCount: 1, rows: [{ status: "active", provider: "google", kind: "phone", platform: "android" }] };
      }
      return { rowCount: 1, rows: [] };
    },
    release() {},
  };
  let ended = false;
  const result = await verifyAccountRestore({
    env: {
      ACCOUNT_DATABASE_URL: "postgresql://test:test@127.0.0.1/test",
      ACCOUNT_DATABASE_SCHEMA_VERSION: "7",
      ACCOUNT_DATABASE_POSTGRESQL_MAJOR: "18",
    },
    poolFactory: () => ({ connect: async () => client, end: async () => { ended = true; } }) as never,
  });
  assert.deepEqual(result, { postgresqlMajor: 18, schemaVersion: 7, accountSmoke: "pass" });
  assert.equal(queries.some((entry) => entry.text === "BEGIN READ WRITE"), true);
  assert.equal(queries.at(-1)?.text, "ROLLBACK");
  assert.equal(ended, true);
});

test("restore verifier fails closed on schema mismatch and still closes the pool", async () => {
  let ended = false;
  const client = {
    async query(text: string) {
      if (text === "SHOW server_version_num") return { rowCount: 1, rows: [{ server_version_num: "180001" }] };
      return { rowCount: 1, rows: [{ version: 6 }] };
    },
    release() {},
  };
  await assert.rejects(() => verifyAccountRestore({
    env: {
      ACCOUNT_DATABASE_URL: "postgresql://test:test@127.0.0.1/test",
      ACCOUNT_DATABASE_SCHEMA_VERSION: "7",
      ACCOUNT_DATABASE_POSTGRESQL_MAJOR: "18",
    },
    poolFactory: () => ({ connect: async () => client, end: async () => { ended = true; } }) as never,
  }), (error: unknown) => (error as { code?: string }).code === "database_schema_mismatch");
  assert.equal(ended, true);
});
