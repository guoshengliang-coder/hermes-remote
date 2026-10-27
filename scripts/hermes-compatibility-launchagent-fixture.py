"""Disposable launchd acceptance using Desktop's actual plist/launcher and controller.

The adapter changes only the label, loopback port, sandbox and isolated environment.
It never addresses a production service label. Raw launchctl output stays private.
"""
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import socket
import stat
import subprocess
import tempfile
import time
import uuid

PRODUCTION_LABEL = 'com.hermesgo.hermes-server'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build_helper(output):
    source = Path(__file__).resolve().parent.parent
    # Build the real Core, not a copied controller. No app is installed or launched.
    subprocess.run(['swift','build','--package-path',str(source/'desktop'),'--target','HermesGoDesktopCore'],
        check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=300)
    built = subprocess.run(['swift','build','--package-path',str(source/'desktop'),'--show-bin-path'],
        check=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=30)
    binary = Path(built.stdout.strip())
    objects = sorted((binary/'HermesGoDesktopCore.build').glob('*.o'))
    if not objects: raise ValueError('core_build_missing')
    helper_source = source/'scripts/hermes-compatibility-launchagent-helper.swift'
    subprocess.run(['swiftc','-parse-as-library','-I',str(binary/'Modules'),str(helper_source),
        *map(str,objects),'-o',str(output)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)
    records = [[str(path.relative_to(source)),digest(path)]
        for path in sorted((source/'desktop/Sources/HermesGoDesktopCore').glob('*.swift'))]
    return {'helperSourceSha256':digest(helper_source),'helperBinarySha256':digest(output),
        'coreSourceSha256':hashlib.sha256(json.dumps(records,separators=(',',':')).encode()).hexdigest()}


def invoke(helper, action, workspace, version):
    result = subprocess.run([str(helper),action,str(workspace),version],stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,text=True,timeout=30,
        env={'PATH':'/usr/bin:/bin','TMPDIR':tempfile.gettempdir(),'HOME':str(workspace)})
    if result.returncode: raise ValueError('launchagent_operation_failed')
    return result.stdout.strip()


def select_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0))
        return probe.getsockname()[1]


class LaunchAgent:
    def __init__(self, helper, workspace, version, port):
        self.helper,self.workspace,self.version,self.port=helper,workspace,version,port
        self.label='com.hermesgo.compat-lab.'+workspace.name.removeprefix('launchd-').lower()
        self.target='gui/'+str(os.getuid())+'/'+self.label
        self.pid=None;self.started=False;self.stopped=False
        self.checks={'loaded':False,'runningOwned':False,'stopped':False,'portReleased':False}

    def inspect(self):
        result=subprocess.run(['/bin/launchctl','print',self.target],stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,timeout=5)
        if result.returncode==113:return None
        if result.returncode or len(result.stdout)>128*1024:raise ValueError('launchagent_inspection_failed')
        # Do not print or persist the environment-bearing output.
        text=result.stdout.decode(errors='strict')
        match=re.search(r'(?m)^\s*pid = ([0-9]+)\s*$',text)
        return int(match[1]) if match else 0

    def start(self):
        if self.inspect() is not None:raise ValueError('launchagent_collision')
        self.started=True  # Intent precedes bootstrap: uncertain success still needs cleanup.
        invoke(self.helper,'start',self.workspace,self.version)
        self.checks['loaded']=True
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            pid=self.inspect()
            if pid:
                self.pid=pid;self.checks['runningOwned']=True;return self
            time.sleep(0.1)
        raise ValueError('launchagent_start_timeout')

    def poll(self):
        return None if self.pid and self.inspect()==self.pid else 1

    def stop(self):
        if not self.started:return
        if self.inspect() is not None:invoke(self.helper,'stop',self.workspace,self.version)
        self.stopped=self.inspect() is None
        deadline=time.monotonic()+20
        while self.pid and time.monotonic()<deadline:
            try:os.kill(self.pid,0)
            except ProcessLookupError:break
            time.sleep(0.1)
        else:
            if self.pid:raise ValueError('launchagent_process_not_stopped')
        self.checks['stopped']=self.stopped
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            try:probe.bind(('127.0.0.1',self.port));self.checks['portReleased']=True
            except OSError:pass
        if not all(self.checks[k] for k in ['stopped','portReleased']):raise ValueError('launchagent_cleanup_failed')


def prepare_agent(root, helper, version, sandbox, environment, token, log_path, port=0):
    root=Path(root).resolve();sandbox=Path(sandbox);log_path=Path(log_path)
    if root.parent!=Path(tempfile.gettempdir()).resolve() or not root.name.startswith('hermes-compat-lab-'):
        raise ValueError('not_disposable_lab')
    info=root.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode&0o077:raise ValueError('unsafe_lab')
    for path in (sandbox,log_path):
        if path.resolve()!=path or not str(path).startswith(str(root/'tmp')+'/'):raise ValueError('unsafe_agent_path')
    if not re.fullmatch(r'[A-Za-z0-9_-]{43}',token):raise ValueError('synthetic_token_invalid')
    workspace=root/'tmp'/('launchd-'+str(uuid.uuid4()));workspace.mkdir(mode=0o700)
    invoke(helper,'prepare',workspace,version)
    plist=workspace/(PRODUCTION_LABEL+'.plist');config=plistlib.loads(plist.read_bytes())
    launcher=workspace/'hermes-local-serve';script=launcher.read_text()
    if config.get('Label')!=PRODUCTION_LABEL or script.count("= '9119'")!=1:
        raise ValueError('desktop_contract_changed')
    arguments=config['ProgramArguments']
    if arguments[-5:]!=['serve','--host','127.0.0.1','--port','9119'] or Path(arguments[0]).resolve()!=launcher:
        raise ValueError('desktop_arguments_changed')
    selected=port or select_port()
    if not isinstance(selected,int) or not 1024<selected<65536 or selected==9119:raise ValueError('unsafe_test_port')
    script=script.replace("= '9119'","= '"+str(selected)+"'")
    launcher.write_text(script);launcher.chmod(0o700)
    token_path=workspace/'session.token';token_path.write_text(token+'\n');token_path.chmod(0o600)
    arguments[-1]=str(selected)
    isolated={**config['EnvironmentVariables'],**environment,'HERMES_SESSION_TOKEN_FILE':str(token_path),'HERMES_DESKTOP':'1'}
    isolated.pop('HERMES_DASHBOARD_SESSION_TOKEN',None)
    for key in list(isolated):
        if 'proxy' in key.lower():isolated.pop(key)
    # Clear launchd's inherited environment before the sandbox and real token-file launcher.
    config['ProgramArguments']=['/usr/bin/env','-i',*[key+'='+value for key,value in sorted(isolated.items())],
        '/usr/bin/sandbox-exec','-f',str(sandbox),*arguments]
    config.pop('EnvironmentVariables')
    agent=LaunchAgent(helper,workspace,version,selected)
    config['Label']=agent.label;config['StandardOutPath']=str(log_path);config['StandardErrorPath']=str(log_path)
    plist.write_bytes(plistlib.dumps(config));plist.chmod(0o600)
    return agent
