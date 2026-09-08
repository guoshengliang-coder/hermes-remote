import Foundation

public struct DesktopLaunchAgentServiceState: Equatable, Sendable {
    public let legacyLoaded: Bool
    public let accountLoaded: Bool
    public let hermesLoaded: Bool

    public init(legacyLoaded: Bool, accountLoaded: Bool, hermesLoaded: Bool = false) {
        self.legacyLoaded = legacyLoaded
        self.accountLoaded = accountLoaded
        self.hermesLoaded = hermesLoaded
    }

    public var hasDuplicateConnector: Bool { legacyLoaded && accountLoaded }
}

public enum DesktopLaunchAgentControllerError: Error, Equatable, Sendable {
    case invalidConfiguration
    case duplicateConnector
    case legacyStopFailed
    case accountStartFailed
    case accountStopFailed
    case hermesStartFailed
    case hermesStopFailed
    case legacyRestoreFailed
}

/// Exact-label launchd operations for a user domain. Callers must first obtain the migration journal
/// lock and explicit user confirmation. This type never discovers or kills arbitrary processes.
public struct DesktopLaunchAgentController<Runner: CommandRunning> {
    public static var legacyLabel: String { "com.hermesremote.connector" }
    public static var accountLabel: String { DesktopManagedInstallLayout.connectorLabel }
    public static var hermesLabel: String { DesktopManagedInstallLayout.hermesLabel }

    private let userID: UInt32
    private let launchAgentsRoot: URL
    private let runner: Runner
    private let launchctl = URL(fileURLWithPath: "/bin/launchctl")

    public init(userID: UInt32, launchAgentsRoot: URL, runner: Runner) throws {
        let root = launchAgentsRoot.standardizedFileURL.resolvingSymlinksInPath()
        guard root.isFileURL, root.path.hasPrefix("/"), root.path != "/" else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        self.userID = userID
        self.launchAgentsRoot = root
        self.runner = runner
    }

    public func inspect() throws -> DesktopLaunchAgentServiceState {
        let state = DesktopLaunchAgentServiceState(
            legacyLoaded: isLoaded(Self.legacyLabel),
            accountLoaded: isLoaded(Self.accountLabel),
            hermesLoaded: isLoaded(Self.hermesLabel)
        )
        guard !state.hasDuplicateConnector else {
            throw DesktopLaunchAgentControllerError.duplicateConnector
        }
        return state
    }

    public func stopLegacy(snapshot: LegacyConnectorSnapshot) throws {
        guard snapshot.isInstalled,
              snapshot.isRunning,
              validPlist(snapshot.launchAgentURL, label: Self.legacyLabel),
              !isLoaded(Self.accountLabel),
              isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.invalidConfiguration }
        guard run(["bootout", serviceTarget(Self.legacyLabel)]).status == 0,
              !isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.legacyStopFailed }
    }

    public func startAccount(plistURL: URL) throws {
        guard validPlist(plistURL, label: Self.accountLabel) else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard !isLoaded(Self.legacyLabel),
              !isLoaded(Self.accountLabel)
        else { throw DesktopLaunchAgentControllerError.duplicateConnector }
        guard run(["bootstrap", domainTarget, plistURL.path]).status == 0,
              isLoaded(Self.accountLabel),
              !isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.accountStartFailed }
    }

    public func stopAccount() throws {
        guard !isLoaded(Self.legacyLabel), isLoaded(Self.accountLabel) else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard run(["bootout", serviceTarget(Self.accountLabel)]).status == 0,
              !isLoaded(Self.accountLabel)
        else { throw DesktopLaunchAgentControllerError.accountStopFailed }
    }

    public func startHermes(plistURL: URL) throws {
        guard validPlist(plistURL, label: Self.hermesLabel),
              !isLoaded(Self.hermesLabel)
        else { throw DesktopLaunchAgentControllerError.invalidConfiguration }
        guard run(["bootstrap", domainTarget, plistURL.path]).status == 0,
              isLoaded(Self.hermesLabel)
        else { throw DesktopLaunchAgentControllerError.hermesStartFailed }
    }

    public func stopHermes() throws {
        guard isLoaded(Self.hermesLabel) else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard run(["bootout", serviceTarget(Self.hermesLabel)]).status == 0,
              !isLoaded(Self.hermesLabel)
        else { throw DesktopLaunchAgentControllerError.hermesStopFailed }
    }

    public func restoreLegacy(snapshot: LegacyConnectorSnapshot) throws {
        guard snapshot.isInstalled,
              validPlist(snapshot.launchAgentURL, label: Self.legacyLabel),
              !isLoaded(Self.accountLabel),
              !isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.invalidConfiguration }
        guard run(["bootstrap", domainTarget, snapshot.launchAgentURL.path]).status == 0,
              isLoaded(Self.legacyLabel),
              !isLoaded(Self.accountLabel)
        else { throw DesktopLaunchAgentControllerError.legacyRestoreFailed }
    }

    private var domainTarget: String { "gui/\(userID)" }
    private func serviceTarget(_ label: String) -> String { "\(domainTarget)/\(label)" }
    private func isLoaded(_ label: String) -> Bool {
        run(["print", serviceTarget(label)]).status == 0
    }
    private func run(_ arguments: [String]) -> CommandResult {
        runner.run(executable: launchctl, arguments: arguments)
    }

    private func validPlist(_ value: URL, label: String) -> Bool {
        let standardized = value.standardizedFileURL
        guard standardized.deletingLastPathComponent().resolvingSymlinksInPath().path
                == launchAgentsRoot.resolvingSymlinksInPath().path,
              standardized.lastPathComponent == "\(label).plist"
        else { return false }
        guard FileManager.default.fileExists(atPath: standardized.path) else { return true }
        guard let values = try? standardized.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey,
        ]) else { return false }
        return values.isRegularFile == true && values.isSymbolicLink != true
    }
}
