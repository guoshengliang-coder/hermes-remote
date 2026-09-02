import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {mkdtemp, readFile, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';

const run = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const BADGING_TOOL = path.join(ROOT, 'scripts', 'lib', 'apk_badging.py');
const SIGNING_TOOL = path.join(ROOT, 'scripts', 'lib', 'apk_signing.py');
const METADATA_TOOL = path.join(ROOT, 'scripts', 'lib', 'release_metadata.py');
const CERT = '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5';

const badgingText = ({name = 'com.hermes.remote', code = '76', version = '0.1.75', sdk = '26', target = '37'} = {}) => [
  `package: name='${name}' versionCode='${code}' versionName='${version}' platformBuildVersionName='16'`,
  ...(sdk === null ? [] : [`sdkVersion:'${sdk}'`]),
  `targetSdkVersion:'${target}'`,
  "application-label:'Hermes GO'",
].join('\n') + '\n';

const gateJson = (extra = {}) => ({
  gate: 'APK_RELEASE_OK', versionName: '0.1.75', versionCode: 76,
  artifact: '/build/Hermes-Remote-0.1.75-debug.apk', sizeBytes: 4321,
  certificateSha256: CERT, sha256: 'a'.repeat(64), minSdk: 26, ...extra,
});

async function workspace() {
  const dir = await mkdtemp(path.join(tmpdir(), 'apk-gate-'));
  return dir;
}

async function readBadging(text, args) {
  const dir = await workspace();
  const file = path.join(dir, 'badging.txt');
  await writeFile(file, text);
  return run('python3', [BADGING_TOOL, file, ...args]);
}

async function readSigning(text) {
  const dir = await workspace();
  const file = path.join(dir, 'signing.txt');
  await writeFile(file, text);
  return run('python3', [SIGNING_TOOL, file]);
}

async function buildMetadata(gate, {notes = {channel: 'internal', releaseNotes: ['first note']}, commit = 'abc1234', publishedAt = '2026-01-01T00:00:00Z'} = {}) {
  const dir = await workspace();
  const releases = path.join(dir, 'releases');
  await run('mkdir', ['-p', releases]);
  const gatePath = path.join(dir, 'gate.json');
  const out = path.join(dir, 'metadata.json');
  await writeFile(gatePath, JSON.stringify(gate));
  if (notes) await writeFile(path.join(releases, `${gate.versionName ?? 'unknown'}.json`), JSON.stringify(notes));
  await run('python3', [METADATA_TOOL, gatePath, releases, 'https://mrlgs.net', out, commit, publishedAt]);
  return JSON.parse(await readFile(out, 'utf8'));
}

test('badging parsing reports the APK minSdk and rejects a package or version mismatch', async () => {
  const {stdout} = await readBadging(badgingText({sdk: '26'}), ['com.hermes.remote', '0.1.75', '76']);
  assert.equal(stdout.trim(), '26');
  const future = await readBadging(badgingText({sdk: '34'}), ['com.hermes.remote', '0.1.75', '76']);
  assert.equal(future.stdout.trim(), '34', 'the parser must read the APK value, not a constant');
  await assert.rejects(readBadging(badgingText({name: 'com.other.app'}), ['com.hermes.remote', '0.1.75', '76']), /unexpected applicationId/);
  await assert.rejects(readBadging(badgingText({version: '0.1.74'}), ['com.hermes.remote', '0.1.75', '76']), /APK version mismatch/);
  await assert.rejects(readBadging(badgingText({code: '75'}), ['com.hermes.remote', '0.1.75', '76']), /APK version mismatch/);
});

test('badging parsing fails closed when minSdk is missing or unusable', async () => {
  await assert.rejects(readBadging(badgingText({sdk: null}), ['com.hermes.remote', '0.1.75', '76']), /unable to read APK minSdk/);
  await assert.rejects(readBadging(badgingText({sdk: '0'}), ['com.hermes.remote', '0.1.75', '76']), /invalid APK minSdk/);
  // targetSdkVersion must never be mistaken for the minimum.
  const {stdout} = await readBadging(badgingText({sdk: '21', target: '37'}), ['com.hermes.remote', '0.1.75', '76']);
  assert.equal(stdout.trim(), '21');
});

test('signing parsing accepts current apksigner signer output and rejects ambiguity', async () => {
  const actual = [
    'Signer #1 certificate DN: C=US, O=Android, CN=Android Debug',
    `Signer #1 certificate SHA-256 digest: ${CERT}`,
  ].join('\n');
  assert.equal((await readSigning(actual)).stdout.trim(), CERT);
  assert.equal((await readSigning(`V1 Signer: certificate SHA-256 digest: ${CERT}`)).stdout.trim(), CERT);
  await assert.rejects(readSigning('Signer #1 certificate SHA-1 digest: abc'), /SHA-256/);
  await assert.rejects(readSigning(`${actual}\nSigner #2 certificate SHA-256 digest: ${'b'.repeat(64)}`), /more than one/);
});

test('publication metadata carries the APK minSdk instead of a hard-coded value', async () => {
  const base = await buildMetadata(gateJson());
  assert.equal(base.minSdk, 26);
  const raised = await buildMetadata(gateJson({minSdk: 34}));
  assert.equal(raised.minSdk, 34, 'metadata must follow the APK, otherwise the client install check fails');
  assert.deepEqual(Object.keys(raised).sort(), [
    'applicationId', 'certificateSha256', 'channel', 'downloadUrl', 'fileName', 'minSdk',
    'publishedAt', 'releaseNotes', 'schemaVersion', 'sha256', 'sizeBytes', 'sourceCommit',
    'versionCode', 'versionName',
  ]);
  assert.equal(raised.fileName, 'Hermes-Remote-0.1.75-debug.apk');
  assert.equal(raised.downloadUrl, 'https://mrlgs.net/releases/Hermes-Remote-0.1.75-debug.apk');
});

test('a gate without a trustworthy minSdk cannot publish metadata', async () => {
  const {minSdk, ...withoutMinSdk} = gateJson();
  await assert.rejects(buildMetadata(withoutMinSdk), /minSdk/);
  for (const bad of [0, -1, '26', 26.5, null, true]) {
    await assert.rejects(buildMetadata(gateJson({minSdk: bad})), /minSdk/, `accepted minSdk ${JSON.stringify(bad)}`);
  }
});

test('the release scripts stay wired to the extracted minSdk and parse cleanly', async () => {
  const gate = await readFile(path.join(ROOT, 'scripts', 'package-debug-apk.sh'), 'utf8');
  const publisher = await readFile(path.join(ROOT, 'scripts', 'publish-android-apk.sh'), 'utf8');
  assert.match(gate, /apk_badging\.py/);
  assert.match(gate, /apk_signing\.py/);
  assert.match(gate, /'minSdk':int\(min_sdk\)/);
  assert.match(gate, /MIN_SDK=\$MIN_SDK/);
  assert.match(publisher, /release_metadata\.py/);
  assert.equal(/minSdk['"]?\s*[:=]\s*\d/.test(publisher), false, 'the publisher must not hard-code a minSdk');
  await run('bash', ['-n', path.join(ROOT, 'scripts', 'package-debug-apk.sh')]);
  await run('bash', ['-n', path.join(ROOT, 'scripts', 'publish-android-apk.sh')]);
});

test('metadata generation still rejects a failed gate or an invalid release description', async () => {
  await assert.rejects(buildMetadata(gateJson({gate: 'APK_RELEASE_FAILED'})), /release gate did not succeed/);
  await assert.rejects(buildMetadata(gateJson(), {notes: {channel: 'beta', releaseNotes: ['x']}}), /invalid release description/);
  await assert.rejects(buildMetadata(gateJson(), {notes: {channel: 'internal', releaseNotes: 'x'}}), /invalid release description/);
  await assert.rejects(buildMetadata(gateJson(), {notes: {channel: 'internal', releaseNotes: ['x'], extra: 1}}), /invalid release description/);
});

test('publication executes the reviewed publisher and schema from the clean source commit', async () => {
  const publisher = await readFile(path.join(ROOT, 'scripts', 'publish-android-apk.sh'), 'utf8');
  assert.match(publisher, /deploy\/publish-release\.mjs/);
  assert.match(publisher, /release-server\/src\/schema\.mjs/);
  assert.match(publisher, /\$REMOTE_TMP\/deploy\/publish-release\.mjs/);
  assert.match(publisher, /flock/);
  assert.match(publisher, /PUBLISH_FLOCK_HELD=1/);
  assert.match(publisher, /APK_RELEASE_GATE_FILE/);
  assert.equal(
    publisher.includes('/opt/hermes-release-server/deploy/publish-release.mjs'),
    false,
    'production must not execute an independently deployed stale publisher',
  );
});
