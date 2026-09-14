import CryptoKit
import Darwin
import Foundation

public protocol DesktopComponentReleaseDownloading {
    func download(
        _ component: DesktopComponentReleaseArtifactV2,
        into downloadRoot: URL
    ) async throws -> URL
}

extension DesktopComponentReleaseDownloader: DesktopComponentReleaseDownloading {}

public protocol DesktopComponentArchiveExtracting {
    func extractComponent(
        archive: URL,
        metadata: DesktopComponentReleaseArtifactV2,
        into destinationRoot: URL,
        runID: String
    ) throws -> URL
}

extension DesktopTarArchiveExtractor: DesktopComponentArchiveExtracting {}

public enum DesktopComponentReleaseInstallError: Error, Equatable, Sendable {
    case invalidWorkspace
    case workspaceAlreadyExists
    case invalidManifest
    case componentHealthProbeFailed(DesktopManagedComponentKind)
    case preparationMismatch
    case cleanupFailed
}

public struct DesktopPreparedComponentRelease: Equatable, Identifiable, Sendable {
    public let id: String
    public let runID: String
    public let manifest: DesktopComponentReleaseManifestV2
    public let workspaceDirectory: URL

    fileprivate let issuerID: UUID
    fileprivate let verifiedManifest: VerifiedDesktopComponentReleaseManifestV2
    fileprivate let receipts: [DesktopManagedComponentReceipt]
    fileprivate let stagedSources: [DesktopManagedComponentKind: URL]
    fileprivate let workspaceBaseDirectory: URL
}

public struct DesktopInstalledComponentRelease: Equatable, Sendable {
    public let manifest: DesktopComponentReleaseManifestV2
    public let activationPlan: DesktopComponentReleaseActivationPlan
    public let referenceURL: URL
    public let workspaceDirectory: URL

    fileprivate let workspaceBaseDirectory: URL

    fileprivate init(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        referenceURL: URL,
        workspaceDirectory: URL,
        workspaceBaseDirectory: URL
    ) {
        self.manifest = manifest
        self.activationPlan = activationPlan
        self.referenceURL = referenceURL
        self.workspaceDirectory = workspaceDirectory
        self.workspaceBaseDirectory = workspaceBaseDirectory
    }
}

/// Prepares and commits the four verified schema-v2 bootstrap archives. `prepare` may download and
/// extract into a private cache but cannot change the component store. `commit` accepts only a token
/// issued by this installer, rechecks every staged component, writes the immutable store/reference,
/// and returns the activation plan. Neither phase writes credentials, LaunchAgents, `current`, or
/// launchd state.
public final class DesktopComponentReleaseInstaller: @unchecked Sendable {
    public typealias HealthProbe = DesktopComponentReleaseActivationPlanner.HealthProbe

    private static let installOrder: [DesktopManagedComponentKind] = [
        .pythonRuntime, .nodeRuntime, .hermesCore, .connector,
    ]

    private let downloader: any DesktopComponentReleaseDownloading
    private let extractor: any DesktopComponentArchiveExtracting
    private let inspector: DesktopManagedComponentStoreInspector
    private let writer: DesktopManagedComponentStoreWriter
    private let activationPlanner: DesktopComponentReleaseActivationPlanner
    private let contentHasher: DesktopManagedComponentContentHasher
    private let fileManager: FileManager
    private let issuerID = UUID()

