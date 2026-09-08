import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedInstallationTests: XCTestCase {
    func testStagesTwoComponentsAndAtomicallyActivatesThenRollsBack() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let managed = testRoot.appendingPathComponent("managed", isDirectory: true)
        let agents = testRoot.appendingPathComponent("agents", isDirectory: true)
        let layout = try DesktopManagedInstallLayout(root: managed, launchAgentsRoot: agents)
        let installer = DesktopManagedInstaller(layout: layout)
        let oldRelease = try layout.release("1.0.0")
        try FileManager.default.createDirectory(at: oldRelease, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(
            atPath: layout.currentRelease.path,
            withDestinationPath: "releases/1.0.0"
        )
        let sources = try componentSources(at: testRoot)

        let installed = try installer.stageRelease(
            manifest: manifest(),
            runID: runID,
            sources: sources
        )
        XCTAssertEqual(installed, try layout.release("1.2.3"))
        let activation = try installer.activate(releaseVersion: "1.2.3", runID: runID)
        XCTAssertEqual(activation.previousRelativeTarget, "releases/1.0.0")
        XCTAssertEqual(
            try FileManager.default.destinationOfSymbolicLink(atPath: layout.currentRelease.path),
            "releases/1.2.3"
        )
        XCTAssertThrowsError(try installer.stageRelease(
            manifest: manifest(),
            runID: "10000000-0000-4000-8000-000000000002",
            sources: sources
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .releaseAlreadyExists)
        }

        try installer.rollback(activation, runID: runID)
        XCTAssertEqual(
            try FileManager.default.destinationOfSymbolicLink(atPath: layout.currentRelease.path),
            "releases/1.0.0"
        )

        let retryRunID = "10000000-0000-4000-8000-000000000003"
        XCTAssertEqual(
            try installer.stageRelease(
                manifest: manifest(),
                runID: retryRunID,
                sources: sources
            ),
            try layout.release("1.2.3")
        )
        let marker = try Data(contentsOf: installed.appendingPathComponent(
            ".hermes-go-managed-release.json"
        ))
        XCTAssertTrue(String(decoding: marker, as: UTF8.self).contains(retryRunID))
    }

    func testUnknownOrActiveReleaseCannotBeReplaced() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let layout = try DesktopManagedInstallLayout(
            root: testRoot.appendingPathComponent("managed"),
            launchAgentsRoot: testRoot.appendingPathComponent("agents")
        )
        let installer = DesktopManagedInstaller(layout: layout)
        let release = try layout.release("1.2.3")
        try FileManager.default.createDirectory(at: release, withIntermediateDirectories: true)
        try Data("unowned state".utf8).write(to: release.appendingPathComponent("keep.txt"))

        XCTAssertThrowsError(try installer.stageRelease(
            manifest: manifest(),
            runID: runID,
            sources: componentSources(at: testRoot)
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .releaseAlreadyExists)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: release.appendingPathComponent("keep.txt").path))
    }

    func testUnsafeExtractedSymlinkAndMissingExecutableFailBeforeReleaseAppears() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let layout = try DesktopManagedInstallLayout(
            root: testRoot.appendingPathComponent("managed"),
            launchAgentsRoot: testRoot.appendingPathComponent("agents")
        )
        let installer = DesktopManagedInstaller(layout: layout)
        let sources = try componentSources(at: testRoot)
        let unsafe = sources[0].directory.appendingPathComponent("escape")
        try FileManager.default.createSymbolicLink(at: unsafe, withDestinationURL: URL(fileURLWithPath: "/tmp"))

        XCTAssertThrowsError(try installer.stageRelease(
            manifest: manifest(), runID: runID, sources: sources
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .unsafeFilesystemObject)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: (try! layout.release("1.2.3")).path))

        try FileManager.default.removeItem(at: unsafe)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600],
            ofItemAtPath: sources[0].directory.appendingPathComponent("bin/hermes-server").path
        )
        XCTAssertThrowsError(try installer.stageRelease(
            manifest: manifest(), runID: runID, sources: sources
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .missingEntrypoint)
        }
    }

    func testCredentialAndLaunchAgentArePrivateAndContainNoBearerOrHermesSecret() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let layout = try DesktopManagedInstallLayout(
            root: testRoot.appendingPathComponent("managed"),
            launchAgentsRoot: testRoot.appendingPathComponent("agents")
        )
        let installer = DesktopManagedInstaller(layout: layout)
        let credential = AccountConnectorCredentialPayload(data: Data("{\"privateKey\":\"test-only\"}".utf8))
        let credentialURL = try installer.writeCredential(credential)
        let configuration = DesktopAccountConnectorLaunchAgent(
            connectorExecutable: testRoot.appendingPathComponent("managed/current/connector/bin/hermes-connector"),
            credentialFile: credentialURL,
            gatewayURL: URL(string: "wss://gateway.example/v2/connect")!,
            hermesBaseURL: URL(string: "http://127.0.0.1:9119")!,
            standardOutput: testRoot.appendingPathComponent("managed/logs/connector.log"),
            standardError: testRoot.appendingPathComponent("managed/logs/connector.error.log")
        )
        let plist = try configuration.encodedPropertyList()
        let plistURL = try installer.writeLaunchAgent(configuration, manifest: manifest())

        XCTAssertEqual(try Data(contentsOf: credentialURL), credential.data)
        for url in [credentialURL, plistURL] {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        }
        let decoded = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: plist, options: [], format: nil) as? [String: Any]
        )
        let environment = try XCTUnwrap(decoded["EnvironmentVariables"] as? [String: String])
        XCTAssertEqual(environment["CONNECTOR_MODE"], "account")
        XCTAssertEqual(environment["GATEWAY_URL"], "wss://gateway.example/v2/connect")
        XCTAssertNil(environment["CONNECTOR_TOKEN"])
        XCTAssertNil(environment["HERMES_AUTH_PASSWORD"])
    }

    func testHermesLaunchAgentUsesOnlySignedEntrypointAndFrozenLoopbackContract() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let layout = try DesktopManagedInstallLayout(
            root: testRoot.appendingPathComponent("managed"),
            launchAgentsRoot: testRoot.appendingPathComponent("agents")
        )
        let configuration = DesktopHermesServerLaunchAgent(
            hermesExecutable: testRoot.appendingPathComponent(
                "managed/current/hermes_server/bin/hermes-server"
            ),
            hermesHome: testRoot.appendingPathComponent("hermes-home"),
            runtimeContract: .serveV1,
            standardOutput: testRoot.appendingPathComponent("managed/logs/hermes-server.log"),
            standardError: testRoot.appendingPathComponent("managed/logs/hermes-server.error.log")
        )

        let plistURL = try DesktopManagedInstaller(layout: layout).writeHermesLaunchAgent(
            configuration,
            manifest: manifest()
        )
        let attributes = try FileManager.default.attributesOfItem(atPath: plistURL.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        let decoded = try XCTUnwrap(
            PropertyListSerialization.propertyList(
                from: Data(contentsOf: plistURL),
                options: [],
                format: nil
            ) as? [String: Any]
        )
        XCTAssertEqual(decoded["Label"] as? String, DesktopManagedInstallLayout.hermesLabel)
        XCTAssertEqual(decoded["ProgramArguments"] as? [String], [
            configuration.hermesExecutable.path,
            "serve", "--host", "127.0.0.1", "--port", "9119",
        ])
        XCTAssertEqual(
            decoded["EnvironmentVariables"] as? [String: String],
            ["HERMES_HOME": testRoot.appendingPathComponent("hermes-home").path]
        )
        XCTAssertNil(decoded["KeepAlive"])
    }

    func testActivationRefusesNonSymlinkCurrentAndCannotOverwriteUnknownState() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let layout = try DesktopManagedInstallLayout(
            root: testRoot.appendingPathComponent("managed"),
            launchAgentsRoot: testRoot.appendingPathComponent("agents")
        )
        let release = try layout.release("1.2.3")
        try FileManager.default.createDirectory(at: release, withIntermediateDirectories: true)
        try Data("owned-by-someone-else".utf8).write(to: layout.currentRelease)

        XCTAssertThrowsError(try DesktopManagedInstaller(layout: layout).activate(
            releaseVersion: "1.2.3", runID: runID
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .unsafeFilesystemObject)
        }
        XCTAssertEqual(try String(contentsOf: layout.currentRelease), "owned-by-someone-else")
    }

    func testLaunchAgentCannotSubstituteAnExecutableOutsideTheSignedRelease() throws {
        let testRoot = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: testRoot) }
        let layout = try DesktopManagedInstallLayout(
            root: testRoot.appendingPathComponent("managed"),
            launchAgentsRoot: testRoot.appendingPathComponent("agents")
        )
        let configuration = DesktopAccountConnectorLaunchAgent(
            connectorExecutable: URL(fileURLWithPath: "/bin/sh"),
            credentialFile: layout.connectorCredential,
            gatewayURL: URL(string: "wss://gateway.example/v2/connect")!,
            hermesBaseURL: URL(string: "http://127.0.0.1:9119")!,
            standardOutput: layout.logsRoot.appendingPathComponent("connector.log"),
            standardError: layout.logsRoot.appendingPathComponent("connector.error.log")
        )

        XCTAssertThrowsError(try DesktopManagedInstaller(layout: layout).writeLaunchAgent(
            configuration,
            manifest: manifest()
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .invalidInput)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: layout.connectorLaunchAgent.path))

        let hermesConfiguration = DesktopHermesServerLaunchAgent(
            hermesExecutable: URL(fileURLWithPath: "/bin/sh"),
            hermesHome: testRoot.appendingPathComponent("hermes-home"),
            runtimeContract: .serveV1,
            standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
            standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log")
        )
        XCTAssertThrowsError(try DesktopManagedInstaller(layout: layout).writeHermesLaunchAgent(
            hermesConfiguration,
            manifest: manifest()
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .invalidInput)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: layout.hermesLaunchAgent.path))
    }

    private var runID: String { "10000000-0000-4000-8000-000000000001" }

    private func componentSources(at root: URL) throws -> [DesktopManagedReleaseSource] {
        let hermes = root.appendingPathComponent("source-hermes", isDirectory: true)
        let connector = root.appendingPathComponent("source-connector", isDirectory: true)
        for (directory, executable) in [(hermes, "hermes-server"), (connector, "hermes-connector")] {
            let bin = directory.appendingPathComponent("bin", isDirectory: true)
            try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
            let entrypoint = bin.appendingPathComponent(executable)
            try Data("test executable".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: entrypoint.path)
        }
        return [
            DesktopManagedReleaseSource(component: .hermesServer, directory: hermes),
            DesktopManagedReleaseSource(component: .connector, directory: connector),
        ]
    }

    private func manifest() -> DesktopReleaseManifest {
        DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            artifacts: [
                DesktopReleaseArtifact(
                    component: .hermesServer,
                    version: "0.20.6",
                    fileName: "Hermes-Server-0.20.6-arm64.tar.gz",
                    entrypoint: "bin/hermes-server",
                    downloadURL: "https://downloads.example/Hermes-Server-0.20.6-arm64.tar.gz",
                    sizeBytes: 1,
                    sha256: String(repeating: "a", count: 64)
                ),
                DesktopReleaseArtifact(
                    component: .connector,
                    version: "0.2.0",
                    fileName: "Hermes-Connector-0.2.0-arm64.tar.gz",
                    entrypoint: "bin/hermes-connector",
                    downloadURL: "https://downloads.example/Hermes-Connector-0.2.0-arm64.tar.gz",
                    sizeBytes: 1,
                    sha256: String(repeating: "b", count: 64)
                ),
            ]
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-managed-install-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
    }
}
