import Darwin
import Foundation

public protocol DesktopReleaseDownloading {
    func fetchManifest(from url: URL) async throws -> Data
    func download(_ artifact: DesktopReleaseArtifact, into downloadRoot: URL) async throws -> URL
}

extension DesktopReleaseDownloader: DesktopReleaseDownloading {}

public protocol DesktopReleaseManifestVerifying {
    func verify(_ envelopeData: Data) throws -> DesktopReleaseManifest
}

extension DesktopReleaseManifestVerifier: DesktopReleaseManifestVerifying {}

public protocol DesktopReleaseArtifactVerifying {
    func verify(
        fileURL: URL,
        metadata: DesktopReleaseArtifact
    ) throws -> VerifiedDesktopArtifact
}

extension DesktopArtifactVerifier: DesktopReleaseArtifactVerifying {}

public protocol DesktopReleaseArchiveExtracting {
    func extract(
        _ artifact: VerifiedDesktopArtifact,
        into destinationRoot: URL,
        runID: String
    ) throws -> DesktopManagedReleaseSource
}

extension DesktopTarArchiveExtractor: DesktopReleaseArchiveExtracting {}

public enum DesktopReleaseAcquisitionError: Error, Equatable, Sendable {
    case invalidWorkspace
    case workspaceAlreadyExists
    case incompleteManifest
    case cleanupFailed
}

public struct DesktopAcquiredRelease: Equatable, Sendable {
    public let manifest: DesktopReleaseManifest
    public let sources: [DesktopManagedReleaseSource]
    public let workspaceDirectory: URL

    fileprivate let workspaceBaseDirectory: URL

    init(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        workspaceDirectory: URL,
        workspaceBaseDirectory: URL
    ) {
        self.manifest = manifest
        self.sources = sources
        self.workspaceDirectory = workspaceDirectory
        self.workspaceBaseDirectory = workspaceBaseDirectory
    }
}

/// Joins signed-manifest download, artifact verification, and safe extraction into one ordered
/// preparation step. It never activates a release, writes a credential, or changes launchd state.
/// The caller must pass the returned sources to `DesktopMigrationCoordinator`, then call `discard`.
public final class DesktopReleaseAcquirer: @unchecked Sendable {
    public typealias ArtifactVerifierFactory = @Sendable (URL) throws
        -> any DesktopReleaseArtifactVerifying

    private let downloader: any DesktopReleaseDownloading
    private let manifestVerifier: any DesktopReleaseManifestVerifying
    private let makeArtifactVerifier: ArtifactVerifierFactory
    private let extractor: any DesktopReleaseArchiveExtracting
    private let fileManager: FileManager

    public init(
        downloader: any DesktopReleaseDownloading,
        manifestVerifier: any DesktopReleaseManifestVerifying,
        makeArtifactVerifier: @escaping ArtifactVerifierFactory = {
            try DesktopArtifactVerifier(downloadRoot: $0)
        },
        extractor: any DesktopReleaseArchiveExtracting = DesktopTarArchiveExtractor(
            runner: SystemOutputCommandRunner()
        ),
        fileManager: FileManager = .default
    ) {
        self.downloader = downloader
        self.manifestVerifier = manifestVerifier
        self.makeArtifactVerifier = makeArtifactVerifier
        self.extractor = extractor
        self.fileManager = fileManager
    }

