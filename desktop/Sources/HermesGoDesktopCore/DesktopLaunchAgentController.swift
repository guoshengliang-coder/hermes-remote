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
    case legacySuppressionFailed
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
    private let convergenceAttempts: Int
    private let convergenceDelay: TimeInterval
    private let log: DesktopServiceOperationLog?
    private let launchctl = URL(fileURLWithPath: "/bin/launchctl")

    public init(
        userID: UInt32,
        launchAgentsRoot: URL,
        runner: Runner,
        convergenceAttempts: Int = 50,
        convergenceDelay: TimeInterval = 0.1,
        log: DesktopServiceOperationLog? = nil
    ) throws {
        let root = launchAgentsRoot.standardizedFileURL.resolvingSymlinksInPath()
        guard root.isFileURL,
              root.path.hasPrefix("/"),
              root.path != "/",
              (1...100).contains(convergenceAttempts),
              (0...1).contains(convergenceDelay)
        else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        self.userID = userID
        self.launchAgentsRoot = root
        self.runner = runner
        self.convergenceAttempts = convergenceAttempts
        self.convergenceDelay = convergenceDelay
        self.log = log
    }

    public func inspect() throws -> DesktopLaunchAgentServiceState {
        let state = inspectAllowingDuplicateConnector()
        guard !state.hasDuplicateConnector else {
            throw DesktopLaunchAgentControllerError.duplicateConnector
        }
        return state
    }

    public func inspectAllowingDuplicateConnector() -> DesktopLaunchAgentServiceState {
        DesktopLaunchAgentServiceState(
            legacyLoaded: isLoaded(Self.legacyLabel),
            accountLoaded: isLoaded(Self.accountLabel),
            hermesLoaded: isLoaded(Self.hermesLabel)
        )
    }

    public func stopLegacy(snapshot: LegacyConnectorSnapshot) throws {
        guard snapshot.isInstalled,
              snapshot.isRunning,
              validPlist(snapshot.launchAgentURL, label: Self.legacyLabel),
              !isLoaded(Self.accountLabel),
              isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.invalidConfiguration }
        guard run(["disable", serviceTarget(Self.legacyLabel)]).status == 0 else {
            throw DesktopLaunchAgentControllerError.legacyStopFailed
        }
        guard run(["bootout", serviceTarget(Self.legacyLabel)]).status == 0,
              waitUntilLoaded(Self.legacyLabel, expected: false)
        else {
            _ = run(["enable", serviceTarget(Self.legacyLabel)])
            throw DesktopLaunchAgentControllerError.legacyStopFailed
        }
    }

    /// Migration Assistant can restore both user LaunchAgents even when the committed managed
    /// installation was the sole authority on the source Mac. The durable account-active journal
    /// is checked by the caller before this exact-label repair is allowed.
    public func suppressTransferredLegacyForActiveManagedInstallation() throws {
        guard isLoaded(Self.accountLabel),
              isLoaded(Self.hermesLabel)
        else { throw DesktopLaunchAgentControllerError.invalidConfiguration }
        guard run(["disable", serviceTarget(Self.legacyLabel)]).status == 0 else {
            throw DesktopLaunchAgentControllerError.legacySuppressionFailed
        }
        guard isLoaded(Self.legacyLabel) else { return }
        guard run(["bootout", serviceTarget(Self.legacyLabel)]).status == 0,
              waitUntilLoaded(Self.legacyLabel, expected: false),
              isLoaded(Self.accountLabel),
              isLoaded(Self.hermesLabel)
        else {
            _ = run(["enable", serviceTarget(Self.legacyLabel)])
            throw DesktopLaunchAgentControllerError.legacySuppressionFailed
        }
    }

    public func startAccount(plistURL: URL) throws {
        guard validPlist(plistURL, label: Self.accountLabel) else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard !isLoaded(Self.legacyLabel),
              !isLoaded(Self.accountLabel)
        else { throw DesktopLaunchAgentControllerError.duplicateConnector }
        guard run(["bootstrap", domainTarget, plistURL.path]).status == 0,
              waitUntilLoaded(Self.accountLabel, expected: true),
              !isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.accountStartFailed }
    }

    public func stopAccount() throws {
        guard !isLoaded(Self.legacyLabel), isLoaded(Self.accountLabel) else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard run(["bootout", serviceTarget(Self.accountLabel)]).status == 0,
              waitUntilLoaded(Self.accountLabel, expected: false)
        else { throw DesktopLaunchAgentControllerError.accountStopFailed }
    }

    public func startHermes(plistURL: URL) throws {
        guard validPlist(plistURL, label: Self.hermesLabel) else {
            log?.record("start \(Self.hermesLabel) refused reason=invalid-plist path=\(plistURL.path)")
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard !isLoaded(Self.hermesLabel) else {
            log?.record("start \(Self.hermesLabel) refused reason=already-loaded")
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard run(["bootstrap", domainTarget, plistURL.path]).status == 0,
              waitUntilLoaded(Self.hermesLabel, expected: true)
        else { throw DesktopLaunchAgentControllerError.hermesStartFailed }
    }

    public func stopHermes() throws {
        guard isLoaded(Self.hermesLabel) else {
            throw DesktopLaunchAgentControllerError.invalidConfiguration
        }
        guard run(["bootout", serviceTarget(Self.hermesLabel)]).status == 0,
              waitUntilLoaded(Self.hermesLabel, expected: false)
        else { throw DesktopLaunchAgentControllerError.hermesStopFailed }
    }

    public func restoreLegacy(snapshot: LegacyConnectorSnapshot) throws {
        guard snapshot.isInstalled,
              validPlist(snapshot.launchAgentURL, label: Self.legacyLabel),
              !isLoaded(Self.accountLabel),
              !isLoaded(Self.legacyLabel)
        else { throw DesktopLaunchAgentControllerError.invalidConfiguration }
        guard run(["enable", serviceTarget(Self.legacyLabel)]).status == 0,
              run(["bootstrap", domainTarget, snapshot.launchAgentURL.path]).status == 0,
              waitUntilLoaded(Self.legacyLabel, expected: true),
              !isLoaded(Self.accountLabel)
        else { throw DesktopLaunchAgentControllerError.legacyRestoreFailed }
    }

    private var domainTarget: String { "gui/\(userID)" }
    private func serviceTarget(_ label: String) -> String { "\(domainTarget)/\(label)" }
    private func isLoaded(_ label: String) -> Bool {
        run(["print", serviceTarget(label)]).status == 0
    }
    /// Polls `launchctl print` after a mutation. Logged once, as a summary: the individual polls
    /// are the same question asked again.
    private func waitUntilLoaded(_ label: String, expected: Bool) -> Bool {
        var lastStatus: Int32 = 0
        for attempt in 0..<convergenceAttempts {
            lastStatus = runner.run(executable: launchctl, arguments: ["print", serviceTarget(label)]).status
            if (lastStatus == 0) == expected {
                log?.record("wait-\(expected ? "loaded" : "unloaded") \(label) result=ok polls=\(attempt + 1) print-status=\(lastStatus)")
                return true
            }
            if attempt + 1 < convergenceAttempts, convergenceDelay > 0 {
                Thread.sleep(forTimeInterval: convergenceDelay)
            }
        }
        log?.record("wait-\(expected ? "loaded" : "unloaded") \(label) result=timed-out polls=\(convergenceAttempts) print-status=\(lastStatus)")
        return false
    }
    /// Every launchctl call goes through here. Mutations are logged with their exit status and
    /// standard error; `print` is a read and is logged only as part of a convergence wait.
    private func run(_ arguments: [String]) -> CommandResult {
        let result = runner.run(executable: launchctl, arguments: arguments)
        if let log, let verb = arguments.first, Self.loggedVerbs.contains(verb) {
            var line = "launchctl \(arguments.joined(separator: " ")) status=\(result.status)"
            if let standardError = result.standardError, !standardError.isEmpty {
                line += " stderr=\(standardError)"
            }
            log.record(line)
        }
        return result
    }
    private static var loggedVerbs: Set<String> { ["bootstrap", "bootout", "enable", "disable"] }

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
