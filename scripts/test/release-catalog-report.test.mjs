import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {test} from 'node:test';

/**
 * Covers scripts/lib/release_catalog_report.py, the reporting half of
 * scripts/retire-android-releases.sh.
 *
 * It exists because the first real retire run never reached the server: the reporting was inline
 * shell, and it failed twice for reasons that had nothing to do with retiring. `python3 -c` with
 * an f-string containing escaped quotes is a SyntaxError before Python 3.12 (macOS ships 3.9),
 * and `curl | python3 <<'PY'` feeds the program to itself because the heredoc *is* stdin. Both
 * were invisible until the operation was already underway against production.
 *
 * So these tests run the real file with the real interpreter. A syntax or quoting regression
 * fails here instead of halfway through a catalog mutation.
 */

const REPORT = new URL('../lib/release_catalog_report.py', import.meta.url).pathname;

function indexFile(count, {latestOffset = 0} = {}) {
  const dir = mkdtempSync(path.join(tmpdir(), 'catalog-'));
  const versions = [];
  for (let code = 1; code <= count; code += 1) {
    versions.push({
      versionName: `0.1.${code}`, versionCode: code,
      fileName: `Hermes-Remote-0.1.${code}-debug.apk`,
    });
  }
  const file = path.join(dir, 'index.json');
  writeFileSync(file, JSON.stringify({
    schemaVersion: 1, channel: 'internal',
    latestVersionCode: count + latestOffset,
    generatedAt: '2026-01-01T00:00:00Z',
    // Deliberately unsorted: the server guarantees nothing about array order.
    versions: versions.slice().reverse(),
  }));
  return file;
}

const run = (...args) => execFileSync('python3', [REPORT, ...args], {encoding: 'utf8'});

test('summary reports count, latest and the ends of the range', () => {
  const out = run('summary', indexFile(100));
  assert.match(out, /100 versions/);
  assert.match(out, /latest 100/);
  assert.match(out, /oldest 0\.1\.1,/);
  assert.match(out, /newest 0\.1\.100/);
});

test('plan names every version that would go, and keeps the newest N', () => {
  const out = run('plan', indexFile(40), '30');
  assert.match(out, /would keep 30: 0\.1\.11 \.\. 0\.1\.40/);
  assert.match(out, /would retire 10: 0\.1\.1 \.\. 0\.1\.10/);
  for (let code = 1; code <= 10; code += 1) assert.ok(out.includes(`0.1.${code}`), `missing 0.1.${code}`);
  assert.ok(!out.includes('0.1.11,'), 'a kept version appeared in the retire list');
});

test('plan says so when nothing is old enough to retire', () => {
  const out = run('plan', indexFile(5), '30');
  assert.match(out, /would keep 5/);
  assert.match(out, /would retire 0/);
});

test('verify passes only on an index that is exactly the kept set', () => {
  assert.match(run('verify', indexFile(30), '30'), /30 versions/);
  assert.throws(() => run('verify', indexFile(31), '30'), /expected 30 versions, index has 31/);
});

test('verify rejects an index whose latest is not its newest entry', () => {
  assert.throws(() => run('verify', indexFile(30, {latestOffset: -5}), '30'),
    /latestVersionCode is not the newest entry/);
});

test('keep below 1 is refused, so the current release can never be dropped', () => {
  for (const keep of ['0', '-1']) {
    assert.throws(() => run('plan', indexFile(10), keep), /KEEP must be a positive integer/);
  }
});

test('the interpreter actually parses it — the bug that started this', () => {
  // py_compile is what would have caught the f-string backslash before it reached production.
  //
  // The output goes to a temp file, NOT the default scripts/lib/__pycache__/. Writing it in place
  // left an untracked directory in the source tree, and the publisher refuses to run against a
  // dirty worktree — so this test, added to catch a release bug, blocked the release instead.
  const cfile = path.join(mkdtempSync(path.join(tmpdir(), 'pyc-')), 'report.pyc');
  execFileSync('python3', [
    '-c',
    'import py_compile,sys; py_compile.compile(sys.argv[1], cfile=sys.argv[2], doraise=True)',
    REPORT, cfile,
  ]);
});