    public init(
        storeRoot: URL,
        currentUserID: UInt32,
        downloader: any DesktopComponentReleaseDownloading = DesktopComponentReleaseDownloader(),
        extractor: any DesktopComponentArchiveExtracting = DesktopTarArchiveExtractor(
            runner: SystemOutputCommandRunner()
        ),
        fileManager: FileManager = .default
    ) throws {
        self.downloader = downloader
        self.extractor = extractor
        inspector = try DesktopManagedComponentStoreInspector(
            root: storeRoot,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        writer = try DesktopManagedComponentStoreWriter(
            root: storeRoot,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        activationPlanner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: storeRoot,
            currentUserID: currentUserID,
            fileManager: fileManager
        )
        contentHasher = DesktopManagedComponentContentHasher(fileManager: fileManager)
        self.fileManager = fileManager
    }

    public func install(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        workspaceRoot: URL,
        runID: String,
        healthProbe: HealthProbe
    ) async throws -> DesktopInstalledComponentRelease {
        let prepared = try await prepare(
            verifiedManifest: verifiedManifest,
            workspaceRoot: workspaceRoot,
            runID: runID,
            healthProbe: healthProbe
        )
        return try commit(prepared, healthProbe: healthProbe)
    }

    public func prepare(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        workspaceRoot: URL,
        runID: String,
        healthProbe: HealthProbe
    ) async throws -> DesktopPreparedComponentRelease {
        let manifest = verifiedManifest.manifest
        guard DesktopComponentReleaseActivationPlanner.validBootstrapManifest(manifest) else {
            throw DesktopComponentReleaseInstallError.invalidManifest
        }
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopComponentReleaseInstallError.invalidWorkspace
        }
        let base = try preparePrivateWorkspaceRoot(workspaceRoot)
        let runRoot = base.appendingPathComponent(normalizedRunID, isDirectory: true)
        try prepareRunRoot(
            runRoot,
            runID: normalizedRunID,
            manifestSHA256: try manifestIdentity(manifest)
        )

        do {
            let downloadRoot = runRoot.appendingPathComponent("downloads", isDirectory: true)
            let extractionRoot = runRoot.appendingPathComponent("extracted", isDirectory: true)
            try prepareRunDirectory(downloadRoot)
            try prepareRunDirectory(extractionRoot)
            var receipts: [DesktopManagedComponentReceipt] = []
            var stagedSources: [DesktopManagedComponentKind: URL] = [:]

            for kind in Self.installOrder {
                guard let artifact = manifest.components.first(where: { $0.kind == kind }) else {
                    throw DesktopComponentReleaseInstallError.invalidManifest
                }
                let requirement: DesktopManagedComponentRequirement
                do { requirement = try artifact.preflightRequirement }
                catch { throw DesktopComponentReleaseInstallError.invalidManifest }
                let receipt = DesktopManagedComponentReceipt(
                    kind: artifact.kind,
                    version: artifact.version,
                    architecture: artifact.architecture,
                    contentSHA256: artifact.contentSHA256
                )
                receipts.append(receipt)

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
                        throw DesktopComponentReleaseInstallError.componentHealthProbeFailed(kind)
                    }
                    continue
                }

                let archive = try await downloader.download(artifact, into: downloadRoot)
                let extracted = try extractor.extractComponent(
                    archive: archive,
                    metadata: artifact,
                    into: extractionRoot,
                    runID: normalizedRunID
                )
                let entrypoint = extracted
                    .appendingPathComponent(artifact.entrypoint)
                    .standardizedFileURL
                guard try healthProbe(artifact.kind, extracted, entrypoint) else {
                    throw DesktopComponentReleaseInstallError.componentHealthProbeFailed(kind)
                }
                stagedSources[kind] = extracted
            }

