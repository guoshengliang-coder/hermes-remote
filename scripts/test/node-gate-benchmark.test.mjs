import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

test('benchmark refuses incomplete/mismatched test evidence and preserves negative speedups', async () => {
  const result = spawnSync('python3', ['-B', '-c', String.raw`
import importlib.util, json
from pathlib import Path
spec = importlib.util.spec_from_file_location('benchmark', 'scripts/ci/benchmark-node-gate.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
log = '\n'.join(['# tests 10\n# pass 8\n# fail 0\n# skipped 2'] * 5)
counts = m.counts_from_log(log)
assert counts['tests'] == [10] * 5
for invalid in [log.replace('# pass 8', '# pass 7', 1), log.replace('# fail 0', '# fail 1', 1), '# tests 10']:
    try: m.counts_from_log(invalid)
    except ValueError: pass
    else: raise AssertionError('accepted incomplete or failing evidence')
rows = [{'variant': v, 'elapsedSeconds': seconds, 'counts': counts.copy()} for v, seconds in zip(m.ORDER, [70, 60, 62, 72, 74, 64])]
summary = m.summarize(rows)
assert summary['medians'] == {'old': 72, 'new': 62}
assert summary['savedSeconds'] == 10
try: m.summarize(rows[:-1])
except ValueError: pass
else: raise AssertionError('accepted missing samples')
rows[-1]['counts'] = {**counts, 'skipped': [1] * 5}
try: m.summarize(rows)
except ValueError: pass
else: raise AssertionError('accepted unequal test counts')
rows[-1]['counts'] = counts.copy()
for row in rows:
    if row['variant'] == 'new': row['elapsedSeconds'] += 20
assert m.summarize(rows)['savedSeconds'] == -10
error = m.ERROR
assert error['retryable'] and error['recoveryAction'].endswith('retry')
assert error['code'] in Path('docs/ERROR_HANDLING.md').read_text()
print(json.dumps(error, ensure_ascii=False))
`], {encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
  const error = JSON.parse(result.stdout);
  assert.equal(error.code, 'HR-CI-001');
  assert.match(error.summaryZh, /构建对比测试/);
  assert.match(error.summaryEn, /build comparison/);
  const registry = await readFile('docs/ERROR_HANDLING.md', 'utf8');
  assert.ok(registry.includes(error.summaryZh) && registry.includes(error.summaryEn));
});

test('benchmark cleanup refuses tracked outputs and symlinked directories', () => {
  const result = spawnSync('python3', ['-B', '-c', String.raw`
import importlib.util, pathlib, subprocess, tempfile
spec = importlib.util.spec_from_file_location('benchmark', 'scripts/ci/benchmark-node-gate.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    dist = root / 'protocol/dist'
    dist.mkdir(parents=True)
    (dist / 'owned.js').write_text('generated')
    subprocess.run(['git', 'add', '.'], cwd=root, check=True)
    try: m.clean_outputs(root)
    except ValueError: pass
    else: raise AssertionError('deleted tracked output')
    assert (dist / 'owned.js').exists()
with tempfile.TemporaryDirectory() as tmp:
    root = pathlib.Path(tmp)
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    (root / 'protocol').mkdir()
    target = root / 'outside'
    target.mkdir()
    (target / 'keep').write_text('keep')
    (root / 'protocol/dist').symlink_to(target, target_is_directory=True)
    try: m.clean_outputs(root)
    except ValueError: pass
    else: raise AssertionError('followed output symlink')
    assert (target / 'keep').read_text() == 'keep'
`], {encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
});

test('branch benchmark has read-only permissions and no release credentials or production actions', async () => {
  const workflow = await readFile('.github/workflows/node-gate-benchmark.yml', 'utf8');
  assert.match(workflow, /branches: \[codex\/github-flow-preflight\]/);
  assert.match(workflow, /permissions:\n  contents: read/);
  assert.match(workflow, /persist-credentials: false/);
  assert.match(workflow, /runs-on: macos-15/);
  assert.match(workflow, /timeout-minutes: 30/);
  assert.match(workflow, /node-version: 22\.23\.2/);
  assert.match(workflow, /python3 scripts\/ci\/benchmark-node-gate\.py/);
  assert.doesNotMatch(workflow, /secrets\.|environment:|tags:|workflow_dispatch:|publish-|notarytool|gradlew|packages: write/);
});
