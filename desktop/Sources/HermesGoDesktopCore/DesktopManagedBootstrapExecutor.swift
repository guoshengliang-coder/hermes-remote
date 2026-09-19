import Foundation

public protocol DesktopReleaseAcquiring: Sendable {
    func acquire(
        manifestURL: URL,
        workspaceRoot: URL,
        runID: String
    ) async throws -> DesktopAcquiredRelease

    func discard(_ release: DesktopAcquiredRelease) throws
}

extension DesktopReleaseAcquirer: DesktopReleaseAcquiring {}

public protocol DesktopReleaseMigrating: Sendable {
    func migrate(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome

    func upgrade(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome
}

public extension DesktopReleaseMigrating {
    func upgrade(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        throw DesktopManagedBootstrapExecutorError.releaseNotNewer
    }
}

extension DesktopMigrationCoordinator: DesktopReleaseMigrating {}

public enum DesktopManagedBootstrapExecutorError: Error, Equatable, Sendable {
    case operationInProgress
    case notPrepared
    case preparationMismatch
    case confirmationRequired
    case cleanupFailed
    case releaseNotNewer
}

public enum DesktopManagedBootstrapIntent: Equatable, Sendable {
    case install
    case upgrade(fromReleaseVersion: String)

    public var isUpgrade: Bool {
        if case .upgrade = self { return true }
        return false
    }
}

public struct DesktopManagedBootstrapPreparation: Equatable, Identifiable, Sendable {
    public let id: String
    public let runID: String
    public let releaseVersion: String
    public let confirmationText: String
    public let intent: DesktopManagedBootstrapIntent

    fileprivate init(
        id: String,
        runID: String,
        releaseVersion: String,
        confirmationText: String,
        intent: DesktopManagedBootstrapIntent
    ) {
        self.id = id
        self.runID = runID
        self.releaseVersion = releaseVersion
        self.confirmationText = confirmationText
        self.intent = intent
    }
}

public enum DesktopManagedBootstrapCommitConfigurationError: Error, Equatable, Sendable {
    case invalidGatewayURL
    case invalidHermesHome
    case invalidManifest
}

public struct DesktopManagedBootstrapCommitConfiguration: Equatable, Sendable {
    public let layout: DesktopManagedInstallLayout
    public let hermesHome: URL
    public let gatewayWebSocketURL: URL
    public let runtimeContract: DesktopHermesRuntimeContract

    public init(
        layout: DesktopManagedInstallLayout,
        hermesHome: URL,
        accountGatewayURL: URL,
        runtimeContract: DesktopHermesRuntimeContract
    ) throws {
        let home = hermesHome.standardizedFileURL
        guard home.isFileURL,
              home.path.hasPrefix("/"),
              home.path != "/",
              !home.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw DesktopManagedBootstrapCommitConfigurationError.invalidHermesHome }
        self.layout = layout
        self.hermesHome = home
        gatewayWebSocketURL = try Self.connectorURL(accountGatewayURL)
        self.runtimeContract = runtimeContract
    }

    fileprivate func launchAgents(
        for manifest: DesktopReleaseManifest
    ) throws -> (
        hermes: DesktopHermesServerLaunchAgent,
        connector: DesktopAccountConnectorLaunchAgent
    ) {
        guard let hermesArtifact = manifest.artifacts.first(where: { $0.component == .hermesServer }),
              let connectorArtifact = manifest.artifacts.first(where: { $0.component == .connector })
        else { throw DesktopManagedBootstrapCommitConfigurationError.invalidManifest }
        let current = layout.currentRelease
        return (
            DesktopHermesServerLaunchAgent(
                hermesExecutable: current
                    .appendingPathComponent(DesktopReleaseComponentKind.hermesServer.rawValue)
                    .appendingPathComponent(hermesArtifact.entrypoint),
                hermesHome: hermesHome,
                runtimeContract: runtimeContract,
                sessionTokenFile: layout.hermesSessionToken,
                standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
                standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log")
            ),
            DesktopAccountConnectorLaunchAgent(
                connectorExecutable: current
                    .appendingPathComponent(DesktopReleaseComponentKind.connector.rawValue)
                    .appendingPathComponent(connectorArtifact.entrypoint),
                credentialFile: layout.connectorCredential,
                gatewayURL: gatewayWebSocketURL,
                hermesBaseURL: runtimeContract.baseURL,
                sessionTokenFile: layout.hermesSessionToken,
                standardOutput: layout.logsRoot.appendingPathComponent("connector.log"),
                standardError: layout.logsRoot.appendingPathComponent("connector.error.log")
            )
        )
    }

    public func componentLaunchAgents(
        for plan: DesktopComponentReleaseActivationPlan,
        optionalRuntime: DesktopOptionalComponentRuntimeEnvironment? = nil
    ) throws -> (
        hermes: DesktopHermesServerLaunchAgent,
        connector: DesktopAccountConnectorLaunchAgent
    ) {
        guard let python = plan.component(.pythonRuntime),
              let hermes = plan.component(.hermesCore),
              let node = plan.component(.nodeRuntime),
              let connector = plan.component(.connector)
        else { throw DesktopManagedBootstrapCommitConfigurationError.invalidManifest }
        return (
            DesktopHermesServerLaunchAgent(
                hermesExecutable: hermes.entrypoint,
                hermesHome: hermesHome,
                runtimeContract: runtimeContract,
                sessionTokenFile: layout.hermesSessionToken,
                standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
                standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log"),
                pythonRuntimeRoot: python.root,
                optionalRuntime: optionalRuntime
            ),
            DesktopAccountConnectorLaunchAgent(
                connectorExecutable: connector.entrypoint,
                credentialFile: layout.connectorCredential,
                gatewayURL: gatewayWebSocketURL,
                hermesBaseURL: runtimeContract.baseURL,
                sessionTokenFile: layout.hermesSessionToken,
                standardOutput: layout.logsRoot.appendingPathComponent("connector.log"),
                standardError: layout.logsRoot.appendingPathComponent("connector.error.log"),
                nodeRuntimeRoot: node.root
            )
        )
    }

    private static func connectorURL(_ value: URL) throws -> URL {
        guard var components = URLComponents(url: value, resolvingAgainstBaseURL: false),
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              let host = components.host?.lowercased(),
              !host.isEmpty,
              components.path.isEmpty || components.path == "/"
        else { throw DesktopManagedBootstrapCommitConfigurationError.invalidGatewayURL }
        switch components.scheme?.lowercased() {
        case "https":
            components.scheme = "wss"
        case "http" where ["127.0.0.1", "::1", "localhost"].contains(host):
            components.scheme = "ws"
        default:
            throw DesktopManagedBootstrapCommitConfigurationError.invalidGatewayURL
        }
        components.path = "/v2/connect"
        guard let result = components.url else {
            throw DesktopManagedBootstrapCommitConfigurationError.invalidGatewayURL
        }
        return result
    }
}

public struct DesktopManagedBootstrapOutcome: Equatable, Sendable {
    public let migration: DesktopMigrationOutcome

