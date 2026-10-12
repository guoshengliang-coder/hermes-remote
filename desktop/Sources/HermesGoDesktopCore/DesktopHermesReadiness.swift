import Darwin
import Foundation

public struct DesktopHermesReadinessCheckpoint: Equatable, Sendable {
    public let logURL: URL
    fileprivate let output: DesktopHermesLogCheckpoint
    fileprivate let errorOutput: DesktopHermesLogCheckpoint?

    public init(logURL: URL) {
        self.logURL = logURL
        output = DesktopHermesLogCheckpoint(logURL: logURL, device: nil, inode: nil, byteCount: 0)
        errorOutput = nil
    }

    fileprivate init(output: DesktopHermesLogCheckpoint, errorOutput: DesktopHermesLogCheckpoint?) {
        logURL = output.logURL
        self.output = output
        self.errorOutput = errorOutput
    }
}

public enum DesktopHermesStartupFailure: String, Equatable, Sendable {
    case pythonGeneratorParameters = "python-generator-parameters"
    case applicationStartupFailed = "application-startup-failed"
    case portInUse = "port-in-use"
}

public enum DesktopHermesReadinessError: Error, Equatable, Sendable, CustomStringConvertible {
    case invalidLog
    case logGrowthExceeded
    case authenticatedEndpointUnavailable
    case startupFailed(DesktopHermesStartupFailure)

    /// Fixed reasons only: stderr may contain model credentials, prompt text and personal paths.
    public var description: String {
        switch self {
        case .invalidLog: "invalidLog"
        case .logGrowthExceeded: "logGrowthExceeded"
        case .authenticatedEndpointUnavailable: "authenticatedEndpointUnavailable"
        case .startupFailed(let failure): "startupFailed reason=\(failure.rawValue)"
        }
    }
}

public protocol DesktopHermesCandidateReadinessChecking {
    func checkpoint(logURL: URL) throws -> DesktopHermesReadinessCheckpoint
    func waitUntilReady(
        checkpoint: DesktopHermesReadinessCheckpoint,
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool
}

/// Requires process-specific stdout evidence written after the checkpoint and a healthy loopback
/// endpoint. A pre-existing process on port 9119 cannot satisfy the marker half of this proof.
public struct DesktopHermesCandidateReadinessChecker: DesktopHermesCandidateReadinessChecking {
    private static let maximumNewLogBytes: UInt64 = 64 * 1024
    private let prober: HTTPHealthProber
    private let authenticatedProbe: (() async -> ProbeResult)?

    public init(authenticatedProbe: (() async -> ProbeResult)? = nil) {
        prober = .loopbackDirect()
        self.authenticatedProbe = authenticatedProbe
    }

    public init(prober: HTTPHealthProber, authenticatedProbe: (() async -> ProbeResult)? = nil) {
        self.prober = prober
        self.authenticatedProbe = authenticatedProbe
    }

    public func checkpoint(logURL: URL) throws -> DesktopHermesReadinessCheckpoint {
        let log = try validatedAbsoluteURL(logURL)
        // All managed starts use these two fixed siblings. Never guess an arbitrary stderr path
        // for another caller, or read another service's logs.
        let errorOutput = log.lastPathComponent == "hermes-server.log"
            ? try logCheckpoint(log.deletingLastPathComponent().appendingPathComponent("hermes-server.error.log"))
            : nil
        return DesktopHermesReadinessCheckpoint(output: try logCheckpoint(log), errorOutput: errorOutput)
    }

    private func logCheckpoint(_ log: URL) throws -> DesktopHermesLogCheckpoint {
        if let metadata = try metadata(log) {
            guard !metadata.isDirectory else { throw DesktopHermesReadinessError.invalidLog }
            return DesktopHermesLogCheckpoint(
                logURL: log,
                device: metadata.device,
                inode: metadata.inode,
                byteCount: metadata.size
            )
        }
        guard let parent = try metadata(log.deletingLastPathComponent()), parent.isDirectory else {
            throw DesktopHermesReadinessError.invalidLog
        }
        return DesktopHermesLogCheckpoint(
            logURL: log,
            device: nil,
            inode: nil,
            byteCount: 0
        )
    }

    public func waitUntilReady(
        checkpoint: DesktopHermesReadinessCheckpoint,
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool {
        guard (1...300).contains(maximumAttempts) else {
            throw DesktopHermesReadinessError.invalidLog
        }
        var output = LogCursor(checkpoint.output)
        var errorOutput = checkpoint.errorOutput.map(LogCursor.init)
        let statusURL = URL(string: "/api/status", relativeTo: contract.baseURL)!.absoluteURL

        var authenticationFailed = false
        for attempt in 0..<maximumAttempts {
            try collect(&output)
            if completeLines(output.collected).contains(where: contract.isPortConflictAnnouncement) {
                throw DesktopHermesReadinessError.startupFailed(.portInUse)
            }
            if var cursor = errorOutput {
                try collect(&cursor)
                errorOutput = cursor
                if let failure = startupFailure(cursor.collected) {
                    throw DesktopHermesReadinessError.startupFailed(failure)
                }
            }
            if containsReadyLine(output.collected, contract: contract),
               await prober.probeHermes(statusURL).level == .healthy {
                if let authenticatedProbe {
                    if await authenticatedProbe().level == .healthy { return true }
                    authenticationFailed = true
                } else { return true }
            }
            if attempt + 1 < maximumAttempts, delayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: delayNanoseconds)
            }
        }
        if authenticationFailed { throw DesktopHermesReadinessError.authenticatedEndpointUnavailable }
        return false
    }

