import Combine
import Foundation

/// The UI and test harness share this complete phase-1 flow, including post-upgrade checks.
@MainActor
public final class DesktopHermesVersionManager: ObservableObject {
    public enum Phase: Equatable { case idle, installing, checkingRuntime, completed, failed }
    @Published public private(set) var report: DesktopHermesUpgradeReport?
    @Published public private(set) var installation: DesktopLocalHermesDetection?
    @Published public private(set) var issue: DesktopIssue?
    @Published public private(set) var isChecking = false
    @Published public private(set) var phase = Phase.idle
    @Published public private(set) var progress = ""
    @Published public private(set) var output: [String] = []
    @Published public private(set) var checks: [DesktopDiagnosticCheck] = []
    @Published public private(set) var checkedAt: Date?
    @Published public private(set) var step: DesktopHermesUpgradeStep = .download
    @Published public private(set) var startedAt: Date?
    @Published public private(set) var stepStartedAt: Date?
    @Published public private(set) var lastOutputAt: Date?
    @Published public private(set) var transfer: DesktopHermesTransferProgress?
    @Published public private(set) var transferAt: Date?
    @Published public private(set) var networkSummary = ""
    @Published public private(set) var substep = ""
    @Published public private(set) var retainedStageCount = 0
    public var retryTitle: String {
        phase == .failed ? "重试：" + step.titleChinese : ""
    }
    private var attemptID: UUID?
    public var completedStepCount: Int {
        phase == .completed ? DesktopHermesUpgradeStep.allCases.count : max(step.index, retainedStageCount == 0 ? 0 : retainedStageCount + 1)
    }
    private var upgradedAt: Date?
    private var retryTarget: DesktopHermesUpgradeTarget?
    private let detect: @Sendable () async -> DesktopLocalHermesDetection
    private let checker: DesktopHermesUpgradeChecker
    public typealias Executor = @Sendable (DesktopHermesUpgradeTarget, @escaping @Sendable (DesktopHermesUpgradeStep) async -> Void, @escaping @Sendable (String) -> Void, @escaping @Sendable (DesktopHermesUpgradeStep, DesktopHermesUpgradeActivity) -> Void) async throws -> Void
    private let execute: Executor

