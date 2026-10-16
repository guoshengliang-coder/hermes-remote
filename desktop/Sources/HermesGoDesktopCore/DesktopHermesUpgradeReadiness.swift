import Darwin
import Foundation

public enum DesktopHermesUpgradeWorkingTree: String, Sendable { case clean, dirty, unknown }
public protocol DesktopHermesUpgradeGitInspecting: Sendable {
    func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree
}
public struct DesktopHermesUpgradeGitInspector: DesktopHermesUpgradeGitInspecting {
    public init() {}
    public func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree {
        let process = Process(), output = Pipe()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
        process.arguments = ["--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false",
            "-C", checkout.path, "status", "--porcelain=v1", "--untracked-files=normal"]
        process.environment = ["PATH": "/usr/bin:/bin", "LC_ALL": "C", "GIT_TERMINAL_PROMPT": "0",
            "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null"]
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = output; process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return .unknown }
        let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 2, execute: timeout)
        let killTimeout = DispatchWorkItem { if process.isRunning { Darwin.kill(process.processIdentifier, SIGKILL) } }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 3, execute: killTimeout)
        var dirty = false
        while let data = try? output.fileHandleForReading.read(upToCount: 4096), !data.isEmpty { dirty = true }
        process.waitUntilExit(); timeout.cancel(); killTimeout.cancel()
        guard process.terminationStatus == 0 else { return .unknown }
        return dirty ? .dirty : .clean
    }
}

public struct DesktopHermesUpgradeReadiness: Equatable, Sendable {
    public let target: DesktopHermesUpgradeTarget
    public let sourceIdentity: String?
    public let workingTree: DesktopHermesUpgradeWorkingTree
    public let recoveryRequired: Bool
    public let officialUpdateInProgress: Bool
    public let targetCertified: Bool
    public let dependenciesVerified: Bool
    public let checkedAt: Date
    public let inspection: DesktopHermesUpgradeInspection?
    public let activity: DesktopHermesUpgradeActivity?
    /// This read-only check cannot prove live-task retirement, stopped writers or restore custody.
    /// It must never be used as execution authorization by the transaction adapter.
    public var issue: DesktopIssue? {
        if recoveryRequired { return DesktopHermesUpgradeError.recoveryRequired.issue }
        if sourceIdentity == nil || workingTree != .clean || officialUpdateInProgress || !targetCertified || !dependenciesVerified {
            return DesktopHermesUpgradeError.preflightBlocked.issue
        }
        guard let inspection, inspection.processSampleStable, inspection.unreadableOwnedProcesses == 0,
              inspection.profileDirectoryScanComplete,
              let sourceIdentity, let activity, activity.isFresh(for: sourceIdentity),
              inspection.sourceIdentity == sourceIdentity, inspection.checkedAt.timeIntervalSince1970.isFinite,
              (0...5).contains(Date().timeIntervalSince(inspection.checkedAt)),
              activity.state == .idleObserved, let process = activity.process,
              inspection.candidates.count == 1,
              inspection.candidates.contains(where: { $0.association == .checkout && $0.identity == process })
        else { return DesktopHermesUpgradeError.preflightBlocked.issue }
        return nil
    }
    public func recordingActivity(_ activity: DesktopHermesUpgradeActivity?) -> Self {
        .init(target: target, sourceIdentity: sourceIdentity, workingTree: workingTree,
            recoveryRequired: recoveryRequired, officialUpdateInProgress: officialUpdateInProgress,
            targetCertified: targetCertified, dependenciesVerified: dependenciesVerified, checkedAt: checkedAt,
            inspection: inspection, activity: activity)
    }
    public var workingTreeSummaryChinese: String {
        switch workingTree {
        case .clean: "未发现本地改动"
        case .dirty: "存在本地改动，请先处理后再升级"
        case .unknown: "无法确认本地改动，暂不能升级"
        }
    }
    public var workingTreeSummaryEnglish: String {
        switch workingTree {
        case .clean: "No local changes found"
        case .dirty: "Resolve local changes before upgrading"
        case .unknown: "Local changes could not be checked; upgrading is blocked"
        }
    }
}

public struct DesktopHermesUpgradeReadinessChecker: Sendable {
    private let git: any DesktopHermesUpgradeGitInspecting
    private let inspector: any DesktopHermesUpgradeInspecting
    public init(git: any DesktopHermesUpgradeGitInspecting = DesktopHermesUpgradeGitInspector(),
                inspector: any DesktopHermesUpgradeInspecting = DesktopHermesUpgradeInspector()) {
        self.git = git; self.inspector = inspector
    }
    public func check(detection: DesktopLocalHermesDetection, target: DesktopHermesUpgradeTarget,
                      journal: DesktopHermesUpgradeJournalStore, officialUpdateInProgress: Bool,
                      certifiedTargets: [DesktopHermesUpgradeTarget] = [], now: Date = Date()) -> DesktopHermesUpgradeReadiness {
        let installation = detection.installation
        let recovery: Bool
        do { try journal.requireNoPendingUpgrade(); recovery = false } catch { recovery = true }
        // Do not inspect a half-written checkout while any update owns it or recovery is owed.
        let workingTree = installation != nil && !officialUpdateInProgress && !recovery
            ? git.workingTree(checkout: installation!.checkoutRoot) : .unknown
        return .init(target: target, sourceIdentity: installation?.runtimeIdentity, workingTree: workingTree,
            recoveryRequired: recovery, officialUpdateInProgress: officialUpdateInProgress,
            targetCertified: certifiedTargets.contains(target), dependenciesVerified: installation?.upgradeDependenciesVerified ?? false, checkedAt: now,
            inspection: installation != nil && !officialUpdateInProgress && !recovery ? inspector.inspect(installation: installation!) : nil,
            activity: nil)
    }
}
