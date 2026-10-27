import Foundation
import OSLog

public enum DesktopManagedBootstrapOperation: Equatable {
    case idle, preparing, awaitingConfirmation, committing, recovering
    case completed(releaseVersion: String, cleanupPending: Bool)
    case failed

    /// A retained cleanup handle must not keep an installation confirmation sheet open.
    public var presentsConfirmation: Bool {
        self == .awaitingConfirmation
    }

    public var uiState: DesktopSetupUIState {
        switch self {
        case .idle: .idle
        case .preparing: .preparing
        case .awaitingConfirmation: .awaitingConfirmation
        case .committing: .committing
        case .recovering: .recovering
        case .completed(_, let pending): pending ? .cleanupPending : .completed
        case .failed: .failed
        }
    }
}

public enum DesktopComponentBootstrapOperation: Equatable {
    case idle, preparing, awaitingConfirmation, committing
    case completed(releaseVersion: String, cleanupPending: Bool)
    case failed

    public var presentsConfirmation: Bool {
        self == .awaitingConfirmation
    }

    public var uiState: DesktopSetupUIState {
        switch self {
        case .idle: .idle
        case .preparing: .preparing
        case .awaitingConfirmation: .awaitingConfirmation
        case .committing: .committing
        case .completed(_, let pending): pending ? .cleanupPending : .completed
        case .failed: .failed
        }
    }
}

public enum DesktopSetupUIState: String, Sendable {
    case idle, preparing, awaitingConfirmation, committing, recovering, completed, cleanupPending, failed
}

public enum DesktopSetupStage: String, CaseIterable, Sendable {
    case checking, staging, stoppingServices, waitingForShutdown
    case startingHermes, waitingForHermes, startingConnector, waitingForConnection
    case committing, cleaning, rollingBack, completed, cleanupPending, failed

    public var titleChinese: String {
        switch self {
        case .checking: "正在重新检查安装条件"
        case .staging: "正在准备服务切换"
        case .stoppingServices: "正在停止旧服务"
        case .waitingForShutdown: "正在等待旧服务退出"
        case .startingHermes: "正在启动 Hermes"
        case .waitingForHermes: "正在验证本机 Hermes"
        case .startingConnector: "正在建立 Desktop 连接"
        case .waitingForConnection: "正在等待云端确认连接"
        case .committing: "正在保存已验证的连接"
        case .cleaning: "正在清理临时文件"
        case .rollingBack: "正在恢复原来的连接"
        case .completed: "连接已生效"
        case .cleanupPending: "连接已生效，临时文件待清理"
        case .failed: "操作已结束，请查看结果"
        }
    }

    public var isTerminal: Bool {
        self == .completed || self == .cleanupPending || self == .failed
    }
}

public struct DesktopSetupProgressSnapshot: Equatable, Sendable {
    public let runID: String
    public let stage: DesktopSetupStage
    public let elapsedSeconds: Int
    public let stageElapsedSeconds: Int

    public var elapsedChinese: String {
        if elapsedSeconds < 60 { return "已用时 \(elapsedSeconds) 秒" }
        return "已用时 \(elapsedSeconds / 60) 分 \(elapsedSeconds % 60) 秒"
    }
}

/// Synchronous, per-run telemetry; it never awaits UI/network work or changes the transaction.
/// The UI reads a snapshot once a second. A monotonic clock keeps wall-clock changes from
/// changing elapsed time. Only fixed enums, canonical UUIDs and durations reach either log sink.
public final class DesktopSetupProgress: @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.hermesgo.desktop", category: "managed-setup")
    private let lock = NSLock()
    private let now: @Sendable () -> TimeInterval
    private let write: @Sendable (String) -> Void
    private var runID: String?
    private var stage = DesktopSetupStage.checking
    private var startedAt: TimeInterval = 0
    private var stageStartedAt: TimeInterval = 0
    private var endedAt: TimeInterval?

    public init(
        operationLog: DesktopServiceOperationLog? = nil,
        now: @escaping @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
        logSink: (@Sendable (String) -> Void)? = nil
    ) {
        self.now = now
        write = logSink ?? { line in
            Self.logger.notice("\(line, privacy: .public)")
            operationLog?.record(line)
        }
    }

    public func begin(runID: String) {
        guard let id = UUID(uuidString: runID)?.uuidString.lowercased() else { return }
        lock.withLock {
            self.runID = id
            stage = .checking
            startedAt = now()
            stageStartedAt = startedAt
            endedAt = nil
            write(line(event: "begin", at: startedAt))
        }
    }

    public func advance(_ next: DesktopSetupStage, runID: String) {
        lock.withLock {
            guard matches(runID), endedAt == nil, next != stage else { return }
            let time = now()
            write(line(event: "stage-end", at: time))
            stage = next
            stageStartedAt = time
            if next.isTerminal { endedAt = time }
            write(line(event: next.isTerminal ? "end" : "stage-start", at: time))
        }
    }

    public func recordJournal(_ state: DesktopMigrationState, runID: String) {
        lock.withLock {
            guard matches(runID), endedAt == nil else { return }
            write(line(event: "journal=\(state.rawValue)", at: now()))
        }
    }

    public func recordUI(_ state: DesktopSetupUIState) {
        lock.withLock { write(line(event: "ui=\(state.rawValue)", at: endedAt ?? now())) }
    }

    public var snapshot: DesktopSetupProgressSnapshot? {
        lock.withLock {
            guard let runID else { return nil }
            let time = endedAt ?? now()
            return DesktopSetupProgressSnapshot(
                runID: runID, stage: stage,
                elapsedSeconds: seconds(time - startedAt),
                stageElapsedSeconds: seconds(time - stageStartedAt)
            )
        }
    }

    private func matches(_ value: String) -> Bool {
        guard let id = UUID(uuidString: value) else { return false }
        return runID == id.uuidString.lowercased()
    }

    private func seconds(_ value: TimeInterval) -> Int {
        guard value.isFinite else { return 0 }
        return Int(max(0, min(value, Double(Int.max / 2))))
    }

    private func line(event: String, at time: TimeInterval) -> String {
        "setup run=\(runID ?? "none") \(event) stage=\(stage.rawValue) elapsed_s=\(runID == nil ? 0 : seconds(time - startedAt)) stage_s=\(runID == nil ? 0 : seconds(time - stageStartedAt))"
    }
}
