import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

import {parseArgs, parseGateOutput, publishRefusal, releasePrBody} from '../android-release-train.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SCRIPT = path.join(ROOT, 'scripts', 'android-release-train.mjs');
const GRADLE = 'val appVersionCode = 142\nval appVersionName = "0.1.141"\n';

test('prepare needs notes and a summary; publish needs one version', () => {
  assert.deepEqual(parseArgs(['prepare', '--notes-file', 'n.md', '--summary', 's']), {command: 'prepare', merge: true, notesFile: 'n.md', summary: 's'});
  assert.equal(parseArgs(['prepare', '--notes-file', 'n', '--summary-file', 's', '--no-merge']).merge, false);
  assert.throws(() => parseArgs(['prepare', '--summary', 's']), /--notes-file/);
  assert.throws(() => parseArgs(['prepare', '--notes-file', 'n']), /--summary/);
  assert.throws(() => parseArgs(['prepare', '--notes-file', 'n', '--summary', 's', '--bogus']), /unknown argument/);
  assert.deepEqual(parseArgs(['publish', '0.1.141']), {command: 'publish', version: '0.1.141'});
  assert.throws(() => parseArgs(['publish']), /exactly one/);
  assert.throws(() => parseArgs(['publish', 'v0.1.141']), /exactly one/);
  assert.throws(() => parseArgs([]), /usage:/);
});

test('the package gate counts only with APK_RELEASE_OK', () => {
  const out = 'building…\nAPK_RELEASE_OK\nARTIFACT=/x/Hermes-Remote-0.1.141-debug.apk\nCERT_SHA256=ab\nSHA256=cd\n';
  assert.deepEqual(parseGateOutput(out), {artifact: '/x/Hermes-Remote-0.1.141-debug.apk', sha256: 'cd', certSha256: 'ab'});
  assert.equal(parseGateOutput('ARTIFACT=/x/app.apk\nSHA256=cd\n'), null);
  assert.equal(parseGateOutput('echo APK_RELEASE_OK later\n'), null);
});

test('publish refuses unless origin/main carries exactly this unpublished version', () => {
  const ok = {version: '0.1.141', gradleText: GRADLE, releaseFileExists: true, tagExists: false};
  assert.equal(publishRefusal(ok), null);
  assert.match(publishRefusal({...ok, version: '0.1.142'}), /at 0\.1\.141, not 0\.1\.142/);
  assert.match(publishRefusal({...ok, releaseFileExists: false}), /releases\/0\.1\.141\.json/);
  // android-v0.1.139: a tag pushed after the same version had already been uploaded.
  assert.match(publishRefusal({...ok, tagExists: true}), /already exists/);
});

test('the release PR says it is red-light and unpublished', () => {
  const body = releasePrBody({version: '0.1.141', gate: {artifact: '/x/Hermes-Remote-0.1.141-debug.apk', sha256: 'cd', certSha256: 'ab'}});
  assert.match(body, /Hermes-Remote-0\.1\.141-debug\.apk/);
  assert.match(body, /--allow-red/);
  assert.match(body, /Not published/);
});

test('the CLI prints usage for an unknown command without touching git', () => {
  assert.throws(() => execFileSync('node', [SCRIPT, 'ship'], {stdio: 'pipe'}), (error) => error.status === 4 && /usage:/.test(error.stderr));
});
