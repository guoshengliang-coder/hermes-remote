import Darwin
import Foundation

public enum DesktopMigrationState: String, Codable, Equatable, Sendable {
    case cleanUninstalled = "clean_uninstalled"
    case legacyActive = "legacy_active"
    case preflight
    case accountStaged = "account_staged"
    case candidateStarting = "candidate_starting"
    case candidateAuthenticated = "candidate_authenticated"
    case candidateHealthy = "candidate_healthy"
    case commitPending = "commit_pending"
    case accountActive = "account_active"
    case rollingBack = "rolling_back"
    case rollbackAttentionRequired = "rollback_attention_required"
}

public enum DesktopLastKnownGoodMode: String, Codable, Equatable, Sendable {
    case none
    case legacy
    case account
}

public struct DesktopMigrationJournal: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let runID: String
    public let state: DesktopMigrationState
    public let lastKnownGoodMode: DesktopLastKnownGoodMode
    public let releaseVersion: String
    public let bindingID: String?
    public let bindingGeneration: Int?
    public let updatedAt: String

    public init(
        schemaVersion: Int = 1,
        runID: String,
        state: DesktopMigrationState,
        lastKnownGoodMode: DesktopLastKnownGoodMode,
        releaseVersion: String,
        bindingID: String?,
        bindingGeneration: Int?,
        updatedAt: String
    ) {
        self.schemaVersion = schemaVersion
        self.runID = runID
        self.state = state
        self.lastKnownGoodMode = lastKnownGoodMode
        self.releaseVersion = releaseVersion
        self.bindingID = bindingID
        self.bindingGeneration = bindingGeneration
        self.updatedAt = updatedAt
    }
}

public enum DesktopMigrationJournalError: Error, Equatable, Sendable {
    case invalidRoot
    case lockUnavailable
    case unsafeStateFile
    case invalidState
    case runMismatch
    case inputMismatch
    case invalidTransition
    case persistenceFailed
}

public final class DesktopMigrationOperationLease: @unchecked Sendable {
    private let descriptor: Int32

    fileprivate init(descriptor: Int32) { self.descriptor = descriptor }

    deinit {
        flock(descriptor, LOCK_UN)
        Darwin.close(descriptor)
    }
}

public final class DesktopMigrationJournalStore: @unchecked Sendable {
    private let root: URL
    private let journalURL: URL
    private let lockURL: URL
    private let fileManager: FileManager
    private let now: @Sendable () -> Date

    public init(
        root: URL,
        fileManager: FileManager = .default,
        now: @escaping @Sendable () -> Date = { Date() }
    ) throws {
        let canonical = root.standardizedFileURL.resolvingSymlinksInPath()
        guard canonical.isFileURL, canonical.path.hasPrefix("/"), canonical.path != "/" else {
            throw DesktopMigrationJournalError.invalidRoot
        }
        self.root = canonical
        journalURL = canonical.appendingPathComponent("migration-state.json")
        lockURL = canonical.appendingPathComponent("migration.lock")
        self.fileManager = fileManager
        self.now = now
    }

    public func load() throws -> DesktopMigrationJournal? {
        try withExclusiveLock { try loadUnlocked() }
    }

    /// Startup/status inspection must remain inert on a clean Mac. Journal saves use atomic file
    /// replacement, so a validated read can avoid creating the root or lock when no migration exists.
    public func loadReadOnly() throws -> DesktopMigrationJournal? {
        guard fileManager.fileExists(atPath: root.path) else { return nil }
        let values: URLResourceValues
        do {
            values = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        } catch {
            throw DesktopMigrationJournalError.invalidRoot
        }
        guard values.isDirectory == true,
              values.isSymbolicLink != true,
              safeOwnershipAndPermissions(root)
        else { throw DesktopMigrationJournalError.invalidRoot }
        return try loadUnlocked()
    }

    public func acquireOperationLease() throws -> DesktopMigrationOperationLease {
        do { try ensureRoot() }
        catch let error as DesktopMigrationJournalError { throw error }
        catch { throw DesktopMigrationJournalError.persistenceFailed }
        let operationLock = root.appendingPathComponent("migration-operation.lock")
        let descriptor = Darwin.open(
            operationLock.path,
            O_CREAT | O_RDWR | O_NOFOLLOW,
            S_IRUSR | S_IWUSR
        )
        guard descriptor >= 0,
              validateLockDescriptor(descriptor),
              flock(descriptor, LOCK_EX | LOCK_NB) == 0
        else {
            if descriptor >= 0 { Darwin.close(descriptor) }
            throw DesktopMigrationJournalError.lockUnavailable
        }
        return DesktopMigrationOperationLease(descriptor: descriptor)
    }

