import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

import {
  allocatedVersions,
  applyGradleVersions,
  applyReadme,
  nextVersion,
  normalizeNotes,
  parseGradleVersions,
  validateNotes,
} from '../bump-android-release.mjs';
import {MAX_RELEASE_NOTES, MAX_RELEASE_NOTE_LENGTH} from '../../release-server/src/schema.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SCRIPT = path.join(ROOT, 'scripts', 'bump-android-release.mjs');

const GRADLE = [
  'val appVersionCode = 115',
  'val appVersionName = "0.1.114"',
  'android {',
  '    namespace = "com.hermes.client"',
  '}',
].join('\n');

const README = [
  '# Hermes Remote Android',
  '',
  '- Version 0.1.96 closes the two ways a run could go wrong.',
  '- Version 0.1.114 repaints the whole app on warm paper.',
  '- Version 0.1.113 completes the account binding.',
  '',
  '```text',
  'app/build/outputs/apk/distribution/debug/Hermes-Remote-0.1.114-debug.apk',
  '```',
].join('\n');

test('the version truth source is read and rewritten as a pair', () => {
  const current = parseGradleVersions(GRADLE);
  assert.deepEqual(current, {versionName: '0.1.114', versionCode: 115});

  const next = nextVersion(current);
  assert.deepEqual(next, {versionName: '0.1.115', versionCode: 116});

  const rewritten = applyGradleVersions(GRADLE, next);
  assert.deepEqual(parseGradleVersions(rewritten), next);
  // Everything that is not the two version lines must survive untouched.
  assert.ok(rewritten.includes('namespace = "com.hermes.client"'));
});

test('a build script without both version lines is rejected rather than half-written', () => {
  assert.throws(() => parseGradleVersions('val appVersionName = "0.1.114"'), /not found/);
  assert.throws(() => parseGradleVersions('val appVersionCode = 115\nval appVersionName = "0.1"'), /not semver/);
});

test('the README gains the new entry and every APK reference moves with it', () => {
  const out = applyReadme(README, {current: '0.1.114', next: '0.1.115', summary: 'does a small thing.'});

  assert.ok(out.includes('- Version 0.1.115 does a small thing.'));
  // Inserted directly above the superseded entry, not at the top of an unsorted list.
  assert.ok(out.indexOf('- Version 0.1.115') < out.indexOf('- Version 0.1.114'));
  assert.ok(out.indexOf('- Version 0.1.96') < out.indexOf('- Version 0.1.115'));

  assert.ok(out.includes('Hermes-Remote-0.1.115-debug.apk'));
  assert.equal(out.includes('Hermes-Remote-0.1.114-debug.apk'), false);
  // The old release note stays; only the artifact path is version-bearing.
  assert.ok(out.includes('- Version 0.1.114 repaints the whole app'));
});

test('the README rewrite satisfies the release gate the same way package-debug-apk.sh checks it', async () => {
  const gate = await readFile(path.join(ROOT, 'scripts', 'package-debug-apk.sh'), 'utf8');
  // The gate builds these two strings; if it ever stops, this test should be the thing that notices.
  assert.ok(gate.includes('f"Version {version}"'));
  assert.ok(gate.includes('f"Hermes-Remote-{version}-debug.apk"'));

  const out = applyReadme(README, {current: '0.1.114', next: '0.1.115', summary: 'x.'});
  for (const required of ['Version 0.1.115', 'Hermes-Remote-0.1.115-debug.apk']) {
    assert.ok(out.includes(required), `gate would reject: missing ${required}`);
  }
});

test('a README missing either anchor fails before anything is written', () => {
  const noEntry = README.replace('- Version 0.1.114 repaints the whole app on warm paper.', '');
  assert.throws(() => applyReadme(noEntry, {current: '0.1.114', next: '0.1.115', summary: 'x.'}), /no "- Version 0\.1\.114" entry/);

  const noApk = README.replace('Hermes-Remote-0.1.114-debug.apk', 'app-debug.apk');
  assert.throws(() => applyReadme(noApk, {current: '0.1.114', next: '0.1.115', summary: 'x.'}), /no Hermes-Remote-0\.1\.114-debug\.apk/);
});

test('a long README entry wraps with the two-space continuation the file already uses', () => {
  const summary = 'word '.repeat(60).trim();
  const out = applyReadme(README, {current: '0.1.114', next: '0.1.115', summary});
  const entry = out.split('\n').slice(
    out.split('\n').findIndex(line => line.startsWith('- Version 0.1.115')),
  );
  assert.ok(entry[0].length <= 100);
  assert.ok(entry[1].startsWith('  '), 'continuation lines must be indented, not flush left');
});

