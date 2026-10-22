import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, readFile, mkdir, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInternalIndex, createAppUpdateIndex, DESKTOP_APP_INDEX, verifyPublicDmg, publishInternalIndex, remoteTransaction } from '../src/internal-publication.mjs';
import { DESKTOP_INTERNAL_INDEX } from '../src/releases.mjs';

const data = Buffer.from('test artifact bytes');
const receipt = { verified: true, notarized: false, channel: 'internal-ad-hoc',
  stage: 'internal-test-published-and-public-readback-verified', version: '0.2.32', build: 35,
  url: 'https://mrlgs.net/desktop/apps/0.2.32/Hermes-Go-Desktop-0.2.32-dev.dmg',
  sourceCommit: 'a'.repeat(40), sizeBytes: data.length, sha256: createHash('sha256').update(data).digest('hex'),
  verifiedAt: '2026-10-12T13:56:00.123Z' };
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'desktop-internal-index-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dmg = join(dir, 'Hermes-Go-Desktop-0.2.32-dev.dmg'); await writeFile(dmg, data);
  const args = { receipt, dmg, notes: ['改善 Mac 连接恢复。'] };
  return { args, index: await createInternalIndex(args) };
}

test('only verified publication receipts with exact local artifact bytes can create a test pointer', async t => {
  const { args, index } = await fixture(t);
  assert.equal(index.distribution, 'ad-hoc'); assert.equal(index.notarized, false);
  assert.equal(index.updatedAt, '2026-10-12T13:56:00Z'); assert.equal(index.sourceCommit, receipt.sourceCommit);
  for (const change of [{ verified: false }, { notarized: true }, { stage: 'candidate' },
    { channel: 'official' }, { sizeBytes: data.length + 1 }, { sha256: 'b'.repeat(64) },
    { sourceCommit: 'unknown' }, { url: receipt.url.replace('-dev.dmg', '.dmg') }]) {
    await assert.rejects(createInternalIndex({ ...args, receipt: { ...receipt, ...change } }));
  }
  await writeFile(args.dmg, 'changed artifact');
  await assert.rejects(createInternalIndex(args));
});

test('public DMG verification rejects truncation, excess bytes, different bytes, HTTP failures and redirects', async t => {
  const { index } = await fixture(t);
  await verifyPublicDmg(index, async (_url, options) => { assert.equal(options.redirect, 'error'); return new Response(data); });
  for (const response of [new Response(data.subarray(1)), new Response(Buffer.concat([data, data])),
    new Response(Buffer.alloc(data.length)), new Response(null, { status: 404 }), new Response(null, { status: 302 })]) {
    await assert.rejects(verifyPublicDmg(index, async () => response));
  }
});

test('test pointer switches only after DMG readback and rolls back failed pointer readback', async t => {
  const { index } = await fixture(t);
  const actions = []; let published = false;
  const fetcher = async url => {
    if (url === index.downloadURL) { assert.equal(published, false); return new Response(data); }
    assert.equal(url, DESKTOP_INTERNAL_INDEX); assert.equal(published, true);
    return new Response('bad pointer');
  };
  const executeRemote = async action => { actions.push(action); if (action === 'publish') published = true; };
  await assert.rejects(publishInternalIndex(index, { fetcher, executeRemote }));
  assert.deepEqual(actions, ['publish', 'rollback']);
  actions.length = 0;
  await assert.rejects(publishInternalIndex(index, { fetcher: async () => new Response('bad DMG'), executeRemote }));
  assert.deepEqual(actions, []);
  await publishInternalIndex(index, { executeRemote, fetcher: async url => new Response(url === index.downloadURL ? data : `${JSON.stringify(index)}\n`) });
  assert.deepEqual(actions, ['publish', 'finish']);
});

