import Foundation

public struct DesktopPreparedOnDemandRuntime: Sendable {
    public let launchAgentURL: URL
    public let logURL: URL
    public let runtimeContract: DesktopHermesRuntimeContract

    fileprivate let replacement: DesktopHermesLaunchAgentReplacement?

    public init(
        launchAgentURL: URL,
        logURL: URL,
        runtimeContract: DesktopHermesRuntimeContract
    ) {
        self.launchAgentURL = launchAgentURL
        self.logURL = logURL
        self.runtimeContract = runtimeContract
        replacement = nil
    }

    fileprivate init(
        replacement: DesktopHermesLaunchAgentReplacement,
        runtimeContract: DesktopHermesRuntimeContract
    ) {
        launchAgentURL = replacement.launchAgentURL
        logURL = replacement.logURL
        self.runtimeContract = runtimeContract
        self.replacement = replacement
    }
}

public protocol DesktopOnDemandRuntimePreparing: Sendable {
    func prepare(
        installed: DesktopInstalledOnDemandCapability,
        activeComponents: [DesktopResolvedOnDemandComponent],
        activationPlan: DesktopComponentReleaseActivationPlan
    ) throws -> DesktopPreparedOnDemandRuntime
    func rollback(_ prepared: DesktopPreparedOnDemandRuntime) throws
}

public final class DesktopOnDemandRuntimePreparer: DesktopOnDemandRuntimePreparing, @unchecked Sendable {
    private let writer: DesktopOptionalComponentRuntimeWriter
    private let installer: DesktopManagedInstaller
    private let configuration: DesktopManagedBootstrapCommitConfiguration

    public init(
        writer: DesktopOptionalComponentRuntimeWriter,
        installer: DesktopManagedInstaller,
        configuration: DesktopManagedBootstrapCommitConfiguration
    ) {
        self.writer = writer
        self.installer = installer
        self.configuration = configuration
    }

    public func prepare(
        installed: DesktopInstalledOnDemandCapability,
        activeComponents: [DesktopResolvedOnDemandComponent],
        activationPlan: DesktopComponentReleaseActivationPlan
    ) throws -> DesktopPreparedOnDemandRuntime {
        guard installed.releaseVersion == activationPlan.releaseVersion,
              !activeComponents.isEmpty,
              Set(activeComponents.map(\.kind)).count == activeComponents.count,
              installed.components.allSatisfy(activeComponents.contains),
              let python = activationPlan.component(.pythonRuntime)
        else { throw DesktopOnDemandRuntimeActivationError.invalidInput }
        let environment = try writer.write(
            releaseVersion: installed.releaseVersion,
            pythonRuntimeRoot: python.root,
            components: activeComponents
        )
        let launchAgents = try configuration.componentLaunchAgents(
            for: activationPlan,
            optionalRuntime: environment
        )
        let replacement = try installer.replaceHermesLaunchAgent(
            launchAgents.hermes,
            activationPlan: activationPlan
        )
        return DesktopPreparedOnDemandRuntime(
            replacement: replacement,
            runtimeContract: configuration.runtimeContract
        )
    }

    public func rollback(_ prepared: DesktopPreparedOnDemandRuntime) throws {
        guard let replacement = prepared.replacement else {
            throw DesktopOnDemandRuntimeActivationError.invalidInput
        }
        try installer.rollbackHermesLaunchAgentReplacement(replacement)
    }
}

public protocol DesktopHermesServiceControlling {
    func inspect() throws -> DesktopLaunchAgentServiceState
    func stopHermes() throws
    func startHermes(plistURL: URL) throws
}

extension DesktopLaunchAgentController: DesktopHermesServiceControlling {}

public enum DesktopOnDemandRuntimeActivationError: Error, Equatable, Sendable {
    case invalidInput
    case operationInProgress
    case invalidStartingState
    case readinessTimedOut
    case activationFailed
    case rollbackFailed
}

