import CryptoKit
import Darwin
import Foundation

/// A local mailbox, not a Gateway command. The operation lease and durable upgrade journal must
/// precede pause. Missing/old Connectors cannot provide an ACK and therefore cannot be upgraded.
public struct DesktopHermesUpgradeMaintenanceMailbox: Sendable {
    public let directory: URL
    public init(directory: URL) { self.directory = directory }
    public struct ConnectorIdentity: Equatable, Sendable {
        public let pid: Int32
        public let instanceID: String
        public init(pid: Int32, instanceID: String) { self.pid = pid; self.instanceID = instanceID }
    }
    public struct Acknowledgement: Decodable, Sendable {
        public let schema: Int
        public let pid: Int32
        public let instanceID: String
        public let runID: String?
        public let sourceIdentity: String?
        public let requestSHA256: String?
        public let paused: Bool
        public let activeOperations: Int
        public let observedAt: Double
    }
    private struct Request: Codable { let schema: Int; let mode: String; let runID: String; let sourceIdentity: String }

    /// Called during explicit preparation, never during read-only readiness. The parent must
    /// already be owner-only; existing unsafe directories are refused rather than chmod-repaired.
    public func prepare() throws {
        try UpgradePrivateFiles.directory(directory.deletingLastPathComponent())
        if !UpgradePrivateFiles.exists(directory) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                    attributes: [.posixPermissions: 0o700])
            try UpgradePrivateFiles.sync(directory.deletingLastPathComponent())
        }
        try UpgradePrivateFiles.directory(directory)
    }

    public func pause(_ journal: DesktopHermesUpgradeJournal) throws {
        guard !journal.phase.terminal else { throw DesktopHermesUpgradeError.recoveryRequired }
        try write(journal, mode: "pause")
    }

    /// An explicit terminal cursor releases admission; deleting a pause never releases it.
    /// Pre-commit cancellation must first restore original services and reach .cancelled.
    public func release(_ journal: DesktopHermesUpgradeJournal) throws {
        guard journal.phase.terminal else { throw DesktopHermesUpgradeError.recoveryRequired }
        try write(journal, mode: "release")
    }

    /// Reopen only after local restoration is independently proved, then run fresh Cloud/business
    /// acceptance. If that acceptance fails the adapter pauses this SAME run again and retains any
    /// newly written history. No automatic restore of old data is allowed.
    public func releaseForVerification(_ journal: DesktopHermesUpgradeJournal,
                                       evidence: DesktopHermesUpgradeCompletionEvidence) throws {
        let expected = journal.services.filter { $0.label == DesktopManagedInstallLayout.hermesLabel && $0.wasLoaded }.count
        guard journal.phase == .verifying, evidence.processExitCode == 0,
              evidence.diskCommit == journal.target.commit, evidence.dependencyInputsCurrent,
              !evidence.sourceCompletionPending, !evidence.fleetRestartPending, evidence.servicesMatchOriginalLoadState,
              evidence.bootCommits.count == expected, evidence.bootCommits.allSatisfy({ $0 == journal.target.commit })
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        try write(journal, mode: "release")
    }

    /// The caller obtains PID from launchd and the instance nonce from this process's ready ACK,
    /// then rechecks kernel process identity before using the result. ACK is admission proof only.
    public func requireDrained(_ journal: DesktopHermesUpgradeJournal, connector: ConnectorIdentity,
                               now: Date = Date()) throws -> Acknowledgement {
        try UpgradePrivateFiles.directory(directory)
        let requestData = try UpgradePrivateFiles.read(directory.appendingPathComponent("request.json"), limit: 4096)
        let ackData = try UpgradePrivateFiles.read(directory.appendingPathComponent("ack.json"), limit: 4096)
        guard requestData == (try encoded(journal, mode: "pause")),
              strictMailboxJSON(ackData, keys: ["schema", "pid", "instanceID", "runID", "sourceIdentity", "requestSHA256", "paused", "activeOperations", "observedAt"]),
              let ack = try? JSONDecoder().decode(Acknowledgement.self, from: ackData),
              ack.schema == 1, ack.pid > 0, ack.pid == connector.pid,
              UUID(uuidString: ack.instanceID) != nil, ack.instanceID == connector.instanceID,
              ack.runID == journal.runID, ack.sourceIdentity == journal.sourceIdentity,
              ack.requestSHA256 == UpgradePrivateFiles.hash(requestData), ack.paused,
              ack.activeOperations == 0, ack.observedAt.isFinite,
              (0...5).contains(now.timeIntervalSince1970 - ack.observedAt / 1000)
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        return ack
    }

    private func encoded(_ journal: DesktopHermesUpgradeJournal, mode: String) throws -> Data {
        guard UUID(uuidString: journal.runID) != nil, DesktopHermesUpgradeJournal.digest(journal.sourceIdentity) else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(Request(schema: 1, mode: mode, runID: journal.runID, sourceIdentity: journal.sourceIdentity))
    }
    private func write(_ journal: DesktopHermesUpgradeJournal, mode: String) throws {
        try UpgradePrivateFiles.directory(directory)
        let destination = directory.appendingPathComponent("request.json")
        if UpgradePrivateFiles.exists(destination) {
            let oldData = try UpgradePrivateFiles.read(destination, limit: 4096)
            guard strictMailboxJSON(oldData, keys: ["schema", "mode", "runID", "sourceIdentity"]),
                  let old = try? JSONDecoder().decode(Request.self, from: oldData), old.schema == 1,
                  ["pause", "release"].contains(old.mode), UUID(uuidString: old.runID) != nil,
                  DesktopHermesUpgradeJournal.digest(old.sourceIdentity),
                  (old.runID == journal.runID && old.sourceIdentity == journal.sourceIdentity) || (old.mode == "release" && mode == "pause")
            else { throw DesktopHermesUpgradeError.recoveryRequired }
            if oldData == (try encoded(journal, mode: mode)) { return }
        } else if mode == "release" { throw DesktopHermesUpgradeError.recoveryRequired }
        try UpgradePrivateFiles.write(encoded(journal, mode: mode), to: destination)
    }
    private func strictMailboxJSON(_ data: Data, keys: Set<String>) -> Bool {
        guard let text = String(data: data, encoding: .utf8), !text.contains("\\"),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], Set(object.keys) == keys,
              let expression = try? NSRegularExpression(pattern: "\"(?:" + keys.sorted().joined(separator: "|") + ")\"\\s*:") else { return false }
        return expression.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text)) == keys.count
    }
}

