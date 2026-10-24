import Darwin
import Foundation

public enum DesktopHermesUpgradeWorkingTree: String, Sendable { case clean, dirty, unsupportedConfiguration, unknown }
public protocol DesktopHermesUpgradeGitInspecting: Sendable {
    func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree
}
public struct DesktopHermesUpgradeGitInspector: DesktopHermesUpgradeGitInspecting {
    public init() {}
    public func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree {
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        let configArguments = ["config", "--local", "--no-includes", "--null", "--list"]
        guard let config = Self.readOnlyOutput(checkout: checkout, arguments: configArguments, deadline: deadline)
        else { return .unknown }
        guard Self.reviewedConfiguration(config) else { return .unsupportedConfiguration }
        guard let top = Self.readOnlyOutput(checkout: checkout, arguments: ["rev-parse", "--show-toplevel"], deadline: deadline),
              let path = String(data: top, encoding: .utf8),
              URL(fileURLWithPath: path.trimmingCharacters(in: .newlines)).resolvingSymlinksInPath().path
                == checkout.resolvingSymlinksInPath().path,
              let status = Self.readOnlyOutput(checkout: checkout,
                arguments: ["status", "--porcelain=v1", "--untracked-files=normal"], deadline: deadline),
              let after = Self.readOnlyOutput(checkout: checkout, arguments: configArguments, deadline: deadline),
              after == config else { return .unknown }
        return status.isEmpty ? .clean : .dirty
    }

    private static func reviewedConfiguration(_ data: Data) -> Bool {
        for row in data.split(separator: 0) {
            guard let name = String(data: Data(row.prefix { $0 != 10 }), encoding: .utf8) else { return false }
            let key = name.lowercased()
            if key.hasPrefix("filter.") || key.hasPrefix("include.") || key.hasPrefix("includeif.")
                || key.hasPrefix("url.") || key.hasPrefix("protocol.") || key.hasPrefix("alias.")
                || key.hasPrefix("submodule.")
                || ["core.attributesfile", "core.sshcommand", "core.gitproxy", "extensions.worktreeconfig"].contains(key)
                || (key.hasPrefix("remote.") && key.hasSuffix(".vcs")) { return false }
        }
        return true
    }

    /// A read-only OS boundary also covers a config change between inspection and status.
    /// No Git child/helper/filter can fork, write a file, or use a network transport.
    static func readOnlyOutput(checkout: URL, arguments: [String], deadline: TimeInterval) -> Data? {
        guard ProcessInfo.processInfo.systemUptime < deadline else { return nil }
        let process = Process(), output = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/sandbox-exec")
        process.arguments = ["-p", "(version 1) (allow default) (deny file-write* (require-not (literal \"/dev/null\"))) (deny network*) (deny process-fork)",
            "/usr/bin/git", "--no-replace-objects", "--no-optional-locks", "--no-pager",
            "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false", "-c", "core.hooksPath=/dev/null",
            "-C", checkout.path] + arguments
        process.environment = ["PATH": "/usr/bin:/bin", "LC_ALL": "C", "GIT_TERMINAL_PROMPT": "0",
            "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1", "GIT_NO_LAZY_FETCH": "1",
            "GIT_ALLOW_PROTOCOL": "", "GIT_ATTR_NOSYSTEM": "1"]
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = output; process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        try? output.fileHandleForWriting.close()
        defer {
            if process.isRunning {
                process.terminate()
                let grace = ProcessInfo.processInfo.systemUptime + 0.2
                while process.isRunning && ProcessInfo.processInfo.systemUptime < grace { usleep(1_000) }
                if process.isRunning { Darwin.kill(process.processIdentifier, SIGKILL) }
            }
            process.waitUntilExit()
            try? output.fileHandleForReading.close()
        }
        let fd = output.fileHandleForReading.fileDescriptor
        let flags = fcntl(fd, F_GETFL)
        guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0 else { return nil }
        var bytes = Data(), buffer = [UInt8](repeating: 0, count: 4096), eof = false
        while ProcessInfo.processInfo.systemUptime < deadline {
            let count = buffer.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress, $0.count) }
            if count > 0 {
                guard bytes.count + count <= 65_536 else { return nil }
                bytes.append(contentsOf: buffer.prefix(count))
            } else if count == 0 {
                eof = true
            } else if errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR { return nil }
            if eof && !process.isRunning { return process.terminationStatus == 0 ? bytes : nil }
            if eof { usleep(1_000) }
            else {
                var descriptor = pollfd(fd: fd, events: Int16(POLLIN | POLLHUP), revents: 0)
                _ = poll(&descriptor, 1, 20)
            }
        }
        return nil
    }
}

/// Diagnostic snapshot only. This slice has no executor or certified upgrade paths.
public struct DesktopHermesUpgradeReadiness: Equatable, Sendable {
    public let target: DesktopHermesUpgradeTarget
    public let workingTree: DesktopHermesUpgradeWorkingTree
    public let recoveryRequired: Bool
    public let officialUpdateInProgress: Bool
    public let checkedAt: Date
    public var executionAvailable: Bool { false }
    public var issue: DesktopIssue? {
        if recoveryRequired { return DesktopHermesUpgradeError.recoveryRequired.issue }
        if workingTree == .unsupportedConfiguration {
            return DesktopIssue(code: .hermesUpdateBlocked, technicalCause: "stage=git-preflight reason=unsupported-configuration")
        }
        return DesktopHermesUpgradeError.preflightBlocked.issue
    }
    public var workingTreeSummaryChinese: String {
        switch workingTree {
        case .clean: "未发现本地改动"
        case .dirty: "存在本地改动，请先处理后再升级"
        case .unsupportedConfiguration: "自定义 Git 配置阻止安全检查，暂不能升级"
        case .unknown: "无法确认本地改动，暂不能升级"
        }
    }
    public var workingTreeSummaryEnglish: String {
        switch workingTree {
        case .clean: "No local changes found"
        case .dirty: "Resolve local changes before upgrading"
        case .unsupportedConfiguration: "Custom Git configuration prevents safe inspection; upgrading is blocked"
        case .unknown: "Local changes could not be checked; upgrading is blocked"
        }
    }
}

public struct DesktopHermesUpgradeReadinessChecker: Sendable {
    private let git: any DesktopHermesUpgradeGitInspecting
    public init(git: any DesktopHermesUpgradeGitInspecting = DesktopHermesUpgradeGitInspector()) { self.git = git }
    public func check(detection: DesktopLocalHermesDetection, target: DesktopHermesUpgradeTarget,
                      journal: DesktopHermesUpgradeJournalStore, officialUpdateInProgress: Bool,
                      now: Date = Date()) -> DesktopHermesUpgradeReadiness {
        let recovery: Bool
        do { try journal.requireNoPendingUpgrade(); recovery = false } catch { recovery = true }
        let installation = detection.installation
        let workingTree = installation != nil && !officialUpdateInProgress && !recovery
            ? git.workingTree(checkout: installation!.checkoutRoot) : .unknown
        return .init(target: target, workingTree: workingTree, recoveryRequired: recovery,
                     officialUpdateInProgress: officialUpdateInProgress, checkedAt: now)
    }
}
