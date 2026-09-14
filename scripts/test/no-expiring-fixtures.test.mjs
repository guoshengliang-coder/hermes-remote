import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';

/**
 * Guards against a fixture that works until a particular instant and then fails every build.
 *
 * The gateway schema puts `CHECK (expires_at > created_at)` on `device_share_invitations` and
 * `email_otp_challenges`, and `created_at` defaults to `now()`. An INSERT that hardcodes an
 * absolute `expires_at` but lets `created_at` default is therefore valid only until the wall
 * clock reaches that timestamp — after which it violates the constraint on every machine, for
 * ever. One did exactly that on 2026-09-11 at 08:00 UTC: `main` was green at 06:59 and every
 * branch went red at 08:51, which reads like "my change broke it" and is nothing of the sort.
 *
 * Two ways to be safe, both used in the suite already: supply `created_at` explicitly so the row
 * is self-consistent, or derive the dates from `now()` / `Date.now()`.
 */

const GATEWAY_SRC = new URL('../../gateway/src/', import.meta.url).pathname;
const GUARDED_TABLES = ['device_share_invitations', 'email_otp_challenges'];

/** Every `INSERT INTO <table> (cols) … );` statement in a file, with its line number. */
function inserts(source, table) {
  const re = new RegExp(String.raw`INSERT INTO ${table}\s*\(([^)]*)\)([\s\S]{0,2000}?)\);`, 'g');
  const out = [];
  for (const m of source.matchAll(re)) {
    out.push({
      columns: m[1].replace(/\s+/g, ' ').trim(),
      body: m[2],
      line: source.slice(0, m.index).split('\n').length,
    });
  }
  return out;
}

const ABSOLUTE_DATE = /new Date\("(\d{4}-\d{2}-\d{2}T[\d:.]+Z)"\)/g;
const RELATIVE = /now\(\)|Date\.now\(\)/;

test('no fixture hardcodes expires_at while letting created_at default to now()', () => {
  const files = readdirSync(GATEWAY_SRC).filter((f) => f.endsWith('.ts'));
  const offenders = [];
  for (const file of files) {
    const source = readFileSync(join(GATEWAY_SRC, file), 'utf8');
    for (const table of GUARDED_TABLES) {
      if (!source.includes(`INSERT INTO ${table}`)) continue;
      for (const stmt of inserts(source, table)) {
        if (!stmt.columns.includes('expires_at')) continue;
        if (stmt.columns.includes('created_at')) continue;
        if (RELATIVE.test(stmt.body)) continue;
        const dates = [...stmt.body.matchAll(ABSOLUTE_DATE)].map((d) => d[1]);
        if (dates.length > 0) {
          offenders.push(`${file}:${stmt.line} inserts into ${table} with absolute dates ` +
            `${dates.join(', ')} and no created_at — it expires when the clock passes expires_at`);
        }
      }
    }
  }
  assert.deepEqual(offenders, [], `\n  ${offenders.join('\n  ')}\n`);
});

test('the guard actually catches the shape it is meant to catch', () => {
  // The exact statement that went off on 2026-09-11, so a refactor of the matcher cannot quietly
  // stop matching it.
  const sample = `
    await pool.query(
      \`INSERT INTO device_share_invitations
         (id, binding_id, owner_account_id, target_email_lookup_hash, target_email_hint,
          token_hash, expires_at, delivery_status, delivered_at, provider_message_id)
       VALUES ($1, $2, $3, $4, 'g***@example.com', $5, $6, 'sent', $7, $8)\`,
      [id, b, o, "d", "e", new Date("2026-09-11T08:00:00.000Z"), new Date("2026-09-08T08:00:00.000Z"), "p"],
    );`;
  const found = inserts(sample, 'device_share_invitations');
  assert.equal(found.length, 1);
  assert.ok(found[0].columns.includes('expires_at'));
  assert.ok(!found[0].columns.includes('created_at'));
  assert.ok(!RELATIVE.test(found[0].body));
  assert.equal([...found[0].body.matchAll(ABSOLUTE_DATE)].length, 2);
});

test('supplying created_at, or deriving from now(), both pass', () => {
  const withCreatedAt = `
    \`INSERT INTO device_share_invitations (id, expires_at, created_at)
       VALUES ($1, $2, $3)\`, [id, new Date("2026-09-11T08:00:00.000Z"), new Date("2026-09-08T07:00:00.000Z")],
    );`;
  const stmt = inserts(withCreatedAt, 'device_share_invitations')[0];
  assert.ok(stmt.columns.includes('created_at'));

  const relative = `
    \`INSERT INTO device_share_invitations (id, expires_at)
       VALUES ($1, now() + interval '7 days')\`, [id],
    );`;
  assert.ok(RELATIVE.test(inserts(relative, 'device_share_invitations')[0].body));
});
