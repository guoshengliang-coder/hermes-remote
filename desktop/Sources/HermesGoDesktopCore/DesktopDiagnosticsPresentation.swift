import Foundation

public struct DesktopDiagnosticCheck: Identifiable, Equatable, Sendable {
    public enum ID: String, Sendable { case cloud, background, localHermes, account, connector, reportedHermes, endToEnd }
    public let id: ID
    public let title: String
    public let source: String
    public let health: ComponentHealth
    public var needsAttention: Bool { health.level == .failed || health.level == .degraded }
    public var result: String {
        switch health.level {
        case .healthy: "通过"
        case .failed: "未通过"
        case .degraded: "需要确认"
        case .checking, .unavailable: "待确认"
        }
    }
}

/// One result set includes both local probes and this Mac's cloud reports. Neither can mask the
/// other. Unknown, stale and future-dated evidence remains pending, including optional E2E health.
public struct DesktopDiagnosticsPresentation: Equatable, Sendable {
    public let checks: [DesktopDiagnosticCheck]
    public var passedCount: Int { checks.filter { $0.health.level == .healthy }.count }
    public var problemCount: Int { checks.filter(\.needsAttention).count }
    public var pendingCount: Int { checks.count - passedCount - problemCount }
    public var summary: String {
        if problemCount > 0 { return "\(problemCount) 项需要处理 · \(passedCount) 项通过 · \(pendingCount) 项待确认" }
        if pendingCount > 0 { return "\(passedCount) 项通过 · \(pendingCount) 项待确认" }
        return "\(passedCount) 项检查全部通过"
    }

    public init(gateway: ComponentHealth, agent: DesktopAgentPresentation,
                localHermes: DesktopLocalHermesPresentation, localHealth: ComponentHealth?,
                accountState: DesktopAccountState, accountCheckedAt: Date?, accountIssue: DesktopIssue?,
                repairNeeded: Bool, now: Date = Date()) {
        func pending(_ component: HealthComponent, _ detail: String = "尚无有效结果，请重新诊断。", _ date: Date? = nil) -> ComponentHealth {
            ComponentHealth(component: component, level: .unavailable, detail: detail, checkedAt: date)
        }
        func fresh(_ health: ComponentHealth) -> ComponentHealth {
            guard AccountDeviceHealth.isFresh(health.checkedAt, now: now) else {
                return pending(health.component, health.checkedAt == nil ? "尚未检查" : "检查结果已过期，请重新诊断。", health.checkedAt)
            }
            return health
        }
        let dashboard: AccountDashboard?
        if case .signedIn(let value) = accountState { dashboard = value } else { dashboard = nil }
        let validAccount = dashboard != nil && accountIssue == nil && AccountDeviceHealth.isFresh(accountCheckedAt, now: now)
        let account = accountIssue.map {
            ComponentHealth(component: .gateway, level: .failed, detail: $0.displayChinese, checkedAt: accountCheckedAt, issue: $0)
        } ?? (validAccount ? ComponentHealth(component: .gateway, level: .healthy, detail: "这台 Desktop 的账号服务请求成功。", checkedAt: accountCheckedAt)
              : pending(.gateway, dashboard == nil ? "登录账号后验证。" : "账号结果待刷新。", accountCheckedAt))
        let remote = validAccount ? dashboard?.localDevice.map { AccountDeviceHealth.snapshot(for: $0, checkedAt: accountCheckedAt!, now: now) } : nil
        let background = repairNeeded ? ComponentHealth(component: .desktopAgent, level: .failed,
            detail: "后台启动项或安装记录需要修复。", checkedAt: agent.health.checkedAt,
            issue: DesktopIssue(code: .managedServicesNeedRepair)) : fresh(agent.health)
        let local = localHealth.map(fresh) ?? ComponentHealth(component: .hermes, level: localHermes.level == .healthy ? .checking : localHermes.level,
            detail: localHermes.detail)
        checks = [
            .init(id: .cloud, title: "云端连接", source: "本机直连云端 · /health", health: fresh(gateway)),
            .init(id: .background, title: "Desktop 后台服务", source: "本机安装与启动项检查", health: background),
            .init(id: .localHermes, title: "本机 Hermes", source: "本机认证接口检查", health: local),
            .init(id: .account, title: "账号服务", source: "本机账号 API 请求", health: account),
            .init(id: .connector, title: "Desktop 远程连接", source: "云端记录 · 仅这台 Mac", health: remote?.component(.desktopAgent) ?? pending(.desktopAgent)),
            .init(id: .reportedHermes, title: "Hermes 远程可达性", source: "这台 Mac 的 Connector 上报", health: remote?.component(.hermes) ?? pending(.hermes)),
            .init(id: .endToEnd, title: "手机访问链路", source: "云端端到端验证 · 非手机实测", health: remote?.component(.endToEnd) ?? pending(.endToEnd)),
        ]
    }
}