    public func begin(
        runID: String,
        lastKnownGoodMode: DesktopLastKnownGoodMode,
        releaseVersion: String,
        bindingID: String?,
        bindingGeneration: Int?
    ) throws -> DesktopMigrationJournal {
        try withExclusiveLock {
            if let existing = try loadUnlocked() {
                guard existing.runID == normalizedUUID(runID) else {
                    throw DesktopMigrationJournalError.runMismatch
                }
                guard existing.lastKnownGoodMode == lastKnownGoodMode,
                      existing.releaseVersion == (try? semanticVersion(releaseVersion)),
                      existing.bindingID == (try? optionalUUID(bindingID)),
                      existing.bindingGeneration == (try? validGeneration(bindingGeneration))
                else { throw DesktopMigrationJournalError.inputMismatch }
                return existing
            }
            let journal = DesktopMigrationJournal(
                runID: try requiredUUID(runID),
                state: .preflight,
                lastKnownGoodMode: lastKnownGoodMode,
                releaseVersion: try semanticVersion(releaseVersion),
                bindingID: try optionalUUID(bindingID),
                bindingGeneration: try validGeneration(bindingGeneration),
                updatedAt: canonicalTimestamp(now())
            )
            try saveUnlocked(journal)
            return journal
        }
    }

    public func transition(
        runID: String,
        to next: DesktopMigrationState
    ) throws -> DesktopMigrationJournal {
        try withExclusiveLock {
            guard let current = try loadUnlocked() else {
                throw DesktopMigrationJournalError.invalidState
            }
            guard current.runID == normalizedUUID(runID) else {
                throw DesktopMigrationJournalError.runMismatch
            }
            guard Self.allowedTransitions[current.state]?.contains(next) == true else {
                throw DesktopMigrationJournalError.invalidTransition
            }
            let updated = DesktopMigrationJournal(
                runID: current.runID,
                state: next,
                lastKnownGoodMode: next == .accountActive ? .account : current.lastKnownGoodMode,
                releaseVersion: current.releaseVersion,
                bindingID: current.bindingID,
                bindingGeneration: current.bindingGeneration,
                updatedAt: canonicalTimestamp(now())
            )
            try saveUnlocked(updated)
            return updated
        }
    }

