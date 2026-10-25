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


def cleanup_marker(root):
    return root/'tmp/launchagent-cleanup-required.json'


def stop_agent(root, agent):
    try:agent.stop()
    except Exception:
        # Quarantine this synthetic installation instead of moving it under a possibly live writer.
        marker=cleanup_marker(root)
        fd=os.open(str(marker),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'w') as output:
            json.dump({'schemaVersion':1,'label':agent.label,'workspace':str(agent.workspace),'pid':agent.pid},output)
        raise


def probe(root, lab, version, stage, node, prompt=False, relay=False, restart=False, launchagent=None):
    if prompt:
        return prompt_probe(root, lab, version, stage, node, relay=relay, restart=restart, launchagent=launchagent)
    return runtime_probe(root, lab, version, stage, node)


def runtime_probe(root, lab, version, stage, node, prompt=False, sandbox=None, relay=False, model_observation=None,
                  capture_restart=False, restart_state=None, launchagent=None):
    env = lab.isolated_environment(root)
    token = secrets.token_urlsafe(32)
    env.update(HERMES_DESKTOP='1', HERMES_DASHBOARD_SESSION_TOKEN=token)
    repo = root / 'user/.hermes/hermes-agent'
    launcher = repo / ('venv/bin/hermes' if version == '0.21.3' else '.hermes/bin/hermes')
    fd = os.open(str(root / 'tmp' / (stage + '.private.log')), os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    result = None
    requested_port = restart_state['port'] if restart_state else 0
    with os.fdopen(fd, 'r+b', buffering=0) as output:
        agent = None
        if launchagent:
            spec=importlib.util.spec_from_file_location('launchagent_fixture',Path(__file__).with_name('hermes-compatibility-launchagent-fixture.py'))
            fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
            agent=fixture.prepare_agent(root,launchagent['helper'],version,sandbox,env,token,
                root/'tmp'/(stage+'.private.log'),requested_port)
            child=agent
        else:
            child = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(sandbox or root / 'isolation.sb'), str(launcher),
                                      '--profile','default','serve','--host','127.0.0.1','--port',str(requested_port)],
                                     cwd=repo, env=env, stdin=subprocess.DEVNULL, stdout=output, stderr=output, start_new_session=True)
        try:
            if agent:agent.start()
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
            def call_probe(active_port,active_token,transport='direct'):
                data={'port':active_port,'token':active_token,'version':version,'prompt':prompt,'transport':transport,
                      'captureRestart':capture_restart and transport=='direct'}
                if restart_state:
                    data={'port':active_port,'token':active_token,'version':version,'operation':'restart',
                          'previousToken':restart_state['token'],'checkpoint':restart_state['checkpoint']}
                runtime = subprocess.run([node, str(Path(__file__).with_name('hermes-compatibility-contract-probe.mjs'))],
                    input=json.dumps(data),
                    text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=100,cwd=Path(__file__).parent.parent,
                    env={'PATH':'/usr/bin:/bin','HOME':str(root/'user'),'TMPDIR':str(root/'tmp')})
                if runtime.returncode:raise ValueError('probe_failed')
                return json.loads(runtime.stdout)
            model_before=model_observation['requests'] if model_observation else 0
            result=call_probe(port,token)
            if agent:
                result['launchAgent']={'scope':'temporary-desktop-launchagent-v1',**launchagent['identity'],
                    'fixtureSha256':lab.sha256(Path(__file__).with_name('hermes-compatibility-launchagent-fixture.py').read_bytes()),
                    'checks':agent.checks}
            if restart_state:
                result['checks'].update(restartPreviousStopped=restart_state['stopped'],restartFreshProcess=child.pid!=restart_state['pid'],
                    restartSamePort=port==requested_port,restartNoModelRequest=model_observation['requests']==model_before)
            elif model_observation is not None:result['checks']['promptModelObserved']=model_observation['requests']>model_before
            if capture_restart:
                checkpoint=result.pop('restartCheckpoint')
                result['_restart']={'port':port,'pid':child.pid,'token':token,'checkpoint':checkpoint,'stopped':False}
            if relay:
                spec=importlib.util.spec_from_file_location('relay_fixture',Path(__file__).with_name('hermes-compatibility-relay-fixture.py'))
                fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
                with fixture.relay_fixture(root,node,sandbox,port,token,stage) as observation:
                    model_before=model_observation['requests']
                    relayed=call_probe(observation['port'],observation['token'],'legacy-relay')
                    relayed['checks']['promptModelObserved']=model_observation['requests']>model_before
                result['relay']={'probe':relayed,'roleSeparation':observation['roleSeparation'],'stopped':observation['stopped'],
                    'gatewayVersion':observation['gatewayVersion'],'connectorVersion':observation['connectorVersion'],'nodeSha256':observation['nodeSha256'],'buildSha256':observation['buildSha256'],
                    'fixtureSha256':lab.sha256(Path(__file__).with_name('hermes-compatibility-relay-fixture.py').read_bytes())}
            return result
        finally:
            # Stop all processes belonging to this synthetic backend before swapping paths.
            if agent:stop_agent(root,agent)
            else:
                try: os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError: pass
                try: child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL); child.wait()
                try: os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError: pass
            if result is not None:
                if capture_restart:result['_restart']['stopped']=child.poll() is not None
                if restart_state:result['checks']['restartStopped']=child.poll() is not None


