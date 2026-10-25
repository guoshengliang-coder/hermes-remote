import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopBundledConnectorIdentityTests: XCTestCase {
    func testCollectorFetchesSelectedReleaseAndReturnsVerifiedRuntimeWithWorkspaceRemoved() async throws {
        let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
        let process = runtimeProcess(f), downloads = IdentityDownloads()
        let collector = DesktopConnectorIdentityCollector(fetch: { url in
            XCTAssertEqual(url.absoluteString, "https://downloads.example/desktop/releases/1.2.3/Hermes-Desktop-1.2.3-arm64.manifest.json")
            return f.envelope
        }, download: { artifact, root in
            let destination = root.appendingPathComponent(artifact.fileName)
            try FileManager.default.copyItem(at: f.archive, to: destination)
            await downloads.record(destination)
            return destination
        }, runtime: { files, layout in
            try DesktopConnectorRuntimeIdentityInspector(runner: RuntimeJobRunner(layout: layout, pid: process.pid),
                readProcess: { _ in process }).inspect(observation: files, layout: layout)
        })
        let result = try await collector.collect(configuration: f.configuration, layout: f.layout)
        XCTAssertEqual(result.component, .init(name: "connector", version: "0.2.0", artifactSha256: f.archiveHash))
        let paths = await downloads.paths
        XCTAssertEqual(paths.count, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: paths[0].deletingLastPathComponent().path))
        XCTAssertEqual(try DesktopConnectorIdentityCollector.selectedVersion(f.layout), "1.2.3")
    }

    func testCollectorRejectsBadSignatureAndChangedSelectionBeforeArchiveDownload() async throws {
        for changeSelection in [false, true] {
            let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
            let collector = DesktopConnectorIdentityCollector(fetch: { _ in
                if changeSelection { try f.select("releases/9.9.9"); return f.envelope }
                var object = try JSONSerialization.jsonObject(with: f.envelope) as! [String: Any]
                object["signature"] = Data(repeating: 0, count: 64).bundledBase64URL
                return try JSONSerialization.data(withJSONObject: object)
            }, download: { _, _ in XCTFail("Unverified manifest must not download"); throw CocoaError(.fileReadUnknown) },
            runtime: { _, _ in XCTFail("Unverified manifest must not inspect"); throw CocoaError(.fileReadUnknown) })
            do { _ = try await collector.collect(configuration: f.configuration, layout: f.layout); XCTFail("Expected failure") }
            catch { assertCollectorIssue(error) }
        }
    }

    func testCollectorCleansWorkspaceAfterDownloadOrRuntimeFailure() async throws {
        for failDownload in [true, false] {
            let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
            let downloads = IdentityDownloads()
            let collector = DesktopConnectorIdentityCollector(fetch: { _ in f.envelope }, download: { artifact, root in
                let destination = root.appendingPathComponent(artifact.fileName)
                await downloads.record(destination)
                if failDownload { throw CocoaError(.fileReadUnknown) }
                try FileManager.default.copyItem(at: f.archive, to: destination)
                return destination
            }, runtime: { _, _ in throw CocoaError(.fileReadUnknown) })
            do { _ = try await collector.collect(configuration: f.configuration, layout: f.layout); XCTFail("Expected failure") }
            catch { assertCollectorIssue(error) }
            let paths = await downloads.paths
            XCTAssertEqual(paths.count, 1)
            XCTAssertFalse(FileManager.default.fileExists(atPath: paths[0].deletingLastPathComponent().path))
        }
    }

    func testCollectorRejectsSelectionChangeDuringArchiveDownload() async throws {
        let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
        let collector = DesktopConnectorIdentityCollector(fetch: { _ in f.envelope }, download: { artifact, root in
            let destination = root.appendingPathComponent(artifact.fileName)
            try FileManager.default.copyItem(at: f.archive, to: destination)
            try f.select("releases/9.9.9")
            return destination
        }, runtime: { _, _ in XCTFail("Changed installation cannot reach runtime inspection"); throw CocoaError(.fileReadUnknown) })
        do { _ = try await collector.collect(configuration: f.configuration, layout: f.layout); XCTFail("Expected failure") }
        catch { assertCollectorIssue(error) }
    }

    func testCollectorRejectsNoncanonicalSelectionWithoutNetwork() async throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        let collector = DesktopConnectorIdentityCollector(fetch: { _ in XCTFail("Unexpected network"); return Data() },
            download: { _, _ in throw CocoaError(.fileReadUnknown) }, runtime: { _, _ in throw CocoaError(.fileReadUnknown) })
        for target in ["releases/../1.2.3", "components/1.2.3", "/releases/1.2.3", "releases/1.2.3/extra"] {
            try f.select(target)
            do { _ = try await collector.collect(configuration: f.configuration, layout: f.layout); XCTFail("Expected failure") }
            catch { assertCollectorIssue(error) }
        }
    }

    func testCollectorPublicTransportHasNoAccountStateAndFiniteTimeouts() {
        let configuration = DesktopConnectorIdentityCollector.publicSessionConfiguration()
        XCTAssertNil(configuration.httpCookieStorage)
        XCTAssertFalse(configuration.httpShouldSetCookies)
        XCTAssertNil(configuration.urlCredentialStorage)
        XCTAssertNil(configuration.httpAdditionalHeaders)
        XCTAssertNil(configuration.urlCache)
        XCTAssertEqual(configuration.timeoutIntervalForRequest, 30)
        XCTAssertEqual(configuration.timeoutIntervalForResource, 120)
        XCTAssertFalse(configuration.waitsForConnectivity)
    }

    func testCollectorPinnedDiscoveryCannotSubstituteDifferentSelectedRelease() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        let url = URL(string: "https://downloads.example/desktop/releases/1.2.3/Hermes-Desktop-1.2.3-arm64.manifest.json")!
        let pinned = try DesktopManagedBootstrapConfiguration(manifestURL: url,
            artifactOrigin: f.configuration.artifactOrigin, channel: "internal", architecture: "arm64",
            signingKeys: f.configuration.signingKeys, runtimeContract: .serveV1)
        XCTAssertEqual(try DesktopConnectorIdentityCollector.manifestURL(pinned, version: "1.2.3"), url)
        XCTAssertThrowsError(try DesktopConnectorIdentityCollector.manifestURL(pinned, version: "9.9.9"))
        XCTAssertThrowsError(try DesktopConnectorIdentityCollector.manifestURL(f.configuration, version: "../other"))
    }

    func testCollectorRejectsValidManifestForDifferentSelectedVersion() async throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        try f.select("releases/9.9.9")
        let collector = DesktopConnectorIdentityCollector(fetch: { _ in f.envelope },
            download: { _, _ in XCTFail("Wrong signed release must not download"); throw CocoaError(.fileReadUnknown) },
            runtime: { _, _ in throw CocoaError(.fileReadUnknown) })
        do { _ = try await collector.collect(configuration: f.configuration, layout: f.layout); XCTFail("Expected failure") }
        catch { assertCollectorIssue(error) }
    }

    private func assertCollectorIssue(_ error: Error, file: StaticString = #filePath, line: UInt = #line) {
        guard let issue = error as? DesktopIssue else { return XCTFail("Expected structured issue", file: file, line: line) }
        XCTAssertEqual(issue.code.rawValue, "HR-HERMESCOMPAT-008", file: file, line: line)
        XCTAssertEqual(issue.summaryChinese, "无法确认升级推荐", file: file, line: line)
        XCTAssertEqual(issue.summaryEnglish, "Upgrade recommendation unavailable", file: file, line: line)
        XCTAssertTrue(issue.retryable, file: file, line: line)
        XCTAssertEqual(issue.recoveryAction, .retry, file: file, line: line)
        XCTAssertFalse(issue.sanitizedDiagnostic.contains("/"), file: file, line: line)
    }

    func testRuntimeMatchesSignedFilesExactJobAndStableProcess() throws {
        let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
        let observation = try f.inspect()
        let process = runtimeProcess(f)
        let runner = RuntimeJobRunner(layout: f.layout, pid: process.pid)
        let result = try DesktopConnectorRuntimeIdentityInspector(runner: runner, readProcess: { _ in process })
            .inspect(observation: observation, layout: f.layout)
        XCTAssertEqual(result.component, observation.component)
        XCTAssertEqual(result.contentSHA256, observation.contentSHA256)
        XCTAssertEqual(result.processID, process.pid)
        XCTAssertEqual(runner.calls, 2)
    }

    func testRuntimeRejectsWrongExecutableArgumentsUserAndLoaderInjection() throws {
        let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
        let observation = try f.inspect(), original = runtimeProcess(f)
        let variants = [
            ConnectorKernelIdentity(pid: original.pid, userID: getuid() + 1, startedAt: original.startedAt,
                executable: original.executable, arguments: original.arguments, injectedEnvironment: false),
            ConnectorKernelIdentity(pid: original.pid, userID: getuid(), startedAt: original.startedAt,
                executable: "/different/node", arguments: original.arguments, injectedEnvironment: false),
            ConnectorKernelIdentity(pid: original.pid, userID: getuid(), startedAt: original.startedAt,
                executable: original.executable, arguments: original.arguments + ["--extra"], injectedEnvironment: false),
            ConnectorKernelIdentity(pid: original.pid, userID: getuid(), startedAt: original.startedAt,
                executable: original.executable, arguments: original.arguments, injectedEnvironment: true)
        ]
        for process in variants {
            assertRuntimeInvalid { _ = try DesktopConnectorRuntimeIdentityInspector(runner: RuntimeJobRunner(layout: f.layout, pid: process.pid),
                readProcess: { _ in process }).inspect(observation: observation, layout: f.layout) }
        }
    }

    func testRuntimeRejectsPIDReuseAndSelectionChangesDuringInspection() throws {
        let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
        let observation = try f.inspect(), process = runtimeProcess(f)
        var reads = 0
        assertRuntimeInvalid {
            _ = try DesktopConnectorRuntimeIdentityInspector(runner: RuntimeJobRunner(layout: f.layout, pid: process.pid), readProcess: { _ in
                reads += 1
                return reads == 1 ? process : ConnectorKernelIdentity(pid: process.pid, userID: process.userID,
                    startedAt: process.startedAt.addingTimeInterval(1), executable: process.executable,
                    arguments: process.arguments, injectedEnvironment: false)
            }).inspect(observation: observation, layout: f.layout)
        }
        reads = 0
        assertRuntimeInvalid {
            _ = try DesktopConnectorRuntimeIdentityInspector(runner: RuntimeJobRunner(layout: f.layout, pid: process.pid), readProcess: { _ in
                reads += 1
                if reads == 2 { try? f.select("releases/9.9.9") }
                return process
            }).inspect(observation: observation, layout: f.layout)
        }
    }

    func testRuntimeRejectsChangedContentAndRestoredBytesWithOldMtime() throws {
        let f = try BundledIdentityFixture(runtime: true); defer { f.remove() }
        let observation = try f.inspect(), process = runtimeProcess(f)
        let script = f.installed.appendingPathComponent("app/connector/dist/index.js")
        let original = try Data(contentsOf: script)
        let attributes = try FileManager.default.attributesOfItem(atPath: script.path)
        try Data("changed".utf8).write(to: script)
        let inspector = DesktopConnectorRuntimeIdentityInspector(runner: RuntimeJobRunner(layout: f.layout, pid: process.pid), readProcess: { _ in process })
        assertRuntimeInvalid { _ = try inspector.inspect(observation: observation, layout: f.layout) }
        try original.write(to: script)
        try FileManager.default.setAttributes([.modificationDate: attributes[.modificationDate]!], ofItemAtPath: script.path)
        XCTAssertEqual(try DesktopManagedComponentContentHasher().identify(directory: f.installed).sha256, observation.contentSHA256)
        assertRuntimeInvalid { _ = try inspector.inspect(observation: observation, layout: f.layout) }
    }

    private func runtimeProcess(_ f: BundledIdentityFixture) -> ConnectorKernelIdentity {
        let node = f.installed.appendingPathComponent("runtime/node").path
        return .init(pid: 12345, userID: getuid(), startedAt: Date(), executable: node,
                     arguments: [node, f.installed.appendingPathComponent("app/connector/dist/index.js").path], injectedEnvironment: false)
    }
    private func assertRuntimeInvalid(_ operation: () throws -> Void, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try operation(), file: file, line: line) { error in
            guard let issue = error as? DesktopIssue else { return XCTFail("Expected structured issue", file: file, line: line) }
            XCTAssertEqual(issue.code.rawValue, "HR-HERMESCOMPAT-008", file: file, line: line)
            XCTAssertEqual(issue.summaryChinese, "无法确认升级推荐", file: file, line: line)
            XCTAssertEqual(issue.summaryEnglish, "Upgrade recommendation unavailable", file: file, line: line)
            XCTAssertTrue(issue.retryable, file: file, line: line)
            XCTAssertEqual(issue.recoveryAction, .retry, file: file, line: line)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("/"), file: file, line: line)
        }
    }
    func testOriginalSignedArchiveIdentifiesSelectedBundledConnectorWithoutChangingIt() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        let before = try DesktopManagedComponentContentHasher().identify(directory: f.installed)
        let result = try f.inspect()
        XCTAssertEqual(result.component, .init(name: "connector", version: "0.2.0", artifactSha256: f.archiveHash))
        XCTAssertEqual(result.releaseVersion, "1.2.3")
        XCTAssertEqual(result.architecture, "arm64")
        XCTAssertEqual(result.contentSHA256, before.sha256)
        XCTAssertNotEqual(result.contentSHA256, result.component.artifactSha256)
        XCTAssertEqual(try DesktopManagedComponentContentHasher().identify(directory: f.installed), before)
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: f.layout.currentRelease.path), "releases/1.2.3")
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testInstalledModificationWithSameVersionIsRejected() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        try Data("#!/bin/sh\nexit 1\n".utf8).write(to: f.installed.appendingPathComponent("bin/hermes-connector"))
        assertInvalid { try f.inspect() }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testExistingWorkspaceWithDirectoryURLHintCanVerifyReference() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        let workspace = URL(fileURLWithPath: f.workspace.path, isDirectory: true)
        XCTAssertEqual(try f.inspect(workspace: workspace).component.artifactSha256, f.archiveHash)
    }

    func testAddedFileAndSymlinkCannotMatchOriginalArchive() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        let extra = f.installed.appendingPathComponent("extra")
        try Data("extra".utf8).write(to: extra)
        assertInvalid { try f.inspect() }
        try FileManager.default.removeItem(at: extra)
        try FileManager.default.createSymbolicLink(at: extra, withDestinationURL: f.archive)
        assertInvalid { try f.inspect() }
    }

    func testModifiedArchiveCannotSupplyIdentityEvenWhenInstalledFilesAreIntact() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        var bytes = try Data(contentsOf: f.archive)
        bytes[bytes.count - 1] ^= 1
        try bytes.write(to: f.archive)
        assertInvalid { try f.inspect() }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testUnsignedOrAmbiguousEnvelopeIsRejectedBeforeWorkspaceUse() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: f.envelope) as? [String: Any])
        object["signature"] = Data(repeating: 0, count: 64).bundledBase64URL
        assertInvalid { try f.inspect(envelope: JSONSerialization.data(withJSONObject: object)) }
        let raw = try XCTUnwrap(String(data: f.envelope, encoding: .utf8))
        let ambiguous = Data(("{\"algorithm\":\"Ed25519\"," + raw.dropFirst()).utf8)
        assertInvalid { try f.inspect(envelope: ambiguous) }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testArchiveChangedBeforePrivateCopyIsReverified() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        let manager = SwitchingIdentityFileManager {
            var bytes = try Data(contentsOf: f.archive)
            bytes[bytes.count - 1] ^= 1
            try bytes.write(to: f.archive)
        }
        assertInvalid { try f.inspect(fileManager: manager) }
        XCTAssertTrue(manager.switched)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testSelectedReleaseMustMatchSignedReleaseBeforeAndAfterComparison() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        try f.select("releases/9.9.9")
        assertInvalid { try f.inspect() }
        try f.select("releases/1.2.3")
        let manager = SwitchingIdentityFileManager {
            try f.select("releases/9.9.9")
        }
        assertInvalid { try f.inspect(fileManager: manager) }
        XCTAssertTrue(manager.switched)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testWorkspaceCannotBeInstalledReleaseOrWritableByOtherUsers() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        assertInvalid { try f.inspect(workspace: f.installed) }
        try FileManager.default.setAttributes([.posixPermissions: 0o777], ofItemAtPath: f.workspace.path)
        assertInvalid { try f.inspect() }
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testCleanupFailureDoesNotReturnSuccessfulIdentity() throws {
        let f = try BundledIdentityFixture(); defer { f.remove() }
        assertInvalid { try f.inspect(fileManager: CleanupRefusingIdentityFileManager()) }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).count, 1)
    }

    private func assertInvalid(_ operation: () throws -> DesktopBundledConnectorObservation,
                               file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertThrowsError(try operation(), file: file, line: line) { error in
            guard let issue = error as? DesktopIssue else { return XCTFail("Expected structured issue", file: file, line: line) }
            XCTAssertEqual(issue.code.rawValue, "HR-HERMESCOMPAT-008", file: file, line: line)
            XCTAssertEqual(issue.summaryChinese, "无法确认升级推荐", file: file, line: line)
            XCTAssertEqual(issue.summaryEnglish, "Upgrade recommendation unavailable", file: file, line: line)
            XCTAssertEqual(issue.detailChinese, "无法确认可用的 Hermes 升级推荐，请刷新版本信息。", file: file, line: line)
            XCTAssertEqual(issue.detailEnglish, "A valid Hermes upgrade recommendation could not be confirmed. Refresh the version information.", file: file, line: line)
            XCTAssertTrue(issue.retryable, file: file, line: line)
            XCTAssertEqual(issue.recoveryAction, .retry, file: file, line: line)
            XCTAssertTrue(issue.technicalCause?.hasPrefix("bundled_connector_identity=") == true, file: file, line: line)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("/"), file: file, line: line)
        }
    }
}

