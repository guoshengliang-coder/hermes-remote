import CryptoKit
import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleasePreflightTests: XCTestCase {
    func testFreshMachineDownloadsOnlyNodeAndConnector() throws {
        let fixture = try ComponentPreflightFixture()
        defer { fixture.remove() }
        let scanner = PreflightEnvironmentScanner()
        let coordinator = try fixture.coordinator(scanner: scanner)

        let result = try coordinator.scan(
            verifiedManifest: fixture.verifiedManifest,
            healthProbe: executablePreflightProbe
        )

        XCTAssertEqual(result.manifest, fixture.manifest)
        XCTAssertEqual(result.plan.decisions.map(\.requirement.kind), [
            .nodeRuntime, .connector,
        ])
        XCTAssertEqual(result.plan.decisions.map(\.action), [
            .download, .download,
        ])
        XCTAssertEqual(result.plan.bootstrapDownloadBytes, 36_057_789)
        XCTAssertEqual(result.plan.deferredDownloadBytes, 0)
        XCTAssertEqual(scanner.scanCount, 1)
    }

    func testHealthyManagedBootstrapContentIsReusedAfterIdentityCheck() throws {
        let fixture = try ComponentPreflightFixture()
        defer { fixture.remove() }
        for kind in [DesktopManagedComponentKind.nodeRuntime, .connector] {
            try fixture.commit(kind)
        }
        let coordinator = try fixture.coordinator(scanner: PreflightEnvironmentScanner())

        let result = try coordinator.scan(
            verifiedManifest: fixture.verifiedManifest,
            healthProbe: executablePreflightProbe
        )

        XCTAssertEqual(result.plan.decisions.map(\.action).filter {
            if case .reuse = $0 { return true }
            return false
        }.count, 2)
        XCTAssertEqual(result.plan.bootstrapDownloadBytes, 0)
        XCTAssertEqual(result.plan.deferredDownloadBytes, 0)
    }

    func testUnhealthyManagedComponentStopsBeforeExternalScan() throws {
        let fixture = try ComponentPreflightFixture()
        defer { fixture.remove() }
        try fixture.commit(.nodeRuntime)
        let scanner = PreflightEnvironmentScanner()
        let coordinator = try fixture.coordinator(scanner: scanner)

        XCTAssertThrowsError(try coordinator.scan(
            verifiedManifest: fixture.verifiedManifest,
            healthProbe: { kind, _, _ in kind != .nodeRuntime }
        )) { error in
            XCTAssertEqual(
                error as? DesktopComponentReleasePreflightError,
                .componentHealthProbeFailed(.nodeRuntime)
            )
        }
        XCTAssertEqual(scanner.scanCount, 0)
    }

    func testSignedManifestWithWrongBootstrapTopologyStopsBeforeInspection() throws {
        let fixture = try ComponentPreflightFixture()
        defer { fixture.remove() }
        var components = fixture.manifest.components
        let connectorIndex = try XCTUnwrap(components.firstIndex(where: { $0.kind == .connector }))
        let connector = components[connectorIndex]
        components[connectorIndex] = fixture.artifact(
            kind: .connector,
            contentSHA256: connector.contentSHA256,
            dependencies: connector.dependencies.filter { $0.kind != .nodeRuntime }
        )
        let scanner = PreflightEnvironmentScanner()

        XCTAssertThrowsError(try fixture.verify(fixture.manifest(components: components))) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseVerificationError, .incompatibleRelease)
        }
        XCTAssertEqual(scanner.scanCount, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.store.path))
    }

    private var executablePreflightProbe: DesktopComponentReleasePreflightCoordinator.HealthProbe {
        { _, root, entrypoint in
            FileManager.default.fileExists(atPath: root.path)
                && FileManager.default.isExecutableFile(atPath: entrypoint.path)
        }
    }
}

private final class PreflightEnvironmentScanner: DesktopExternalEnvironmentScanning {
    private let result: DesktopExternalEnvironmentScan
    private(set) var scanCount = 0

    init(result: DesktopExternalEnvironmentScan = .init(observations: [])) {
        self.result = result
    }

    func scan(
        requirements: [DesktopManagedComponentRequirement]
    ) -> DesktopExternalEnvironmentScan {
        scanCount += 1
        return result
    }
}

