import Darwin
import Foundation

public enum DesktopComponentReleaseActivationError: Error, Equatable, Sendable {
    case invalidManifest
    case missingComponent(DesktopManagedComponentKind)
    case invalidEntrypoint(DesktopManagedComponentKind)
    case healthProbeFailed(DesktopManagedComponentKind)
}

public struct DesktopResolvedManagedComponent: Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let root: URL
    public let entrypoint: URL
    public let contentSHA256: String

    fileprivate init(
        kind: DesktopManagedComponentKind,
        root: URL,
        entrypoint: URL,
        contentSHA256: String
    ) {
        self.kind = kind
        self.root = root
        self.entrypoint = entrypoint
        self.contentSHA256 = contentSHA256
    }
}

/// A read-only, content-addressed launch plan. Creating it revalidates every bootstrap component
/// through the managed-store inspector and never consults PATH, Homebrew, or a mutable user venv.
///
/// That still holds for everything resolved here. One bounded exception lives elsewhere: since
/// HG-58 the agent this plan is written into carries a `PATH`
/// (`DesktopHermesRuntimeContract.searchPath`) so that *optional external* binaries the user
/// installed — a PDF rasteriser, say — are reachable at all. launchd's bare
/// `/usr/bin:/bin:/usr/sbin:/sbin` made an installed poppler invisible and Hermes reported it as
/// "not installed". No managed component is resolved that way, and none may be: the cost of the
/// exception is that a capability riding on it depends on what the user's Homebrew contains, which
/// is exactly the dependency this type refuses to take for the components it owns.
public struct DesktopComponentReleaseActivationPlan: Equatable, Sendable {
    public let releaseVersion: String
    public let components: [DesktopResolvedManagedComponent]

    fileprivate init(
        releaseVersion: String,
        components: [DesktopResolvedManagedComponent]
    ) {
        self.releaseVersion = releaseVersion
        self.components = components
    }

    public func component(
        _ kind: DesktopManagedComponentKind
    ) -> DesktopResolvedManagedComponent? {
        components.first(where: { $0.kind == kind })
    }
}

public struct DesktopComponentReleaseActivationPlanner: @unchecked Sendable {
    public typealias HealthProbe = @Sendable (
        _ kind: DesktopManagedComponentKind,
        _ componentRoot: URL,
        _ entrypoint: URL
    ) throws -> Bool

    private let inspector: DesktopManagedComponentStoreInspector
    private let storeRoot: URL
    private let currentUserID: UInt32