    private func collect(_ cursor: inout LogCursor) throws {
        guard let current = try metadata(cursor.logURL) else { return }
        guard !current.isDirectory else { throw DesktopHermesReadinessError.invalidLog }
        if cursor.device != current.device || cursor.inode != current.inode || current.size < cursor.offset {
            cursor.device = current.device
            cursor.inode = current.inode
            cursor.offset = 0
            cursor.collected.removeAll(keepingCapacity: true)
            // Keep the total bound across rotations: replacing the file cannot reset the budget.
        }
        let growth = current.size - cursor.offset
        guard growth <= Self.maximumNewLogBytes - cursor.observedBytes else {
            throw DesktopHermesReadinessError.logGrowthExceeded
        }
        if growth > 0 {
            let data = try read(cursor.logURL, offset: cursor.offset, count: Int(growth), expected: current)
            cursor.offset += UInt64(data.count)
            cursor.observedBytes += UInt64(data.count)
            cursor.collected.append(data)
        }
    }

    private func completeLines(_ data: Data) -> [String] {
        String(decoding: data, as: UTF8.self).components(separatedBy: "\n").dropLast().map {
            $0.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private func startupFailure(_ data: Data) -> DesktopHermesStartupFailure? {
        let lines = completeLines(data)
        let exited = lines.contains { line in
            let message = line.hasPrefix("ERROR:") ? String(line.dropFirst(6)) : line
            return message.trimmingCharacters(in: .whitespaces) == "Application startup failed. Exiting."
        }
        guard exited else { return nil }
        return lines.contains("TypeError: Too few arguments for typing.Generator; actual 2, expected 3")
            ? .pythonGeneratorParameters : .applicationStartupFailed
    }

    private func containsReadyLine(_ data: Data, contract: DesktopHermesRuntimeContract) -> Bool {
        guard let value = String(data: data, encoding: .utf8) else { return false }
        return value.split(whereSeparator: \.isNewline).contains { rawLine in
            contract.isReadyAnnouncement(String(rawLine).trimmingCharacters(in: .newlines))
        }
    }

    private func read(_ url: URL, offset: UInt64, count: Int, expected: FileMetadata) throws -> Data {
        let descriptor = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        guard descriptor >= 0 else { throw DesktopHermesReadinessError.invalidLog }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        defer { try? handle.close() }
        var opened = stat()
        guard Darwin.fstat(descriptor, &opened) == 0,
              opened.st_mode & S_IFMT == S_IFREG, opened.st_uid == Darwin.getuid(),
              UInt64(opened.st_dev) == expected.device, UInt64(opened.st_ino) == expected.inode
        else { throw DesktopHermesReadinessError.invalidLog }
        do {
            try handle.seek(toOffset: offset)
            var result = Data()
            while result.count < count {
                let remaining = count - result.count
                guard let chunk = try handle.read(upToCount: remaining), !chunk.isEmpty else { break }
                result.append(chunk)
            }
            return result
        } catch {
            throw DesktopHermesReadinessError.invalidLog
        }
    }

    private func validatedAbsoluteURL(_ value: URL) throws -> URL {
        let standardized = value.standardizedFileURL
        guard standardized.isFileURL, standardized.path.hasPrefix("/"), standardized.path != "/" else {
            throw DesktopHermesReadinessError.invalidLog
        }
        return standardized
    }

    private func metadata(_ url: URL) throws -> FileMetadata? {
        var value = stat()
        if Darwin.lstat(url.path, &value) != 0 {
            if errno == ENOENT { return nil }
            throw DesktopHermesReadinessError.invalidLog
        }
        let kind = value.st_mode & S_IFMT
        guard (kind == S_IFREG || kind == S_IFDIR), value.st_uid == Darwin.getuid() else {
            throw DesktopHermesReadinessError.invalidLog
        }
        return FileMetadata(
            device: UInt64(value.st_dev),
            inode: UInt64(value.st_ino),
            size: UInt64(max(0, value.st_size)),
            isDirectory: kind == S_IFDIR
        )
    }
}

fileprivate struct DesktopHermesLogCheckpoint: Equatable, Sendable {
    let logURL: URL
    let device: UInt64?
    let inode: UInt64?
    let byteCount: UInt64
}

private struct LogCursor {
    let logURL: URL
    var device: UInt64?
    var inode: UInt64?
    var offset: UInt64
    var collected = Data()
    var observedBytes: UInt64 = 0

    init(_ checkpoint: DesktopHermesLogCheckpoint) {
        logURL = checkpoint.logURL
        device = checkpoint.device
        inode = checkpoint.inode
        offset = checkpoint.byteCount
    }
}

private struct FileMetadata {
    let device: UInt64
    let inode: UInt64
    let size: UInt64
    let isDirectory: Bool
}
