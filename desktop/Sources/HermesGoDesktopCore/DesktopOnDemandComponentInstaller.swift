import CryptoKit
import Foundation

public enum DesktopOnDemandComponentLocation: Equatable, Sendable {
    case managed(root: URL, entrypoint: URL)
    case external(executable: URL)
}

public struct DesktopResolvedOnDemandComponent: Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let location: DesktopOnDemandComponentLocation

    public init(
        kind: DesktopManagedComponentKind,
        location: DesktopOnDemandComponentLocation
    ) {
        self.kind = kind
        self.location = location
    }
}

public struct DesktopInstalledOnDemandCapability: Equatable, Sendable {
    public let releaseVersion: String
    public let trigger: String
    public let components: [DesktopResolvedOnDemandComponent]
    public let referenceURLs: [URL]

    public init(
        releaseVersion: String,
        trigger: String,
        components: [DesktopResolvedOnDemandComponent],
        referenceURLs: [URL]
    ) {
        self.releaseVersion = releaseVersion
        self.trigger = trigger
        self.components = components
        self.referenceURLs = referenceURLs
    }
}

public enum DesktopOnDemandComponentInstallError: Error, Equatable, Sendable {
    case invalidWorkspace
    case workspaceAlreadyExists
    case invalidManifest
    case unknownTrigger
    case missingBaseReleaseReference
    case invalidManagedStore
    case componentHealthProbeFailed(DesktopManagedComponentKind)
    case invalidExternalCandidate
    case cleanupFailed
}

/// Resolves one signed first-use capability against an installed release. It validates the complete
/// bootstrap release before any external scan, workspace mutation, or network access. It never
/// changes credentials, the active release pointer, LaunchAgents, or processes.
public final class DesktopOnDemandComponentInstaller: @unchecked Sendable {
    public typealias HealthProbe = DesktopComponentReleaseInstaller.HealthProbe

    private let storeRoot: URL
    private let currentUserID: UInt32
    private let downloader: any DesktopComponentReleaseDownloading
    private let extractor: any DesktopComponentArchiveExtracting
    private let externalScanner: any DesktopExternalEnvironmentScanning
    private let inspector: DesktopManagedComponentStoreInspector
    private let writer: DesktopManagedComponentStoreWriter
    private let activationPlanner: DesktopComponentReleaseActivationPlanner
    private let garbageCollectionPlanner: DesktopManagedComponentGarbageCollectionPlanner
    private let fileManager: FileManager

