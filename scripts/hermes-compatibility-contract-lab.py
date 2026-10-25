#!/usr/bin/env python3
"""Probe existing disposable old/new Hermes runtimes at their original absolute path.

No installation, user data, external service or production account is involved.
--prompt uses a fenced loopback model fixture and restores the synthetic configuration.
The candidate is temporarily reactivated only inside a successful synthetic installation lab.
"""
import argparse
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import stat
import subprocess
import tempfile
import time


def load_lab():
    spec = importlib.util.spec_from_file_location('install_lab', Path(__file__).with_name('hermes-compatibility-lab.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def probe(root, lab, version, stage, node, prompt=False):
    if prompt:
        return prompt_probe(root, lab, version, stage, node)
    return runtime_probe(root, lab, version, stage, node)


def runtime_probe(root, lab, version, stage, node, prompt=False, sandbox=None):
    env = lab.isolated_environment(root)
    token = secrets.token_urlsafe(32)
    env.update(HERMES_DESKTOP='1', HERMES_DASHBOARD_SESSION_TOKEN=token)
    repo = root / 'user/.hermes/hermes-agent'
    launcher = repo / ('venv/bin/hermes' if version == '0.21.3' else '.hermes/bin/hermes')
    fd = os.open(str(root / 'tmp' / (stage + '.private.log')), os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'r+b', buffering=0) as output:
        child = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(sandbox or root / 'isolation.sb'), str(launcher),
                                  '--profile','default','serve','--host','127.0.0.1','--port','0'],
                                 cwd=repo, env=env, stdin=subprocess.DEVNULL, stdout=output, stderr=output, start_new_session=True)
        try:
            deadline = time.monotonic() + 180
            port = None
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise ValueError('startup_failed')
                text = os.pread(output.fileno(), 4 * 1024 * 1024, 0).decode(errors='replace')
                ready = re.search(r'(?m)^HERMES_BACKEND_READY port=([0-9]+)\s*$', text)
                if ready:
                    port = int(ready[1]); break
                time.sleep(0.25)
            if port is None:
                raise ValueError('startup_timeout')
            # Tokens travel through stdin, never command arguments, evidence or printed logs.
            runtime = subprocess.run([node, str(Path(__file__).with_name('hermes-compatibility-contract-probe.mjs'))],
                input=json.dumps({'port':port,'token':token,'version':version,'prompt':prompt}), text=True, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, timeout=100, cwd=Path(__file__).parent.parent,
                env={'PATH':'/usr/bin:/bin','HOME':str(root/'user'),'TMPDIR':str(root/'tmp')})
            if runtime.returncode:
                raise ValueError('probe_failed')
            return json.loads(runtime.stdout)
        finally:
            # Stop all processes belonging to this synthetic backend before swapping paths.
            try: os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            try: child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL); child.wait()
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass


def prompt_probe(root, lab, version, stage, node):
    spec=importlib.util.spec_from_file_location('model_fixture',Path(__file__).with_name('hermes-compatibility-model-fixture.py'))
    fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
    sandbox=root/'tmp'/(stage+'.sb')
    sandbox.write_text((root/'isolation.sb').read_text()+
        '(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n')
    sandbox.chmod(0o600)
    # Verify denial before allowing the test to perform any model-facing operation.
    fence=subprocess.run(['/usr/bin/sandbox-exec','-f',str(sandbox),'/usr/bin/python3','-c',
        'import socket; s=socket.socket(); s.settimeout(1);\ntry: s.connect(("192.0.2.1",9))\nexcept PermissionError: raise SystemExit(0)\nexcept OSError: raise SystemExit(2)\nraise SystemExit(3)'],
        env=lab.isolated_environment(root),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=5)
    if fence.returncode: raise ValueError('network_fence_failed')
    with fixture.model_fixture(root/'user/.hermes/config.yaml') as observation:
        result=runtime_probe(root,lab,version,stage,node,prompt=True,sandbox=sandbox)
    result['checks']['promptModelObserved']=observation['requests']>0
    result['checks']['promptConfigurationRestored']=observation['restored']
    result['checks']['promptNetworkFence']=True
    result['modelFixtureSha256']=lab.sha256(Path(__file__).with_name('hermes-compatibility-model-fixture.py').read_bytes())
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-root', type=Path, required=True)
    parser.add_argument('--prompt', action='store_true', help='Add a loopback model fixture and synthetic prompt test')
    args = parser.parse_args()
    root = args.lab_root.resolve()
    if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('hermes-compat-lab-'):
        raise ValueError('not_disposable_lab')
    info = root.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('unsafe_lab')
    old = root/'user'; candidate=root/'candidate-after-experiment'; parked=root/'contract-probe-old'
    for folder in (old,candidate):
        if not stat.S_ISDIR(folder.lstat().st_mode): raise ValueError('unsafe_runtime')
    if parked.exists(): raise ValueError('previous_probe_needs_recovery')
    lab=load_lab()
    report=json.loads((root/'report.json').read_text())
    if report.get('kind')!='runtime-rehearsal' or report.get('status')!='runtime_stage_passed': raise ValueError('runtime_required')
    for folder,commit in ((old,lab.RUNTIME_SOURCE),(candidate,lab.RUNTIME_TARGET)):
        if lab.git_read(folder/'.hermes/hermes-agent','rev-parse','HEAD').decode().strip()!=commit: raise ValueError('commit_mismatch')
    run_probe=(lambda *items: probe(*items,prompt=True)) if args.prompt else probe
    node=shutil.which('node')
    if not node: raise ValueError('node_missing')
    lock=os.open(str(root/'contract-probe.lock'),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    try:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        output={'schemaVersion':1,'kind':'live-runtime-contracts','sourceCommit':lab.RUNTIME_SOURCE,
                'targetCommit':lab.RUNTIME_TARGET,'compatibilityApproved':False,'stages':{}}
        stamp=str(time.time_ns())
        output['stages']['source']=run_probe(root,lab,'0.21.3','contract-source-'+stamp,node)
        old.rename(parked)
        try:
            candidate.rename(old)
            try: output['stages']['candidate']=run_probe(root,lab,'0.22.5','contract-candidate-'+stamp,node)
            finally: old.rename(candidate)
        finally: parked.rename(old)
        output['stages']['restored']=run_probe(root,lab,'0.21.3','contract-restored-'+stamp,node)
        output['suiteSha256']=lab.sha256(Path(__file__).read_bytes())
        output['probeSha256']=lab.sha256(Path(__file__).with_name('hermes-compatibility-contract-probe.mjs').read_bytes())
        output['status']='passed' if all(all(s['checks'].values()) for s in output['stages'].values()) else 'contract_gap'
        path=root/('contract-report-'+stamp+'.json')
        path.write_text(json.dumps(output,indent=2)+'\n');path.chmod(0o600)
        print(json.dumps({'report':str(path),'status':output['status']}))
    finally: os.close(lock)


if __name__=='__main__':
    try: main()
    except Exception:
        print(json.dumps({'code':'HR-HERMESCOMPAT-004','summaryZh':'隔离接口验证未完成，请查看测试记录。',
                          'summaryEn':'Isolated contract verification incomplete. Review the test record.'}))
        raise SystemExit(1)
