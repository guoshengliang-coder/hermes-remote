import Darwin
import Foundation

public struct DesktopHermesReadinessCheckpoint: Equatable, Sendable {
    public let logURL: URL
    fileprivate let device: UInt64?
    fileprivate let inode: UInt64?
    fileprivate let byteCount: UInt64

    public init(logURL: URL) {
        self.logURL = logURL
        device = nil
        inode = nil
        byteCount = 0
    }

    fileprivate init(logURL: URL, device: UInt64?, inode: UInt64?, byteCount: UInt64) {
        self.logURL = logURL
        self.device = device
        self.inode = inode
        self.byteCount = byteCount
    }
}

public enum DesktopHermesReadinessError: Error, Equatable, Sendable {
    case invalidLog
    case logGrowthExceeded
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

    public init(prober: HTTPHealthProber = HTTPHealthProber()) {
        self.prober = prober
    }

    public func checkpoint(logURL: URL) throws -> DesktopHermesReadinessCheckpoint {
        let log = try validatedAbsoluteURL(logURL)
        if let metadata = try metadata(log) {
            guard !metadata.isDirectory else { throw DesktopHermesReadinessError.invalidLog }
            return DesktopHermesReadinessCheckpoint(
                logURL: log,
                device: metadata.device,
                inode: metadata.inode,
                byteCount: metadata.size
            )
        }
        guard let parent = try metadata(log.deletingLastPathComponent()), parent.isDirectory else {
            throw DesktopHermesReadinessError.invalidLog
        }
        return DesktopHermesReadinessCheckpoint(
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
        var offset = checkpoint.byteCount
        var device = checkpoint.device
        var inode = checkpoint.inode
        var announced = false
        var collected = Data()
        var observedBytes: UInt64 = 0
        let statusURL = URL(string: "/api/status", relativeTo: contract.baseURL)!.absoluteURL

        for attempt in 0..<maximumAttempts {
            if let current = try metadata(checkpoint.logURL) {
                guard !current.isDirectory else { throw DesktopHermesReadinessError.invalidLog }
                if device != current.device || inode != current.inode || current.size < offset {
                    device = current.device
                    inode = current.inode
                    offset = 0
                    announced = false
                    collected.removeAll(keepingCapacity: true)
                    observedBytes = 0
                }
                let growth = current.size - offset
                guard growth <= Self.maximumNewLogBytes - observedBytes else {
                    throw DesktopHermesReadinessError.logGrowthExceeded
                }
                if growth > 0 {
                    let data = try read(checkpoint.logURL, offset: offset, count: Int(growth))
                    offset += UInt64(data.count)
                    observedBytes += UInt64(data.count)
                    collected.append(data)
                    announced = containsReadyLine(collected, contract: contract)
                }
            }
            if announced, await prober.probeHermes(statusURL).level == .healthy { return true }
            if attempt + 1 < maximumAttempts, delayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: delayNanoseconds)
            }
        }
        return false
    }

    private func containsReadyLine(_ data: Data, contract: DesktopHermesRuntimeContract) -> Bool {
        guard let value = String(data: data, encoding: .utf8) else { return false }
        return value.split(whereSeparator: \.isNewline).contains { rawLine in
            contract.isReadyAnnouncement(String(rawLine).trimmingCharacters(in: .newlines))
        }
    }

    private func read(_ url: URL, offset: UInt64, count: Int) throws -> Data {
        let handle: FileHandle
        do { handle = try FileHandle(forReadingFrom: url) }
        catch { throw DesktopHermesReadinessError.invalidLog }
        defer { try? handle.close() }
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

private struct FileMetadata {
    let device: UInt64
    let inode: UInt64
    let size: UInt64
    let isDirectory: Bool
}
