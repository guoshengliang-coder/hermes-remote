import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {link, mkdir, mkdtemp, readdir, readFile, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {publishRelease} from '../../deploy/publish-release.mjs';
import {MAX_VERSIONS, validateIndex} from '../src/schema.mjs';

const CERT = '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5';
const sha256 = async file => createHash('sha256').update(await readFile(file)).digest('hex');
const version = (name, code, extra = {}) => ({
  schemaVersion: 1, channel: 'internal', versionName: name, versionCode: code,
  applicationId: 'com.hermes.remote', publishedAt: '2026-01-01T00:00:00Z',
  fileName: `Hermes-Remote-${name}-debug.apk`,
  downloadUrl: `https://mrlgs.net/releases/Hermes-Remote-${name}-debug.apk`,
  sizeBytes: 3, sha256: 'a'.repeat(64), certificateSha256: CERT, minSdk: 26,
  releaseNotes: ['note'], sourceCommit: 'abc', ...extra,
});

async function setup() {
  const root = await mkdtemp(path.join(tmpdir(), 'publish-durability-'));
  const apk = path.join(root, 'incoming.apk');
  await writeFile(apk, 'one');
  return {root, apk, meta: version('1.0.0', 1, {sha256: await sha256(apk)})};
}

async function payload(root, name) {
  const file = path.join(root, `${name}.apk`);
  await writeFile(file, name);
  return {file, sha256: await sha256(file), sizeBytes: name.length};
}

const indexOf = async root => validateIndex(JSON.parse(await readFile(path.join(root, 'index.json'), 'utf8')));
const rollbackOf = async root => validateIndex(JSON.parse(await readFile(path.join(root, 'index.json.prev'), 'utf8')));

test('index.json.prev is replaced atomically instead of truncated in place', async () => {
  const s = await setup();
  await publishRelease(s.root, s.apk, s.meta);
  const two = await payload(s.root, 'two');
  await publishRelease(s.root, two.file, version('1.0.1', 2, {sizeBytes: two.sizeBytes, sha256: two.sha256}));
  // A hard link pins the inode that currently holds the rollback index. A publisher that
  // truncates and rewrites index.json.prev in place destroys this reader's file mid-write;
  // a temp+fsync+rename replacement leaves it complete and valid.
  const canary = path.join(s.root, 'canary.json');
  await link(path.join(s.root, 'index.json.prev'), canary);
  const three = await payload(s.root, 'three');
  await publishRelease(s.root, three.file, version('1.0.2', 3, {sizeBytes: three.sizeBytes, sha256: three.sha256}));

  assert.deepEqual((await indexOf(s.root)).versions.map(v => v.versionCode), [3, 2, 1]);
  assert.deepEqual((await rollbackOf(s.root)).versions.map(v => v.versionCode), [2, 1]);
  const preserved = validateIndex(JSON.parse(await readFile(canary, 'utf8')));
  assert.deepEqual(preserved.versions.map(v => v.versionCode), [1], 'the previous rollback index was rewritten in place');
});

test('a failed transaction leaves index.json and index.json.prev as valid rollback points', async () => {
  const s = await setup();
  await publishRelease(s.root, s.apk, s.meta);
  const two = await payload(s.root, 'two');
  await publishRelease(s.root, two.file, version('1.0.1', 2, {sizeBytes: two.sizeBytes, sha256: two.sha256}));
  const before = await readFile(path.join(s.root, 'index.json'), 'utf8');
  const previousBefore = await readFile(path.join(s.root, 'index.json.prev'), 'utf8');

  // Blocking the APK's final rename aborts the transaction after validation, standing in for a
  // publisher that dies part way through the data-root mutation.
  const three = await payload(s.root, 'three');
  await mkdir(path.join(s.root, 'Hermes-Remote-1.0.2-debug.apk'));
  await assert.rejects(publishRelease(s.root, three.file, version('1.0.2', 3, {sizeBytes: three.sizeBytes, sha256: three.sha256})));

  assert.equal(await readFile(path.join(s.root, 'index.json'), 'utf8'), before);
  assert.equal(await readFile(path.join(s.root, 'index.json.prev'), 'utf8'), previousBefore);
  assert.deepEqual((await indexOf(s.root)).versions.map(v => v.versionCode), [2, 1]);
  assert.deepEqual((await rollbackOf(s.root)).versions.map(v => v.versionCode), [1]);
  const leftovers = (await readdir(s.root)).filter(name => name.includes('.tmp-'));
  assert.deepEqual(leftovers, []);
});

test('a full catalog fails before mutating the data root and keeps the current release', async () => {
  const s = await setup();
  const versions = Array.from({length: MAX_VERSIONS}, (_, i) => version(`2.0.${MAX_VERSIONS - i}`, MAX_VERSIONS - i));
  const full = {schemaVersion: 1, channel: 'internal', latestVersionCode: MAX_VERSIONS, generatedAt: '2026-01-01T00:00:00Z', versions};
  await writeFile(path.join(s.root, 'index.json'), `${JSON.stringify(validateIndex(full), null, 2)}\n`);
  const before = await readFile(path.join(s.root, 'index.json'), 'utf8');
  const next = await payload(s.root, 'next');

  await assert.rejects(
    publishRelease(s.root, next.file, version('3.0.0', MAX_VERSIONS + 1, {sizeBytes: next.sizeBytes, sha256: next.sha256})),
    /release catalog is full/,
  );
  assert.equal(await readFile(path.join(s.root, 'index.json'), 'utf8'), before);
  assert.deepEqual((await indexOf(s.root)).versions[0].versionCode, MAX_VERSIONS);
  assert.equal((await readdir(s.root)).includes('Hermes-Remote-3.0.0-debug.apk'), false);
});

test('a nearly full catalog warns the operator before it fails closed', async () => {
  const s = await setup();
  const used = MAX_VERSIONS - 9;
  const versions = Array.from({length: used}, (_, i) => version(`2.0.${used - i}`, used - i));
  const nearly = {schemaVersion: 1, channel: 'internal', latestVersionCode: used, generatedAt: '2026-01-01T00:00:00Z', versions};
  await writeFile(path.join(s.root, 'index.json'), `${JSON.stringify(validateIndex(nearly), null, 2)}\n`);
  const next = await payload(s.root, 'next');
  const warnings = [];
  const original = console.warn;
  console.warn = message => warnings.push(String(message));
  try {
    await publishRelease(s.root, next.file, version('3.0.0', used + 1, {sizeBytes: next.sizeBytes, sha256: next.sha256}));
  } finally {
    console.warn = original;
  }
  assert.equal((await indexOf(s.root)).versions.length, used + 1);
  assert.equal(warnings.some(line => /catalog slots/.test(line) && line.includes(`${used + 1}/${MAX_VERSIONS}`)), true, warnings.join('\n'));
});
