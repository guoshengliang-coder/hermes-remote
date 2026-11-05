import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import test from 'node:test';

test('Swift cache comparison requires complete identical evidence and preserves regressions', () => {
  const proc = spawnSync('python3', ['-B', '-c', String.raw`
import importlib.util, copy, json
from pathlib import Path
spec = importlib.util.spec_from_file_location('benchmark', 'scripts/ci/benchmark-swift-cache.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
evidence = m.test_counts('Executed 3 tests, with 0 failures (0 unexpected)\nExecuted 740 tests, with 0 failures (0 unexpected)')
assert evidence['tests'] == 740
for bad in ['', 'Executed 0 tests, with 0 failures (0 unexpected)', 'Executed 740 tests, with 1 failures (0 unexpected)']:
    try: m.test_counts(bad)
    except ValueError: pass
    else: raise AssertionError('invalid tests accepted')
cold = {key: key for key in m.IDENTITY}
cold.update(mode='unit', variant='cold', cacheHit=False, evidence=evidence, returncode=0, totalSeconds=100)
cached = dict(cold, variant='cached', cacheHit=True, totalSeconds=70)
assert m.compare(cold, cached)['savedSeconds'] == 30
cached['totalSeconds'] = 110
assert m.compare(cold, cached)['savedSeconds'] == -10
for key in (*m.IDENTITY, 'mode', 'evidence', 'cacheHit', 'returncode', 'totalSeconds'):
    bad = copy.deepcopy(cached)
    bad[key] = False if key == 'cacheHit' else 1 if key == 'returncode' else float('nan') if key == 'totalSeconds' else 'different'
    try: m.compare(cold, bad)
    except ValueError: pass
    else: raise AssertionError('mismatched evidence accepted: ' + key)
assert m.COMMANDS == {'unit':['npm','run','desktop:test'], 'app':['npm','run','desktop:app']}
try: m.outside_checkout(Path('/tmp/checkout'), Path('/tmp/checkout/results'))
except ValueError: pass
else: raise AssertionError('unsafe outputs accepted')
registry = Path('docs/ERROR_HANDLING.md').read_text()
for key in ('code','summaryZh','summaryEn'): assert m.ERROR[key] in registry
print(json.dumps(m.ERROR, ensure_ascii=False))
`], {encoding: 'utf8'});
  assert.equal(proc.status, 0, proc.stderr);
  assert.equal(JSON.parse(proc.stdout).code, 'HR-CI-001');
});

test('Swift measurement CLI redacts invalid arguments and requires actual cache hits', () => {
  const proc = spawnSync('python3', ['-B', 'scripts/ci/benchmark-swift-cache.py', 'run', 'unit', 'cached', '/tmp', 'synthetic-secret'], {encoding: 'utf8'});
  assert.equal(proc.status, 1);
  assert.match(proc.stderr, /HR-CI-001/);
  assert.doesNotMatch(proc.stdout + proc.stderr, /synthetic-secret|Traceback/);
});
