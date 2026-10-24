import Foundation

public struct RecentLogSummary: Equatable, Sendable {
    public let warningCount: Int

    public init(warningCount: Int) {
        self.warningCount = warningCount
    }
}

public enum RecentLogAnalyzer {
    public static func isWarning(_ line: String) -> Bool {
        let normalized = " \(line.lowercased()) "
        return [" error ", " failed", " failure", " timeout", " exception", " warn", "[error]", "[warn]"].contains(where: normalized.contains)
    }

    public static func filter(_ lines: [String], source: String? = nil, query: String = "", warningsOnly: Bool = false) -> [String] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return lines.map { SecretRedactor.redact($0) }.filter { line in
            (source.map { line.hasPrefix("[\($0)] ") } ?? true) &&
            (query.isEmpty || line.localizedCaseInsensitiveContains(query)) &&
            (!warningsOnly || isWarning(line))
        }
    }

    public static func summarize(_ lines: [String]) -> RecentLogSummary {
        let count = lines.reduce(into: 0) { result, line in
            if isWarning(line) { result += 1 }
        }
        return RecentLogSummary(warningCount: count)
    }
}
