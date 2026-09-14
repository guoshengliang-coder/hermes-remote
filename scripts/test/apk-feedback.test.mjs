import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {test} from 'node:test';

/**
 * Covers scripts/lib/apk_feedback.py, the release gate's guard against shipping an APK whose
 * in-app feedback entry is silently absent.
 *
 * 0.1.120 is why it exists. It was built from a worktree without the gitignored
 * android/missiongo.properties, so BuildConfig.MISSIONGO_ENDPOINT compiled in empty, the reporter
 * fell back to UnavailableFeedbackReporter, and the "反馈与建议" row was never drawn. Package,
 * version, signature and hash were all correct, so every other gate check passed and the loss only
 * surfaced when a person went looking for the feature.
 */

const CHECK = new URL('../lib/apk_feedback.py', import.meta.url).pathname;
const ENDPOINT = 'https://missiongo.example/api';
const TOKEN = 'nonsecret-test-token';

/** An APK-shaped zip whose classes.dex contains each of `strings`. */
function apkWith(strings) {
  const dir = mkdtempSync(path.join(tmpdir(), 'apk-'));
  const file = path.join(dir, 'app.apk');
  execFileSync('python3', [
    '-c',
    [
      'import sys, zipfile',
      'target, payload = sys.argv[1], sys.argv[2]',
      'with zipfile.ZipFile(target, "w") as z:',
      '    z.writestr("classes.dex", payload.encode("utf-8"))',
      '    z.writestr("AndroidManifest.xml", b"stub")',
    ].join('\n'),
    file,
    strings.join(' '),
  ]);
  return file;
}

function buildConfig({endpoint = ENDPOINT, token = TOKEN} = {}) {
  const dir = mkdtempSync(path.join(tmpdir(), 'build-config-'));
  const file = path.join(dir, 'BuildConfig.java');
  writeFileSync(file, [
    'package com.hermes.client;',
    `public static final String MISSIONGO_ENDPOINT = ${JSON.stringify(endpoint)};`,
    `public static final String MISSIONGO_SDK_TOKEN = ${JSON.stringify(token)};`,
  ].join('\n'));
  return file;
}

const run = (apk, values) => execFileSync(
  'python3', [CHECK, apk, buildConfig(values)], {encoding: 'utf8'},
);

test('an APK carrying both generated feedback values passes', () => {
  assert.match(run(apkWith(['noise', ENDPOINT, TOKEN, 'more noise'])), /FEEDBACK_CONFIG_OK/);
});

test('either blank generated value fails — the exact 0.1.120 class of bug', () => {
  for (const blank of ['', '   ']) {
    assert.throws(() => run(apkWith([TOKEN]), {endpoint: blank}), /MISSIONGO_ENDPOINT/);
    assert.throws(() => run(apkWith([ENDPOINT]), {token: blank}), /MISSIONGO_SDK_TOKEN/);
  }
});

test('either generated value missing from the artifact fails', () => {
  // The build inputs say one thing and the compiled dex says another — a stale Gradle
  // configuration-cache entry. Checking inputs alone would call this a pass.
  assert.throws(
    () => run(apkWith([TOKEN])),
    /MISSIONGO_ENDPOINT.*not present in the APK/s,
  );
  assert.throws(
    () => run(apkWith([ENDPOINT])),
    /MISSIONGO_SDK_TOKEN.*not present in the APK/s,
  );
});

test('the failure never echoes the endpoint back', () => {
  // The endpoint and token are credentials; a gate that prints them puts them in CI logs.
  const endpoint = 'https://secret-host.example/collect';
  const token = 'secret-token-value';
  try {
    run(apkWith(['unrelated']), {endpoint, token});
    assert.fail('expected a failure');
  } catch (error) {
    const output = `${error.stdout ?? ''}${error.stderr ?? ''}`;
    assert.ok(!output.includes('secret-host'), `endpoint leaked into output: ${output}`);
    assert.ok(!output.includes(token), `token leaked into output: ${output}`);
  }
});

test('multi-dex is searched, not just classes.dex', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'apk-'));
  const file = path.join(dir, 'multi.apk');
  execFileSync('python3', [
    '-c',
    [
      'import sys, zipfile',
      'target, endpoint, token = sys.argv[1:]',
      'with zipfile.ZipFile(target, "w") as z:',
      '    z.writestr("classes.dex", b"nothing here")',
      '    z.writestr("classes2.dex", (endpoint + " " + token).encode("utf-8"))',
    ].join('\n'),
    file,
    ENDPOINT,
    TOKEN,
  ]);
  assert.match(run(file), /FEEDBACK_CONFIG_OK/);
});
