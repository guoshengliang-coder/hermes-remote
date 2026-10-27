"""Disposable real Gateway/Connector pair. Only the caller's sandboxed lab is writable."""
from contextlib import contextmanager, ExitStack
import json
import importlib.util
import hashlib
import shutil
import os
import re
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


def get_json(url, token=None, account=False):
    headers=({'Authorization':'Bearer '+token} if account else {'X-Hermes-Session-Token':token}) if token else {}
    request=urllib.request.Request(url,headers=headers)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request,timeout=1) as response:
            body=response.read(64*1024+1)
            if len(body)>64*1024:raise ValueError('relay_response_limit')
            return response.status,json.loads(body)
    except urllib.error.HTTPError as error:
        status=error.code
        try:
            data=error.read(64*1024+1)
            body=json.loads(data) if len(data)<=64*1024 else None
        except (ValueError,OSError):body=None
        finally:error.close()
        return status,body


def wait_ready(children, origin, device=None, online=True, account_token=None):
    deadline=time.monotonic()+(45 if account_token and online else 20)
    while time.monotonic()<deadline:
        if any(child.poll() is not None for child in children):raise ValueError('relay_process_exited')
        try:
            if account_token:
                if device is None:
                    status,body=get_json(origin+'/readyz')
                    if status==200 and body.get('status')=='ready':return
                else:
                    status,body=get_json(origin+'/v2/connector-binding',account_token,account=True)
                    if (status==200 and body.get('state')=='bound' and body.get('binding',{}).get('connector',{}).get('online') is online
                        and (not online or body.get('binding',{}).get('hermes',{}).get('reachable') is True)):
                        if not online:return
                        # Key proof marks the binding online before the new peer is registered;
                        # its persisted health can still belong to the previous connection.
                        # Read through the actual account route before beginning acceptance.
                        routed_status,_=get_json(origin+'/api/status',account_token,account=True)
                        if routed_status==200:return
                time.sleep(0.1);continue
            status,body=get_json(origin+'/health')
            if status==200 and body.get('ok') is True and (device is None or
                any(row.get('deviceId')==device and row.get('online') is True for row in body.get('devices',[])) is online):return
        except (OSError,ValueError):pass
        time.sleep(0.1)
    raise ValueError('relay_start_timeout')


