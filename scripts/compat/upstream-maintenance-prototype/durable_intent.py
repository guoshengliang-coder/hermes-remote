"""Test-only persisted admission intent. No clear/finish or production authority API.

Per-domain pre-enrolled slots are never unlinked, replaced or reset here. All must
contain the same independently expected context before a recovery may run. Partial
publication blocks but is not automatically repaired. No mutation is authorized
until every slot and registry directory have been fsynced successfully.
"""
import hashlib
import importlib.util
import os
from pathlib import Path

spec = importlib.util.spec_from_file_location("intent_binding", Path(__file__).with_name("bound_handoff.py"))
binding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binding)

IDLE = b"HG199-IDLE-1\n"
LIMIT = 16_384


def record(context):
    value = {"schema": 1, "kind": "maintenance-intent", "context": context,
             "digest": hashlib.sha256(binding.encode(context)).hexdigest()}
    data = binding.encode(value) + b"\n"
    if not len(IDLE) < len(data) <= LIMIT:
        raise ValueError("intent context size")
    return data


def validate(held, context):
    if (not held.maintenance or not held.ready or len(held.domains) != 2
            or type(context) is not dict
            or any(type(context.get(key)) is not str for key in ("run", "source", "home", "entry"))):
        raise ValueError("exclusive maintenance required")
    # Revalidate current controls and actual exclusive descriptions; closing
    # duplicates never unlocks the caller's custody.
    duplicate = held.__class__.inherit_maintenance(held.domains, held.pass_fds)
    duplicate.close()
    records = [[str(d.registry), d.binding["kind"], str(d.directory)] for d in held.domains]
    checkout = next(d.directory for d in held.domains if d.binding["kind"] == "checkout")
    binding.validate(context, context["run"], context["source"], checkout, Path(context["home"]),
                     context["entry"], records)


def sync_registry(held):
    for registry in sorted({d.registry for d in held.domains}):
        fd = os.open(registry, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    for domain in held.domains:
        domain._check_directories()


def apply(held, context, recovering=False, checkpoint=None):
    validate(held, context)
    expected = record(context)
    fds = []
    try:
        for domain in held.domains:
            fds.append(domain.intent())
        previous = [os.pread(fd, LIMIT + 1, 0) for fd in fds]
        if previous != [expected if recovering else IDLE] * len(fds):
            raise ValueError("intent does not match requested operation")
        if not recovering:
            for index, fd in enumerate(fds):
                offset = 0
                while offset < len(expected):
                    count = os.pwrite(fd, expected[offset:], offset)
                    if count <= 0:
                        raise OSError("intent write failed")
                    offset += count
                os.fsync(fd)
                if checkpoint:
                    checkpoint("slot-synced", index)
        else:
            # A new process establishes synchronization itself; saved JSON/FD
            # integers or an empty kernel lock do not establish this boundary.
            for fd in fds:
                os.fsync(fd)
        sync_registry(held)
        validate(held, context)
        if [os.pread(fd, LIMIT + 1, 0) for fd in fds] != [expected] * len(fds):
            raise ValueError("intent changed during synchronization")
        if checkpoint:
            checkpoint("all-synced", len(fds))
        return hashlib.sha256(expected).hexdigest()
    finally:
        for fd in fds:
            os.close(fd)


def begin(held, context, checkpoint=None):
    return apply(held, context, checkpoint=checkpoint)


def resume(held, expected_context):
    return apply(held, expected_context, recovering=True)
