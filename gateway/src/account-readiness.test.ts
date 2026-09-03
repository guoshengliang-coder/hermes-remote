import assert from "node:assert/strict";
import type { Pool } from "pg";
import test from "node:test";
import { checkDatabaseReadiness } from "./account/account-runtime.js";
import { loadServerReleaseManifest } from "./server-release.js";

test("database readiness requires the certified PostgreSQL and exact migration versions", async () => {
  const release = loadServerReleaseManifest();
  const healthy = fakePool([
    { rows: [{ server_version_num: "180006" }] },
    { rows: [{ version: release.databaseSchemaVersion }] },
  ]);
  assert.deepEqual(await checkDatabaseReadiness(healthy, release), {
    ready: true,
    checks: {
      config: "ok",
      database: "ok",
      migrations: "ok",
      postgresql: "supported",
    },
  });

  const stale = fakePool([
    { rows: [{ server_version_num: "180006" }] },
    { rows: [{ version: release.databaseSchemaVersion - 1 }] },
  ]);
  const staleResult = await checkDatabaseReadiness(stale, release);
  assert.equal(staleResult.ready, false);
  assert.equal(staleResult.checks.migrations, "mismatch");

  const unsupported = fakePool([
    { rows: [{ server_version_num: "170009" }] },
    { rows: [{ version: release.databaseSchemaVersion }] },
  ]);
  const unsupportedResult = await checkDatabaseReadiness(unsupported, release);
  assert.equal(unsupportedResult.ready, false);
  assert.equal(unsupportedResult.checks.postgresql, "unsupported");
});

test("database readiness redacts connection failures into bounded states", async () => {
  const release = loadServerReleaseManifest();
  const pool = {
    query: async () => { throw new Error("postgresql://user:secret@example.invalid/private"); },
  } as unknown as Pool;
  assert.deepEqual(await checkDatabaseReadiness(pool, release), {
    ready: false,
    checks: {
      config: "ok",
      database: "unavailable",
      migrations: "unknown",
      postgresql: "unknown",
    },
  });
});

function fakePool(results: Array<{ rows: Array<Record<string, unknown>> }>): Pool {
  let index = 0;
  return {
    query: async () => results[index++],
  } as unknown as Pool;
}
