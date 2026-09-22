import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleasePreflightRuntimeTests: XCTestCase {
    func testFetchesVerifiesThenScansExactSignedManifest() async throws {
        let fixture = try RuntimeManifestFixture()
        let downloader = RuntimeManifestDownloader(result: fixture.envelope)
        let scanner = RuntimePreflightScanner(result: fixture.result)
        let runtime = try DesktopComponentReleasePreflightRuntime(
            manifestURL: fixture.manifestURL,
            downloader: downloader,
            verifier: fixture.verifier,
            scanner: scanner
        )

        let result = try await runtime.load(healthProbe: { _, _, _ in true })
        let requestedURLs = await downloader.requestedURLs()

        XCTAssertEqual(result, fixture.result)
        XCTAssertEqual(requestedURLs, [fixture.manifestURL])
        XCTAssertEqual(scanner.scannedManifests(), [fixture.manifest])
    }

    func testTrustedLoadKeepsExactVerifierTokenBesidePresentationResult() async throws {
        let fixture = try RuntimeManifestFixture()
        let scanner = RuntimePreflightScanner(result: fixture.result)
        let runtime = try DesktopComponentReleasePreflightRuntime(
            manifestURL: fixture.manifestURL,
            downloader: RuntimeManifestDownloader(result: fixture.envelope),
            verifier: fixture.verifier,
            scanner: scanner
        )

        let trusted = try await runtime.loadTrusted(healthProbe: { _, _, _ in true })

        XCTAssertEqual(trusted.result, fixture.result)
        XCTAssertEqual(trusted.verifiedManifest.manifest, fixture.manifest)
        XCTAssertEqual(scanner.scannedManifests(), [fixture.manifest])
    }

    func testInvalidEnvelopeStopsBeforeEnvironmentScan() async throws {
        let fixture = try RuntimeManifestFixture()
        let downloader = RuntimeManifestDownloader(result: Data("unsigned".utf8))
        let scanner = RuntimePreflightScanner(result: fixture.result)
        let runtime = try DesktopComponentReleasePreflightRuntime(
            manifestURL: fixture.manifestURL,
            downloader: downloader,
            verifier: fixture.verifier,
            scanner: scanner
        )

        await assertPreflightRuntimeError(try await runtime.load(healthProbe: { _, _, _ in true })) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleaseVerificationError,
                .invalidEnvelope
            )
        }
        XCTAssertEqual(scanner.scanCount(), 0)
    }

    func testDownloadFailureStopsBeforeVerificationAndScan() async throws {
        let fixture = try RuntimeManifestFixture()
        let downloader = RuntimeManifestDownloader(failure: .transportFailed)
        let scanner = RuntimePreflightScanner(result: fixture.result)
        let runtime = try DesktopComponentReleasePreflightRuntime(
            manifestURL: fixture.manifestURL,
            downloader: downloader,
            verifier: fixture.verifier,
            scanner: scanner
        )

        await assertPreflightRuntimeError(try await runtime.load(healthProbe: { _, _, _ in true })) {
            XCTAssertEqual($0 as? DesktopReleaseDownloadError, .transportFailed)
        }
        XCTAssertEqual(scanner.scanCount(), 0)
    }

    func testConcurrentRefreshIsRejectedBeforeSecondDownload() async throws {
        let fixture = try RuntimeManifestFixture()
        let downloader = SuspendingRuntimeManifestDownloader(result: fixture.envelope)
        let scanner = RuntimePreflightScanner(result: fixture.result)
        let runtime = try DesktopComponentReleasePreflightRuntime(
            manifestURL: fixture.manifestURL,
            downloader: downloader,
            verifier: fixture.verifier,
            scanner: scanner
        )
        let first = Task {
            try await runtime.load(healthProbe: { _, _, _ in true })
        }
        await downloader.waitUntilStarted()

        await assertPreflightRuntimeError(try await runtime.load(healthProbe: { _, _, _ in true })) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleasePreflightRuntimeError,
                .operationInProgress
            )
        }
        await downloader.release()
        let firstResult = try await first.value
        let downloadCount = await downloader.downloadCount()
        XCTAssertEqual(firstResult, fixture.result)
        XCTAssertEqual(downloadCount, 1)
        XCTAssertEqual(scanner.scanCount(), 1)
    }

    func testConfigurationRejectsNonHTTPSOrAmbiguousManifestURLs() throws {
        let fixture = try RuntimeManifestFixture()
        for value in [
            "http://updates.example/component-v2.json",
            "https://user@updates.example/component-v2.json",
            "https://updates.example/component-v2.json?candidate=1",
            "https://updates.example/",
            "https://updates.example/releases%2fcomponent-v2.json",
        ] {
            XCTAssertThrowsError(try DesktopComponentReleasePreflightRuntime(
                manifestURL: URL(string: value)!,
                verifier: fixture.verifier,
                scanner: RuntimePreflightScanner(result: fixture.result)
            )) {
                XCTAssertEqual(
                    $0 as? DesktopComponentReleasePreflightRuntimeError,
                    .invalidConfiguration
                )
            }
        }
    }
}