    public func acquire(
        manifestURL: URL,
        workspaceRoot: URL,
        runID: String
    ) async throws -> DesktopAcquiredRelease {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopReleaseAcquisitionError.invalidWorkspace
        }
        let base = try preparePrivateWorkspaceRoot(workspaceRoot)
        let runRoot = base.appendingPathComponent(normalizedRunID, isDirectory: true)
        guard !fileManager.fileExists(atPath: runRoot.path) else {
            throw DesktopReleaseAcquisitionError.workspaceAlreadyExists
        }
        do {
            try fileManager.createDirectory(
                at: runRoot,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
        } catch {
            if filesystemObjectExists(runRoot) {
                throw DesktopReleaseAcquisitionError.workspaceAlreadyExists
            }
            throw DesktopReleaseAcquisitionError.invalidWorkspace
        }

        do {
            let envelope = try await downloader.fetchManifest(from: manifestURL)
            let manifest = try manifestVerifier.verify(envelope)
            guard manifest.artifacts.count == DesktopReleaseComponentKind.allCases.count,
                  Set(manifest.artifacts.map(\.component)) == Set(DesktopReleaseComponentKind.allCases)
            else { throw DesktopReleaseAcquisitionError.incompleteManifest }
            let downloadRoot = runRoot.appendingPathComponent("downloads", isDirectory: true)
            let extractionRoot = runRoot.appendingPathComponent("extracted", isDirectory: true)
            try createPrivateDirectory(downloadRoot)
            try createPrivateDirectory(extractionRoot)
            let artifactVerifier = try makeArtifactVerifier(downloadRoot)
            var sources: [DesktopManagedReleaseSource] = []

            for component in DesktopReleaseComponentKind.allCases {
                guard let artifact = manifest.artifacts.first(where: { $0.component == component }) else {
                    throw DesktopReleaseAcquisitionError.incompleteManifest
                }
                let downloaded = try await downloader.download(artifact, into: downloadRoot)
                let verified = try artifactVerifier.verify(fileURL: downloaded, metadata: artifact)
                sources.append(try extractor.extract(
                    verified,
                    into: extractionRoot,
                    runID: normalizedRunID
                ))
            }
            guard sources.count == DesktopReleaseComponentKind.allCases.count,
                  Set(sources.map(\.component)) == Set(DesktopReleaseComponentKind.allCases)
            else { throw DesktopReleaseAcquisitionError.incompleteManifest }
            return DesktopAcquiredRelease(
                manifest: manifest,
                sources: sources,
                workspaceDirectory: runRoot,
                workspaceBaseDirectory: base
            )
        } catch {
            guard cleanupRunRoot(runRoot, beneath: base) else {
                throw DesktopReleaseAcquisitionError.cleanupFailed
            }
            throw error
        }
    }

    public func discard(_ release: DesktopAcquiredRelease) throws {
        guard cleanupRunRoot(
            release.workspaceDirectory,
            beneath: release.workspaceBaseDirectory
        ) else { throw DesktopReleaseAcquisitionError.cleanupFailed }
    }

    private func preparePrivateWorkspaceRoot(_ value: URL) throws -> URL {
        let standardized = value.standardizedFileURL
        guard standardized.isFileURL,
              standardized.path.hasPrefix("/"),
              standardized.path != "/"
        else { throw DesktopReleaseAcquisitionError.invalidWorkspace }
        if !filesystemObjectExists(standardized) {
            let parent = standardized.deletingLastPathComponent()
            guard validOwnedDirectory(parent) else {
                throw DesktopReleaseAcquisitionError.invalidWorkspace
            }
            do {
                try fileManager.createDirectory(
                    at: standardized,
                    withIntermediateDirectories: false,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopReleaseAcquisitionError.invalidWorkspace }
        }
        let resolved = standardized.resolvingSymlinksInPath()
        guard resolved == standardized,
              validOwnedDirectory(resolved)
        else { throw DesktopReleaseAcquisitionError.invalidWorkspace }
        do {
            try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: resolved.path)
        } catch { throw DesktopReleaseAcquisitionError.invalidWorkspace }
        return resolved
    }

    private func createPrivateDirectory(_ value: URL) throws {
        guard !fileManager.fileExists(atPath: value.path) else {
            throw DesktopReleaseAcquisitionError.workspaceAlreadyExists
        }
        do {
            try fileManager.createDirectory(
                at: value,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
        } catch {
            throw DesktopReleaseAcquisitionError.invalidWorkspace
        }
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

    private func filesystemObjectExists(_ value: URL) -> Bool {
        fileManager.fileExists(atPath: value.path)
            || (try? fileManager.destinationOfSymbolicLink(atPath: value.path)) != nil
    }
}
