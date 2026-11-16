import AppKit
import Darwin
import Foundation

public struct DesktopAppUpdateTransaction: Codable, Equatable, Sendable {
    public enum State: String, Codable, Sendable {
        case prepared, replacing, awaitingStartup, completed, recoveryRequired, restoring, restored
    }
    public let id: UUID
    public let destination: URL
    public let staged: URL
    public let backup: URL
    public let workspace: URL
    public let oldDigest: String
    public let newDigest: String
    public let version: String
    public let build: Int
    public let source: String
    public var oldPID: Int32
    public var state: State
    public var changedAt: Date

    public init(prepared: DesktopPreparedAppUpdate, processID: Int32) throws {
        id = UUID()
        destination = prepared.destinationAppURL
        staged = prepared.stagedAppURL
        workspace = prepared.workspaceURL
        backup = destination.deletingLastPathComponent()
            .appendingPathComponent(".HermesGoDesktopPrevious-\(id.uuidString).app")
        oldDigest = prepared.destinationDigest
        newDigest = prepared.stagedDigest
        version = prepared.reference.appVersion
        build = prepared.reference.buildNumber
        source = prepared.reference.sourceCommit
        oldPID = processID
        state = .prepared
        changedAt = Date()
    }

    static func digest(_ url: URL) throws -> String {
        try DesktopManagedComponentContentHasher().identify(directory: url).sha256
    }
}

/// A single owner-private record outlives the app and its helper. It is never deleted on failure.
/// The swap has no automatic timeout rollback: a live/unknown new process must not be overwritten.
public enum DesktopAppUpdateTransactions {
    public static func recordURL(root: URL) -> URL { root.appendingPathComponent("active-update.json") }

    public static func load(root: URL) throws -> DesktopAppUpdateTransaction? {
        let url = recordURL(root: root)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        try privateFile(url)
        let data = try Data(contentsOf: url)
        guard data.count <= 32_768 else { throw DesktopAppUpdateInstallError.invalidRequest }
        let value = try JSONDecoder().decode(DesktopAppUpdateTransaction.self, from: data)
        try validate(value, root: root)
        return value
    }

