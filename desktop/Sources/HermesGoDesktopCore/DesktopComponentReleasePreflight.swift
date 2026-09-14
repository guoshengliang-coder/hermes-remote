import Foundation

public protocol DesktopExternalEnvironmentScanning {
    func scan(
        requirements: [DesktopManagedComponentRequirement]
    ) -> DesktopExternalEnvironmentScan
}

extension DesktopExternalEnvironmentScanner: DesktopExternalEnvironmentScanning {}

public enum DesktopComponentReleasePreflightError: Error, Equatable, Sendable {
    case invalidManifest
    case componentHealthProbeFailed(DesktopManagedComponentKind)
}

public struct DesktopComponentReleasePreflightResult: Equatable, Sendable {
    public let manifest: DesktopComponentReleaseManifestV2
    public let plan: DesktopManagedComponentPreflightPlan
    public let externalEnvironment: DesktopExternalEnvironmentScan

    public init(
        manifest: DesktopComponentReleaseManifestV2,
        plan: DesktopManagedComponentPreflightPlan,
        externalEnvironment: DesktopExternalEnvironmentScan
    ) {
        self.manifest = manifest
        self.plan = plan
        self.externalEnvironment = externalEnvironment
    }
}

/// Builds the complete read-only component decision shown before installation. Managed content is
/// rehashed and health checked, while external paths are observations unless a signed compatibility
/// contract and a Desktop-owned probe both allow reuse. No directory, credential, or service state
/// is changed by this coordinator.
public final class DesktopComponentReleasePreflightCoordinator: @unchecked Sendable {
    public typealias HealthProbe = DesktopComponentReleaseInstaller.HealthProbe

    private let inspector: DesktopManagedComponentStoreInspector
    private let externalScanner: any DesktopExternalEnvironmentScanning

    public convenience init(
        storeRoot: URL,
        currentUserID: UInt32,
        externalPaths: [DesktopExternalEnvironmentPath] = DesktopExternalEnvironmentScanner<
            SystemOutputCommandRunner
        >.defaultPaths,
        browserCompatibilityProbe: @escaping DesktopExternalEnvironmentScanner<
            SystemOutputCommandRunner
        >.BrowserCompatibilityProbe = { _, _, _ in false },
        fileManager: FileManager = .default
    ) throws {
        try self.init(
            storeRoot: storeRoot,
            currentUserID: currentUserID,
            externalScanner: DesktopExternalEnvironmentScanner(
                paths: externalPaths,
                runner: SystemOutputCommandRunner(),
                currentUserID: currentUserID,
                browserCompatibilityProbe: browserCompatibilityProbe,
                fileManager: fileManager
            ),
            fileManager: fileManager
        )
    }

    public init(
        storeRoot: URL,
        currentUserID: UInt32,
        externalScanner: any DesktopExternalEnvironmentScanning,
        fileManager: FileManager = .default
    ) throws {
        inspector = try DesktopManagedComponentStoreInspector(
            root: storeRoot,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        self.externalScanner = externalScanner
    }

    public func scan(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        healthProbe: HealthProbe
    ) throws -> DesktopComponentReleasePreflightResult {
        let manifest = verifiedManifest.manifest
        guard DesktopComponentReleaseActivationPlanner.validBootstrapManifest(manifest) else {
            throw DesktopComponentReleasePreflightError.invalidManifest
        }
        let requirements: [DesktopManagedComponentRequirement]
        do { requirements = try manifest.preflightRequirements }
        catch { throw DesktopComponentReleasePreflightError.invalidManifest }
        let artifacts = Dictionary(uniqueKeysWithValues: manifest.components.map { ($0.kind, $0) })
        var managedCandidates: [DesktopManagedComponentCandidate] = []
        for requirement in requirements {
            guard let artifact = artifacts[requirement.kind] else {
                throw DesktopComponentReleasePreflightError.invalidManifest
            }
            if let candidate = try inspector.candidate(
                for: requirement,
                healthProbe: { root in
                    try healthProbe(
                        artifact.kind,
                        root,
                        root.appendingPathComponent(artifact.entrypoint).standardizedFileURL
                    )
                }
            ) {
                guard candidate.healthProbePassed else {
                    throw DesktopComponentReleasePreflightError.componentHealthProbeFailed(
                        artifact.kind
                    )
                }
                managedCandidates.append(candidate)
            }
        }

        let externalEnvironment = externalScanner.scan(requirements: requirements)
        let plan: DesktopManagedComponentPreflightPlan
        do {
            plan = try DesktopManagedComponentPreflightPlanner.plan(
                requirements: requirements,
                candidates: managedCandidates + externalEnvironment.reusableCandidates
            )
        } catch {
            throw DesktopComponentReleasePreflightError.invalidManifest
        }
        return DesktopComponentReleasePreflightResult(
            manifest: manifest,
            plan: plan,
            externalEnvironment: externalEnvironment
        )
    }
}
