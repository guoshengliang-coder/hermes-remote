import Foundation

/// A disk record is historical intent, never a liveness, health or execution permission signal.
/// Keep private journal paths and receipt identities out of the presentation model.
public enum DesktopHermesUpgradeRecord: Equatable, Sendable {
    case unchecked
    case absent
    case recorded(target: DesktopHermesUpgradeTarget, phase: DesktopHermesUpgradePhase, updatedAt: Date)
    case unreadable

    public static func read(from store: DesktopHermesUpgradeJournalStore) -> Self {
        do {
            guard let journal = try store.loadReadOnly() else { return .absent }
            return .recorded(target: journal.target, phase: journal.phase, updatedAt: journal.updatedAt)
        } catch { return .unreadable }
    }

    public var requiresRecovery: Bool {
        switch self {
        case .unchecked, .absent: return false
        case .unreadable: return true
        case .recorded(_, let phase, _): return !phase.terminal
        }
    }

    public var issue: DesktopIssue? {
        requiresRecovery ? DesktopHermesUpgradeError.recoveryRequired.issue : nil
    }

    public var titleChinese: String {
        switch self {
        case .unchecked: return "尚未读取升级记录"
        case .absent: return "没有本机升级记录"
        case .unreadable: return "升级记录无法核验"
        case .recorded(_, let phase, _): return phase.terminal ? "历史升级记录" : "待恢复的升级记录"
        }
    }

    public var titleEnglish: String {
        switch self {
        case .unchecked: return "Upgrade record not yet checked"
        case .absent: return "No local upgrade record"
        case .unreadable: return "Upgrade record could not be verified"
        case .recorded(_, let phase, _): return phase.terminal ? "Past upgrade record" : "Upgrade record needs recovery"
        }
    }

    public var explanationChinese: String {
        switch self {
        case .unchecked: return "刷新以读取本机升级记录。"
        case .absent: return "此处只读取本机记录，刷新不会执行升级。"
        case .unreadable: return "记录不完整或无法安全读取，自动操作已暂停。请查看诊断。"
        case .recorded(_, let phase, _):
            return phase.terminal
                ? "这是历史记录，不能证明当前运行的 Hermes 版本或健康状态。"
                : "阶段表示最后写入的操作意图，不能证明操作成功或进程仍在运行。自动操作已暂停，请查看诊断。"
        }
    }

    public var explanationEnglish: String {
        switch self {
        case .unchecked: return "Refresh to read the local upgrade record."
        case .absent: return "Refresh only reads local records; it does not perform an upgrade."
        case .unreadable: return "The record is incomplete or unsafe to read. Automatic operations are paused. View diagnostics."
        case .recorded(_, let phase, _):
            return phase.terminal
                ? "This past record does not verify the currently running Hermes version or health."
                : "The stage is the last recorded intent, not proof of success or a running process. Automatic operations are paused. View diagnostics."
        }
    }
}

public extension DesktopHermesUpgradePhase {
    var recordedLabelChinese: String {
        switch self {
        case .quiescing: return "准备停止接收新任务"
        case .backingUp: return "准备备份"
        case .updating: return "准备更新源码和依赖"
        case .restoringServices: return "准备恢复服务"
        case .verifying: return "准备验证升级结果"
        case .cancelling: return "准备取消并恢复服务"
        case .completed: return "记录为已完成"
        case .cancelled: return "记录为已取消"
        }
    }

    var recordedLabelEnglish: String {
        switch self {
        case .quiescing: return "Intent to stop accepting new work"
        case .backingUp: return "Intent to create a backup"
        case .updating: return "Intent to update source and dependencies"
        case .restoringServices: return "Intent to restore services"
        case .verifying: return "Intent to verify the upgrade"
        case .cancelling: return "Intent to cancel and restore services"
        case .completed: return "Recorded as completed"
        case .cancelled: return "Recorded as cancelled"
        }
    }
}