/// Explicit evidence for ALL served profiles and independent writers, separate from a drained
/// Connector. An empty/partial inventory is unknown. Acquisition must use a pinned upstream
/// adapter plus kernel process identities; a UI version/health check cannot construct this proof.
public struct DesktopHermesUpgradeIdleEvidence: Sendable {
    public let runID: String
    public let sourceIdentity: String
    public let observedAt: Date
    public let profiles: [String]
    public let checkedProfiles: [String]
    public let activeTurns: Int
    public let activeCron: Int
    public let backgroundWork: Int
    public let unknownWriters: Int
    public let processInventoryComplete: Bool
    public let writerAdmissionClosed: Bool
    public init(runID: String, sourceIdentity: String, observedAt: Date, profiles: [String], checkedProfiles: [String],
                activeTurns: Int, activeCron: Int, backgroundWork: Int, unknownWriters: Int,
                processInventoryComplete: Bool, writerAdmissionClosed: Bool) {
        self.runID = runID; self.sourceIdentity = sourceIdentity; self.observedAt = observedAt
        self.profiles = profiles; self.checkedProfiles = checkedProfiles; self.activeTurns = activeTurns
        self.activeCron = activeCron; self.backgroundWork = backgroundWork; self.unknownWriters = unknownWriters
        self.processInventoryComplete = processInventoryComplete; self.writerAdmissionClosed = writerAdmissionClosed
    }
    public func requireIdle(for journal: DesktopHermesUpgradeJournal, now: Date = Date()) throws {
        guard runID == journal.runID, sourceIdentity == journal.sourceIdentity,
              observedAt.timeIntervalSince1970.isFinite, (0...5).contains(now.timeIntervalSince(observedAt)),
              !profiles.isEmpty, profiles.count <= 128, Set(profiles).count == profiles.count,
              profiles.allSatisfy({ $0.range(of: "^[a-z0-9][a-z0-9_-]{0,63}$", options: .regularExpression) != nil }),
              Set(checkedProfiles) == Set(profiles), checkedProfiles.count == profiles.count,
              activeTurns == 0, activeCron == 0, backgroundWork == 0, unknownWriters == 0,
              processInventoryComplete, writerAdmissionClosed
        else { throw DesktopHermesUpgradeError.preflightBlocked }
    }
}

