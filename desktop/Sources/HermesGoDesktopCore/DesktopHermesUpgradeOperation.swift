import Darwin
import Foundation

/// Durable intent and a GO maintenance lease, not permission to run an upstream updater or
/// restore user data. The executor must independently verify recommendation, updater ownership,
/// service quiescence and backup/data safety before each corresponding effect.
public final class DesktopHermesUpgradeOperation: @unchecked Sendable {
    private let store: DesktopHermesUpgradeJournalStore
    private let directory: Int32
    private let lease: Int32
    private let mutex = NSLock()
    private var current: DesktopHermesUpgradeJournal
    private var ownsDescriptors = false
    private var updateEffectRunning = false
    private let persistenceHook: @Sendable (PersistencePoint) throws -> Void
    enum PersistencePoint: Equatable, Sendable { case temporarySynced, replaced, directorySynced }

    public var snapshot: DesktopHermesUpgradeJournal {
        mutex.lock(); defer { mutex.unlock() }; return current
    }

    /// One official installer invocation per operation object. Holding the object retains the GO
    /// lease; another task cannot advance intent underneath the running effect. This is not a
    /// replacement for the executor's independent service/writer checks.
    func withUpdatingEffect<T>(_ body: (DesktopHermesUpgradeJournal) async throws -> T) async throws -> T {
        let expected = try beginUpdatingEffect()
        defer { finishUpdatingEffect() }
        let result = try await body(expected)
        try requireUpdatingEffect(expected)
        return result
    }

    private func beginUpdatingEffect() throws -> DesktopHermesUpgradeJournal {
        mutex.lock(); defer { mutex.unlock() }
        try assertOwnership()
        guard !updateEffectRunning, current.phase == .updating, current.backup != nil,
              try store.loadReadOnly() == current else { throw DesktopHermesUpgradeError.preflightBlocked }
        updateEffectRunning = true
        return current
    }

