"""Gate a captured real PM spawn BEFORE worker imports; never runs its requested operation."""
import json
import os
from pathlib import Path
import select
import sys


def run(root, events, gate, identities):
    # This helper runs in the actual selected PM interpreter. Context numbers are diagnostic
    # expectations only; they confer no ability to finish or resume any transaction.
    marker = root / ".hg199-isolated-source-pm"
    if (not root.name.startswith("hg199-source-transition-") or root.stat().st_mode & 0o077
            or marker.is_symlink() or marker.stat().st_mode & 0o077
            or marker.read_bytes() != b"HG199 isolated joint source and PM preparation v1\n"):
        raise ValueError("marked worker gate required")
    matches = True
    for fd, identity in identities:
        try:
            actual = os.fstat(fd)
            matches = matches and [actual.st_dev, actual.st_ino] == identity
        except OSError:
            matches = False
    value = {"realWorkerSpawnGated": True, "descriptorsMatch": matches,
             "runtimeVersion": list(sys.version_info[:3]), "pid": os.getpid()}
    os.write(events, json.dumps(value).encode() + b"\n")
    os.close(events)
    if not select.select([gate], [], [], 30)[0] or os.read(gate, 64) != b"exit-without-worker\n":
        raise ValueError("worker gate release not observed")
    os.close(gate)


if __name__ == "__main__":
    root, events, gate, identities = sys.argv[1:]
    run(Path(root), int(events), int(gate), json.loads(identities))
