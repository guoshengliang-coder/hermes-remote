import Darwin
import Foundation

/// A small, private, size-bounded record of what Desktop asked launchd to do and what it answered.
///
/// It exists because the 2026-09-21 incident left `stage=runtime rollbackFailed` as the only clue:
/// launchd's own log showed a bootout and no bootstrap, and nothing on the Mac said what Desktop had
/// been waiting for in between. Each line is one launchctl mutation (bootstrap, bootout, enable,
/// disable) with its exit status and standard error, one convergence wait (the `print` polls after a
/// mutation, summarised), or one shutdown/readiness/reload outcome.
///
/// Lines are redacted with `SecretRedactor` (home-directory names become `/Users/<user>`), the file is
/// created 0600, and it is rotated to `<name>.1` once it passes `maximumBytes`, so at most twice that
/// is ever kept. Logging never throws and never blocks a service operation on I/O failure.
public final class DesktopServiceOperationLog: @unchecked Sendable {
    public static let fileName = "desktop-runtime.log"
    public static let defaultMaximumBytes = 256 * 1024

    public let url: URL
    private let maximumBytes: Int
    private let now: @Sendable () -> Date
    private let lock = NSLock()

    public init(
        url: URL,
        maximumBytes: Int = DesktopServiceOperationLog.defaultMaximumBytes,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.url = url
        self.maximumBytes = max(maximumBytes, 1024)
        self.now = now
    }

    /// The log beside the managed service logs, `Managed/logs/desktop-runtime.log`.
    public convenience init(layout: DesktopManagedInstallLayout) {
        self.init(url: layout.logsRoot.appendingPathComponent(Self.fileName))
    }

    public func record(_ message: String) {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let flattened = message
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " | ")
        let line = SecretRedactor.redact("\(formatter.string(from: now())) \(flattened)") + "\n"
        let data = Data(line.utf8)
        lock.withLock { append(data) }
    }

    private func append(_ data: Data) {
        let directory = url.deletingLastPathComponent().path
        var directoryStatus = stat()
        // Never create the managed tree: before an install there is nothing to log about.
        guard lstat(directory, &directoryStatus) == 0,
              (directoryStatus.st_mode & S_IFMT) == S_IFDIR
        else { return }
        rotateIfNeeded(adding: data.count)
        let descriptor = open(url.path, O_WRONLY | O_APPEND | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard descriptor >= 0 else { return }
        defer { close(descriptor) }
        var status = stat()
        guard fstat(descriptor, &status) == 0,
              (status.st_mode & S_IFMT) == S_IFREG,
              status.st_uid == getuid()
        else { return }
        if status.st_mode & 0o077 != 0 { _ = fchmod(descriptor, 0o600) }
        data.withUnsafeBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            _ = write(descriptor, base, buffer.count)
        }
    }

    private func rotateIfNeeded(adding count: Int) {
        var status = stat()
        guard lstat(url.path, &status) == 0,
              (status.st_mode & S_IFMT) == S_IFREG,
              Int(status.st_size) + count > maximumBytes
        else { return }
        _ = rename(url.path, url.path + ".1")
    }
}
