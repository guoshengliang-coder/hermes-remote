public enum DesktopBindingAction: Equatable, Sendable {
    case createFirst
    case confirmFirst(id: String, generation: Int)
    case createReplacement
    case confirmReplacement(requestID: String)
    case unbind
    case refresh
}

public enum DesktopBindingActionRole: Equatable, Sendable {
    case normal
    case destructive
}

public struct DesktopBindingActionDescriptor: Equatable, Identifiable, Sendable {
    public let id: String
    public let action: DesktopBindingAction
    public let label: String
    public let accessibilityHint: String
    public let role: DesktopBindingActionRole

    public init(
        id: String,
        action: DesktopBindingAction,
        label: String,
        accessibilityHint: String,
        role: DesktopBindingActionRole = .normal
    ) {
        self.id = id
        self.action = action
        self.label = label
        self.accessibilityHint = accessibilityHint
        self.role = role
    }
}

public struct DesktopBindingPresentation: Equatable, Sendable {
    public let symbol: String
    public let title: String
    public let detail: String
    public let safetyNote: String
    public let actions: [DesktopBindingActionDescriptor]

    public init(
        symbol: String,
        title: String,
        detail: String,
        safetyNote: String,
        actions: [DesktopBindingActionDescriptor]
    ) {
        self.symbol = symbol
        self.title = title
        self.detail = detail
        self.safetyNote = safetyNote
        self.actions = actions
    }
}

public extension AccountBindingSnapshot {
    var presentation: DesktopBindingPresentation {
        switch bindingState {
        case .noBinding:
            DesktopBindingPresentation(
                symbol: "desktopcomputer",
                title: "尚未建立账号绑定",
                detail: "可以先登记这台 Desktop 的安全身份；登记不会停止或替换旧 Connector。",
                safetyNote: "候选身份必须通过 Connector 密钥和健康预检后才能启用。",
                actions: [
                    DesktopBindingActionDescriptor(
                        id: "create-first",
                        action: .createFirst,
                        label: "准备 Desktop 绑定",
                        accessibilityHint: "创建短期候选绑定，旧 Connector 保持运行"
                    ),
                ]
            )
        case .bindingPending:
            pendingFirstPresentation
        case .bound:
            boundPresentation
        case .replacementPending:
            replacementPresentation
        case .revoked:
            DesktopBindingPresentation(
                symbol: "xmark.shield",
                title: "这台 Mac 的绑定已撤销",
                detail: "另一代 Desktop 身份已经生效；如需改用这台 Mac，请验证账号并准备替换。",
                safetyNote: "原来的活动连接会保持到新候选验证完成并最终确认。",
                actions: [
                    DesktopBindingActionDescriptor(
                        id: "create-replacement",
                        action: .createReplacement,
                        label: "验证并准备替换",
                        accessibilityHint: "将在浏览器重新验证 Google 账号，暂不切换活动连接"
                    ),
                ]
            )
        case .unknown:
            DesktopBindingPresentation(
                symbol: "questionmark.circle",
                title: "需要刷新绑定状态",
                detail: "Gateway 返回了当前版本尚不认识的绑定状态。",
                safetyNote: "未确认状态前不会执行绑定变更。",
                actions: [refreshAction]
            )
        }
    }

    private var pendingFirstPresentation: DesktopBindingPresentation {
        let ready = keyProved == true && healthVerified == true
        let confirmation: DesktopBindingActionDescriptor? = if ready, let id, let generation {
            DesktopBindingActionDescriptor(
                id: "confirm-first",
                action: .confirmFirst(id: id, generation: generation),
                label: "确认启用绑定",
                accessibilityHint: "候选身份和健康检查已通过，将启用这台 Desktop"
            )
        } else {
            nil
        }
        return DesktopBindingPresentation(
            symbol: ready ? "checkmark.shield" : "clock",
            title: ready ? "候选 Desktop 已通过预检" : "正在等待 Connector 验证",
            detail: ready
                ? "密钥与健康检查均已通过，可以明确确认启用。"
                : "候选绑定尚未完成密钥与健康检查。",
            safetyNote: "确认前旧 Connector 和旧版手机连接保持原样。",
            actions: confirmation.map { [$0] } ?? [refreshAction]
        )
    }

    private var boundPresentation: DesktopBindingPresentation {
        let online = binding?.connector.online == true
        return DesktopBindingPresentation(
            symbol: online ? "checkmark.circle.fill" : "exclamationmark.triangle.fill",
            title: online ? "连接正常" : "Connector 当前离线",
            detail: online
                ? "账号已绑定这台 Desktop，手机可以共享访问同一个 Hermes。"
                : "账号绑定仍保留；打开对应 Mac 上的 Connector 即可恢复。",
            safetyNote: "更换会先验证新候选；解绑不会退出手机或 Desktop 账号，也不会修改 Hermes。",
            actions: [
                DesktopBindingActionDescriptor(
                    id: "create-replacement",
                    action: .createReplacement,
                    label: "准备更换 Mac",
                    accessibilityHint: "将在浏览器重新验证账号，原连接暂时保持工作"
                ),
                DesktopBindingActionDescriptor(
                    id: "unbind",
                    action: .unbind,
                    label: "解除账号绑定",
                    accessibilityHint: "需要重新验证账号，将撤销 Connector 远程访问",
                    role: .destructive
                ),
            ]
        )
    }

    private var replacementPresentation: DesktopBindingPresentation {
        let ready = candidate?.keyProved == true && candidate?.healthVerified == true
        let confirmation: DesktopBindingActionDescriptor? = if ready, let id {
            DesktopBindingActionDescriptor(
                id: "confirm-replacement",
                action: .confirmReplacement(requestID: id),
                label: "确认更换 Mac",
                accessibilityHint: "新候选已通过预检，将原子切换到新的 Desktop"
            )
        } else {
            nil
        }
        return DesktopBindingPresentation(
            symbol: ready ? "checkmark.shield" : "arrow.triangle.2.circlepath",
            title: ready ? "新 Mac 已通过预检" : "等待新 Mac 完成验证",
            detail: ready
                ? "新候选的密钥与健康检查均已通过，可以执行最终切换。"
                : "新候选仍在进行密钥或健康检查。",
            safetyNote: "最终确认提交前，原来的 Desktop 连接继续工作。",
            actions: confirmation.map { [$0] } ?? [refreshAction]
        )
    }

    private var refreshAction: DesktopBindingActionDescriptor {
        DesktopBindingActionDescriptor(
            id: "refresh",
            action: .refresh,
            label: "刷新验证状态",
            accessibilityHint: "从 Gateway 重新读取候选密钥和健康检查状态"
        )
    }
}