private final class ComponentPreflightFixture {
    let root: URL
    let store: URL
    let manifest: DesktopComponentReleaseManifestV2
    let verifiedManifest: VerifiedDesktopComponentReleaseManifestV2
    private let sources: [DesktopManagedComponentKind: URL]
    private let hashes: [DesktopManagedComponentKind: String]

    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "hermes-component-preflight-\(UUID().uuidString)", isDirectory: true
        )
        store = root.appendingPathComponent("managed", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        let entrypoints: [DesktopManagedComponentKind: String] = [
            .nodeRuntime: "bin/node",
            .connector: "bin/hermes-connector",
        ]
        var builtSources: [DesktopManagedComponentKind: URL] = [:]
        var builtHashes: [DesktopManagedComponentKind: String] = [:]
        for (kind, relative) in entrypoints {
            let source = root.appendingPathComponent("source-\(kind.rawValue)", isDirectory: true)
            let entrypoint = source.appendingPathComponent(relative)
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o700], ofItemAtPath: entrypoint.path
            )
            builtSources[kind] = source
            builtHashes[kind] = try DesktopManagedComponentContentHasher()
                .identify(directory: source).sha256
        }
        sources = builtSources
        hashes = builtHashes
        let node = Self.makeArtifact(
            kind: .nodeRuntime,
            contentSHA256: builtHashes[.nodeRuntime]!,
            dependencies: []
        )
        let connector = Self.makeArtifact(
            kind: .connector,
            contentSHA256: builtHashes[.connector]!,
            dependencies: [.init(kind: .nodeRuntime, contentSHA256: builtHashes[.nodeRuntime]!)]
        )
        let builtManifest = DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.2",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [node, connector]
        )
        manifest = builtManifest
        verifiedManifest = try Self.verify(builtManifest)
    }

    func coordinator(
        scanner: PreflightEnvironmentScanner
    ) throws -> DesktopComponentReleasePreflightCoordinator {
        try DesktopComponentReleasePreflightCoordinator(
            storeRoot: store,
            currentUserID: Darwin.getuid(),
            externalScanner: scanner
        )
    }

    func commit(_ kind: DesktopManagedComponentKind) throws {
        let component = manifest.components.first(where: { $0.kind == kind })!
        _ = try DesktopManagedComponentStoreWriter(
            root: store,
            currentUserID: Darwin.getuid()
        ).commit(
            sourceDirectory: sources[kind]!,
            receipt: DesktopManagedComponentReceipt(
                kind: kind,
                version: component.version,
                architecture: component.architecture,
                contentSHA256: hashes[kind]!
            ),
            runID: UUID().uuidString,
            healthProbe: { _ in true }
        )
    }

    func artifact(
        kind: DesktopManagedComponentKind,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        Self.makeArtifact(
            kind: kind,
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
    }

    func manifest(
        components: [DesktopComponentReleaseArtifactV2]
    ) -> DesktopComponentReleaseManifestV2 {
        DesktopComponentReleaseManifestV2(
            releaseVersion: manifest.releaseVersion,
            channel: manifest.channel,
            architecture: manifest.architecture,
            minimumMacOS: manifest.minimumMacOS,
            createdAt: manifest.createdAt,
            expiresAt: manifest.expiresAt,
            components: components
        )
    }

    func verify(
        _ manifest: DesktopComponentReleaseManifestV2
    ) throws -> VerifiedDesktopComponentReleaseManifestV2 {
        try Self.verify(manifest)
    }

    func remove() {
        try? FileManager.default.removeItem(at: root)
    }

    private static func makeArtifact(
        kind: DesktopManagedComponentKind,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        let entrypoints: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "bin/python3",
            .nodeRuntime: "bin/node",
            .hermesCore: "bin/hermes",
            .connector: "bin/hermes-connector",
            .browserAutomation: "bin/chromium",
        ]
        let versions: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "3.11.15",
            .nodeRuntime: "22.23.2",
            .hermesCore: "0.21.0",
            .connector: "0.3.2",
            .browserAutomation: "1.2.3",
        ]
        let sizes: [DesktopManagedComponentKind: Int64] = [
            .pythonRuntime: 45_000_000,
            .nodeRuntime: 36_000_000,
            .hermesCore: 17_000_000,
            .connector: 57_789,
            .browserAutomation: 130_000_000,
        ]
        let isBrowser = kind == .browserAutomation
        let fileName = "Hermes-Component-\(kind.rawValue)-\(versions[kind]!)-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: versions[kind]!,
            architecture: "arm64",
            installPhase: isBrowser ? .onDemand : .bootstrap,
            requiredForBootstrap: !isBrowser,
            onDemandTrigger: isBrowser ? "browser" : nil,
            reuseContract: isBrowser ? .verifiedCompatibility : .exactContent,
            compatibilityIdentifier: isBrowser ? "playwright-system-chromium-v1" : nil,
            fileName: fileName,
            entrypoint: entrypoints[kind]!,
            downloadURL: "https://downloads.example/desktop/components/\(fileName)",
            sizeBytes: max(1, sizes[kind]!),
            sha256: SHA256.hash(data: Data(kind.rawValue.utf8))
                .map { String(format: "%02x", $0) }.joined(),
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
    }

    private static func verify(
        _ manifest: DesktopComponentReleaseManifestV2
    ) throws -> VerifiedDesktopComponentReleaseManifestV2 {
        let signer = Curve25519.Signing.PrivateKey()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let payload = try encoder.encode(manifest)
        let signature = try signer.signature(for: payload)
        let envelope = try JSONSerialization.data(withJSONObject: [
            "algorithm": "Ed25519",
            "keyId": "test-key",
            "payload": base64URL(payload),
            "signature": base64URL(signature),
        ], options: [.sortedKeys])
        return try DesktopComponentReleaseManifestV2Verifier(
            expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal",
            expectedArchitecture: "arm64",
            currentMacOS: OperatingSystemVersion(
                majorVersion: 14, minorVersion: 8, patchVersion: 0
            ),
            signingKeys: ["test-key": signer.publicKey.rawRepresentation],
            now: { ISO8601DateFormatter().date(from: "2026-09-10T00:00:00Z")! }
        ).verifyForInstallation(envelope)
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
