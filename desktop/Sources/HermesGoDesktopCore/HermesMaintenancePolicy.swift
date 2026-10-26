import Foundation

/// Part of the signed validation identity. Missing or unknown policies must fail decoding;
/// no historical report implicitly gains the newly accepted support scope.
public enum HermesMaintenancePolicy: String, Codable, Equatable, Sendable {
    case goControlledWindowV1 = "go-controlled-window-v1"

    public var titleChinese: String { "GO 控制的维护窗口" }
    public var summaryChinese: String {
        "升级及恢复期间，请勿从其他入口启动 Hermes 或运行更新。发现现存外部更新或无法确认已停止的服务时，GO 会暂停操作。"
    }
    public var summaryEnglish: String {
        "During upgrade and recovery, do not start Hermes or run updates from other tools. GO pauses if an external update or a service that cannot be confirmed stopped is detected."
    }
    public var recoveryChinese: String {
        "关闭 Desktop 页面不会取消升级。发生中断时先检查原操作；新版产生的数据会保留，不会自动用旧备份覆盖。"
    }
    public var recoveryEnglish: String {
        "Closing the Desktop page does not cancel an upgrade. After an interruption, review the existing operation. Data created by the new version is retained and is never automatically overwritten with an old backup."
    }
}
