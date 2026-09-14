import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, readFile, readdir, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {ARCHIVE_DIR, publishRelease, retireReleases} from '../../deploy/publish-release.mjs';
import {MAX_VERSIONS} from '../src/schema.mjs';

const sha = text => createHash('sha256').update(text).digest('hex');

/** A catalog of [count] published versions, codes 1..count. */
async function catalog(count) {
  const root = await mkdtemp(path.join(tmpdir(), 'retire-'));
  for (let code = 1; code <= count; code += 1) {
    const name = `1.0.${code}`;
    const apk = path.join(root, `incoming-${code}.apk`);
    const body = `apk-${code}`;
    await writeFile(apk, body);
    await publishRelease(root, apk, {
      schemaVersion: 1, channel: 'internal', versionName: name, versionCode: code,
      applicationId: 'com.hermes.remote', publishedAt: '2026-01-01T00:00:00Z',
      fileName: `Hermes-Remote-${name}-debug.apk`,
      downloadUrl: `https://mrlgs.net/releases/Hermes-Remote-${name}-debug.apk`,
      sizeBytes: body.length, sha256: sha(body),
      certificateSha256: '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',
      minSdk: 26, releaseNotes: [`notes ${code}`], sourceCommit: 'abc',
    });
  }
  return root;
}

const index = async root => JSON.parse(await readFile(path.join(root, 'index.json'), 'utf8'));

test('keeps the newest N, and every retired APK is archived rather than deleted', async () => {
  const root = await catalog(14);
  const result = await retireReleases(root, {keep: 10});
  assert.equal(result.changed, true);
  assert.equal(result.kept, 10);
  assert.deepEqual(result.retired, ['1.0.4', '1.0.3', '1.0.2', '1.0.1']);

  const after = await index(root);
  assert.deepEqual(after.versions.map(v => v.versionCode), [14, 13, 12, 11, 10, 9, 8, 7, 6, 5]);
  assert.equal(after.latestVersionCode, 14);

  const archived = await readdir(path.join(root, ARCHIVE_DIR));
  for (const name of ['1.0.1', '1.0.2', '1.0.3', '1.0.4']) {
    assert.ok(archived.includes(`Hermes-Remote-${name}-debug.apk`), `${name} not archived`);
  }
  // The kept ones stay exactly where the index points.
  const served = await readdir(root);
  assert.ok(served.includes('Hermes-Remote-1.0.14-debug.apk'));
  assert.ok(!served.includes('Hermes-Remote-1.0.1-debug.apk'));
});

test('every surviving entry still resolves to a file on the served path', async () => {
  const root = await catalog(12);
  await retireReleases(root, {keep: 5});
  const served = new Set(await readdir(root));
  for (const version of (await index(root)).versions) {
    assert.ok(served.has(version.fileName), `${version.versionName} has no file`);
  }
});

test('the previous index is left as a rollback point', async () => {
  const root = await catalog(12);
  await retireReleases(root, {keep: 5});
  const previous = JSON.parse(await readFile(path.join(root, 'index.json.prev'), 'utf8'));
  assert.equal(previous.versions.length, 12);
});

test('idempotent: a second pass with the same keep changes nothing', async () => {
  const root = await catalog(12);
  await retireReleases(root, {keep: 5});
  const before = await readFile(path.join(root, 'index.json'), 'utf8');
  const again = await retireReleases(root, {keep: 5});
  assert.equal(again.changed, false);
  assert.deepEqual(again.retired, []);
  assert.equal(await readFile(path.join(root, 'index.json'), 'utf8'), before);
});

test('dry run reports what would go and touches nothing', async () => {
  const root = await catalog(12);
  const before = await readFile(path.join(root, 'index.json'), 'utf8');
  const result = await retireReleases(root, {keep: 5, dryRun: true});
  assert.equal(result.changed, false);
  assert.equal(result.retired.length, 7);
  assert.equal(await readFile(path.join(root, 'index.json'), 'utf8'), before);
  await assert.rejects(readdir(path.join(root, ARCHIVE_DIR)));
});

test('keep must be a positive integer, so keep=0 can never revoke the current release', async () => {
  const root = await catalog(3);
  for (const keep of [0, -1, 1.5, undefined, 'all']) {
    await assert.rejects(retireReleases(root, {keep}), /keep must be a positive integer/);
  }
  assert.equal((await index(root)).versions.length, 3);
});

test('retiring frees the slots a full catalog needs to publish again', async () => {
  const root = await catalog(MAX_VERSIONS);
  const apk = path.join(root, 'next.apk');
  const body = 'next';
  await writeFile(apk, body);
  const next = {
    schemaVersion: 1, channel: 'internal', versionName: '2.0.0', versionCode: MAX_VERSIONS + 1,
    applicationId: 'com.hermes.remote', publishedAt: '2026-01-02T00:00:00Z',
    fileName: 'Hermes-Remote-2.0.0-debug.apk',
    downloadUrl: 'https://mrlgs.net/releases/Hermes-Remote-2.0.0-debug.apk',
    sizeBytes: body.length, sha256: sha(body),
    certificateSha256: '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',
    minSdk: 26, releaseNotes: ['next'], sourceCommit: 'def',
  };
  await assert.rejects(publishRelease(root, apk, next), /release catalog is full/);
  await retireReleases(root, {keep: 10});
  await publishRelease(root, apk, next);
  const after = await index(root);
  assert.equal(after.versions.length, 11);
  assert.equal(after.latestVersionCode, MAX_VERSIONS + 1);
});
