import assert from 'node:assert/strict';
import {readdir, readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WORKFLOWS = path.join(ROOT, '.github', 'workflows');
const read = async name => readFile(path.join(WORKFLOWS, name), 'utf8');
const readRoot = async name => readFile(path.join(ROOT, name), 'utf8');

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

test('ordinary CI, SAST, and the Gateway image gates stay unprivileged and never require signing material', async () => {
  for (const file of ['ci.yml', 'sast.yml', 'gateway-oci.yml', 'gateway-r5e-recovery.yml']) {
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
  assert.match(gatewayOci, /run: node scripts\/package-production-baseline-bundle\.mjs outputs\/production-baseline-bundle/);
  assert.match(gatewayOci, /if: github\.event_name == 'push' && github\.ref == 'refs\/heads\/main'/);
  assert.match(gatewayOci, /uses: actions\/upload-artifact@[0-9a-f]{40} # v7\.0\.1/);
  assert.match(gatewayOci, /name: gateway-bundle-\$\{\{ github\.sha \}\}/);
  assert.match(gatewayOci, /path: \|[\s\S]*outputs\/gateway-bundle\/[\s\S]*outputs\/production-baseline-bundle\//);
  assert.match(gatewayOci, /if-no-files-found: error/);
  assert.match(gatewayOci, /retention-days: 7/);
  assert.match(gatewayOci, /permissions:\n  contents: read/);
  assert.equal(/docker\s+(?:push|login)|packages: write|secrets\./.test(gatewayOci), false, 'Gateway OCI gate must not publish images or receive secrets');
});

test('routine workflows cancel stale PR runs and bound every job', async () => {
  for (const file of ['ci.yml', 'sast.yml', 'gateway-oci.yml', 'gateway-r5e-recovery.yml']) {
    const workflow = await read(file);
    assert.match(workflow, /group: .+\$\{\{ github\.event\.pull_request\.number \|\| github\.ref \}\}/);
    assert.match(workflow, /cancel-in-progress: \$\{\{ github\.event_name == 'pull_request' \}\}/);
    const jobsText = workflow.slice(workflow.indexOf('\njobs:\n') + '\njobs:\n'.length);
    const headers = [...jobsText.matchAll(/^  ([a-z][\w-]*):$/gm)];
    assert.ok(headers.length > 0, `${file}: no jobs found`);
    for (const [index, header] of headers.entries()) {
      const body = jobsText.slice(header.index, headers[index + 1]?.index ?? jobsText.length);
      assert.match(body, /^    timeout-minutes: \d+$/m, `${file}: ${header[1]} has no timeout`);
    }
  }
});

test('CI keeps secret scanning universal while component builds use tested path selection', async () => {
  const ci = await read('ci.yml');
  assert.match(ci, /run: node scripts\/ci\/changed-components\.mjs/);
  for (const component of ['node', 'android', 'desktop']) {
    assert.match(ci, new RegExp(`  ${component}:[\\s\\S]*?needs: changes[\\s\\S]*?if: needs\\.changes\\.outputs\\.${component} == 'true'`));
  }
  assert.match(ci, /  secrets:\n    runs-on:/, 'secret scanning must not depend on the path-selection job');
  assert.match(ci, /if: steps\.filter\.outputs\.assets == 'true'[\s\S]*?run: cmp android\/app\/src\/main\/ic_launcher-playstore\.png desktop\/Packaging\/AppIcon\.png/);
  const desktopJob = ci.slice(ci.indexOf('\n  desktop:'), ci.indexOf('\n  secrets:'));
  assert.equal(desktopJob.includes('desktop:assets:test'), false, 'icon parity must not require a macOS runner');

  const sast = await read('sast.yml');
  assert.match(sast, /pull_request:\n    paths-ignore:[\s\S]*?docs\/\*\*/);
  assert.match(sast, /schedule:\n    - cron:/, 'weekly full SAST scan must remain enabled');
});

test('CI and Android release build Node workspaces once before running the full test gate', async () => {
  for (const file of ['ci.yml', 'android-release.yml']) {
    const workflow = await read(file);
    assert.match(workflow, /npm run test:ci/);
    assert.equal(/npm run build\s*\n\s*npm test/.test(workflow), false, `${file}: repeats builds through npm test`);
  }

  const rootPackage = JSON.parse(await readRoot('package.json'));
  assert.equal(rootPackage.scripts['test:ci'], 'npm run build && npm run test:built');
  assert.match(rootPackage.scripts['test:built'], /@hermes-remote\/protocol/);
  assert.match(rootPackage.scripts['test:built'], /@hermes-remote\/connector/);
  assert.match(rootPackage.scripts['test:built'], /@hermes-remote\/gateway/);
  assert.match(rootPackage.scripts['test:built'], /@hermes-remote\/release-server/);
  assert.match(rootPackage.scripts['test:built'], /test:scripts/);
});

test('Gateway ephemeral staging is manual, bounded, secretless, and production-isolated', async () => {
  const workflow = await read('gateway-ephemeral-staging.yml');
  assert.match(workflow, /^on:\n  workflow_dispatch:\s*$/m);
  assert.equal(/\n\s+(?:push|pull_request|schedule):/.test(workflow), false);
  assert.match(workflow, /permissions:\n  contents: read/);
  assert.match(workflow, /runs-on: ubuntu-24\.04/);
  assert.match(workflow, /timeout-minutes: 30/);
  assert.match(workflow, /group: gateway-r4-ephemeral-staging/);
  assert.match(workflow, /fetch-depth: 0/);
  assert.match(workflow, /image: postgres:18-alpine@sha256:[0-9a-f]{64}/);
  assert.match(workflow, /cancel-in-progress: false/);
  assert.match(workflow, /run: \.\/scripts\/test-gateway-staging-bootstrap\.sh/);
  assert.equal(/secrets\.|docker\s+(?:push|login)|packages: write|ssh\b|mrlgs\.net/.test(workflow), false);
});

test('R5-D managed baseline runs only on a disposable secretless host', async () => {
  const workflow = await read('gateway-r5d-managed-baseline.yml');
  assert.match(workflow, /^on:\n  workflow_dispatch:\s*$/m);
  assert.equal(/\n\s+(?:push|pull_request|schedule):/.test(workflow), false);
  assert.match(workflow, /permissions:\n  contents: read/);
  assert.match(workflow, /runs-on: ubuntu-24\.04/);
  assert.match(workflow, /timeout-minutes: 30/);
  assert.match(workflow, /group: gateway-r5d-managed-baseline/);
  assert.match(workflow, /image: postgres:18-alpine@sha256:[0-9a-f]{64}/);
  assert.match(workflow, /HERMES_R5D_ONLY: "1"/);
  assert.match(workflow, /run: \.\/scripts\/test-gateway-staging-bootstrap\.sh/);
  assert.equal(/secrets\.|docker\s+(?:push|login)|packages: write|ssh\b|mrlgs\.net|47\.239\./.test(workflow), false);
  const harness = await readRoot('scripts/test-gateway-staging-bootstrap.sh');
  assert.match(harness, /GATEWAY_R5D_MANAGED_BASELINE_OK/);
  assert.match(harness, /scripts\/production-baseline\.mjs/);
});

test('R5-E recovery uses only disposable PostgreSQL 18 and an immutable local image', async () => {
  const workflow = await read('gateway-r5e-recovery.yml');
  assert.match(workflow, /pull_request:[\s\S]*workflow_dispatch:/);
  assert.match(workflow, /permissions:\n  contents: read/);
  assert.match(workflow, /runs-on: ubuntu-24\.04/);
  assert.match(workflow, /timeout-minutes: 25/);
  assert.match(workflow, /image: postgres:18-alpine@sha256:[0-9a-f]{64}/);
  assert.match(workflow, /run: node scripts\/test\/postgresql-recovery-e2e\.mjs/);
  assert.match(workflow, /R5E_SOURCE_POSTGRES_CONTAINER_ID: \$\{\{ job\.services\.postgres\.id \}\}/);
  assert.match(workflow, /R5E_RESTORE_POSTGRES_CONTAINER_ID: \$\{\{ job\.services\.postgres_restore\.id \}\}/);
  assert.match(workflow, /R5E_TARGET_IMAGE_ID: \$\{\{ steps\.image\.outputs\.id \}\}/);
  assert.equal(/secrets\.|docker\s+(?:push|login)|packages: write|ssh\b|mrlgs\.net|47\.239\./.test(workflow), false);
  const harness = await readRoot('scripts/test/postgresql-recovery-e2e.mjs');
  assert.match(harness, /capturePostgresqlBackup/);
  assert.match(harness, /verifyPostgresqlRestore/);
  assert.match(harness, /publishPostgresqlBackupStatus/);
  assert.match(harness, /account_smoke_transaction_not_rolled_back/);
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
