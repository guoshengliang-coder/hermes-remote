import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopAppIdentityTests: XCTestCase {
    func testMatchesFullBundleAndKeepsArchiveAndContentIdentitiesDistinct() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        let before = try DesktopManagedComponentContentHasher().identify(directory: f.installed)
        let result = try f.inspect()
        XCTAssertEqual(result.component, f.expected)
        XCTAssertEqual(result.buildNumber, "45")
        XCTAssertEqual(result.contentSHA256, before.sha256)
        XCTAssertNotEqual(result.component.artifactSha256, result.contentSHA256)
        XCTAssertEqual(try DesktopManagedComponentContentHasher().identify(directory: f.installed), before)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
        XCTAssertEqual(f.runner.calls.map { $0[0] }, ["attach", "detach"])
        XCTAssertTrue(f.runner.calls[0].contains("-readonly"))
        XCTAssertTrue(f.runner.calls[0].contains("-noautoopen"))
    }

    func testSameVersionModifiedFileAndAddedFileCannotMatch() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        let executable = f.installed.appendingPathComponent("Contents/MacOS/HermesGoDesktop")
        try Data("changed".utf8).write(to: executable)
        assertInvalid { try f.inspect() }
        try FileManager.default.removeItem(at: f.installed)
        try FileManager.default.copyItem(at: f.source, to: f.installed)
        try Data("extra".utf8).write(to: f.installed.appendingPathComponent("extra"))
        assertInvalid { try f.inspect() }
        XCTAssertEqual(f.runner.calls.map { $0[0] }, ["attach", "detach", "attach", "detach"])
    }

    func testArchiveMismatchAndSymlinkAreRejectedBeforeMounting() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        let expected = f.expected
        try Data("changed archive".utf8).write(to: f.archive)
        assertInvalid { try f.inspect(expected: expected) }
        let link = f.root.appendingPathComponent("link.dmg")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: f.archive)
        assertInvalid { try f.inspect(archive: link) }
        XCTAssertTrue(f.runner.calls.isEmpty)
    }

    func testInvalidComponentOrVersionCannotProduceObservation() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        for (name, version) in [("connector", "0.2.42"), ("desktop", "-1.2.42"), ("desktop", "0.2.43")] {
            assertInvalid { try f.inspect(expected: .init(name: name, version: version, artifactSha256: f.expected.artifactSha256)) }
        }
        XCTAssertTrue(f.runner.calls.isEmpty)
    }

    func testCopyTimeArchiveMutationIsReverifiedBeforeMounting() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        let manager = AppIdentityCopyHook {
            try Data("changed during copy".utf8).write(to: f.archive)
        }
        assertInvalid { try f.inspect(manager: manager) }
        XCTAssertTrue(f.runner.calls.isEmpty)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    func testInstalledMutationDuringMountIsRejectedAndDetached() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        f.runner.onAttach = {
            try Data("changed".utf8).write(to: f.installed.appendingPathComponent("Contents/MacOS/HermesGoDesktop"))
        }
        assertInvalid { try f.inspect() }
        XCTAssertEqual(f.runner.calls.map { $0[0] }, ["attach", "detach"])
    }

    func testWrongBundleIdentifierOrNonExecutableCannotSupplyDesktopIdentity() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        try f.writeInfo(f.installed, identifier: "example.other.app")
        assertInvalid { try f.inspect() }
        try f.writeInfo(f.installed)
        try FileManager.default.setAttributes([.posixPermissions: 0o600],
            ofItemAtPath: f.installed.appendingPathComponent("Contents/MacOS/HermesGoDesktop").path)
        assertInvalid { try f.inspect() }
        XCTAssertTrue(f.runner.calls.isEmpty)
    }

    func testBundleLinkAndWorkspaceInsideInstallationAreRejected() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        assertInvalid { try f.inspect(workspace: f.installed) }
        try FileManager.default.createSymbolicLink(at: f.installed.appendingPathComponent("link"), withDestinationURL: f.archive)
        assertInvalid { try f.inspect() }
        XCTAssertTrue(f.runner.calls.isEmpty)
    }

    func testMultipleAppsOrMountFailureCannotProduceObservation() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        f.runner.multipleApps = true
        assertInvalid { try f.inspect() }
        f.runner.attachStatus = 1
        assertInvalid { try f.inspect() }
        XCTAssertEqual(f.runner.calls.map { $0[0] }, ["attach", "detach", "attach", "detach"])
    }

    func testDetachFailureRetainsReferenceAndNeverDeletesMountedContents() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        f.runner.detachStatus = 1
        assertInvalid { try f.inspect() }
        let runs = try FileManager.default.contentsOfDirectory(at: f.workspace, includingPropertiesForKeys: nil)
        XCTAssertEqual(runs.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: runs[0].appendingPathComponent("mount/Hermes.app/Contents/Info.plist").path))
        XCTAssertEqual(f.runner.calls.map { $0[0] }, ["attach", "detach"])
    }

    func testCleanupFailureCannotReturnSuccessfulObservation() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        assertInvalid { try f.inspect(manager: AppIdentityCleanupFailure()) }
        XCTAssertEqual(f.runner.calls.map { $0[0] }, ["attach", "detach"])
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).count, 1)
    }

    func testRealReadOnlyDiskImageCanBeComparedAndUnmounted() throws {
        let f = try AppIdentityFixture(); defer { f.remove() }
        try FileManager.default.removeItem(at: f.archive)
        let result = SystemCommandRunner().run(executable: URL(fileURLWithPath: "/usr/bin/hdiutil"),
            arguments: ["create", "-srcfolder", f.source.deletingLastPathComponent().path,
                        "-format", "UDZO", "-volname", "HermesIdentityTest", f.archive.path])
        XCTAssertEqual(result.status, 0)
        guard result.status == 0 else { return }
        let before = try DesktopManagedComponentContentHasher().identify(directory: f.installed)
        let observation = try DesktopAppIdentityInspector().inspect(expected: f.expected, archive: f.archive,
            installedApp: f.installed, workspaceRoot: f.workspace)
        XCTAssertEqual(observation.contentSHA256, before.sha256)
        XCTAssertEqual(try DesktopManagedComponentContentHasher().identify(directory: f.installed), before)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: f.workspace.path).isEmpty)
    }

    private func assertInvalid(_ operation: () throws -> DesktopAppIdentityObservation,
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
            XCTAssertTrue(issue.technicalCause?.hasPrefix("desktop_app_identity=") == true, file: file, line: line)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("/"), file: file, line: line)
        }
    }
}

