import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { prepareInternalDmg, publishInternalDmg, remoteArtifactInstall } from '../src/internal-dmg-publication.mjs';

const bytes = Buffer.from('verified test package');
const receipt = { verified: true, stage: 'internal-test-package-verified', notarized: false,
  channel: 'internal-ad-hoc', version: '0.2.33', build: 36, sizeBytes: bytes.length,
  sha256: createHash('sha256').update(bytes).digest('hex'), sourceCommit: 'a'.repeat(40), verifiedAt: '2026-10-14T10:00:00Z',
  checks: { assets: 'passed', desktopTests: 563, failures: 0, appBuild: 'passed', imageIntegrity: 'passed' } };
async function fixture(t) {
  const dir = await mkdtemp(join(tmpdir(), 'desktop-publish-auto-sync-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const dmg = join(dir, 'Hermes-Go-Desktop-0.2.33-dev.dmg'); await writeFile(dmg, bytes);
  const args = { receipt, dmg, notes: ['改善 Mac 连接。'] };
  return { dir, args, index: await prepareInternalDmg(args) };
}

test('new internal publication requires a complete package gate and exact artifact bytes', async t => {
  const { args, index } = await fixture(t);
  assert.equal(index.appVersion, '0.2.33'); assert.equal(index.notarized, false);
  for (const change of [{ verified: false }, { stage: 'candidate' }, { checks: {} },
    { checks: { ...receipt.checks, failures: 1 } }, { checks: { ...receipt.checks, desktopTests: 0 } },
    { sha256: 'b'.repeat(64) }, { sourceCommit: 'unknown' }]) {
    await assert.rejects(prepareInternalDmg({ ...args, receipt: { ...receipt, ...change } }));
  }
});

test('publishing a DMG automatically synchronizes its website pointer before returning success', async t => {
  const { index } = await fixture(t);
  const events = [];
  const result = await publishInternalDmg(index, {
    installArtifact: async () => events.push('artifact-installed'),
    fetcher: async url => {
      events.push(url === index.downloadURL ? 'public-DMG-readback' : 'public-pointer-readback');
      return new Response(url === index.downloadURL ? bytes : `${JSON.stringify(index)}\n`);
    }, executeRemote: async action => events.push(action),
  });
  assert.deepEqual(events, ['artifact-installed', 'public-DMG-readback', 'publish', 'public-pointer-readback', 'finish']);
  assert.equal(result.websiteSynchronized, true); assert.equal(result.version, '0.2.33'); assert.equal(result.sha256, receipt.sha256);
});

test('failed artifact or public verification never advances the website; failed sync fails the whole publication', async t => {
  const { index } = await fixture(t); const events = [];
  const executeRemote = async action => events.push(action);
  await assert.rejects(publishInternalDmg(index, { installArtifact: async () => { throw new Error('upload failed'); },
    fetcher: async () => { assert.fail('must not fetch'); }, executeRemote }));
  assert.deepEqual(events, []);
  await assert.rejects(publishInternalDmg(index, { installArtifact: async () => {}, fetcher: async () => new Response('bad DMG'), executeRemote }));
  assert.deepEqual(events, []);
  await assert.rejects(publishInternalDmg(index, { installArtifact: async () => {},
    fetcher: async url => new Response(url === index.downloadURL ? bytes : 'bad pointer'), executeRemote }));
  assert.deepEqual(events, ['publish', 'rollback']);
});

test('actual artifact transaction creates only exact routes, refuses immutable overwrite and rolls back invalid routing', async t => {
  const { dir, index } = await fixture(t);
  const root = join(dir, 'store'), stage = join(dir, 'stage'), routes = join(dir, 'routes.conf'), bin = join(dir, 'bin');
  for (const path of [root, stage, bin]) await mkdir(path);
  const originalRoutes = 'location = /desktop/apps/index.json { return 404; }\n';
  await writeFile(routes, originalRoutes);
  await writeFile(join(root, 'index.json'), 'official channel');
  await writeFile(join(stage, 'Hermes-Go-Desktop-0.2.33-dev.dmg'), bytes);
  // Only external commands are simulated. The real file install/lock/route rollback runs here.
  const failure = join(dir, 'fail-once');
  await writeFile(join(bin, 'nginx'), `#!/bin/sh\nif test -e '${failure}'; then rm '${failure}'; exit 1; fi\nexit 0\n`, { mode: 0o755 });
  await writeFile(join(bin, 'systemctl'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  const source = remoteArtifactInstall.replace("'/srv/hermes-desktop-apps'", JSON.stringify(root))
    .replace("'/etc/hermes-go/desktop-release-routes.conf'", JSON.stringify(routes))
    .replaceAll('stat().st_uid == 0', 'stat().st_uid == os.getuid()');
  const run = value => execFileSync('python3', ['-c', source, stage, Buffer.from(JSON.stringify(value)).toString('base64')],
    { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` }, stdio: 'pipe' });
  run(index);
  const publishedRoutes = await readFile(routes, 'utf8');
  assert.ok(publishedRoutes.startsWith(originalRoutes)); assert.match(publishedRoutes, /location = \/desktop\/apps\/0\.2\.33\/Hermes-Go-Desktop-0\.2\.33-dev\.dmg/);
  assert.match(publishedRoutes, /limit_except GET HEAD \{ deny all; \}/);
  run(index); assert.equal(await readFile(routes, 'utf8'), publishedRoutes);
  await writeFile(join(stage, 'Hermes-Go-Desktop-0.2.33-dev.dmg'), Buffer.from('different package'));
  assert.throws(() => run({ ...index, sizeBytes: 17, sha256: createHash('sha256').update('different package').digest('hex') }));
  assert.deepEqual(await readFile(join(root, '0.2.33', 'Hermes-Go-Desktop-0.2.33-dev.dmg')), bytes);
  const next = { ...index, appVersion: '0.2.34' };
  await writeFile(join(stage, 'Hermes-Go-Desktop-0.2.34-dev.dmg'), bytes);
  await writeFile(failure, 'fail nginx -t once');
  assert.throws(() => run(next));
  assert.equal(await readFile(routes, 'utf8'), publishedRoutes);
  assert.equal(await readFile(join(root, 'index.json'), 'utf8'), 'official channel');
});
