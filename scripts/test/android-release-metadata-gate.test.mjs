import assert from 'node:assert/strict';
import {execFileSync, spawnSync} from 'node:child_process';
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import test from 'node:test';
import {decideAndroidReleaseMetadata, formatGateDecision} from '../ci/android-release-metadata-gate.mjs';
import {applyGradleVersions, applyReadme} from '../bump-android-release.mjs';

const BASE = 'a'.repeat(40), HEAD = 'b'.repeat(40), REPO = 'example/repo';
const run = (overrides = {}) => ({event: 'push', head_branch: 'main', head_sha: BASE,
  path: '.github/workflows/ci.yml', head_repository: {full_name: REPO},
  status: 'completed', conclusion: 'success', ...overrides});
const response = (runs = [run()], total = runs.length) => new Response(JSON.stringify({total_count: total, workflow_runs: runs}));
const options = overrides => ({eventName: 'pull_request', baseRef: 'main', baseSha: BASE, headSha: HEAD,
  repository: REPO, token: 'synthetic-token', classify: () => ({candidate: true}),
  fetchImpl: async () => response(), ...overrides});

test('only exact PR metadata plus successful CI at that main BASE can skip', async () => {
  let calls = 0;
  const result = await decideAndroidReleaseMetadata(options({fetchImpl: async (url, init) => {
    calls++;
    const parsed = new URL(url);
    assert.equal(parsed.origin, 'https://api.github.com');
    assert.equal(parsed.pathname, `/repos/${REPO}/actions/workflows/ci.yml/runs`);
    assert.equal(parsed.searchParams.get('head_sha'), BASE);
    assert.equal(parsed.searchParams.get('event'), 'push');
    assert.equal(parsed.searchParams.get('branch'), 'main');
    assert.equal(init.headers.Authorization, 'Bearer synthetic-token');
    assert.equal(init.redirect, 'error');
    assert.equal(init.signal.aborted, false);
    return response();
  }}));
  assert.deepEqual(result, {skipAndroid: true, reason: 'exact_metadata_on_green_main'});
  assert.equal(calls, 1);
  assert.match(formatGateDecision(result), /Main, local prepare, the full package gate/);
});

for (const [label, change] of [
  ['main push', {eventName: 'push'}], ['workflow dispatch', {eventName: 'workflow_dispatch'}],
  ['different PR base', {baseRef: 'dev'}], ['missing SHA', {baseSha: ''}],
  ['revision expression', {headSha: 'HEAD'}], ['untrusted repository URL', {repository: '../private'}],
  ['source changes', {classify: () => ({candidate: false})}], ['classifier error', {classify: () => {throw new Error('private');}}],
  ['missing token', {token: ''}],
]) test(`${label} falls back before any API access`, async () => {
  let called = false;
  const result = await decideAndroidReleaseMetadata(options({...change, fetchImpl: async () => {called = true; throw new Error();}}));
  assert.equal(result.skipAndroid, false);
  assert.equal(called, false);
});

for (const [field, value] of Object.entries({event: 'pull_request', head_branch: 'dev', head_sha: HEAD,
  path: '.github/workflows/sast.yml', head_repository: {full_name: 'attacker/fork'}, status: 'in_progress', conclusion: 'failure'})) {
  test(`base CI with mismatched ${field} cannot grant an exemption`, async () => {
    assert.equal((await decideAndroidReleaseMetadata(options({fetchImpl: async () => response([run({[field]: value})])}))).skipAndroid, false);
  });
}

for (const conclusion of ['cancelled', 'skipped', 'neutral', 'timed_out', null]) {
  test(`base CI ${conclusion} is not success`, async () => {
    assert.equal((await decideAndroidReleaseMetadata(options({fetchImpl: async () => response([run({conclusion})])}))).skipAndroid, false);
  });
}

for (const [label, fetchImpl] of [
  ['no runs', async () => response([])],
  ['unconfirmed older failure', async () => response([run(), run({conclusion: 'failure'})])],
  ['truncated run list', async () => response([run()], 2)],
  ['invalid count', async () => new Response(JSON.stringify({total_count: '1', workflow_runs: [run()]}))],
  ['missing list', async () => new Response('{}')],
  ['permission denied', async () => new Response('synthetic-private-body', {status: 403})],
  ['API unavailable', async () => {throw new Error('synthetic-private-body');}],
  ['invalid JSON', async () => new Response('synthetic-private-body')],
  ['oversize body', async () => new Response('x'.repeat(1024 * 1024 + 1))],
  ['empty body', async () => new Response(null)],
  ['invalid UTF-8', async () => new Response(Uint8Array.from([0xff]))],
]) test(`${label} retains full checks and exposes no API body`, async () => {
  const result = await decideAndroidReleaseMetadata(options({fetchImpl}));
  assert.equal(result.skipAndroid, false);
  assert.doesNotMatch(JSON.stringify(result) + formatGateDecision(result), /synthetic-private-body|synthetic-token/);
});

test('API deadline aborts and retains full validation', async () => {
  const result = await decideAndroidReleaseMetadata(options({timeoutMs: 10, fetchImpl: async (_url, {signal}) =>
    new Promise((resolve, reject) => {
      const keepAlive = setTimeout(resolve, 1000);
      signal.addEventListener('abort', () => {clearTimeout(keepAlive); reject(signal.reason);}, {once: true});
    })}));
  assert.equal(result.skipAndroid, false);
});

const MODULES = ['scripts/ci/android-release-metadata-gate.mjs', 'scripts/ci/android-release-metadata.mjs',
  'scripts/bump-android-release.mjs', 'release-server/src/schema.mjs'];
