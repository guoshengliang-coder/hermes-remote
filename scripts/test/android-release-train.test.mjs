import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

import {parseArgs, PREPARATION_TASKS, publishRefusal, releasePrBody} from '../android-release-train.mjs';

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

test('preparation runs development checks without a signing or feedback gate', () => {
  assert.deepEqual(PREPARATION_TASKS, [':app:testDebugUnitTest', ':app:lintDebug']);
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
  const body = releasePrBody({version: '0.1.141'});
  assert.match(body, /No local APK was produced or approved/);
  assert.match(body, /full package gate with repository secrets/);
  assert.doesNotMatch(body, /passed locally|SHA-256|APK_RELEASE_OK/);
  assert.match(body, /--allow-red/);
  assert.match(body, /Not published/);
});

test('the CLI prints usage for an unknown command without touching git', () => {
  assert.throws(() => execFileSync('node', [SCRIPT, 'ship'], {stdio: 'pipe'}), (error) => error.status === 4 && /usage:/.test(error.stderr));
});

test('CI release preparation executes development checks, never local packaging or publication', async (t) => {
  const {cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync, rmSync, realpathSync} = await import('node:fs');
  const {tmpdir} = await import('node:os');
  const {spawnSync} = await import('node:child_process');
  const fixture = realpathSync(mkdtempSync(path.join(tmpdir(), 'release preparation ')));
  t.after(() => rmSync(fixture, {recursive: true, force: true}));
  for (const name of ['scripts/lib', 'android/app', 'release-server/src', 'bin']) mkdirSync(path.join(fixture, name), {recursive: true});
  for (const name of ['scripts/android-release-train.mjs', 'scripts/bump-android-release.mjs', 'scripts/merge-when-green.mjs', 'scripts/lib/android-release-errors.json', 'release-server/src/schema.mjs']) {
    cpSync(path.join(ROOT, name), path.join(fixture, name));
  }
  writeFileSync(path.join(fixture, 'android/app/build.gradle.kts'), GRADLE);
  const log = path.join(fixture, 'commands.log');
  writeFileSync(path.join(fixture, 'bin/git'), `#!/bin/bash\nprintf 'git %s\\n' "$*" >> "$COMMAND_LOG"\ncase "$1" in\nrev-parse) echo same-commit ;;\nstatus) ;;\nesac\n`, {mode: 0o755});
  writeFileSync(path.join(fixture, 'bin/node'), `#!/bin/bash\nprintf 'node %s\\n' "$*" >> "$COMMAND_LOG"\n`, {mode: 0o755});
  writeFileSync(path.join(fixture, 'bin/gh'), `#!/bin/bash\nprintf 'gh %s\\n' "$*" >> "$COMMAND_LOG"\necho https://github.com/example/hermes-remote/pull/1\n`, {mode: 0o755});
  writeFileSync(path.join(fixture, 'android/gradlew'), `#!/bin/bash\nprintf 'gradle %s\\n' "$*" >> "$COMMAND_LOG"\nexit "\${DEVELOPMENT_STATUS:-0}"\n`, {mode: 0o755});
  // If preparation accidentally tries to package, this sentinel makes the regression obvious.
  writeFileSync(path.join(fixture, 'scripts/package-debug-apk.sh'), `#!/bin/bash\necho forbidden-package >> "$COMMAND_LOG"\nexit 99\n`, {mode: 0o755});
  const env = {...process.env, PATH: `${path.join(fixture, 'bin')}:${process.env.PATH}`, COMMAND_LOG: log};
  delete env.MISSIONGO_ENDPOINT;
  delete env.MISSIONGO_SDK_TOKEN;
  const args = [path.join(fixture, 'scripts/android-release-train.mjs'), 'prepare', '--notes-file', 'notes.json', '--summary', 'test', '--no-merge'];
  const result = spawnSync(process.execPath, args, {env, encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /No APK built/);
  const commands = readFileSync(log, 'utf8');
  assert.match(commands, /gradle :app:testDebugUnitTest :app:lintDebug --console=plain/);
  assert.match(commands, /gh pr create/);
  assert.doesNotMatch(commands, /forbidden-package|assembleDebug|verifyMissionGoConfiguration|git tag|workflow run/);

  writeFileSync(log, '');
  const failed = spawnSync(process.execPath, args, {env: {...env, DEVELOPMENT_STATUS: '1'}, encoding: 'utf8'});
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /HR-RELEASE-007/);
  assert.match(failed.stderr, /development_checks_failed/);
  assert.doesNotMatch(readFileSync(log, 'utf8'), /gh pr create|git commit|git push/);
});
