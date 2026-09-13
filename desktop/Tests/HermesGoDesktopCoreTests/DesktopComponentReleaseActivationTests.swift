import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleaseActivationTests: XCTestCase {
    func testPlanRevalidatesStoreAndLaunchAgentsPinRuntimeRoots() throws {
        let fixture = try ActivationFixture()
        defer { fixture.remove() }
        let planner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid()
        )

        let plan = try planner.plan(manifest: fixture.manifest) { _, root, entrypoint in
            FileManager.default.fileExists(atPath: root.path)
                && FileManager.default.isExecutableFile(atPath: entrypoint.path)
        }

        XCTAssertEqual(plan.releaseVersion, "0.4.0")
        XCTAssertEqual(plan.components.map(\.kind), [
            .connector, .hermesCore, .nodeRuntime, .pythonRuntime,
        ])
        let layout = try DesktopManagedInstallLayout(
            root: fixture.store,
            launchAgentsRoot: fixture.base.appendingPathComponent("agents")
        )
        let configuration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: fixture.base.appendingPathComponent("hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        let agents = try configuration.componentLaunchAgents(for: plan)
        let hermesEnvironment = try environment(agents.hermes.encodedPropertyList())
        let connectorEnvironment = try environment(agents.connector.encodedPropertyList())

        XCTAssertEqual(
            hermesEnvironment["HERMES_PYTHON_RUNTIME_ROOT"],
            plan.component(.pythonRuntime)?.root.path
        )
        XCTAssertEqual(
            connectorEnvironment["HERMES_NODE_RUNTIME_ROOT"],
            plan.component(.nodeRuntime)?.root.path
        )
        XCTAssertEqual(
            agents.hermes.hermesExecutable,
            plan.component(.hermesCore)?.entrypoint
        )
        XCTAssertEqual(
            agents.connector.connectorExecutable,
            plan.component(.connector)?.entrypoint
        )

        let installer = DesktopManagedInstaller(layout: layout)
        let hermesPlist = try installer.writeHermesLaunchAgent(
            agents.hermes, activationPlan: plan
        )
        let connectorPlist = try installer.writeLaunchAgent(
            agents.connector, activationPlan: plan
        )
        for plist in [hermesPlist, connectorPlist] {
            let attributes = try FileManager.default.attributesOfItem(atPath: plist.path)
            XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        }
    }

    func testMissingComponentAndFailedHealthProbeFailClosed() throws {
        let fixture = try ActivationFixture()
        defer { fixture.remove() }
        let planner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid()
        )
        let node = try XCTUnwrap(fixture.manifest.components.first(where: { $0.kind == .nodeRuntime }))
        try FileManager.default.removeItem(at: fixture.store.appendingPathComponent(
            "components/node_runtime/\(node.contentSHA256)"
        ))

        XCTAssertThrowsError(try planner.plan(manifest: fixture.manifest) { _, _, _ in true }) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleaseActivationError,
                .missingComponent(.nodeRuntime)
            )
        }

        let healthyFixture = try ActivationFixture()
        defer { healthyFixture.remove() }
        let healthyPlanner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: healthyFixture.store,
            currentUserID: Darwin.getuid()
        )
        XCTAssertThrowsError(try healthyPlanner.plan(manifest: healthyFixture.manifest) {
            kind, _, _ in kind != .hermesCore
        }) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleaseActivationError,
                .healthProbeFailed(.hermesCore)
            )
        }
    }

    func testUnexpectedBootstrapDependencyAndNonExecutableEntrypointFailClosed() throws {
        let fixture = try ActivationFixture()
        defer { fixture.remove() }
        let planner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid()
        )
        var components = fixture.manifest.components
        let connectorIndex = try XCTUnwrap(components.firstIndex(where: { $0.kind == .connector }))
        let connector = components[connectorIndex]
        components[connectorIndex] = fixture.artifact(
            kind: .connector,
            contentSHA256: connector.contentSHA256,
            dependencies: connector.dependencies.filter { $0.kind != .nodeRuntime }
        )
        let invalidManifest = fixture.manifest(with: components)
        XCTAssertThrowsError(try planner.plan(manifest: invalidManifest) { _, _, _ in true }) {
            XCTAssertEqual($0 as? DesktopComponentReleaseActivationError, .invalidManifest)
        }

        let browser = DesktopComponentReleaseArtifactV2(
            kind: .browserAutomation,
            version: "1.2.3",
            architecture: "arm64",
            installPhase: .bootstrap,
            requiredForBootstrap: true,
            reuseContract: .exactContent,
            fileName: "Hermes-Component-browser_automation-1.2.3-arm64.tar.gz",
            entrypoint: "bin/chromium",
            downloadURL: "https://downloads.example/desktop/components/Hermes-Component-browser_automation-1.2.3-arm64.tar.gz",
            sizeBytes: 1,
            sha256: String(repeating: "b", count: 64),
            contentSHA256: String(repeating: "c", count: 64),
            dependencies: []
        )
        XCTAssertThrowsError(try planner.plan(
            manifest: fixture.manifest(with: fixture.manifest.components + [browser]),
            healthProbe: { _, _, _ in true }
        )) {
            XCTAssertEqual($0 as? DesktopComponentReleaseActivationError, .invalidManifest)
        }

        let nonExecutable = try ActivationFixture(nonExecutableKind: .hermesCore)
        defer { nonExecutable.remove() }
        let nonExecutablePlanner = try DesktopComponentReleaseActivationPlanner(
            storeRoot: nonExecutable.store,
            currentUserID: Darwin.getuid()
        )
        XCTAssertThrowsError(try nonExecutablePlanner.plan(
            manifest: nonExecutable.manifest,
            healthProbe: { _, _, _ in true }
        )) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleaseActivationError,
                .invalidEntrypoint(.hermesCore)
            )
        }
    }

    func testLegacyLaunchAgentWriterRejectsComponentRuntimeInjection() throws {
        let fixture = try ActivationFixture()
        defer { fixture.remove() }
        let layout = try DesktopManagedInstallLayout(
            root: fixture.store,
            launchAgentsRoot: fixture.base.appendingPathComponent("agents")
        )
        let configuration = DesktopHermesServerLaunchAgent(
            hermesExecutable: layout.currentRelease.appendingPathComponent("hermes_server/bin/hermes-server"),
            hermesHome: fixture.base.appendingPathComponent("hermes-home"),
            runtimeContract: .serveV1,
            sessionTokenFile: layout.hermesSessionToken,
            standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
            standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log"),
            pythonRuntimeRoot: fixture.base.appendingPathComponent("unexpected-runtime")
        )

        XCTAssertThrowsError(try DesktopManagedInstaller(layout: layout).writeHermesLaunchAgent(
            configuration,
            manifest: legacyManifest()
        )) {
            XCTAssertEqual($0 as? DesktopManagedInstallError, .invalidInput)
        }
    }

    func testComponentWriterRejectsPlanFromDifferentManagedRoot() throws {
        let destination = try ActivationFixture()
        let foreign = try ActivationFixture()
        defer {
            destination.remove()
            foreign.remove()
        }
        let foreignPlan = try DesktopComponentReleaseActivationPlanner(
            storeRoot: foreign.store,
            currentUserID: Darwin.getuid()
        ).plan(manifest: foreign.manifest) { _, _, _ in true }
        let layout = try DesktopManagedInstallLayout(
            root: destination.store,
            launchAgentsRoot: destination.base.appendingPathComponent("agents")
        )
        let configuration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: destination.base.appendingPathComponent("hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        let agents = try configuration.componentLaunchAgents(for: foreignPlan)

        XCTAssertThrowsError(try DesktopManagedInstaller(layout: layout).writeHermesLaunchAgent(
            agents.hermes,
            activationPlan: foreignPlan
        )) {
            XCTAssertEqual($0 as? DesktopManagedInstallError, .invalidInput)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: layout.hermesLaunchAgent.path))
    }

    func testComponentWriterRehashesPlanBeforePersistingLaunchAgent() throws {
        let fixture = try ActivationFixture()
        defer { fixture.remove() }
        let plan = try DesktopComponentReleaseActivationPlanner(
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid()
        ).plan(manifest: fixture.manifest) { _, _, _ in true }
        let layout = try DesktopManagedInstallLayout(
            root: fixture.store,
            launchAgentsRoot: fixture.base.appendingPathComponent("agents")
        )
        let configuration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: fixture.base.appendingPathComponent("hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        let agents = try configuration.componentLaunchAgents(for: plan)
        let python = try XCTUnwrap(plan.component(.pythonRuntime))
        try Data("tampered after planning".utf8).write(to: python.entrypoint)

        XCTAssertThrowsError(try DesktopManagedInstaller(layout: layout).writeHermesLaunchAgent(
            agents.hermes,
            activationPlan: plan
        )) {
            XCTAssertEqual($0 as? DesktopManagedInstallError, .invalidInput)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: layout.hermesLaunchAgent.path))
    }

    private func environment(_ plist: Data) throws -> [String: String] {
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: plist, options: [], format: nil)
                as? [String: Any]
        )
        return try XCTUnwrap(object["EnvironmentVariables"] as? [String: String])
    }

    private func legacyManifest() -> DesktopReleaseManifest {
        DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            platform: "macos",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            artifacts: [
                DesktopReleaseArtifact(
                    component: .hermesServer,
                    version: "1.2.3",
                    fileName: "Hermes-Server-1.2.3-arm64.tar.gz",
                    entrypoint: "bin/hermes-server",
                    downloadURL: "https://downloads.example/Hermes-Server-1.2.3-arm64.tar.gz",
                    sizeBytes: 1,
                    sha256: String(repeating: "a", count: 64)
                ),
                DesktopReleaseArtifact(
                    component: .connector,
                    version: "1.2.3",
                    fileName: "Hermes-Connector-1.2.3-arm64.tar.gz",
                    entrypoint: "bin/hermes-connector",
                    downloadURL: "https://downloads.example/Hermes-Connector-1.2.3-arm64.tar.gz",
                    sizeBytes: 1,
                    sha256: String(repeating: "b", count: 64)
                ),
            ]
        )
    }
}

