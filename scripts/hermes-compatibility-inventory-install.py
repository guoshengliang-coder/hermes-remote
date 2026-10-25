#!/usr/bin/env python3
"""Hold a kernel lock across the actual Node inventory writer; no signing or network access."""
import fcntl
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile


def main():
    if len(sys.argv)!=4 or sys.argv[1] not in ('initialize','update'):raise ValueError('arguments')
    config=Path(sys.argv[2])
    if not config.is_absolute() or config.resolve()!=config:raise ValueError('configuration')
    fd=os.open(config,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_nlink!=1 or info.st_mode&0o022 or info.st_size>512*1024:raise ValueError('configuration')
        data=os.read(fd,512*1024+1)
        if len(data)!=info.st_size:raise ValueError('configuration')
        target=Path(json.loads(data)['inventoryFile'])
    finally:os.close(fd)
    if not target.is_absolute() or target.parent.resolve()!=target.parent:raise ValueError('directory')
    parent=target.parent.stat()
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022:raise ValueError('directory')
    import re
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,100}',target.name):raise ValueError('filename')
    lock=os.open(target.parent/('.'+target.name+'.lock'),os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW,0o600)
    info=os.fstat(lock)
    if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_nlink!=1 or info.st_mode&0o022:raise ValueError('lock')
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    os.set_inheritable(lock,True)
    node=shutil.which('node')
    if not node:raise ValueError('runtime')
    node=str(Path(node).resolve())
    env={'PATH':str(Path(node).parent)+':/usr/bin:/bin','HOME':tempfile.gettempdir(),
         'HERMES_INVENTORY_LOCK_FD':str(lock)}
    script=Path(__file__).resolve().with_suffix('.mjs')
    # exec keeps the same process and lock descriptor; there is no parent-only lock owner.
    os.execve(node,[node,str(script),*sys.argv[1:]],env)

if __name__=='__main__':
    try:main()
    except Exception:
        print(json.dumps({'code':'HR-HERMESCOMPAT-006','summaryZh':'推荐清单尚未发布完成，请查看发布记录后重试。',
          'summaryEn':'The recommendation list has not finished publishing. Review the publication record and retry.',
          'retryable':True,'action':'review_publication'}),file=sys.stderr)
        raise SystemExit(1)
