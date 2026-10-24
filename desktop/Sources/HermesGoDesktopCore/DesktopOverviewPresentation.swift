import Foundation

public struct DesktopOverviewStatus: Equatable, Sendable {
    public let level: HealthLevel
    public let title: String
    public let detail: String
    public let checkedAt: Date?
}

/// Local evidence only: selectedDevice is deliberately never read. A health check is useful for
/// three monitor intervals plus one account refresh; after three minutes it must be revalidated.
public struct DesktopOverviewPresentation: Equatable, Sendable {
    public let cloud: DesktopOverviewStatus
    public let desktop: DesktopOverviewStatus
    public let hermes: DesktopOverviewStatus
    public let accountService: String
    public let background: String
    public let remoteConnection: String
    public let remoteAccess: String
    public let remoteAccessCheckedAt: Date?
    public let connectionCheckedAt: Date?
    public let title: String
    public let detail: String
    public let level: HealthLevel
    public let needsLogin: Bool

    public init(gateway: ComponentHealth, agent: DesktopAgentPresentation,
                localHermes: DesktopLocalHermesPresentation, localHealth: ComponentHealth?,
                accountState: DesktopAccountState, accountCheckedAt: Date?, hasAccountIssue: Bool,
                manageOnly: Bool, operationInProgress: Bool, now: Date = Date()) {
        func fresh(_ date: Date?) -> Bool {
            AccountDeviceHealth.isFresh(date, now: now)
        }
        func status(_ level: HealthLevel, _ title: String, _ detail: String, _ date: Date?) -> DesktopOverviewStatus {
            DesktopOverviewStatus(level: level, title: title, detail: detail, checkedAt: date)
        }
        let dashboard: AccountDashboard?
        if case .signedIn(let value) = accountState { dashboard = value } else { dashboard = nil }
        switch accountState {
        case .signedOut, .needsSignIn, .signingIn: needsLogin = true
        default: needsLogin = false
        }
        let accountFresh = fresh(accountCheckedAt) && !hasAccountIssue && dashboard != nil
        accountService = needsLogin ? "登录后验证" : accountFresh ? "响应正常" : "尚未确认"
        if gateway.level == .checking || !fresh(gateway.checkedAt) {
            cloud = status(.checking, "尚未确认", "等待这台 Mac 的云端连接检查。", gateway.checkedAt)
        } else if gateway.level == .healthy {
            cloud = status(hasAccountIssue && !needsLogin ? .degraded : .healthy,
                           hasAccountIssue && !needsLogin ? "账号服务待确认" : "连接正常",
                           "这台 Mac 可以连接 Hermes GO 云端。账号服务的验证结果单独显示。", gateway.checkedAt)
        } else {
            cloud = status(.failed, "无法连接", "这台 Mac 暂时无法连接云端，请检查网络后重试。", gateway.checkedAt)
        }

        let identityVerified: Bool
        if case .managedVerified = agent.mode { identityVerified = true } else { identityVerified = false }
        let binding = dashboard?.binding.state == "bound" ? dashboard?.binding.binding : nil
        remoteAccessCheckedAt = binding?.endToEnd.checkedAt.flatMap(parseCanonicalTimestamp)
        let reportFresh = fresh(remoteAccessCheckedAt)
        remoteAccess = !accountFresh || !reportFresh ? "尚未验证" :
            binding?.endToEnd.healthy == true ? "已通过" : binding?.endToEnd.healthy == false ? "未通过" : "尚未验证"
        connectionCheckedAt = accountCheckedAt
        remoteConnection = !accountFresh ? "尚未确认" : binding == nil ? "尚未绑定" :
            binding?.connector.online == true ? "已连接云端" : "未连接云端"
        if operationInProgress {
            background = "操作进行中"
            desktop = status(.checking, "正在处理", "Desktop 正在安装、更新或修复，请等待当前操作完成。", agent.health.checkedAt)
        } else if manageOnly {
            background = "未启用"
            desktop = status(.unavailable, "仅用于管理", "这台 Mac 尚未启用远程连接；其他 Mac 可在设备页管理。", agent.health.checkedAt)
        } else if !fresh(agent.health.checkedAt) || agent.mode == .checking {
            background = "尚未确认"
            desktop = status(.checking, "尚未确认", "正在检查这台 Mac 的 Desktop 后台。", agent.health.checkedAt)
        } else {
            switch agent.mode {
            case .managedVerified, .managedUnverified:
                background = "正在运行"
                if !accountFresh || binding == nil || !identityVerified {
                    desktop = status(.degraded, "连接待验证", "后台正在运行，仍需确认这台 Mac 的账号与云端连接。", agent.health.checkedAt)
                } else if binding?.connector.online == true && reportFresh && binding?.endToEnd.healthy == false {
                    desktop = status(.failed, "远程访问受限", "后台已连接云端，但手机访问验证未通过，请查看诊断。", agent.health.checkedAt)
                } else if binding?.connector.online == true {
                    desktop = status(.healthy, "运行正常", "后台正在运行，且这台 Mac 已连接云端。关闭窗口后连接继续保留。", agent.health.checkedAt)
                } else {
                    desktop = status(.failed, "远程连接中断", "后台已启动，但这台 Mac 尚未连接云端，手机暂时无法访问。", agent.health.checkedAt)
                }
            case .attentionRequired:
                background = "需要检查"
                desktop = status(.failed, "需要修复", "这台 Mac 的后台运行或安装记录需要处理，请查看下方原因。", agent.health.checkedAt)
            case .unavailable:
                background = "未设置"
                desktop = status(.unavailable, "尚未设置", "完成这台 Mac 的连接设置后，才能从手机远程访问。", agent.health.checkedAt)
            case .checking:
                background = "检查中"
                desktop = status(.checking, "检查中", "正在检查 Desktop 后台。", nil)
            }
        }
        if let localHealth, !fresh(localHealth.checkedAt) {
            hermes = status(.checking, "等待重新检查", "上次本机验证结果已过期，请刷新状态。", localHealth.checkedAt)
        } else if localHermes.canOpen {
            hermes = status(.healthy, "可以使用", localHermes.detail, localHealth?.checkedAt)
        } else {
            hermes = status(localHermes.level, localHermes.title, localHermes.detail, localHealth?.checkedAt)
        }
        let remoteVerified = accountFresh && reportFresh && binding?.endToEnd.healthy == true
        if needsLogin {
            title = hermes.level == .healthy ? "本机可用，登录后管理远程连接" : "登录后连接这台 Mac"
            detail = "从左下角登录账号，即可继续设置或管理远程访问。已有后台服务不会因退出账号而停止。"
            level = .degraded
        } else if manageOnly {
            title = "这台 Mac 仅用于管理"
            detail = "可在设备页管理其他 Mac；启用本机连接后，手机也能访问这台 Mac。"
            level = .degraded
        } else if hermes.level == .failed {
            title = "本机 Hermes 暂不可用"
            detail = "本机可用性检查未通过，任务可能无法开始或继续。请查看本机 Hermes 的诊断。"
            level = .failed
        } else if cloud.level == .failed || desktop.level == .failed {
            title = hermes.level == .healthy ? "本机可用，远程访问中断" : "远程访问需要处理"
            detail = cloud.level == .failed ? cloud.detail : desktop.detail
            level = .failed
        } else if cloud.level == .healthy && desktop.level == .healthy && hermes.level == .healthy && remoteVerified {
            title = "本机与远程访问均正常"
            detail = "云端、Desktop 和本机 Hermes 均已验证，手机可以访问这台 Mac。"
            level = .healthy
        } else {
            title = hermes.level == .healthy ? "本机可用，远程状态待确认" : "正在确认可用状态"
            detail = "请查看下方各部分的检测结果；尚未验证或过期的状态不会显示为正常。"
            level = .checking
        }
    }
}