private final class ActivationFixture {
    let base: URL
    let store: URL
    let manifest: DesktopComponentReleaseManifestV2

    init(nonExecutableKind: DesktopManagedComponentKind? = nil) throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(
            "hermes-component-activation-\(UUID().uuidString)", isDirectory: true
        )
        store = base.appendingPathComponent("managed", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: false)
        let writer = try DesktopManagedComponentStoreWriter(
            root: store,
            currentUserID: Darwin.getuid()
        )
        let paths: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "bin/python3",
            .hermesCore: "bin/hermes",
            .nodeRuntime: "bin/node",
            .connector: "bin/hermes-connector",
        ]
        var hashes: [DesktopManagedComponentKind: String] = [:]
        for kind in [
            DesktopManagedComponentKind.pythonRuntime, .hermesCore, .nodeRuntime, .connector,
        ] {
            let source = base.appendingPathComponent("source-\(kind.rawValue)", isDirectory: true)
            let entrypoint = source.appendingPathComponent(paths[kind]!)
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes(
                [.posixPermissions: kind == nonExecutableKind ? 0o600 : 0o700],
                ofItemAtPath: entrypoint.path
            )
            let hash = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
            hashes[kind] = hash
            _ = try writer.commit(
                sourceDirectory: source,
                receipt: DesktopManagedComponentReceipt(
                    kind: kind,
                    version: "1.2.3",
                    architecture: "arm64",
                    contentSHA256: hash
                ),
                runID: UUID().uuidString,
                healthProbe: { _ in true }
            )
        }
        let python = Self.makeArtifact(
            kind: .pythonRuntime, contentSHA256: hashes[.pythonRuntime]!, dependencies: []
        )
        let hermes = Self.makeArtifact(
            kind: .hermesCore,
            contentSHA256: hashes[.hermesCore]!,
            dependencies: [.init(kind: .pythonRuntime, contentSHA256: hashes[.pythonRuntime]!)]
        )
        let node = Self.makeArtifact(
            kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!, dependencies: []
        )
        let connector = Self.makeArtifact(
            kind: .connector,
            contentSHA256: hashes[.connector]!,
            dependencies: [
                .init(kind: .hermesCore, contentSHA256: hashes[.hermesCore]!),
                .init(kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!),
            ]
        )
        manifest = DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.0",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [python, hermes, node, connector]
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
        with components: [DesktopComponentReleaseArtifactV2]
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

    func remove() {
        try? FileManager.default.removeItem(at: base)
    }

    private static func makeArtifact(
        kind: DesktopManagedComponentKind,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        let paths: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "bin/python3",
            .hermesCore: "bin/hermes",
            .nodeRuntime: "bin/node",
            .connector: "bin/hermes-connector",
        ]
        let fileName = "Hermes-Component-\(kind.rawValue)-1.2.3-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            installPhase: .bootstrap,
            requiredForBootstrap: true,
            reuseContract: .exactContent,
            fileName: fileName,
            entrypoint: paths[kind]!,
            downloadURL: "https://downloads.example/desktop/components/\(fileName)",
            sizeBytes: 1,
            sha256: String(repeating: "a", count: 64),
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
    }
}
