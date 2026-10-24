import Darwin
import Foundation

/// Observations, never a fence or proof of task inactivity. No raw argv, environment, file bodies or
/// interpreter paths survive into this report (including diagnostics/serialization).
public struct DesktopHermesUpgradeInspection: Equatable, Codable, Sendable {
    public struct ProcessIdentity: Equatable, Codable, Sendable {
        public let pid: Int32
        public let parentPID: Int32
        public let startedAt: Date
    }
    public struct Candidate: Equatable, Codable, Sendable {
        public enum Association: String, Codable, Sendable { case checkout, unscopedHermes, descendant }
        public let identity: ProcessIdentity
        public let association: Association
        /// Only an explicit valid selector; nil never implies the default profile.
        public let profile: String?
    }
    public enum Interpreter: String, Codable, Sendable {
        case insideHome, outsideHome, unknown, managedGenerationNotInspected
    }
    public let sourceIdentity: String
    public let checkedAt: Date
    public let candidates: [Candidate]
    public let unreadableOwnedProcesses: Int
    public let processSampleStable: Bool
    /// Potential directories, including deleted/markerless shells. Not the official live fleet.
    public let profileDirectories: [String]
    public let profileDirectoryScanComplete: Bool
    public let interpreter: Interpreter
    public var writerAdmissionProven: Bool { false }
    public var taskInactivityProven: Bool { false }
    public var dependencyRecoveryProven: Bool { false }

    /// Adds a negative check to a separately fenced/verified transaction. Absence is never proof.
    func requireNoObservedWriters(for journal: DesktopHermesUpgradeJournal, now: Date = Date()) throws {
        try requireFresh(journal, now: now)
        guard candidates.isEmpty else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    func requireOnlyOriginalServices(for journal: DesktopHermesUpgradeJournal, now: Date = Date()) throws {
        try requireFresh(journal, now: now)
        guard candidates.allSatisfy({ candidate in
            candidate.association == .checkout && journal.services.contains {
                $0.wasLoaded && $0.processID == candidate.identity.pid && $0.processStartedAt == candidate.identity.startedAt
            }
        }) else { throw DesktopHermesUpgradeError.preflightBlocked }
    }
    private func requireFresh(_ journal: DesktopHermesUpgradeJournal, now: Date) throws {
        guard sourceIdentity == journal.sourceIdentity, checkedAt.timeIntervalSince1970.isFinite,
              (0...5).contains(now.timeIntervalSince(checkedAt)), processSampleStable,
              unreadableOwnedProcesses == 0, profileDirectoryScanComplete else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
    }
}

public protocol DesktopHermesUpgradeInspecting: Sendable {
    func inspect(installation: DesktopLocalHermesInstallation) -> DesktopHermesUpgradeInspection
    func inspect(journal: DesktopHermesUpgradeJournal) -> DesktopHermesUpgradeInspection
}

/// Native sysctl reads only. Never invokes Hermes, ps, a shell, a status helper or a signal.
/// Kernel argv buffers also contain environment bytes; decoding stops after argc, before env.
public struct DesktopHermesUpgradeInspector: DesktopHermesUpgradeInspecting {
    private let processes: any UpgradeProcessReading
    public init() { processes = UpgradeKernelProcesses() }
    init(processes: any UpgradeProcessReading) { self.processes = processes }

    public func inspect(installation: DesktopLocalHermesInstallation) -> DesktopHermesUpgradeInspection {
        inspect(home: installation.hermesHome, checkout: installation.checkoutRoot,
                sourceIdentity: installation.runtimeIdentity, interpreter: Self.interpreter(installation))
    }
    public func inspect(journal: DesktopHermesUpgradeJournal) -> DesktopHermesUpgradeInspection {
        inspect(home: URL(fileURLWithPath: journal.hermesHome), checkout: URL(fileURLWithPath: journal.checkout),
                sourceIdentity: journal.sourceIdentity, interpreter: .unknown)
    }
    private func inspect(home: URL, checkout: URL, sourceIdentity: String,
                         interpreter: DesktopHermesUpgradeInspection.Interpreter) -> DesktopHermesUpgradeInspection {
        let began = Date()
        let before = processes.identities()
        var candidates = [DesktopHermesUpgradeInspection.Candidate]()
        var unreadable = 0
        if let before {
            for identity in before {
                guard let command = processes.command(pid: identity.pid) else { unreadable += 1; continue }
                if let candidate = Self.candidate(identity, command: command, checkout: checkout) {
                    candidates.append(candidate)
                }
            }
            // A task outside the checkout can still write through its Hermes parent. Retain all
            // observed descendants, including ones whose argv could not be read.
            var selected = Set(candidates.map { $0.identity.pid })
            let children = Dictionary(grouping: before, by: \.parentPID)
            var queue = Array(selected), cursor = 0
            while cursor < queue.count {
                let parent = queue[cursor]; cursor += 1
                for child in children[parent] ?? [] where !selected.contains(child.pid) {
                    candidates.append(.init(identity: child, association: .descendant, profile: nil))
                    selected.insert(child.pid)
                    queue.append(child.pid)
                }
            }
        }
        let after = processes.identities()
        let profiles = UpgradeProfileDirectories.inspect(home: home)
        return .init(sourceIdentity: sourceIdentity, checkedAt: began,
            candidates: candidates.sorted { $0.identity.pid < $1.identity.pid }, unreadableOwnedProcesses: unreadable,
            processSampleStable: before != nil && before == after,
            profileDirectories: profiles.names, profileDirectoryScanComplete: profiles.complete,
            interpreter: interpreter)
    }

