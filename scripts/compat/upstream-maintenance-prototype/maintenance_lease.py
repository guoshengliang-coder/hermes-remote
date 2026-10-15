"""POSIX-only upstream design prototype; never imported by the GO application.

Cooperative writers enter BEFORE any bootstrap/config/database write. A maintenance
request closes admission before draining lifetime writer locks. Paths and fleet home
are explicit: this module cannot discover profiles or certify nonparticipating code.
No PID scans, signals, runtime imports, receipts or production configuration.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat


class Rejected(ValueError):
    """Internal prototype boundary, not a user-facing error string."""


def _directory(path, private=False):
    path = Path(path)
    if not path.is_absolute() or path.resolve(strict=True) != path:
        raise Rejected("noncanonical-directory")
    value = path.stat()
    if (not stat.S_ISDIR(value.st_mode) or value.st_uid != os.getuid()
            or value.st_mode & (0o077 if private else 0o022)):
        raise Rejected("unsafe-directory")
    return value.st_dev, value.st_ino


def _file(path, create=False):
    flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
    fd = os.open(path, flags | (os.O_CREAT | os.O_EXCL if create else 0), 0o600)
    try:
        value = os.fstat(fd)
        current = path.lstat()
        if (not stat.S_ISREG(value.st_mode) or value.st_uid != os.getuid()
                or value.st_nlink != 1 or stat.S_IMODE(value.st_mode) != 0o600
                or (value.st_dev, value.st_ino) != (current.st_dev, current.st_ino)):
            raise Rejected("unsafe-control-file")
        return fd
    except BaseException:
        os.close(fd)
        raise


def _identity(fd):
    value = os.fstat(fd)
    return [value.st_dev, value.st_ino]


class Domain:
    """A pre-enrolled home OR checkout. Never silently enroll a missing domain."""

    def __init__(self, registry, kind, directory):
        self.registry = Path(registry)
        self.directory = Path(directory)
        if kind not in ("home", "checkout"):
            raise Rejected("unknown-domain-kind")
        self.registry_identity = _directory(self.registry, private=True)
        self.root_identity = _directory(self.directory)
        if (self.registry == self.directory or self.directory in self.registry.parents
                or self.registry in self.directory.parents):
            raise Rejected("overlapping-control-and-data")
        self.key = hashlib.sha256((kind + "\0" + str(self.directory)).encode()).hexdigest()
        self.binding = {"schema": 1, "kind": kind, "path": str(self.directory),
                        "identity": list(self.root_identity)}

    def _path(self, suffix):
        return self.registry / (self.key + "." + suffix)

    def enroll(self):
        """One controlled bootstrap step, never a writer's fallback.

        A crash/parallel enrollment may leave incomplete files. That state refuses
        use; operators must resolve it while independently holding the domain idle.
        Stable lock files are never unlinked or replaced here.
        """
        fds = []
        try:
            self._check_directories()
            for kind in ("gate", "writers"):
                fds.append(_file(self._path(kind), create=True))
            record = {**self.binding, "locks": [_identity(fd) for fd in fds]}
            metadata = _file(self._path("json"), create=True)
            try:
                data = json.dumps(record, sort_keys=True, separators=(",", ":")).encode()
                with os.fdopen(os.dup(metadata), "wb") as output:
                    output.write(data)
                    output.flush()
                    os.fsync(output.fileno())
            finally:
                os.close(metadata)
            directory_fd = os.open(self.registry, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            for fd in fds:
                os.close(fd)

    def _check_directories(self):
        if (_directory(self.registry, private=True) != self.registry_identity
                or _directory(self.directory) != self.root_identity):
            raise Rejected("replaced-directory")

    def open(self):
        self._check_directories()
        metadata = _file(self._path("json"))
        try:
            data = os.read(metadata, 8193)
            if len(data) > 8192:
                raise Rejected("oversized-binding")
            record = json.loads(data)
            if (not isinstance(record, dict) or set(record) != set(self.binding) | {"locks"}
                    or {k: v for k, v in record.items() if k != "locks"} != self.binding):
                raise Rejected("changed-binding")
        finally:
            os.close(metadata)
        fds = []
        try:
            for kind in ("gate", "writers"):
                fds.append(_file(self._path(kind)))
            if [_identity(fd) for fd in fds] != record["locks"]:
                raise Rejected("replaced-lock-file")
            self._check_directories()
            return tuple(fds)
        except BaseException:
            for fd in fds:
                os.close(fd)
            raise


def _domains(domains):
    ordered = sorted(domains, key=lambda value: (str(value.registry), value.key))
    if (not ordered or len(ordered) > 32
            or len({(str(d.registry), d.key) for d in ordered}) != len(ordered)
            or {d.binding["kind"] for d in ordered} != {"home", "checkout"}
            or len({str(d.registry) for d in ordered}) != 1):
        raise Rejected("incomplete-or-duplicate-domains")
    return ordered


class Lease:
    """Owned file descriptions, not a serializable token or completed-upgrade proof.

    Close, NEVER LOCK_UN: an inherited child must keep the same kernel lock after
    the parent dies/closes its descriptor. Children must explicitly use pass_fds;
    CLOEXEC otherwise prevents accidentally leaking an indefinite lease.
    """

    def __init__(self, domains, maintenance=False):
        self.domains = _domains(domains)
        self.maintenance = maintenance
        self.ready = False
        self._pairs = []
        try:
            # Acquire every admission gate in one stable order before lifetime locks.
            for domain in self.domains:
                pair = domain.open()
                self._pairs.append(pair)
                fcntl.flock(pair[0], (fcntl.LOCK_EX if maintenance else fcntl.LOCK_SH) | fcntl.LOCK_NB)
            if not maintenance:
                for _, writer in self._pairs:
                    fcntl.flock(writer, fcntl.LOCK_SH | fcntl.LOCK_NB)
                for gate, _ in self._pairs:
                    os.close(gate)
                self._pairs = [(-1, writer) for _, writer in self._pairs]
                self.ready = True
        except BaseException:
            self.close()
            raise

    @classmethod
    def inherit_maintenance(cls, domains, descriptors):
        """Trusted-launch kernel primitive, not an authenticated transaction handoff.

        Duplicate explicitly passed open descriptions and validate current bindings
        and exclusive locks. Never acquire a new shared writer lease under the
        parent's exclusive gates. Caller must separately authorize run/source/role.
        Caller passes live handles explicitly; this method does not discover them
        from startup configuration or accept a saved receipt as authority.
        """
        value = cls.__new__(cls)
        value.domains = _domains(domains)
        value.maintenance, value.ready, value._pairs = True, False, []
        descriptors = tuple(descriptors)
        if (len(descriptors) != 2 * len(value.domains)
                or any(type(fd) is not int or fd < 3 for fd in descriptors)
                or len(set(descriptors)) != len(descriptors)):
            raise Rejected("invalid-maintenance-descriptors")
        duplicated = []
        try:
            for fd in descriptors:
                duplicate = os.dup(fd)  # CLOEXEC; closing the caller's copy cannot drop this lock.
                duplicated.append(duplicate)
                if fcntl.fcntl(duplicate, fcntl.F_GETFL) & os.O_ACCMODE != os.O_RDWR:
                    raise Rejected("readonly-maintenance-descriptor")
            for index, domain in enumerate(value.domains):
                observed = domain.open()
                try:
                    pair = duplicated[2 * index:2 * index + 2]
                    if [_identity(fd) for fd in observed] != [_identity(fd) for fd in pair]:
                        raise Rejected("foreign-maintenance-descriptors")
                finally:
                    for fd in observed:
                        os.close(fd)
            for fd in duplicated:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            value._pairs = list(zip(duplicated[::2], duplicated[1::2]))
            value.ready = True
            return value
        except BaseException:
            for fd in duplicated:
                os.close(fd)
            raise

    def drain(self):
        """Nonblocking: busy writers remain alive; closed admission is retained.

        Caller owns a deadline/cancel policy. A failed drain closes partially taken
        writer descriptions and reopens them without dropping ANY admission gate.
        """
        if not self.maintenance or not self._pairs:
            raise Rejected("not-an-active-maintenance-request")
        if self.ready:
            raise Rejected("already-drained")
        try:
            for domain, held in zip(self.domains, self._pairs):
                observed = domain.open()
                try:
                    if [_identity(fd) for fd in observed] != [_identity(fd) for fd in held]:
                        raise Rejected("changed-held-locks")
                finally:
                    for fd in observed:
                        os.close(fd)
            for _, writer in self._pairs:
                fcntl.flock(writer, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            for _, writer in self._pairs:
                os.close(writer)
            # Keep gates closed. If rebinding fails, abandon the request entirely.
            gates = [gate for gate, _ in self._pairs]
            self._pairs = [(gate, -1) for gate in gates]
            try:
                for index, domain in enumerate(self.domains):
                    gate, writer = domain.open()
                    os.close(gate)
                    self._pairs[index] = (gates[index], writer)
            except BaseException:
                self.close()
                raise
            return False
        except BaseException:
            self.close()
            raise
        self.ready = True
        return True

    @property
    def pass_fds(self):
        if not self.ready:
            raise Rejected("cannot-transfer-undrained-request")
        return tuple(fd for pair in self._pairs for fd in pair if fd >= 0)

    def close(self):
        self.ready = False
        for pair in reversed(self._pairs):
            for fd in reversed(pair):
                if fd >= 0:
                    os.close(fd)
        self._pairs = []
