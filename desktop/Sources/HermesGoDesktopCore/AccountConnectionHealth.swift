import Foundation

public struct AccountConnectionHealth: Codable, Equatable, Sendable {
    public let version: Int
    public let business: String
    public let control: String
    public let local: String
    public let localReason: String
    public let healthAgeMs: Int

    public var summary: String {
        if business == "lost" { return control == "lost" ? "Desktop 离线" : "聊天连接暂不可用，正在重连" }
        if local == "unhealthy" { return "HR-CONN-006 · 本机 Hermes 不可用，请在 Mac 上检查" }
        if business == "suspect" || control == "suspect" { return "网络拥塞，文件传输已减速，可继续聊天" }
        if business == "recovering" || control == "recovering" { return "连接恢复中，可继续聊天" }
        if control == "lost" { return "健康检查连接重连中，可继续聊天" }
        if local == "unknown" || healthAgeMs >= 60_000 { return "Hermes 状态待刷新，可尝试连接" }
        if local == "degraded" { return "Hermes 响应较慢，正在复查" }
        return "账号链路正常"
    }

    public var level: HealthLevel {
        if business == "lost" || local == "unhealthy" { return .failed }
        if local == "unknown" || healthAgeMs >= 60_000 { return .unavailable }
        return business == "normal" && control == "normal" && local == "healthy" ? .healthy : .degraded
    }
}
