import Darwin
import Foundation

public struct CommandResult: Equatable, Sendable {
    public let status: Int32
    /// Standard error, bounded, when the runner was asked to keep it (see `SystemCommandRunner`).
    public let standardError: String?

    public init(status: Int32, standardError: String? = nil) {
        self.status = status
        self.standardError = standardError
    }
}

public protocol CommandRunning {
    func run(executable: URL, arguments: [String]) -> CommandResult
}

public struct SystemCommandRunner: CommandRunning {
    /// At most this much standard error is kept; the rest is read and discarded.
    public static let maximumStandardErrorBytes = 4 * 1024

    private let capturesStandardError: Bool

    /// `capturesStandardError` keeps launchctl's own explanation of a refusal for the operation log.
    public init(capturesStandardError: Bool = false) {
        self.capturesStandardError = capturesStandardError
    }

    public func run(executable: URL, arguments: [String]) -> CommandResult {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        let pipe = capturesStandardError ? Pipe() : nil
        process.standardError = pipe ?? FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            return CommandResult(status: -1, standardError: capturesStandardError ? "spawn failed" : nil)
        }
        var kept = Data()
        if let pipe {
            // Drained before waiting so a chatty child can never block on a full pipe.
            while let chunk = try? pipe.fileHandleForReading.read(upToCount: 4 * 1024), !chunk.isEmpty {
                let room = Self.maximumStandardErrorBytes - kept.count
                if room > 0 { kept.append(chunk.prefix(room)) }
            }
        }
        process.waitUntilExit()
        let standardError = pipe.map { _ in
            String(decoding: kept, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return CommandResult(status: process.terminationStatus, standardError: standardError)
    }
}

