import Foundation

public struct RecentLogSummary: Equatable, Sendable {
    public let warningCount: Int

    public init(warningCount: Int) {
        self.warningCount = warningCount
    }
}

public enum RecentLogAnalyzer {
    public static func summarize(_ lines: [String]) -> RecentLogSummary {
        let warningWords = [" error ", " failed", " failure", " timeout", " exception"]
        let count = lines.reduce(into: 0) { result, line in
            let normalized = " \(line.lowercased()) "
            if warningWords.contains(where: normalized.contains) { result += 1 }
        }
        return RecentLogSummary(warningCount: count)
    }
}