    private func loadUnlocked() throws -> DesktopMigrationJournal? {
        guard fileManager.fileExists(atPath: journalURL.path) else { return nil }
        let values: URLResourceValues
        do {
            values = try journalURL.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey,
            ])
        } catch {
            throw DesktopMigrationJournalError.unsafeStateFile
        }
        guard values.isRegularFile == true,
              values.isSymbolicLink != true,
              (values.fileSize ?? 0) <= 64 * 1024,
              safeOwnershipAndPermissions(journalURL)
        else { throw DesktopMigrationJournalError.unsafeStateFile }
        let data: Data
        do { data = try Data(contentsOf: journalURL) }
        catch { throw DesktopMigrationJournalError.persistenceFailed }
        let allowedKeys = Set([
                "schemaVersion", "runID", "state", "lastKnownGoodMode", "releaseVersion",
                "bindingID", "bindingGeneration", "updatedAt",
              ])
        let requiredKeys = allowedKeys.subtracting(["bindingID", "bindingGeneration"])
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys).isSubset(of: allowedKeys),
              requiredKeys.isSubset(of: Set(object.keys)),
              let journal = try? JSONDecoder().decode(DesktopMigrationJournal.self, from: data),
              journal.schemaVersion == 1,
              (try? requiredUUID(journal.runID)) != nil,
              (try? semanticVersion(journal.releaseVersion)) != nil,
              parseCanonicalTimestamp(journal.updatedAt) != nil,
              validBindingReference(journal.bindingID, journal.bindingGeneration)
        else { throw DesktopMigrationJournalError.invalidState }
        return journal
    }

    private func saveUnlocked(_ journal: DesktopMigrationJournal) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(journal), data.count <= 64 * 1024 else {
            throw DesktopMigrationJournalError.persistenceFailed
        }
        let temporary = root.appendingPathComponent(".migration-state-\(journal.runID).tmp")
        do {
            try data.write(to: temporary, options: [.atomic])
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: temporary.path)
            if fileManager.fileExists(atPath: journalURL.path) {
                _ = try fileManager.replaceItemAt(journalURL, withItemAt: temporary)
            } else {
                try fileManager.moveItem(at: temporary, to: journalURL)
            }
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: journalURL.path)
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw DesktopMigrationJournalError.persistenceFailed
        }
    }

    private func withExclusiveLock<T>(_ operation: () throws -> T) throws -> T {
        do {
            try ensureRoot()
        } catch let error as DesktopMigrationJournalError {
            throw error
        } catch {
            throw DesktopMigrationJournalError.persistenceFailed
        }
        let descriptor = Darwin.open(lockURL.path, O_CREAT | O_RDWR | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw DesktopMigrationJournalError.lockUnavailable }
        defer { Darwin.close(descriptor) }
        guard validateLockDescriptor(descriptor) else {
            throw DesktopMigrationJournalError.lockUnavailable
        }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            throw DesktopMigrationJournalError.lockUnavailable
        }
        defer { flock(descriptor, LOCK_UN) }
        return try operation()
    }

    private func validateLockDescriptor(_ descriptor: Int32) -> Bool {
        guard Darwin.fchmod(descriptor, S_IRUSR | S_IWUSR) == 0 else { return false }
        var lockStat = stat()
        return Darwin.fstat(descriptor, &lockStat) == 0
            && lockStat.st_uid == Darwin.getuid()
            && lockStat.st_nlink == 1
            && lockStat.st_mode & S_IFMT == S_IFREG
    }

    private func ensureRoot() throws {
        if !fileManager.fileExists(atPath: root.path) {
            try fileManager.createDirectory(
                at: root,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
        }
        let values = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard values.isDirectory == true,
              values.isSymbolicLink != true,
              ownedByCurrentUser(root)
        else {
            throw DesktopMigrationJournalError.invalidRoot
        }
        try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        guard safeOwnershipAndPermissions(root) else {
            throw DesktopMigrationJournalError.invalidRoot
        }
    }

    private func safeOwnershipAndPermissions(_ url: URL) -> Bool {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let permissions = attributes[.posixPermissions] as? NSNumber,
              let owner = attributes[.ownerAccountID] as? NSNumber
        else { return false }
        return permissions.intValue & 0o077 == 0 && owner.uint32Value == Darwin.getuid()
    }

    private func ownedByCurrentUser(_ url: URL) -> Bool {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let owner = attributes[.ownerAccountID] as? NSNumber
        else { return false }
        return owner.uint32Value == Darwin.getuid()
    }

    private static let allowedTransitions: [DesktopMigrationState: Set<DesktopMigrationState>] = [
        .cleanUninstalled: [.preflight],
        .legacyActive: [.preflight],
        .preflight: [.accountStaged, .rollingBack],
        .accountStaged: [.candidateStarting, .rollingBack],
        .candidateStarting: [.candidateAuthenticated, .rollingBack],
        .candidateAuthenticated: [.candidateHealthy, .rollingBack],
        .candidateHealthy: [.commitPending, .rollingBack],
        .commitPending: [.accountActive, .rollingBack, .rollbackAttentionRequired],
        .rollingBack: [.cleanUninstalled, .legacyActive, .rollbackAttentionRequired],
        .accountActive: [],
        .rollbackAttentionRequired: [],
    ]
}

private func normalizedUUID(_ value: String) -> String? {
    UUID(uuidString: value)?.uuidString.lowercased()
}

private func requiredUUID(_ value: String) throws -> String {
    guard let normalized = normalizedUUID(value) else {
        throw DesktopMigrationJournalError.invalidState
    }
    return normalized
}

private func optionalUUID(_ value: String?) throws -> String? {
    guard let value else { return nil }
    return try requiredUUID(value)
}

private func validGeneration(_ value: Int?) throws -> Int? {
    guard let value else { return nil }
    guard (1...2_147_483_647).contains(value) else {
        throw DesktopMigrationJournalError.invalidState
    }
    return value
}

private func validBindingReference(_ bindingID: String?, _ generation: Int?) -> Bool {
    if bindingID == nil && generation == nil { return true }
    guard let bindingID, let generation else { return false }
    return normalizedUUID(bindingID) != nil && (1...2_147_483_647).contains(generation)
}

private func semanticVersion(_ value: String) throws -> String {
    guard value.range(
        of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$",
        options: .regularExpression
    ) != nil else { throw DesktopMigrationJournalError.invalidState }
    return value
}

private func canonicalTimestamp(_ date: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: date)
}

private func parseCanonicalTimestamp(_ value: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let date = formatter.date(from: value), formatter.string(from: date) == value else {
        return nil
    }
    return date
}
