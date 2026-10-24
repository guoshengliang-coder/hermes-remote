import Darwin
import Foundation

/// Conflict detection only. Never reads retired credentials, configuration or logs.
public struct RetiredConnectorSnapshot: Equatable, Sendable {
    public let isInstalled: Bool
    public let isRunning: Bool

    public init(isInstalled: Bool, isRunning: Bool) {
        self.isInstalled = isInstalled
        self.isRunning = isRunning
    }
}

public struct RetiredConnectorInspector<Runner: CommandRunning> {
    private let homeDirectory: URL
    private let userID: UInt32
    private let runner: Runner
    private let fileManager: FileManager

    public init(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
                userID: UInt32 = getuid(), runner: Runner, fileManager: FileManager = .default) {
        self.homeDirectory = homeDirectory
        self.userID = userID
        self.runner = runner
        self.fileManager = fileManager
    }

    public func inspect() -> RetiredConnectorSnapshot {
        let plist = homeDirectory.appendingPathComponent("Library/LaunchAgents/com.hermesremote.connector.plist")
        let running = runner.run(executable: URL(fileURLWithPath: "/bin/launchctl"),
            arguments: ["print", "gui/\(userID)/com.hermesremote.connector"]).status == 0
        // An inert old data directory is not a runnable service and must not block a clean setup.
        return RetiredConnectorSnapshot(isInstalled: fileManager.fileExists(atPath: plist.path),
                                        isRunning: running)
    }
}