    public init(checker: DesktopHermesUpgradeChecker = .init(),
                detect: @escaping @Sendable () async -> DesktopLocalHermesDetection,
                execute: @escaping @Sendable (DesktopHermesUpgradeTarget, @escaping @Sendable (DesktopHermesUpgradeStep) async -> Void, @escaping @Sendable (String) -> Void) async throws -> Void) {
        self.checker = checker; self.detect = detect
        self.execute = { target, progress, output, _ in try await execute(target, progress, output) }
    }
    public init(checker: DesktopHermesUpgradeChecker = .init(),
                detect: @escaping @Sendable () async -> DesktopLocalHermesDetection,
                executeWithEvents: @escaping Executor) {
        self.checker = checker; self.detect = detect; self.execute = executeWithEvents
    }
    public static func system(paths: DesktopLocalHermesPaths) -> DesktopHermesVersionManager {
        let detector = DesktopLocalHermesDetector(paths: paths)
        let session = DesktopHermesUpgradeSession(paths: paths)
        return .init(detect: { await Task.detached { detector.detect() }.value }, executeWithEvents: { target, progress, output, activity in
            let proxy = await Task.detached { DesktopSystemProxy.current() }.value
            try await DesktopHermesStableInstaller(paths: paths, proxy: proxy, session: session).run(target: target, progress: progress, output: output, activity: activity)
        })
    }
    public var isRunning: Bool { phase == .installing || phase == .checkingRuntime }
    public var canUpgrade: Bool {
        !isRunning && !isChecking && (retryTarget != nil || (installation?.installation != nil && hasNewerStable))
    }
    public var target: DesktopHermesUpgradeTarget? { retryTarget ?? report?.latestStable }
    public var hasNewerStable: Bool {
        guard let current = installation?.installation, let latest = report?.latestStable else { return false }
        return DesktopLocalHermesDetector.version(current.version, isOlderThan: latest.version)
    }
    public var comparison: String {
        guard let current = installation?.installation else { return "未识别出本机 Hermes 安装，请查看本机状态。" }
        guard let latest = report?.latestStable else { return "检查最新稳定版后可升级。" }
        if current.version == latest.version {
            return current.commit == latest.commit ? "已是最新稳定版" : "版本号相同，当前代码与稳定版不同"
        }
        return hasNewerStable ? "有新的稳定版本可用" : "本机版本高于最新稳定版，无需升级"
    }
    public var resultSummary: String {
        if phase == .failed { return "升级未完成" }
        let failed = checks.filter(\.needsAttention).count
        let pending = checks.filter { !$0.needsAttention && $0.health.level != .healthy }.count
        if failed > 0 { return "升级完成，\(failed) 项运行检测未通过" }
        if checks.isEmpty || pending > 0 { return "升级完成，部分运行检测待确认" }
        return "升级完成，运行检测通过"
    }
    public func check() async {
        guard !isChecking, !isRunning else { return }
        isChecking = true; issue = nil
        defer { isChecking = false }
        installation = await detect()
        do { report = try await checker.check(installation: installation!) }
        catch { report = nil; issue = DesktopHermesUpgradeError.checkFailed.issue }
    }
    public func upgrade(diagnose: @escaping @MainActor () async -> [DesktopDiagnosticCheck]) async {
        guard canUpgrade, let target else { return }
        let isRetry = phase == .failed && retryTarget != nil
        phase = .installing; issue = nil; checks = []; checkedAt = nil; output = []; retryTarget = target
        let attempt = UUID(); attemptID = attempt
        startedAt = Date(); stepStartedAt = startedAt; lastOutputAt = nil
        step = .download; progress = step.titleChinese
        transfer = nil; transferAt = nil; substep = ""; networkSummary = ""; retainedStageCount = 0
        do {
            // Re-read just the local identity. No compatibility certification or Git configuration preflight.
            installation = await detect()
            if let current = installation?.installation {
                guard !DesktopLocalHermesDetector.version(target.version, isOlderThan: current.version)
                else { throw DesktopHermesUpgradeError.preflightBlocked }
            } else if !isRetry {
                throw DesktopHermesUpgradeError.preflightBlocked
            }
            try await execute(target, { [weak self] value in
                await self?.receiveStep(value, attempt: attempt)
            }, { [weak self] value in
                Task { @MainActor in
                    guard self?.attemptID == attempt else { return }
                    self?.appendOutput(value)
                }
            }, { [weak self] source, activity in
                Task { @MainActor in self?.receiveActivity(activity, source: source, attempt: attempt) }
            })
            receiveStep(.verify, attempt: attempt)
            installation = await detect()
            guard let installed = installation?.installation, installed.version == target.version,
                  installed.commit == target.commit, installed.dependenciesConsistent
            else { throw DesktopHermesUpgradeError.checkFailed }
            retryTarget = nil
            upgradedAt = Date()
        } catch {
            appendOutput("升级执行失败：\(String(describing: error))")
            installation = await detect()
            issue = DesktopIssue(code: .hermesUpdateExecutionFailed, technicalCause: "stable_upgrade_failed")
            phase = .failed; progress = ""; return
        }
        await runDiagnostics(diagnose)
    }
    public func recheckRuntime(diagnose: @escaping @MainActor () async -> [DesktopDiagnosticCheck]) async {
        guard phase == .completed else { return }
        startedAt = Date(); stepStartedAt = startedAt; lastOutputAt = nil
        await runDiagnostics(diagnose)
    }
    private func runDiagnostics(_ diagnose: @MainActor () async -> [DesktopDiagnosticCheck]) async {
        step = .diagnostics; stepStartedAt = Date()
        phase = .checkingRuntime; progress = step.titleChinese; issue = nil
        let reported = await diagnose()
        checks = reported.map { check in
            guard let upgradedAt, [.connector, .reportedHermes, .endToEnd].contains(check.id),
                  check.health.checkedAt == nil || check.health.checkedAt! < upgradedAt else { return check }
            return DesktopDiagnosticCheck(id: check.id, title: check.title, source: check.source,
                health: ComponentHealth(component: check.health.component, level: .unavailable,
                    detail: "等待升级后的新检测报告，可稍后重新检测。", checkedAt: check.health.checkedAt))
        }
        checkedAt = Date()
        if checks.contains(where: \.needsAttention) { issue = DesktopIssue(code: .hermesUpdateDiagnosticsFailed) }
        phase = .completed; progress = ""
    }
    private func receiveStep(_ value: DesktopHermesUpgradeStep, attempt: UUID) {
        guard attemptID == attempt, phase == .installing, value.index >= step.index else { return }
        if value != step { stepStartedAt = Date(); transfer = nil; transferAt = nil; substep = "" }
        step = value; progress = value.titleChinese
    }
    private func receiveActivity(_ activity: DesktopHermesUpgradeActivity, source: DesktopHermesUpgradeStep, attempt: UUID) {
        guard attemptID == attempt, phase == .installing else { return }
        switch activity {
        case .network(let text): networkSummary = text
        case .resumed(let count): retainedStageCount = count
        case .transfer(let value):
            guard source == step else { return }
            transfer = value; transferAt = Date()
        case .substep(let text):
            guard source == step else { return }
            substep = text; transfer = nil; transferAt = nil
        }
    }
    private func appendOutput(_ value: String) {
        lastOutputAt = Date()
        output.append(String(SecretRedactor.redact(value).prefix(4000)))
        if output.count > 200 { output.removeFirst(output.count - 200) }
    }
}
