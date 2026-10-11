import Darwin
import Foundation

/// Intent is persisted before each effect. A phase names work that may have started, not a
/// successful effect. Resume adapters must reconcile receipts and service state before retrying.
public enum DesktopHermesUpgradePhase: String, Codable, Sendable {
    case quiescing, backingUp, updating, restoringServices, verifying, cancelling, completed, cancelled
    public var terminal: Bool { self == .completed || self == .cancelled }
    public var canCancel: Bool { self == .quiescing || self == .backingUp }
    public var sourceMayHaveChanged: Bool {
        self == .updating || self == .restoringServices || self == .verifying || self == .completed
    }
}

public struct DesktopHermesUpgradeService: Codable, Equatable, Sendable {
    public let label: String
    public let wasLoaded: Bool
    public let configurationDigest: String
    public init(label: String, wasLoaded: Bool, configurationDigest: String) {
        self.label = label; self.wasLoaded = wasLoaded; self.configurationDigest = configurationDigest
    }
}

public struct DesktopHermesUpgradeBackup: Codable, Equatable, Sendable {
    /// An operation UUID, not an arbitrary path supplied by a receipt.
    public let runID: String
    public let manifestSHA256: String
    public init(runID: String, manifestSHA256: String) {
        self.runID = runID; self.manifestSHA256 = manifestSHA256
    }
}

public struct DesktopHermesUpgradeJournal: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let runID: String
    public let target: DesktopHermesUpgradeTarget
    public let sourceCommit: String
    public let sourceIdentity: String
    public let checkout: String
    public let hermesHome: String
    public let services: [DesktopHermesUpgradeService]
    public let originalChannel: String?
    public fileprivate(set) var phase: DesktopHermesUpgradePhase
    public fileprivate(set) var backup: DesktopHermesUpgradeBackup?
    public fileprivate(set) var updatedAt: Date

    public init(runID: String = UUID().uuidString, target: DesktopHermesUpgradeTarget,
                installation: DesktopLocalHermesInstallation, services: [DesktopHermesUpgradeService],
                originalChannel: String?, now: Date = Date()) {
        schemaVersion = 1; self.runID = runID; self.target = target
        sourceCommit = installation.commit; sourceIdentity = installation.runtimeIdentity
        checkout = installation.checkoutRoot.path; hermesHome = installation.hermesHome.path
        self.services = services; self.originalChannel = originalChannel
        phase = .quiescing; backup = nil; updatedAt = now
    }

    fileprivate func validate() throws {
        guard schemaVersion == 1, UUID(uuidString: runID) != nil,
              DesktopLocalHermesDetector.isObjectID(sourceCommit), Self.digest(sourceIdentity),
              Self.path(checkout), Self.path(hermesHome),
              checkout == hermesHome + "/hermes-agent", services.count <= 32,
              Set(services.map(\.label)).count == services.count,
              services.allSatisfy({ $0.label.range(of: "^[a-zA-Z0-9][a-zA-Z0-9.-]{0,127}$", options: .regularExpression) != nil && Self.digest($0.configurationDigest) }),
              originalChannel == nil || ["stable", "main"].contains(originalChannel!),
              updatedAt.timeIntervalSince1970.isFinite,
              backup == nil || (backup?.runID == runID && Self.digest(backup!.manifestSHA256)),
              !phase.sourceMayHaveChanged || backup != nil
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private static func path(_ text: String) -> Bool {
        text.hasPrefix("/") && text != "/" && URL(fileURLWithPath: text).standardizedFileURL.path == text
            && !text.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
    }
    static func digest(_ text: String) -> Bool {
        text.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }
}

/// Shares the migration operation lease. Reads on a clean Mac create no files. Unsafe or unknown
/// journal contents fail closed, including a dangling link or a journal from a future app version.
public final class DesktopHermesUpgradeJournalStore: @unchecked Sendable {
    public let root: URL
    public let journalURL: URL
    public init(root: URL) {
        self.root = root.standardizedFileURL
        journalURL = self.root.appendingPathComponent("hermes-upgrade-state.json")
    }