    public convenience init(
        storeRoot: URL,
        currentUserID: UInt32,
        externalPaths: [DesktopExternalEnvironmentPath] = DesktopExternalEnvironmentScanner<
            SystemOutputCommandRunner
        >.defaultPaths,
        browserCompatibilityProbe: @escaping DesktopExternalEnvironmentScanner<
            SystemOutputCommandRunner
        >.BrowserCompatibilityProbe = { _, _, _ in false },
        downloader: any DesktopComponentReleaseDownloading = DesktopComponentReleaseDownloader(),
        extractor: any DesktopComponentArchiveExtracting = DesktopTarArchiveExtractor(
            runner: SystemOutputCommandRunner()
        ),
        fileManager: FileManager = .default
    ) throws {
        try self.init(
            storeRoot: storeRoot,
            currentUserID: currentUserID,
            downloader: downloader,
            extractor: extractor,
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
        downloader: any DesktopComponentReleaseDownloading,
        extractor: any DesktopComponentArchiveExtracting,
        externalScanner: any DesktopExternalEnvironmentScanning,
        fileManager: FileManager = .default
    ) throws {
        let normalizedStore = storeRoot.standardizedFileURL.resolvingSymlinksInPath()
        self.storeRoot = normalizedStore
        self.currentUserID = currentUserID
        self.downloader = downloader
        self.extractor = extractor
        self.externalScanner = externalScanner
        inspector = try DesktopManagedComponentStoreInspector(
            root: normalizedStore,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        writer = try DesktopManagedComponentStoreWriter(
            root: normalizedStore,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        activationPlanner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: normalizedStore,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        garbageCollectionPlanner = try DesktopManagedComponentGarbageCollectionPlanner(
            root: normalizedStore,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        self.fileManager = fileManager
    }

    public func install(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        trigger: String,
        workspaceRoot: URL,
        runID: String,
        healthProbe: HealthProbe
    ) async throws -> DesktopInstalledOnDemandCapability {
        let manifest = verifiedManifest.manifest
        guard DesktopComponentReleaseActivationPlanner.validBootstrapManifest(manifest) else {
            throw DesktopOnDemandComponentInstallError.invalidManifest
        }
        let artifacts = try orderedArtifacts(for: trigger, manifest: manifest)
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopOnDemandComponentInstallError.invalidWorkspace
        }

        do {
            _ = try garbageCollectionPlanner.plan(
                protectedReleaseVersions: [manifest.releaseVersion]
            )
        } catch DesktopManagedComponentGarbageCollectionError.missingProtectedReference {
            throw DesktopOnDemandComponentInstallError.missingBaseReleaseReference
        } catch {
            throw DesktopOnDemandComponentInstallError.invalidManagedStore
        }

        do {
            _ = try activationPlanner.plan(manifest: manifest, healthProbe: healthProbe)
        } catch let error as DesktopComponentReleaseActivationError {
            switch error {
            case .healthProbeFailed(let kind):
                throw DesktopOnDemandComponentInstallError.componentHealthProbeFailed(kind)
            default:
                throw DesktopOnDemandComponentInstallError.invalidManagedStore
            }
        } catch {
            throw DesktopOnDemandComponentInstallError.invalidManagedStore
        }

        let requirements: [DesktopManagedComponentRequirement]
        do {
            requirements = try artifacts.map { try $0.preflightRequirement }
        } catch {
            throw DesktopOnDemandComponentInstallError.invalidManifest
        }
        var managedCandidates: [DesktopManagedComponentCandidate] = []
        for (artifact, requirement) in zip(artifacts, requirements) {
            if let candidate = try inspector.candidate(
                for: requirement,
                healthProbe: { root in
                    let entrypoint = root
                        .appendingPathComponent(artifact.entrypoint)
                        .standardizedFileURL
                    guard self.validManagedEntrypoint(entrypoint, beneath: root) else {
                        return false
                    }
                    return try healthProbe(artifact.kind, root, entrypoint)
                }
            ) {
                guard candidate.healthProbePassed else {
                    throw DesktopOnDemandComponentInstallError.componentHealthProbeFailed(
                        artifact.kind
                    )
                }
                managedCandidates.append(candidate)
            }
        }

        let externalEnvironment = externalScanner.scan(requirements: requirements)
        let preflight: DesktopManagedComponentPreflightPlan
        do {
            preflight = try DesktopManagedComponentPreflightPlanner.plan(
                requirements: requirements,
                candidates: managedCandidates + externalEnvironment.reusableCandidates
            )
        } catch {
            throw DesktopOnDemandComponentInstallError.invalidManifest
        }
        let decisions = Dictionary(
            uniqueKeysWithValues: preflight.decisions.map {
                ($0.requirement.kind, $0.action)
            }
        )
        let needsDownload = artifacts.contains {
            decisions[$0.kind] == .deferUntilNeeded
        }

        var workspaceBase: URL?
        var runRoot: URL?
        if needsDownload {
            let base = try preparePrivateWorkspaceRoot(workspaceRoot)
            let run = base.appendingPathComponent(normalizedRunID, isDirectory: true)
            try prepareRunRoot(
                run,
                runID: normalizedRunID,
                operationSHA256: try operationIdentity(manifest: manifest, trigger: trigger)
            )
            workspaceBase = base
            runRoot = run
        }

        do {
            let downloadRoot = runRoot?.appendingPathComponent(
                "downloads", isDirectory: true
            )
            let extractionRoot = runRoot?.appendingPathComponent(
                "extracted", isDirectory: true
            )
            if let downloadRoot, let extractionRoot {
                try prepareRunDirectory(downloadRoot)
                try prepareRunDirectory(extractionRoot)
            }

            var resolved: [DesktopResolvedOnDemandComponent] = []
            var managedReceipts: [DesktopManagedComponentReceipt] = []
            for artifact in artifacts {
                guard let action = decisions[artifact.kind] else {
                    throw DesktopOnDemandComponentInstallError.invalidManifest
                }
                switch action {
                case .reuse(let candidate) where candidate.source == .managedStore:
                    let root = managedRoot(for: artifact)
                    let entrypoint = root
                        .appendingPathComponent(artifact.entrypoint)
                        .standardizedFileURL
                    resolved.append(DesktopResolvedOnDemandComponent(
                        kind: artifact.kind,
                        location: .managed(root: root, entrypoint: entrypoint)
                    ))
                    managedReceipts.append(receipt(for: artifact))

                case .reuse(let candidate) where candidate.source == .external:
                    let observations = externalEnvironment.observations.filter {
                        $0.status == .reusable && $0.candidate == candidate
                    }.sorted {
                        $0.executableURL.path < $1.executableURL.path
                    }
                    guard let observation = observations.first,
                          validExternalExecutable(observation.executableURL)
                    else {
                        throw DesktopOnDemandComponentInstallError.invalidExternalCandidate
                    }
                    resolved.append(DesktopResolvedOnDemandComponent(
                        kind: artifact.kind,
                        location: .external(
                            executable: observation.executableURL.standardizedFileURL
                        )
                    ))

                case .deferUntilNeeded:
                    guard let downloadRoot, let extractionRoot else {
                        throw DesktopOnDemandComponentInstallError.invalidWorkspace
                    }
                    let archive = try await downloader.download(artifact, into: downloadRoot)
                    let extracted = try extractor.extractComponent(
                        archive: archive,
                        metadata: artifact,
                        into: extractionRoot,
                        runID: normalizedRunID
                    )
                    let receipt = receipt(for: artifact)
                    let root = try writer.commit(
                        sourceDirectory: extracted,
                        receipt: receipt,
                        runID: normalizedRunID,
                        healthProbe: { root in
                            let entrypoint = root
                                .appendingPathComponent(artifact.entrypoint)
                                .standardizedFileURL
                            guard self.validManagedEntrypoint(entrypoint, beneath: root) else {
                                return false
                            }
                            return try healthProbe(artifact.kind, root, entrypoint)
                        }
                    )
                    let entrypoint = root
                        .appendingPathComponent(artifact.entrypoint)
                        .standardizedFileURL
                    resolved.append(DesktopResolvedOnDemandComponent(
                        kind: artifact.kind,
                        location: .managed(root: root, entrypoint: entrypoint)
                    ))
                    managedReceipts.append(receipt)

                default:
                    throw DesktopOnDemandComponentInstallError.invalidManifest
                }
            }

            var referenceURLs: [URL] = []
            for receipt in managedReceipts {
                referenceURLs.append(try writer.recordCapabilityReference(
                    releaseVersion: manifest.releaseVersion,
                    receipt: receipt,
                    runID: normalizedRunID
                ))
            }
            let result = DesktopInstalledOnDemandCapability(
                releaseVersion: manifest.releaseVersion,
                trigger: trigger,
                components: resolved,
                referenceURLs: referenceURLs
            )
            if let runRoot, let workspaceBase,
               !cleanupRunRoot(runRoot, beneath: workspaceBase) {
                throw DesktopOnDemandComponentInstallError.cleanupFailed
            }
            return result
        } catch let error as DesktopComponentDownloadError where error == .transportFailed {
            throw error
        } catch {
            if let runRoot, let workspaceBase,
               !cleanupRunRoot(runRoot, beneath: workspaceBase) {
                throw DesktopOnDemandComponentInstallError.cleanupFailed
            }
            throw error
        }
    }

    public func discardInterruptedInstall(workspaceRoot: URL, runID: String) throws {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopOnDemandComponentInstallError.invalidWorkspace
        }
        let base = workspaceRoot.standardizedFileURL
        guard base.resolvingSymlinksInPath() == base, validOwnedDirectory(base) else {
            throw DesktopOnDemandComponentInstallError.invalidWorkspace
        }
        let runRoot = base.appendingPathComponent(normalizedRunID, isDirectory: true)
        guard filesystemObjectExists(runRoot) else { return }
        guard validPrivateDirectory(runRoot),
              validInterruptedMarker(
                runRoot.appendingPathComponent("capability-install.json"),
                runID: normalizedRunID
              )
        else { throw DesktopOnDemandComponentInstallError.invalidWorkspace }
        guard cleanupRunRoot(runRoot, beneath: base) else {
            throw DesktopOnDemandComponentInstallError.cleanupFailed
        }
    }

    private func orderedArtifacts(
        for trigger: String,
        manifest: DesktopComponentReleaseManifestV2
    ) throws -> [DesktopComponentReleaseArtifactV2] {
        let byKind = Dictionary(
            uniqueKeysWithValues: manifest.components.map { ($0.kind, $0) }
        )
        let roots = manifest.components.filter {
            $0.installPhase == .onDemand && $0.onDemandTrigger == trigger
        }
        guard !roots.isEmpty else {
            throw DesktopOnDemandComponentInstallError.unknownTrigger
        }

        var visiting = Set<DesktopManagedComponentKind>()
        var visited = Set<DesktopManagedComponentKind>()
        var ordered: [DesktopComponentReleaseArtifactV2] = []
        func visit(_ artifact: DesktopComponentReleaseArtifactV2) throws {
            guard artifact.installPhase == .onDemand else { return }
            guard !visiting.contains(artifact.kind) else {
                throw DesktopOnDemandComponentInstallError.invalidManifest
            }
            guard !visited.contains(artifact.kind) else { return }
            visiting.insert(artifact.kind)
            for dependency in artifact.dependencies.sorted(by: {
                $0.kind.rawValue < $1.kind.rawValue
            }) {
                guard let dependencyArtifact = byKind[dependency.kind],
                      dependencyArtifact.contentSHA256 == dependency.contentSHA256
                else {
                    throw DesktopOnDemandComponentInstallError.invalidManifest
                }
                try visit(dependencyArtifact)
            }
            visiting.remove(artifact.kind)
            visited.insert(artifact.kind)
            ordered.append(artifact)
        }
        for root in roots.sorted(by: { $0.kind.rawValue < $1.kind.rawValue }) {
            try visit(root)
        }
        return ordered
    }

    private func receipt(
        for artifact: DesktopComponentReleaseArtifactV2
    ) -> DesktopManagedComponentReceipt {
        DesktopManagedComponentReceipt(
            kind: artifact.kind,
            version: artifact.version,
            architecture: artifact.architecture,
            contentSHA256: artifact.contentSHA256
        )
    }

    private func managedRoot(for artifact: DesktopComponentReleaseArtifactV2) -> URL {
        storeRoot
            .appendingPathComponent("components", isDirectory: true)
            .appendingPathComponent(artifact.kind.rawValue, isDirectory: true)
            .appendingPathComponent(artifact.contentSHA256, isDirectory: true)
            .appendingPathComponent("content", isDirectory: true)
    }

    private func validManagedEntrypoint(_ value: URL, beneath root: URL) -> Bool {
        let prefix = root.standardizedFileURL.path + "/"
        guard value.path.hasPrefix(prefix) else { return false }
        return validExecutable(value, allowedOwners: [currentUserID])
    }

    private func validExternalExecutable(_ value: URL) -> Bool {
        let standardized = value.standardizedFileURL
        guard standardized.isFileURL,
              standardized.path.hasPrefix("/"),
              standardized.path != "/",
              standardized.resolvingSymlinksInPath() == standardized
        else { return false }
        return validExecutable(standardized, allowedOwners: [0, currentUserID])
    }

    private func validExecutable(_ value: URL, allowedOwners: Set<UInt32>) -> Bool {
        guard let resource = try? value.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey, .isExecutableKey,
        ]),
              resource.isRegularFile == true,
              resource.isSymbolicLink != true,
              resource.isExecutable == true,
              let attributes = try? fileManager.attributesOfItem(atPath: value.path),
              let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value,
              allowedOwners.contains(owner),
              let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue,
              permissions & 0o022 == 0
        else { return false }
        return true
    }

