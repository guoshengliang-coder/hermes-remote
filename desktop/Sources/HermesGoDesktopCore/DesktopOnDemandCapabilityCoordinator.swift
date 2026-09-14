import Foundation

public enum DesktopOnDemandCapability: String, CaseIterable, Sendable {
    case browser
    case speech
    case document

    fileprivate var componentKind: DesktopManagedComponentKind {
        switch self {
        case .browser: .browserAutomation
        case .speech: .speechRuntime
        case .document: .documentTools
        }
    }
}

public enum DesktopOnDemandCapabilityCoordinatorError: Error, Equatable, Sendable {
    case invalidConfiguration
    case unsupportedCapability(DesktopOnDemandCapability)
    case operationInProgress
}

public protocol DesktopOnDemandCapabilityInstalling: Sendable {
    func install(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        trigger: String,
        workspaceRoot: URL,
        runID: String,
        healthProbe: DesktopOnDemandComponentInstaller.HealthProbe
    ) async throws -> DesktopInstalledOnDemandCapability
}

extension DesktopOnDemandComponentInstaller: DesktopOnDemandCapabilityInstalling {}

public protocol DesktopComponentReleaseActivationPlanning: Sendable {
    func plan(
        manifest: DesktopComponentReleaseManifestV2,
        healthProbe: DesktopComponentReleaseActivationPlanner.HealthProbe
    ) throws -> DesktopComponentReleaseActivationPlan
}

extension DesktopComponentReleaseActivationPlanner: DesktopComponentReleaseActivationPlanning {}

public protocol DesktopOnDemandRuntimeActivating: Sendable {
    func activateAndRetry<T: Sendable>(
        installed: DesktopInstalledOnDemandCapability,
        hermesLaunchAgentURL: URL,
        activationPlan: DesktopComponentReleaseActivationPlan,
        componentHealthProbe: DesktopOnDemandComponentInstaller.HealthProbe,
        retry: @Sendable () async throws -> T
    ) async throws -> T
}

extension DesktopOnDemandRuntimeActivator: DesktopOnDemandRuntimeActivating {}

/// Composes one verifier-backed optional-component install with exact base-plan regeneration,
/// controlled Hermes activation, and one retry of the original capability operation. The caller
/// chooses a fixed capability kind rather than supplying a manifest trigger string. This type does
/// not interpret Hermes errors; a future upstream adapter must provide a structured capability kind.
public actor DesktopOnDemandCapabilityCoordinator {
    private let verifiedManifest: VerifiedDesktopComponentReleaseManifestV2
    private let installer: any DesktopOnDemandCapabilityInstalling
    private let activationPlanner: any DesktopComponentReleaseActivationPlanning
    private let activator: any DesktopOnDemandRuntimeActivating
    private let workspaceRoot: URL
    private let hermesLaunchAgentURL: URL
    private let runID: @Sendable () -> String
    private var running = false

    public init(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        installer: any DesktopOnDemandCapabilityInstalling,
        activationPlanner: any DesktopComponentReleaseActivationPlanning,
        activator: any DesktopOnDemandRuntimeActivating,
        workspaceRoot: URL,
        hermesLaunchAgentURL: URL,
        runID: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }
    ) throws {
        guard Self.validAbsolutePath(workspaceRoot),
              Self.validAbsolutePath(hermesLaunchAgentURL),
              DesktopComponentReleaseActivationPlanner.validBootstrapManifest(
                  verifiedManifest.manifest
              )
        else { throw DesktopOnDemandCapabilityCoordinatorError.invalidConfiguration }
        self.verifiedManifest = verifiedManifest
        self.installer = installer
        self.activationPlanner = activationPlanner
        self.activator = activator
        self.workspaceRoot = workspaceRoot.standardizedFileURL
        self.hermesLaunchAgentURL = hermesLaunchAgentURL.standardizedFileURL
        self.runID = runID
    }

    public func installActivateAndRetry<T: Sendable>(
        capability: DesktopOnDemandCapability,
        healthProbe: @escaping DesktopOnDemandComponentInstaller.HealthProbe,
        retry: @Sendable () async throws -> T
    ) async throws -> T {
        guard !running else {
            throw DesktopOnDemandCapabilityCoordinatorError.operationInProgress
        }
        running = true
        defer { running = false }

        let manifest = verifiedManifest.manifest
        guard let artifact = manifest.components.first(where: {
            $0.kind == capability.componentKind && $0.installPhase == .onDemand
        }),
              let trigger = artifact.onDemandTrigger,
              !trigger.isEmpty
        else {
            throw DesktopOnDemandCapabilityCoordinatorError.unsupportedCapability(capability)
        }
        let installed = try await installer.install(
            verifiedManifest: verifiedManifest,
            trigger: trigger,
            workspaceRoot: workspaceRoot,
            runID: runID(),
            healthProbe: healthProbe
        )
        let activationPlan = try activationPlanner.plan(
            manifest: manifest,
            healthProbe: healthProbe
        )
        return try await activator.activateAndRetry(
            installed: installed,
            hermesLaunchAgentURL: hermesLaunchAgentURL,
            activationPlan: activationPlan,
            componentHealthProbe: healthProbe,
            retry: retry
        )
    }

    private static func validAbsolutePath(_ value: URL) -> Bool {
        let normalized = value.standardizedFileURL
        return normalized.isFileURL && normalized.path.hasPrefix("/") && normalized.path != "/"
            && normalized.resolvingSymlinksInPath().path == normalized.path
            && !normalized.path.unicodeScalars.contains(
                where: CharacterSet.controlCharacters.contains
            )
    }
}
