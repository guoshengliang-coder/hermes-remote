import assert from 'node:assert/strict';
import {spawnSync, execFileSync} from 'node:child_process';
import {cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const CERT = '06c18dfc4a852330654c2da040a578bccab13b71dde4ac962bb9bc2271dd32c5';
const ENDPOINT = 'https://feedback.example.invalid';
const TOKEN = 'synthetic-feedback-token';

function fixture(t, {preflightFails = false, dexMismatch = false} = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'android package '));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  mkdirSync(path.join(root, 'scripts'), {recursive: true});
  cpSync(path.join(ROOT, 'scripts/package-debug-apk.sh'), path.join(root, 'scripts/package-debug-apk.sh'));
  cpSync(path.join(ROOT, 'scripts/lib'), path.join(root, 'scripts/lib'), {recursive: true});
  mkdirSync(path.join(root, 'android/app'), {recursive: true});
  writeFileSync(path.join(root, 'android/app/build.gradle.kts'), `val appVersionCode = 160\nval appVersionName = "0.1.159"\nval expectedDebugCertificateSha256 = "${CERT}"\n`);
  writeFileSync(path.join(root, 'android/README.md'), 'Version 0.1.159\nHermes-Remote-0.1.159-debug.apk\n');
  const taskLog = path.join(root, 'tasks.log');
  writeFileSync(path.join(root, 'android/gradlew'), `#!/bin/bash\nprintf '%s\\n' "$*" >> "$TASK_LOG"\nif [[ "$*" == *":app:verifyMissionGoConfiguration --no-configuration-cache"* && "$PREFLIGHT_FAILS" == 1 ]]; then\n  echo "synthetic-feedback-token" >&2\n  exit 1\nfi\n`, {mode: 0o755});
  const bin = path.join(root, 'bin');
  mkdirSync(bin);
  writeFileSync(path.join(bin, 'git'), '#!/bin/bash\nexit 0\n', {mode: 0o755});
  const sdk = path.join(root, 'sdk');
  mkdirSync(path.join(sdk, 'build-tools/37.0.0'), {recursive: true});
  writeFileSync(path.join(sdk, 'build-tools/37.0.0/aapt'), `#!/bin/bash\nprintf "%s\\n" "package: name='com.hermes.remote' versionCode='160' versionName='0.1.159'" "sdkVersion:'26'"\n`, {mode: 0o755});
  writeFileSync(path.join(sdk, 'build-tools/37.0.0/apksigner'), `#!/bin/bash\necho 'Signer #1 certificate SHA-256 digest: ${CERT}'\n`, {mode: 0o755});
  const apk = path.join(root, 'android/app/build/outputs/apk/distribution/debug/Hermes-Remote-0.1.159-debug.apk');
  mkdirSync(path.dirname(apk), {recursive: true});
  execFileSync('python3', ['-c', 'import sys,zipfile\nwith zipfile.ZipFile(sys.argv[1],"w") as z: z.writestr("classes.dex", sys.argv[2])', apk, dexMismatch ? 'unrelated' : `${ENDPOINT} ${TOKEN}`]);
  const config = path.join(root, 'android/app/build/generated/source/buildConfig/debug/com/hermes/client/BuildConfig.java');
  mkdirSync(path.dirname(config), {recursive: true});
  writeFileSync(config, `public static final String MISSIONGO_ENDPOINT = ${JSON.stringify(ENDPOINT)};\npublic static final String MISSIONGO_SDK_TOKEN = ${JSON.stringify(TOKEN)};\n`);
  const metadata = path.join(root, 'gate.json');
  const env = {...process.env, PATH: `${bin}:${process.env.PATH}`, TASK_LOG: taskLog, PREFLIGHT_FAILS: preflightFails ? '1' : '0', ANDROID_HOME: sdk, APK_RELEASE_METADATA_FILE: metadata};
  delete env.APK_REQUIRE_MISSIONGO_CONFIG;
  return {root, taskLog, metadata, env, run(extra = {}) {
    return spawnSync('bash', [path.join(root, 'scripts/package-debug-apk.sh')], {cwd: root, env: {...env, ...extra}, encoding: 'utf8'});
  }};
}

