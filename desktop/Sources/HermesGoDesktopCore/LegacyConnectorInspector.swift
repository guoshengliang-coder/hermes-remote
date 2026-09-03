import Darwin
import Foundation

public struct CommandResult: Equatable, Sendable {
    public let status: Int32

    public init(status: Int32) {
        self.status = status
    }
}

public protocol CommandRunning {
    func run(executable: URL, arguments: [String]) -> CommandResult
}

public struct SystemCommandRunner: CommandRunning {
    public init() {}

    public func run(executable: URL, arguments: [String]) -> CommandResult {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
            process.waitUntilExit()
            return CommandResult(status: process.terminationStatus)
        } catch {
            return CommandResult(status: -1)
        }
    }
}

public struct LegacyConnectorSnapshot: Equatable, Sendable {
    public let isInstalled: Bool
    public let isRunning: Bool
    public let config: LegacyConnectorConfig
    public let recentLogs: [String]
    public let installDirectory: URL
    public let launchAgentURL: URL

    public var recentLogSummary: RecentLogSummary {
        RecentLogAnalyzer.summarize(recentLogs)
    }

    public init(
        isInstalled: Bool,
        isRunning: Bool,
        config: LegacyConnectorConfig,
        recentLogs: [String],
        installDirectory: URL,
        launchAgentURL: URL
    ) {
        self.isInstalled = isInstalled
        self.isRunning = isRunning
        self.config = config
        self.recentLogs = recentLogs
        self.installDirectory = installDirectory
        self.launchAgentURL = launchAgentURL
    }
}

public struct LegacyConnectorInspector<Runner: CommandRunning> {
    private let homeDirectory: URL
    private let userID: UInt32
    private let runner: Runner
    private let fileManager: FileManager

    public init(
        homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
        userID: UInt32 = getuid(),
        runner: Runner,
        fileManager: FileManager = .default
    ) {
        self.homeDirectory = homeDirectory
        self.userID = userID
        self.runner = runner
        self.fileManager = fileManager
    }

    public func inspect() -> LegacyConnectorSnapshot {
        let installDirectory = homeDirectory
            .appendingPathComponent("Library/Application Support/Hermes Remote", isDirectory: true)
        let launchAgentURL = homeDirectory
            .appendingPathComponent("Library/LaunchAgents/com.hermesremote.connector.plist")
        let configURL = installDirectory.appendingPathComponent("connector.env")
        let isInstalled = fileManager.fileExists(atPath: launchAgentURL.path)
            || fileManager.fileExists(atPath: installDirectory.path)

        let configText = (try? String(contentsOf: configURL, encoding: .utf8)) ?? ""
        let config = LegacyConnectorConfigParser.parse(configText)
        let running = runner.run(
            executable: URL(fileURLWithPath: "/bin/launchctl"),
            arguments: ["print", "gui/\(userID)/com.hermesremote.connector"]
        ).status == 0

        let logURLs = [
            installDirectory.appendingPathComponent("connector.log"),
            installDirectory.appendingPathComponent("connector.error.log"),
        ]
        let recentLogs = logURLs
            .flatMap { tailLines(at: $0, maximumBytes: 64 * 1024, maximumLines: 30) }
            .map { SecretRedactor.redact($0) }
            .suffix(40)

        return LegacyConnectorSnapshot(
            isInstalled: isInstalled,
            isRunning: running,
            config: config,
            recentLogs: Array(recentLogs),
            installDirectory: installDirectory,
            launchAgentURL: launchAgentURL
        )
    }

    private func tailLines(at url: URL, maximumBytes: UInt64, maximumLines: Int) -> [String] {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return [] }
        defer { try? handle.close() }

        guard let end = try? handle.seekToEnd() else { return [] }
        let start = end > maximumBytes ? end - maximumBytes : 0
        try? handle.seek(toOffset: start)
        guard let data = try? handle.readToEnd(),
              let text = String(data: data, encoding: .utf8)
        else { return [] }
        return text.split(whereSeparator: \.isNewline).suffix(maximumLines).map(String.init)
    }
}
