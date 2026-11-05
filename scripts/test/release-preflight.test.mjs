import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {INPUT_PROFILES, checkAndroidCatalog, checkInputs, fetchAndroidCatalog, preflightError, runPreflight} from '../release-preflight.mjs';
import {MAX_INDEX_BYTES, MAX_VERSIONS} from '../../release-server/src/schema.mjs';

const version = {versionName: '0.1.201', versionCode: 202};
const catalog = (count = 1) => ({
  schemaVersion: 1, channel: 'internal', generatedAt: '2026-11-05T00:00:00Z',
  latestVersionCode: count ? 201 : 0,
  versions: Array.from({length: count}, (_, i) => ({
    schemaVersion: 1, channel: 'internal', applicationId: 'com.hermes.remote',
    versionName: `0.1.${200 - i}`, versionCode: 201 - i,
    publishedAt: '2026-11-01T00:00:00Z', fileName: `Hermes-Remote-0.1.${200 - i}-debug.apk`,
    downloadUrl: `https://mrlgs.net/releases/Hermes-Remote-0.1.${200 - i}-debug.apk`,
    sha256: 'a'.repeat(64), sizeBytes: 1234, minSdk: 26, sourceCommit: 'b'.repeat(40), releaseNotes: ['测试'],
    certificateSha256: '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5',
  })),
});
const flags = profile => Object.fromEntries(INPUT_PROFILES[profile].map(name => [`HAS_${name}`, 'true']));
const reason = expected => error => error.releasePreflight?.technicalCause === expected;

test('every required input rejects absent, false or accidental raw-secret flags', () => {
  for (const profile of Object.keys(INPUT_PROFILES)) {
    checkInputs(profile, flags(profile));
    for (const name of INPUT_PROFILES[profile]) {
      for (const value of [undefined, 'false', 'synthetic-private-key']) {
        assert.throws(() => checkInputs(profile, {...flags(profile), [`HAS_${name}`]: value}), error => {
          assert.equal(error.releasePreflight.technicalCause, 'inputs_missing');
          assert.deepEqual(error.releasePreflight.missingInputs, [name]);
          assert.doesNotMatch(JSON.stringify(error.releasePreflight), /synthetic-private-key/);
          return true;
        });
      }
    }
  }
  assert.throws(() => checkInputs('private-untrusted-profile', {}), reason('invalid_profile'));
  assert.throws(() => checkInputs('__proto__', {}), reason('invalid_profile'));
});

test('Android preflight rejects duplicate names/codes, rollbacks, invalid catalogs and full catalogs', () => {
  checkAndroidCatalog(version, catalog());
  checkAndroidCatalog(version, catalog(0));
  for (const duplicate of [{...version, versionCode: 201}, {...version, versionName: '0.1.200'}]) {
    assert.throws(() => checkAndroidCatalog(duplicate, catalog()), reason('version_taken'));
  }
  assert.throws(() => checkAndroidCatalog({...version, versionCode: 100}, catalog()), reason('version_not_newer'));
  assert.throws(() => checkAndroidCatalog(version, catalog(MAX_VERSIONS)), reason('catalog_full'));
  for (const bad of [null, {}, {...catalog(), latestVersionCode: 999}, catalog(MAX_VERSIONS + 1)]) {
    assert.throws(() => checkAndroidCatalog(version, bad), reason('catalog_invalid'));
  }
});

test('catalog requests use only the fixed public endpoint, no credentials or redirects, and a bounded deadline', async () => {
  const index = catalog();
  const actual = await fetchAndroidCatalog(async (url, options) => {
    assert.equal(url, 'https://mrlgs.net/releases/index.json');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.redirect, 'error');
    assert.equal(options.cache, 'no-store');
    assert.ok(options.signal instanceof AbortSignal);
    assert.equal(options.signal.aborted, false);
    assert.equal(options.headers, undefined);
    return new Response(JSON.stringify(index));
  });
  assert.deepEqual(actual, index);
  for (const response of [new Response('private HTTP body', {status: 503}), new Response('not JSON')]) {
    await assert.rejects(fetchAndroidCatalog(async () => response), error => {
      assert.match(error.releasePreflight.technicalCause, /^catalog_(invalid|unavailable)$/);
      assert.doesNotMatch(JSON.stringify(error.releasePreflight), /private HTTP body|not JSON/);
      return true;
    });
  }
  await assert.rejects(fetchAndroidCatalog(async () => { throw new DOMException('private timeout context', 'TimeoutError'); }), reason('catalog_unavailable'));
});

test('oversized catalog streaming is cancelled before parsing', async () => {
  let cancelled = false;
  const stream = new ReadableStream({
    pull(controller) { controller.enqueue(new Uint8Array(MAX_INDEX_BYTES + 1)); },
    cancel() { cancelled = true; },
  });
  await assert.rejects(fetchAndroidCatalog(async () => new Response(stream)), reason('catalog_too_large'));
  assert.equal(cancelled, true);
});

