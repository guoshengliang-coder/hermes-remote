import assert from 'node:assert/strict';
import {readdir, readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WORKFLOWS = path.join(ROOT, '.github', 'workflows');
const read = async name => readFile(path.join(WORKFLOWS, name), 'utf8');

test('every action is pinned to a full commit SHA with a readable version comment', async () => {
  const files = (await readdir(WORKFLOWS)).filter(name => name.endsWith('.yml'));
  assert.ok(files.length >= 3);
  for (const file of files) {
    for (const line of (await read(file)).split('\n')) {
      const used = /^\s*(?:-\s*)?uses:\s*(\S+)/.exec(line);
      if (!used) continue;
      assert.match(used[1], /^[\w.-]+\/[\w./-]+@[0-9a-f]{40}$/, `${file}: unpinned action ${used[1]}`);
      assert.match(line, /#\s*v\d+\.\d+\.\d+/, `${file}: pinned action without a version comment: ${line.trim()}`);
    }
  }
});

test('ordinary CI compiles and tests Android without asking for signing material', async () => {
  for (const file of ['ci.yml', 'codeql.yml']) {
    const text = await read(file);
    assert.equal(/gradlew[^\n]*assemble/.test(text), false, `${file}: packages an APK, which needs the canonical debug key`);
    assert.equal(/KEYSTORE|RELEASE_SSH/.test(text), false, `${file}: references release signing or deployment secrets`);
  }
  const ci = await read('ci.yml');
  assert.match(ci, /gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugSources/);
  assert.match(await read('codeql.yml'), /gradlew :app:compileDebugSources/);
});

test('release secrets stay scoped to the steps that consume them', async () => {
  const release = await read('android-release.yml');
  const lines = release.split('\n');
  const jobLevelEnv = lines.filter(line => /^ {4}env:\s*$/.test(line));
  assert.deepEqual(jobLevelEnv, [], 'release secrets must not be exposed to every step through job-level env');
  const secrets = lines.filter(line => line.includes('secrets.'));
  assert.equal(secrets.length, 3);
  for (const line of secrets) assert.match(line, /^ {10}\w+: \$\{\{ secrets\.\w+ \}\}$/, `unexpected secret usage: ${line}`);
  assert.match(release, /Build signed APK and erase signing key[\s\S]*key="\$HOME\/\.android\/debug\.keystore"[\s\S]*trap[^\n]*\$key/);
  assert.match(release, /Publish APK and erase deployment key[\s\S]*key="\$HOME\/\.ssh\/id_ed25519"[\s\S]*trap[^\n]*\$key/);
  assert.match(release, /APK_RELEASE_GATE_FILE/);
  const signing = release.indexOf('Build signed APK and erase signing key');
  const deployment = release.indexOf('Publish APK and erase deployment key');
  assert.ok(signing >= 0 && deployment > signing, 'signing and deployment must be separate ordered steps');
  assert.equal(release.slice(signing, deployment).includes('RELEASE_SSH_PRIVATE_KEY'), false, 'SSH key must not exist while Gradle/build scripts run');
});