test('missing configuration stops before tests/assembly and never exposes the Gradle failure contents', (t) => {
  const f = fixture(t, {preflightFails: true});
  const result = f.run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /HR-RELEASE-005/);
  assert.match(result.stderr, /Android 发包配置检查未通过/);
  assert.match(result.stderr, /Use the CI release build/);
  assert.doesNotMatch(result.stderr + result.stdout, /synthetic-feedback-token|APK_RELEASE_OK/);
  assert.equal(readFileSync(f.taskLog, 'utf8').trim(), ':app:verifyMissionGoConfiguration --no-configuration-cache --console=plain');
});

test('feedback validation cannot be disabled to produce a distribution package', (t) => {
  const f = fixture(t);
  const result = f.run({APK_REQUIRE_MISSIONGO_CONFIG: '0'});
  assert.equal(result.status, 1);
  assert.match(result.stderr, /HR-RELEASE-005/);
  assert.doesNotMatch(result.stdout, /APK_RELEASE_OK/);
});

test('a configured build must still prove the constants in the APK before emitting release metadata', (t) => {
  const f = fixture(t);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /FEEDBACK_CONFIG_OK/);
  assert.match(result.stdout, /APK_RELEASE_OK/);
  const tasks = readFileSync(f.taskLog, 'utf8').trim().split('\n');
  assert.match(tasks[0], /^:app:verifyMissionGoConfiguration --no-configuration-cache/);
  assert.match(tasks[1], /^:app:testDebugUnitTest :app:assembleDebug :app:verifyMissionGoConfiguration/);
  assert.equal(JSON.parse(readFileSync(f.metadata, 'utf8')).missionGoConfigured, true);
  assert.doesNotMatch(result.stdout + result.stderr, /synthetic-feedback-token|feedback\.example/);
});

test('successful preflight cannot bless an APK compiled from stale or mismatching configuration', (t) => {
  const f = fixture(t, {dexMismatch: true});
  const result = f.run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /HR-RELEASE-006/);
  assert.match(result.stderr, /feedback_value_absent_from_apk/);
  assert.doesNotMatch(result.stdout + result.stderr, /APK_RELEASE_OK|synthetic-feedback-token|feedback\.example/);
});

test('Android release error definitions have registered bilingual summaries and safe serialized causes', () => {
  const definitions = JSON.parse(readFileSync(path.join(ROOT, 'scripts/lib/android-release-errors.json'), 'utf8'));
  const registry = readFileSync(path.join(ROOT, 'docs/ERROR_HANDLING.md'), 'utf8');
  assert.deepEqual(Object.values(definitions).map(x => x.code), ['HR-RELEASE-005', 'HR-RELEASE-006', 'HR-RELEASE-007']);
  for (const [kind, definition] of Object.entries(definitions)) {
    assert.match(definition.summaryZh, /[\u3400-\u9fff]/);
    assert.match(definition.summaryEn, /^Android|^The Android/);
    assert.equal(definition.retryable, true);
    assert.match(definition.recoveryAction, /retry/);
    assert.ok(registry.includes(`| \`${definition.code}\` |`));
    const response = spawnSync('python3', [path.join(ROOT, 'scripts/lib/android_release_error.py'), kind, 'token=private-value password=hidden'], {encoding: 'utf8'});
    const parsed = JSON.parse(response.stderr.trim().split('\n').at(-1));
    assert.equal(parsed.code, definition.code);
    assert.equal(parsed.technicalCause, 'redacted');
    assert.doesNotMatch(response.stderr, /private-value|hidden/);
    const opaque = spawnSync('python3', [path.join(ROOT, 'scripts/lib/android_release_error.py'), kind, 'secretlowercase'], {encoding: 'utf8'});
    assert.doesNotMatch(opaque.stderr, /secretlowercase/);
  }
});
