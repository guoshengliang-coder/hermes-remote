import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleaseActivationTests: XCTestCase {
    func testFinalTopologyPinsOnlyNodeAndConnector() throws {
        let fixture = try FinalActivationFixture()
        defer { fixture.remove() }
        let plan = try DesktopComponentReleaseActivationPlanner(
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid()
        ).plan(manifest: fixture.manifest) { _, _, entrypoint in
            FileManager.default.isExecutableFile(atPath: entrypoint.path)
        }

        XCTAssertEqual(plan.components.map(\.kind), [.connector, .nodeRuntime])
        let layout = try DesktopManagedInstallLayout(
            root: fixture.store,
            launchAgentsRoot: fixture.base.appendingPathComponent("agents")
        )
        let configuration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: fixture.base.appendingPathComponent(".hermes"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        let agents = try configuration.componentLaunchAgents(for: plan)
        XCTAssertEqual(agents.hermes.hermesExecutable, layout.localHermesLauncher)
        XCTAssertEqual(agents.connector.connectorExecutable, plan.component(.connector)?.entrypoint)
        XCTAssertEqual(agents.connector.nodeRuntimeRoot, plan.component(.nodeRuntime)?.root)
        XCTAssertNoThrow(try DesktopManagedInstaller(layout: layout).writeLaunchAgent(
            agents.connector,
            activationPlan: plan
        ))
    }

    func testLegacyFourComponentTopologyIsNotActivatable() throws {
        let fixture = try FinalActivationFixture(includeBundledHermes: true)
        defer { fixture.remove() }
        XCTAssertThrowsError(try DesktopComponentReleaseActivationPlanner(
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid()
        ).plan(manifest: fixture.manifest) { _, _, _ in true }) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseActivationError, .invalidManifest)
        }
    }
}

private final class FinalActivationFixture {
    let base: URL
    let store: URL
    let manifest: DesktopComponentReleaseManifestV2

    init(includeBundledHermes: Bool = false) throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(
            "hermes-final-activation-\(UUID().uuidString)", isDirectory: true
        )
        store = base.appendingPathComponent("managed", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: false)
        let writer = try DesktopManagedComponentStoreWriter(root: store, currentUserID: Darwin.getuid())
        let kinds: [DesktopManagedComponentKind] = includeBundledHermes
            ? [.pythonRuntime, .hermesCore, .nodeRuntime, .connector]
            : [.nodeRuntime, .connector]
        var hashes: [DesktopManagedComponentKind: String] = [:]
        for kind in kinds {
            let source = base.appendingPathComponent("source-\(kind.rawValue)", isDirectory: true)
            let entrypoint = source.appendingPathComponent("bin/\(kind.rawValue)")
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: entrypoint.path)
            let hash = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
            hashes[kind] = hash
            _ = try writer.commit(
                sourceDirectory: source,
                receipt: DesktopManagedComponentReceipt(
                    kind: kind,
                    version: "1.0.0",
                    architecture: "arm64",
                    contentSHA256: hash
                ),
                runID: UUID().uuidString,
                healthProbe: { _ in true }
            )
        }
        let artifacts = kinds.map { kind -> DesktopComponentReleaseArtifactV2 in
            let dependencies: [DesktopComponentReleaseDependency]
            switch kind {
            case .hermesCore:
                dependencies = [.init(kind: .pythonRuntime, contentSHA256: hashes[.pythonRuntime]!)]
            case .connector:
                dependencies = includeBundledHermes
                    ? [
                        .init(kind: .hermesCore, contentSHA256: hashes[.hermesCore]!),
                        .init(kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!),
                    ]
                    : [.init(kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!)]
            default:
                dependencies = []
            }
            return DesktopComponentReleaseArtifactV2(
                kind: kind,
                version: "1.0.0",
                architecture: "arm64",
                installPhase: .bootstrap,
                requiredForBootstrap: true,
                reuseContract: .exactContent,
                fileName: "Hermes-Component-\(kind.rawValue)-1.0.0-arm64.tar.gz",
                entrypoint: "bin/\(kind.rawValue)",
                downloadURL: "https://downloads.example/desktop/components/\(kind.rawValue).tar.gz",
                sizeBytes: 1,
                sha256: String(repeating: "a", count: 64),
                contentSHA256: hashes[kind]!,
                dependencies: dependencies
            )
        }
        manifest = DesktopComponentReleaseManifestV2(
            releaseVersion: "0.5.0",
            channel: "stable",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-10-01T00:00:00Z",
            components: artifacts
        )
    }

    func remove() { try? FileManager.default.removeItem(at: base) }
}
