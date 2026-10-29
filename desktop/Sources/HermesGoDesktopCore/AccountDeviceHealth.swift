import Foundation

public enum AccountDeviceHealth {
    public static func snapshot(
        for device: AccountDevice,
        checkedAt: Date = Date(),
        now: Date = Date()
    ) -> DesktopHealthSnapshot {
        let reportedAt = device.endToEnd.checkedAt.flatMap(parseCanonicalTimestamp) ?? .distantPast
        let accountFresh = isFresh(checkedAt, now: now)
        if let connection = device.connection, connection.version == 1, accountFresh {
            let elapsed = max(0, now.timeIntervalSince(checkedAt))
            let localFresh = Double(connection.healthAgeMs) + elapsed * 1000 < 60_000
            let local = ComponentHealth(component: .hermes,
                level: !localFresh || connection.local == "unknown" ? .unavailable
                    : connection.local == "healthy" ? .healthy : connection.local == "unhealthy" ? .failed : .degraded,
                detail: !localFresh || connection.local == "unknown" ? "Hermes 状态待刷新"
                    : connection.local == "healthy" ? "Hermes 可访问"
                    : connection.local == "unhealthy" ? "HR-CONN-006 · 请在 Mac 上检查 Hermes" : "Hermes 响应较慢，正在复查",
                checkedAt: checkedAt)
            return DesktopHealthSnapshot(components: [connectorHealth(device, checkedAt: checkedAt),
                gatewayHealth(device, checkedAt: checkedAt), local,
                ComponentHealth(component: .endToEnd, level: localFresh ? connection.level : .unavailable,
                    detail: localFresh ? connection.summary : "Hermes 状态待刷新", checkedAt: checkedAt)], checkedAt: checkedAt)
        }
        let reportFresh = accountFresh && isFresh(reportedAt, now: now)
        func pending(_ component: HealthComponent, _ date: Date) -> ComponentHealth {
            ComponentHealth(component: component, level: .unavailable,
                detail: "状态尚未确认，请重新检查", checkedAt: date)
        }
        return DesktopHealthSnapshot(
            components: [
                accountFresh ? connectorHealth(device, checkedAt: checkedAt) : pending(.desktopAgent, checkedAt),
                accountFresh ? gatewayHealth(device, checkedAt: checkedAt) : pending(.gateway, checkedAt),
                reportFresh ? hermesHealth(device, checkedAt: reportedAt) : pending(.hermes, reportedAt),
                reportFresh ? endToEndHealth(device, checkedAt: reportedAt) : pending(.endToEnd, reportedAt),
            ],
            checkedAt: checkedAt
        )
    }

    public static func isFresh(_ date: Date?, now: Date = Date()) -> Bool {
        guard let date else { return false }
        return (-5...180).contains(now.timeIntervalSince(date))
    }

    public static func deviceSummary(_ device: AccountDevice, checkedAt: Date?, now: Date = Date()) -> String {
        guard isFresh(checkedAt, now: now) else { return "设备状态待刷新" }
        if let connection = device.connection, connection.version == 1 { return connection.summary }
        let connector = device.connector.online ? "Desktop 在线" : "Desktop 离线"
        guard isFresh(device.endToEnd.checkedAt.flatMap(parseCanonicalTimestamp), now: now) else {
            return "\(connector) · Hermes 状态待更新"
        }
        let hermes = device.hermes.reachable.map { $0 ? "Hermes 可访问" : "Hermes 不可访问" } ?? "Hermes 未确认"
        return "\(connector) · \(hermes)"
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
        case .some(true):
            let detail = device.hermes.version.map { "可访问 · \($0)" } ?? "可访问"
            return ComponentHealth(
                component: .hermes,
                level: .healthy,
                detail: detail,
                checkedAt: checkedAt
            )
        case .some(false):
            let issue = DesktopIssue(code: .hermesUnavailable)
            return ComponentHealth(
                component: .hermes,
                level: .failed,
                detail: issue.displayChinese,
                checkedAt: checkedAt,
                issue: issue
            )
        case .none:
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
        if let connection = device.connection, connection.version == 1 {
            return ComponentHealth(component: .endToEnd, level: connection.level, detail: connection.summary, checkedAt: checkedAt)
        }
        switch device.endToEnd.healthy {
        case .some(true):
            return ComponentHealth(
                component: .endToEnd,
                level: .healthy,
                detail: "账号链路正常",
                checkedAt: checkedAt
            )
        case .some(false):
            let issue = DesktopIssue(code: .relayFailure)
            return ComponentHealth(
                component: .endToEnd,
                level: .failed,
                detail: issue.displayChinese,
                checkedAt: checkedAt,
                issue: issue
            )
        case .none:
            return ComponentHealth(
                component: .endToEnd,
                level: .degraded,
                detail: "等待端到端状态上报",
                checkedAt: checkedAt
            )
        }
    }
}
