import Darwin
import Foundation

/// Bounded, redacted tail of the active managed Connector and Desktop service-operation logs.
/// Hermes' own stdout is deliberately excluded: it may contain conversation content.
public struct ManagedLogInspector: Sendable {
    public let logsDirectory: URL

    public init(logsDirectory: URL) {
        self.logsDirectory = logsDirectory
    }

    public func inspect() -> [String] {
        var directoryStatus = stat()
        guard lstat(logsDirectory.path, &directoryStatus) == 0,
              (directoryStatus.st_mode & S_IFMT) == S_IFDIR,
              directoryStatus.st_uid == getuid()
        else { return [] }
        // Keep room for every source; the Connector's active log is last so the five-line
        // Diagnostics preview does not get buried by an old service-operation tail.
        let sources = ["desktop-runtime.log", "connector.error.log", "connector.log"]
        return sources.flatMap { name in
            tail(name: name).map { "[\(name)] \(SecretRedactor.redact($0))" }
        }
    }

    private func tail(name: String) -> [String] {
        let url = logsDirectory.appendingPathComponent(name)
        var status = stat()
        guard lstat(url.path, &status) == 0,
              (status.st_mode & S_IFMT) == S_IFREG,
              status.st_uid == getuid(),
              let handle = try? FileHandle(forReadingFrom: url)
        else { return [] }
        defer { try? handle.close() }
        guard let end = try? handle.seekToEnd() else { return [] }
        try? handle.seek(toOffset: end > 64 * 1024 ? end - 64 * 1024 : 0)
        guard let data = try? handle.readToEnd() else { return [] }
        let text = String(decoding: data, as: UTF8.self)
        let lines = text.split(whereSeparator: \.isNewline)
        return (end > 64 * 1024 ? lines.dropFirst() : lines[...]).suffix(20).map(String.init)
    }
}
