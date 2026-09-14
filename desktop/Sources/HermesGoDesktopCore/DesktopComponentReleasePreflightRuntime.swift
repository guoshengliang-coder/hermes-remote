import Foundation

public protocol DesktopComponentManifestDownloading: Sendable {
    func fetchManifest(from url: URL) async throws -> Data
}

extension DesktopReleaseDownloader: DesktopComponentManifestDownloading {}

public protocol DesktopComponentReleasePreflightScanning: Sendable {
    func scan(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        healthProbe: DesktopComponentReleasePreflightCoordinator.HealthProbe
    ) throws -> DesktopComponentReleasePreflightResult
}

extension DesktopComponentReleasePreflightCoordinator: DesktopComponentReleasePreflightScanning {}

public enum DesktopComponentReleasePreflightRuntimeError: Error, Equatable, Sendable {
    case invalidConfiguration
    case operationInProgress
}

/// Fetches one bounded HTTPS manifest, verifies its Ed25519 envelope, then performs the read-only
/// component and external-environment scan. Construction is inert, and failures before verification
/// never reach the scanner. This runtime does not install content or change services.
public actor DesktopComponentReleasePreflightRuntime {
    private let manifestURL: URL
    private let downloader: any DesktopComponentManifestDownloading
    private let verifier: DesktopComponentReleaseManifestV2Verifier
    private let scanner: any DesktopComponentReleasePreflightScanning
    private var running = false

    public init(
        manifestURL: URL,
        downloader: any DesktopComponentManifestDownloading = DesktopReleaseDownloader(),
        verifier: DesktopComponentReleaseManifestV2Verifier,
        scanner: any DesktopComponentReleasePreflightScanning
    ) throws {
        guard Self.validManifestURL(manifestURL) else {
            throw DesktopComponentReleasePreflightRuntimeError.invalidConfiguration
        }
        self.manifestURL = manifestURL
        self.downloader = downloader
        self.verifier = verifier
        self.scanner = scanner
    }

    public func load(
        healthProbe: @escaping DesktopComponentReleasePreflightCoordinator.HealthProbe
    ) async throws -> DesktopComponentReleasePreflightResult {
        guard !running else {
            throw DesktopComponentReleasePreflightRuntimeError.operationInProgress
        }
        running = true
        defer { running = false }

        let envelope = try await downloader.fetchManifest(from: manifestURL)
        let verifiedManifest = try verifier.verifyForInstallation(envelope)
        return try scanner.scan(
            verifiedManifest: verifiedManifest,
            healthProbe: healthProbe
        )
    }

    private static func validManifestURL(_ value: URL) -> Bool {
        guard value.scheme == "https", value.host != nil,
              value.user == nil, value.password == nil,
              value.query == nil, value.fragment == nil,
              !value.path.isEmpty, value.path != "/",
              !value.pathComponents.contains(".."),
              let encodedPath = URLComponents(url: value, resolvingAgainstBaseURL: false)?
                .percentEncodedPath.lowercased(),
              !encodedPath.contains("%2f"), !encodedPath.contains("%5c")
        else { return false }
        return true
    }
}
