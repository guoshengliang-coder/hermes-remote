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

test('ordinary CI, SAST, and the Gateway image gate stay unprivileged and never require signing material', async () => {
  for (const file of ['ci.yml', 'sast.yml', 'gateway-oci.yml']) {
    const text = await read(file);
    assert.equal(/gradlew[^\n]*assemble/.test(text), false, `${file}: packages an APK, which needs the canonical debug key`);
    assert.equal(/KEYSTORE|RELEASE_SSH/.test(text), false, `${file}: references release signing or deployment secrets`);
  }
  const ci = await read('ci.yml');
  assert.match(ci, /gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugSources/);

  const sast = await read('sast.yml');
  assert.match(sast, /semgrep\/semgrep:1\.176\.0@sha256:[0-9a-f]{64}/);
  assert.match(sast, /semgrep scan[\s\S]*--config p\/default[\s\S]*--error[\s\S]*--metrics off/);
  assert.match(sast, /permissions:\n  contents: read/);
  assert.equal(/security-events|SEMGREP_APP_TOKEN|secrets\./.test(sast), false, 'SAST must not upload source or receive secrets');

  const gatewayOci = await read('gateway-oci.yml');
  assert.match(gatewayOci, /runs-on: ubuntu-24\.04/);
  assert.match(gatewayOci, /run: \.\/scripts\/test-gateway-image\.sh/);
  assert.match(gatewayOci, /run: \.\/scripts\/package-gateway-bundle\.sh outputs\/gateway-bundle/);
  assert.match(gatewayOci, /permissions:\n  contents: read/);
  assert.equal(/docker\s+(?:push|login)|packages: write|secrets\./.test(gatewayOci), false, 'Gateway OCI gate must not publish images or receive secrets');
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