@contextmanager
def relay_fixture(root,node,sandbox,backend_port,backend_token,stage,postgres_bin=None,web=None):
    root=Path(root).resolve();sandbox=Path(sandbox)
    if not root.name.startswith('hermes-compat-lab-') or root.stat().st_uid!=os.getuid() or root.stat().st_mode&0o077:
        raise ValueError('unsafe_relay_lab')
    if not sandbox.is_file() or not str(sandbox.resolve()).startswith(str(root)+'/'):
        raise ValueError('unsafe_relay_sandbox')
    if not isinstance(backend_port,int) or not 0<backend_port<65536:raise ValueError('invalid_backend')
    if web and not postgres_bin:raise ValueError('web_requires_account')
    workspace=root/'tmp'/('relay-'+stage);workspace.mkdir(mode=0o700)
    web_fixture=None;web_options=None
    if web:
        path=Path(__file__).with_name('hermes-compatibility-web-fixture.py')
        spec=importlib.util.spec_from_file_location('web_fixture',path)
        web_fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(web_fixture)
        web_options=web_fixture.prepare(workspace,sandbox,web)
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
    children=[];logs=[];active=[];database_context=ExitStack();account_token=None;account_evidence=None
    def start(component, env, suffix=''):
        fd=os.open(str(workspace/(component+suffix+'.private.log')),os.O_CREAT|os.O_EXCL|os.O_WRONLY|os.O_NOFOLLOW,0o600)
        log=os.fdopen(fd,'wb');logs.append(log)
        child=subprocess.Popen(['/usr/bin/sandbox-exec','-f',str(sandbox),node,str(source/component/'dist/index.js')],
            cwd=source,env=env,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
        children.append(child);active.append(child)
        wait_ready(active,origin,device if component=='connector' else None,account_token=account_token)
        return child
    try:
        if postgres_bin:
            postgres_path=Path(__file__).with_name('hermes-compatibility-postgres-fixture.py')
            spec=importlib.util.spec_from_file_location('postgres_fixture',postgres_path)
            postgres=importlib.util.module_from_spec(spec);spec.loader.exec_module(postgres)
            database=database_context.enter_context(postgres.postgres_fixture(workspace,postgres_bin))
            account_path=Path(__file__).with_name('hermes-compatibility-account-fixture.mjs')
            seeded=subprocess.run(['/usr/bin/sandbox-exec','-f',str(sandbox),node,str(account_path)],
                input=json.dumps({'url':database['url'],'dataDirectory':database['dataDirectory'],'workspace':str(workspace),'web':bool(web)}),
                text=True,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=60,cwd=source,env={**common,'TMPDIR':str(root.parent)})
            if seeded.returncode:raise ValueError('account_fixture_failed')
            account=json.loads(seeded.stdout);app_token=account['token'];account_token=app_token;device=account['device']
            gateway.update(ACCOUNT_AUTH_ENABLED='1',ACCOUNT_BINDING_ENABLED='1',ACCOUNT_DATABASE_URL=database['url'],
                ACCOUNT_TOKEN_HASH_KEY=account['tokenKey'],ACCOUNT_GATEWAY_ORIGIN=origin)
            if web_options:gateway.update(web_fixture.gateway_environment(web_options))
            connector.update(CONNECTOR_MODE='account',GATEWAY_URL=origin.replace('http:','ws:')+'/v2/connect',
                ACCOUNT_CONNECTOR_CREDENTIAL_FILE=account['credentialPath'])
            connector.pop('CONNECTOR_TOKEN');connector.pop('DEVICE_ID')
            account_evidence={'scope':'synthetic-account-routing-v1','setupSha256':digest(account_path),
                'postgresFixtureSha256':digest(postgres_path),'postgresBinarySha256':database['postgresBinarySha256'],
                'postgresVersion':database['postgresVersion'],'migrationsSha256':account['migrationsSha256'],
                'checks':{'databaseIdentityVerified':account['databaseIdentityVerified'],
                    'emptyDatabaseVerified':account['emptyDatabaseVerified'],'otherAccountDenied':False,'databaseStopped':False}}
        gateway_child=start('gateway',gateway)
        connector_child=start('connector',connector)
        recovered=False
        def reconnect(new_backend_token):
            nonlocal connector_child,recovered
            if recovered or new_backend_token==backend_token or not isinstance(new_backend_token,str) or len(new_backend_token)!=43:
                raise ValueError('invalid_relay_recovery')
            recovered=True
            previous=connector_child
            stop_child(previous);active.remove(previous)
            wait_ready(active,origin,device,online=False,account_token=account_token)
            connector_child=start('connector',{**connector,'HERMES_SESSION_TOKEN':new_backend_token},'-restarted')
            return {'gatewaySameProcess':gateway_child.poll() is None,
                'connectorPreviousStopped':previous.poll() is not None,'connectorFreshProcess':connector_child.pid!=previous.pid,
                'connectorOfflineObserved':True,'connectorOnline':True,
                'clientCredentialPreserved':observation['token']==app_token and observation['port']==port}
        def diagnose(new_backend_token):
            # Fixed read-only routes; persist only status/registered codes/booleans, never response bodies.
            status,body=get_json(origin+'/api/status',app_token,account=bool(account_token))
            code=body.get('error',{}).get('code') if isinstance(body,dict) and isinstance(body.get('error'),dict) else None
            result={'relayStatus':status,'registeredErrorCode':code if isinstance(code,str) and re.fullmatch(r'HR-[A-Z]+-[0-9]{3}',code) else None}
            result['localStatus']=get_json(f'http://127.0.0.1:{backend_port}/api/status',new_backend_token)[0]
            if account_token:
                status,body=get_json(origin+'/v2/connector-binding',app_token,account=True)
                result.update(accountStatus=status,connectorOnline=body.get('binding',{}).get('connector',{}).get('online') is True,
                    hermesReachable=body.get('binding',{}).get('hermes',{}).get('reachable') is True)
            return result
        local_status,_=get_json(f'http://127.0.0.1:{backend_port}/api/profiles/active',app_token)
        relay_status,_=get_json(origin+'/api/profiles/active',backend_token)
        observation={'port':port,'token':app_token,'roleSeparation':local_status in (401,403) and relay_status in (401,403),
            'reconnect':reconnect,'diagnose':diagnose,'stopped':False,'buildSha256':build_sha,'nodeSha256':node_sha,'gatewayVersion':json.loads((source/'gateway/package.json').read_text())['version'],
            'connectorVersion':json.loads((source/'connector/package.json').read_text())['version']}
        if web_options:observation['probeWeb']=lambda:web_fixture.probe(shutil.which('node'),web_options,port,account['browserSession'],root)
        if account_evidence is not None:
            status,body=get_json(origin+'/api/status',account['otherToken'],account=True)
            account_evidence['checks']['otherAccountDenied']=status==409 and body.get('error',{}).get('code')=='HR-BIND-001'
            observation['account']=account_evidence
        yield observation
        if any(child.poll() is not None for child in active):raise ValueError('relay_process_exited')
        if build_digest(source)!=build_sha or digest(local_node)!=node_sha:raise ValueError('relay_build_changed')
    finally:
        try:
            for child in reversed(active):stop_child(child)
        finally:
            for log in logs:log.close()
            local_node.unlink(missing_ok=True)
            database_context.close()
            if account_evidence is not None:account_evidence['checks']['databaseStopped']=database['stopped']
        if 'observation' in locals():observation['stopped']=all(child.poll() is not None for child in children)