    public func loadReadOnly() throws -> DesktopHermesUpgradeJournal? {
        var info = stat()
        if lstat(journalURL.path, &info) != 0 {
            guard errno == ENOENT else { throw DesktopHermesUpgradeError.recoveryRequired }
            return nil
        }
        try validateRoot()
        let fd = Darwin.open(journalURL.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        guard fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_nlink == 1,
              info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0,
              info.st_size > 0, info.st_size <= 64 * 1024
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        guard let data = try? FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: 64 * 1024 + 1),
              data.count <= 64 * 1024,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys).isSubset(of: ["schemaVersion", "runID", "target", "sourceCommit", "sourceIdentity", "checkout", "hermesHome", "services", "originalChannel", "phase", "backup", "updatedAt"]),
              let journal = try? JSONDecoder().decode(DesktopHermesUpgradeJournal.self, from: data)
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        try journal.validate()
        return journal
    }

    public func requireNoPendingUpgrade() throws {
        if let journal = try loadReadOnly(), !journal.phase.terminal {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
    }

    /// Called only with the shared operation lease held. Refuses to replace another pending run.
    public func begin(_ journal: DesktopHermesUpgradeJournal) throws {
        try requireNoPendingUpgrade()
        guard journal.phase == .quiescing, journal.backup == nil else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        // A later upgrade must not erase the earlier recovery point's target/service/backup
        // references merely because the current cursor is terminal.
        if let previous = try loadReadOnly() { try archive(previous) }
        try persist(journal)
    }

    private func archive(_ journal: DesktopHermesUpgradeJournal) throws {
        let directory = root.appendingPathComponent("hermes-upgrade-runs")
        if !FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        }
        var info = stat()
        guard lstat(directory.path, &info) == 0, info.st_mode & S_IFMT == S_IFDIR,
              info.st_uid == getuid(), info.st_mode & 0o077 == 0,
              directory.resolvingSymlinksInPath().path == directory.path else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(journal)
        let destination = directory.appendingPathComponent(journal.runID.lowercased() + ".json")
        let temporary = directory.appendingPathComponent(".archive-\(UUID().uuidString).tmp")
        let fd = Darwin.open(temporary.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd); try? FileManager.default.removeItem(at: temporary) }
        try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
        guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        // macOS exclusive rename is atomic and leaves no second hard link if the app dies here.
        if Darwin.renamex_np(temporary.path, destination.path, UInt32(RENAME_EXCL)) != 0 {
            // Retry after a crash between the archive and new cursor: validate the existing copy.
            let copyFD = Darwin.open(destination.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
            guard copyFD >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            defer { Darwin.close(copyFD) }
            guard fstat(copyFD, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_nlink == 1,
                  info.st_uid == getuid(), info.st_mode & 0o077 == 0, info.st_size <= 64 * 1024,
                  let existing = try? FileHandle(fileDescriptor: copyFD, closeOnDealloc: false).read(upToCount: 64 * 1024 + 1),
                  existing == data else { throw DesktopHermesUpgradeError.recoveryRequired }
        }
        let folder = Darwin.open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard folder >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(folder) }
        guard fsync(folder) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    @discardableResult
    public func transition(runID: String, to phase: DesktopHermesUpgradePhase,
                           backup: DesktopHermesUpgradeBackup? = nil) throws -> DesktopHermesUpgradeJournal {
        guard var journal = try loadReadOnly(), journal.runID == runID else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        let allowed: [DesktopHermesUpgradePhase: Set<DesktopHermesUpgradePhase>] = [
            .quiescing: [.backingUp, .cancelling], .backingUp: [.updating, .cancelling],
            .updating: [.restoringServices], .restoringServices: [.verifying],
            .verifying: [.completed], .cancelling: [.cancelled],
        ]
        guard allowed[journal.phase]?.contains(phase) == true else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        if let backup {
            guard journal.phase == .backingUp, phase == .updating else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            journal.backup = backup
        }
        journal.phase = phase; journal.updatedAt = Date()
        try persist(journal)
        return journal
    }

    private func validateRoot() throws {
        var info = stat()
        guard root.path != "/", root.path.hasPrefix("/"), lstat(root.path, &info) == 0,
              info.st_mode & S_IFMT == S_IFDIR, info.st_uid == getuid(), info.st_mode & 0o077 == 0,
              root.resolvingSymlinksInPath().path == root.path
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func persist(_ journal: DesktopHermesUpgradeJournal) throws {
        try journal.validate(); try validateRoot()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(journal)
        guard data.count <= 64 * 1024 else { throw DesktopHermesUpgradeError.recoveryRequired }
        let temporary = root.appendingPathComponent(".hermes-upgrade-\(UUID().uuidString).tmp")
        let fd = Darwin.open(temporary.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd); try? FileManager.default.removeItem(at: temporary) }
        do { try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data) }
        catch { throw DesktopHermesUpgradeError.recoveryRequired }
        guard fsync(fd) == 0, Darwin.rename(temporary.path, journalURL.path) == 0 else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        let directory = Darwin.open(root.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard directory >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(directory) }
        guard fsync(directory) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
}

