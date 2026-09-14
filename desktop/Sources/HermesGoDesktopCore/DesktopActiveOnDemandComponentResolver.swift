import Darwin
import Foundation

public enum DesktopActiveOnDemandComponentResolutionError: Error, Equatable, Sendable {
    case invalidInput
    case invalidManifest
    case invalidReference
    case invalidManagedStore
    case invalidLaunchAgent
    case invalidExternalCandidate
    case componentConflict(DesktopManagedComponentKind)
    case componentHealthProbeFailed(DesktopManagedComponentKind)
}

public protocol DesktopActiveOnDemandComponentResolving: Sendable {
    func resolve(
        installed: DesktopInstalledOnDemandCapability,
        hermesLaunchAgentURL: URL,
        healthProbe: DesktopOnDemandComponentInstaller.HealthProbe
    ) throws -> [DesktopResolvedOnDemandComponent]
}

/// Reconstructs the complete optional runtime for a managed release before Hermes is restarted.
/// Managed capabilities come from immutable per-release references and are revalidated against the
/// signed manifest and content store. A currently configured system browser is retained only when
/// the signed compatibility contract and a fresh external scan still accept that exact executable.
public final class DesktopActiveOnDemandComponentResolver:
    DesktopActiveOnDemandComponentResolving, @unchecked Sendable
{
    public typealias HealthProbe = DesktopOnDemandComponentInstaller.HealthProbe

    private static let capabilityKinds: Set<DesktopManagedComponentKind> = [
        .browserAutomation, .speechRuntime, .documentTools,
    ]

    private let manifest: DesktopComponentReleaseManifestV2
    private let storeRoot: URL
    private let currentUserID: UInt32
    private let externalScanner: any DesktopExternalEnvironmentScanning
    private let inspector: DesktopManagedComponentStoreInspector
    private let garbageCollectionPlanner: DesktopManagedComponentGarbageCollectionPlanner
    private let fileManager: FileManager

    public convenience init(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
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
            verifiedManifest: verifiedManifest,
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
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        storeRoot: URL,
        currentUserID: UInt32,
        externalScanner: any DesktopExternalEnvironmentScanning,
        fileManager: FileManager = .default
    ) throws {
        let normalized = storeRoot.standardizedFileURL.resolvingSymlinksInPath()
        guard normalized.isFileURL, normalized.path.hasPrefix("/"), normalized.path != "/" else {
            throw DesktopActiveOnDemandComponentResolutionError.invalidInput
        }
        manifest = verifiedManifest.manifest
        self.storeRoot = normalized
        self.currentUserID = currentUserID
        self.externalScanner = externalScanner
        inspector = try DesktopManagedComponentStoreInspector(
            root: normalized, currentUserID: currentUserID, fileManager: fileManager
        )
        garbageCollectionPlanner = try DesktopManagedComponentGarbageCollectionPlanner(
            root: normalized, currentUserID: currentUserID, fileManager: fileManager
        )
        self.fileManager = fileManager
    }

    public func resolve(
        installed: DesktopInstalledOnDemandCapability,
        hermesLaunchAgentURL: URL,
        healthProbe: HealthProbe
    ) throws -> [DesktopResolvedOnDemandComponent] {
        guard DesktopComponentReleaseActivationPlanner.validBootstrapManifest(manifest),
              installed.releaseVersion == manifest.releaseVersion,
              try orderedKinds(for: installed.trigger, manifest: manifest)
                == Set(installed.components.map(\.kind)),
              Set(installed.components.map(\.kind)).count == installed.components.count
        else { throw DesktopActiveOnDemandComponentResolutionError.invalidInput }

        do {
            _ = try garbageCollectionPlanner.plan(
                protectedReleaseVersions: [manifest.releaseVersion]
            )
        } catch {
            throw DesktopActiveOnDemandComponentResolutionError.invalidManagedStore
        }

        let artifacts = Dictionary(
            uniqueKeysWithValues: manifest.components.map { ($0.kind, $0) }
        )
        let references = try readCapabilityReferences(releaseVersion: manifest.releaseVersion)
        var resolved: [DesktopManagedComponentKind: DesktopResolvedOnDemandComponent] = [:]

        for (kind, contentSHA256) in references {
            guard let artifact = artifacts[kind],
                  artifact.installPhase == .onDemand,
                  artifact.contentSHA256 == contentSHA256
            else { throw DesktopActiveOnDemandComponentResolutionError.invalidReference }
            resolved[kind] = try resolveManaged(artifact: artifact, healthProbe: healthProbe)
        }

        for component in installed.components {
            guard let artifact = artifacts[component.kind], artifact.installPhase == .onDemand else {
                throw DesktopActiveOnDemandComponentResolutionError.invalidManifest
            }
            let checked: DesktopResolvedOnDemandComponent
            switch component.location {
            case .managed(let root, let entrypoint):
                guard references[component.kind] == artifact.contentSHA256 else {
                    throw DesktopActiveOnDemandComponentResolutionError.invalidReference
                }
                checked = try resolveManaged(artifact: artifact, healthProbe: healthProbe)
                guard checked == DesktopResolvedOnDemandComponent(
                    kind: component.kind,
                    location: .managed(
                        root: root.standardizedFileURL,
                        entrypoint: entrypoint.standardizedFileURL
                    )
                ) else {
                    throw DesktopActiveOnDemandComponentResolutionError.componentConflict(
                        component.kind
                    )
                }
            case .external(let executable):
                checked = try resolveExternalBrowser(
                    executable: executable, artifact: artifact
                )
            }
            if let existing = resolved[component.kind], existing != checked {
                throw DesktopActiveOnDemandComponentResolutionError.componentConflict(component.kind)
            }
            resolved[component.kind] = checked
        }

        if resolved[.browserAutomation] == nil,
           let browser = try currentBrowserExecutable(from: hermesLaunchAgentURL) {
            guard let artifact = artifacts[.browserAutomation],
                  artifact.installPhase == .onDemand
            else { throw DesktopActiveOnDemandComponentResolutionError.invalidManifest }
            resolved[.browserAutomation] = try resolveExternalBrowser(
                executable: browser, artifact: artifact
            )
        }

        return resolved.values.sorted { $0.kind.rawValue < $1.kind.rawValue }
    }

    private func resolveManaged(
        artifact: DesktopComponentReleaseArtifactV2,
        healthProbe: HealthProbe
    ) throws -> DesktopResolvedOnDemandComponent {
        let requirement: DesktopManagedComponentRequirement
        do { requirement = try artifact.preflightRequirement }
        catch { throw DesktopActiveOnDemandComponentResolutionError.invalidManifest }
        let root = managedRoot(for: artifact)
        let entrypoint = root.appendingPathComponent(artifact.entrypoint).standardizedFileURL
        let candidate: DesktopManagedComponentCandidate?
        do {
            candidate = try inspector.candidate(for: requirement) { inspectedRoot in
                guard inspectedRoot.standardizedFileURL.path == root.path,
                      self.validManagedEntrypoint(entrypoint, beneath: root)
                else { return false }
                return try healthProbe(artifact.kind, root, entrypoint)
            }
        } catch {
            throw DesktopActiveOnDemandComponentResolutionError.invalidManagedStore
        }
        guard let candidate else {
            throw DesktopActiveOnDemandComponentResolutionError.invalidManagedStore
        }
        guard candidate.healthProbePassed else {
            throw DesktopActiveOnDemandComponentResolutionError.componentHealthProbeFailed(
                artifact.kind
            )
        }
        return DesktopResolvedOnDemandComponent(
            kind: artifact.kind,
            location: .managed(root: root, entrypoint: entrypoint)
        )
    }

    private func resolveExternalBrowser(
        executable: URL,
        artifact: DesktopComponentReleaseArtifactV2
    ) throws -> DesktopResolvedOnDemandComponent {
        guard artifact.kind == .browserAutomation,
              artifact.installPhase == .onDemand,
              artifact.reuseContract == .verifiedCompatibility,
              let requirement = try? artifact.preflightRequirement
        else { throw DesktopActiveOnDemandComponentResolutionError.invalidExternalCandidate }
        let expected = executable.standardizedFileURL
        let scan = externalScanner.scan(requirements: [requirement])
        let matching = scan.observations.filter { observation in
            guard observation.kind == .browserAutomation,
                  observation.status == .reusable,
                  observation.candidate?.source == .external
            else { return false }
            return observation.executableURL.standardizedFileURL.path == expected.path
        }
        guard let observation = matching.first else {
            throw DesktopActiveOnDemandComponentResolutionError.invalidExternalCandidate
        }
        return DesktopResolvedOnDemandComponent(
            kind: .browserAutomation,
            location: .external(executable: observation.executableURL.standardizedFileURL)
        )
    }

    private func readCapabilityReferences(
        releaseVersion: String
    ) throws -> [DesktopManagedComponentKind: String] {
        let root = storeRoot.appendingPathComponent(
            "capability-references/\(releaseVersion)", isDirectory: true
        )
        guard fileManager.fileExists(atPath: root.path) else { return [:] }
        try requireOwnedObject(root, directory: true)
        let entries: [URL]
        do {
            entries = try fileManager.contentsOfDirectory(
                at: root, includingPropertiesForKeys: nil, options: []
            )
        } catch {
            throw DesktopActiveOnDemandComponentResolutionError.invalidReference
        }
        guard !entries.isEmpty, entries.count <= Self.capabilityKinds.count else {
            throw DesktopActiveOnDemandComponentResolutionError.invalidReference
        }
        var result: [DesktopManagedComponentKind: String] = [:]
        for entry in entries {
            try requireOwnedObject(entry, directory: false)
            let name = entry.lastPathComponent
            guard name.hasSuffix(".json"),
                  let kind = DesktopManagedComponentKind(rawValue: String(name.dropLast(5))),
                  Self.capabilityKinds.contains(kind),
                  result[kind] == nil,
                  let data = try? Data(contentsOf: entry, options: [.mappedIfSafe]),
                  data.count <= 64 * 1_024,
                  let object = try? JSONSerialization.jsonObject(with: data),
                  let dictionary = object as? [String: Any],
                  Set(dictionary.keys) == ["schemaVersion", "releaseVersion", "component"],
                  let rawComponent = dictionary["component"] as? [String: Any],
                  Set(rawComponent.keys) == ["kind", "contentSHA256"],
                  let reference = try? JSONDecoder().decode(
                      DesktopManagedCapabilityReference.self, from: data
                  ),
                  reference.schemaVersion == 1,
                  reference.releaseVersion == releaseVersion,
                  reference.component.kind == kind,
                  reference.component.contentSHA256.range(
                      of: "^[0-9a-f]{64}$", options: .regularExpression
                  ) != nil
            else { throw DesktopActiveOnDemandComponentResolutionError.invalidReference }
            result[kind] = reference.component.contentSHA256
        }
        return result
    }

    private func currentBrowserExecutable(from launchAgentURL: URL) throws -> URL? {
        let normalized = launchAgentURL.standardizedFileURL
        guard normalized.isFileURL, normalized.path.hasPrefix("/"), normalized.path != "/" else {
            throw DesktopActiveOnDemandComponentResolutionError.invalidLaunchAgent
        }
        var metadata = stat()
        guard Darwin.lstat(normalized.path, &metadata) == 0,
              metadata.st_mode & S_IFMT == S_IFREG,
              metadata.st_uid == currentUserID,
              metadata.st_mode & 0o077 == 0,
              metadata.st_size >= 0,
              metadata.st_size <= 64 * 1_024,
              let data = try? Data(contentsOf: normalized, options: [.mappedIfSafe]),
              let object = try? PropertyListSerialization.propertyList(
                  from: data, options: [], format: nil
              ) as? [String: Any],
              let environment = object["EnvironmentVariables"] as? [String: Any],
              environment.allSatisfy({ $0.value is String })
        else { throw DesktopActiveOnDemandComponentResolutionError.invalidLaunchAgent }
        guard let value = environment["AGENT_BROWSER_EXECUTABLE_PATH"] as? String else {
            return nil
        }
        let executable = URL(fileURLWithPath: value).standardizedFileURL
        guard value == executable.path, value.hasPrefix("/"), value != "/",
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw DesktopActiveOnDemandComponentResolutionError.invalidLaunchAgent }
        return executable
    }

    private func orderedKinds(
        for trigger: String,
        manifest: DesktopComponentReleaseManifestV2
    ) throws -> Set<DesktopManagedComponentKind> {
        let byKind = Dictionary(uniqueKeysWithValues: manifest.components.map { ($0.kind, $0) })
        let roots = manifest.components.filter {
            $0.installPhase == .onDemand && $0.onDemandTrigger == trigger
        }
        guard !roots.isEmpty else {
            throw DesktopActiveOnDemandComponentResolutionError.invalidInput
        }
        var visiting = Set<DesktopManagedComponentKind>()
        var visited = Set<DesktopManagedComponentKind>()
        func visit(_ artifact: DesktopComponentReleaseArtifactV2) throws {
            guard artifact.installPhase == .onDemand, !visited.contains(artifact.kind),
                  !visiting.contains(artifact.kind)
            else {
                if visiting.contains(artifact.kind) {
                    throw DesktopActiveOnDemandComponentResolutionError.invalidManifest
                }
                return
            }
            visiting.insert(artifact.kind)
            for dependency in artifact.dependencies {
                guard let child = byKind[dependency.kind],
                      child.contentSHA256 == dependency.contentSHA256
                else { throw DesktopActiveOnDemandComponentResolutionError.invalidManifest }
                try visit(child)
            }
            visiting.remove(artifact.kind)
            visited.insert(artifact.kind)
        }
        for root in roots { try visit(root) }
        return visited
    }

    private func managedRoot(for artifact: DesktopComponentReleaseArtifactV2) -> URL {
        storeRoot.appendingPathComponent(
            "components/\(artifact.kind.rawValue)/\(artifact.contentSHA256)/content",
            isDirectory: true
        )
    }

    private func validManagedEntrypoint(_ value: URL, beneath root: URL) -> Bool {
        guard value.path.hasPrefix(root.path + "/") else { return false }
        var metadata = stat()
        guard Darwin.lstat(value.path, &metadata) == 0,
              metadata.st_uid == currentUserID,
              metadata.st_mode & S_IFMT == S_IFREG,
              metadata.st_mode & 0o111 != 0,
              metadata.st_mode & 0o022 == 0,
              let resource = try? value.resourceValues(forKeys: [
                  .isRegularFileKey, .isSymbolicLinkKey,
              ])
        else { return false }
        return resource.isRegularFile == true && resource.isSymbolicLink != true
    }

    private func requireOwnedObject(_ value: URL, directory: Bool) throws {
        var metadata = stat()
        guard Darwin.lstat(value.path, &metadata) == 0,
              metadata.st_uid == currentUserID,
              metadata.st_mode & 0o077 == 0,
              directory
                ? metadata.st_mode & S_IFMT == S_IFDIR
                : metadata.st_mode & S_IFMT == S_IFREG
        else { throw DesktopActiveOnDemandComponentResolutionError.invalidReference }
    }
}
