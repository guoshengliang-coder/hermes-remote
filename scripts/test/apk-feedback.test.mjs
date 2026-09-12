import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync} from 'node:fs';
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

const run = (...args) => execFileSync('python3', [CHECK, ...args], {encoding: 'utf8'});

test('an APK carrying the configured endpoint passes', () => {
  const endpoint = 'https://missiongo.example/api';
  assert.match(run(apkWith(['noise', endpoint, 'more noise']), endpoint), /FEEDBACK_CONFIG_OK/);
});

test('a blank endpoint fails — the exact 0.1.120 case', () => {
  const apk = apkWith(['noise']);
  for (const blank of ['', '   ']) {
    assert.throws(() => run(apk, blank), /反馈与建议/);
  }
});

test('a configured endpoint missing from the artifact fails', () => {
  // The build inputs say one thing and the compiled dex says another — a stale Gradle
  // configuration-cache entry. Checking inputs alone would call this a pass.
  assert.throws(
    () => run(apkWith(['unrelated']), 'https://missiongo.example/api'),
    /not present in the built APK/,
  );
});

test('the failure never echoes the endpoint back', () => {
  // The endpoint and token are credentials; a gate that prints them puts them in CI logs.
  const endpoint = 'https://secret-host.example/collect';
  try {
    run(apkWith(['unrelated']), endpoint);
    assert.fail('expected a failure');
  } catch (error) {
    const output = `${error.stdout ?? ''}${error.stderr ?? ''}`;
    assert.ok(!output.includes('secret-host'), `endpoint leaked into output: ${output}`);
  }
});

test('multi-dex is searched, not just classes.dex', () => {
  const endpoint = 'https://missiongo.example/api';
  const dir = mkdtempSync(path.join(tmpdir(), 'apk-'));
  const file = path.join(dir, 'multi.apk');
  execFileSync('python3', [
    '-c',
    [
      'import sys, zipfile',
      'target, endpoint = sys.argv[1], sys.argv[2]',
      'with zipfile.ZipFile(target, "w") as z:',
      '    z.writestr("classes.dex", b"nothing here")',
      '    z.writestr("classes2.dex", endpoint.encode("utf-8"))',
    ].join('\n'),
    file,
    endpoint,
  ]);
  assert.match(run(file, endpoint), /FEEDBACK_CONFIG_OK/);
});