/// Exact LaunchAgent bytes outside HERMES_HOME, retained for every run including cancellation.
/// Capture follows independent ownership/loaded-arguments verification by the runtime adapter.
/// This is a service recovery point; it does not claim external interpreter closure backup.
public struct DesktopHermesUpgradeServiceSnapshotStore: Sendable {
    public let root: URL
    public init(root: URL) { self.root = root }
    struct Image: Codable, Equatable {
        let schema: Int; let runID: String; let sourceIdentity: String
        let services: [DesktopHermesUpgradeService]; let configurations: [String: Data]
    }
    private var labels: Set<String> { [DesktopManagedInstallLayout.hermesLabel, DesktopManagedInstallLayout.connectorLabel] }

    public func capture(_ journal: DesktopHermesUpgradeJournal, configurations: [String: Data]) throws {
        let image = Image(schema: 1, runID: journal.runID, sourceIdentity: journal.sourceIdentity,
                          services: journal.services, configurations: configurations)
        try validate(image, journal: journal)
        let destination = try url(journal.runID)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(image)
        guard data.count <= 1024 * 1024 else { throw DesktopHermesUpgradeError.recoveryRequired }
        if UpgradePrivateFiles.exists(destination) {
            guard try load(journal) == image else { throw DesktopHermesUpgradeError.recoveryRequired }
            return
        }
        try UpgradePrivateFiles.write(data, to: destination)
    }
    public func configurations(_ journal: DesktopHermesUpgradeJournal) throws -> [String: Data] {
        try load(journal).configurations
    }
    private func url(_ runID: String) throws -> URL {
        guard UUID(uuidString: runID) != nil else { throw DesktopHermesUpgradeError.recoveryRequired }
        try UpgradePrivateFiles.directory(root)
        return root.appendingPathComponent(runID.lowercased() + ".json")
    }
    private func load(_ journal: DesktopHermesUpgradeJournal) throws -> Image {
        let data = try UpgradePrivateFiles.read(url(journal.runID), limit: 1024 * 1024)
        guard let image = try? JSONDecoder().decode(Image.self, from: data) else { throw DesktopHermesUpgradeError.recoveryRequired }
        try validate(image, journal: journal)
        return image
    }
    private func validate(_ image: Image, journal: DesktopHermesUpgradeJournal) throws {
        guard image.schema == 1, image.runID == journal.runID, image.sourceIdentity == journal.sourceIdentity,
              root.path != journal.hermesHome, !root.path.hasPrefix(journal.hermesHome + "/"),
              image.services == journal.services, Set(image.services.map(\.label)) == labels,
              image.services.count == labels.count, Set(image.configurations.keys) == labels else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        for service in image.services {
            guard let data = image.configurations[service.label], data.count <= 256 * 1024,
                  UpgradePrivateFiles.hash(data) == service.configurationDigest,
                  let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any],
                  plist["Label"] as? String == service.label,
                  let arguments = plist["ProgramArguments"] as? [String], !arguments.isEmpty,
                  arguments.allSatisfy({ !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) })
            else { throw DesktopHermesUpgradeError.recoveryRequired }
        }
    }
}

/// A service can be unloaded, loaded without a PID, or unobservable. Unknown is never unloaded.
public enum DesktopHermesUpgradeServiceObservation: Equatable, Sendable {
    case unloaded
    case loaded(arguments: [String], pid: Int32?, startedAt: Date?)
    case unknown
}
public protocol DesktopHermesUpgradeServicesOperating: Sendable {
    func observe(label: String) throws -> DesktopHermesUpgradeServiceObservation
    func configuration(label: String) throws -> Data
    func stop(label: String) async throws
    func start(label: String, configuration: Data) async throws
    func requireHermesReady(commit: String) async throws
}

