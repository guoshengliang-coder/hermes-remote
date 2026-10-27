import Darwin
import Foundation

public struct DesktopConnectorRuntimeIdentity: Equatable, Sendable {
    public let component: HermesCompatibilityComponent
    public let processID: Int32
    public let startedAt: Date
    public let contentSHA256: String
    fileprivate init(observation: DesktopBundledConnectorObservation, process: ConnectorKernelIdentity) {
        component = observation.component; processID = process.pid
        startedAt = process.startedAt; contentSHA256 = observation.contentSHA256
    }
}

/// Read-only continuity check for the schema-1 bundled Node + Connector layout. It ties a fresh
/// signed-archive file observation to the exact managed job and its kernel-observed process.
/// This is not protection against a privileged debugger or evidence of client/Hermes compatibility.
public struct DesktopConnectorRuntimeIdentityInspector {
    private let runner: any OutputCommandRunning
    private let readProcess: (Int32) -> ConnectorKernelIdentity?
    public init(runner: any OutputCommandRunning = SystemOutputCommandRunner()) {
        self.runner = runner; readProcess = ConnectorKernelIdentity.read
    }
    init(runner: any OutputCommandRunning, readProcess: @escaping (Int32) -> ConnectorKernelIdentity?) {
        self.runner = runner; self.readProcess = readProcess
    }

    public func inspect(observation: DesktopBundledConnectorObservation,
                        layout: DesktopManagedInstallLayout) throws -> DesktopConnectorRuntimeIdentity {
        var stage = "inspect_job"
        do {
            let root = try layout.release(observation.releaseVersion).appendingPathComponent("connector", isDirectory: true)
            for relative in ["runtime/node", "app/connector/dist/index.js"] {
                var info = stat()
                guard lstat(root.appendingPathComponent(relative).path, &info) == 0,
                      info.st_mode & S_IFMT == S_IFREG,
                      relative != "runtime/node" || info.st_mode & 0o111 != 0 else { throw Self.issue }
            }
            try requireSelection(layout, version: observation.releaseVersion)
            let pid = try jobPID(layout)
            stage = "inspect_process"
            guard let before = readProcess(pid), before.pid == pid, before.userID == getuid(), !before.injectedEnvironment,
                  before.executable == root.appendingPathComponent("runtime/node").path,
                  before.arguments == [before.executable, root.appendingPathComponent("app/connector/dist/index.js").path]
            else { throw Self.issue }
            stage = "verify_content"
            let content = try DesktopManagedComponentContentHasher().identify(directory: root)
            guard content.sha256 == observation.contentSHA256 else { throw Self.issue }
            // ctime cannot be restored by setting mtime. Content or directory replacement after
            // launch is unknown, even if a copied package retains old modification timestamps.
            stage = "verify_continuity"
            try Self.requireUnchangedTree(root, since: before.startedAt)
            stage = "recheck_process"
            guard readProcess(pid) == before, try jobPID(layout) == pid else { throw Self.issue }
            try requireSelection(layout, version: observation.releaseVersion)
            return DesktopConnectorRuntimeIdentity(observation: observation, process: before)
        } catch {
            throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid,
                               technicalCause: "connector_runtime_identity=\(stage)")
        }
    }

    private func jobPID(_ layout: DesktopManagedInstallLayout) throws -> Int32 {
        let target = "gui/\(getuid())/\(DesktopManagedInstallLayout.connectorLabel)"
        let output = runner.run(executable: URL(fileURLWithPath: "/bin/launchctl"),
                                arguments: ["print", target], maximumOutputBytes: 256 * 1024)
        guard output.status == 0, !output.outputLimitExceeded,
              let text = String(data: output.stdout, encoding: .utf8) else { throw Self.issue }
        return try Self.parseJob(text, target: target,
            launcher: layout.currentRelease.appendingPathComponent("connector/bin/hermes-connector").path)
    }

    static func parseJob(_ text: String, target: String, launcher: String) throws -> Int32 {
        let lines = text.components(separatedBy: "\n")
        let pids = lines.filter { $0.hasPrefix("\tpid = ") }
        guard lines.first == target + " = {", pids.count == 1,
              let pid = Int32(pids[0].dropFirst("\tpid = ".count)), pid > 0,
              lines.filter({ $0 == "\targuments = {" }).count == 1,
              DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.arguments(fromLaunchctlPrint: text) == [launcher]
        else { throw issue }
        return pid
    }

    private func requireSelection(_ layout: DesktopManagedInstallLayout, version: String) throws {
        var info = stat()
        guard lstat(layout.currentRelease.path, &info) == 0, info.st_mode & S_IFMT == S_IFLNK,
              info.st_uid == getuid(),
              try FileManager.default.destinationOfSymbolicLink(atPath: layout.currentRelease.path) == "releases/\(version)"
        else { throw Self.issue }
    }

    static func requireUnchangedTree(_ root: URL, since startedAt: Date, allowedOwners: Set<uid_t> = [getuid()]) throws {
        guard root.standardizedFileURL == root.standardizedFileURL.resolvingSymlinksInPath() else { throw issue }
        var failed = false
        guard let iterator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil,
            errorHandler: { _, _ in failed = true; return false }) else { throw issue }
        var count = 0
        func inspect(_ path: URL) throws {
            var info = stat()
            guard lstat(path.path, &info) == 0,
                  [S_IFDIR, S_IFREG].contains(info.st_mode & S_IFMT), allowedOwners.contains(info.st_uid),
                  info.st_mode & 0o022 == 0 else { throw issue }
            let changed = TimeInterval(info.st_ctimespec.tv_sec) + TimeInterval(info.st_ctimespec.tv_nsec) / 1_000_000_000
            guard changed <= startedAt.timeIntervalSince1970 else { throw issue }
        }
        try inspect(root)
        while let item = iterator.nextObject() as? URL {
            count += 1
            guard count <= DesktopManagedComponentContentHasher.maximumEntries else { throw issue }
            try inspect(item)
        }
        guard !failed else { throw issue }
    }

    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}

