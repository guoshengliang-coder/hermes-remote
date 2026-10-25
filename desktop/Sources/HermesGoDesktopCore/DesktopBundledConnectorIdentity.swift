import Darwin
import Foundation

/// Identity of the selected bundled Connector's files at inspection time. This is not proof of
/// the running process, a current compatibility recommendation, or permission to upgrade Hermes.
public struct DesktopBundledConnectorObservation: Equatable, Sendable {
    public let component: HermesCompatibilityComponent
    public let releaseVersion: String
    public let architecture: String
    public let contentSHA256: String

    fileprivate init(artifact: DesktopReleaseArtifact, manifest: DesktopReleaseManifest, contentSHA256: String) {
        component = HermesCompatibilityComponent(name: "connector", version: artifact.version,
                                               artifactSha256: artifact.sha256)
        releaseVersion = manifest.releaseVersion
        architecture = manifest.architecture
        self.contentSHA256 = contentSHA256
    }
}

/// Schema-1 receipts do not contain a content digest. Reconstruct the mapping from a verified
/// original archive without changing the installed release, its marker or launchd configuration.
public struct DesktopBundledConnectorIdentityInspector {
    private let verifier: DesktopReleaseManifestVerifier
    private let fileManager: FileManager

    public init(verifier: DesktopReleaseManifestVerifier, fileManager: FileManager = .default) {
        self.verifier = verifier
        self.fileManager = fileManager
    }

    public func inspect(
        signedManifest: Data,
        archive: URL,
        layout: DesktopManagedInstallLayout,
        workspaceRoot: URL
    ) throws -> DesktopBundledConnectorObservation {
        var stage = "verify_manifest"
        do {
            // The normal release verifier supplies signature/origin/architecture checks. Reject
            // JSON ambiguity here too, before using the manifest to establish local identity.
            guard DesktopHermesUpgradeJSON.isUnambiguous(signedManifest, maximumBytes: 256 * 1024),
                  let envelope = try JSONSerialization.jsonObject(with: signedManifest) as? [String: Any],
                  let encoded = envelope["payload"] as? String,
                  let payload = Data(desktopCanonicalBase64URL: encoded),
                  DesktopHermesUpgradeJSON.isUnambiguous(payload, maximumBytes: 128 * 1024)
            else { throw Self.issue }
            let manifest = try verifier.verify(signedManifest)
            guard let artifact = manifest.artifacts.first(where: { $0.component == .connector })
            else { throw Self.issue }
            stage = "inspect_installation"
            try requireCurrent(layout, releaseVersion: manifest.releaseVersion)
            let installed = try layout.release(manifest.releaseVersion).appendingPathComponent("connector")
            try requirePrivateDirectory(installed)
            let probe = DesktopManagedComponentEntrypointProbe(currentUserID: Darwin.getuid())
            guard try probe(.connector, root: installed,
                            entrypoint: installed.appendingPathComponent(artifact.entrypoint))
            else { throw Self.issue }
            let hasher = DesktopManagedComponentContentHasher(fileManager: fileManager)
            let before = try hasher.identify(directory: installed)
            stage = "verify_archive"
            _ = try DesktopArtifactVerifier(downloadRoot: archive.deletingLastPathComponent())
                .verify(fileURL: archive, metadata: artifact)

            // A caller supplies an existing private workspace; never chmod an installed path.
            stage = "prepare_reference"
            try requirePrivateDirectory(workspaceRoot)
            guard !workspaceRoot.standardizedFileURL.path.hasPrefix(layout.root.path + "/"),
                  workspaceRoot.standardizedFileURL != layout.root
            else { throw Self.issue }
            let run = workspaceRoot.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
            try fileManager.createDirectory(at: run, withIntermediateDirectories: false,
                                            attributes: [.posixPermissions: 0o700])
            defer { try? fileManager.removeItem(at: run) }
            // Extract a private verified copy, not a caller-owned download that can be replaced
            // between digest verification and tar reading it.
            stage = "copy_reference"
            let reference = run.appendingPathComponent(artifact.fileName)
            try fileManager.copyItem(at: archive, to: reference)
            stage = "verify_reference"
            let verified = try DesktopArtifactVerifier(downloadRoot: run)
                .verify(fileURL: reference, metadata: artifact)
            stage = "extract_reference"
            let source = try DesktopTarArchiveExtractor(runner: SystemOutputCommandRunner(), fileManager: fileManager)
                .extract(verified, into: run, runID: UUID().uuidString)
            stage = "compare_content"
            let expected = try hasher.identify(directory: source.directory)
            guard before == expected, try hasher.identify(directory: installed) == expected else { throw Self.issue }
            stage = "recheck_selection"
            try requireCurrent(layout, releaseVersion: manifest.releaseVersion)
            // Fail closed if the temporary reference could not be removed.
            stage = "cleanup_reference"
            try fileManager.removeItem(at: run)
            return DesktopBundledConnectorObservation(artifact: artifact, manifest: manifest,
                                                       contentSHA256: expected.sha256)
        } catch {
            // Only closed enums are retained; Foundation errors can contain private paths.
            let reason: String
            switch error {
            case let error as DesktopArtifactVerificationError: reason = String(describing: error)
            case let error as DesktopArchiveExtractionError: reason = String(describing: error)
            case let error as DesktopManagedComponentStoreError: reason = String(describing: error)
            case let error as DesktopReleaseVerificationError: reason = String(describing: error)
            default: reason = "unverified"
            }
            throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid,
                               technicalCause: "bundled_connector_identity=\(stage) reason=\(reason)")
        }
    }

    private func requireCurrent(_ layout: DesktopManagedInstallLayout, releaseVersion: String) throws {
        for directory in [layout.root, layout.releasesRoot, try layout.release(releaseVersion)] {
            try requirePrivateDirectory(directory)
        }
        var info = stat()
        guard Darwin.lstat(layout.currentRelease.path, &info) == 0,
              info.st_mode & S_IFMT == S_IFLNK, info.st_uid == Darwin.getuid(),
              try fileManager.destinationOfSymbolicLink(atPath: layout.currentRelease.path) == "releases/\(releaseVersion)"
        else { throw Self.issue }
    }

    private func requirePrivateDirectory(_ url: URL) throws {
        let normalized = url.standardizedFileURL
        var info = stat()
        guard normalized.isFileURL, normalized.path != "/",
              normalized == normalized.resolvingSymlinksInPath(),
              Darwin.lstat(normalized.path, &info) == 0,
              info.st_mode & S_IFMT == S_IFDIR, info.st_uid == Darwin.getuid(),
              info.st_mode & 0o022 == 0
        else { throw Self.issue }
    }

    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}
