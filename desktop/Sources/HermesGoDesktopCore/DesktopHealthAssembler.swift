import Foundation

public enum DesktopHealthAssembler {
    public static func assemble(
        legacy observation: LegacyConnectorSnapshot,
        relay: ProbeResult,
        hermes: ProbeResult,
        endToEnd: ProbeResult,
        checkedAt: Date = Date()
    ) -> DesktopHealthSnapshot {
        DesktopHealthSnapshot(
            components: [
                agentHealth(observation, checkedAt: checkedAt),
                probeHealth(.gateway, result: relay, checkedAt: checkedAt),
                probeHealth(.hermes, result: hermes, checkedAt: checkedAt),
                observerHealth(observation, checkedAt: checkedAt),
                probeHealth(.endToEnd, result: endToEnd, checkedAt: checkedAt),
            ],
            checkedAt: checkedAt
        )
    }

    private static func agentHealth(
        _ observation: LegacyConnectorSnapshot,
        checkedAt: Date
    ) -> ComponentHealth {
        ComponentHealth(
            component: .desktopAgent,
            level: observation.isRunning
                ? .healthy
                : (observation.isInstalled ? .failed : .unavailable),
            detail: observation.isRunning
                ? "旧 Connector 正在运行（兼容观察模式）"
                : (observation.isInstalled ? "已安装但当前未运行" : "未检测到旧 Connector"),
            checkedAt: checkedAt
        )
    }

    private static func observerHealth(
        _ observation: LegacyConnectorSnapshot,
        checkedAt: Date
    ) -> ComponentHealth {
        guard observation.isInstalled else {
            return ComponentHealth(
                component: .observer,
                level: .unavailable,
                detail: "未检测到旧 Connector",
                checkedAt: checkedAt
            )
        }
        return ComponentHealth(
            component: .observer,
            level: observation.config.observerEnabled ? .checking : .unavailable,
            detail: observation.config.observerEnabled
                ? "按现有配置启用，阶段 0 尚未独立上报"
                : "现有配置已关闭",
            checkedAt: checkedAt
        )
    }

    private static func probeHealth(
        _ component: HealthComponent,
        result: ProbeResult,
        checkedAt: Date
    ) -> ComponentHealth {
        let detail = result.latencyMilliseconds.map {
            "\(result.detail) · \($0) ms"
        } ?? result.detail
        return ComponentHealth(
            component: component,
            level: result.level,
            detail: detail,
            checkedAt: checkedAt,
            issue: result.issue
        )
    }
}
