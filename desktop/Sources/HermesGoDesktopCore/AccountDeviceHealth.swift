import Foundation

public enum AccountDeviceHealth {
    public static func snapshot(
        for device: AccountDevice,
        checkedAt: Date = Date()
    ) -> DesktopHealthSnapshot {
        DesktopHealthSnapshot(
            components: [
                connectorHealth(device, checkedAt: checkedAt),
                gatewayHealth(device, checkedAt: checkedAt),
                hermesHealth(device, checkedAt: checkedAt),
                endToEndHealth(device, checkedAt: checkedAt),
            ],
            checkedAt: checkedAt
        )
    }

    private static func connectorHealth(
        _ device: AccountDevice,
        checkedAt: Date
    ) -> ComponentHealth {
        if device.connector.online {
            return ComponentHealth(
                component: .desktopAgent,
                level: .healthy,
                detail: "账号 Connector 在线",
                checkedAt: checkedAt
            )
        }
        let issue = DesktopIssue(code: .connectorOffline)
        return ComponentHealth(
            component: .desktopAgent,
            level: .failed,
            detail: issue.displayChinese,
            checkedAt: checkedAt,
            issue: issue
        )
    }

    private static func gatewayHealth(
        _ device: AccountDevice,
        checkedAt: Date
    ) -> ComponentHealth {
        let detail = device.gateway.latencyMs.map { "账号服务可用 · \($0) ms" }
            ?? "账号服务可用"
        return ComponentHealth(
            component: .gateway,
            level: .healthy,
            detail: detail,
            checkedAt: checkedAt
        )
    }

    private static func hermesHealth(
        _ device: AccountDevice,
        checkedAt: Date
    ) -> ComponentHealth {
        switch device.hermes.reachable {
        case true:
            let detail = device.hermes.version.map { "可访问 · \($0)" } ?? "可访问"
            return ComponentHealth(
                component: .hermes,
                level: .healthy,
                detail: detail,
                checkedAt: checkedAt
            )
        case false:
            let issue = DesktopIssue(code: .hermesUnavailable)
            return ComponentHealth(
                component: .hermes,
                level: .failed,
                detail: issue.displayChinese,
                checkedAt: checkedAt,
                issue: issue
            )
        case nil:
            return ComponentHealth(
                component: .hermes,
                level: .degraded,
                detail: "等待 Hermes 状态上报",
                checkedAt: checkedAt
            )
        }
    }

    private static func endToEndHealth(
        _ device: AccountDevice,
        checkedAt: Date
    ) -> ComponentHealth {
        switch device.endToEnd.healthy {
        case true:
            return ComponentHealth(
                component: .endToEnd,
                level: .healthy,
                detail: "账号链路正常",
                checkedAt: checkedAt
            )
        case false:
            let issue = DesktopIssue(code: .relayFailure)
            return ComponentHealth(
                component: .endToEnd,
                level: .failed,
                detail: issue.displayChinese,
                checkedAt: checkedAt,
                issue: issue
            )
        case nil:
            return ComponentHealth(
                component: .endToEnd,
                level: .degraded,
                detail: "等待端到端状态上报",
                checkedAt: checkedAt
            )
        }
    }
}
