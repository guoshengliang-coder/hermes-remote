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
    private let account: any DesktopBindingCoordinating
    private let journal: DesktopMigrationJournalStore
    private let installer: DesktopManagedInstaller
    private let launchAgent: DesktopLaunchAgentController<Runner>
    private let hermesReadiness: any DesktopHermesCandidateReadinessChecking
    private let maximumHealthPolls: Int
    private let healthPollDelayNanoseconds: UInt64

    public init(
        account: any DesktopBindingCoordinating,
        journal: DesktopMigrationJournalStore,
        installer: DesktopManagedInstaller,
        launchAgent: DesktopLaunchAgentController<Runner>,
        hermesReadiness: any DesktopHermesCandidateReadinessChecking
            = DesktopHermesCandidateReadinessChecker(),
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
        guard confirmation == Self.confirmationText(releaseVersion: manifest.releaseVersion) else {
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
            releaseVersion: manifest.releaseVersion,
            bindingID: target.id,
            bindingGeneration: target.generation
        )
        var activation: DesktopReleaseActivation?
        do {
            _ = try installer.stageRelease(manifest: manifest, runID: runID, sources: sources)
            _ = try installer.writeCredential(preparation.credential)
            _ = try installer.ensureHermesSessionToken()
            let hermesLaunchAgentURL = try installer.writeHermesLaunchAgent(
                hermesLaunchAgentConfiguration,
                manifest: manifest
            )
            let accountLaunchAgentURL = try installer.writeLaunchAgent(
                launchAgentConfiguration,
                manifest: manifest
            )
            _ = try journal.transition(runID: runID, to: .accountStaged)
            _ = try journal.transition(runID: runID, to: .candidateStarting)
            activation = try installer.activate(releaseVersion: manifest.releaseVersion, runID: runID)
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
            try launchAgent.startAccount(plistURL: accountLaunchAgentURL)

            if target.isAlreadyBound {
                try await waitForCommittedBinding(
                    bindingID: target.id,
                    generation: target.generation,
                    runID: runID
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
                guard isCommitted(reconciled, bindingID: target.id, generation: target.generation) else {
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
            try installer.commitHermesSessionTokenFileMigration()
            return DesktopMigrationOutcome(
                runID: UUID(uuidString: runID)!.uuidString.lowercased(),
                releaseVersion: manifest.releaseVersion,
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
                try installer.deactivateExpectedRelease(recorded.releaseVersion)
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
            try launchAgent.startAccount(plistURL: migration.connectorLaunchAgentURL)
            try await waitForExistingCommittedBinding(
                bindingID: previewBindingID,
                generation: previewGeneration
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
        runID: String
    ) async throws {
        for attempt in 0..<maximumHealthPolls {
            let state = try await account.refresh()
            guard case .signedIn(let dashboard) = state,
                  dashboard.binding.state == "bound",
                  let binding = dashboard.binding.binding,
                  binding.id == bindingID,
                  binding.generation == generation
            else { throw DesktopMigrationCoordinatorError.invalidBindingState }
            if isCommitted(state, bindingID: bindingID, generation: generation) {
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

    private func waitForExistingCommittedBinding(bindingID: String, generation: Int) async throws {
        for attempt in 0..<maximumHealthPolls {
            let state = try await account.refresh()
            guard hasExactBoundBinding(state, bindingID: bindingID, generation: generation) else {
                throw DesktopMigrationCoordinatorError.invalidBindingState
            }
            if isCommitted(state, bindingID: bindingID, generation: generation) { return }
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
        try launchAgent.startAccount(plistURL: migration.connectorLaunchAgentURL)
        try await waitForExistingCommittedBinding(bindingID: bindingID, generation: generation)
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
