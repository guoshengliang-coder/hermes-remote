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
    private var attemptID: UUID?
    public var completedStepCount: Int {
        phase == .completed ? DesktopHermesUpgradeStep.allCases.count : step.index
    }
    private var upgradedAt: Date?
    private var retryTarget: DesktopHermesUpgradeTarget?
    private let detect: @Sendable () async -> DesktopLocalHermesDetection
    private let checker: DesktopHermesUpgradeChecker
    private let execute: @Sendable (DesktopHermesUpgradeTarget, @escaping @Sendable (DesktopHermesUpgradeStep) async -> Void, @escaping @Sendable (String) -> Void) async throws -> Void

    public init(checker: DesktopHermesUpgradeChecker = .init(),
                detect: @escaping @Sendable () async -> DesktopLocalHermesDetection,
                execute: @escaping @Sendable (DesktopHermesUpgradeTarget, @escaping @Sendable (DesktopHermesUpgradeStep) async -> Void, @escaping @Sendable (String) -> Void) async throws -> Void) {
        self.checker = checker; self.detect = detect; self.execute = execute
    }
    public static func system(paths: DesktopLocalHermesPaths) -> DesktopHermesVersionManager {
        let detector = DesktopLocalHermesDetector(paths: paths)
        return .init(detect: { await Task.detached { detector.detect() }.value }, execute: { target, progress, output in
            let proxy = await Task.detached { DesktopSystemProxy.current() }.value
            try await DesktopHermesStableInstaller(paths: paths, proxy: proxy).run(target: target, progress: progress, output: output)
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
        if value != step { stepStartedAt = Date() }
        step = value; progress = value.titleChinese
    }
    private func appendOutput(_ value: String) {
        lastOutputAt = Date()
        output.append(String(SecretRedactor.redact(value).prefix(4000)))
        if output.count > 200 { output.removeFirst(output.count - 200) }
    }
}