/// Restores the original load set, Hermes before Connector, and recognizes work done before an app
/// crash. It never stops a newly loaded/unknown job or overwrites a changed service definition.
public struct DesktopHermesUpgradeServiceRestorer<Driver: DesktopHermesUpgradeServicesOperating>: Sendable {
    private let snapshots: DesktopHermesUpgradeServiceSnapshotStore
    private let driver: Driver
    public init(snapshots: DesktopHermesUpgradeServiceSnapshotStore, driver: Driver) {
        self.snapshots = snapshots; self.driver = driver
    }
    public func quiesce(_ journal: DesktopHermesUpgradeJournal,
                        idleEvidence: @Sendable () async throws -> DesktopHermesUpgradeIdleEvidence,
                        requireRetiredWriters: @Sendable () async throws -> Void) async throws {
        guard journal.phase == .quiescing else { throw DesktopHermesUpgradeError.recoveryRequired }
        do {
            let configurations = try snapshots.configurations(journal)
            let ordered = [DesktopManagedInstallLayout.connectorLabel, DesktopManagedInstallLayout.hermesLabel]
            for label in ordered {
                guard try driver.configuration(label: label) == configurations[label] else { throw DesktopHermesUpgradeError.recoveryRequired }
                try requireOwnedState(journal, label: label, configuration: configurations[label]!)
            }
            for label in ordered {
                // Resume can encounter the first job already stopped. It must re-inventory instead
                // of reusing the earlier idle result or stopping any job that appeared meanwhile.
                try await idleEvidence().requireIdle(for: journal)
                if try driver.observe(label: label) != .unloaded {
                    try requireOwnedState(journal, label: label, configuration: configurations[label]!)
                    guard let original = journal.services.first(where: { $0.label == label }),
                          case .loaded(_, let pid, let birth) = try driver.observe(label: label),
                          original.processID != nil, original.processStartedAt != nil,
                          pid == original.processID, birth == original.processStartedAt else {
                        throw DesktopHermesUpgradeError.recoveryRequired
                    }
                    try await driver.stop(label: label)
                }
                guard try driver.observe(label: label) == .unloaded else { throw DesktopHermesUpgradeError.recoveryRequired }
            }
            try await requireRetiredWriters()
        } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    public func restore(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws {
        guard originalInstallation ? journal.phase == .cancelling : journal.phase == .restoringServices else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        do {
            let configurations = try snapshots.configurations(journal)
            let ordered = [DesktopManagedInstallLayout.hermesLabel, DesktopManagedInstallLayout.connectorLabel]
            // Validate every file and already-loaded job BEFORE the first restart.
            for label in ordered {
                guard try driver.configuration(label: label) == configurations[label] else { throw DesktopHermesUpgradeError.recoveryRequired }
                try requireOwnedState(journal, label: label, configuration: configurations[label]!)
            }
            for label in ordered {
                let service = journal.services.first(where: { $0.label == label })!
                if service.wasLoaded {
                    if try driver.observe(label: label) == .unloaded {
                        try await driver.start(label: label, configuration: configurations[label]!)
                    }
                    try requireOwnedState(journal, label: label, configuration: configurations[label]!, requireLoaded: true)
                    if label == DesktopManagedInstallLayout.hermesLabel {
                        try await driver.requireHermesReady(commit: originalInstallation ? journal.sourceCommit : journal.target.commit)
                    }
                } else {
                    // A job can appear after the initial inventory (for example during boot proof).
                    // Detect it without stopping or adopting it as the owner's original load set.
                    try requireOwnedState(journal, label: label, configuration: configurations[label]!)
                }
            }
            for label in ordered {
                try requireOwnedState(journal, label: label, configuration: configurations[label]!,
                                      requireLoaded: journal.services.first(where: { $0.label == label })!.wasLoaded)
            }
        } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func requireOwnedState(_ journal: DesktopHermesUpgradeJournal, label: String, configuration: Data,
                                   requireLoaded: Bool = false) throws {
        guard let plist = try PropertyListSerialization.propertyList(from: configuration, options: [], format: nil) as? [String: Any],
              let expected = plist["ProgramArguments"] as? [String],
              let service = journal.services.first(where: { $0.label == label }) else { throw DesktopHermesUpgradeError.recoveryRequired }
        switch try driver.observe(label: label) {
        case .unloaded:
            guard !requireLoaded else { throw DesktopHermesUpgradeError.recoveryRequired }
        case .loaded(let arguments, let pid, let startedAt):
            guard service.wasLoaded, arguments == expected, pid.map({ $0 > 0 }) == true,
                  startedAt?.timeIntervalSince1970.isFinite == true else { throw DesktopHermesUpgradeError.recoveryRequired }
        case .unknown: throw DesktopHermesUpgradeError.recoveryRequired
        }
    }
}

/// Concrete exact-label launchd driver. No disable/enable, kickstart, arbitrary PID signals or
/// edits to plists. Immutable configurations come from independently verified committed GO
/// ownership. The readiness callback must prove a NEW process's boot commit, not disk HEAD.
public struct DesktopHermesUpgradeLaunchdServices<Runner: OutputCommandRunning & Sendable>: DesktopHermesUpgradeServicesOperating {
    private let runner: Runner
    private let layout: DesktopManagedInstallLayout
    private let approved: [String: Data]
    private let originals: [DesktopHermesUpgradeService]
    private let userID: UInt32
    private let ready: @Sendable (String) async throws -> Void
    private let startDate: @Sendable (Int32) -> Date?
    public init(runner: Runner, layout: DesktopManagedInstallLayout, approvedConfigurations: [String: Data],
                originalServices: [DesktopHermesUpgradeService], userID: UInt32 = getuid(),
                requireBootCommit: @escaping @Sendable (String) async throws -> Void) {
        self.runner = runner; self.layout = layout; approved = approvedConfigurations; self.userID = userID
        originals = originalServices
        ready = requireBootCommit
        startDate = { DesktopLaunchdHermesServiceProcessInspector<Runner>.startDate(pid: $0) }
    }
    // Test seam does not issue any real launchctl command or signal.
    init(runner: Runner, layout: DesktopManagedInstallLayout, approved: [String: Data], originals: [DesktopHermesUpgradeService],
         startDate: @escaping @Sendable (Int32) -> Date?, ready: @escaping @Sendable (String) async throws -> Void) {
        self.runner = runner; self.layout = layout; self.approved = approved; userID = getuid()
        self.originals = originals
        self.startDate = startDate; self.ready = ready
    }
    private func path(_ label: String) throws -> URL {
        switch label {
        case DesktopManagedInstallLayout.hermesLabel: layout.hermesLaunchAgent
        case DesktopManagedInstallLayout.connectorLabel: layout.connectorLaunchAgent
        default: throw DesktopHermesUpgradeError.recoveryRequired
        }
    }
    public func configuration(label: String) throws -> Data {
        let url = try path(label)
        var info = stat()
        guard url.resolvingSymlinksInPath().path == url.path,
              lstat(url.deletingLastPathComponent().path, &info) == 0, info.st_uid == getuid(),
              info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o022 == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        guard fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFREG,
              info.st_mode & 0o077 == 0, info.st_nlink == 1, info.st_size > 0, info.st_size <= 256 * 1024,
              let data = try FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: 256 * 1024 + 1),
              data == approved[label] else { throw DesktopHermesUpgradeError.recoveryRequired }
        return data
    }
    public func observe(label: String) throws -> DesktopHermesUpgradeServiceObservation {
        _ = try path(label)
        let result = command(["print", "gui/\(userID)/\(label)"])
        guard !result.outputLimitExceeded else { return .unknown }
        if result.status == 113 { return .unloaded }
        guard result.status == 0, let text = String(data: result.stdout, encoding: .utf8),
              let arguments = DesktopLaunchdHermesServiceProcessInspector<Runner>.arguments(fromLaunchctlPrint: text) else { return .unknown }
        let pid = DesktopLaunchdHermesServiceProcessInspector<Runner>.pid(fromLaunchctlPrint: text)
        let birth = pid.flatMap(startDate)
        guard pid == nil || (pid! > 0 && birth?.timeIntervalSince1970.isFinite == true) else { return .unknown }
        return .loaded(arguments: arguments, pid: pid, startedAt: birth)
    }
    public func stop(label: String) async throws {
        let data = try configuration(label: label)
        guard case .loaded(let arguments, let pid, let birth) = try observe(label: label),
              let original = originals.first(where: { $0.label == label }), original.wasLoaded,
              arguments == (try plistArguments(data)), pid != nil, birth != nil,
              pid == original.processID, birth == original.processStartedAt,
              UpgradePrivateFiles.hash(data) == original.configurationDigest else { throw DesktopHermesUpgradeError.recoveryRequired }
        try noLegacyConnector()
        guard command(["bootout", "gui/\(userID)/\(label)"]).status == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        try await wait(label: label, loaded: false)
    }
    public func start(label: String, configuration data: Data) async throws {
        guard let original = originals.first(where: { $0.label == label }), original.wasLoaded,
              UpgradePrivateFiles.hash(data) == original.configurationDigest,
              try configuration(label: label) == data, try observe(label: label) == .unloaded else { throw DesktopHermesUpgradeError.recoveryRequired }
        try noLegacyConnector()
        guard command(["bootstrap", "gui/\(userID)", try path(label).path]).status == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        try await wait(label: label, loaded: true)
    }
    public func requireHermesReady(commit: String) async throws { try await ready(commit) }
    private func noLegacyConnector() throws {
        guard userID == getuid() else { throw DesktopHermesUpgradeError.recoveryRequired }
        let result = command(["print", "gui/\(userID)/com.hermesremote.connector"])
        guard result.status == 113, !result.outputLimitExceeded else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func wait(label: String, loaded: Bool) async throws {
        for attempt in 0..<50 {
            let observation = try observe(label: label)
            if !loaded, observation == .unloaded { return }
            if loaded, case .loaded(let arguments, let pid, let birth) = observation,
               arguments == (try plistArguments(configuration(label: label))), pid != nil, birth != nil { return }
            if case .unknown = observation { throw DesktopHermesUpgradeError.recoveryRequired }
            if attempt < 49 { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        throw DesktopHermesUpgradeError.recoveryRequired
    }
    private func command(_ args: [String]) -> DesktopCommandOutput {
        runner.run(executable: URL(fileURLWithPath: "/bin/launchctl"), arguments: args, maximumOutputBytes: 256 * 1024)
    }
    private func plistArguments(_ data: Data) throws -> [String] {
        guard let plist = try PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any],
              let args = plist["ProgramArguments"] as? [String], !args.isEmpty else { throw DesktopHermesUpgradeError.recoveryRequired }
        return args
    }
}

/// Bounded owner-only I/O shared by the maintenance mailbox and external service images.
enum UpgradePrivateFiles {
    static func exists(_ url: URL) -> Bool { var info = stat(); return lstat(url.path, &info) == 0 || errno != ENOENT }
    static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    static func directory(_ url: URL) throws {
        var info = stat()
        guard url.path != "/", url.standardizedFileURL.path == url.path, url.resolvingSymlinksInPath().path == url.path,
              lstat(url.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    static func read(_ url: URL, limit: Int) throws -> Data {
        try directory(url.deletingLastPathComponent())
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFREG,
              info.st_nlink == 1, info.st_mode & 0o077 == 0, info.st_size > 0, info.st_size <= limit,
              let data = try FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: limit + 1), data.count <= limit
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        return data
    }
    static func write(_ data: Data, to url: URL) throws {
        let parent = url.deletingLastPathComponent()
        try directory(parent)
        if exists(url) { _ = try read(url, limit: 1024 * 1024) }
        let temporary = parent.appendingPathComponent(".\(UUID().uuidString).tmp")
        let fd = Darwin.open(temporary.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd); Darwin.unlink(temporary.path) }
        try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
        guard fsync(fd) == 0, Darwin.rename(temporary.path, url.path) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        try sync(parent)
    }
    static func sync(_ url: URL) throws {
        let fd = Darwin.open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
}