/// Raw process arguments/environment never leave this local reader. Only argv and a closed boolean
/// about loader injection survive; no environment values are stored, logged or returned to callers.
struct ConnectorKernelIdentity: Equatable {
    let pid: Int32
    let userID: UInt32
    let startedAt: Date
    let executable: String
    let arguments: [String]
    let injectedEnvironment: Bool

    static func read(_ pid: Int32) -> Self? {
        guard pid > 0 else { return nil }
        var info = proc_bsdinfo()
        guard proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, Int32(MemoryLayout<proc_bsdinfo>.size)) == MemoryLayout<proc_bsdinfo>.size,
              info.pbi_pid == pid, info.pbi_uid == getuid(), info.pbi_ruid == getuid(),
              info.pbi_status != UInt32(SZOMB) else { return nil }
        // libproc.h defines PROC_PIDPATHINFO_MAXSIZE as (4 * MAXPATHLEN); Swift cannot import
        // that expression macro, so preserve the SDK definition here.
        var path = [CChar](repeating: 0, count: 4 * Int(MAXPATHLEN))
        guard proc_pidpath(pid, &path, UInt32(path.count)) > 0 else { return nil }
        var size = 0
        var mib: [Int32] = [CTL_KERN, KERN_PROCARGS2, pid]
        guard sysctl(&mib, u_int(mib.count), nil, &size, nil, 0) == 0, size > 4, size <= 4 * 1024 * 1024 else { return nil }
        var data = Data(count: size)
        let status = data.withUnsafeMutableBytes { sysctl(&mib, u_int(mib.count), $0.baseAddress, &size, nil, 0) }
        guard status == 0, size <= data.count else { return nil }
        data.count = size
        guard let parsed = parseArguments(data) else { return nil }
        return Self(pid: pid, userID: info.pbi_uid,
            startedAt: Date(timeIntervalSince1970: Double(info.pbi_start_tvsec) + Double(info.pbi_start_tvusec) / 1_000_000),
            executable: String(cString: path), arguments: parsed.arguments, injectedEnvironment: parsed.injected)
    }

    static func parseArguments(_ data: Data) -> (arguments: [String], injected: Bool)? {
        guard data.count > 4 else { return nil }
        let argc = data.withUnsafeBytes { $0.loadUnaligned(as: Int32.self) }
        guard (1...256).contains(argc) else { return nil }
        var offset = 4
        func string() -> String? {
            guard offset < data.count, let end = data[offset...].firstIndex(of: 0),
                  let value = String(data: data[offset..<end], encoding: .utf8) else { return nil }
            offset = end + 1; return value
        }
        guard string() != nil else { return nil } // kernel exec-path, followed by alignment padding
        while offset < data.count && data[offset] == 0 { offset += 1 }
        var arguments: [String] = []
        for _ in 0..<argc { guard let argument = string() else { return nil }; arguments.append(argument) }
        var injected = false
        while offset < data.count {
            if data[offset] == 0 { offset += 1; continue }
            guard let field = string(), let equal = field.firstIndex(of: "=") else { return nil }
            let name = field[..<equal]
            if (name.hasPrefix("NODE_") || name.hasPrefix("DYLD_")) && field.index(after: equal) < field.endIndex {
                injected = true
            }
        }
        return (arguments, injected)
    }
}