    static func save(_ value: DesktopAppUpdateTransaction, root: URL) throws {
        try validate(value, root: root)
        let url = recordURL(root: root)
        if FileManager.default.fileExists(atPath: url.path) { try privateFile(url) }
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    static func withLock<T>(root: URL, _ body: () throws -> T) throws -> T {
        try privateDirectory(root)
        let lock = root.appendingPathComponent("app-update.lock")
        let fd = Darwin.open(lock.path, O_CREAT | O_RDWR | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw DesktopAppUpdateInstallError.stagingFailed }
        defer { Darwin.close(fd) }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else { throw DesktopAppUpdateInstallError.recoveryRequired }
        defer { flock(fd, LOCK_UN) }
        return try body()
    }

    static func privateDirectory(_ root: URL) throws {
        let fm = FileManager.default
        if !fm.fileExists(atPath: root.path) {
            try fm.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        let attrs = try fm.attributesOfItem(atPath: root.path)
        guard root.isFileURL, root.path != "/", root.standardizedFileURL.path == root.resolvingSymlinksInPath().path,
              attrs[.type] as? FileAttributeType == .typeDirectory,
              (attrs[.ownerAccountID] as? NSNumber)?.uint32Value == geteuid(),
              ((attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0) & 0o077 == 0
        else { throw DesktopAppUpdateInstallError.invalidRequest }
    }

    private static func privateFile(_ url: URL) throws {
        let a = try FileManager.default.attributesOfItem(atPath: url.path)
        guard a[.type] as? FileAttributeType == .typeRegular,
              (a[.ownerAccountID] as? NSNumber)?.uint32Value == geteuid(),
              ((a[.posixPermissions] as? NSNumber)?.intValue ?? 0) & 0o077 == 0
        else { throw DesktopAppUpdateInstallError.invalidRequest }
    }

    private static func validate(_ r: DesktopAppUpdateTransaction, root: URL) throws {
        try privateDirectory(root)
        let parent = r.destination.deletingLastPathComponent()
        guard r.oldPID > 1, DesktopSemanticVersion.components(r.version) != nil, r.build > 0,
              DesktopDistributionConfiguration.validSource(r.source),
              [r.oldDigest, r.newDigest].allSatisfy({ $0.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil }),
              [r.destination, r.staged, r.backup, r.workspace].allSatisfy({
                  $0.isFileURL && $0.path != "/" && $0.standardizedFileURL.path == $0.resolvingSymlinksInPath().path
              }),
              r.destination.pathExtension == "app",
              r.staged.deletingLastPathComponent().path == parent.path,
              r.staged.lastPathComponent.hasPrefix(".HermesGoDesktopUpdate-"),
              r.staged.pathExtension == "app",
              r.backup.path == parent.appendingPathComponent(".HermesGoDesktopPrevious-\(r.id.uuidString).app").path,
              r.workspace.deletingLastPathComponent().path == root.path,
              UUID(uuidString: r.workspace.lastPathComponent) != nil
        else { throw DesktopAppUpdateInstallError.invalidRequest }
    }

    /// Called only after ordinary application initialization, from the actual destination process.
    public static func acknowledgeStartup(root: URL, bundle: Bundle = .main) throws {
        guard let r = try load(root: root), r.state == .awaitingStartup,
              bundle.bundleURL.resolvingSymlinksInPath().path == r.destination.path,
              try DesktopAppUpdateTransaction.digest(r.destination) == r.newDigest else { return }
        try DesktopDistributionConfiguration.validate(bundle: bundle, version: r.version, build: r.build, source: r.source)
        let ack = StartupAcknowledgement(id: r.id, pid: getpid(), createdAt: Date())
        let url = root.appendingPathComponent("startup-\(r.id.uuidString).json")
        try JSONEncoder().encode(ack).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        // A crashed helper may have left an acknowledged swap unfinished. Only after obtaining
        // its lease and re-proving this exact running destination may startup finish cleanup.
        try? withLock(root: root) {
            guard var latest = try load(root: root), latest.id == r.id, latest.state == .awaitingStartup,
                  confirmed(latest, root: root) else { return }
            if FileManager.default.fileExists(atPath: latest.backup.path) {
                guard try DesktopAppUpdateTransaction.digest(latest.backup) == latest.oldDigest else {
                    throw DesktopAppUpdateInstallError.recoveryRequired
                }
                try FileManager.default.removeItem(at: latest.backup)
            }
            try? FileManager.default.removeItem(at: latest.workspace)
            latest.state = .completed; latest.changedAt = Date(); try save(latest, root: root)
        }
    }

    private struct StartupAcknowledgement: Codable {
        let id: UUID
        let pid: Int32
        let createdAt: Date
    }

    static func confirmed(_ r: DesktopAppUpdateTransaction, root: URL) -> Bool {
        let url = root.appendingPathComponent("startup-\(r.id.uuidString).json")
        guard (try? privateFile(url)) != nil,
              let data = try? Data(contentsOf: url), data.count < 4096,
              let ack = try? JSONDecoder().decode(StartupAcknowledgement.self, from: data), ack.id == r.id,
              ack.createdAt >= r.changedAt, ack.pid != r.oldPID,
              let app = NSRunningApplication(processIdentifier: ack.pid), !app.isTerminated,
              app.bundleURL?.resolvingSymlinksInPath().path == r.destination.path,
              app.executableURL?.resolvingSymlinksInPath().path == r.destination.appendingPathComponent("Contents/MacOS/HermesGoDesktop").path,
              let launched = app.launchDate, launched >= r.changedAt,
              (try? DesktopAppUpdateTransaction.digest(r.destination)) == r.newDigest
        else { return false }
        return true
    }

    /// Invoked by the separately packaged helper. Hooks exist only to exercise failure paths with
    /// disposable apps; production always uses the fixed system open and real process evidence.
    static func apply(root: URL, id: UUID, restoring: Bool,
                      waitForExit: (Int32) -> Bool,
                      launch: (URL) -> Bool,
                      waitForStartup: (DesktopAppUpdateTransaction) -> Bool) throws {
        try withLock(root: root) {
            guard var r = try load(root: root), r.id == id,
                  restoring ? r.state == .restoring : r.state == .prepared
            else { throw DesktopAppUpdateInstallError.invalidRequest }
            let fm = FileManager.default
            guard waitForExit(r.oldPID) else {
                r.state = .recoveryRequired; try save(r, root: root); return
            }
            do {
                if restoring {
                    try validateRestoration(r)
                    if fm.fileExists(atPath: r.backup.path) {
                        if fm.fileExists(atPath: r.destination.path) {
                            try fm.moveItem(at: r.destination, to: r.staged)
                        }
                        try fm.moveItem(at: r.backup, to: r.destination)
                    }
                    guard launch(r.destination) else { throw DesktopAppUpdateInstallError.launchFailed }
                    r.state = .restored
                    // "Restored" is a file/launch result, never a claim of business health.
                } else {
                    guard try DesktopAppUpdateTransaction.digest(r.destination) == r.oldDigest,
                          try DesktopAppUpdateTransaction.digest(r.staged) == r.newDigest,
                          !fm.fileExists(atPath: r.backup.path) else {
                        throw DesktopAppUpdateInstallError.verificationFailed
                    }
                    r.state = .replacing; try save(r, root: root)
                    try fm.moveItem(at: r.destination, to: r.backup)
                    try fm.moveItem(at: r.staged, to: r.destination)
                    r.state = .awaitingStartup; r.changedAt = Date(); try save(r, root: root)
                    guard launch(r.destination), waitForStartup(r) else {
                        throw DesktopAppUpdateInstallError.recoveryRequired
                    }
                    // Revalidate immediately before deleting the only known-good backup.
                    guard try DesktopAppUpdateTransaction.digest(r.backup) == r.oldDigest,
                          try DesktopAppUpdateTransaction.digest(r.destination) == r.newDigest else {
                        throw DesktopAppUpdateInstallError.verificationFailed
                    }
                    try fm.removeItem(at: r.backup)
                    try? fm.removeItem(at: r.workspace)
                    r.state = .completed
                }
                r.changedAt = Date(); try save(r, root: root)
            } catch {
                r.state = .recoveryRequired; r.changedAt = Date(); try save(r, root: root)
                throw error
            }
        }
    }

    /// Reconcile each interrupted rename using both known digests. An absent backup is safe
    /// only when the old destination and the retained failed candidate are both already proven.
    static func validateRestoration(_ r: DesktopAppUpdateTransaction) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: r.backup.path) {
            guard try DesktopAppUpdateTransaction.digest(r.backup) == r.oldDigest else {
                throw DesktopAppUpdateInstallError.verificationFailed
            }
            if fm.fileExists(atPath: r.destination.path) {
                guard try DesktopAppUpdateTransaction.digest(r.destination) == r.newDigest,
                      !fm.fileExists(atPath: r.staged.path) else {
                    throw DesktopAppUpdateInstallError.verificationFailed
                }
            } else if fm.fileExists(atPath: r.staged.path) {
                guard try DesktopAppUpdateTransaction.digest(r.staged) == r.newDigest else {
                    throw DesktopAppUpdateInstallError.verificationFailed
                }
            }
        } else {
            guard try DesktopAppUpdateTransaction.digest(r.destination) == r.oldDigest,
                  try DesktopAppUpdateTransaction.digest(r.staged) == r.newDigest else {
                throw DesktopAppUpdateInstallError.verificationFailed
            }
        }
    }

    public static func cancelUnapplied(root: URL, id: UUID) throws {
        try withLock(root: root) {
            guard var r = try load(root: root), r.id == id,
                  [.prepared, .replacing, .recoveryRequired].contains(r.state),
                  !FileManager.default.fileExists(atPath: r.backup.path),
                  try DesktopAppUpdateTransaction.digest(r.destination) == r.oldDigest,
                  try DesktopAppUpdateTransaction.digest(r.staged) == r.newDigest else {
                throw DesktopAppUpdateInstallError.recoveryRequired
            }
            try FileManager.default.removeItem(at: r.staged)
            try? FileManager.default.removeItem(at: r.workspace)
            r.state = .restored; r.changedAt = Date(); try save(r, root: root)
        }
    }

    public static func runHelper(root: URL, id: UUID, restoring: Bool) throws {
        // An explicit helper "restore" invocation also works when the new GUI cannot start.
        // It never terminates a running app: the operator must quit it before external recovery.
        if restoring {
            try withLock(root: root) {
                guard var r = try load(root: root), r.id == id else { throw DesktopAppUpdateInstallError.invalidRequest }
                if r.state != .restoring {
                    guard [.recoveryRequired, .awaitingStartup, .replacing].contains(r.state),
                          !NSWorkspace.shared.runningApplications.contains(where: { $0.bundleURL?.resolvingSymlinksInPath().path == r.destination.path }) else {
                        throw DesktopAppUpdateInstallError.recoveryRequired
                    }
                    try validateRestoration(r)
                    r.oldPID = Int32.max; r.state = .restoring; try save(r, root: root)
                }
            }
        }
        guard let destination = try load(root: root)?.destination else { throw DesktopAppUpdateInstallError.invalidRequest }
        try apply(root: root, id: id, restoring: restoring, waitForExit: { pid in
            for _ in 0..<120 {
                if kill(pid, 0) != 0 && errno == ESRCH {
                    return !NSWorkspace.shared.runningApplications.contains {
                        $0.bundleURL?.resolvingSymlinksInPath().path == destination.path
                    }
                }
                usleep(500_000)
            }
            return false
        }, launch: { app in
            let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/open")
            p.arguments = ["-n", app.path]; p.standardOutput = FileHandle.nullDevice; p.standardError = FileHandle.nullDevice
            guard (try? p.run()) != nil else { return false }; p.waitUntilExit(); return p.terminationStatus == 0
        }, waitForStartup: { r in
            for _ in 0..<180 { if confirmed(r, root: root) { return true }; usleep(500_000) }
            return false
        })
    }
}
