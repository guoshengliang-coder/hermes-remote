import Foundation

public enum DesktopBootstrapReadiness: String, Equatable, Sendable {
    case checking
    case existingServicePreserved
    case existingServiceNeedsAttention
    case waitingForSignedRelease
    case readyForManagedInstall
    case managedInstallActive
}

public enum DesktopBootstrapStepKind: String, Equatable, Sendable {
    case inspectExisting
    case preserveExisting
    case verifySignedRelease
    case installHermes
    case configureLocalProvider
    case installConnector
    case bindAccount
    case enableAutomaticStartup
    case verifyEndToEnd
}

public struct DesktopBootstrapStep: Equatable, Sendable, Identifiable {
    public let kind: DesktopBootstrapStepKind
    public let titleChinese: String
    public let changesMachine: Bool

    public var id: String { kind.rawValue }
}

public struct DesktopBootstrapPlan: Equatable, Sendable {
    public let readiness: DesktopBootstrapReadiness
    public let titleChinese: String
    public let detailChinese: String
    public let steps: [DesktopBootstrapStep]
    public let requiresConfirmation: Bool
    public let canBegin: Bool

    public static let checking = DesktopBootstrapPlan(
        readiness: .checking,
        titleChinese: "正在检查本机安装",
        detailChinese: "只读取 Hermes、Connector 与自动启动状态。",
        steps: [],
        requiresConfirmation: false,
        canBegin: false
    )
}

public enum DesktopBootstrapPlanner {
    public static func plan(
        legacy: LegacyConnectorSnapshot,
        hermesReachable: Bool,
        managedInstallAvailability: DesktopManagedBootstrapAvailability,
        managedInstallation: DesktopManagedBootstrapInstallationStatus = .absent
    ) -> DesktopBootstrapPlan {
        switch managedInstallation {
        case .active(let releaseVersion, _, _):
            return plan(
                readiness: .managedInstallActive,
                title: "受管连接正在运行",
                detail: "Hermes Go \(releaseVersion) 的 Hermes Server 与 Connector 已由 Desktop 管理。",
                steps: [.inspectExisting, .verifyEndToEnd],
                canBegin: false
            )
        case .interrupted(_, let state):
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "检测到未完成的受管安装",
                detail: "上次操作停在 \(state.rawValue)，Desktop 会先恢复到已知安全状态，不会开始第二次安装。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        case .attentionRequired:
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "受管连接需要人工检查",
                detail: "自动恢复无法证明唯一安全连接，已阻止再次安装。请复制诊断信息后检查服务状态。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        case .inconsistent:
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "受管服务状态不一致",
                detail: "启动项与迁移记录不一致，Desktop 已阻止再次安装，避免产生重复服务。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        case .absent:
            break
        }

        if legacy.isRunning && !legacy.isInstalled {
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "检测到不一致的 Connector 状态",
                detail: "后台任务仍在运行，但找不到对应安装。已停止自动设置，避免产生重复 Connector。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        }

        if legacy.isInstalled {
            let runningDetail = legacy.isRunning
                ? "现有 Connector 正在运行，Desktop 会保持它不变；迁移前需要单独确认并准备可回滚版本。"
                : "检测到现有 Connector，但它当前未运行。Desktop 不会覆盖或另起一个实例。"
            return plan(
                readiness: legacy.isRunning ? .existingServicePreserved : .existingServiceNeedsAttention,
                title: legacy.isRunning ? "已保护现有 Hermes 服务" : "现有 Connector 需要检查",
                detail: hermesReachable
                    ? "\(runningDetail) 本机 Hermes 当前可访问。"
                    : "\(runningDetail) 本机 Hermes 当前未通过访问检查。",
                steps: [.inspectExisting, .preserveExisting, .bindAccount, .verifyEndToEnd],
                canBegin: false
            )
        }

        if hermesReachable {
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "检测到未托管的 Hermes 服务",
                detail: "本机 9119 端口已有 Hermes 响应，但没有可识别的 Connector。Desktop 不会覆盖或停止这个进程，请先确认它的启动方式。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        }

        guard managedInstallAvailability == .ready else {
            let detail = switch managedInstallAvailability {
            case .disabled:
                "未检测到旧 Connector。托管安装通道当前未启用，Desktop 会保持只读。"
            case .invalidConfiguration:
                "未检测到旧 Connector。托管安装配置不完整或无效，Desktop 已停止安装入口。"
            case .serverCapabilityUnavailable:
                "未检测到旧 Connector。Gateway 尚未声明托管安装能力，Desktop 会保持只读。"
            case .runtimeContractMismatch:
                "未检测到旧 Connector。Gateway 与 Desktop 的 Hermes 运行合同不一致，安装入口已关闭。"
            case .ready:
                preconditionFailure("ready availability must pass the guard")
            }
            return plan(
                readiness: .waitingForSignedRelease,
                title: "这台 Mac 可以开始全新设置",
                detail: detail,
                steps: [.inspectExisting, .verifySignedRelease],
                canBegin: false
            )
        }

        return plan(
            readiness: .readyForManagedInstall,
            title: "已准备好引导安装",
            detail: "开始前会再次展示所有改动；模型服务凭据只保存在这台 Mac。",
            steps: [
                .inspectExisting,
                .verifySignedRelease,
                .installHermes,
                .configureLocalProvider,
                .installConnector,
                .bindAccount,
                .enableAutomaticStartup,
                .verifyEndToEnd,
            ],
            canBegin: true
        )
    }

    private static func plan(
        readiness: DesktopBootstrapReadiness,
        title: String,
        detail: String,
        steps: [DesktopBootstrapStepKind],
        canBegin: Bool
    ) -> DesktopBootstrapPlan {
        let mapped = steps.map(step)
        return DesktopBootstrapPlan(
            readiness: readiness,
            titleChinese: title,
            detailChinese: detail,
            steps: mapped,
            requiresConfirmation: mapped.contains(where: \.changesMachine),
            canBegin: canBegin
        )
    }

    private static func step(_ kind: DesktopBootstrapStepKind) -> DesktopBootstrapStep {
        let title: String = switch kind {
        case .inspectExisting: "检查现有安装与进程"
        case .preserveExisting: "保留现有 Connector 与配置"
        case .verifySignedRelease: "验证签名、版本与校验和"
        case .installHermes: "原子安装或升级 Hermes Server"
        case .configureLocalProvider: "使用本机 Hermes 配置目录"
        case .installConnector: "安装唯一的 Connector"
        case .bindAccount: "将这台 Mac 绑定到当前账号"
        case .enableAutomaticStartup: "启用用户级自动启动"
        case .verifyEndToEnd: "运行端到端健康检查"
        }
        let changesMachine: Bool = switch kind {
        case .installHermes, .configureLocalProvider, .installConnector,
             .bindAccount, .enableAutomaticStartup:
            true
        case .inspectExisting, .preserveExisting, .verifySignedRelease, .verifyEndToEnd:
            false
        }
        return DesktopBootstrapStep(kind: kind, titleChinese: title, changesMachine: changesMachine)
    }
}