            return DesktopPreparedComponentRelease(
                id: UUID().uuidString.lowercased(),
                runID: normalizedRunID,
                manifest: manifest,
                workspaceDirectory: runRoot,
                issuerID: issuerID,
                verifiedManifest: verifiedManifest,
                receipts: receipts,
                stagedSources: stagedSources,
                workspaceBaseDirectory: base
            )
        } catch let error as DesktopComponentDownloadError where error == .transportFailed {
            guard resetForTransportResume(runRoot, manifest: manifest) else {
                _ = cleanupRunRoot(runRoot, beneath: base)
                throw DesktopComponentReleaseInstallError.cleanupFailed
            }
            throw error
        } catch {
            guard cleanupRunRoot(runRoot, beneath: base) else {
                throw DesktopComponentReleaseInstallError.cleanupFailed
            }
            throw error
        }
    }

    public func commit(
        _ preparation: DesktopPreparedComponentRelease,
        healthProbe: HealthProbe
    ) throws -> DesktopInstalledComponentRelease {
        guard preparation.issuerID == issuerID,
              preparation.verifiedManifest.manifest == preparation.manifest,
              preparation.workspaceDirectory.deletingLastPathComponent()
                == preparation.workspaceBaseDirectory,
              validPrivateDirectory(preparation.workspaceDirectory),
              validInterruptedMarker(
                  preparation.workspaceDirectory.appendingPathComponent("install.json"),
                  runID: preparation.runID
              ),
              (try? manifestIdentity(preparation.manifest))
                == (try? workspaceManifestIdentity(preparation.workspaceDirectory))
        else { throw DesktopComponentReleaseInstallError.preparationMismatch }

        do {
            try validatePreparedContent(preparation, healthProbe: healthProbe)
            for kind in Self.installOrder {
                guard let artifact = preparation.manifest.components.first(where: {
                    $0.kind == kind
                }),
                      let receipt = preparation.receipts.first(where: { $0.kind == kind })
                else { throw DesktopComponentReleaseInstallError.preparationMismatch }
                guard let source = preparation.stagedSources[kind] else { continue }
                _ = try writer.commit(
                    sourceDirectory: source,
                    receipt: receipt,
                    runID: preparation.runID,
                    healthProbe: { root in
                        try healthProbe(
                            artifact.kind,
                            root,
                            root.appendingPathComponent(artifact.entrypoint).standardizedFileURL
                        )
                    }
                )
            }

            let activationPlan = try activationPlanner.plan(
                manifest: preparation.manifest,
                healthProbe: healthProbe
            )
            let referenceURL = try writer.recordReferences(
                releaseVersion: preparation.manifest.releaseVersion,
                receipts: preparation.receipts,
                runID: preparation.runID
            )
            return DesktopInstalledComponentRelease(
                manifest: preparation.manifest,
                activationPlan: activationPlan,
                referenceURL: referenceURL,
                workspaceDirectory: preparation.workspaceDirectory,
                workspaceBaseDirectory: preparation.workspaceBaseDirectory
            )
        } catch {
            guard cleanupRunRoot(
                preparation.workspaceDirectory,
                beneath: preparation.workspaceBaseDirectory
            ) else { throw DesktopComponentReleaseInstallError.cleanupFailed }
            throw error
        }
    }

    private func validatePreparedContent(
        _ preparation: DesktopPreparedComponentRelease,
        healthProbe: HealthProbe
    ) throws {
        for kind in Self.installOrder {
            guard let artifact = preparation.manifest.components.first(where: {
                $0.kind == kind
            }),
                  let receipt = preparation.receipts.first(where: { $0.kind == kind }),
                  receipt.contentSHA256 == artifact.contentSHA256
            else { throw DesktopComponentReleaseInstallError.preparationMismatch }
            if let source = preparation.stagedSources[kind] {
                guard try contentHasher.identify(directory: source).sha256
                    == artifact.contentSHA256
                else { throw DesktopManagedComponentStoreError.identityMismatch }
                let entrypoint = source
                    .appendingPathComponent(artifact.entrypoint)
                    .standardizedFileURL
                guard try healthProbe(kind, source, entrypoint) else {
                    throw DesktopComponentReleaseInstallError.componentHealthProbeFailed(kind)
                }
                continue
            }
            let requirement: DesktopManagedComponentRequirement
            do { requirement = try artifact.preflightRequirement }
            catch { throw DesktopComponentReleaseInstallError.invalidManifest }
            guard let candidate = try inspector.candidate(
                for: requirement,
                healthProbe: { root in
                    try healthProbe(
                        kind,
                        root,
                        root.appendingPathComponent(artifact.entrypoint).standardizedFileURL
                    )
                }
            ), candidate.healthProbePassed else {
                throw DesktopComponentReleaseInstallError.componentHealthProbeFailed(kind)
            }
        }
    }

    public func discard(_ preparation: DesktopPreparedComponentRelease) throws {
        guard preparation.issuerID == issuerID else {
            throw DesktopComponentReleaseInstallError.preparationMismatch
        }
        guard cleanupRunRoot(
            preparation.workspaceDirectory,
            beneath: preparation.workspaceBaseDirectory
        ) else { throw DesktopComponentReleaseInstallError.cleanupFailed }
    }

    public func discard(_ release: DesktopInstalledComponentRelease) throws {
        guard cleanupRunRoot(
            release.workspaceDirectory,
            beneath: release.workspaceBaseDirectory
        ) else { throw DesktopComponentReleaseInstallError.cleanupFailed }
    }

    public func discardInterruptedInstall(workspaceRoot: URL, runID: String) throws {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopComponentReleaseInstallError.invalidWorkspace
        }
        let base = workspaceRoot.standardizedFileURL
        guard base.resolvingSymlinksInPath() == base, validOwnedDirectory(base) else {
            throw DesktopComponentReleaseInstallError.invalidWorkspace
        }
        let runRoot = base.appendingPathComponent(normalizedRunID, isDirectory: true)
        guard filesystemObjectExists(runRoot) else { return }
        guard validPrivateDirectory(runRoot),
              validInterruptedMarker(
                runRoot.appendingPathComponent("install.json"),
                runID: normalizedRunID
              )
        else { throw DesktopComponentReleaseInstallError.invalidWorkspace }
        guard cleanupRunRoot(runRoot, beneath: base) else {
            throw DesktopComponentReleaseInstallError.cleanupFailed
        }
    }

    private func preparePrivateWorkspaceRoot(_ value: URL) throws -> URL {
        let standardized = value.standardizedFileURL
        guard standardized.isFileURL,
              standardized.path.hasPrefix("/"),
              standardized.path != "/"
        else { throw DesktopComponentReleaseInstallError.invalidWorkspace }
        if !filesystemObjectExists(standardized) {
            guard validOwnedDirectory(standardized.deletingLastPathComponent()) else {
                throw DesktopComponentReleaseInstallError.invalidWorkspace
            }
            do {
                try fileManager.createDirectory(
                    at: standardized,
                    withIntermediateDirectories: false,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopComponentReleaseInstallError.invalidWorkspace }
        }
        let resolved = standardized.resolvingSymlinksInPath()
        guard resolved == standardized, validOwnedDirectory(resolved) else {
            throw DesktopComponentReleaseInstallError.invalidWorkspace
        }
        do { try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: resolved.path) }
        catch { throw DesktopComponentReleaseInstallError.invalidWorkspace }
        return resolved
    }

    private func prepareRunRoot(
        _ value: URL,
        runID: String,
        manifestSHA256: String
    ) throws {
        let marker = value.appendingPathComponent("install.json")
        let expected = ComponentInstallWorkspaceMarker(
            runID: runID,
            manifestSHA256: manifestSHA256
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let expectedData: Data
        do { expectedData = try encoder.encode(expected) }
        catch { throw DesktopComponentReleaseInstallError.invalidManifest }

        if filesystemObjectExists(value) {
            guard validPrivateDirectory(value),
                  validPrivateFile(marker),
                  let existing = try? Data(contentsOf: marker),
                  existing == expectedData
            else { throw DesktopComponentReleaseInstallError.workspaceAlreadyExists }
            return
        }
        do {
            try fileManager.createDirectory(
                at: value,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
            try expectedData.write(to: marker, options: [.withoutOverwriting])
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: marker.path)
        } catch {
            try? fileManager.removeItem(at: value)
            throw DesktopComponentReleaseInstallError.invalidWorkspace
        }
    }

    private func prepareRunDirectory(_ value: URL) throws {
        if filesystemObjectExists(value) {
            guard validPrivateDirectory(value) else {
                throw DesktopComponentReleaseInstallError.workspaceAlreadyExists
            }
            return
        }
        do {
            try fileManager.createDirectory(
                at: value,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
        } catch { throw DesktopComponentReleaseInstallError.invalidWorkspace }
    }

    private func manifestIdentity(_ manifest: DesktopComponentReleaseManifestV2) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data: Data
        do { data = try encoder.encode(manifest) }
        catch { throw DesktopComponentReleaseInstallError.invalidManifest }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// A transport interruption may keep only signed-name partial downloads. Completed archives and
    /// extracted trees are removed so a retry revalidates them instead of trusting stale workspace
    /// content. The interrupted archive itself still resumes through the downloader's Range contract.
    private func resetForTransportResume(
        _ runRoot: URL,
        manifest: DesktopComponentReleaseManifestV2
    ) -> Bool {
        let downloadRoot = runRoot.appendingPathComponent("downloads", isDirectory: true)
        let extractionRoot = runRoot.appendingPathComponent("extracted", isDirectory: true)
        guard validPrivateDirectory(runRoot), validPrivateDirectory(downloadRoot),
              validPrivateDirectory(extractionRoot)
        else { return false }
        let allowedPartials = Dictionary(uniqueKeysWithValues: manifest.components.map {
            (".\($0.fileName).partial", $0.sizeBytes)
        })
        do {
            for value in try fileManager.contentsOfDirectory(
                at: downloadRoot,
                includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
                options: [.skipsHiddenFiles]
            ) {
                try fileManager.removeItem(at: value)
            }
            // Hidden partials are skipped above and must be exact signed names and private files.
            let hidden = try fileManager.contentsOfDirectory(
                at: downloadRoot,
                includingPropertiesForKeys: nil,
                options: []
            ).filter { $0.lastPathComponent.hasPrefix(".") }
            guard hidden.allSatisfy({
                guard let maximum = allowedPartials[$0.lastPathComponent] else { return false }
                return validPrivatePartialFile($0, maximumBytes: maximum)
            }) else { return false }
            try fileManager.removeItem(at: extractionRoot)
            return true
        } catch {
            return false
        }
    }

    private func workspaceManifestIdentity(_ runRoot: URL) throws -> String {
        let data = try Data(contentsOf: runRoot.appendingPathComponent("install.json"))
        let marker = try JSONDecoder().decode(ComponentInstallWorkspaceMarker.self, from: data)
        return marker.manifestSHA256
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
        ]), values.isDirectory == true, values.isSymbolicLink != true else { return false }
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
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid()
        else { return false }
        return true
    }

    private func validPrivateDirectory(_ value: URL) -> Bool {
        guard validOwnedDirectory(value),
              let attributes = try? fileManager.attributesOfItem(atPath: value.path),
              let permissions = attributes[.posixPermissions] as? NSNumber
        else { return false }
        return permissions.intValue & 0o077 == 0
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
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid(),
              let permissions = attributes[.posixPermissions] as? NSNumber,
              permissions.intValue & 0o077 == 0,
              let size = attributes[.size] as? NSNumber,
              (1...1_024).contains(size.intValue)
        else { return false }
        return true
    }

    private func validPrivatePartialFile(_ value: URL, maximumBytes: Int64) -> Bool {
        let standardized = value.standardizedFileURL
        guard maximumBytes > 0,
              standardized.resolvingSymlinksInPath() == standardized,
              let resource = try? standardized.resourceValues(forKeys: [
                  .isRegularFileKey, .isSymbolicLinkKey,
              ]),
              resource.isRegularFile == true,
              resource.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: standardized.path),
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid(),
              let permissions = attributes[.posixPermissions] as? NSNumber,
              permissions.intValue & 0o077 == 0,
              let size = attributes[.size] as? NSNumber,
              size.int64Value >= 0,
              size.int64Value <= maximumBytes
        else { return false }
        return true
    }

    private func validInterruptedMarker(_ value: URL, runID: String) -> Bool {
        guard validPrivateFile(value),
              let data = try? Data(contentsOf: value),
              data.count <= 1_024,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == ["schemaVersion", "runID", "manifestSHA256"],
              let marker = try? JSONDecoder().decode(
                ComponentInstallWorkspaceMarker.self, from: data
              ),
              marker.schemaVersion == 1,
              marker.runID == runID,
              marker.manifestSHA256.range(
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

private struct ComponentInstallWorkspaceMarker: Codable, Equatable {
    let schemaVersion: Int
    let runID: String
    let manifestSHA256: String

    init(schemaVersion: Int = 1, runID: String, manifestSHA256: String) {
        self.schemaVersion = schemaVersion
        self.runID = runID
        self.manifestSHA256 = manifestSHA256
    }
}
