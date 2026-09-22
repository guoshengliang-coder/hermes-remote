import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

import {
  RED_LIGHT_SOURCES,
  classifyChecks,
  classifyRuns,
  redLightFiles,
  refusal,
  waitFor,
} from '../merge-when-green.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const SCRIPT = path.join(ROOT, 'scripts', 'merge-when-green.mjs');

const run = (name, status, conclusion, workflowName = 'CI') => ({__typename: 'CheckRun', name, status, conclusion, workflowName});

test('checks pass only when every one completed with success, skipped or neutral', () => {
  assert.equal(classifyChecks([run('node', 'COMPLETED', 'SUCCESS'), run('android', 'COMPLETED', 'SKIPPED'), run('x', 'COMPLETED', 'NEUTRAL')]).state, 'success');
  const pending = classifyChecks([run('node', 'COMPLETED', 'SUCCESS'), run('android', 'IN_PROGRESS', null)]);
  assert.equal(pending.state, 'pending');
  assert.deepEqual(pending.pending, ['CI / android']);
});

test('one failure settles the result without waiting for the rest', () => {
  for (const conclusion of ['FAILURE', 'CANCELLED', 'TIMED_OUT', 'ACTION_REQUIRED', 'STARTUP_FAILURE']) {
    const result = classifyChecks([run('node', 'COMPLETED', conclusion), run('android', 'QUEUED', null)]);
    assert.equal(result.state, 'failure', conclusion);
    assert.deepEqual(result.failed, [`CI / node (${conclusion})`]);
  }
});

test('no reported checks is its own state, never success', () => {
  assert.equal(classifyChecks([]).state, 'none');
  assert.equal(classifyChecks(undefined).state, 'none');
});

test('commit statuses are judged by their state', () => {
  const status = (state) => ({__typename: 'StatusContext', context: 'ext', state});
  assert.equal(classifyChecks([status('SUCCESS')]).state, 'success');
  assert.equal(classifyChecks([status('PENDING')]).state, 'pending');
  assert.equal(classifyChecks([status('ERROR')]).state, 'failure');
});

test('main runs count only push-triggered workflows', () => {
  const runs = [
    {name: 'CI', event: 'push', status: 'completed', conclusion: 'success'},
    {name: 'CI', event: 'pull_request', status: 'completed', conclusion: 'failure'},
  ];
  assert.equal(classifyRuns(runs).state, 'success');
  assert.equal(classifyRuns([{name: 'CI', event: 'push', status: 'in_progress', conclusion: ''}]).state, 'pending');
  assert.equal(classifyRuns([{name: 'SAST', event: 'push', status: 'completed', conclusion: 'failure'}]).state, 'failure');
  assert.equal(classifyRuns([runs[1]]).state, 'none');
});

const diffFor = (file, body) => `diff --git a/${file} b/${file}\nindex 1..2 100644\n--- a/${file}\n+++ b/${file}\n@@ -1,3 +1,3 @@\n${body}\n`;

test('a version bump is red; an ordinary edit to the same file is not', () => {
  assert.deepEqual(redLightFiles(diffFor('android/app/build.gradle.kts', '-val appVersionName = "0.1.140"\n+val appVersionName = "0.1.141"')), ['android/app/build.gradle.kts']);
  assert.deepEqual(redLightFiles(diffFor('android/app/build.gradle.kts', ' android {\n+    implementation(libs.foo)')), []);
  assert.deepEqual(redLightFiles(diffFor('gateway/package.json', '-  "version": "0.4.19",\n+  "version": "0.4.20",')), ['gateway/package.json']);
  assert.deepEqual(redLightFiles(diffFor('gateway/package.json', '+    "zod": "^4.0.0",')), []);
  assert.deepEqual(redLightFiles(diffFor('gateway/release-contract.json', '+  "note": 1')), ['gateway/release-contract.json']);
  assert.deepEqual(redLightFiles(diffFor('gateway/src/index.ts', '+  "version": "1"')), []);
});

test('a plist bump that changes only the value line is still red', () => {
  const bump = '   <key>CFBundleShortVersionString</key>\n-  <string>0.2.25</string>\n+  <string>0.2.26</string>';
  assert.deepEqual(redLightFiles(diffFor('desktop/Packaging/Info.plist', bump)), ['desktop/Packaging/Info.plist']);
  const other = '   <key>LSMinimumSystemVersion</key>\n-  <string>14.0</string>\n+  <string>15.0</string>';
  assert.deepEqual(redLightFiles(diffFor('desktop/Packaging/Info.plist', other)), []);
});

test('every red-light source is still named in docs/INTEGRATION.md table 1', async () => {
  const doc = await readFile(path.join(ROOT, 'docs', 'INTEGRATION.md'), 'utf8');
  const row = doc.split('\n').find((line) => line.startsWith('| 版本真相源'));
  assert.ok(row, 'table 1 版本真相源 row not found');
  for (const source of RED_LIGHT_SOURCES) assert.ok(row.includes(source.doc), `${source.file}: "${source.doc}" missing from table 1`);
});

test('merged, closed, draft and conflicting PRs are refused', () => {
  assert.equal(refusal({state: 'OPEN', isDraft: false, mergeable: 'MERGEABLE'}), null);
  assert.equal(refusal({state: 'OPEN', isDraft: false, mergeable: 'UNKNOWN'}), null);
  assert.match(refusal({state: 'MERGED'}), /MERGED/);
  assert.match(refusal({state: 'OPEN', isDraft: true}), /draft/);
  assert.match(refusal({state: 'OPEN', isDraft: false, mergeable: 'CONFLICTING'}), /conflict/);
});

function clock(results) {
  let t = 0;
  const seen = [];
  return {
    seen,
    options: {
      probe: async () => results.shift(),
      classify: (state) => ({state, pending: state === 'pending' ? ['CI / node'] : [], failed: state === 'failure' ? ['CI / node (FAILURE)'] : []}),
      interval: 10,
      timeout: 100,
      grace: 30,
      sleep: async (ms) => { t += ms; },
      now: () => t,
      log: (line) => seen.push(line),
    },
  };
}

test('success must hold on two consecutive polls', async () => {
  const c = clock(['pending', 'success', 'pending', 'success', 'success']);
  assert.equal((await waitFor(c.options)).state, 'success');
  assert.deepEqual(c.seen, ['  pending: CI / node', '  success', '  pending: CI / node', '  success']);
});

test('failure returns immediately', async () => {
  const c = clock(['pending', 'failure', 'success']);
  assert.equal((await waitFor(c.options)).state, 'failure');
});

test('no checks are tolerated for the grace period, then reported', async () => {
  assert.equal((await waitFor(clock(['none', 'none', 'success', 'success']).options)).state, 'success');
  assert.equal((await waitFor(clock(['none', 'none', 'none', 'none', 'none']).options)).state, 'none');
});

test('pending past the timeout reports timeout', async () => {
  assert.equal((await waitFor(clock(Array(20).fill('pending')).options)).state, 'timeout');
});

test('the CLI rejects a missing PR number without calling gh', () => {
  assert.throws(() => execFileSync('node', [SCRIPT], {stdio: 'pipe'}), (error) => error.status === 4 && /usage:/.test(error.stderr));
  assert.throws(() => execFileSync('node', [SCRIPT, '12', '--bogus'], {stdio: 'pipe'}), (error) => error.status === 4 && /unknown argument/.test(error.stderr));
});
