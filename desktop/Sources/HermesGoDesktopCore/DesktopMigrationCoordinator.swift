import Foundation

public protocol DesktopBindingCoordinating: Sendable {
    func beginBinding(
        retryingTerminalBindingID: String?,
        retryingTerminalGeneration: Int?
    ) async throws -> DesktopBindingPreparation
    func refresh() async throws -> DesktopAccountState
    func confirmBinding() async throws -> DesktopAccountState
}

extension DesktopAccountController: DesktopBindingCoordinating {}

public enum DesktopMigrationCoordinatorError: Error, Equatable, Sendable {
    case confirmationRequired
    case invalidStartingState
    case invalidBindingState
    case releaseNotNewer
    case hermesStopTimedOut
    case hermesHealthTimedOut
    case healthTimedOut
    case commitNotApplied
    case commitAmbiguous
    case rollbackFailed
}

public struct DesktopMigrationOutcome: Equatable, Sendable {
    public let runID: String
    public let releaseVersion: String
    public let bindingID: String
    public let bindingGeneration: Int
}

public final class DesktopMigrationCoordinator<Runner: CommandRunning>: @unchecked Sendable {
    private enum ReleaseCandidate {
        case bundled(
            manifest: DesktopReleaseManifest,
            sources: [DesktopManagedReleaseSource]
        )
        case components(
            manifest: DesktopComponentReleaseManifestV2,
            activationPlan: DesktopComponentReleaseActivationPlan
        )

        var releaseVersion: String {
            switch self {
            case .bundled(let manifest, _): manifest.releaseVersion
            case .components(let manifest, _): manifest.releaseVersion
            }
        }

        var releaseLayout: DesktopManagedReleaseLayoutKind {
            switch self {
            case .bundled: .bundledRelease
            case .components: .componentStore
            }
        }
    }

    private let account: any DesktopBindingCoordinating
    private let journal: DesktopMigrationJournalStore
    private let installer: DesktopManagedInstaller
    private let launchAgent: DesktopLaunchAgentController<Runner>
    private let hermesReadiness: any DesktopHermesCandidateReadinessChecking
    private let hermesShutdown: any DesktopHermesShutdownChecking
    private let maximumHealthPolls: Int
    private let healthPollDelayNanoseconds: UInt64

    public init(
        account: any DesktopBindingCoordinating,
        journal: DesktopMigrationJournalStore,
        installer: DesktopManagedInstaller,
        launchAgent: DesktopLaunchAgentController<Runner>,
        hermesReadiness: any DesktopHermesCandidateReadinessChecking
            = DesktopHermesCandidateReadinessChecker(),
        hermesShutdown: any DesktopHermesShutdownChecking = DesktopHermesShutdownChecker(),
        maximumHealthPolls: Int = 75,
        healthPollDelayNanoseconds: UInt64 = 1_000_000_000
    ) throws {
        guard (1...300).contains(maximumHealthPolls) else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        self.account = account
        self.journal = journal
        self.installer = installer
        self.launchAgent = launchAgent
        self.hermesReadiness = hermesReadiness
        self.hermesShutdown = hermesShutdown
        self.maximumHealthPolls = maximumHealthPolls
        self.healthPollDelayNanoseconds = healthPollDelayNanoseconds
    }

    public static func confirmationText(releaseVersion: String) -> String {
        "升级到 \(releaseVersion) 并短暂重启 Hermes Server 与 Connector"
    }

    public func migrate(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        try await migrate(
            candidate: .bundled(manifest: manifest, sources: sources),
            hermesLaunchAgentConfiguration: hermesLaunchAgentConfiguration,
            launchAgentConfiguration: launchAgentConfiguration,
            legacy: legacy,
            runID: runID,
            confirmation: confirmation
        )
    }