private final class AppIdentityFixture {
    let root: URL, source: URL, installed: URL, archive: URL, workspace: URL
    let runner: AppIdentityMountRunner
    var expected: HermesCompatibilityComponent {
        .init(name: "desktop", version: "0.2.42", artifactSha256:
            SHA256.hash(data: try! Data(contentsOf: archive)).map { String(format: "%02x", $0) }.joined())
    }
    init() throws {
        root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("hermes-app-identity-\(UUID().uuidString)", isDirectory: true)
        source = root.appendingPathComponent("source/Hermes.app", isDirectory: true)
        installed = root.appendingPathComponent("Installed.app", isDirectory: true)
        archive = root.appendingPathComponent("reference.dmg")
        workspace = root.appendingPathComponent("work", isDirectory: true)
        runner = AppIdentityMountRunner(source: source)
        for dir in [source.appendingPathComponent("Contents/MacOS"), workspace] {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        try writeInfo(source)
        let binary = source.appendingPathComponent("Contents/MacOS/HermesGoDesktop")
        try Data("#!/bin/sh\nexit 99\n".utf8).write(to: binary)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: binary.path)
        try FileManager.default.copyItem(at: source, to: installed)
        try Data("synthetic image bytes; fake mount in unit tests".utf8).write(to: archive)
    }
    func writeInfo(_ app: URL, identifier: String = "com.hermesgo.desktop") throws {
        let plist = ["CFBundleIdentifier": identifier, "CFBundleShortVersionString": "0.2.42",
                     "CFBundleVersion": "45", "CFBundleExecutable": "HermesGoDesktop"]
        try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
            .write(to: app.appendingPathComponent("Contents/Info.plist"))
    }
    func inspect(expected: HermesCompatibilityComponent? = nil, archive: URL? = nil,
                 workspace: URL? = nil, manager: FileManager = .default) throws -> DesktopAppIdentityObservation {
        try DesktopAppIdentityInspector(runner: runner, fileManager: manager).inspect(expected: expected ?? self.expected,
            archive: archive ?? self.archive, installedApp: installed, workspaceRoot: workspace ?? self.workspace)
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}

private final class AppIdentityMountRunner: CommandRunning {
    let source: URL
    var calls: [[String]] = [], attachStatus: Int32 = 0, detachStatus: Int32 = 0
    var multipleApps = false
    var onAttach: (() throws -> Void)?
    init(source: URL) { self.source = source }
    func run(executable: URL, arguments: [String]) -> CommandResult {
        calls.append(arguments)
        guard executable.path == "/usr/bin/hdiutil" else { return .init(status: 1) }
        if arguments[0] == "detach" { return .init(status: detachStatus, standardError: "private /Users/test/example") }
        guard arguments[0] == "attach", attachStatus == 0,
              let index = arguments.firstIndex(of: "-mountpoint") else { return .init(status: 1) }
        do {
            let mount = URL(fileURLWithPath: arguments[index + 1], isDirectory: true)
            try FileManager.default.copyItem(at: source, to: mount.appendingPathComponent("Hermes.app"))
            if multipleApps { try FileManager.default.copyItem(at: source, to: mount.appendingPathComponent("Other.app")) }
            try onAttach?()
            return .init(status: 0)
        } catch { return .init(status: 1) }
    }
}

private final class AppIdentityCopyHook: FileManager, @unchecked Sendable {
    private let hook: () throws -> Void
    init(hook: @escaping () throws -> Void) { self.hook = hook; super.init() }
    override func copyItem(at srcURL: URL, to dstURL: URL) throws {
        try hook()
        try super.copyItem(at: srcURL, to: dstURL)
    }
}

private final class AppIdentityCleanupFailure: FileManager, @unchecked Sendable {
    override func removeItem(at URL: URL) throws { throw CocoaError(.fileWriteNoPermission) }
}
