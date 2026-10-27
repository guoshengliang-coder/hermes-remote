import Foundation

/// Presentation evidence only. This never starts, stops or times out an executor.
public struct DesktopOperationStatus: Equatable, Sendable {
    public enum Kind: String, Sendable {
        case hermesUpgrade, desktopUpdate, managedSetup, componentSetup, serviceRepair, hermesInstall
        public var title: String {
            switch self {
            case .hermesUpgrade: "升级 Hermes"
            case .desktopUpdate: "更新 Desktop"
            case .managedSetup, .componentSetup: "设置 Desktop 连接"
            case .serviceRepair: "修复 Desktop 连接"
            case .hermesInstall: "安装 Hermes"
            }
        }
    }
    public enum State: Sendable {
        case running, waitingForConfirmation, succeeded, failed, attention, cancelled, handoff
        public var isActive: Bool { self == .running || self == .waitingForConfirmation }
        public var title: String {
            switch self {
            case .running: "执行中"
            case .waitingForConfirmation: "等待你的确认"
            case .succeeded: "已完成"
            case .failed: "未完成"
            case .attention: "需要处理"
            case .cancelled: "已取消"
            case .handoff: "正在交接安装，即将重启"
            }
        }
    }
    public let kind: Kind
    public var state: State
    public var stage: String
    public var detail: String
    public var issue: DesktopIssue?
    public var measurements: [String]
    public var output: [String]
    /// Real stage/output identity, excluding elapsed labels and stale-speed visibility.
    public var activity: String
    public var retryTitle: String?
    public init(kind: Kind, state: State, stage: String, detail: String = "", issue: DesktopIssue? = nil,
                measurements: [String] = [], output: [String] = [], activity: String = "", retryTitle: String? = nil) {
        self.kind = kind; self.state = state; self.stage = stage; self.detail = detail; self.issue = issue
        self.measurements = measurements.map { SecretRedactor.redact($0) }
        self.output = output.suffix(100).map { String(SecretRedactor.redact($0).prefix(2000)) }
        self.activity = activity; self.retryTitle = retryTitle
    }
}

/// One window follows an operation through preparation, confirmation and execution. A clock tick
/// alone cannot count as progress; silence never changes the operation to failed or succeeded.
public struct DesktopOperationTracker: Sendable {
    public private(set) var status: DesktopOperationStatus?
    public private(set) var generation = 0
    private var started: TimeInterval = 0
    private var stageStarted: TimeInterval = 0
    private var lastActivity: TimeInterval = 0
    private var ended: TimeInterval?
    public init() {}

    @discardableResult public mutating func update(_ next: DesktopOperationStatus?, now: TimeInterval) -> Bool {
        guard let next else { return false } // Missing evidence is never completion.
        let begins = status == nil || status?.kind != next.kind ||
            (status?.state.isActive == false && next.state.isActive)
        if begins {
            guard next.state.isActive || next.state == .attention else { return false } // Don't resurrect old terminal results on launch.
            generation += 1; started = now; stageStarted = now; lastActivity = now; ended = nil
        } else if status?.stage != next.stage || status?.state != next.state {
            if next.state.isActive { stageStarted = now }
            lastActivity = now
        }
        if status?.activity != next.activity || status?.output != next.output { lastActivity = now }
        if !next.state.isActive, ended == nil { ended = now }
        status = next
        return begins
    }
    public func elapsed(at now: TimeInterval) -> Int { seconds((ended ?? now) - started) }
    public func stageElapsed(at now: TimeInterval) -> Int { seconds((ended ?? now) - stageStarted) }
    public func silentSeconds(at now: TimeInterval) -> Int { seconds((ended ?? now) - lastActivity) }
    public func showsSilence(at now: TimeInterval) -> Bool {
        status?.state == .running && silentSeconds(at: now) >= 45
    }
    private func seconds(_ interval: TimeInterval) -> Int {
        guard interval.isFinite else { return 0 }
        return Int(max(0, min(interval, 31_536_000)))
    }
}
