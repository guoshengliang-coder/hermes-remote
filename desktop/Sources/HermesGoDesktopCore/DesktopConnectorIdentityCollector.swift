import Darwin
import Foundation

/// Collects fresh identity for the selected schema-1 installation. It neither follows the latest
/// release index nor changes installed files/services. Other layouts remain unknown.
public struct DesktopConnectorIdentityCollector: Sendable {
    private let fetch: @Sendable (URL) async throws -> Data
    private let download: @Sendable (DesktopReleaseArtifact, URL) async throws -> URL
    private let runtime: @Sendable (DesktopBundledConnectorObservation, DesktopManagedInstallLayout) throws -> DesktopConnectorRuntimeIdentity

    public init() {
        let downloader = DesktopReleaseDownloader(session: URLSession(configuration: Self.publicSessionConfiguration()))
        fetch = { try await downloader.fetchManifest(from: $0) }
        download = { try await downloader.download($0, into: $1) }
        runtime = { try DesktopConnectorRuntimeIdentityInspector().inspect(observation: $0, layout: $1) }
    }

    init(fetch: @escaping @Sendable (URL) async throws -> Data,
         download: @escaping @Sendable (DesktopReleaseArtifact, URL) async throws -> URL,
         runtime: @escaping @Sendable (DesktopBundledConnectorObservation, DesktopManagedInstallLayout) throws -> DesktopConnectorRuntimeIdentity) {
        self.fetch = fetch; self.download = download; self.runtime = runtime
    }

    static func publicSessionConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.httpAdditionalHeaders = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 120
        configuration.waitsForConnectivity = false
        return configuration
    }

    public func collect(configuration: DesktopManagedBootstrapConfiguration,
                        layout: DesktopManagedInstallLayout) async throws -> DesktopConnectorRuntimeIdentity {
        var stage = "resolve_selected_release"
        var workspace: URL?
        defer { if let workspace { try? FileManager.default.removeItem(at: workspace) } }
        do {
            try Task.checkCancellation()
            let version = try Self.selectedVersion(layout)
            let url = try Self.manifestURL(configuration, version: version)
            stage = "fetch_manifest"
            let envelope = try await fetch(url)
            let verifier = try configuration.makeManifestVerifier()
            stage = "verify_manifest"
            let manifest = try DesktopBundledConnectorIdentityInspector.verifiedManifest(envelope, verifier: verifier)
            guard manifest.releaseVersion == version,
                  let artifact = manifest.artifacts.first(where: { $0.component == .connector }),
                  try Self.selectedVersion(layout) == version else { throw Self.issue }
            try Task.checkCancellation()
            stage = "prepare_workspace"
            let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
                .appendingPathComponent("hermes-connector-observation-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false,
                                                    attributes: [.posixPermissions: 0o700])
            workspace = root
            stage = "download_archive"
            let archive = try await download(artifact, root)
            try Task.checkCancellation()
            stage = "inspect_runtime"
            // Hashing, archive inspection and kernel/launchd reads must not block the UI actor.
            let observation = try await Task.detached(priority: .utility) {
                let files = try DesktopBundledConnectorIdentityInspector(verifier: verifier)
                    .inspect(signedManifest: envelope, archive: archive, layout: layout, workspaceRoot: root)
                return try runtime(files, layout)
            }.value
            try Task.checkCancellation()
            guard try Self.selectedVersion(layout) == version else { throw Self.issue }
            stage = "cleanup_workspace"
            try FileManager.default.removeItem(at: root)
            workspace = nil
            return observation
        } catch {
            throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid,
                               technicalCause: "connector_identity_collection=\(stage)")
        }
    }

    static func selectedVersion(_ layout: DesktopManagedInstallLayout) throws -> String {
        var info = stat()
        guard lstat(layout.currentRelease.path, &info) == 0, info.st_mode & S_IFMT == S_IFLNK,
              info.st_uid == getuid() else { throw issue }
        let target = try FileManager.default.destinationOfSymbolicLink(atPath: layout.currentRelease.path)
        guard target.hasPrefix("releases/") else { throw issue }
        let version = String(target.dropFirst("releases/".count))
        guard DesktopManagedInstallLayout.validVersion(version) else { throw issue }
        return version
    }

    static func manifestURL(_ configuration: DesktopManagedBootstrapConfiguration, version: String) throws -> URL {
        guard DesktopManagedInstallLayout.validVersion(version) else { throw issue }
        if let pinned = configuration.pinnedReleaseVersion {
            guard pinned == version else { throw issue }
            return configuration.manifestURL
        }
        guard configuration.manifestURL.lastPathComponent == "index.json" else { throw issue }
        // Canonical schema-1 publisher path. This is discovery only: the signed release must
        // independently match the selected version, channel, architecture and artifact origin.
        return configuration.manifestURL.deletingLastPathComponent()
            .appendingPathComponent(version, isDirectory: true)
            .appendingPathComponent("Hermes-Desktop-\(version)-\(configuration.architecture).manifest.json")
    }

    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}
