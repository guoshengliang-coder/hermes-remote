"""Disposable real Gateway/Connector pair. Only the caller's sandboxed lab is writable."""
from contextlib import contextmanager
import json
import hashlib
import shutil
import os
from pathlib import Path
import secrets
import signal
import socket
import stat
import subprocess
import time
import urllib.request
import urllib.error


def build_digest(source):
    records=[]
    for directory in ['gateway/dist','connector/dist','protocol/dist']:
        folder=source/directory
        if not folder.is_dir() or folder.is_symlink():raise ValueError('missing_relay_build')
        for path in sorted(folder.rglob('*')):
            if path.is_symlink():raise ValueError('unsafe_relay_build')
            if path.is_file():records.append([str(path.relative_to(source)),hashlib.sha256(path.read_bytes()).hexdigest()])
    records.append(['package-lock.json',hashlib.sha256((source/'package-lock.json').read_bytes()).hexdigest()])
    return hashlib.sha256(json.dumps(records,separators=(',',':')).encode()).hexdigest()


def stop_child(child):
    if child.poll() is None:
        try: os.killpg(child.pid,signal.SIGTERM)
        except ProcessLookupError: pass
        try: child.wait(timeout=4)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=4)
    # All descendants were started in this disposable process group.
    try: os.killpg(child.pid,signal.SIGKILL)
    except ProcessLookupError: pass


def get_json(url, token=None):
    headers={'X-Hermes-Session-Token':token} if token else {}
    request=urllib.request.Request(url,headers=headers)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request,timeout=1) as response:
            body=response.read(64*1024+1)
            if len(body)>64*1024:raise ValueError('relay_response_limit')
            return response.status,json.loads(body)
    except urllib.error.HTTPError as error:
        status=error.code;error.close();return status,None


def wait_ready(children, origin, device=None):
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        if any(child.poll() is not None for child in children):raise ValueError('relay_process_exited')
        try:
            status,body=get_json(origin+'/health')
            if status==200 and body.get('ok') is True and (device is None or
                any(row.get('deviceId')==device and row.get('online') is True for row in body.get('devices',[]))):return
        except (OSError,ValueError):pass
        time.sleep(0.1)
    raise ValueError('relay_start_timeout')


@contextmanager
def relay_fixture(root,node,sandbox,backend_port,backend_token,stage):
    root=Path(root).resolve();sandbox=Path(sandbox)
    if not root.name.startswith('hermes-compat-lab-') or root.stat().st_uid!=os.getuid() or root.stat().st_mode&0o077:
        raise ValueError('unsafe_relay_lab')
    if not sandbox.is_file() or not str(sandbox.resolve()).startswith(str(root)+'/'):
        raise ValueError('unsafe_relay_sandbox')
    if not isinstance(backend_port,int) or not 0<backend_port<65536:raise ValueError('invalid_backend')
    workspace=root/'tmp'/('relay-'+stage);workspace.mkdir(mode=0o700)
    source=Path(__file__).resolve().parent.parent
    build_sha=build_digest(source)
    # The host toolchain may live under the owner's HOME, which the lab cannot read.
    # Copy only its selected executable, verify identical bytes, and keep the fence intact.
    source_node=Path(node).resolve()
    def digest(path):
        with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest() if hasattr(hashlib,'file_digest') else hashlib.sha256(stream.read()).hexdigest()
    node_sha=digest(source_node)
    local_node=workspace/'node';shutil.copyfile(source_node,local_node);local_node.chmod(0o500)
    if digest(local_node)!=node_sha or digest(source_node)!=node_sha:raise ValueError('node_copy_changed')
    node=str(local_node)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    app_token=secrets.token_urlsafe(32);connector_token=secrets.token_urlsafe(32)
    device='compat-relay-'+secrets.token_hex(8)
    origin=f'http://127.0.0.1:{port}'
    common={'PATH':'/usr/bin:/bin','HOME':str(workspace),'TMPDIR':str(workspace),
            'NODE_ENV':'test','NO_PROXY':'*'}
    gateway={**common,'HOST':'127.0.0.1','PORT':str(port),'APP_TOKEN':app_token,
        'CONNECTOR_TOKEN':connector_token,'DEFAULT_DEVICE_ID':device,'GATEWAY_LOG_LEVEL':'off',
        'LIFECYCLE_EVENT_STORE_FILE':str(workspace/'events.json')}
    connector={**common,'CONNECTOR_MODE':'legacy','HERMES_MODE':'live','SESSION_OBSERVER_ENABLED':'1',
        'GATEWAY_URL':origin.replace('http:','ws:')+'/v1/connect','CONNECTOR_TOKEN':connector_token,
        'DEVICE_ID':device,'HERMES_BASE_URL':f'http://127.0.0.1:{backend_port}',
        'HERMES_SESSION_TOKEN':backend_token,'CONNECTOR_LOG_LEVEL':'off','FILES_ROOT':str(workspace),
        'OBSERVER_STATE_FILE':str(workspace/'observer.json')}
    children=[];logs=[]
    try:
        for component,env in [('gateway',gateway),('connector',connector)]:
            fd=os.open(str(workspace/(component+'.private.log')),os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
            log=os.fdopen(fd,'wb');logs.append(log)
            child=subprocess.Popen(['/usr/bin/sandbox-exec','-f',str(sandbox),node,str(source/component/'dist/index.js')],
                cwd=source,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
            children.append(child)
            wait_ready(children,origin,device if component=='connector' else None)
        local_status,_=get_json(f'http://127.0.0.1:{backend_port}/api/profiles/active',app_token)
        relay_status,_=get_json(origin+'/api/profiles/active',backend_token)
        observation={'port':port,'token':app_token,'roleSeparation':local_status in (401,403) and relay_status in (401,403),
            'stopped':False,'buildSha256':build_sha,'nodeSha256':node_sha,'gatewayVersion':json.loads((source/'gateway/package.json').read_text())['version'],
            'connectorVersion':json.loads((source/'connector/package.json').read_text())['version']}
        yield observation
        if any(child.poll() is not None for child in children):raise ValueError('relay_process_exited')
        if build_digest(source)!=build_sha or digest(local_node)!=node_sha:raise ValueError('relay_build_changed')
    finally:
        for child in reversed(children):stop_child(child)
        for log in logs:log.close()
        local_node.unlink(missing_ok=True)
        if 'observation' in locals():observation['stopped']=all(child.poll() is not None for child in children)