test('notes come from a JSON array verbatim or from blank-line separated paragraphs', () => {
  assert.deepEqual(normalizeNotes('["one", "two"]', {json: true}), ['one', 'two']);
  assert.deepEqual(normalizeNotes('one\nstill one\n\n\ntwo\n', {json: false}), ['one still one', 'two']);
  assert.deepEqual(normalizeNotes('  \n\n  ', {json: false}), []);
  assert.throws(() => normalizeNotes('{"a":1}', {json: true}), /must be an array/);
});

test('notes are validated against the manifest limits the release server enforces', () => {
  assert.throws(() => validateNotes([]), /empty/);
  assert.throws(() => validateNotes(Array(MAX_RELEASE_NOTES + 1).fill('ok')), new RegExp(`at most ${MAX_RELEASE_NOTES}`));
  assert.throws(() => validateNotes(['x'.repeat(MAX_RELEASE_NOTE_LENGTH + 1)]), /exceeds/);
  assert.throws(() => validateNotes(['a\u0007b']), /control characters/);
  assert.deepEqual(validateNotes(['x'.repeat(MAX_RELEASE_NOTE_LENGTH)]).length, 1);
});

test('a version already carried by a release file or an origin tag counts as allocated', () => {
  const taken = allocatedVersions({
    releaseFiles: ['0.1.114.json', '0.1.97.json', 'README.md', 'not-a-version.json'],
    tags: ['android-v0.1.115', 'android-v0.1.34'],
  });
  // The 0.1.95/0.1.97 collisions: another agent's release file or tag exists while this checkout's
  // build.gradle.kts still shows the older number.
  assert.ok(taken.has('0.1.114'));
  assert.ok(taken.has('0.1.115'));
  assert.ok(taken.has('0.1.97'));
  assert.equal(taken.has('not-a-version'), false);
  assert.equal(taken.has('0.1.116'), false);
});

test('the real repository state is consistent with what the allocator would refuse', async () => {
  const gradle = parseGradleVersions(await readFile(path.join(ROOT, 'android', 'app', 'build.gradle.kts'), 'utf8'));
  const readme = await readFile(path.join(ROOT, 'android', 'README.md'), 'utf8');
  // If these ever stop holding, the next allocation would fail at the README step; catching it in
  // CI is cheaper than catching it mid-release.
  assert.ok(readme.includes(`Version ${gradle.versionName}`));
  assert.ok(readme.includes(`Hermes-Remote-${gradle.versionName}-debug.apk`));
});

test('rewriting the version pair never swallows the blank line that follows it', () => {
  // The real build script has a blank line after appVersionName. A trailing `\s*$` under the m flag
  // matched across that newline, so the rewrite deleted the line and the 0.1.116 release commit had
  // to put it back by hand. The earlier fixture never had a blank line there, so it stayed green.
  const nameLast = 'val appVersionCode = 116\nval appVersionName = "0.1.115"\n\n// comment\n';
  assert.equal(
    applyGradleVersions(nameLast, {versionName: '0.1.116', versionCode: 117}),
    'val appVersionCode = 117\nval appVersionName = "0.1.116"\n\n// comment\n',
  );
  const codeLast = 'val appVersionName = "0.1.115"\nval appVersionCode = 116\n\n// comment\n';
  assert.equal(
    applyGradleVersions(codeLast, {versionName: '0.1.116', versionCode: 117}),
    'val appVersionName = "0.1.116"\nval appVersionCode = 117\n\n// comment\n',
  );
});

test('rewriting the real build script changes exactly the two version lines and nothing else', async () => {
  const real = await readFile(path.join(ROOT, 'android', 'app', 'build.gradle.kts'), 'utf8');
  const before = real.split('\n');
  const after = applyGradleVersions(real, nextVersion(parseGradleVersions(real))).split('\n');
  assert.equal(after.length, before.length, 'the rewrite added or removed lines');
  const changed = before.flatMap((line, index) => (line === after[index] ? [] : [index]));
  assert.equal(changed.length, 2);
  for (const index of changed) assert.match(before[index], /^val appVersion(Name|Code) = /);
});

test('--help and -h print usage and exit 0 before any git check can run', () => {
  // The first real run of this script, for 0.1.116, began with `--help` and got "unknown argument".
  // Help must also come before assertReleasableCheckout: on a PR checkout HEAD is not origin/main,
  // so a help flag that reached the git checks would exit 1 here instead of printing usage.
  for (const flag of ['--help', '-h']) {
    const out = execFileSync(process.execPath, [SCRIPT, flag], {encoding: 'utf8'});
    assert.match(out, /^Usage: node scripts\/bump-android-release\.mjs/);
    for (const option of ['--notes-file', '--summary', '--summary-file', '--dry-run']) assert.ok(out.includes(option));
  }
});
