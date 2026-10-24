import Foundation

public enum DesktopBootstrapReadiness: String, Equatable, Sendable {
    case checking
    case existingServiceNeedsAttention
    case waitingForSignedRelease
    case readyForManagedInstall
    case readyToAdoptLocalHermes
    case managedUpgradeAvailable
    case managedInstallActive
}

public enum DesktopBootstrapStepKind: String, Equatable, Sendable {
    case inspectExisting
    case preserveExisting
    case verifySignedRelease
    case installHermes
    case adoptLocalHermes
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
        detailChinese: "正在检查 Desktop 连接与本机 Hermes。",
        steps: [],
        requiresConfirmation: false,
        canBegin: false
    )
}

public enum DesktopBootstrapPlanner {
    public static var hermesStatusURL: URL {
        URL(string: "/api/status", relativeTo: DesktopHermesRuntimeContract.serveV1.baseURL)!.absoluteURL
    }

    public static func plan(
        legacy: RetiredConnectorSnapshot,
        hermesReachable: Bool,
        managedInstallAvailability: DesktopManagedBootstrapAvailability,
        managedInstallation: DesktopManagedBootstrapInstallationStatus = .absent,
        targetReleaseVersion: String? = nil,
        localHermes: DesktopLocalHermesInstallation? = nil
    ) -> DesktopBootstrapPlan {
        switch managedInstallation {
        case .active(let releaseVersion, _, _, _):
            if managedInstallAvailability == .ready,
               let targetReleaseVersion,
               isNewer(targetReleaseVersion, than: releaseVersion) {
                return plan(
                    readiness: .managedUpgradeAvailable,
                    title: "Desktop 有可用更新",
                    detail: "更新会保留账号、设备绑定和本机数据；连接和任务可能短暂中断。请通过 Desktop 更新入口继续。",
                    steps: [
                        .inspectExisting,
                        .preserveExisting,
                        .verifySignedRelease,
                        .installHermes,
                        .installConnector,
                        .enableAutomaticStartup,
                        .verifyEndToEnd,
                    ],
                    canBegin: true
                )
            }
            return plan(
                readiness: .managedInstallActive,
                title: "Desktop 连接正在运行",
                detail: "Desktop 已完成设置，正在管理这台 Mac 的后台连接。",
                steps: [.inspectExisting, .verifyEndToEnd],
                canBegin: false
            )
        case .interrupted:
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "Desktop 设置尚未完成",
                detail: "Desktop 将先恢复上次未完成的设置。具体进度可在诊断中查看。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        case .attentionRequired:
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "Desktop 连接需要检查",
                detail: "自动恢复无法证明唯一安全连接，已阻止再次安装。请复制诊断信息后检查服务状态。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        case .inconsistent:
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "Desktop 运行状态需要检查",
                detail: "启动项与迁移记录不一致，Desktop 已阻止再次安装，避免产生重复服务。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        case .absent:
            break
        }

        if legacy.isInstalled || legacy.isRunning {
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "旧连接服务需要移除",
                detail: DesktopIssue(code: .retiredConnectionUnsupported).displayChinese,
                steps: [.inspectExisting],
                canBegin: false
            )
        }

        if let localHermes {
            guard managedInstallAvailability == .ready else {
                return plan(
                    readiness: .waitingForSignedRelease,
                    title: "已找到本机 Hermes，等待安装服务就绪",
                    detail: "Desktop 将使用本机已有的 Hermes；当前安装服务不可用，请稍后重试。",
                    steps: [.inspectExisting, .preserveExisting, .verifySignedRelease],
                    canBegin: false
                )
            }
            return plan(
                readiness: .readyToAdoptLocalHermes,
                title: "已准备好接管本机 Hermes",
                detail: "这台 Mac 已有 Hermes \(localHermes.version)。Desktop 将使用这份安装并保留配置与数据；连接前会检查现有服务，发生冲突时请打开诊断处理。",
                steps: [
                    .inspectExisting,
                    .preserveExisting,
                    .verifySignedRelease,
                    .adoptLocalHermes,
                    .configureLocalProvider,
                    .installConnector,
                    .bindAccount,
                    .enableAutomaticStartup,
                    .verifyEndToEnd,
                ],
                canBegin: true
            )
        }

        if hermesReachable {
            return plan(
                readiness: .existingServiceNeedsAttention,
                title: "检测到未托管的 Hermes 服务",
                detail: "已找到正在运行的 Hermes，但暂时无法确认其安装来源。请打开诊断检查启动方式，再继续设置。",
                steps: [.inspectExisting, .preserveExisting],
                canBegin: false
            )
        }

        guard managedInstallAvailability == .ready else {
            let detail = switch managedInstallAvailability {
            case .disabled:
                "托管安装通道当前未启用，Desktop 会保持只读。"
            case .invalidConfiguration:
                "托管安装配置不完整或无效，Desktop 已停止安装入口。"
            case .serverCapabilityUnavailable:
                "账号服务暂不支持设置这台 Mac，请稍后重试。"
            case .runtimeContractMismatch:
                "当前 Desktop 与账号服务不兼容，暂时无法安装。请打开诊断查看详情。"
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
        case .preserveExisting: "保留现有连接与配置"
        case .verifySignedRelease: "验证签名、版本与校验和"
        case .installHermes: "准备本机 Hermes"
        case .adoptLocalHermes: "接管本机已有的 Hermes 服务"
        case .configureLocalProvider: "使用本机 Hermes 配置目录"
        case .installConnector: "设置 Desktop 连接"
        case .bindAccount: "将这台 Mac 绑定到当前账号"
        case .enableAutomaticStartup: "启用用户级自动启动"
        case .verifyEndToEnd: "运行端到端健康检查"
        }
        let changesMachine: Bool = switch kind {
        case .installHermes, .adoptLocalHermes, .configureLocalProvider, .installConnector,
             .bindAccount, .enableAutomaticStartup:
            true
        case .inspectExisting, .preserveExisting, .verifySignedRelease, .verifyEndToEnd:
            false
        }
        return DesktopBootstrapStep(kind: kind, titleChinese: title, changesMachine: changesMachine)
    }

    private static func isNewer(_ candidate: String, than installed: String) -> Bool {
        guard let candidate = semanticVersion(candidate),
              let installed = semanticVersion(installed)
        else { return false }
        return candidate.lexicographicallyPrecedes(installed) == false && candidate != installed
    }

    private static func semanticVersion(_ value: String) -> [Int]? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        let parsed = parts.compactMap { Int($0) }
        guard parsed.count == 3,
              zip(parts, parsed).allSatisfy({ String($0.1) == $0.0 })
        else { return nil }
        return parsed
    }
}
