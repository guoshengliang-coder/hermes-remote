import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import test from 'node:test';

test('watchdog runner discovers all cases, bounds parallelism and requires complete success', () => {
  const proc = spawnSync('python3', ['-B', '-c', String.raw`
import contextlib,io,json,subprocess,sys,threading
from unittest.mock import patch
sys.path.insert(0,'android/test-watchdog')
import run_tests as m
cases=m.discover_cases()
assert len(cases)>=6
for count,expected in ((1,1),(2,2),(64,3)):
 with patch.object(m.os,'sched_getaffinity',create=True,return_value=set(range(count))):
  assert m.available_workers()==expected
def result(total=1,code=0,tail='OK'):
 return subprocess.CompletedProcess([],code,'',f'Ran {total} test'+('s' if total!=1 else '')+' in 0.1s\n\n'+tail+'\n')
assert m.complete(result(),1)
for bad in (result(code=1),result(total=0),result(tail='OK (skipped=1)'),subprocess.CompletedProcess([],0,'','OK\n')):
 assert not m.complete(bad,1)
lock=threading.Lock();ready=threading.Event();active=0;peak=0;calls=[]
def execute(argv,**kwargs):
 global active,peak
 with lock:
  active+=1;peak=max(peak,active);calls.append(argv)
  if active==3:ready.set()
 assert ready.wait(3),'parallel worker bound was not exercised'
 with lock:active-=1
 return result()
out=io.StringIO()
with patch.object(m,'available_workers',return_value=3),patch.object(m.subprocess,'run',side_effect=execute),contextlib.redirect_stdout(out),contextlib.redirect_stderr(io.StringIO()):
 assert m.run()==0
assert peak==3
assert sorted(argv[-2] for argv in calls)==sorted(cases)
summary=json.loads(next(l.split(' ',1)[1] for l in out.getvalue().splitlines() if l.startswith('WATCHDOG_RESULTS ')))
assert summary['cases']==cases and summary['passed']==summary['total']==len(cases)
class FutureCase(m.unittest.TestCase):
 def test_future(self):pass
FutureCase.__module__=m.test_watchdog.__name__
m.test_watchdog.FutureCase=FutureCase
assert 'FutureCase.test_future' in m.discover_cases()
`], {encoding: 'utf8'});
  assert.equal(proc.status, 0, proc.stderr);
});

test('one bad watchdog subprocess blocks the gate and low-core hosts retain the serial suite', () => {
  const proc = spawnSync('python3', ['-B', '-c', String.raw`
import contextlib,io,subprocess,sys
from unittest.mock import patch
sys.path.insert(0,'android/test-watchdog');import run_tests as m
cases=m.discover_cases()
good=subprocess.CompletedProcess([],0,'','Ran 1 test in 0.1s\n\nOK\n')
def execute(argv,**kwargs):
 return subprocess.CompletedProcess([],1,'','FAILED\n') if argv[-2].endswith(cases[0]) else good
with patch.object(m,'available_workers',return_value=3),patch.object(m.subprocess,'run',side_effect=execute) as run,contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
 assert m.run()==1 and run.call_count==len(cases)
serial=subprocess.CompletedProcess([],0,'',f'Ran {len(cases)} tests in 1.0s\n\nOK\n')
with patch.object(m,'available_workers',return_value=1),patch.object(m.subprocess,'run',return_value=serial) as run,contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
 assert m.run()==0 and run.call_count==1
 assert run.call_args.args[0][-1]=='-v' and len(run.call_args.args[0])==3
`], {encoding: 'utf8'});
  assert.equal(proc.status, 0, proc.stderr);
});
