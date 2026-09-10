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
    case legacy
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
        legacy: LegacyConnectorSnapshot,
        managed: DesktopManagedBootstrapInstallationStatus,
        accountVerification: DesktopManagedAccountVerification,
        checkedAt: Date
    ) -> DesktopAgentPresentation {
        if case .active(let releaseVersion, _, _) = managed {
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

        if legacy.isRunning {
            return DesktopAgentPresentation(
                mode: .legacy,
                health: ComponentHealth(
                    component: .desktopAgent,
                    level: .healthy,
                    detail: "旧 Connector 正在运行（兼容观察模式）",
                    checkedAt: checkedAt
                )
            )
        }
        return DesktopAgentPresentation(
            mode: legacy.isInstalled ? .attentionRequired : .unavailable,
            health: ComponentHealth(
                component: .desktopAgent,
                level: legacy.isInstalled ? .failed : .unavailable,
                detail: legacy.isInstalled ? "已安装但当前未运行" : "未检测到 Connector",
                checkedAt: checkedAt
            )
        )
    }
}