    /// A committed migration remains successful if temporary cleanup needs a later retry. The UI
    /// must surface diagnostics without pretending that the active Connector rolled back.
    public let temporaryWorkspaceRemoved: Bool
    public let cleanupRetry: DesktopManagedBootstrapPreparation?
}

/// The only core boundary that joins inert release acquisition to the user-confirmed migration.
/// `prepare` may use network and private temporary disk space, but cannot mutate an installation,
/// credential, LaunchAgent, process, or binding. `commit` accepts only the exact preparation issued
/// by this actor and an exact release-specific confirmation. Every terminal path attempts cleanup.
public actor DesktopManagedBootstrapExecutor {
    private let acquisition: any DesktopReleaseAcquiring
    private let migration: any DesktopReleaseMigrating
    private var state: State = .idle

    private enum State {
        case idle
        case preparing
        case prepared(PendingPreparation)
        case committing
        case cleanupPending(PendingPreparation)
    }

    private struct PendingPreparation {
        let publicValue: DesktopManagedBootstrapPreparation
        let acquired: DesktopAcquiredRelease
    }

    public init(
        acquisition: any DesktopReleaseAcquiring,
        migration: any DesktopReleaseMigrating
    ) {
        self.acquisition = acquisition
        self.migration = migration
    }

    public func prepare(
        manifestURL: URL,
        workspaceRoot: URL,
        runID: String,
        installation: DesktopManagedBootstrapInstallationStatus = .absent
    ) async throws -> DesktopManagedBootstrapPreparation {
        guard case .idle = state else {
            throw DesktopManagedBootstrapExecutorError.operationInProgress
        }
        state = .preparing

        let acquired: DesktopAcquiredRelease
        do {
            acquired = try await acquisition.acquire(
                manifestURL: manifestURL,
                workspaceRoot: workspaceRoot,
                runID: runID
            )
        } catch {
            state = .idle
            throw error
        }

        do {
            try Task.checkCancellation()
        } catch {
            state = .idle
            do { try acquisition.discard(acquired) }
            catch { throw DesktopManagedBootstrapExecutorError.cleanupFailed }
            throw error
        }

        let intent: DesktopManagedBootstrapIntent
        switch installation {
        case .absent:
            intent = .install
        case .active(let installed, _, _):
            guard Self.version(acquired.manifest.releaseVersion, isNewerThan: installed) else {
                state = .idle
                do { try acquisition.discard(acquired) }
                catch { throw DesktopManagedBootstrapExecutorError.cleanupFailed }
                throw DesktopManagedBootstrapExecutorError.releaseNotNewer
            }
            intent = .upgrade(fromReleaseVersion: installed)
        case .interrupted, .attentionRequired, .inconsistent:
            state = .idle
            do { try acquisition.discard(acquired) }
            catch { throw DesktopManagedBootstrapExecutorError.cleanupFailed }
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() ?? runID
        let publicValue = DesktopManagedBootstrapPreparation(
            id: UUID().uuidString.lowercased(),
            runID: normalizedRunID,
            releaseVersion: acquired.manifest.releaseVersion,
            confirmationText: DesktopMigrationCoordinator<SystemCommandRunner>
                .confirmationText(releaseVersion: acquired.manifest.releaseVersion),
            intent: intent
        )
        state = .prepared(PendingPreparation(publicValue: publicValue, acquired: acquired))
        return publicValue
    }

    public func commit(
        _ preparation: DesktopManagedBootstrapPreparation,
        configuration: DesktopManagedBootstrapCommitConfiguration,
        legacy: LegacyConnectorSnapshot,
        confirmation: String
    ) async throws -> DesktopManagedBootstrapOutcome {
        guard case .prepared(let pending) = state else {
            switch state {
            case .preparing, .committing, .cleanupPending:
                throw DesktopManagedBootstrapExecutorError.operationInProgress
            case .idle:
                throw DesktopManagedBootstrapExecutorError.notPrepared
            case .prepared:
                preconditionFailure("handled above")
            }
        }
        guard pending.publicValue == preparation else {
            throw DesktopManagedBootstrapExecutorError.preparationMismatch
        }
        guard confirmation == pending.publicValue.confirmationText else {
            throw DesktopManagedBootstrapExecutorError.confirmationRequired
        }
        let launchAgents = try configuration.launchAgents(for: pending.acquired.manifest)
        state = .committing

        let migrationOutcome: DesktopMigrationOutcome
        do {
            try Task.checkCancellation()
            switch pending.publicValue.intent {
            case .install:
                migrationOutcome = try await migration.migrate(
                    manifest: pending.acquired.manifest,
                    sources: pending.acquired.sources,
                    hermesLaunchAgentConfiguration: launchAgents.hermes,
                    launchAgentConfiguration: launchAgents.connector,
                    legacy: legacy,
                    runID: pending.publicValue.runID,
                    confirmation: confirmation
                )
            case .upgrade:
                migrationOutcome = try await migration.upgrade(
                    manifest: pending.acquired.manifest,
                    sources: pending.acquired.sources,
                    hermesLaunchAgentConfiguration: launchAgents.hermes,
                    launchAgentConfiguration: launchAgents.connector,
                    runID: pending.publicValue.runID,
                    confirmation: confirmation
                )
            }
        } catch {
            do {
                try acquisition.discard(pending.acquired)
                state = .idle
            } catch {
                state = .cleanupPending(pending)
                throw DesktopManagedBootstrapExecutorError.cleanupFailed
            }
            throw error
        }

        let workspaceRemoved: Bool
        do {
            try acquisition.discard(pending.acquired)
            workspaceRemoved = true
            state = .idle
        } catch {
            workspaceRemoved = false
            state = .cleanupPending(pending)
        }
        return DesktopManagedBootstrapOutcome(
            migration: migrationOutcome,
            temporaryWorkspaceRemoved: workspaceRemoved,
            cleanupRetry: workspaceRemoved ? nil : pending.publicValue
        )
    }

    public func cancel(_ preparation: DesktopManagedBootstrapPreparation) async throws {
        let pending: PendingPreparation
        switch state {
        case .prepared(let value), .cleanupPending(let value):
            pending = value
        default:
            switch state {
            case .preparing, .committing:
                throw DesktopManagedBootstrapExecutorError.operationInProgress
            case .idle:
                throw DesktopManagedBootstrapExecutorError.notPrepared
            case .prepared, .cleanupPending:
                preconditionFailure("handled above")
            }
        }
        guard pending.publicValue == preparation else {
            throw DesktopManagedBootstrapExecutorError.preparationMismatch
        }
        state = .committing
        do {
            try acquisition.discard(pending.acquired)
            state = .idle
        } catch {
            state = .cleanupPending(pending)
            throw DesktopManagedBootstrapExecutorError.cleanupFailed
        }
    }

    public func retryCleanup(_ preparation: DesktopManagedBootstrapPreparation) async throws {
        guard case .cleanupPending(let pending) = state else {
            throw DesktopManagedBootstrapExecutorError.notPrepared
        }
        guard pending.publicValue == preparation else {
            throw DesktopManagedBootstrapExecutorError.preparationMismatch
        }
        try await cancel(preparation)
    }

    /// Compatibility helper for non-UI callers. UI code should use `prepare` then `commit` so the
    /// exact signed release version can be shown before confirmation.
    public func execute(
        manifestURL: URL,
        workspaceRoot: URL,
        configuration: DesktopManagedBootstrapCommitConfiguration,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String,
        installation: DesktopManagedBootstrapInstallationStatus = .absent
    ) async throws -> DesktopManagedBootstrapOutcome {
        let preparation = try await prepare(
            manifestURL: manifestURL,
            workspaceRoot: workspaceRoot,
            runID: runID,
            installation: installation
        )
        do {
            return try await commit(
                preparation,
                configuration: configuration,
                legacy: legacy,
                confirmation: confirmation
            )
        } catch {
            if case .prepared(let pending) = state, pending.publicValue == preparation {
                do { try await cancel(preparation) }
                catch { throw DesktopManagedBootstrapExecutorError.cleanupFailed }
            }
            throw error
        }
    }

    private static func version(_ candidate: String, isNewerThan installed: String) -> Bool {
        let candidateParts = candidate.split(separator: ".").compactMap { Int($0) }
        let installedParts = installed.split(separator: ".").compactMap { Int($0) }
        return candidateParts.count == 3 && installedParts.count == 3
            && candidateParts != installedParts
            && candidateParts.lexicographicallyPrecedes(installedParts) == false
    }
}
