import Darwin
import Foundation

/// Intent is persisted before each effect. A phase names work that may have started, not a
/// successful effect. Resume adapters must reconcile receipts and service state before retrying.
public enum DesktopHermesUpgradePhase: String, Codable, Sendable {
    case quiescing, backingUp, updating, restoringServices, verifying, cancelling, recovering, completed, cancelled, rolledBack
    public var terminal: Bool { self == .completed || self == .cancelled || self == .rolledBack }
    public var canCancel: Bool { self == .quiescing || self == .backingUp }
    public var sourceMayHaveChanged: Bool {
        self == .updating || self == .restoringServices || self == .verifying || self == .completed
            || self == .recovering || self == .rolledBack
    }
}

public struct DesktopHermesUpgradeService: Codable, Equatable, Sendable {
    public let label: String
    public let wasLoaded: Bool
    public let configurationDigest: String
    public let processID: Int32?
    public let processStartedAt: Date?
    public init(label: String, wasLoaded: Bool, configurationDigest: String,
                processID: Int32? = nil, processStartedAt: Date? = nil) {
        self.label = label; self.wasLoaded = wasLoaded; self.configurationDigest = configurationDigest
        self.processID = processID; self.processStartedAt = processStartedAt
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
    public let upstreamUpdateID: String?
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

    public init(runID: UUID, target: DesktopHermesUpgradeTarget, sourceCommit: String, sourceIdentity: String,
                checkout: URL, hermesHome: URL, services: [DesktopHermesUpgradeService], originalChannel: String?,
                upstreamUpdateID: UUID? = nil, now: Date = Date()) throws {
        guard checkout.isFileURL, hermesHome.isFileURL else { throw DesktopHermesUpgradeError.preflightBlocked }
        schemaVersion = 1; self.runID = runID.uuidString
        self.upstreamUpdateID = upstreamUpdateID?.uuidString; self.target = target
        self.sourceCommit = sourceCommit; self.sourceIdentity = sourceIdentity
        self.checkout = checkout.path; self.hermesHome = hermesHome.path; self.services = services
        self.originalChannel = originalChannel; phase = .quiescing; backup = nil; updatedAt = now
        try validate()
    }

    func advancing(to phase: DesktopHermesUpgradePhase, backup: DesktopHermesUpgradeBackup?, now: Date) throws -> Self {
        var result = self
        result.phase = phase; result.backup = backup ?? self.backup; result.updatedAt = now
        try result.validate()
        return result
    }

    func validate() throws {
        guard schemaVersion == 1, UUID(uuidString: runID) != nil,
              upstreamUpdateID == nil || UUID(uuidString: upstreamUpdateID!) != nil,
              DesktopLocalHermesDetector.isObjectID(sourceCommit), Self.digest(sourceIdentity),
              Self.path(checkout), Self.path(hermesHome),
              checkout == hermesHome + "/hermes-agent", services.count <= 32,
              Set(services.map(\.label)).count == services.count,
              services.allSatisfy({ $0.label.range(of: "^[a-zA-Z0-9][a-zA-Z0-9.-]{0,127}$", options: .regularExpression) != nil && Self.digest($0.configurationDigest)
                  && (($0.processID == nil && $0.processStartedAt == nil)
                      || ($0.wasLoaded && $0.processID.map({ $0 > 0 }) == true && $0.processStartedAt?.timeIntervalSince1970.isFinite == true)) }),
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
        let snapshot = info
        guard let data = try? FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: 64 * 1024 + 1),
              data.count == Int(snapshot.st_size), data.count <= 64 * 1024,
              DesktopHermesUpgradeJSON.isUnambiguous(data),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys).isSubset(of: ["schemaVersion", "runID", "upstreamUpdateID", "target", "sourceCommit", "sourceIdentity", "checkout", "hermesHome", "services", "originalChannel", "phase", "backup", "updatedAt"]),
              let target = object["target"] as? [String: Any], Set(target.keys) == ["version", "commit", "channel"],
              let services = object["services"] as? [[String: Any]],
              services.allSatisfy({ Set($0.keys).isSubset(of: ["label", "wasLoaded", "configurationDigest", "processID", "processStartedAt"]) }),
              object["backup"] == nil || object["backup"] is NSNull
                || (object["backup"] as? [String: Any]).map({ Set($0.keys) == ["runID", "manifestSHA256"] }) == true,
              let journal = try? JSONDecoder().decode(DesktopHermesUpgradeJournal.self, from: data)
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        guard unchanged(snapshot, descriptor: fd) else { throw DesktopHermesUpgradeError.recoveryRequired }
        try journal.validate()
        return journal
    }

    private func unchanged(_ expected: stat, descriptor: Int32) -> Bool {
        var descriptorInfo = stat(), pathInfo = stat()
        guard fstat(descriptor, &descriptorInfo) == 0, lstat(journalURL.path, &pathInfo) == 0 else { return false }
        func matches(_ value: stat) -> Bool {
            value.st_dev == expected.st_dev && value.st_ino == expected.st_ino
                && value.st_mode == expected.st_mode && value.st_uid == expected.st_uid
                && value.st_nlink == expected.st_nlink && value.st_size == expected.st_size
                && value.st_mtimespec.tv_sec == expected.st_mtimespec.tv_sec
                && value.st_mtimespec.tv_nsec == expected.st_mtimespec.tv_nsec
                && value.st_ctimespec.tv_sec == expected.st_ctimespec.tv_sec
                && value.st_ctimespec.tv_nsec == expected.st_ctimespec.tv_nsec
        }
        return matches(descriptorInfo) && matches(pathInfo)
    }

    public func requireNoPendingUpgrade() throws {
        if let journal = try loadReadOnly(), !journal.phase.terminal {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
    }

    private func validateRoot() throws {
        var info = stat()
        guard root.path != "/", root.path.hasPrefix("/"), lstat(root.path, &info) == 0,
              info.st_mode & S_IFMT == S_IFDIR, info.st_uid == getuid(), info.st_mode & 0o077 == 0,
              root.resolvingSymlinksInPath().path == root.path
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
}