private func assertPreflightRuntimeError<T>(
    _ expression: @autoclosure () async throws -> T,
    _ handler: (Error) -> Void
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        handler(error)
    }
}

private actor RuntimeManifestDownloader: DesktopComponentManifestDownloading {
    private let result: Data?
    private let failure: DesktopReleaseDownloadError?
    private var URLs: [URL] = []

    init(result: Data? = nil, failure: DesktopReleaseDownloadError? = nil) {
        self.result = result
        self.failure = failure
    }

    func fetchManifest(from url: URL) async throws -> Data {
        URLs.append(url)
        if let failure { throw failure }
        return result!
    }

    func requestedURLs() -> [URL] { URLs }
}

private actor SuspendingRuntimeManifestDownloader: DesktopComponentManifestDownloading {
    private let result: Data
    private var downloads = 0
    private var started = false
    private var startedWaiter: CheckedContinuation<Void, Never>?
    private var releaseWaiter: CheckedContinuation<Void, Never>?

    init(result: Data) { self.result = result }

    func fetchManifest(from url: URL) async throws -> Data {
        downloads += 1
        started = true
        startedWaiter?.resume()
        startedWaiter = nil
        await withCheckedContinuation { releaseWaiter = $0 }
        return result
    }

    func waitUntilStarted() async {
        guard !started else { return }
        await withCheckedContinuation { startedWaiter = $0 }
    }

    func release() {
        releaseWaiter?.resume()
        releaseWaiter = nil
    }

    func downloadCount() -> Int { downloads }
}

private final class RuntimePreflightScanner:
    DesktopComponentReleasePreflightScanning, @unchecked Sendable
{
    private let lock = NSLock()
    private let result: DesktopComponentReleasePreflightResult
    private var manifests: [DesktopComponentReleaseManifestV2] = []

    init(result: DesktopComponentReleasePreflightResult) { self.result = result }

    func scan(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        healthProbe: DesktopComponentReleasePreflightCoordinator.HealthProbe
    ) throws -> DesktopComponentReleasePreflightResult {
        lock.withLock { manifests.append(verifiedManifest.manifest) }
        return result
    }

    func scannedManifests() -> [DesktopComponentReleaseManifestV2] {
        lock.withLock { manifests }
    }

    func scanCount() -> Int { lock.withLock { manifests.count } }
}

private struct RuntimeManifestFixture {
    let manifestURL = URL(string: "https://updates.example/releases/component-v2.json")!
    let manifest: DesktopComponentReleaseManifestV2
    let envelope: Data
    let verifier: DesktopComponentReleaseManifestV2Verifier
    let result: DesktopComponentReleasePreflightResult

    init() throws {
        let signer = Curve25519.Signing.PrivateKey()
        let nodeHash = Self.hash("b")
        manifest = DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.0",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [
                Self.component(.nodeRuntime, hash: nodeHash, entrypoint: "bin/node"),
                Self.component(
                    .connector,
                    hash: Self.hash("d"),
                    entrypoint: "bin/hermes-connector",
                    dependencies: [.init(kind: .nodeRuntime, contentSHA256: nodeHash)]
                ),
            ]
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let payload = try encoder.encode(manifest)
        let signature = try signer.signature(for: payload)
        envelope = try JSONSerialization.data(withJSONObject: [
            "algorithm": "Ed25519",
            "keyId": "test-key",
            "payload": payload.runtimeTestBase64URL,
            "signature": signature.runtimeTestBase64URL,
        ], options: [.sortedKeys])
        verifier = try DesktopComponentReleaseManifestV2Verifier(
            expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal",
            expectedArchitecture: "arm64",
            currentMacOS: OperatingSystemVersion(
                majorVersion: 14,
                minorVersion: 8,
                patchVersion: 0
            ),
            signingKeys: ["test-key": signer.publicKey.rawRepresentation],
            now: { Date(timeIntervalSince1970: 1_788_307_200) }
        )
        result = DesktopComponentReleasePreflightResult(
            manifest: manifest,
            plan: DesktopManagedComponentPreflightPlan(decisions: []),
            externalEnvironment: DesktopExternalEnvironmentScan(observations: [])
        )
    }

    private static func component(
        _ kind: DesktopManagedComponentKind,
        hash: String,
        entrypoint: String,
        dependencies: [DesktopComponentReleaseDependency] = []
    ) -> DesktopComponentReleaseArtifactV2 {
        let fileName = "Hermes-Component-\(kind.rawValue)-1.2.3-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            installPhase: .bootstrap,
            requiredForBootstrap: true,
            reuseContract: .exactContent,
            fileName: fileName,
            entrypoint: entrypoint,
            downloadURL: "https://downloads.example/desktop/components/\(fileName)",
            sizeBytes: 1_024,
            sha256: hash,
            contentSHA256: hash,
            dependencies: dependencies
        )
    }

    private static func hash(_ value: String) -> String {
        String(repeating: value, count: 64)
    }
}

private extension Data {
    var runtimeTestBase64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
