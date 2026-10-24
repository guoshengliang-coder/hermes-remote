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

    public var title: String {
        switch self {
        case .unknown: "模型配置待确认"
        case .missing: "尚未选择模型"
        case .selected: "已选择模型"
        }
    }
    public var detail: String {
        switch self {
        case .unknown: "服务连通不代表模型已配置。请在 Hermes 中完成模型与凭据配置，再开始任务。"
        case .missing: "先配置模型服务，才能开始 AI 任务。"
        case .selected(let name): "\(name) · 模型凭据与实际任务可用性尚未验证。"
        }
    }
}
