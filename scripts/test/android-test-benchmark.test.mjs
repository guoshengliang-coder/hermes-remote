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
 for encoding in ('utf-8','utf-16'):
  for declaration in ('<!DOCTYPE testsuite [<!ENTITY a "expanded">]>', '<!DOCTYPE testsuite SYSTEM "file:///synthetic-no-read">'):
   xml='<?xml version="1.0" encoding="'+encoding+'"?>'+declaration+'<testsuite name="com.example.Test" tests="1" time="1"><testcase classname="com.example.Test" name="a" time="1"/></testsuite>'
   file.write_bytes(xml.encode(encoding))
   try:m.junit_summary(root)
   except ValueError as error:assert str(error)=='unsupported_xml'
   else:raise AssertionError('DTD/entity-bearing XML accepted')
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

test('timeout diagnostics target test workers only and tolerate unavailable jcmd', () => {
  const proc = spawnSync('python3', ['-B', '-c', String.raw`
import importlib.util,subprocess,tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('m','scripts/ci/benchmark-android-tests.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory() as d:
 root=Path(d)
 with patch.object(m.subprocess,'run',side_effect=[SimpleNamespace(stdout='12 GradleDaemon\n34 worker.org.gradle.process.internal.worker.GradleWorkerMain\n'),SimpleNamespace(returncode=0)]) as run:
  m.snapshot_threads(root,'sample',2)
  assert run.call_count==2
  assert run.call_args_list[1].args[0]==['jcmd','34','Thread.print','-l']
 with patch.object(m.subprocess,'run',side_effect=subprocess.TimeoutExpired('jcmd',15)):
  m.snapshot_threads(root,'sample',6)
 assert (root/'sample-diagnostics-6.txt').read_text()=='Thread snapshot unavailable.\n'
`], {encoding: 'utf8'});
  assert.equal(proc.status, 0, proc.stderr);
});

test('Android research keeps complete tests, compiler reuse checks and watchdog', () => {
  const helper = readFileSync('scripts/ci/benchmark-android-tests.py', 'utf8');
  assert.doesNotMatch(helper, /--tests|excludeCategories|ignoreFailures|maxHeapSize\s*=/);
  assert.match(helper, /:verifyTestWatchdog/);
  assert.match(helper, /watchdogEnabled/);
  assert.match(helper, /compiler_reran_between_samples/);
  assert.match(helper, /test_coverage_changed/);
  assert.match(helper, /TASK, "--rerun"/);
  assert.match(helper, /ORDER = \("baseline", "candidate", "candidate", "baseline"\)/);
});
