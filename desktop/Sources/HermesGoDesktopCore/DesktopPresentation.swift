public struct DesktopHealthPresentation: Equatable, Sendable {
    public let title: String
    public let detail: String
    public let level: HealthLevel

    public init(title: String, detail: String, level: HealthLevel) {
        self.title = title
        self.detail = detail
        self.level = level
    }
}

public extension DesktopHealthSnapshot {
    var presentation: DesktopHealthPresentation {
        switch overall {
        case .checking:
            DesktopHealthPresentation(
                title: "正在检查",
                detail: "正在确认旧 Connector 与连接链路",
                level: .checking
            )
        case .healthy:
            DesktopHealthPresentation(
                title: "工作正常",
                detail: "这台 Mac 正在安全连接 Hermes GO",
                level: .healthy
            )
        case .degraded:
            DesktopHealthPresentation(
                title: "部分功能受限",
                detail: "主链路可用，但有一项能力需要确认",
                level: .degraded
            )
        case .needsAttention:
            DesktopHealthPresentation(
                title: "需要处理",
                detail: "连接链路中有一项关键检查未通过",
                level: .failed
            )
        }
    }
}

public struct DesktopAccountPresentation: Equatable, Sendable {
    public let level: HealthLevel
    public let menuLabel: String
    public let settingsLabel: String

    public init(level: HealthLevel, menuLabel: String, settingsLabel: String) {
        self.level = level
        self.menuLabel = menuLabel
        self.settingsLabel = settingsLabel
    }
}

public extension DesktopAccountState {
    func presentation(hasIssue: Bool) -> DesktopAccountPresentation {
        if hasIssue, self == .checking {
            return DesktopAccountPresentation(
                level: .degraded,
                menuLabel: "需确认",
                settingsLabel: "需要确认"
            )
        }
        return switch self {
        case .signedIn:
            DesktopAccountPresentation(level: .healthy, menuLabel: "已登录", settingsLabel: "已登录")
        case .needsSignIn:
            DesktopAccountPresentation(level: .failed, menuLabel: "需登录", settingsLabel: "需要重新登录")
        case .checking, .signingIn:
            DesktopAccountPresentation(level: .checking, menuLabel: "检查中", settingsLabel: "正在检查")
        case .unavailable:
            DesktopAccountPresentation(level: .unavailable, menuLabel: "未开放", settingsLabel: "账号模式未开放")
        case .signedOut:
            DesktopAccountPresentation(level: .unavailable, menuLabel: "未登录", settingsLabel: "未登录")
        }
    }
}
