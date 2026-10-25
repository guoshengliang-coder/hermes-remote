import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopBundledConnectorIdentityTests: XCTestCase {
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

    init() throws {
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
