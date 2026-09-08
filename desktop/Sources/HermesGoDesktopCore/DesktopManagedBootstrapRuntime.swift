import Darwin
import Foundation

public enum DesktopManagedBootstrapPathsError: Error, Equatable, Sendable {
    case invalidHomeDirectory
}

public struct DesktopManagedBootstrapPaths: Equatable, Sendable {
    public let managedRoot: URL
    public let workspaceRoot: URL
    public let migrationJournalRoot: URL
    public let launchAgentsRoot: URL
    public let hermesHome: URL

    public init(homeDirectory: URL) throws {
        let home = homeDirectory.standardizedFileURL.resolvingSymlinksInPath()
        guard home.isFileURL,
              home.path.hasPrefix("/"),
              home.path != "/",
              !home.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw DesktopManagedBootstrapPathsError.invalidHomeDirectory }
        managedRoot = home
            .appendingPathComponent("Library/Application Support/Hermes Go/Managed", isDirectory: true)
        workspaceRoot = home
            .appendingPathComponent("Library/Caches/com.hermesgo.desktop-managed-bootstrap", isDirectory: true)
        migrationJournalRoot = managedRoot.appendingPathComponent("state", isDirectory: true)
        launchAgentsRoot = home.appendingPathComponent("Library/LaunchAgents", isDirectory: true)
        hermesHome = home.appendingPathComponent(".hermes", isDirectory: true)
    }

    public static func currentUser(
        fileManager: FileManager = .default
    ) throws -> DesktopManagedBootstrapPaths {
        try DesktopManagedBootstrapPaths(homeDirectory: fileManager.homeDirectoryForCurrentUser)
    }
}

public enum DesktopManagedBootstrapInstallationStatus: Equatable, Sendable {
    case absent
    case active(releaseVersion: String, bindingID: String, bindingGeneration: Int)
    case interrupted(runID: String, state: DesktopMigrationState)
    case attentionRequired
    case inconsistent

    public static func reduce(
        journal: DesktopMigrationJournal?,
        services: DesktopLaunchAgentServiceState
    ) -> DesktopManagedBootstrapInstallationStatus {
        guard let journal else {
            return services.accountLoaded || services.hermesLoaded ? .inconsistent : .absent
        }
        switch journal.state {
        case .accountActive:
            guard services.accountLoaded,
                  services.hermesLoaded,
                  !services.legacyLoaded,
                  let bindingID = journal.bindingID,
                  let bindingGeneration = journal.bindingGeneration
            else {
                return .inconsistent
            }
            return .active(
                releaseVersion: journal.releaseVersion,
                bindingID: bindingID,
                bindingGeneration: bindingGeneration
            )
        case .cleanUninstalled, .legacyActive:
            return services.accountLoaded || services.hermesLoaded ? .inconsistent : .absent
        case .rollbackAttentionRequired:
            return .attentionRequired
        case .preflight, .accountStaged, .candidateStarting, .candidateAuthenticated,
             .candidateHealthy, .commitPending, .rollingBack:
            return .interrupted(runID: journal.runID, state: journal.state)
        }
    }

    public func scopedToCurrentAccount(
        bindingID: String?,
        bindingGeneration: Int?
    ) -> DesktopManagedBootstrapInstallationStatus {
        guard case .active(_, let installedBindingID, let installedGeneration) = self else {
            return self
        }
        guard bindingID == installedBindingID, bindingGeneration == installedGeneration else {
            return .inconsistent
        }
        return self
    }
}

/// Existing managed installations must remain observable and recoverable even when new-install
/// rollout is disabled. Construction and inspection are read-only; only recovery of a journal
/// created by an earlier confirmed commit may mutate exact managed/legacy service labels.
public final class DesktopManagedRecoveryRuntime: @unchecked Sendable {
    public let journal: DesktopMigrationJournalStore
    private let migration: DesktopMigrationCoordinator<SystemCommandRunner>
    private let launchAgent: DesktopLaunchAgentController<SystemCommandRunner>