private final class CleanupRefusingIdentityFileManager: FileManager, @unchecked Sendable {
    override func removeItem(at URL: URL) throws { throw CocoaError(.fileWriteNoPermission) }
}

private final class SwitchingIdentityFileManager: FileManager, @unchecked Sendable {
    private let switchRelease: () throws -> Void
    private(set) var switched = false
    init(switchRelease: @escaping () throws -> Void) { self.switchRelease = switchRelease; super.init() }
    override func createDirectory(at url: URL, withIntermediateDirectories createIntermediates: Bool,
                                  attributes: [FileAttributeKey: Any]? = nil) throws {
        if !switched { switched = true; try switchRelease() }
        try super.createDirectory(at: url, withIntermediateDirectories: createIntermediates, attributes: attributes)
    }
}

private final class BundledIdentityFixture {
    let root: URL
    let layout: DesktopManagedInstallLayout
    let installed: URL
    let workspace: URL
    let archive: URL
    let archiveHash: String
    let envelope: Data
    let verifier: DesktopReleaseManifestVerifier
    let configuration: DesktopManagedBootstrapConfiguration

    init(runtime: Bool = false) throws {
        root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("hermes-bundled-identity-\(UUID().uuidString)")
        layout = try DesktopManagedInstallLayout(root: root.appendingPathComponent("managed"),
                                                 launchAgentsRoot: root.appendingPathComponent("agents"))
        installed = try layout.release("1.2.3").appendingPathComponent("connector")
        workspace = root.appendingPathComponent("work")
        archive = root.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        for directory in [installed.appendingPathComponent("bin"), workspace] {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
        }
        let executable = installed.appendingPathComponent("bin/hermes-connector")
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: executable)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: executable.path)
        if runtime {
            for relative in ["runtime", "app/connector/dist"] {
                try FileManager.default.createDirectory(at: installed.appendingPathComponent(relative), withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700])
            }
            let node = installed.appendingPathComponent("runtime/node")
            try Data("synthetic node bytes; never executed".utf8).write(to: node)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: node.path)
            try Data("// synthetic script; never executed\n".utf8).write(to: installed.appendingPathComponent("app/connector/dist/index.js"))
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
        process.arguments = ["-czf", archive.path, "-C", installed.path, "."]
        process.environment = ["PATH": "/usr/bin:/bin", "COPYFILE_DISABLE": "1"]
        process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        try process.run(); process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw CocoaError(.fileWriteUnknown) }
        let bytes = try Data(contentsOf: archive)
        archiveHash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let manifest = DesktopReleaseManifest(releaseVersion: "1.2.3", channel: "internal", architecture: "arm64",
            minimumMacOS: "14.0", createdAt: "2026-09-01T00:00:00Z", expiresAt: "2026-09-20T00:00:00Z",
            artifacts: [
                .init(component: .hermesServer, version: "0.20.6", fileName: "Hermes-Server-0.20.6-arm64.tar.gz",
                      entrypoint: "bin/hermes-server", downloadURL: "https://downloads.example/desktop/releases/Hermes-Server-0.20.6-arm64.tar.gz",
                      sizeBytes: 1, sha256: String(repeating: "a", count: 64)),
                .init(component: .connector, version: "0.2.0", fileName: archive.lastPathComponent,
                      entrypoint: "bin/hermes-connector", downloadURL: "https://downloads.example/desktop/releases/\(archive.lastPathComponent)",
                      sizeBytes: Int64(bytes.count), sha256: archiveHash)
            ])
        let key = Curve25519.Signing.PrivateKey()
        configuration = try DesktopManagedBootstrapConfiguration(
            manifestURL: URL(string: "https://downloads.example/desktop/releases/index.json")!,
            artifactOrigin: URL(string: "https://downloads.example")!, channel: "internal", architecture: "arm64",
            signingKeyID: "test", signingPublicKey: key.publicKey.rawRepresentation, runtimeContract: .serveV1)
        let payload = try JSONEncoder().encode(manifest)
        envelope = try JSONSerialization.data(withJSONObject: ["payload": payload.bundledBase64URL,
            "keyId": "test", "algorithm": "Ed25519", "signature": key.signature(for: payload).bundledBase64URL])
        verifier = try DesktopReleaseManifestVerifier(expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal", expectedArchitecture: "arm64",
            currentMacOS: .init(majorVersion: 14, minorVersion: 0, patchVersion: 0),
            signingKeys: ["test": key.publicKey.rawRepresentation])
        try select("releases/1.2.3")
    }

    func select(_ target: String) throws {
        try? FileManager.default.removeItem(at: layout.currentRelease)
        try FileManager.default.createSymbolicLink(atPath: layout.currentRelease.path, withDestinationPath: target)
    }

    func inspect(envelope: Data? = nil, workspace: URL? = nil, fileManager: FileManager = .default) throws -> DesktopBundledConnectorObservation {
        try DesktopBundledConnectorIdentityInspector(verifier: verifier, fileManager: fileManager)
            .inspect(signedManifest: envelope ?? self.envelope, archive: archive, layout: layout,
                     workspaceRoot: workspace ?? self.workspace)
    }

    func remove() { try? FileManager.default.removeItem(at: root) }
}

private extension Data {
    var bundledBase64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

private final class RuntimeJobRunner: OutputCommandRunning {
    let layout: DesktopManagedInstallLayout
    let pid: Int32
    private(set) var calls = 0
    init(layout: DesktopManagedInstallLayout, pid: Int32) { self.layout = layout; self.pid = pid }
    func run(executable: URL, arguments: [String], maximumOutputBytes: Int) -> DesktopCommandOutput {
        calls += 1
        let target = "gui/\(getuid())/\(DesktopManagedInstallLayout.connectorLabel)"
        guard executable.path == "/bin/launchctl", arguments == ["print", target] else {
            return .init(status: 1, stdout: Data(), outputLimitExceeded: false)
        }
        let path = layout.currentRelease.appendingPathComponent("connector/bin/hermes-connector").path
        return .init(status: 0, stdout: Data("\(target) = {\n\tpid = \(pid)\n\targuments = {\n\t\t\(path)\n\t}\n}\n".utf8), outputLimitExceeded: false)
    }
}

private actor IdentityDownloads {
    private(set) var paths: [URL] = []
    func record(_ url: URL) { paths.append(url) }
}
