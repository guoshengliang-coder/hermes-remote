"""Owned, password-protected PostgreSQL cluster for synthetic account acceptance only."""
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import subprocess
import time
import tempfile


@contextmanager
def postgres_fixture(workspace, binaries):
    workspace=Path(workspace).resolve();binaries=Path(binaries).resolve()
    root=workspace.parent.parent
    if (workspace.parent.name!='tmp' or root.parent!=Path(tempfile.gettempdir()).resolve() or not root.name.startswith('hermes-compat-lab-')
        or root.stat().st_uid!=os.getuid() or root.stat().st_mode&0o077
        or workspace.stat().st_uid!=os.getuid() or workspace.stat().st_mode&0o077):
        raise ValueError('unsafe_account_database_workspace')
    commands={name:binaries/name for name in ['initdb','postgres','pg_isready']}
    for path in commands.values():
        if not path.is_file() or not os.access(path,os.X_OK) or path.stat().st_mode&0o022:
            raise ValueError('unsafe_postgresql_binary')
    env={'PATH':'/usr/bin:/bin','HOME':str(workspace),'TMPDIR':str(workspace),'LC_ALL':'C'}
    version=subprocess.run([str(commands['postgres']),'--version'],env=env,text=True,stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,timeout=10,check=True).stdout.strip()
    version_match=re.fullmatch(r'postgres \(PostgreSQL\) (18\.[0-9]+)(?: \(Homebrew\))?',version)
    if not version_match:raise ValueError('unsupported_postgresql')
    password=secrets.token_urlsafe(32)
    password_file=workspace/'postgres-password'
    fd=os.open(str(password_file),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w') as output:output.write(password+'\n')
    data=workspace/'postgres-data'
    subprocess.run([str(commands['initdb']),'-D',str(data),'-U','hermes_compat','--auth=scram-sha-256',
        '--encoding=UTF8','--no-locale','--pwfile='+str(password_file)],env=env,
        stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=60,check=True)
    password_file.unlink()
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    fd=os.open(str(workspace/'postgres.private.log'),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    child=None;observation=None
    with os.fdopen(fd,'wb') as log:
        try:
            child=subprocess.Popen([str(commands['postgres']),'-D',str(data),'-h','127.0.0.1','-p',str(port),
                '-c','unix_socket_directories=','-c','max_connections=30'],env=env,stdin=subprocess.DEVNULL,
                stdout=log,stderr=log,start_new_session=True)
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                if child.poll() is not None:raise ValueError('account_database_exited')
                ready=subprocess.run([str(commands['pg_isready']),'-h','127.0.0.1','-p',str(port),'-t','1'],
                    env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=3)
                if ready.returncode==0:
                    if (data/'postmaster.pid').read_text().splitlines()[0]!=str(child.pid):raise ValueError('database_owner_mismatch')
                    break
                time.sleep(0.1)
            else:raise ValueError('account_database_timeout')
            observation={'url':f'postgresql://hermes_compat:{password}@127.0.0.1:{port}/postgres',
                'dataDirectory':str(data),'postgresVersion':version_match.group(1),
                'postgresBinarySha256':hashlib.sha256(commands['postgres'].read_bytes()).hexdigest(),'stopped':False}
            yield observation
        finally:
            if child and child.poll() is None:
                # PostgreSQL fast shutdown terminates only this cluster's own sessions/children.
                os.killpg(child.pid,signal.SIGINT)
                try:child.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=5)
            if observation is not None:observation['stopped']=child.poll() is not None
