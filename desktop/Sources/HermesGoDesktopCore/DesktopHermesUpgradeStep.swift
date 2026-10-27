import Foundation

/// Completed steps, not elapsed-time or download percentages. Each stage can take a different time.
public enum DesktopHermesUpgradeStep: String, CaseIterable, Sendable {
    case download, repository, venv
    case dependencies = "python-deps"
    case config, products, complete, verify, diagnostics

    public static let installerSteps: [Self] = [.repository, .venv, .dependencies, .config, .products, .complete]
    public var index: Int { Self.allCases.firstIndex(of: self)! }
    public var titleChinese: String {
        switch self {
        case .download: "下载官方升级程序"
        case .verify: "确认升级后的 Hermes 版本"
        case .diagnostics: "运行 Hermes Go 检测"
        default: DesktopHermesInstallerStage(name: rawValue, title: rawValue, category: "", needsUserInput: false).titleChinese
        }
    }

    public static func elapsed(since start: Date, at now: Date) -> String {
        let seconds = max(0, Int(now.timeIntervalSince(start)))
        if seconds < 60 { return "\(seconds) 秒" }
        if seconds < 3600 { return "\(seconds / 60) 分 \(seconds % 60) 秒" }
        return "\(seconds / 3600) 小时 \(seconds % 3600 / 60) 分"
    }
}