test('the fifteen-second deadline aborts requests without exposing timeout diagnostics', async t => {
  t.mock.method(AbortSignal, 'timeout', milliseconds => {
    assert.equal(milliseconds, 15_000);
    return AbortSignal.abort(new Error('private network diagnostic'));
  });
  await assert.rejects(fetchAndroidCatalog(async (_url, options) => {
    options.signal.throwIfAborted();
  }), error => {
    assert.equal(error.releasePreflight.technicalCause, 'catalog_unavailable');
    assert.doesNotMatch(JSON.stringify(error.releasePreflight), /private network diagnostic/);
    return true;
  });
});

test('missing input and invalid local release notes stop before any public request', async t => {
  const root = await mkdtemp(path.join(tmpdir(), 'release-preflight-'));
  t.after(() => rm(root, {recursive: true, force: true}));
  const fetchImpl = async () => { assert.fail('preflight should stop before the network'); };
  await assert.rejects(runPreflight('android', {env: {}, root, fetchImpl}), reason('inputs_missing'));
  await mkdir(path.join(root, 'android/app'), {recursive: true});
  await mkdir(path.join(root, 'android/releases'));
  await writeFile(path.join(root, 'android/app/build.gradle.kts'), 'val appVersionName = "0.1.201"\nval appVersionCode = 202\n');
  const notesPath = path.join(root, 'android/releases/0.1.201.json');
  for (const releaseNotes of [[], 'not an array', ['\nprivate'], [' '], [123], Array(21).fill('note')]) {
    await writeFile(notesPath, JSON.stringify({channel: 'internal', releaseNotes}));
    await assert.rejects(runPreflight('android', {env: flags('android'), root, fetchImpl}), reason('android_metadata_invalid'));
  }
  await writeFile(notesPath, JSON.stringify({channel: 'internal', releaseNotes: ['reviewed']}));
  await runPreflight('android', {env: flags('android'), root, fetchImpl: async () => new Response(JSON.stringify(catalog()))});
});

test('preflight error is registered, bilingual, retryable, serializable and contains only safe diagnostics', async () => {
  const error = preflightError('password=secretlowercase', ['unknown-secret', 'SIGNING_PRIVATE_KEY']);
  assert.equal(error.code, 'HR-RELEASE-008');
  assert.equal(error.technicalCause, 'redacted');
  assert.equal(error.retryable, true);
  assert.match(error.summaryZh, /发布前置检查/);
  assert.match(error.summaryEn, /^Release preflight failed/);
  assert.match(error.recoveryAction, /retry/);
  assert.deepEqual(error.missingInputs, ['SIGNING_PRIVATE_KEY']);
  assert.doesNotMatch(JSON.stringify(error), /secretlowercase|unknown-secret/);
  assert.deepEqual(JSON.parse(JSON.stringify(error)), error);
  const registry = await readFile('docs/ERROR_HANDLING.md', 'utf8');
  for (const text of [error.code, error.summaryZh, error.summaryEn]) assert.ok(registry.includes(text));
  const result = spawnSync(process.execPath, ['scripts/release-preflight.mjs', 'desktop-components'], {
    env: {...process.env, HAS_SIGNING_PRIVATE_KEY: 'synthetic-private-key'}, encoding: 'utf8',
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /HR-RELEASE-008/);
  assert.doesNotMatch(result.stdout + result.stderr, /synthetic-private-key|RELEASE_PREFLIGHT_OK/);
  const success = spawnSync(process.execPath, ['scripts/release-preflight.mjs', 'desktop-components'], {
    env: {...process.env, ...flags('desktop-components')}, encoding: 'utf8',
  });
  assert.equal(success.status, 0, success.stderr);
  assert.match(success.stdout, /RELEASE_PREFLIGHT_OK profile=desktop-components/);
});

test('protected workflows reject missing inputs before dependency installation, tests and packaging', async () => {
  for (const [filename, profile] of [['android-release.yml', 'android'], ['desktop-app-release.yml', 'desktop-app'], ['desktop-managed-release-package.yml', 'desktop-components']]) {
    const workflow = await readFile(`.github/workflows/${filename}`, 'utf8');
    const start = workflow.indexOf('      - name: Check release prerequisites before building');
    const command = `run: node scripts/release-preflight.mjs ${profile}`;
    const end = workflow.indexOf(command, start) + command.length;
    assert.ok(start > 0 && end > start);
    const block = workflow.slice(start, end);
    assert.ok(end < workflow.indexOf('npm ci'), filename);
    assert.equal(block.match(/secrets\./g)?.length, profile === 'android' ? 5 : profile === 'desktop-app' ? 7 : 1);
    for (const name of INPUT_PROFILES[profile]) {
      assert.match(block, new RegExp(`HAS_${name}: \\$\\{\\{ (secrets|vars)\\.\\w+ != '' \\}\\}`));
    }
  }
});
