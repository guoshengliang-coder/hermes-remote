import Foundation

public extension DesktopHermesUpgradeTarget {
    /// Reviewed translation of the official release body, bound to the exact release commit.
    /// Future releases show their own original text; never reuse an older version's promises.
    var highlightsChinese: [String]? {
        guard version == "0.22.5", commit == "bf0203a794a07aac3ff764972ab9087baa4e4f4e",
              let releaseNotes, releaseNotes.contains("long Claude DirectSDK answers"),
              releaseNotes.contains("drag-to-pin"), releaseNotes.contains("pluggable delegation backends") else { return nil }
        return [
            "修复 Claude DirectSDK 长回答被停顿计时器过早截断的问题。",
            "子代理可按模型映射或配置选择后端，并支持按会话切换。",
            "OpenClaw 迁移、MCP 服务端等能力移至插件；使用这些功能时请查看官方配置说明。",
            "工作目录被删除时提供明确提示，并更新多项依赖。"
        ]
    }

    var releaseHighlights: [String] {
        if let highlightsChinese { return highlightsChinese }
        guard let releaseNotes, !releaseNotes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        // Prefer actual bullet content; otherwise preserve bounded official paragraphs verbatim.
        let lines = releaseNotes.components(separatedBy: .newlines)
        let bullets = lines.filter { $0.hasPrefix("- ") || $0.hasPrefix("* ") }
            .map { String($0.dropFirst(2)).trimmingCharacters(in: .whitespaces) }
        let content = bullets.isEmpty ? lines.filter {
            !$0.isEmpty && !$0.hasPrefix("#") && !$0.hasPrefix("<!--") && !$0.hasPrefix("|")
        } : bullets
        return Array(content.prefix(5)).map { String($0.prefix(600)) }
    }
}
