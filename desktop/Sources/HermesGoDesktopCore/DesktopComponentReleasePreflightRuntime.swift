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

/// Carries the exact verifier-issued capability beside its read-only presentation input. The
/// manifest token cannot be constructed outside Core and is the only value accepted by component
/// preparation, so callers never need to rebuild install authority from display data.
public struct DesktopTrustedComponentPreflight: Equatable, Sendable {
    public let result: DesktopComponentReleasePreflightResult
    public let verifiedManifest: VerifiedDesktopComponentReleaseManifestV2

    init(
        result: DesktopComponentReleasePreflightResult,
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2
    ) {
        self.result = result
        self.verifiedManifest = verifiedManifest
    }
}

/// Fetches one bounded HTTPS manifest, verifies its Ed25519 envelope, then performs the read-only
/// component and external-environment scan. Construction is inert, and failures before verification
/// never reach the scanner. This runtime does not install content or change services.
public actor DesktopComponentReleasePreflightRuntime {
    private let manifestURL: URL
    private let downloader: any DesktopComponentManifestDownloading
    private let verifier: DesktopComponentReleaseManifestV2Verifier
    private let scanner: any DesktopComponentReleasePreflightScanning
    private let indexChannel: String?
    private let indexArchitecture: String?
    private var running = false

    public init(
        manifestURL: URL,
        downloader: any DesktopComponentManifestDownloading = DesktopReleaseDownloader(),
        verifier: DesktopComponentReleaseManifestV2Verifier,
        scanner: any DesktopComponentReleasePreflightScanning,
        indexChannel: String? = nil,
        indexArchitecture: String? = nil
    ) throws {
        guard Self.validManifestURL(manifestURL) else {
            throw DesktopComponentReleasePreflightRuntimeError.invalidConfiguration
        }
        self.manifestURL = manifestURL
        self.downloader = downloader
        self.verifier = verifier
        self.scanner = scanner
        self.indexChannel = indexChannel
        self.indexArchitecture = indexArchitecture
    }

    public func load(
        healthProbe: @escaping DesktopComponentReleasePreflightCoordinator.HealthProbe
    ) async throws -> DesktopComponentReleasePreflightResult {
        try await loadTrusted(healthProbe: healthProbe).result
    }

    public func loadTrusted(
        healthProbe: @escaping DesktopComponentReleasePreflightCoordinator.HealthProbe
    ) async throws -> DesktopTrustedComponentPreflight {
        guard !running else {
            throw DesktopComponentReleasePreflightRuntimeError.operationInProgress
        }
        running = true
        defer { running = false }

        let envelope: Data
        var indexReference: DesktopReleaseIndexReference?
        if manifestURL.lastPathComponent == "index.json" {
            guard let indexChannel, let indexArchitecture else {
                throw DesktopComponentReleasePreflightRuntimeError.invalidConfiguration
            }
            let index = try await downloader.fetchManifest(from: manifestURL)
            let reference = try DesktopReleaseIndex.resolve(
                index,
                indexURL: manifestURL,
                expectedChannel: indexChannel,
                expectedArchitecture: indexArchitecture
            )
            envelope = try await downloader.fetchManifest(from: reference.manifestURL)
            try DesktopReleaseIndex.verifyEnvelope(envelope, reference: reference)
            indexReference = reference
        } else {
            envelope = try await downloader.fetchManifest(from: manifestURL)
        }
        let verifiedManifest = try verifier.verifyForInstallation(envelope)
        if let indexReference,
           indexReference.releaseVersion != verifiedManifest.manifest.releaseVersion {
            throw DesktopReleaseIndexError.manifestIdentityMismatch
        }
        let result = try scanner.scan(
            verifiedManifest: verifiedManifest,
            healthProbe: healthProbe
        )
        return DesktopTrustedComponentPreflight(
            result: result,
            verifiedManifest: verifiedManifest
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
