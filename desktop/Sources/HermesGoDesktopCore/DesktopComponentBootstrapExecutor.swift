import Foundation

public protocol DesktopComponentReleaseInstalling: Sendable {
    func prepare(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        workspaceRoot: URL,
        runID: String,
        healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    ) async throws -> DesktopPreparedComponentRelease

    func commit(
        _ preparation: DesktopPreparedComponentRelease,
        healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    ) throws -> DesktopInstalledComponentRelease

    func discard(_ preparation: DesktopPreparedComponentRelease) throws
    func discard(_ release: DesktopInstalledComponentRelease) throws
    func discardInterruptedInstall(workspaceRoot: URL, runID: String) throws
}

extension DesktopComponentReleaseInstaller: DesktopComponentReleaseInstalling {}

/// The persistent migration implementation is intentionally a separate boundary: it owns
/// credentials, LaunchAgents, service ordering, account binding, journaling, and rollback. The
/// component bootstrap executor never mutates those surfaces directly.
public protocol DesktopComponentBootstrapMigrating: Sendable {
    func migrateComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome

    func upgradeComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome
}

public extension DesktopComponentBootstrapMigrating {
    func upgradeComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        throw DesktopComponentBootstrapExecutorError.releaseNotNewer
    }
}

public enum DesktopComponentBootstrapExecutorError: Error, Equatable, Sendable {
    case operationInProgress
    case invalidPreflight
    case notPrepared
    case preparationMismatch
    case confirmationRequired
    case cleanupFailed
    case releaseNotNewer
}

public struct DesktopComponentBootstrapPreparation: Equatable, Identifiable, Sendable {
    public let id: String
    public let runID: String
    public let releaseVersion: String
    public let confirmationText: String
    public let preflight: DesktopComponentReleasePreflightResult
    public let intent: DesktopManagedBootstrapIntent

    fileprivate init(
        id: String,
        runID: String,
        releaseVersion: String,
        confirmationText: String,
        preflight: DesktopComponentReleasePreflightResult,
        intent: DesktopManagedBootstrapIntent
    ) {
        self.id = id
        self.runID = runID
        self.releaseVersion = releaseVersion
        self.confirmationText = confirmationText
        self.preflight = preflight
        self.intent = intent
    }
}

public struct DesktopComponentBootstrapOutcome: Equatable, Sendable {
    public let migration: DesktopMigrationOutcome
    public let manifest: DesktopComponentReleaseManifestV2
    public let activationPlan: DesktopComponentReleaseActivationPlan
    public let referenceURL: URL

    /// A committed migration stays successful when private cache cleanup needs a retry. The
    /// content-addressed store and its reference are durable installation state, not temporary data.
    public let temporaryWorkspaceRemoved: Bool
    public let cleanupRetry: DesktopComponentBootstrapPreparation?
}

