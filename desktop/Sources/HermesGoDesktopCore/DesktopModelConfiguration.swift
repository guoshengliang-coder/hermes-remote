import Foundation

/// Metadata is not a credential check or a successful model request. Missing optional endpoints
/// stay unknown; no credentials, provider environment or conversation data enter this value.
public enum DesktopModelConfiguration: Equatable, Sendable {
    case unknown
    case missing
    case selected(String)

    public static func parse(_ data: Data) -> Self {
        guard data.count <= 256 * 1024,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let model = object["model"] as? String else { return .unknown }
        let name = model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard name.count <= 256, !name.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { return .unknown }
        return name.isEmpty ? .missing : .selected(SecretRedactor.redact(name))
    }
    /// Match the managed serve profile even when the interactive CLI has a sticky selection.
    public static func setupCommand(executable: String) -> String {
        "'" + executable.replacingOccurrences(of: "'", with: "'\"'\"'") + "' --profile default setup"
    }

    public func current(health: ComponentHealth?, now: Date = Date()) -> Self {
        guard let health, health.level == .healthy, AccountDeviceHealth.isFresh(health.checkedAt, now: now) else { return .unknown }
        return self
    }

    public var statusText: String {
        switch self {
        case .unknown: "待确认"
        case .missing: "未配置"
        case .selected: "已配置"
        }
    }
    public var level: HealthLevel {
        switch self {
        case .unknown: .unavailable
        case .missing: .degraded
        case .selected: .healthy
        }
    }

    public var title: String {
        switch self {
        case .unknown: "模型配置待确认"
        case .missing: "尚未选择模型"
        case .selected: "已选择模型"
        }
    }
    public var detail: String {
        switch self {
        case .unknown: "暂时无法确认模型配置，可在 Hermes 中查看。此状态不表示尚未配置模型。"
        case .missing: "先配置模型服务，才能开始 AI 任务。"
        case .selected(let name): "\(name) · 模型凭据与实际任务可用性尚未验证。"
        }
    }
}
