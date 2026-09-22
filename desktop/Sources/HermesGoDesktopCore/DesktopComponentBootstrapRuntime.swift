import Darwin
import Foundation

/// Fully composed schema-v2 bootstrap dependencies. Construction is inert: the trusted preflight
/// performs its first network and read-only scan only when called, and the executor cannot change
/// installation state until its exact preparation is confirmed.
public final class DesktopComponentBootstrapRuntime: @unchecked Sendable {
    public let preflight: DesktopComponentReleasePreflightRuntime
    public let workspaceRoot: URL
    public let executor: DesktopComponentBootstrapExecutor
    public let commitConfiguration: DesktopManagedBootstrapCommitConfiguration
    public let journal: DesktopMigrationJournalStore

    public init(
        releaseConfiguration: DesktopComponentPreflightConfiguration,
        accountGatewayURL: URL,
        runtimeContract: DesktopHermesRuntimeContract,
        account: any DesktopBindingCoordinating,
        paths: DesktopManagedBootstrapPaths,
        userID: UInt32 = Darwin.getuid()
    ) throws {
        let layout = try DesktopManagedInstallLayout(
            root: paths.managedRoot,
            launchAgentsRoot: paths.launchAgentsRoot
        )
        let verifier = try releaseConfiguration.makeManifestVerifier()
        let scanner = try DesktopComponentReleasePreflightCoordinator(
            storeRoot: paths.managedRoot,
            currentUserID: userID
        )
        let journal = try DesktopMigrationJournalStore(root: paths.migrationJournalRoot)
        let operationLog = DesktopServiceOperationLog(layout: layout)
        let launchAgent = try DesktopLaunchAgentController(
            userID: userID,
            launchAgentsRoot: paths.launchAgentsRoot,
            runner: SystemCommandRunner(capturesStandardError: true),
            log: operationLog
        )
        let migration = try DesktopMigrationCoordinator(
            account: account,
            journal: journal,
            installer: DesktopManagedInstaller(layout: layout),
            launchAgent: launchAgent,
            hermesShutdown: DesktopHermesShutdownChecker(log: operationLog),
            operationLog: operationLog,
            localHermesForFreshInstall: DesktopLocalHermesRuntimeSetting.freshInstallProvider(
                detector: (try? DesktopLocalHermesPaths(homeDirectory: paths.hermesHome.deletingLastPathComponent()))
                    .map { DesktopLocalHermesDetector(paths: $0) }
            )
        )
        let installer = try DesktopComponentReleaseInstaller(
            storeRoot: paths.managedRoot,
            currentUserID: userID
        )

        preflight = try DesktopComponentReleasePreflightRuntime(
            manifestURL: releaseConfiguration.manifestURL,
            verifier: verifier,
            scanner: scanner,
            indexChannel: releaseConfiguration.channel,
            indexArchitecture: releaseConfiguration.architecture
        )
        workspaceRoot = paths.workspaceRoot
        executor = DesktopComponentBootstrapExecutor(
            installer: installer,
            migration: migration
        )
        commitConfiguration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: paths.hermesHome,
            accountGatewayURL: accountGatewayURL,
            runtimeContract: runtimeContract
        )
        self.journal = journal
    }
}