    public func migrateComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        guard Self.validComponentCandidate(manifest: manifest, activationPlan: activationPlan) else {
            throw DesktopComponentReleaseActivationError.invalidManifest
        }
        return try await migrate(
            candidate: .components(manifest: manifest, activationPlan: activationPlan),
            hermesLaunchAgentConfiguration: hermesLaunchAgentConfiguration,
            launchAgentConfiguration: launchAgentConfiguration,
            legacy: legacy,
            runID: runID,
            confirmation: confirmation
        )
    }

    public func upgrade(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        try await upgrade(
            candidate: .bundled(manifest: manifest, sources: sources),
            hermesLaunchAgentConfiguration: hermesLaunchAgentConfiguration,
            launchAgentConfiguration: launchAgentConfiguration,
            runID: runID,
            confirmation: confirmation
        )
    }

    public func upgradeComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        guard Self.validComponentCandidate(manifest: manifest, activationPlan: activationPlan) else {
            throw DesktopComponentReleaseActivationError.invalidManifest
        }
        return try await upgrade(
            candidate: .components(manifest: manifest, activationPlan: activationPlan),
            hermesLaunchAgentConfiguration: hermesLaunchAgentConfiguration,
            launchAgentConfiguration: launchAgentConfiguration,
            runID: runID,
            confirmation: confirmation
        )
    }

    private func upgrade(
        candidate: ReleaseCandidate,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        guard confirmation == Self.confirmationText(releaseVersion: candidate.releaseVersion) else {
            throw DesktopMigrationCoordinatorError.confirmationRequired
        }
        let operationLease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(operationLease) {} }
        guard let installed = try journal.load(),
              installed.state == .accountActive,
              installed.lastKnownGoodMode == .account,
              let bindingID = installed.bindingID,
              let bindingGeneration = installed.bindingGeneration,
              Self.version(candidate.releaseVersion, isNewerThan: installed.releaseVersion),
              launchAgentConfiguration.hermesBaseURL
                == hermesLaunchAgentConfiguration.runtimeContract.baseURL
        else { throw DesktopMigrationCoordinatorError.releaseNotNewer }
        let services = try launchAgent.inspect()
        let currentAccount = try await account.refresh()
        guard services.accountLoaded, services.hermesLoaded, !services.legacyLoaded,
              hasExactBoundBinding(
                currentAccount,
                bindingID: bindingID,
                generation: bindingGeneration
              ),
              isCommitted(currentAccount, bindingID: bindingID, generation: bindingGeneration)
        else { throw DesktopMigrationCoordinatorError.invalidStartingState }

        let snapshot = try installer.prepareManagedUpgradeSnapshot(
            runID: runID,
            previousReleaseVersion: installed.releaseVersion,
            targetReleaseVersion: candidate.releaseVersion,
            previousReleaseLayout: installed.releaseLayout,
            targetReleaseLayout: candidate.releaseLayout
        )
        do {
            _ = try journal.beginUpgrade(
                runID: runID,
                installedReleaseVersion: installed.releaseVersion,
                targetReleaseVersion: candidate.releaseVersion,
                installedReleaseLayout: installed.releaseLayout,
                targetReleaseLayout: candidate.releaseLayout,
                bindingID: bindingID,
                bindingGeneration: bindingGeneration
            )
        } catch {
            try? installer.discardManagedUpgradeSnapshot(snapshot)
            throw error
        }

        do {
            let hermesLaunchAgentURL: URL
            let accountLaunchAgentURL: URL
            switch candidate {
            case .bundled(let manifest, let sources):
                _ = try installer.stageRelease(manifest: manifest, runID: runID, sources: sources)
                hermesLaunchAgentURL = try installer.writeHermesLaunchAgent(
                    hermesLaunchAgentConfiguration,
                    manifest: manifest
                )
                accountLaunchAgentURL = try installer.writeLaunchAgent(
                    launchAgentConfiguration,
                    manifest: manifest
                )
            case .components(_, let activationPlan):
                hermesLaunchAgentURL = try installer.writeHermesLaunchAgent(
                    hermesLaunchAgentConfiguration,
                    activationPlan: activationPlan
                )
                accountLaunchAgentURL = try installer.writeLaunchAgent(
                    launchAgentConfiguration,
                    activationPlan: activationPlan
                )
            }
            _ = try journal.transition(runID: runID, to: .accountStaged)
            _ = try journal.transition(runID: runID, to: .candidateStarting)
            if case .bundled = candidate {
                _ = try installer.activate(releaseVersion: candidate.releaseVersion, runID: runID)
            }

            let checkpoint = try hermesReadiness.checkpoint(
                logURL: hermesLaunchAgentConfiguration.standardOutput
            )
            let cloudHealthCheckpoint = try await captureBoundHealthCheckpoint(
                bindingID: bindingID,
                generation: bindingGeneration
            )
            try launchAgent.stopAccount()
            try launchAgent.stopHermes()
            guard try await hermesShutdown.waitUntilStopped(
                contract: hermesLaunchAgentConfiguration.runtimeContract,
                maximumAttempts: maximumHealthPolls,
                delayNanoseconds: healthPollDelayNanoseconds
            ) else { throw DesktopMigrationCoordinatorError.hermesStopTimedOut }
            try launchAgent.startHermes(plistURL: hermesLaunchAgentURL)
            guard try await hermesReadiness.waitUntilReady(
                checkpoint: checkpoint,
                contract: hermesLaunchAgentConfiguration.runtimeContract,
                maximumAttempts: maximumHealthPolls,
                delayNanoseconds: healthPollDelayNanoseconds
            ) else { throw DesktopMigrationCoordinatorError.hermesHealthTimedOut }
            try launchAgent.startAccount(plistURL: accountLaunchAgentURL)
            try await waitForExistingCommittedBinding(
                bindingID: bindingID,
                generation: bindingGeneration,
                healthNewerThan: cloudHealthCheckpoint
            )
            _ = try journal.transition(runID: runID, to: .candidateAuthenticated)
            _ = try journal.transition(runID: runID, to: .candidateHealthy)
            _ = try journal.transition(runID: runID, to: .commitPending)
            switch candidate {
            case .bundled:
                try installer.commitHermesSessionTokenFileMigration()
            case .components(_, let activationPlan):
                try installer.commitHermesSessionTokenFileMigration(activationPlan: activationPlan)
            }
            _ = try journal.transition(runID: runID, to: .accountActive)
            try? installer.discardManagedUpgradeSnapshot(snapshot)
            return DesktopMigrationOutcome(
                runID: snapshot.runID,
                releaseVersion: candidate.releaseVersion,
                bindingID: bindingID,
                bindingGeneration: bindingGeneration
            )
        } catch {
            do {
                try await rollbackUpgrade(
                    snapshot,
                    bindingID: bindingID,
                    generation: bindingGeneration,
                    contract: hermesLaunchAgentConfiguration.runtimeContract
                )
            } catch {
                try? markRollbackAttention(runID: runID)
                throw DesktopMigrationCoordinatorError.rollbackFailed
            }
            throw error
        }
    }

    private func migrate(
        candidate: ReleaseCandidate,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        guard confirmation == Self.confirmationText(releaseVersion: candidate.releaseVersion) else {
            throw DesktopMigrationCoordinatorError.confirmationRequired
        }
        let operationLease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(operationLease) {} }
        let serviceState = try launchAgent.inspect()
        guard (legacy.isInstalled && legacy.isRunning) || (!legacy.isInstalled && !legacy.isRunning),
              serviceState.accountLoaded == false,
              serviceState.hermesLoaded == false,
              launchAgentConfiguration.hermesBaseURL
                == hermesLaunchAgentConfiguration.runtimeContract.baseURL,
              serviceState.legacyLoaded == legacy.isRunning
        else { throw DesktopMigrationCoordinatorError.invalidStartingState }

        let lastKnownGood: DesktopLastKnownGoodMode = legacy.isRunning ? .legacy : .none
        let retryingTerminalBinding = try terminalRetryBinding(lastKnownGood: lastKnownGood)
        let preparation = try await account.beginBinding(
            retryingTerminalBindingID: retryingTerminalBinding?.id,
            retryingTerminalGeneration: retryingTerminalBinding?.generation
        )
        let target = try bindingTarget(
            preparation.state,
            retryingTerminalBinding: retryingTerminalBinding
        )
        _ = try journal.begin(
            runID: runID,
            lastKnownGoodMode: lastKnownGood,
            releaseVersion: candidate.releaseVersion,
            releaseLayout: candidate.releaseLayout,
            bindingID: target.id,
            bindingGeneration: target.generation
        )
        var activation: DesktopReleaseActivation?
        do {
            switch candidate {
            case .bundled(let manifest, let sources):
                _ = try installer.stageRelease(manifest: manifest, runID: runID, sources: sources)
            case .components:
                break
            }
            _ = try installer.writeCredential(preparation.credential)
            _ = try installer.ensureHermesSessionToken()
            let hermesLaunchAgentURL: URL
            let accountLaunchAgentURL: URL
            switch candidate {
            case .bundled(let manifest, _):
                hermesLaunchAgentURL = try installer.writeHermesLaunchAgent(
                    hermesLaunchAgentConfiguration,
                    manifest: manifest
                )
                accountLaunchAgentURL = try installer.writeLaunchAgent(
                    launchAgentConfiguration,
                    manifest: manifest
                )
            case .components(_, let activationPlan):
                hermesLaunchAgentURL = try installer.writeHermesLaunchAgent(
                    hermesLaunchAgentConfiguration,
                    activationPlan: activationPlan
                )
                accountLaunchAgentURL = try installer.writeLaunchAgent(
                    launchAgentConfiguration,
                    activationPlan: activationPlan
                )
            }
            _ = try journal.transition(runID: runID, to: .accountStaged)
            _ = try journal.transition(runID: runID, to: .candidateStarting)
            if case .bundled = candidate {
                activation = try installer.activate(
                    releaseVersion: candidate.releaseVersion,
                    runID: runID
                )
            }
            let hermesCheckpoint = try hermesReadiness.checkpoint(
                logURL: hermesLaunchAgentConfiguration.standardOutput
            )
            if legacy.isRunning { try launchAgent.stopLegacy(snapshot: legacy) }
            try launchAgent.startHermes(plistURL: hermesLaunchAgentURL)
            guard try await hermesReadiness.waitUntilReady(
                checkpoint: hermesCheckpoint,
                contract: hermesLaunchAgentConfiguration.runtimeContract,
                maximumAttempts: maximumHealthPolls,
                delayNanoseconds: healthPollDelayNanoseconds
            ) else { throw DesktopMigrationCoordinatorError.hermesHealthTimedOut }
            let committedHealthCheckpoint = target.isAlreadyBound
                ? try await captureBoundHealthCheckpoint(
                    bindingID: target.id,
                    generation: target.generation
                )
                : nil
            try launchAgent.startAccount(plistURL: accountLaunchAgentURL)

            if target.isAlreadyBound {
                try await waitForCommittedBinding(
                    bindingID: target.id,
                    generation: target.generation,
                    runID: runID,
                    healthNewerThan: committedHealthCheckpoint
                )
            } else {
                try await waitForCandidate(
                    bindingID: target.id,
                    generation: target.generation,
                    runID: runID
                )
            }
            _ = try journal.transition(runID: runID, to: .commitPending)
            if target.isAlreadyBound {
                let reconciled = try await account.refresh()
                guard isCommitted(reconciled, bindingID: target.id, generation: target.generation),
                      hasFreshCloudHealth(
                          reconciled,
                          bindingID: target.id,
                          generation: target.generation,
                          newerThan: committedHealthCheckpoint
                      )
                else {
                    throw DesktopMigrationCoordinatorError.commitAmbiguous
                }
            } else {
                do {
                    let confirmed = try await account.confirmBinding()
                    guard isCommitted(confirmed, bindingID: target.id, generation: target.generation) else {
                        throw DesktopMigrationCoordinatorError.commitAmbiguous
                    }
                } catch {
                    let reconciled = try await account.refresh()
                    if !isCommitted(reconciled, bindingID: target.id, generation: target.generation) {
                        guard isPending(reconciled, bindingID: target.id, generation: target.generation) else {
                            throw DesktopMigrationCoordinatorError.commitAmbiguous
                        }
                        throw DesktopMigrationCoordinatorError.commitNotApplied
                    }
                }
            }
            _ = try journal.transition(runID: runID, to: .accountActive)
            switch candidate {
            case .bundled:
                try installer.commitHermesSessionTokenFileMigration()
            case .components(_, let activationPlan):
                try installer.commitHermesSessionTokenFileMigration(
                    activationPlan: activationPlan
                )
            }
            return DesktopMigrationOutcome(
                runID: UUID(uuidString: runID)!.uuidString.lowercased(),
                releaseVersion: candidate.releaseVersion,
                bindingID: target.id,
                bindingGeneration: target.generation
            )
        } catch {
            let failureState = try? journal.load()?.state
            if failureState == .accountActive { throw error }
            if failureState == .commitPending,
               error as? DesktopMigrationCoordinatorError != .commitNotApplied {
                if (try? launchAgent.inspect().accountLoaded) == true {
                    try? launchAgent.stopAccount()
                }
                if (try? launchAgent.inspect().hermesLoaded) == true {
                    try? launchAgent.stopHermes()
                }
                _ = try? journal.transition(runID: runID, to: .rollbackAttentionRequired)
                throw error
            }
            do {
                try rollback(
                    activation: activation,
                    legacy: legacy,
                    runID: runID,
                    lastKnownGood: lastKnownGood
                )
            } catch {
                try? markRollbackAttention(runID: runID)
                throw DesktopMigrationCoordinatorError.rollbackFailed
            }
            throw error
        }
    }

    public func recoverInterrupted(
        legacy: LegacyConnectorSnapshot,
        runID: String
    ) async throws -> DesktopMigrationState {
        let operationLease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(operationLease) {} }
        guard let recorded = try journal.load() else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        guard recorded.runID == UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopMigrationJournalError.runMismatch
        }
        if recorded.lastKnownGoodMode == .account {
            switch recorded.state {
            case .accountActive:
                if let snapshot = try installer.loadManagedUpgradeSnapshot(runID: runID) {
                    try installer.discardManagedUpgradeSnapshot(snapshot)
                }
                return recorded.state
            case .rollbackAttentionRequired:
                return recorded.state
            default:
                guard let snapshot = try installer.loadManagedUpgradeSnapshot(runID: runID),
                      let bindingID = recorded.bindingID,
                      let generation = recorded.bindingGeneration
                else { return try stopForManualRecovery(runID: runID) }
                do {
                    try await rollbackUpgrade(
                        snapshot,
                        bindingID: bindingID,
                        generation: generation,
                        contract: .serveV1
                    )
                    return .accountActive
                } catch {
                    _ = try? markRollbackAttention(runID: runID)
                    throw DesktopMigrationCoordinatorError.rollbackFailed
                }
            }
        }
        switch recorded.state {
        case .cleanUninstalled, .legacyActive, .accountActive, .rollbackAttentionRequired:
            return recorded.state
        case .commitPending:
            guard let bindingID = recorded.bindingID,
                  let generation = recorded.bindingGeneration
            else { return try stopForManualRecovery(runID: runID) }
            let remote = try await account.refresh()
            if isCommitted(remote, bindingID: bindingID, generation: generation) {
                return try journal.transition(runID: runID, to: .accountActive).state
            }
            guard isPending(remote, bindingID: bindingID, generation: generation) else {
                return try stopForManualRecovery(runID: runID)
            }
            fallthrough
        case .preflight, .accountStaged, .candidateStarting,
             .candidateAuthenticated, .candidateHealthy, .rollingBack:
            do {
                if recorded.state != .rollingBack {
                    _ = try journal.transition(runID: runID, to: .rollingBack)
                }
                let services = try launchAgent.inspect()
                if services.accountLoaded { try launchAgent.stopAccount() }
                if services.hermesLoaded { try launchAgent.stopHermes() }
                if recorded.releaseLayout == .bundledRelease {
                    try installer.deactivateExpectedRelease(recorded.releaseVersion)
                }
                if recorded.lastKnownGoodMode == .legacy {
                    let current = try launchAgent.inspect()
                    if !current.legacyLoaded { try launchAgent.restoreLegacy(snapshot: legacy) }
                    return try journal.transition(runID: runID, to: .legacyActive).state
                }
                return try journal.transition(runID: runID, to: .cleanUninstalled).state
            } catch {
                _ = try? markRollbackAttention(runID: runID)
                throw DesktopMigrationCoordinatorError.rollbackFailed
            }
        }
    }

    /// Re-establishes the single-Connector invariant after Migration Assistant restores both
    /// LaunchAgents. This is allowed only for a durably committed account installation whose two
    /// managed services are already loaded; it never starts or rewrites a service.
    @discardableResult
    public func reconcileTransferredAccountActive() throws -> Bool {
        guard let preview = try journal.loadReadOnly(),
              preview.state == .accountActive,
              preview.bindingID != nil,
              preview.bindingGeneration != nil
        else { return false }
        let operationLease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(operationLease) {} }
        guard let recorded = try journal.load(),
              recorded.state == .accountActive,
              recorded.bindingID != nil,
              recorded.bindingGeneration != nil
        else { return false }
        let services = launchAgent.inspectAllowingDuplicateConnector()
        guard services.accountLoaded, services.hermesLoaded else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        try launchAgent.suppressTransferredLegacyForActiveManagedInstallation()
        return services.legacyLoaded
    }

    /// Moves a committed pre-contract installation from inline LaunchAgent credentials to the
    /// private token-file contract. The service restart and both health proofs are part of the
    /// transaction; any failure restores the exact previous files and restarts that configuration.
    @discardableResult
    public func reconcileCommittedHermesSessionTokenStorage() async throws -> Bool {
        guard let preview = try journal.loadReadOnly(),
              preview.state == .accountActive,
              let previewBindingID = preview.bindingID,
              let previewGeneration = preview.bindingGeneration
        else { return false }
        guard Self.supportsSessionTokenFile(releaseVersion: preview.releaseVersion) else {
            return false
        }
        let operationLease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(operationLease) {} }
        guard let recorded = try journal.load(),
              recorded.state == .accountActive,
              recorded.bindingID == previewBindingID,
              recorded.bindingGeneration == previewGeneration
        else { return false }

        let preflight = try await account.refresh()
        guard hasExactBoundBinding(
            preflight,
            bindingID: previewBindingID,
            generation: previewGeneration
        ) else { throw DesktopMigrationCoordinatorError.invalidBindingState }

        let services = launchAgent.inspectAllowingDuplicateConnector()
        guard services.accountLoaded, services.hermesLoaded else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        if services.legacyLoaded {
            try launchAgent.suppressTransferredLegacyForActiveManagedInstallation()
        }
        guard let migration = try installer.prepareHermesSessionTokenFileMigration() else {
            return false
        }

        do {
            let checkpoint = try hermesReadiness.checkpoint(logURL: migration.hermesLogURL)
            try launchAgent.stopAccount()
            try launchAgent.stopHermes()
            try launchAgent.startHermes(plistURL: migration.hermesLaunchAgentURL)
            guard try await hermesReadiness.waitUntilReady(
                checkpoint: checkpoint,
                contract: .serveV1,
                maximumAttempts: maximumHealthPolls,
                delayNanoseconds: healthPollDelayNanoseconds
            ) else { throw DesktopMigrationCoordinatorError.hermesHealthTimedOut }
            let cloudHealthCheckpoint = try await captureBoundHealthCheckpoint(
                bindingID: previewBindingID,
                generation: previewGeneration
            )
            try launchAgent.startAccount(plistURL: migration.connectorLaunchAgentURL)
            try await waitForExistingCommittedBinding(
                bindingID: previewBindingID,
                generation: previewGeneration,
                healthNewerThan: cloudHealthCheckpoint
            )
            try installer.commitHermesSessionTokenFileMigration()
            return true
        } catch {
            do {
                try await rollbackHermesSessionTokenFileMigration(
                    migration,
                    bindingID: previewBindingID,
                    generation: previewGeneration
                )
            } catch {
                throw DesktopMigrationCoordinatorError.rollbackFailed
            }
            throw error
        }
    }

    /// Gives a committed managed installation the Hermes search path it predates, restarting only the
    /// Hermes server so the new environment is actually read.
    ///
    /// launchd reads a LaunchAgent's environment at bootstrap, so the agent this rewrites has no
    /// effect until the job is started again — which is why the restart is part of the operation and
    /// not left to the next reboot. Only Hermes is restarted: the Connector's own agent is untouched
    /// here, unlike the token-file contract where both files changed. That follows
    /// `DesktopOnDemandRuntimeActivator`, which restarts Hermes alone after rewriting the same agent.
    ///
    /// Idempotent without a marker: [DesktopManagedInstaller.prepareHermesSearchPathRepair] answers
    /// nil once the key is present, so this returns false and touches no service. A machine therefore
    /// pays for one Hermes restart, once, and a machine that never needed the repair pays nothing.
    ///
    /// The cost is stated plainly because it is real: the restart is not announced and cannot be
    /// declined, and nothing here can tell whether a turn is in flight — no Hermes API reports it and
    /// Desktop has never had such a check (the token-file reconcile above restarts both services on
    /// the same terms). What bounds it is that it happens at most once per machine, at app launch.
    @discardableResult
    public func reconcileCommittedHermesSearchPath() async throws -> Bool {
        guard let preview = try journal.loadReadOnly(),
              preview.state == .accountActive,
              let previewBindingID = preview.bindingID,
              let previewGeneration = preview.bindingGeneration
        else { return false }
        let operationLease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(operationLease) {} }
        guard let recorded = try journal.load(),
              recorded.state == .accountActive,
              recorded.bindingID == previewBindingID,
              recorded.bindingGeneration == previewGeneration
        else { return false }

        let services = launchAgent.inspectAllowingDuplicateConnector()
        guard services.accountLoaded, services.hermesLoaded else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        guard let repair = try installer.prepareHermesSearchPathRepair() else { return false }

        do {
            let checkpoint = try hermesReadiness.checkpoint(logURL: repair.logURL)
            try launchAgent.stopHermes()
            try launchAgent.startHermes(plistURL: repair.launchAgentURL)
            guard try await hermesReadiness.waitUntilReady(
                checkpoint: checkpoint,
                contract: .serveV1,
                maximumAttempts: maximumHealthPolls,
                delayNanoseconds: healthPollDelayNanoseconds
            ) else { throw DesktopMigrationCoordinatorError.hermesHealthTimedOut }
            return true
        } catch {
            do {
                try installer.rollbackHermesSearchPathRepair(repair)
                if launchAgent.inspectAllowingDuplicateConnector().hermesLoaded {
                    try launchAgent.stopHermes()
                }
                try launchAgent.startHermes(plistURL: repair.launchAgentURL)
                let restored = try hermesReadiness.checkpoint(logURL: repair.logURL)
                guard try await hermesReadiness.waitUntilReady(
                    checkpoint: restored,
                    contract: .serveV1,
                    maximumAttempts: maximumHealthPolls,
                    delayNanoseconds: healthPollDelayNanoseconds
                ) else { throw DesktopMigrationCoordinatorError.rollbackFailed }
            } catch {
                throw DesktopMigrationCoordinatorError.rollbackFailed
            }
            throw error
        }
    }

    private func rollbackUpgrade(
        _ snapshot: DesktopManagedUpgradeSnapshot,
        bindingID: String,
        generation: Int,
        contract: DesktopHermesRuntimeContract
    ) async throws {
        let current = try journal.load()
        if current?.state != .rollingBack {
            _ = try journal.transition(runID: snapshot.runID, to: .rollingBack)
        }
        var services = try launchAgent.inspect()
        guard !services.legacyLoaded else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        if services.accountLoaded { try launchAgent.stopAccount() }
        services = try launchAgent.inspect()
        if services.hermesLoaded { try launchAgent.stopHermes() }
        guard try await hermesShutdown.waitUntilStopped(
            contract: contract,
            maximumAttempts: maximumHealthPolls,
            delayNanoseconds: healthPollDelayNanoseconds
        ) else { throw DesktopMigrationCoordinatorError.hermesStopTimedOut }

        try installer.restoreManagedUpgradeSnapshot(snapshot)
        let checkpoint = try hermesReadiness.checkpoint(logURL: installer.managedHermesLogURL)
        try launchAgent.startHermes(plistURL: installer.managedHermesLaunchAgentURL)
        guard try await hermesReadiness.waitUntilReady(
            checkpoint: checkpoint,
            contract: contract,
            maximumAttempts: maximumHealthPolls,
            delayNanoseconds: healthPollDelayNanoseconds
        ) else { throw DesktopMigrationCoordinatorError.hermesHealthTimedOut }
        let healthCheckpoint = try await captureBoundHealthCheckpoint(
            bindingID: bindingID,
            generation: generation
        )
        try launchAgent.startAccount(plistURL: installer.managedConnectorLaunchAgentURL)
        try await waitForExistingCommittedBinding(
            bindingID: bindingID,
            generation: generation,
            healthNewerThan: healthCheckpoint
        )
        _ = try journal.completeUpgradeRollback(
            runID: snapshot.runID,
            previousReleaseVersion: snapshot.previousReleaseVersion,
            previousReleaseLayout: snapshot.previousReleaseLayout,
            targetReleaseLayout: snapshot.targetReleaseLayout,
            bindingID: bindingID,
            bindingGeneration: generation
        )
        try installer.discardManagedUpgradeSnapshot(snapshot)
    }

    private func waitForCandidate(bindingID: String, generation: Int, runID: String) async throws {
        var authenticated = false
        for attempt in 0..<maximumHealthPolls {
            let state = try await account.refresh()
            guard case .signedIn(let dashboard) = state,
                  dashboard.binding.id == bindingID,
                  dashboard.binding.generation == generation,
                  dashboard.binding.state == "binding_pending"
            else { throw DesktopMigrationCoordinatorError.invalidBindingState }
            if dashboard.binding.keyProved == true, !authenticated {
                _ = try journal.transition(runID: runID, to: .candidateAuthenticated)
                authenticated = true
            }
            if authenticated, dashboard.binding.healthVerified == true {
                _ = try journal.transition(runID: runID, to: .candidateHealthy)
                return
            }
            if attempt + 1 < maximumHealthPolls, healthPollDelayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: healthPollDelayNanoseconds)
            }
        }
        throw DesktopMigrationCoordinatorError.healthTimedOut
    }

    private func waitForCommittedBinding(
        bindingID: String,
        generation: Int,
        runID: String,
        healthNewerThan checkpoint: Date?
    ) async throws {
        for attempt in 0..<maximumHealthPolls {
            let state = try await account.refresh()
            guard case .signedIn(let dashboard) = state,
                  dashboard.binding.state == "bound",
                  let binding = dashboard.binding.binding,
                  binding.id == bindingID,
                  binding.generation == generation
            else { throw DesktopMigrationCoordinatorError.invalidBindingState }
            if isCommitted(state, bindingID: bindingID, generation: generation),
               hasFreshCloudHealth(
                   state,
                   bindingID: bindingID,
                   generation: generation,
                   newerThan: checkpoint
               ) {
                _ = try journal.transition(runID: runID, to: .candidateAuthenticated)
                _ = try journal.transition(runID: runID, to: .candidateHealthy)
                return
            }
            if attempt + 1 < maximumHealthPolls, healthPollDelayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: healthPollDelayNanoseconds)
            }
        }
        throw DesktopMigrationCoordinatorError.healthTimedOut
    }

    private func waitForExistingCommittedBinding(
        bindingID: String,
        generation: Int,
        healthNewerThan checkpoint: Date?
    ) async throws {
        for attempt in 0..<maximumHealthPolls {
            let state = try await account.refresh()
            guard hasExactBoundBinding(state, bindingID: bindingID, generation: generation) else {
                throw DesktopMigrationCoordinatorError.invalidBindingState
            }
            if isCommitted(state, bindingID: bindingID, generation: generation),
               hasFreshCloudHealth(
                   state,
                   bindingID: bindingID,
                   generation: generation,
                   newerThan: checkpoint
               ) { return }
            if attempt + 1 < maximumHealthPolls, healthPollDelayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: healthPollDelayNanoseconds)
            }
        }
        throw DesktopMigrationCoordinatorError.healthTimedOut
    }

    private func rollbackHermesSessionTokenFileMigration(
        _ migration: DesktopHermesSessionTokenMigration,
        bindingID: String,
        generation: Int
    ) async throws {
        var services = launchAgent.inspectAllowingDuplicateConnector()
        guard !services.legacyLoaded else {
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        if services.accountLoaded { try launchAgent.stopAccount() }
        services = launchAgent.inspectAllowingDuplicateConnector()
        if services.hermesLoaded { try launchAgent.stopHermes() }
        try installer.rollbackHermesSessionTokenFileMigration(migration)

        let checkpoint = try hermesReadiness.checkpoint(logURL: migration.hermesLogURL)
        try launchAgent.startHermes(plistURL: migration.hermesLaunchAgentURL)
        guard try await hermesReadiness.waitUntilReady(
            checkpoint: checkpoint,
            contract: .serveV1,
            maximumAttempts: maximumHealthPolls,
            delayNanoseconds: healthPollDelayNanoseconds
        ) else { throw DesktopMigrationCoordinatorError.hermesHealthTimedOut }
        let cloudHealthCheckpoint = try await captureBoundHealthCheckpoint(
            bindingID: bindingID,
            generation: generation
        )
        try launchAgent.startAccount(plistURL: migration.connectorLaunchAgentURL)
        try await waitForExistingCommittedBinding(
            bindingID: bindingID,
            generation: generation,
            healthNewerThan: cloudHealthCheckpoint
        )
    }

    private func rollback(
        activation: DesktopReleaseActivation?,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        lastKnownGood: DesktopLastKnownGoodMode
    ) throws {
        let current = try journal.load()
        if current?.state != .rollingBack {
            _ = try journal.transition(runID: runID, to: .rollingBack)
        }
        let services = try launchAgent.inspect()
        if services.accountLoaded { try launchAgent.stopAccount() }
        let afterConnectorStop = try launchAgent.inspect()
        if afterConnectorStop.hermesLoaded { try launchAgent.stopHermes() }
        if let activation { try installer.rollback(activation, runID: runID) }
        if legacy.isRunning {
            let afterStop = try launchAgent.inspect()
            if !afterStop.legacyLoaded { try launchAgent.restoreLegacy(snapshot: legacy) }
        }
        _ = try journal.transition(
            runID: runID,
            to: lastKnownGood == .legacy ? .legacyActive : .cleanUninstalled
        )
    }

    private func markRollbackAttention(runID: String) throws {
        let state = try journal.load()?.state
        if state != .rollingBack { _ = try journal.transition(runID: runID, to: .rollingBack) }
        _ = try journal.transition(runID: runID, to: .rollbackAttentionRequired)
    }

    private func stopForManualRecovery(runID: String) throws -> DesktopMigrationState {
        if (try? launchAgent.inspect().accountLoaded) == true { try launchAgent.stopAccount() }
        if (try? launchAgent.inspect().hermesLoaded) == true { try launchAgent.stopHermes() }
        _ = try journal.transition(runID: runID, to: .rollbackAttentionRequired)
        return .rollbackAttentionRequired
    }

    private func bindingTarget(
        _ state: DesktopAccountState,
        retryingTerminalBinding: (id: String, generation: Int)?
    ) throws -> (id: String, generation: Int, isAlreadyBound: Bool) {
        guard case .signedIn(let dashboard) = state else {
            throw DesktopMigrationCoordinatorError.invalidBindingState
        }
        if dashboard.binding.state == "binding_pending",
           let id = dashboard.binding.id,
           let generation = dashboard.binding.generation {
            return (id, generation, false)
        }
        if dashboard.binding.state == "bound",
           let retryingTerminalBinding,
           let binding = dashboard.binding.binding,
           binding.id == retryingTerminalBinding.id,
           binding.generation == retryingTerminalBinding.generation {
            return (binding.id, binding.generation, true)
        }
        throw DesktopMigrationCoordinatorError.invalidBindingState
    }

    private func terminalRetryBinding(
        lastKnownGood: DesktopLastKnownGoodMode
    ) throws -> (id: String, generation: Int)? {
        guard let recorded = try journal.load(),
              let bindingID = recorded.bindingID,
              let generation = recorded.bindingGeneration,
              generation > 0
        else { return nil }

        switch (recorded.state, recorded.lastKnownGoodMode, lastKnownGood) {
        case (.legacyActive, .legacy, .legacy),
             (.cleanUninstalled, .none, .none):
            return (bindingID, generation)
        default:
            return nil
        }
    }

    private func isCommitted(_ state: DesktopAccountState, bindingID: String, generation: Int) -> Bool {
        guard case .signedIn(let dashboard) = state,
              dashboard.binding.state == "bound",
              let binding = dashboard.binding.binding
        else { return false }
        return binding.id == bindingID
            && binding.generation == generation
            && binding.connector.online
            && binding.hermes.reachable == true
            && binding.endToEnd.healthy == true
    }

    private func captureBoundHealthCheckpoint(
        bindingID: String,
        generation: Int
    ) async throws -> Date? {
        let state = try await account.refresh()
        guard case .signedIn(let dashboard) = state,
              dashboard.binding.state == "bound",
              let binding = dashboard.binding.binding,
              binding.id == bindingID,
              binding.generation == generation
        else { throw DesktopMigrationCoordinatorError.invalidBindingState }
        guard let checkedAt = binding.endToEnd.checkedAt else { return nil }
        guard let parsed = Self.parseRFC3339(checkedAt) else {
            throw DesktopMigrationCoordinatorError.invalidBindingState
        }
        return parsed
    }

    private func hasFreshCloudHealth(
        _ state: DesktopAccountState,
        bindingID: String,
        generation: Int,
        newerThan checkpoint: Date?
    ) -> Bool {
        guard case .signedIn(let dashboard) = state,
              dashboard.binding.state == "bound",
              let binding = dashboard.binding.binding,
              binding.id == bindingID,
              binding.generation == generation,
              let checkedAt = binding.endToEnd.checkedAt,
              let current = Self.parseRFC3339(checkedAt)
        else { return false }
        guard let checkpoint else { return true }
        return current > checkpoint
    }

    private static func parseRFC3339(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }

        let wholeSeconds = ISO8601DateFormatter()
        wholeSeconds.formatOptions = [.withInternetDateTime]
        return wholeSeconds.date(from: value)
    }

    private static func supportsSessionTokenFile(releaseVersion: String) -> Bool {
        // Managed release 0.3.1 is the first immutable package whose Hermes wrapper and Connector
        // both consume HERMES_SESSION_TOKEN_FILE. Release 0.3.0's Connector accepts only the inline
        // token, so rewriting its LaunchAgent leaves its Gateway control socket online while every
        // tunneled local WebSocket fails authentication.
        let firstSessionTokenFileRelease = [0, 3, 1]
        let components = releaseVersion.split(separator: ".", omittingEmptySubsequences: false)
        guard components.count == 3 else { return false }
        let parsed = components.compactMap { Int($0) }
        guard parsed.count == 3 else { return false }
        return parsed.lexicographicallyPrecedes(firstSessionTokenFileRelease) == false
    }

    private static func version(_ candidate: String, isNewerThan installed: String) -> Bool {
        let candidateParts = candidate.split(separator: ".", omittingEmptySubsequences: false)
        let installedParts = installed.split(separator: ".", omittingEmptySubsequences: false)
        guard candidateParts.count == 3, installedParts.count == 3 else { return false }
        let candidateNumbers = candidateParts.compactMap { Int($0) }
        let installedNumbers = installedParts.compactMap { Int($0) }
        guard candidateNumbers.count == 3, installedNumbers.count == 3 else { return false }
        return candidateNumbers != installedNumbers
            && candidateNumbers.lexicographicallyPrecedes(installedNumbers) == false
    }

    private static func validComponentCandidate(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan
    ) -> Bool {
        guard DesktopComponentReleaseActivationPlanner.validBootstrapManifest(manifest),
              activationPlan.releaseVersion == manifest.releaseVersion
        else { return false }

        let artifacts = manifest.components.filter { $0.installPhase == .bootstrap }
        guard activationPlan.components.count == artifacts.count else { return false }
        return artifacts.allSatisfy { artifact in
            guard let component = activationPlan.component(artifact.kind) else { return false }
            let expectedEntrypoint = component.root
                .appendingPathComponent(artifact.entrypoint)
                .standardizedFileURL
            return component.contentSHA256 == artifact.contentSHA256
                && component.entrypoint.standardizedFileURL.path == expectedEntrypoint.path
        }
    }

    private func hasExactBoundBinding(
        _ state: DesktopAccountState,
        bindingID: String,
        generation: Int
    ) -> Bool {
        guard case .signedIn(let dashboard) = state,
              dashboard.binding.state == "bound",
              let binding = dashboard.binding.binding
        else { return false }
        return binding.id == bindingID && binding.generation == generation
    }

    private func isPending(_ state: DesktopAccountState, bindingID: String, generation: Int) -> Bool {
        guard case .signedIn(let dashboard) = state else { return false }
        return dashboard.binding.state == "binding_pending"
            && dashboard.binding.id == bindingID
            && dashboard.binding.generation == generation
    }

}

extension DesktopMigrationCoordinator: DesktopComponentBootstrapMigrating {}