def prompt_probe(root, lab, version, stage, node, relay=False, restart=False, launchagent=None):
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
        result=runtime_probe(root,lab,version,stage,node,prompt=True,sandbox=sandbox,relay=relay,model_observation=observation,capture_restart=restart,launchagent=launchagent)
        if restart:
            # Keep token, PID, port, stored ID and transcript in memory only; never serialize them to the report.
            previous=result.pop('_restart')
            if not previous['stopped']:raise ValueError('previous_backend_alive')
            if previous['checkpoint'] is not None:
                result['restart']=runtime_probe(root,lab,version,stage+'-restart',node,sandbox=sandbox,
                    model_observation=observation,restart_state=previous,launchagent=launchagent)
            else:
                # Preserve the actual prompt failure; missing prerequisite is not an infrastructure error
                # or a claim that a restart was attempted. The consumer rejects a false skip.
                result['restart']={'schemaVersion':1,'kind':'live-backend-restart-probe',
                    'scope':'completed-text-turn-backend-restart-v1','version':version,'attempted':False,
                    'checks':{},'compatibilityApproved':False}
    for item in [result]+([result['relay']['probe']] if relay else []):
        item['checks']['promptConfigurationRestored']=observation['restored']
        item['checks']['promptNetworkFence']=True
        item['modelFixtureSha256']=lab.sha256(Path(__file__).with_name('hermes-compatibility-model-fixture.py').read_bytes())
    if restart and result['restart']['attempted']:
        result['restart']['checks'].update(restartConfigurationRestored=observation['restored'],restartNetworkFence=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-root', type=Path, required=True)
    parser.add_argument('--prompt', action='store_true', help='Add a loopback model fixture and synthetic prompt test')
    parser.add_argument('--relay',action='store_true',help='Also exercise a disposable real legacy Gateway/Connector pair')
    parser.add_argument('--restart',action='store_true',help='Restart the owned synthetic backend and resume its completed turn')
    parser.add_argument('--launchagent',action='store_true',help='Use actual Desktop Core with isolated launchd labels and ports')
    args = parser.parse_args()
    if args.relay and not args.prompt:raise ValueError('relay_requires_fenced_prompt')
    if args.restart and not args.prompt:raise ValueError('restart_requires_fenced_prompt')
    if args.launchagent and not args.restart:raise ValueError('launchagent_requires_restart')
    root = args.lab_root.resolve()
    if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('hermes-compat-lab-'):
        raise ValueError('not_disposable_lab')
    info = root.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('unsafe_lab')
    if cleanup_marker(root).exists():raise ValueError('previous_launchagent_needs_cleanup')
    old = root/'user'; candidate=root/'candidate-after-experiment'; parked=root/'contract-probe-old'
    for folder in (old,candidate):
        if not stat.S_ISDIR(folder.lstat().st_mode): raise ValueError('unsafe_runtime')
    if parked.exists(): raise ValueError('previous_probe_needs_recovery')
    lab=load_lab()
    report=json.loads((root/'report.json').read_text())
    if report.get('kind')!='runtime-rehearsal' or report.get('status')!='runtime_stage_passed': raise ValueError('runtime_required')
    for folder,commit in ((old,lab.RUNTIME_SOURCE),(candidate,lab.RUNTIME_TARGET)):
        if lab.git_read(folder/'.hermes/hermes-agent','rev-parse','HEAD').decode().strip()!=commit: raise ValueError('commit_mismatch')
    node=shutil.which('node')
    if not node: raise ValueError('node_missing')
    lock=os.open(str(root/'contract-probe.lock'),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    native=None
    try:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if args.launchagent:
            spec=importlib.util.spec_from_file_location('launchagent_fixture',Path(__file__).with_name('hermes-compatibility-launchagent-fixture.py'))
            fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
            native={'helper':root/'tmp'/('native-helper-'+secrets.token_hex(16))}
            native['identity']=fixture.build_helper(native['helper'])
        run_probe=(lambda *items: probe(*items,prompt=True,relay=args.relay,restart=args.restart,launchagent=native)) if args.prompt else probe
        output={'schemaVersion':1,'kind':'live-runtime-contracts','sourceCommit':lab.RUNTIME_SOURCE,
                'targetCommit':lab.RUNTIME_TARGET,'compatibilityApproved':False,'stages':{}}
        stamp=str(time.time_ns())
        output['stages']['source']=run_probe(root,lab,'0.21.3','contract-source-'+stamp,node)
        old.rename(parked)
        try:
            candidate.rename(old)
            try: output['stages']['candidate']=run_probe(root,lab,'0.22.5','contract-candidate-'+stamp,node)
            finally:
                if not cleanup_marker(root).exists():old.rename(candidate)
        finally:
            if not cleanup_marker(root).exists():parked.rename(old)
        output['stages']['restored']=run_probe(root,lab,'0.21.3','contract-restored-'+stamp,node)
        output['suiteSha256']=lab.sha256(Path(__file__).read_bytes())
        output['probeSha256']=lab.sha256(Path(__file__).with_name('hermes-compatibility-contract-probe.mjs').read_bytes())
        output['status']='passed' if all(all(s['checks'].values()) and (not args.relay or
            (all(s['relay']['probe']['checks'].values()) and s['relay']['roleSeparation'] and s['relay']['stopped']))
            and (not args.restart or all(s['restart']['checks'].values())) for s in output['stages'].values()) else 'contract_gap'
        if args.relay:output['relayScope']='legacy-source-build-relay-v1'
        path=root/('contract-report-'+stamp+'.json')
        path.write_text(json.dumps(output,indent=2)+'\n');path.chmod(0o600)
        print(json.dumps({'report':str(path),'status':output['status']}))
    finally:
        if native:native['helper'].unlink(missing_ok=True)
        os.close(lock)


if __name__=='__main__':
    try: main()
    except Exception:
        print(json.dumps({'code':'HR-HERMESCOMPAT-004','summaryZh':'隔离接口验证未完成，请查看测试记录。',
                          'summaryEn':'Isolated contract verification incomplete. Review the test record.'}))
        raise SystemExit(1)
