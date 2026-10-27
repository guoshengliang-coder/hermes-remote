import Darwin
import Foundation

public struct DesktopAppIdentityCollector: Sendable {
    private let download: @Sendable (URL, URL) async throws -> URL
    private let inspect: @Sendable (HermesCompatibilityComponent, URL, URL, URL) throws -> DesktopAppIdentityObservation
    private let runtime: @Sendable (DesktopAppIdentityObservation, URL, Int32) throws -> DesktopAppRuntimeIdentity

    public init() {
        let downloader = DesktopReleaseDownloader(session: URLSession(configuration: DesktopConnectorIdentityCollector.publicSessionConfiguration()))
        download = { url, root in
            try await downloader.download(from: url, fileName: url.lastPathComponent,
                                          maximumBytes: 2 * 1024 * 1024 * 1024, into: root)
        }
        inspect = { try DesktopAppIdentityInspector().inspect(expected: $0, archive: $1, installedApp: $2, workspaceRoot: $3) }
        runtime = { try DesktopAppRuntimeIdentityInspector().inspect(observation: $0, installedApp: $1, processID: $2) }
    }

    init(download: @escaping @Sendable (URL, URL) async throws -> URL,
         inspect: @escaping @Sendable (HermesCompatibilityComponent, URL, URL, URL) throws -> DesktopAppIdentityObservation,
         runtime: @escaping @Sendable (DesktopAppIdentityObservation, URL, Int32) throws -> DesktopAppRuntimeIdentity) {
        self.download = download; self.inspect = inspect; self.runtime = runtime
    }

    /// `expected` must originate in a verified current compatibility snapshot. The public URL is
    /// discovery only; the full DMG digest is checked before mounting, independently of TLS/index.
    public func collect(expected: HermesCompatibilityComponent, configuration: DesktopAppUpdateConfiguration,
                        installedApp: URL, processID: Int32 = getpid()) async throws -> DesktopAppRuntimeIdentity {
        var stage = "validate_input"
        var workspace: URL?, downloaded: URL?
        defer {
            // An inspector may retain a failed-detach mount. Never recursively delete that tree.
            // Only unlink our outer archive and remove the workspace if it is already empty.
            if let downloaded { _ = unlink(downloaded.path) }
            if let workspace { _ = rmdir(workspace.path) }
        }
        do {
            try Task.checkCancellation()
            guard expected.name == "desktop", HermesCompatibilitySnapshot.version(expected.version),
                  expected.artifactSha256.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil
            else { throw Self.issue }
            let url = configuration.indexURL.deletingLastPathComponent()
                .appendingPathComponent(expected.version, isDirectory: true)
                .appendingPathComponent("Hermes-Go-Desktop-\(expected.version).dmg")
            stage = "prepare_workspace"
            let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
                .appendingPathComponent("hermes-desktop-observation-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false,
                                                    attributes: [.posixPermissions: 0o700])
            workspace = root
            stage = "download_archive"
            let archive = try await download(url, root)
            // Enforce the cleanup boundary even for an injected downloader.
            guard archive.standardizedFileURL == root.appendingPathComponent(url.lastPathComponent) else { throw Self.issue }
            downloaded = archive
            try Task.checkCancellation()
            stage = "inspect_runtime"
            let result = try await Task.detached(priority: .utility) {
                let observation = try inspect(expected, archive, installedApp, root)
                return try runtime(observation, installedApp, processID)
            }.value
            try Task.checkCancellation()
            stage = "cleanup_workspace"
            guard unlink(archive.path) == 0 else { throw Self.issue }
            downloaded = nil
            guard rmdir(root.path) == 0 else { throw Self.issue }
            workspace = nil
            return result
        } catch {
            throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid,
                               technicalCause: "desktop_identity_collection=\(stage)")
        }
    }

    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}