/// Applies one already-installed optional capability to a healthy managed Hermes service. While the
/// migration lease is held, it resolves the complete active optional set before replacing the
/// LaunchAgent. The replacement is health-gated and restored on activation failure. The caller's
/// capability operation runs exactly once after the new runtime is ready; its own error is returned
/// without rolling back a healthy installation.
public actor DesktopOnDemandRuntimeActivator {
    private let journal: DesktopMigrationJournalStore
    private let resolver: any DesktopActiveOnDemandComponentResolving
    private let preparer: any DesktopOnDemandRuntimePreparing
    private let service: any DesktopHermesServiceControlling
    private let readiness: any DesktopHermesCandidateReadinessChecking
    private let maximumReadinessAttempts: Int
    private let readinessDelayNanoseconds: UInt64
    private var running = false

    public init(
        journal: DesktopMigrationJournalStore,
        resolver: any DesktopActiveOnDemandComponentResolving,
        preparer: any DesktopOnDemandRuntimePreparing,
        service: any DesktopHermesServiceControlling,
        readiness: any DesktopHermesCandidateReadinessChecking = DesktopHermesCandidateReadinessChecker(),
        maximumReadinessAttempts: Int = 75,
        readinessDelayNanoseconds: UInt64 = 1_000_000_000
    ) throws {
        guard (1...300).contains(maximumReadinessAttempts) else {
            throw DesktopOnDemandRuntimeActivationError.invalidInput
        }
        self.journal = journal
        self.resolver = resolver
        self.preparer = preparer
        self.service = service
        self.readiness = readiness
        self.maximumReadinessAttempts = maximumReadinessAttempts
        self.readinessDelayNanoseconds = readinessDelayNanoseconds
    }

    public func activateAndRetry<T: Sendable>(
        installed: DesktopInstalledOnDemandCapability,
        hermesLaunchAgentURL: URL,
        activationPlan: DesktopComponentReleaseActivationPlan,
        componentHealthProbe: DesktopOnDemandComponentInstaller.HealthProbe,
        retry: @Sendable () async throws -> T
    ) async throws -> T {
        guard !running else { throw DesktopOnDemandRuntimeActivationError.operationInProgress }
        running = true
        defer { running = false }

        let lease: DesktopMigrationOperationLease
        do { lease = try journal.acquireOperationLease() }
        catch DesktopMigrationJournalError.lockUnavailable {
            throw DesktopOnDemandRuntimeActivationError.operationInProgress
        }
        defer { withExtendedLifetime(lease) {} }

        guard let state = try journal.load(),
              state.state == .accountActive,
              state.releaseVersion == installed.releaseVersion,
              activationPlan.releaseVersion == installed.releaseVersion
        else { throw DesktopOnDemandRuntimeActivationError.invalidStartingState }
        let services = try service.inspect()
        guard !services.legacyLoaded, services.accountLoaded, services.hermesLoaded else {
            throw DesktopOnDemandRuntimeActivationError.invalidStartingState
        }

        let activeComponents = try resolver.resolve(
            installed: installed,
            hermesLaunchAgentURL: hermesLaunchAgentURL,
            healthProbe: componentHealthProbe
        )

        let prepared = try preparer.prepare(
            installed: installed,
            activeComponents: activeComponents,
            activationPlan: activationPlan
        )
        var hermesStopped = false
        do {
            let checkpoint = try readiness.checkpoint(logURL: prepared.logURL)
            try service.stopHermes()
            hermesStopped = true
            try service.startHermes(plistURL: prepared.launchAgentURL)
            guard try await readiness.waitUntilReady(
                checkpoint: checkpoint,
                contract: prepared.runtimeContract,
                maximumAttempts: maximumReadinessAttempts,
                delayNanoseconds: readinessDelayNanoseconds
            ) else { throw DesktopOnDemandRuntimeActivationError.readinessTimedOut }
        } catch {
            do {
                let failedState = try service.inspect()
                let restartOriginal = hermesStopped || !failedState.hermesLoaded
                if hermesStopped, failedState.hermesLoaded {
                    try service.stopHermes()
                }
                try preparer.rollback(prepared)
                if restartOriginal {
                    let checkpoint = try readiness.checkpoint(logURL: prepared.logURL)
                    try service.startHermes(plistURL: prepared.launchAgentURL)
                    guard try await readiness.waitUntilReady(
                        checkpoint: checkpoint,
                        contract: prepared.runtimeContract,
                        maximumAttempts: maximumReadinessAttempts,
                        delayNanoseconds: readinessDelayNanoseconds
                    ) else { throw DesktopOnDemandRuntimeActivationError.rollbackFailed }
                }
            } catch {
                throw DesktopOnDemandRuntimeActivationError.rollbackFailed
            }
            if let typed = error as? DesktopOnDemandRuntimeActivationError { throw typed }
            throw DesktopOnDemandRuntimeActivationError.activationFailed
        }

        return try await retry()
    }
}
