import Foundation

/// Retry only temporary connectivity failures; never retry credentials, integrity, protocol or
/// owner-cancel failures. Backoff is cancellable, and the initial attempt is included in the cap.
public enum DesktopInstallRetry {
    public static func isTransient(_ error: Error) -> Bool {
        guard case .network(_, let detail) = error as? DesktopHermesInstallFailure else { return false }
        if detail.hasPrefix("HTTP "), let code = Int(detail.dropFirst(5)) {
            return [408, 429, 500, 502, 503, 504].contains(code)
        }
        if detail.hasPrefix("URLError "), let code = Int(detail.dropFirst(9)) {
            return [-1001, -1003, -1004, -1005, -1006, -1009, -1018, -1019, -1020].contains(code)
        }
        return DesktopHermesInstaller.looksLikeNetworkFailure([detail])
    }

    public static func run<T>(
        sleep: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) },
        onRetry: (Int, Int) -> Void,
        operation: () async throws -> T
    ) async throws -> T {
        for attempt in 1...3 {
            try Task.checkCancellation()
            do { return try await operation() }
            catch {
                guard attempt < 3, isTransient(error) else { throw error }
                let seconds = attempt * 2
                onRetry(attempt + 1, seconds)
                try await sleep(UInt64(seconds) * 1_000_000_000)
            }
        }
        fatalError("bounded retry loop must return or throw")
    }
}

public struct DesktopInstallerDownloadProgress: Equatable, Sendable {
    public let receivedBytes: Int
    public let totalBytes: Int?
    public let bytesPerSecond: Double?
    public init(receivedBytes: Int, totalBytes: Int?, elapsed: TimeInterval) {
        self.receivedBytes = max(0, receivedBytes)
        self.totalBytes = totalBytes.flatMap { $0 > 0 && $0 >= receivedBytes ? $0 : nil }
        self.bytesPerSecond = elapsed >= 0.5 && elapsed.isFinite ? Double(max(0, receivedBytes)) / elapsed : nil
    }
}