    static func candidate(_ identity: DesktopHermesUpgradeInspection.ProcessIdentity, command: UpgradeProcessCommand,
                          checkout: URL) -> DesktopHermesUpgradeInspection.Candidate? {
        func inCheckout(_ value: String) -> Bool {
            guard value.hasPrefix("/"), !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { return false }
            let path = URL(fileURLWithPath: value).standardizedFileURL.path
            return path == checkout.path || path.hasPrefix(checkout.path + "/")
        }
        let scoped = ([command.executable] + command.arguments).contains(where: inCheckout)
        let names = [command.executable, command.arguments.first ?? ""].map { URL(fileURLWithPath: $0).lastPathComponent }
        let module = zip(command.arguments, command.arguments.dropFirst()).contains {
            $0.0 == "-m" && ($0.1 == "hermes_cli" || $0.1.hasPrefix("hermes_cli.") || $0.1 == "gateway.run" || $0.1 == "gateway")
        }
        guard scoped || names.contains("hermes") || module else { return nil }
        var selectors = [String]()
        for (index, argument) in command.arguments.enumerated() {
            if ["-p", "--profile"].contains(argument) {
                selectors.append(index + 1 < command.arguments.count ? command.arguments[index + 1] : "")
            } else if argument.hasPrefix("--profile=") { selectors.append(String(argument.dropFirst(10))) }
        }
        let profile = selectors.count == 1 && validProfile(selectors[0]) ? selectors[0] : nil
        return .init(identity: identity, association: scoped ? .checkout : .unscopedHermes, profile: profile)
    }
    static func validProfile(_ name: String) -> Bool {
        !name.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
            && name.range(of: "^[a-z0-9][a-z0-9_-]{0,63}$", options: .regularExpression) != nil
    }
    private static func interpreter(_ installation: DesktopLocalHermesInstallation) -> DesktopHermesUpgradeInspection.Interpreter {
        guard !installation.usesPackageManager else { return .managedGenerationNotInspected }
        let path = installation.executable.deletingLastPathComponent().appendingPathComponent("python")
        let target = path.resolvingSymlinksInPath()
        var info = stat()
        guard stat(target.path, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_mode & 0o022 == 0, [getuid(), 0].contains(info.st_uid), access(target.path, X_OK) == 0 else { return .unknown }
        return target.path.hasPrefix(installation.hermesHome.path + "/") ? .insideHome : .outsideHome
    }
}

// Transient internal data only. Never serialize/log a command or expose it as diagnostics.
struct UpgradeProcessCommand: Sendable { let executable: String; let arguments: [String] }
protocol UpgradeProcessReading: Sendable {
    func identities() -> [DesktopHermesUpgradeInspection.ProcessIdentity]?
    func command(pid: Int32) -> UpgradeProcessCommand?
}
struct UpgradeKernelProcesses: UpgradeProcessReading {
    func identities() -> [DesktopHermesUpgradeInspection.ProcessIdentity]? {
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_ALL, 0]
        var length = 0
        guard sysctl(&mib, 4, nil, &length, nil, 0) == 0,
              length > 0, length <= MemoryLayout<kinfo_proc>.stride * 16_384 else { return nil }
        var rows = [kinfo_proc](repeating: kinfo_proc(), count: min(16_384, length / MemoryLayout<kinfo_proc>.stride + 64))
        length = rows.count * MemoryLayout<kinfo_proc>.stride
        let status = rows.withUnsafeMutableBytes { sysctl(&mib, 4, $0.baseAddress, &length, nil, 0) }
        guard status == 0, length % MemoryLayout<kinfo_proc>.stride == 0, length <= rows.count * MemoryLayout<kinfo_proc>.stride else { return nil }
        var identities = [DesktopHermesUpgradeInspection.ProcessIdentity]()
        for row in rows.prefix(length / MemoryLayout<kinfo_proc>.stride) where
            (row.kp_eproc.e_ucred.cr_uid == getuid() || row.kp_eproc.e_pcred.p_ruid == getuid()) && row.kp_proc.p_pid > 0 {
            let birth = row.kp_proc.p_starttime
            guard birth.tv_sec > 0, (0..<1_000_000).contains(birth.tv_usec) else { return nil }
            identities.append(.init(pid: row.kp_proc.p_pid, parentPID: row.kp_eproc.e_ppid,
                startedAt: Date(timeIntervalSince1970: Double(birth.tv_sec) + Double(birth.tv_usec) / 1_000_000)))
        }
        guard !identities.isEmpty, Set(identities.map(\.pid)).count == identities.count else { return nil }
        return identities.sorted { $0.pid < $1.pid }
    }
    func command(pid: Int32) -> UpgradeProcessCommand? {
        var mib: [Int32] = [CTL_KERN, KERN_ARGMAX]
        var maximum: Int32 = 0, size = MemoryLayout<Int32>.size
        guard sysctl(&mib, 2, &maximum, &size, nil, 0) == 0, (4...1_048_576).contains(maximum) else { return nil }
        var data = Data(count: Int(maximum)); var length = data.count
        mib = [CTL_KERN, KERN_PROCARGS2, pid]
        let result = data.withUnsafeMutableBytes { sysctl(&mib, 3, $0.baseAddress, &length, nil, 0) }
        guard result == 0, length <= data.count else { return nil }
        data.removeSubrange(length..<data.count)
        return Self.decode(data)
    }
    static func decode(_ data: Data) -> UpgradeProcessCommand? {
        guard (5...1_048_576).contains(data.count) else { return nil }
        var argc: Int32 = 0
        _ = withUnsafeMutableBytes(of: &argc) { data.copyBytes(to: $0, count: 4) }
        guard (1...4096).contains(argc) else { return nil }
        var position = 4
        func string() -> String? {
            guard position < data.count, let end = data[position...].firstIndex(of: 0),
                  let value = String(data: data[position..<end], encoding: .utf8) else { return nil }
            position = end + 1
            return value
        }
        guard let executable = string(), !executable.isEmpty else { return nil }
        while position < data.count && data[position] == 0 { position += 1 }
        var arguments = [String]()
        for _ in 0..<argc { guard let value = string() else { return nil }; arguments.append(value) }
        return .init(executable: executable, arguments: arguments)
    }
}

enum UpgradeProfileDirectories {
    static func inspect(home: URL) -> (names: [String], complete: Bool) {
        guard ownedDirectory(home) else { return ([], false) }
        let root = home.appendingPathComponent("profiles")
        var info = stat()
        if lstat(root.path, &info) != 0 { return errno == ENOENT ? (["default"], true) : ([], false) }
        guard ownedDirectory(root), let directory = opendir(root.path) else { return ([], false) }
        defer { closedir(directory) }
        let fd = dirfd(directory)
        var before = stat(); guard fstat(fd, &before) == 0 else { return ([], false) }
        var names = ["default"], complete = true, entries = 0
        errno = 0
        while let entry = readdir(directory) {
            let name = withUnsafePointer(to: entry.pointee.d_name) {
                $0.withMemoryRebound(to: CChar.self, capacity: 256) { String(cString: $0) }
            }
            if name == "." || name == ".." { continue }
            entries += 1; guard entries <= 128 else { return (names.sorted(), false) }
            guard name != "default", DesktopHermesUpgradeInspector.validProfile(name),
                  fstatat(fd, name, &info, AT_SYMLINK_NOFOLLOW) == 0,
                  info.st_mode & S_IFMT == S_IFDIR, info.st_uid == getuid(), info.st_mode & 0o022 == 0 else { complete = false; continue }
            names.append(name)
            errno = 0
        }
        if errno != 0 { complete = false }
        var after = stat(), current = stat()
        guard fstat(fd, &after) == 0, lstat(root.path, &current) == 0,
              before.st_ino == after.st_ino, before.st_dev == after.st_dev,
              after.st_ino == current.st_ino, after.st_dev == current.st_dev,
              before.st_mtimespec.tv_sec == after.st_mtimespec.tv_sec,
              before.st_mtimespec.tv_nsec == after.st_mtimespec.tv_nsec, ownedDirectory(home), ownedDirectory(root)
        else { complete = false; return (names.sorted(), complete) }
        return (names.sorted(), complete)
    }
    private static func ownedDirectory(_ url: URL) -> Bool {
        var info = stat()
        return url.path != "/" && url.standardizedFileURL.path == url.path && url.resolvingSymlinksInPath().path == url.path
            && lstat(url.path, &info) == 0 && info.st_mode & S_IFMT == S_IFDIR && info.st_uid == getuid() && info.st_mode & 0o022 == 0
    }
}