public struct DesktopHermesUpgradePreflight: Sendable {
    public let installation: DesktopLocalHermesInstallation
    public let services: [DesktopHermesUpgradeService]
    public let originalChannel: String?
    public init(installation: DesktopLocalHermesInstallation, services: [DesktopHermesUpgradeService], originalChannel: String?) {
        self.installation = installation; self.services = services; self.originalChannel = originalChannel
    }
}

/// Adapters own actual idle/admission proof, upstream lock custody, stopped-writer checks, WAL-safe
/// backup, official completion receipts, service reconciliation and boot-commit verification.
/// No adapter is enabled by default: source/DB compatibility alone does not certify an updater.
public protocol DesktopHermesUpgradeOperating: Sendable {
    func preflight(target: DesktopHermesUpgradeTarget, confirmedIdentity: String) async throws -> DesktopHermesUpgradePreflight
    func acquireUpstreamLock(checkout: URL, hermesHome: URL) async throws -> any DesktopHermesUpgradeLockHolding
    func validateResume(_ journal: DesktopHermesUpgradeJournal) async throws
    func quiesce(_ journal: DesktopHermesUpgradeJournal) async throws
    func backup(_ journal: DesktopHermesUpgradeJournal) async throws -> DesktopHermesUpgradeBackup
    func installExactTarget(_ journal: DesktopHermesUpgradeJournal) async throws
    func restoreServices(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws
    func verify(_ journal: DesktopHermesUpgradeJournal) async throws
}
public protocol DesktopHermesUpgradeLockHolding: AnyObject, Sendable {}

/// Certification applies to a tested source → target path, never to every older/newer installation
/// merely because the destination passed a source contract check.
public struct DesktopHermesUpgradeCertifiedPath: Equatable, Sendable {
    public let sourceCommit: String
    public let target: DesktopHermesUpgradeTarget
    public init(sourceCommit: String, target: DesktopHermesUpgradeTarget) throws {
        guard DesktopLocalHermesDetector.isObjectID(sourceCommit) else { throw DesktopHermesUpgradeError.invalidRelease }
        self.sourceCommit = sourceCommit; self.target = target
    }
}

/// Serializes clicks, persists intent across process death, and never rolls a database back.
/// Cancellation is cooperative; UI task cancellation cannot interrupt the committed update tail.
public actor DesktopHermesUpgradeCoordinator {
    private let migration: DesktopMigrationJournalStore
    private let store: DesktopHermesUpgradeJournalStore
    private let operations: any DesktopHermesUpgradeOperating
    private let certifiedPaths: [DesktopHermesUpgradeCertifiedPath]
    private var running = false
    private var cancelRequested = false
    public init(migration: DesktopMigrationJournalStore, operations: any DesktopHermesUpgradeOperating,
                certifiedPaths: [DesktopHermesUpgradeCertifiedPath] = []) {
        self.migration = migration; store = migration.hermesUpgradeJournal
        self.operations = operations; self.certifiedPaths = certifiedPaths
    }
    public func requestCancellation() { cancelRequested = true }

    public func start(target: DesktopHermesUpgradeTarget, confirmedIdentity: String,
                      progress: @escaping @Sendable (DesktopHermesUpgradeJournal) -> Void = { _ in }) async throws -> DesktopHermesUpgradeJournal {
        guard !running, certifiedPaths.contains(where: { $0.target == target }) else { throw DesktopHermesUpgradeError.preflightBlocked }
        running = true; cancelRequested = false
        defer { running = false }
        do {
            let lease = try migration.acquireHermesUpgradeOperationLease()
            defer { withExtendedLifetime(lease) {} }
            try store.requireNoPendingUpgrade()
            let initial = try await operations.preflight(target: target, confirmedIdentity: confirmedIdentity)
            guard initial.installation.runtimeIdentity == confirmedIdentity,
                  initial.installation.dependenciesConsistent,
                  certifiedPaths.contains(where: { $0.target == target && $0.sourceCommit == initial.installation.commit }),
                  initial.installation.commit != target.commit,
                  !DesktopLocalHermesDetector.version(target.version, isOlderThan: initial.installation.version)
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            let upstream = try await operations.acquireUpstreamLock(checkout: initial.installation.checkoutRoot,
                                                                   hermesHome: initial.installation.hermesHome)
            defer { withExtendedLifetime(upstream) {} }
            // A CLI update or git checkout may have raced the initial inventory.
            let checked = try await operations.preflight(target: target, confirmedIdentity: confirmedIdentity)
            guard checked.installation == initial.installation, checked.services == initial.services,
                  checked.originalChannel == initial.originalChannel else { throw DesktopHermesUpgradeError.preflightBlocked }
            if cancelRequested { throw DesktopHermesUpgradeError.preflightBlocked }
            let journal = DesktopHermesUpgradeJournal(target: target, installation: checked.installation,
                services: checked.services, originalChannel: checked.originalChannel)
            try store.begin(journal)
            // An unstructured task does not inherit cancellation of the UI's awaiting task. Once the
            // durable intent exists, only requestCancellation() may cancel at a safe phase boundary.
            let execution = Task { try await self.finish(journal, progress: progress) }
            return try await execution.value
        } catch let error as DesktopHermesUpgradeError { throw error }
        catch {
            do { try store.requireNoPendingUpgrade() }
            catch { throw DesktopHermesUpgradeError.recoveryRequired }
            throw DesktopHermesUpgradeError.preflightBlocked
        }
    }

    public func resume(runID: String, progress: @escaping @Sendable (DesktopHermesUpgradeJournal) -> Void = { _ in }) async throws -> DesktopHermesUpgradeJournal {
        guard !running else { throw DesktopHermesUpgradeError.preflightBlocked }
        running = true; cancelRequested = false
        defer { running = false }
        do {
            let lease = try migration.acquireHermesUpgradeOperationLease()
            defer { withExtendedLifetime(lease) {} }
            guard let journal = try store.loadReadOnly(), journal.runID == runID,
                  !journal.phase.terminal,
                  certifiedPaths.contains(where: { $0.target == journal.target && $0.sourceCommit == journal.sourceCommit }) else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            // The adapter must prove receipt custody, source state and absence of new/unowned writers.
            try await operations.validateResume(journal)
            let upstream = try await operations.acquireUpstreamLock(checkout: URL(fileURLWithPath: journal.checkout),
                                                                   hermesHome: URL(fileURLWithPath: journal.hermesHome))
            defer { withExtendedLifetime(upstream) {} }
            try await operations.validateResume(journal)
            let execution = Task { try await self.finish(journal, progress: progress) }
            return try await execution.value
        } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    private func finish(_ initial: DesktopHermesUpgradeJournal,
                        progress: @Sendable (DesktopHermesUpgradeJournal) -> Void) async throws -> DesktopHermesUpgradeJournal {
        var journal = initial
        do {
            while !journal.phase.terminal {
                progress(journal)
                if cancelRequested, journal.phase.canCancel {
                    journal = try store.transition(runID: journal.runID, to: .cancelling)
                    continue
                }
                switch journal.phase {
                case .quiescing:
                    try await operations.quiesce(journal)
                    journal = try store.transition(runID: journal.runID, to: .backingUp)
                case .backingUp:
                    let backup = try await operations.backup(journal)
                    // A request made during backup still cancels before the source commit point.
                    if cancelRequested {
                        journal = try store.transition(runID: journal.runID, to: .cancelling)
                    } else {
                        journal = try store.transition(runID: journal.runID, to: .updating, backup: backup)
                    }
                case .updating:
                    try await operations.installExactTarget(journal)
                    journal = try store.transition(runID: journal.runID, to: .restoringServices)
                case .restoringServices:
                    try await operations.restoreServices(journal, originalInstallation: false)
                    journal = try store.transition(runID: journal.runID, to: .verifying)
                case .verifying:
                    try await operations.verify(journal)
                    journal = try store.transition(runID: journal.runID, to: .completed)
                case .cancelling:
                    try await operations.restoreServices(journal, originalInstallation: true)
                    journal = try store.transition(runID: journal.runID, to: .cancelled)
                case .completed, .cancelled: break
                }
            }
            progress(journal)
            return journal
        } catch {
            // The durable phase remains the recovery cursor. In particular, neither a Git rollback
            // nor a DB restore is implied by a failed updater or an exit-zero owed completion.
            throw DesktopHermesUpgradeError.recoveryRequired
        }
    }
}