    public init(
        storeRoot: URL,
        currentUserID: UInt32,
        fileManager: FileManager = .default
    ) throws {
        let normalizedRoot = storeRoot.standardizedFileURL.resolvingSymlinksInPath()
        inspector = try DesktopManagedComponentStoreInspector(
            root: normalizedRoot,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        self.storeRoot = normalizedRoot
        self.currentUserID = currentUserID
    }

    public func plan(
        manifest: DesktopComponentReleaseManifestV2,
        healthProbe: HealthProbe
    ) throws -> DesktopComponentReleaseActivationPlan {
        guard Self.validBootstrapManifest(manifest) else {
            throw DesktopComponentReleaseActivationError.invalidManifest
        }

        var resolved: [DesktopResolvedManagedComponent] = []
        for artifact in manifest.components
            .filter({ $0.installPhase == .bootstrap })
            .sorted(by: { $0.kind.rawValue < $1.kind.rawValue }) {
            let requirement: DesktopManagedComponentRequirement
            do { requirement = try artifact.preflightRequirement }
            catch { throw DesktopComponentReleaseActivationError.invalidManifest }

            let root = storeRoot
                .appendingPathComponent("components", isDirectory: true)
                .appendingPathComponent(artifact.kind.rawValue, isDirectory: true)
                .appendingPathComponent(artifact.contentSHA256, isDirectory: true)
                .appendingPathComponent("content", isDirectory: true)
            let entrypoint = root.appendingPathComponent(artifact.entrypoint).standardizedFileURL
            let candidate = try inspector.candidate(for: requirement) { inspectedRoot in
                guard inspectedRoot.standardizedFileURL.path == root.standardizedFileURL.path else {
                    throw DesktopComponentReleaseActivationError.invalidEntrypoint(artifact.kind)
                }
                guard try validEntrypoint(entrypoint, beneath: root) else {
                    throw DesktopComponentReleaseActivationError.invalidEntrypoint(artifact.kind)
                }
                return try healthProbe(artifact.kind, root, entrypoint)
            }
            guard let candidate else {
                throw DesktopComponentReleaseActivationError.missingComponent(artifact.kind)
            }
            guard candidate.healthProbePassed else {
                throw DesktopComponentReleaseActivationError.healthProbeFailed(artifact.kind)
            }
            resolved.append(DesktopResolvedManagedComponent(
                kind: artifact.kind,
                root: root,
                entrypoint: entrypoint,
                contentSHA256: artifact.contentSHA256
            ))
        }
        return DesktopComponentReleaseActivationPlan(
            releaseVersion: manifest.releaseVersion,
            components: resolved
        )
    }

    static func validBootstrapManifest(_ manifest: DesktopComponentReleaseManifestV2) -> Bool {
        guard manifest.schemaVersion == 2,
              DesktopManagedInstallLayout.validVersion(manifest.releaseVersion),
              Self.validArchitecture(manifest.architecture),
              Set(manifest.components.map(\.kind)).count == manifest.components.count
        else { return false }
        let byKind = Dictionary(uniqueKeysWithValues: manifest.components.map { ($0.kind, $0) })
        return validBootstrapTopology(byKind, manifestArchitecture: manifest.architecture)
    }

    private static func validBootstrapTopology(
        _ artifacts: [DesktopManagedComponentKind: DesktopComponentReleaseArtifactV2],
        manifestArchitecture: String
    ) -> Bool {
        let baseKinds: Set<DesktopManagedComponentKind> = [
            .pythonRuntime, .hermesCore, .nodeRuntime, .connector,
        ]
        guard let python = artifacts[.pythonRuntime],
              let hermes = artifacts[.hermesCore],
              let node = artifacts[.nodeRuntime],
              let connector = artifacts[.connector],
              Set(artifacts.values.filter({ $0.installPhase == .bootstrap }).map(\.kind)) == baseKinds,
              [python, hermes, node, connector].allSatisfy({
                Self.validBootstrapArtifact($0, manifestArchitecture: manifestArchitecture)
              })
        else { return false }

        func dependencies(
            _ kind: DesktopManagedComponentKind
        ) -> Set<DesktopComponentReleaseDependency> {
            Set(artifacts[kind]?.dependencies ?? [])
        }
        guard dependencies(.pythonRuntime).isEmpty,
              dependencies(.nodeRuntime).isEmpty,
              dependencies(.hermesCore) == Set([
                DesktopComponentReleaseDependency(
                    kind: .pythonRuntime,
                    contentSHA256: python.contentSHA256
                ),
              ]),
              dependencies(.connector) == Set([
                DesktopComponentReleaseDependency(
                    kind: .hermesCore,
                    contentSHA256: hermes.contentSHA256
                ),
                DesktopComponentReleaseDependency(
                    kind: .nodeRuntime,
                    contentSHA256: node.contentSHA256
                ),
              ])
        else { return false }
        return true
    }

    private static func validBootstrapArtifact(
        _ artifact: DesktopComponentReleaseArtifactV2,
        manifestArchitecture: String
    ) -> Bool {
        artifact.installPhase == .bootstrap
            && artifact.requiredForBootstrap
            && artifact.onDemandTrigger == nil
            && artifact.reuseContract == .exactContent
            && artifact.compatibilityIdentifier == nil
            && DesktopManagedInstallLayout.validVersion(artifact.version)
            && Self.validArchitecture(artifact.architecture)
            && (artifact.architecture == manifestArchitecture || artifact.architecture == "universal")
            && Self.validSHA256(artifact.contentSHA256)
            && Self.validRelativePath(artifact.entrypoint)
            && artifact.dependencies.allSatisfy { Self.validSHA256($0.contentSHA256) }
    }

    private static func validArchitecture(_ value: String) -> Bool {
        ["arm64", "x86_64", "universal"].contains(value)
    }

    private static func validSHA256(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }

    private static func validRelativePath(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 256, !value.hasPrefix("/"), !value.hasSuffix("/"),
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { return false }
        return value.split(separator: "/", omittingEmptySubsequences: false)
            .allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    private func validEntrypoint(_ value: URL, beneath root: URL) throws -> Bool {
        let normalizedRoot = root.standardizedFileURL
        let prefix = normalizedRoot.path.hasSuffix("/")
            ? normalizedRoot.path
            : normalizedRoot.path + "/"
        guard value.path.hasPrefix(prefix),
              !value.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { return false }
        var metadata = stat()
        guard Darwin.lstat(value.path, &metadata) == 0,
              metadata.st_uid == currentUserID,
              metadata.st_mode & S_IFMT == S_IFREG,
              metadata.st_mode & 0o111 != 0,
              metadata.st_mode & 0o022 == 0
        else { return false }
        let resource = try value.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        return resource.isRegularFile == true && resource.isSymbolicLink != true
    }
}