test('actual filesystem transaction preserves other channels and refuses downgrades or concurrent rollback', async t => {
  const { index } = await fixture(t);
  const root = await mkdtemp(join(tmpdir(), 'desktop-internal-transaction-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  // Exercise the same transaction against an isolated filesystem, without SSH or sudo.
  const source = remoteTransaction.replace("'/srv/hermes-desktop-apps'", JSON.stringify(root));
  const run = (action, value, id) => execFileSync('python3', ['-c', source, action,
    Buffer.from(`${JSON.stringify(value)}\n`).toString('base64'), id], { stdio: 'pipe' });
  async function stage(value) {
    await mkdir(join(root, value.appVersion), { recursive: true });
    await writeFile(join(root, value.appVersion, `Hermes-Go-Desktop-${value.appVersion}-dev.dmg`), data);
  }
  await writeFile(join(root, 'index.json'), 'official pointer');
  await stage(index);
  run('publish', index, 'first');
  assert.deepEqual(JSON.parse(await readFile(join(root, 'internal-index.json'), 'utf8')), index);
  run('rollback', index, 'first');
  await assert.rejects(readFile(join(root, 'internal-index.json')));
  run('publish', index, 'second'); run('finish', index, 'second');
  assert.throws(() => run('publish', { ...index, releaseNotes: ['same-version replacement'] }, 'replacement'));
  const newer = { ...index, appVersion: '0.2.33', buildNumber: 36,
    downloadURL: 'https://mrlgs.net/desktop/apps/0.2.33/Hermes-Go-Desktop-0.2.33-dev.dmg' };
  await stage(newer); run('publish', newer, 'newer');
  assert.throws(() => run('publish', index, 'downgrade'));
  assert.throws(() => run('rollback', index, 'second'));
  assert.equal(JSON.parse(await readFile(join(root, 'internal-index.json'), 'utf8')).appVersion, '0.2.33');
  run('rollback', newer, 'newer');
  assert.equal(JSON.parse(await readFile(join(root, 'internal-index.json'), 'utf8')).appVersion, '0.2.32');
  assert.equal(await readFile(join(root, 'index.json'), 'utf8'), 'official pointer');
});

test('legacy Desktop discovery uses the same verified bytes and exact legacy schema', async () => {
  const internal = JSON.parse(await readFile(new URL('./fixtures/desktop-internal-index.json', import.meta.url)));
  const expected = JSON.parse(await readFile(new URL('./fixtures/desktop-app-index.json', import.meta.url)));
  assert.deepEqual(createAppUpdateIndex(internal), expected);
  assert.deepEqual(createAppUpdateIndex({ ...internal, futureWebsiteField: 'ignored by legacy schema' }), expected);
  assert.equal(expected.sha256, internal.sha256);
  assert.equal(expected.sizeBytes, internal.sizeBytes);
  assert.match(expected.releaseNotes.join(' '), /临时签名/);
  assert.equal(Object.hasOwn(expected, 'notarized'), false);
});

test('both public files are verified before switching; either pointer readback failure rolls back both', async t => {
  const { index } = await fixture(t);
  for (const failure of [index.downloadURL.replace('-dev.dmg', '.dmg'), DESKTOP_INTERNAL_INDEX, DESKTOP_APP_INDEX]) {
    const actions = [];
    const fetcher = async url => new Response(url === failure ? 'wrong bytes' : url.endsWith('.dmg') ? data
      : `${JSON.stringify(url === DESKTOP_APP_INDEX ? createAppUpdateIndex(index) : index)}\n`);
    await assert.rejects(publishInternalIndex(index, { syncAppUpdate: true, fetcher,
      executeRemote: async action => actions.push(action) }));
    assert.deepEqual(actions, failure.endsWith('.dmg') ? [] : ['publish', 'rollback']);
  }
});

test('paired pointer transaction restores absent/existing indexes, refuses conflicting channels and concurrent rollback', async t => {
  const { index } = await fixture(t);
  const root = await mkdtemp(join(tmpdir(), 'desktop-paired-pointers-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const source = remoteTransaction.replace("'/srv/hermes-desktop-apps'", JSON.stringify(root));
  const payload = { internalIndex: index, appUpdateIndex: createAppUpdateIndex(index) };
  const run = (action, value = payload, id = 'pair') => execFileSync('python3', ['-c', source, action,
    Buffer.from(`${JSON.stringify(value)}\n`).toString('base64'), id], { stdio: 'pipe' });
  await mkdir(join(root, index.appVersion));
  await writeFile(join(root, index.appVersion, `Hermes-Go-Desktop-${index.appVersion}-dev.dmg`), data);
  run('publish');
  assert.deepEqual(JSON.parse(await readFile(join(root, 'index.json'))), payload.appUpdateIndex);
  run('rollback');
  for (const name of ['index.json', 'internal-index.json']) await assert.rejects(readFile(join(root, name)));
  const olderApp = { ...payload.appUpdateIndex, appVersion: '0.2.31', buildNumber: 34 };
  const oldBytes = `${JSON.stringify(olderApp)}\n`;
  await writeFile(join(root, 'index.json'), oldBytes);
  run('publish', payload, 'existing'); run('rollback', payload, 'existing');
  assert.equal(await readFile(join(root, 'index.json'), 'utf8'), oldBytes);
  await writeFile(join(root, 'index.json'), JSON.stringify({ ...olderApp, channel: 'stable' }));
  assert.throws(() => run('publish', payload, 'other-channel'));
  await assert.rejects(readFile(join(root, 'internal-index.json')));
  await writeFile(join(root, 'index.json'), oldBytes);
  run('publish', payload, 'concurrent');
  await writeFile(join(root, 'index.json'), 'new concurrent pointer');
  assert.throws(() => run('rollback', payload, 'concurrent'));
  assert.deepEqual(JSON.parse(await readFile(join(root, 'internal-index.json'))), index);
});
