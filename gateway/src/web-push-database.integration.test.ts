import assert from "node:assert/strict";
import { randomUUID, randomBytes } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";
import { Pool } from "pg";
import webpush from "web-push";
import type { AccountPrincipal } from "./account/model.js";
import { PostgresWebPushStore } from "./account/push/web-push.js";

const databaseUrl = process.env.ACCOUNT_TEST_DATABASE_URL;
test(
  "Web Push routes only to live owner/shared browser sessions and drops revoked registrations",
  {
    skip: databaseUrl
      ? false
      : "set ACCOUNT_TEST_DATABASE_URL to a disposable PostgreSQL database",
  },
  async () => {
    const schema = `web_push_${randomUUID().replaceAll("-", "")}`;
    const admin = new Pool({ connectionString: databaseUrl, max: 1 });
    let pool: Pool | undefined;
    try {
      await admin.query(`CREATE SCHEMA "${schema}"`);
      pool = new Pool({
        connectionString: databaseUrl,
        max: 4,
        options: `-c search_path=${schema}`,
      });
      for (const name of (await readdir(resolve("migrations")))
        .filter((n) => /^\d{3}_[a-z0-9_]+\.sql$/.test(n))
        .sort()) {
        await pool.query(await readFile(resolve("migrations", name), "utf8"));
      }
      assert.equal(
        (
          await pool.query(
            "SELECT version FROM gateway_schema_state WHERE singleton=true",
          )
        ).rows[0].version,
        18,
      );
      const store = new PostgresWebPushStore(pool);
      const seed = async () => {
        const accountId = randomUUID(),
          installationId = randomUUID(),
          sessionId = randomUUID();
        await pool!.query("INSERT INTO accounts (id) VALUES ($1)", [accountId]);
        await pool!.query(
          `INSERT INTO installations (id,account_id,client_installation_id,kind,platform,display_name,app_version)
        VALUES ($1,$2,$3,'browser','web','Test browser','test')`,
          [installationId, accountId, randomUUID()],
        );
        const family = randomUUID();
        await pool!.query(
          `INSERT INTO account_sessions (id,account_id,installation_id,refresh_family_id,access_token_hash,access_expires_at)
        VALUES ($1,$2,$3,$4,$5,now()+interval '15 minutes')`,
          [
            sessionId,
            accountId,
            installationId,
            family,
            randomBytes(32).toString("hex"),
          ],
        );
        await pool!.query(
          `INSERT INTO refresh_tokens (id,session_id,family_id,token_hash,expires_at)
        VALUES ($1,$2,$3,$4,now()+interval '30 days')`,
          [randomUUID(), sessionId, family, randomBytes(32).toString("hex")],
        );
        return {
          account: { id: accountId },
          installation: {
            id: installationId,
            kind: "browser",
            platform: "web",
          },
          sessionId,
        } as AccountPrincipal;
      };
      const owner = await seed(),
        shared = await seed(),
        unrelated = await seed();
      const desktopId = randomUUID(),
        bindingId = randomUUID();
      await pool.query(
        `INSERT INTO installations (id,account_id,client_installation_id,kind,platform,display_name,app_version)
      VALUES ($1,$2,$3,'desktop','macos','Mac','test')`,
        [desktopId, owner.account.id, randomUUID()],
      );
      await pool.query(
        `INSERT INTO connector_bindings
      (id,account_id,desktop_installation_id,display_name,device_id,public_key,key_algorithm,public_key_fingerprint,generation,status,activated_at)
      VALUES ($1,$2,$3,'Mac','mac-test',$4,'Ed25519',$5,1,'active',now())`,
        [
          bindingId,
          owner.account.id,
          desktopId,
          Buffer.alloc(32, 7),
          "7".repeat(64),
        ],
      );
      const grantId = randomUUID();
      await pool.query(
        `INSERT INTO device_access_grants (id,binding_id,owner_account_id,grantee_account_id,grantee_email_hint)
      VALUES ($1,$2,$3,$4,'shared@example.invalid')`,
        [grantId, bindingId, owner.account.id, shared.account.id],
      );
      const subscription = {
        endpoint: "https://web.push.apple.com/test",
        keys: {
          p256dh: webpush.generateVAPIDKeys().publicKey,
          auth: randomBytes(16).toString("base64url"),
        },
      };
      for (const p of [owner, shared, unrelated])
        await store.put(
          p,
          { ...subscription, endpoint: subscription.endpoint + p.account.id },
          randomUUID(),
          "zh",
        );
      const targets = async () =>
        (await store.targets("mac-test")).map((t) => t.accountId).sort();
      assert.deepEqual(
        await targets(),
        [owner.account.id, shared.account.id].sort(),
      );
      // Access-cookie expiry alone must not disable background notification delivery.
      await pool.query(
        "UPDATE account_sessions SET access_expires_at=now()-interval '1 minute' WHERE id=$1",
        [owner.sessionId],
      );
      assert.deepEqual(
        await targets(),
        [owner.account.id, shared.account.id].sort(),
      );
      await pool.query(
        "UPDATE device_access_grants SET status='revoked',revoked_at=now() WHERE id=$1",
        [grantId],
      );
      assert.deepEqual(await targets(), [owner.account.id]);
      await pool.query(
        "UPDATE account_sessions SET revoked_at=now() WHERE id=$1",
        [owner.sessionId],
      );
      assert.deepEqual(await targets(), []);
      assert.equal(await store.get(owner), null);
      await assert.rejects(() =>
        store.put(owner, subscription, randomUUID(), "zh"),
      );
      await pool.query(
        "UPDATE installations SET revoked_at=now() WHERE id=$1",
        [shared.installation.id],
      );
      assert.equal(await store.get(shared), null);
      const old = await store.targets("missing-device");
      assert.deepEqual(old, []);
      const oldChannel = (await store.get(unrelated))!.channelId;
      const newChannel = randomUUID();
      await store.put(unrelated, subscription, newChannel, "en");
      await store.drop({
        installationId: unrelated.installation.id,
        accountId: unrelated.account.id,
        channelId: oldChannel,
        language: "zh",
        subscription,
      });
      assert.deepEqual(await store.get(unrelated), { channelId: newChannel });
      await store.remove(unrelated);
      assert.equal(await store.get(unrelated), null);
    } finally {
      await pool?.end();
      await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);
      await admin.end();
    }
  },
);
