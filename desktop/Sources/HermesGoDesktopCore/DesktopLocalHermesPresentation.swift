import Foundation

/// Installation is not evidence that Hermes can serve requests. Only the authenticated probe of
/// this Mac can establish usability; a selected remote device must never supply this evidence.
public struct DesktopLocalHermesPresentation: Equatable, Sendable {
    public let level: HealthLevel
    public let title: String
    public let detail: String
    public let version: String
    public let canOpen: Bool
    public let managementDetail: String?
    public let managementLabel: String?

    public init(detection: DesktopLocalHermesDetection?, health: ComponentHealth?, hasManagedInstallation: Bool, now: Date = Date()) {
        let health = health.flatMap { AccountDeviceHealth.isFresh($0.checkedAt, now: now) ? $0 : nil }
        version = detection?.installation?.version ?? "版本未识别"
        managementLabel = hasManagedInstallation ? "由 Desktop 管理" : nil
        if case .unsupported = detection {
            managementDetail = "安装信息未通过识别，自动升级暂不可用。请查看检测原因。（HR-MIGRATE-008）"
        } else { managementDetail = nil }
        canOpen = health?.level == .healthy
        if canOpen {
            level = managementDetail == nil ? .healthy : .degraded
            title = managementDetail == nil ? "服务已就绪" : "服务可用，升级受限"
            detail = "Hermes 服务与本机认证检查已通过，模型配置单独显示。"
        } else if let health, health.level != .checking {
            level = health.level == .unavailable ? .checking : .failed
            title = "暂时无法使用"
            detail = "本机 Hermes 尚未通过可用性检查，任务可能无法开始或继续。请运行诊断。"
        } else {
            switch detection {
            case .usable:
                level = .checking
                title = "已安装，待验证"
                detail = "已找到 Hermes；完成这台 Mac 的连接设置后才能验证实际可用性。"
            case .unsupported:
                level = .failed
                title = "安装需要处理"
                detail = "已找到 Hermes，但当前安装无法直接使用。请在诊断中查看原因。"
            case .absent:
                level = hasManagedInstallation ? .checking : .unavailable
                title = hasManagedInstallation ? "等待检查" : "尚未安装"
                detail = hasManagedInstallation ? "Desktop 正在检查本机 Hermes。" : "连接这台 Mac 时可以安装 Hermes，已有数据会保留。"
            case nil:
                level = .checking
                title = "正在检查"
                detail = "正在读取这台 Mac 的安装情况。"
            }
        }
    }
}