const untrustedModule = "import {appendFileSync,writeFileSync} from 'node:fs'; writeFileSync('untrusted-executed', 'yes'); appendFileSync(process.env.GITHUB_OUTPUT, 'skip_android=true\\n');";
const workflow = readFileSync('.github/workflows/ci.yml', 'utf8');
const shellBlock = /      - id: release_metadata\n[\s\S]*?        run: \|\n([\s\S]*?)(?=      - name: Verify shared app icon)/.exec(workflow)?.[1];
const bootstrap = shellBlock?.split('\n').map(line => line.slice(10)).join('\n');

function fixture(t, {missingBaseModule = false} = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'metadata trusted base '));
  t.after(() => rmSync(root, {recursive: true, force: true}));
  const git = (...args) => execFileSync('git', ['-C', root, '-c', 'core.hooksPath=/dev/null', ...args], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
  const write = (file, text) => {mkdirSync(path.dirname(path.join(root, file)), {recursive: true}); writeFileSync(path.join(root, file), text);};
  const commit = () => {git('add', '-A'); git('-c', 'user.name=Gate test', '-c', 'user.email=gate@example.invalid', 'commit', '-qm', 'fixture'); return git('rev-parse', 'HEAD');};
  git('init', '-q');
  for (const file of MODULES) if (!(missingBaseModule && file === MODULES[0])) write(file, readFileSync(file));
  const gradle = 'val appVersionName = "0.1.194"\nval appVersionCode = 195\n';
  const readme = '- Version 0.1.194 previous\n\nHermes-Remote-0.1.194-debug.apk\n';
  write('android/app/build.gradle.kts', gradle); write('android/README.md', readme);
  const base = commit();
  write('android/app/build.gradle.kts', applyGradleVersions(gradle, {versionName: '0.1.195', versionCode: 196}));
  write('android/README.md', applyReadme(readme, {current: '0.1.194', next: '0.1.195', summary: 'synthetic release'}));
  write('android/releases/0.1.195.json', JSON.stringify({channel: 'internal', releaseNotes: ['synthetic private note']}, null, 2) + '\n');
  return {root, write, commit, base, head: commit(), execute(overrides = {}) {
    assert.ok(bootstrap, 'workflow must have the tested trusted bootstrap');
    write('output', ''); write('summary', '');
    write('preload.mjs', `globalThis.fetch = async () => new Response(JSON.stringify({total_count:1,workflow_runs:[{event:'push',head_branch:'main',head_sha:process.env.BASE_SHA,path:'.github/workflows/ci.yml',head_repository:{full_name:'example/repo'},status:'completed',conclusion:'success'}]}));`);
    const proc = spawnSync('bash', ['-e', '-o', 'pipefail', '-c', bootstrap], {cwd: root, encoding: 'utf8',
      env: {...process.env, EVENT_NAME: 'pull_request', BASE_REF: 'main', BASE_SHA: base, HEAD_SHA: this.head,
        GITHUB_REPOSITORY: REPO, GH_TOKEN: 'synthetic-token', RUNNER_TEMP: root,
        GITHUB_OUTPUT: path.join(root, 'output'), GITHUB_STEP_SUMMARY: path.join(root, 'summary'),
        NODE_OPTIONS: `--import=${pathToFileURL(path.join(root, 'preload.mjs')).href}`, ...overrides}});
    assert.equal(proc.status, 0, proc.stderr);
    const output = readFileSync(path.join(root, 'output'), 'utf8');
    const summary = readFileSync(path.join(root, 'summary'), 'utf8');
    this.summary = summary;
    assert.doesNotMatch(proc.stdout + proc.stderr + output + summary, /synthetic-token|synthetic private note/);
    return output.trim().split('\n').at(-1);
  }};
}

test('the real workflow bootstrap grants the exact fixture PR and rejects a main push', t => {
  const f = fixture(t);
  assert.equal(f.execute(), 'skip_android=true', f.summary);
  assert.equal(f.execute({EVENT_NAME: 'push'}), 'skip_android=false');
  assert.equal(f.execute({BASE_REF: 'dev'}), 'skip_android=false');
});

test('bootstrap with no BASE gate never falls back to the PR module', t => {
  const f = fixture(t, {missingBaseModule: true});
  f.write(MODULES[0], untrustedModule);
  f.head = f.commit();
  assert.equal(f.execute(), 'skip_android=false');
  assert.equal(existsSync(path.join(f.root, 'untrusted-executed')), false);
});

test('PR gate edits are rejected by BASE code without executing the PR module', t => {
  const f = fixture(t);
  f.write(MODULES[0], untrustedModule);
  f.head = f.commit();
  assert.equal(f.execute(), 'skip_android=false');
  assert.equal(existsSync(path.join(f.root, 'untrusted-executed')), false);
});

test('bootstrap uses immutable BASE modules despite worktree module corruption', t => {
  const f = fixture(t);
  for (const file of MODULES) f.write(file, 'throw new Error("worktree module executed");');
  assert.equal(f.execute(), 'skip_android=true', f.summary);
});

test('only the changes job receives read-only Actions access and only Android jobs consume exemption', () => {
  const changes = workflow.split('\n  changes:\n')[1].split('\n  node:\n')[0];
  assert.match(changes, /permissions:\n      contents: read\n      actions: read/);
  assert.match(changes, /GH_TOKEN: \$\{\{ github\.token \}\}/);
  assert.doesNotMatch(changes, /contents: write|actions: write|secrets\./);
  assert.equal((workflow.match(/needs\.changes\.outputs\.skip_android != 'true'/g) ?? []).length, 2);
});
