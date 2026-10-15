"""Live private-channel binding experiment. No durable/production authority is implied.

The trusted launcher provisions an anonymous socketpair, read-only private context
FD and actual lock descriptions. Descriptor numbers/JSON alone grant no authority.
Protocol nonces never leave this channel or appear in diagnostics.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import stat
import time
import uuid

LIMIT = 16_384
ROLE = "maintenance-recovery"


class Rejected(ValueError):
    pass


def reject():
    raise Rejected("live handoff rejected")


def encode(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def decode(data):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                reject()
            result[key] = value
        return result
    try:
        value = json.loads(data, object_pairs_hook=pairs, parse_constant=lambda _: reject())
        if encode(value) != data:
            reject()
        return value
    except (ValueError, TypeError, UnicodeError, RecursionError):
        reject()


def digest(value):
    return hashlib.sha256(encode(value)).hexdigest()


def directory(path):
    value = Path(path)
    if not value.is_absolute() or value.resolve(strict=True) != value:
        reject()
    info = value.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
        reject()
    return [info.st_dev, info.st_ino]


def snapshot(checkout, names):
    root = Path(checkout)
    directory(root)
    if not isinstance(names, (list, dict)) or not 1 <= len(names) <= 64:
        reject()
    result, total = {}, 0
    if any(type(name) is not str for name in names):
        reject()
    for name in sorted(names):
        if (not isinstance(name, str) or len(name) > 512 or not name or name.startswith("/")
                or any(part in ("", ".", "..") for part in name.split("/"))):
            reject()
        path = root / name
        if path.resolve(strict=True) != path:
            reject()
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            before = os.fstat(fd)
            if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_nlink != 1
                    or before.st_mode & 0o022 or not 0 <= before.st_size <= 2 * 1024 * 1024):
                reject()
            total += before.st_size
            if total > 16 * 1024 * 1024:
                reject()
            hasher, count = hashlib.sha256(), 0
            while chunk := os.read(fd, 65_536):
                count += len(chunk)
                if count > before.st_size:
                    reject()
                hasher.update(chunk)
            def stamp(info):
                return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_mode)
            if (count != before.st_size or stamp(os.fstat(fd)) != stamp(before)
                    or stamp(path.lstat()) != stamp(before)):
                reject()
            result[name] = hasher.hexdigest()
        finally:
            os.close(fd)
    return result


def context(run_id, source_id, checkout, home, entry, records, names):
    return {"schema": 1, "role": ROLE, "run": run_id, "source": source_id,
            "checkout": str(checkout), "checkoutIdentity": directory(checkout),
            "home": str(home), "homeIdentity": directory(home), "entry": entry,
            "domains": [{"record": record, "root": directory(record[2]), "registry": directory(record[0])}
                        for record in records], "files": snapshot(checkout, names)}


def private_context_fd(control, value):
    data = encode(value)
    if len(data) > LIMIT:
        reject()
    path = Path(control) / (".live-context-" + str(uuid.uuid4()))
    writer = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(writer, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        reader = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            path.unlink()  # No discoverable receipt path; the parent has closed its writer.
        except BaseException:
            os.close(reader)
            raise
        return reader
    finally:
        if path.exists():
            path.unlink()


def read_context(fd):
    info = os.fstat(fd)
    if (fd < 3 or fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE != os.O_RDONLY
            or not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 0
            or stat.S_IMODE(info.st_mode) != 0o600 or not 1 <= info.st_size <= LIMIT):
        reject()
    data = os.pread(fd, LIMIT + 1, 0)
    if len(data) != info.st_size:
        reject()
    return decode(data)


def validate(value, run_id, source_id, checkout, home, entry, records):
    keys = {"schema", "role", "run", "source", "checkout", "checkoutIdentity", "home", "homeIdentity",
            "entry", "domains", "files"}
    if (type(value) is not dict or set(value) != keys or type(value["schema"]) is not int or value["schema"] != 1
            or value["role"] != ROLE or value["run"] != run_id or value["source"] != source_id
            or type(run_id) is not str or str(uuid.UUID(run_id)) != run_id
            or type(source_id) is not str or re.fullmatch("[0-9a-f]{40}", source_id) is None
            or value["checkout"] != str(checkout) or value["home"] != str(home) or value["entry"] != entry):
        reject()
    if not any(record[1] == "home" and (Path(home) == Path(record[2]) or Path(record[2]) in Path(home).parents)
               for record in records) or not any(record[1] == "checkout" and record[2] == str(checkout) for record in records):
        reject()
    observed = context(run_id, source_id, checkout, home, entry, records, value["files"])
    if value != observed:
        reject()


def send(channel, value):
    data = encode(value)
    if len(data) + 1 > LIMIT:
        reject()
    channel.settimeout(2)
    channel.sendall(data + b"\n")


def receive(channel, timeout=2):
    data, deadline = b"", time.monotonic() + timeout
    while b"\n" not in data:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            reject()
        channel.settimeout(remaining)
        chunk = channel.recv(LIMIT + 1 - len(data))
        if not chunk or len(data) + len(chunk) > LIMIT:
            reject()
        data += chunk
    if not data.endswith(b"\n") or data.count(b"\n") != 1:
        reject()
    return decode(data[:-1])


def packet(kind, binding, challenge, transfer=None):
    value = {"schema": 1, "type": kind, "binding": binding, "challenge": challenge}
    if transfer is not None:
        value["transfer"] = transfer
    return value


def matches(actual, expected):
    return (type(actual) is dict and set(actual) == set(expected)
            and all(type(actual[key]) is type(value) and actual[key] == value for key, value in expected.items()))


def issue(channel, expected, fault=None, after_hello=None):
    hello = receive(channel)
    if (type(hello) is not dict or set(hello) != {"schema", "type", "binding", "challenge"}
            or type(hello["challenge"]) is not str or re.fullmatch("[0-9a-f]{64}", hello["challenge"]) is None):
        reject()
    binding, challenge, transfer = digest(expected), hello["challenge"], secrets.token_hex(32)
    if not matches(hello, packet("ready", binding, challenge)):
        reject()
    if after_hello:
        after_hello()
    stale = ("0" if challenge[0] != "0" else "1") + challenge[1:]
    grant = packet("grant", binding, stale if fault == "stale-grant" else challenge, transfer)
    send(channel, grant)
    accepted = receive(channel)
    if not matches(accepted, packet("accepted", binding, challenge, transfer)):
        reject()
    if fault == "bad-ack":
        accepted = {**accepted, "transfer": ("0" if transfer[0] != "0" else "1") + transfer[1:]}
    if not matches(accepted, packet("accepted", binding, challenge, transfer)) or fault == "lost-ack":
        reject()
    send(channel, packet("commit", binding, challenge, transfer))
    if not matches(receive(channel), packet("committed", binding, challenge, transfer)):
        reject()


def accept(channel_fd, context_fd, run_id, source_id, checkout, home, entry, records, acquire):
    adopted = None
    with socket.socket(fileno=channel_fd) as channel:
        channel.set_inheritable(False)
        if channel.family != socket.AF_UNIX or channel.type != socket.SOCK_STREAM:
            reject()
        try:
            value = read_context(context_fd)
            validate(value, run_id, source_id, checkout, home, entry, records)
            adopted = acquire()
            binding, challenge = digest(value), secrets.token_hex(32)
            send(channel, packet("ready", binding, challenge))
            grant = receive(channel)
            transfer = grant.get("transfer") if type(grant) is dict else None
            if (type(transfer) is not str or re.fullmatch("[0-9a-f]{64}", transfer) is None
                    or not matches(grant, packet("grant", binding, challenge, transfer))):
                reject()
            validate(value, run_id, source_id, checkout, home, entry, records)
            send(channel, packet("accepted", binding, challenge, transfer))
            if not matches(receive(channel), packet("commit", binding, challenge, transfer)):
                reject()
            validate(value, run_id, source_id, checkout, home, entry, records)
            send(channel, packet("committed", binding, challenge, transfer))
            return adopted
        except BaseException:
            if adopted is not None:
                adopted.close()
            raise