    private func preparePrivateWorkspaceRoot(_ value: URL) throws -> URL {
        let standardized = value.standardizedFileURL
        guard standardized.isFileURL,
              standardized.path.hasPrefix("/"),
              standardized.path != "/"
        else { throw DesktopOnDemandComponentInstallError.invalidWorkspace }
        if !filesystemObjectExists(standardized) {
            guard validOwnedDirectory(standardized.deletingLastPathComponent()) else {
                throw DesktopOnDemandComponentInstallError.invalidWorkspace
            }
            do {
                try fileManager.createDirectory(
                    at: standardized,
                    withIntermediateDirectories: false,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch {
                throw DesktopOnDemandComponentInstallError.invalidWorkspace
            }
        }
        guard standardized.resolvingSymlinksInPath() == standardized,
              validOwnedDirectory(standardized)
        else { throw DesktopOnDemandComponentInstallError.invalidWorkspace }
        do {
            try fileManager.setAttributes(
                [.posixPermissions: 0o700], ofItemAtPath: standardized.path
            )
        } catch {
            throw DesktopOnDemandComponentInstallError.invalidWorkspace
        }
        return standardized
    }

    private func prepareRunRoot(
        _ value: URL,
        runID: String,
        operationSHA256: String
    ) throws {
        let marker = value.appendingPathComponent("capability-install.json")
        let expected = OnDemandInstallWorkspaceMarker(
            runID: runID,
            operationSHA256: operationSHA256
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let expectedData = try? encoder.encode(expected) else {
            throw DesktopOnDemandComponentInstallError.invalidManifest
        }
        if filesystemObjectExists(value) {
            guard validPrivateDirectory(value),
                  validPrivateFile(marker),
                  let existing = try? Data(contentsOf: marker),
                  existing == expectedData
            else {
                throw DesktopOnDemandComponentInstallError.workspaceAlreadyExists
            }
            return
        }
        do {
            try fileManager.createDirectory(
                at: value,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
            try expectedData.write(to: marker, options: [.withoutOverwriting])
            try fileManager.setAttributes(
                [.posixPermissions: 0o600], ofItemAtPath: marker.path
            )
        } catch {
            try? fileManager.removeItem(at: value)
            throw DesktopOnDemandComponentInstallError.invalidWorkspace
        }
    }

    private func prepareRunDirectory(_ value: URL) throws {
        if filesystemObjectExists(value) {
            guard validPrivateDirectory(value) else {
                throw DesktopOnDemandComponentInstallError.workspaceAlreadyExists
            }
            return
        }
        do {
            try fileManager.createDirectory(
                at: value,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
        } catch {
            throw DesktopOnDemandComponentInstallError.invalidWorkspace
        }
    }

    private func operationIdentity(
        manifest: DesktopComponentReleaseManifestV2,
        trigger: String
    ) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let manifestData = try? encoder.encode(manifest) else {
            throw DesktopOnDemandComponentInstallError.invalidManifest
        }
        var digest = SHA256()
        digest.update(data: manifestData)
        digest.update(data: Data([0]))
        digest.update(data: Data(trigger.utf8))
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func cleanupRunRoot(_ value: URL, beneath base: URL) -> Bool {
        let standardized = value.standardizedFileURL
        guard standardized.deletingLastPathComponent() == base,
              UUID(uuidString: standardized.lastPathComponent) != nil,
              standardized.path != "/"
        else { return false }
        guard filesystemObjectExists(standardized) else { return true }
        guard let values = try? standardized.resourceValues(forKeys: [
            .isDirectoryKey, .isSymbolicLinkKey,
        ]), values.isDirectory == true, values.isSymbolicLink != true else {
            return false
        }
        do {
            try fileManager.removeItem(at: standardized)
            return true
        } catch {
            return false
        }
    }

    private func validOwnedDirectory(_ value: URL) -> Bool {
        let standardized = value.standardizedFileURL
        guard standardized.resolvingSymlinksInPath() == standardized,
              let values = try? standardized.resourceValues(forKeys: [
                .isDirectoryKey, .isSymbolicLinkKey,
              ]),
              values.isDirectory == true,
              values.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: standardized.path),
              let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value,
              owner == currentUserID
        else { return false }
        return true
    }

    private func validPrivateDirectory(_ value: URL) -> Bool {
        guard validOwnedDirectory(value),
              let attributes = try? fileManager.attributesOfItem(atPath: value.path),
              let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue
        else { return false }
        return permissions & 0o077 == 0
    }

    private func validPrivateFile(_ value: URL) -> Bool {
        let standardized = value.standardizedFileURL
        guard standardized.resolvingSymlinksInPath() == standardized,
              let resource = try? standardized.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey,
              ]),
              resource.isRegularFile == true,
              resource.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: standardized.path),
              let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value,
              owner == currentUserID,
              let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue,
              permissions & 0o077 == 0,
              let size = (attributes[.size] as? NSNumber)?.intValue,
              (1...1_024).contains(size)
        else { return false }
        return true
    }

    private func validInterruptedMarker(_ value: URL, runID: String) -> Bool {
        guard validPrivateFile(value),
              let data = try? Data(contentsOf: value),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == ["schemaVersion", "runID", "operationSHA256"],
              let marker = try? JSONDecoder().decode(
                OnDemandInstallWorkspaceMarker.self, from: data
              ),
              marker.schemaVersion == 1,
              marker.runID == runID,
              marker.operationSHA256.range(
                of: "^[0-9a-f]{64}$", options: .regularExpression
              ) != nil
        else { return false }
        return true
    }

    private func filesystemObjectExists(_ value: URL) -> Bool {
        fileManager.fileExists(atPath: value.path)
            || (try? fileManager.destinationOfSymbolicLink(atPath: value.path)) != nil
    }
}

private struct OnDemandInstallWorkspaceMarker: Codable, Equatable {
    let schemaVersion: Int
    let runID: String
    let operationSHA256: String

    init(schemaVersion: Int = 1, runID: String, operationSHA256: String) {
        self.schemaVersion = schemaVersion
        self.runID = runID
        self.operationSHA256 = operationSHA256
    }
}