    func requireUpdatingEffect(_ expected: DesktopHermesUpgradeJournal) throws {
        mutex.lock(); defer { mutex.unlock() }
        try assertOwnership()
        guard updateEffectRunning, current == expected, try store.loadReadOnly() == expected
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    private func finishUpdatingEffect() {
        mutex.lock(); defer { mutex.unlock() }; updateEffectRunning = false
    }

    public func captureRecoveryPoint(in recovery: DesktopHermesRecoveryPointStore,
                                     externalRuntimeRoots: [URL]) throws -> DesktopHermesUpgradeBackup {
        mutex.lock(); defer { mutex.unlock() }
        try assertOwnership()
        guard try store.loadReadOnly() == current, current.phase == .backingUp else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        let receipt = try recovery.capture(journal: current, externalRuntimeRoots: externalRuntimeRoots)
        try assertOwnership()
        guard try store.loadReadOnly() == current else { throw DesktopHermesUpgradeError.recoveryRequired }
        return receipt
    }

    /// Reconstructs private material only. No current installation or new user data is overwritten.
    public func materializeRecoveryPoint(in recovery: DesktopHermesRecoveryPointStore) throws -> DesktopHermesRecoveryMaterial {
        mutex.lock(); defer { mutex.unlock() }
        try assertOwnership()
        guard try store.loadReadOnly() == current, current.phase == .recovering else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        let material = try recovery.materialize(journal: current)
        try assertOwnership()
        guard try store.loadReadOnly() == current else { throw DesktopHermesUpgradeError.recoveryRequired }
        return material
    }

    /// Begin a new operation, or return the identical already-written initial intent. An old
    /// terminal record may be replaced; a pending record requires explicit exact-state resume.
    public static func begin(store: DesktopHermesUpgradeJournalStore, journal: DesktopHermesUpgradeJournal) throws -> Self {
        try Self(store: store, expected: journal, resuming: false)
    }

    /// Acquires the same kernel lease as install/repair. A journal alone never proves that its
    /// worker died: if the old worker still holds the lease, this call refuses.
    public static func resume(store: DesktopHermesUpgradeJournalStore, expected: DesktopHermesUpgradeJournal) throws -> Self {
        try Self(store: store, expected: expected, resuming: true)
    }

    init(store: DesktopHermesUpgradeJournalStore, expected: DesktopHermesUpgradeJournal, resuming: Bool,
         persistenceHook: @escaping @Sendable (PersistencePoint) throws -> Void = { _ in }) throws {
        try expected.validate()
        guard resuming ? !expected.phase.terminal : (expected.phase == .quiescing && expected.backup == nil)
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let root = store.root
        guard root.isFileURL, root.path != "/", root.resolvingSymlinksInPath().path == root.path
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        // The configured state parent must already exist. Never create arbitrary parent chains.
        let created = mkdir(root.path, 0o700) == 0
        if !created && errno != EEXIST { throw DesktopHermesUpgradeError.recoveryRequired }
        if created {
            let parent = Darwin.open(root.deletingLastPathComponent().path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
            guard parent >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            defer { Darwin.close(parent) }
            guard fsync(parent) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        }
        let directory = Darwin.open(root.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard directory >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        var keepDirectory = false
        defer { if !keepDirectory { Darwin.close(directory) } }
        guard Self.safeDirectory(directory, root: root) else { throw DesktopHermesUpgradeError.recoveryRequired }
        let lease = openat(directory, "migration-operation.lock", O_CREAT | O_RDWR | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0o600)
        guard lease >= 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
        var keepLease = false
        defer { if !keepLease { Darwin.close(lease) } }
        guard Self.safeLease(lease, directory: directory), flock(lease, LOCK_EX | LOCK_NB) == 0
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        do {
            let migration = try DesktopMigrationJournalStore(root: root).loadReadOnly()
            guard migration == nil || migration?.state == .accountActive || migration?.state == .cleanUninstalled
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            let existing = try store.loadReadOnly()
            if resuming {
                guard existing == expected else { throw DesktopHermesUpgradeError.recoveryRequired }
            } else if let existing {
                guard existing == expected || (existing.phase.terminal && existing.runID != expected.runID)
                else { throw DesktopHermesUpgradeError.recoveryRequired }
            }
            self.store = store; self.directory = directory; self.lease = lease
            current = expected; self.persistenceHook = persistenceHook
            if !resuming && existing != expected { try persist(expected, replacing: existing) }
            try assertOwnership()
            ownsDescriptors = true; keepDirectory = true; keepLease = true
        } catch {
            flock(lease, LOCK_UN)
            throw (error as? DesktopHermesUpgradeError) ?? .recoveryRequired
        }
    }

    deinit {
        if ownsDescriptors { flock(lease, LOCK_UN); Darwin.close(lease); Darwin.close(directory) }
    }

    /// Persist intent before the effect. On any error the caller must stop mutating and reload;
    /// an error after rename can mean the new intent is already visible on disk.
    @discardableResult public func advance(expected: DesktopHermesUpgradeJournal, to phase: DesktopHermesUpgradePhase,
                                          backup: DesktopHermesUpgradeBackup? = nil, now: Date = Date()) throws -> DesktopHermesUpgradeJournal {
        mutex.lock(); defer { mutex.unlock() }
        try assertOwnership()
        guard !updateEffectRunning, current == expected, try store.loadReadOnly() == expected else { throw DesktopHermesUpgradeError.recoveryRequired }
        guard Self.transitions[current.phase]?.contains(phase) == true,
              now.timeIntervalSince1970.isFinite, now >= current.updatedAt,
              backup == nil || (current.phase == .backingUp && phase == .updating && current.backup == nil
                  && backup?.runID == current.runID && DesktopHermesUpgradeJournal.digest(backup!.manifestSHA256)),
              !phase.sourceMayHaveChanged || backup != nil || current.backup != nil
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let next = try current.advancing(to: phase, backup: backup, now: now)
        try persist(next, replacing: expected)
        current = next
        return next
    }

    private static let transitions: [DesktopHermesUpgradePhase: Set<DesktopHermesUpgradePhase>] = [
        .quiescing: [.backingUp, .cancelling], .backingUp: [.updating, .cancelling],
        .updating: [.restoringServices, .recovering], .restoringServices: [.verifying, .recovering],
        .verifying: [.completed, .recovering], .cancelling: [.cancelled], .recovering: [.rolledBack],
    ]

    private func persist(_ next: DesktopHermesUpgradeJournal, replacing expected: DesktopHermesUpgradeJournal?) throws {
        try assertOwnership()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(next)
        guard !data.isEmpty, data.count <= 64 * 1024 else { throw DesktopHermesUpgradeError.recoveryRequired }
        let temporary = ".hermes-upgrade-\(UUID().uuidString).tmp"
        let fd = openat(directory, temporary, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd); unlinkat(directory, temporary, 0) }
        do {
            try data.withUnsafeBytes { bytes in
                var offset = 0
                while offset < bytes.count {
                    let written = Darwin.write(fd, bytes.baseAddress!.advanced(by: offset), bytes.count - offset)
                    if written < 0 && errno == EINTR { continue }
                    guard written > 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
                    offset += written
                }
            }
            guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            try persistenceHook(.temporarySynced)
            try assertOwnership()
            guard try store.loadReadOnly() == expected else { throw DesktopHermesUpgradeError.recoveryRequired }
            guard renameat(directory, temporary, directory, "hermes-upgrade-state.json") == 0
            else { throw DesktopHermesUpgradeError.recoveryRequired }
            try persistenceHook(.replaced)
            guard fsync(directory) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            try persistenceHook(.directorySynced)
            try assertOwnership()
            guard try store.loadReadOnly() == next else { throw DesktopHermesUpgradeError.recoveryRequired }
        } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    private func assertOwnership() throws {
        guard Self.safeDirectory(directory, root: store.root), Self.safeLease(lease, directory: directory)
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    private static func safeDirectory(_ descriptor: Int32, root: URL) -> Bool {
        var file = stat(), path = stat()
        return fstat(descriptor, &file) == 0 && lstat(root.path, &path) == 0
            && file.st_dev == path.st_dev && file.st_ino == path.st_ino
            && file.st_uid == path.st_uid && file.st_mode == path.st_mode
            && file.st_uid == getuid() && file.st_mode & S_IFMT == S_IFDIR && file.st_mode & 0o077 == 0
            && path.st_mode & S_IFMT == S_IFDIR && root.resolvingSymlinksInPath().path == root.path
    }

    private static func safeLease(_ descriptor: Int32, directory: Int32) -> Bool {
        var file = stat(), path = stat()
        return fstat(descriptor, &file) == 0
            && fstatat(directory, "migration-operation.lock", &path, AT_SYMLINK_NOFOLLOW) == 0
            && file.st_dev == path.st_dev && file.st_ino == path.st_ino
            && file.st_uid == path.st_uid && file.st_mode == path.st_mode && file.st_nlink == path.st_nlink
            && file.st_uid == getuid() && file.st_mode & S_IFMT == S_IFREG
            && file.st_mode & 0o077 == 0 && file.st_nlink == 1
    }
}
