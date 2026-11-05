import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import test from 'node:test';

test('Android measurement validates task timing and complete JUnit identities', () => {
  const proc = spawnSync('python3', ['-B', '-c', String.raw`
import importlib.util,json,tempfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('m','scripts/ci/benchmark-android-tests.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
assert m.seconds('2m38.20s')==158.2
assert m.seconds('1h2m3.50s')==3723.5
for bad in ('','2m','-1s','NaNs'):
 try:m.seconds(bad)
 except ValueError:pass
 else:raise AssertionError('invalid duration accepted')
html='<table><tr><td>:app:testDebugUnitTest</td><td>2m38.20s</td><td></td></tr><tr><td>:app:compileDebugKotlin</td><td>0s</td><td>UP-TO-DATE</td></tr></table>'
assert m.profile_tasks(html)[':app:testDebugUnitTest']=={'seconds':158.2,'result':''}
try:m.profile_tasks(html+html)
except ValueError:pass
else:raise AssertionError('duplicate task accepted')
with tempfile.TemporaryDirectory() as d:
 root=Path(d);file=root/'TEST-a.xml'
 def write(cases,tests=None):
  file.write_text(f'<testsuite name="com.example.Test" tests="{tests if tests is not None else len(cases)}" failures="0" errors="0" skipped="0" time="3.0">'+''.join(cases)+'<system-out>synthetic-private-body</system-out></testsuite>')
 def case(name):return f'<testcase classname="com.example.Test" name="{name}" time="1.0"/>'
 write([case('a'),case('b')]);first=m.junit_summary(root)
 assert first['counts']=={'tests':2,'failures':0,'errors':0,'skipped':0}
 assert 'synthetic-private-body' not in json.dumps(first)
 write([case('b'),case('a')]);assert m.junit_summary(root)['identityHash']==first['identityHash']
 write([case('a'),case('a')]);assert m.junit_summary(root)['identityHash']!=first['identityHash']
 for cases,total in [([case('a')],2),([case('a').replace('/>','><failure/></testcase>')],1),([case('a').replace('time="1.0"','time="nan"')],1)]:
  write(cases,tests=total)
  try:m.junit_summary(root)
  except ValueError:pass
  else:raise AssertionError('incomplete/failing/nonfinite results accepted')
print(json.dumps(m.ERROR,ensure_ascii=False))
`], {encoding: 'utf8'});
  assert.equal(proc.status, 0, proc.stderr);
  const error = JSON.parse(proc.stdout);
  assert.equal(error.code, 'HR-CI-001');
  const registry = readFileSync('docs/ERROR_HANDLING.md', 'utf8');
  for (const key of ['code', 'summaryZh', 'summaryEn']) assert.ok(registry.includes(error[key]));
});

test('Android research rejects unsafe output paths without exposing raw context', () => {
  const proc = spawnSync('python3', ['-B', 'scripts/ci/benchmark-android-tests.py'], {
    encoding: 'utf8', env: {...process.env, BENCHMARK_OUTPUT_DIR: 'synthetic-secret-output'},
  });
  assert.equal(proc.status, 1);
  assert.match(proc.stderr, /HR-CI-001/);
  assert.doesNotMatch(proc.stdout + proc.stderr, /synthetic-secret-output|Traceback/);
});

test('research workflow keeps full tests, watchdog and source isolation', () => {
  const workflow = readFileSync('.github/workflows/android-test-research.yml', 'utf8');
  const helper = readFileSync('scripts/ci/benchmark-android-tests.py', 'utf8');
  assert.match(workflow, /branches: \[codex\/android-test-research\]/);
  assert.match(workflow, /persist-credentials: false/);
  assert.match(workflow, /timeout-minutes: 35/);
  assert.doesNotMatch(workflow, /secrets\.|environment:|assembleDebug|--scan|pull_request:|workflow_dispatch:/);
  assert.doesNotMatch(helper, /--tests|excludeCategories|ignoreFailures|maxHeapSize\s*=/);
  assert.match(helper, /:verifyTestWatchdog/);
  assert.match(helper, /TASK, "--rerun"/);
  assert.match(helper, /ORDER = \("baseline", "candidate", "candidate", "baseline"\)/);
});
