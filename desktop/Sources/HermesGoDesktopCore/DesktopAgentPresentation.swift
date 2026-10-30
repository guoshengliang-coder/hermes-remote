import Foundation

public enum DesktopManagedAccountVerification: Equatable, Sendable {
    case unavailable
    case verified
    case mismatched
}

public enum DesktopAgentMode: Equatable, Sendable {
    case checking
    case managedVerified(releaseVersion: String)
    case managedUnverified(releaseVersion: String)
    case managedStopped(releaseVersion: String)
    case managedProcessUnknown(releaseVersion: String)
    case unavailable
    case attentionRequired
}

public struct DesktopAgentPresentation: Equatable, Sendable {
    public let mode: DesktopAgentMode
    public let health: ComponentHealth

    public init(mode: DesktopAgentMode, health: ComponentHealth) {
        self.mode = mode
        self.health = health
    }

    public static let checking = DesktopAgentPresentation(
        mode: .checking,
        health: ComponentHealth(
            component: .desktopAgent,
            level: .checking,
            detail: "正在检查"
        )
    )

    public static func reduce(
        legacy: RetiredConnectorSnapshot,
        managed: DesktopManagedBootstrapInstallationStatus,
        accountVerification: DesktopManagedAccountVerification,
        connectorProcess: DesktopConnectorProcessState = .unknown,
        checkedAt: Date
    ) -> DesktopAgentPresentation {
        if case .active(let releaseVersion, _, _, _) = managed {
            if accountVerification != .mismatched, connectorProcess != .running {
                let stopped = connectorProcess == .stopped || connectorProcess == .notLoaded
                return DesktopAgentPresentation(
                    mode: stopped ? .managedStopped(releaseVersion: releaseVersion) : .managedProcessUnknown(releaseVersion: releaseVersion),
                    health: ComponentHealth(component: .desktopAgent, level: stopped ? .failed : .degraded,
                        detail: stopped ? "Desktop 后台连接已停止，可重新连接" : "尚未确认 Desktop 后台连接进程",
                        checkedAt: checkedAt, issue: stopped ? DesktopIssue(code: .managedServicesNeedRepair) : nil)
                )
            }
            switch accountVerification {
            case .verified:
                return DesktopAgentPresentation(
                    mode: .managedVerified(releaseVersion: releaseVersion),
                    health: ComponentHealth(
                        component: .desktopAgent,
                        level: .healthy,
                        detail: "托管 Connector 正在运行",
                        checkedAt: checkedAt
                    )
                )
            case .unavailable:
                return DesktopAgentPresentation(
                    mode: .managedUnverified(releaseVersion: releaseVersion),
                    health: ComponentHealth(
                        component: .desktopAgent,
                        level: .degraded,
                        detail: "托管 Connector 正在运行，账号待核验",
                        checkedAt: checkedAt
                    )
                )
            case .mismatched:
                return DesktopAgentPresentation(
                    mode: .attentionRequired,
                    health: ComponentHealth(
                        component: .desktopAgent,
                        level: .failed,
                        detail: "托管 Connector 与当前账号不匹配",
                        checkedAt: checkedAt
                    )
                )
            }
        }

        switch managed {
        case .interrupted, .attentionRequired, .inconsistent:
            return DesktopAgentPresentation(
                mode: .attentionRequired,
                health: ComponentHealth(
                    component: .desktopAgent,
                    level: .failed,
                    detail: "托管安装需要恢复或核验",
                    checkedAt: checkedAt
                )
            )
        case .absent:
            break
        case .active:
            preconditionFailure("active managed installation handled above")
        }

        let hasConflict = legacy.isInstalled || legacy.isRunning
        return DesktopAgentPresentation(
            mode: hasConflict ? .attentionRequired : .unavailable,
            health: ComponentHealth(
                component: .desktopAgent,
                level: hasConflict ? .failed : .unavailable,
                detail: hasConflict ? DesktopIssue(code: .retiredConnectionUnsupported).displayChinese : "Desktop 尚未完成设置",
                checkedAt: checkedAt,
                issue: hasConflict ? DesktopIssue(code: .retiredConnectionUnsupported) : nil
            )
        )
    }
}