    public init(
        account: any DesktopBindingCoordinating,
        paths: DesktopManagedBootstrapPaths,
        userID: UInt32 = Darwin.getuid()
    ) throws {
        let layout = try DesktopManagedInstallLayout(
            root: paths.managedRoot,
            launchAgentsRoot: paths.launchAgentsRoot
        )
        let journal = try DesktopMigrationJournalStore(root: paths.migrationJournalRoot)
        let launchAgent = try DesktopLaunchAgentController(
            userID: userID,
            launchAgentsRoot: paths.launchAgentsRoot,
            runner: SystemCommandRunner()
        )
        self.journal = journal
        self.launchAgent = launchAgent
        migration = try DesktopMigrationCoordinator(
            account: account,
            journal: journal,
            installer: DesktopManagedInstaller(layout: layout),
            launchAgent: launchAgent
        )
    }

    public func inspectInstallation() throws -> DesktopManagedBootstrapInstallationStatus {
        let services = try launchAgent.inspect()
        return .reduce(journal: try journal.loadReadOnly(), services: services)
    }

    @discardableResult
    public func recoverInterrupted(
        legacy: LegacyConnectorSnapshot,
        runID: String
    ) async throws -> DesktopMigrationState {
        try await migration.recoverInterrupted(legacy: legacy, runID: runID)
    }
}

/// Fully composed managed-bootstrap dependencies. Creating this value is inert: directories,
/// credentials, LaunchAgents, processes, and bindings are touched only by prepare/commit calls.
public final class DesktopManagedBootstrapRuntime: @unchecked Sendable {
    public let manifestURL: URL
    public let workspaceRoot: URL
    public let executor: DesktopManagedBootstrapExecutor
    public let commitConfiguration: DesktopManagedBootstrapCommitConfiguration
    public let journal: DesktopMigrationJournalStore
    private let migration: DesktopMigrationCoordinator<SystemCommandRunner>
    private let launchAgent: DesktopLaunchAgentController<SystemCommandRunner>

    public init(
        releaseConfiguration: DesktopManagedBootstrapConfiguration,
        accountGatewayURL: URL,
        account: any DesktopBindingCoordinating,
        paths: DesktopManagedBootstrapPaths,
        userID: UInt32 = Darwin.getuid()
    ) throws {
        let layout = try DesktopManagedInstallLayout(
            root: paths.managedRoot,
            launchAgentsRoot: paths.launchAgentsRoot
        )
        let verifier = try releaseConfiguration.makeManifestVerifier()
        let acquisition = DesktopReleaseAcquirer(
            downloader: DesktopReleaseDownloader(),
            manifestVerifier: verifier
        )
        let journal = try DesktopMigrationJournalStore(root: paths.migrationJournalRoot)
        let launchAgent = try DesktopLaunchAgentController(
            userID: userID,
            launchAgentsRoot: paths.launchAgentsRoot,
            runner: SystemCommandRunner()
        )
        let migration = try DesktopMigrationCoordinator(
            account: account,
            journal: journal,
            installer: DesktopManagedInstaller(layout: layout),
            launchAgent: launchAgent
        )

        manifestURL = releaseConfiguration.manifestURL
        workspaceRoot = paths.workspaceRoot
        executor = DesktopManagedBootstrapExecutor(
            acquisition: acquisition,
            migration: migration
        )
        commitConfiguration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: paths.hermesHome,
            accountGatewayURL: accountGatewayURL,
            runtimeContract: releaseConfiguration.runtimeContract
        )
        self.journal = journal
        self.migration = migration
        self.launchAgent = launchAgent
    }

    public func inspectInstallation() throws -> DesktopManagedBootstrapInstallationStatus {
        let services = try launchAgent.inspect()
        return .reduce(journal: try journal.loadReadOnly(), services: services)
    }

    @discardableResult
    public func recoverInterrupted(
        legacy: LegacyConnectorSnapshot,
        runID: String
    ) async throws -> DesktopMigrationState {
        try await migration.recoverInterrupted(legacy: legacy, runID: runID)
    }
}