/// Joins one verifier-issued preflight session to component preparation and the existing migration
/// transaction boundary. `prepare` may write only beneath the owner-private cache. No component
/// store, credential, LaunchAgent, binding, or process changes occur until `commit` receives the
/// exact preparation and release-specific confirmation. Component content committed before a later
/// migration failure remains an unactivated immutable cache/reference and is safe for reuse or GC.
public actor DesktopComponentBootstrapExecutor {
    private let installer: any DesktopComponentReleaseInstalling
    private let migration: any DesktopComponentBootstrapMigrating
    private var state: State = .idle

    private enum State {
        case idle
        case preparing
        case prepared(PendingPreparation)
        case committing
        case cleanupPending(PendingCleanup)
    }

    private struct PendingPreparation: Sendable {
        let publicValue: DesktopComponentBootstrapPreparation
        let componentValue: DesktopPreparedComponentRelease
        let healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    }

    private enum CleanupValue: Sendable {
        case prepared(DesktopPreparedComponentRelease)
        case installed(DesktopInstalledComponentRelease)
    }

    private struct PendingCleanup: Sendable {
        let publicValue: DesktopComponentBootstrapPreparation
        let componentValue: CleanupValue
    }

    public init(
        installer: any DesktopComponentReleaseInstalling,
        migration: any DesktopComponentBootstrapMigrating
    ) {
        self.installer = installer
        self.migration = migration
    }

    public func prepare(
        trustedPreflight: DesktopTrustedComponentPreflight,
        workspaceRoot: URL,
        runID: String,
        installation: DesktopManagedBootstrapInstallationStatus = .absent,
        healthProbe: @escaping DesktopComponentReleaseInstaller.HealthProbe
    ) async throws -> DesktopComponentBootstrapPreparation {
        guard case .idle = state else {
            throw DesktopComponentBootstrapExecutorError.operationInProgress
        }
        guard trustedPreflight.result.manifest == trustedPreflight.verifiedManifest.manifest else {
            throw DesktopComponentBootstrapExecutorError.invalidPreflight
        }
        let intent: DesktopManagedBootstrapIntent
        switch installation {
        case .absent:
            intent = .install
        case .active(let installed, let layout, _, _):
            guard layout == .componentStore else {
                throw DesktopComponentBootstrapExecutorError.releaseNotNewer
            }
            guard Self.version(
                trustedPreflight.result.manifest.releaseVersion,
                isNewerThan: installed
            ) else { throw DesktopComponentBootstrapExecutorError.releaseNotNewer }
            intent = .upgrade(fromReleaseVersion: installed)
        case .interrupted, .attentionRequired, .inconsistent:
            throw DesktopMigrationCoordinatorError.invalidStartingState
        }
        state = .preparing

        let componentValue: DesktopPreparedComponentRelease
        do {
            componentValue = try await installer.prepare(
                verifiedManifest: trustedPreflight.verifiedManifest,
                workspaceRoot: workspaceRoot,
                runID: runID,
                healthProbe: healthProbe
            )
        } catch {
            state = .idle
            throw error
        }

        let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() ?? runID
        guard componentValue.manifest == trustedPreflight.result.manifest,
              componentValue.runID == normalizedRunID
        else {
            return try discardInvalidPreparation(componentValue)
        }

        do {
            try Task.checkCancellation()
        } catch {
            state = .idle
            do { try installer.discard(componentValue) }
            catch {
                let publicValue = makePublicPreparation(
                    preflight: trustedPreflight.result,
                    runID: normalizedRunID,
                    intent: intent
                )
                state = .cleanupPending(PendingCleanup(
                    publicValue: publicValue,
                    componentValue: .prepared(componentValue)
                ))
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
            throw error
        }

        let publicValue = makePublicPreparation(
            preflight: trustedPreflight.result,
            runID: normalizedRunID,
            intent: intent
        )
        state = .prepared(PendingPreparation(
            publicValue: publicValue,
            componentValue: componentValue,
            healthProbe: healthProbe
        ))
        return publicValue
    }

    public func commit(
        _ preparation: DesktopComponentBootstrapPreparation,
        configuration: DesktopManagedBootstrapCommitConfiguration,
        legacy: LegacyConnectorSnapshot,
        confirmation: String
    ) async throws -> DesktopComponentBootstrapOutcome {
        guard case .prepared(let pending) = state else {
            switch state {
            case .preparing, .committing, .cleanupPending:
                throw DesktopComponentBootstrapExecutorError.operationInProgress
            case .idle:
                throw DesktopComponentBootstrapExecutorError.notPrepared
            case .prepared:
                preconditionFailure("handled above")
            }
        }
        guard pending.publicValue == preparation else {
            throw DesktopComponentBootstrapExecutorError.preparationMismatch
        }
        guard confirmation == pending.publicValue.confirmationText else {
            throw DesktopComponentBootstrapExecutorError.confirmationRequired
        }
        state = .committing

        let installed: DesktopInstalledComponentRelease
        do {
            try Task.checkCancellation()
            installed = try installer.commit(
                pending.componentValue,
                healthProbe: pending.healthProbe
            )
        } catch {
            if cleanupPreparedAfterFailedCommit(pending) == false {
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
            throw error
        }

        let launchAgents: (
            hermes: DesktopHermesServerLaunchAgent,
            connector: DesktopAccountConnectorLaunchAgent
        )
        do {
            launchAgents = try configuration.componentLaunchAgents(
                for: installed.activationPlan
            )
        } catch {
            if cleanupInstalled(installed, publicValue: pending.publicValue) == false {
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
            throw error
        }

        let migrationOutcome: DesktopMigrationOutcome
        do {
            switch pending.publicValue.intent {
            case .install:
                migrationOutcome = try await migration.migrateComponentRelease(
                    manifest: installed.manifest,
                    activationPlan: installed.activationPlan,
                    hermesLaunchAgentConfiguration: launchAgents.hermes,
                    launchAgentConfiguration: launchAgents.connector,
                    legacy: legacy,
                    runID: pending.publicValue.runID,
                    confirmation: confirmation
                )
            case .upgrade:
                migrationOutcome = try await migration.upgradeComponentRelease(
                    manifest: installed.manifest,
                    activationPlan: installed.activationPlan,
                    hermesLaunchAgentConfiguration: launchAgents.hermes,
                    launchAgentConfiguration: launchAgents.connector,
                    runID: pending.publicValue.runID,
                    confirmation: confirmation
                )
            }
        } catch {
            if cleanupInstalled(installed, publicValue: pending.publicValue) == false {
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
            throw error
        }

        let workspaceRemoved = cleanupInstalled(
            installed,
            publicValue: pending.publicValue
        )
        return DesktopComponentBootstrapOutcome(
            migration: migrationOutcome,
            manifest: installed.manifest,
            activationPlan: installed.activationPlan,
            referenceURL: installed.referenceURL,
            temporaryWorkspaceRemoved: workspaceRemoved,
            cleanupRetry: workspaceRemoved ? nil : pending.publicValue
        )
    }

    public func cancel(_ preparation: DesktopComponentBootstrapPreparation) throws {
        switch state {
        case .prepared(let pending):
            guard pending.publicValue == preparation else {
                throw DesktopComponentBootstrapExecutorError.preparationMismatch
            }
            state = .committing
            do {
                try installer.discard(pending.componentValue)
                state = .idle
            } catch {
                state = .cleanupPending(PendingCleanup(
                    publicValue: pending.publicValue,
                    componentValue: .prepared(pending.componentValue)
                ))
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
        case .cleanupPending(let pending):
            guard pending.publicValue == preparation else {
                throw DesktopComponentBootstrapExecutorError.preparationMismatch
            }
            state = .committing
            do {
                try discard(pending.componentValue)
                state = .idle
            } catch {
                state = .cleanupPending(pending)
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
        case .preparing, .committing:
            throw DesktopComponentBootstrapExecutorError.operationInProgress
        case .idle:
            throw DesktopComponentBootstrapExecutorError.notPrepared
        }
    }

    public func retryCleanup(_ preparation: DesktopComponentBootstrapPreparation) throws {
        guard case .cleanupPending = state else {
            throw DesktopComponentBootstrapExecutorError.notPrepared
        }
        try cancel(preparation)
    }

    /// Removes a resumable transport-interruption workspace before `prepare` returned a public
    /// handle. The installer accepts only the exact UUID directory with its valid private marker.
    public func discardInterruptedPreparation(workspaceRoot: URL, runID: String) throws {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopComponentBootstrapExecutorError.invalidPreflight
        }
        if case .cleanupPending(let pending) = state {
            guard pending.publicValue.runID == normalizedRunID else {
                throw DesktopComponentBootstrapExecutorError.preparationMismatch
            }
            state = .committing
            do {
                try discard(pending.componentValue)
                state = .idle
            } catch {
                state = .cleanupPending(pending)
                throw DesktopComponentBootstrapExecutorError.cleanupFailed
            }
            return
        }
        guard case .idle = state else {
            throw DesktopComponentBootstrapExecutorError.operationInProgress
        }
        state = .committing
        defer {
            if case .committing = state { state = .idle }
        }
        try installer.discardInterruptedInstall(
            workspaceRoot: workspaceRoot,
            runID: normalizedRunID
        )
    }

    private func makePublicPreparation(
        preflight: DesktopComponentReleasePreflightResult,
        runID: String,
        intent: DesktopManagedBootstrapIntent
    ) -> DesktopComponentBootstrapPreparation {
        DesktopComponentBootstrapPreparation(
            id: UUID().uuidString.lowercased(),
            runID: runID,
            releaseVersion: preflight.manifest.releaseVersion,
            confirmationText: DesktopMigrationCoordinator<SystemCommandRunner>
                .confirmationText(releaseVersion: preflight.manifest.releaseVersion),
            preflight: preflight,
            intent: intent
        )
    }

    private func discardInvalidPreparation<T>(
        _ componentValue: DesktopPreparedComponentRelease
    ) throws -> T {
        state = .idle
        do { try installer.discard(componentValue) }
        catch { throw DesktopComponentBootstrapExecutorError.cleanupFailed }
        throw DesktopComponentBootstrapExecutorError.invalidPreflight
    }

    private func cleanupPreparedAfterFailedCommit(_ pending: PendingPreparation) -> Bool {
        do {
            try installer.discard(pending.componentValue)
            state = .idle
            return true
        } catch {
            state = .cleanupPending(PendingCleanup(
                publicValue: pending.publicValue,
                componentValue: .prepared(pending.componentValue)
            ))
            return false
        }
    }

    private func cleanupInstalled(
        _ installed: DesktopInstalledComponentRelease,
        publicValue: DesktopComponentBootstrapPreparation
    ) -> Bool {
        do {
            try installer.discard(installed)
            state = .idle
            return true
        } catch {
            state = .cleanupPending(PendingCleanup(
                publicValue: publicValue,
                componentValue: .installed(installed)
            ))
            return false
        }
    }

    private func discard(_ value: CleanupValue) throws {
        switch value {
        case .prepared(let preparation):
            try installer.discard(preparation)
        case .installed(let release):
            try installer.discard(release)
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
