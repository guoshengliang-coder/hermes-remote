import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopOptionalComponentRuntimeTests: XCTestCase {
    func testProjectsMultiplePythonComponentsIntoOneReadOnlyLazyTarget() throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        let speech = try fixture.managed(.speechRuntime, hash: "a")
        let document = try fixture.managed(.documentTools, hash: "b")
        let writer = try fixture.writer()

        let first = try writer.write(
            releaseVersion: "0.4.0",
            pythonRuntimeRoot: fixture.python,
            components: [speech, document]
        )
        let second = try writer.write(
            releaseVersion: "0.4.0",
            pythonRuntimeRoot: fixture.python,
            components: [document, speech]
        )

        XCTAssertEqual(first, second)
        let target = try XCTUnwrap(first.lazyInstallTarget)
        XCTAssertNil(first.browserExecutable)
        XCTAssertEqual(permissions(target), 0o500)
        XCTAssertEqual(
            try String(contentsOf: target.appendingPathComponent(
                DesktopOptionalComponentRuntimeWriter.abiFileName
            ), encoding: .utf8),
            "3.11:.cpython-311-darwin.so\n"
        )
        XCTAssertEqual(
            try String(contentsOf: target.appendingPathComponent(
                DesktopOptionalComponentRuntimeWriter.pathFileName
            ), encoding: .utf8),
            [
                fixture.sitePackages(.documentTools, hash: "b").path,
                fixture.sitePackages(.speechRuntime, hash: "a").path,
                "",
            ].joined(separator: "\n")
        )
        XCTAssertEqual(permissions(target.appendingPathComponent(
            DesktopOptionalComponentRuntimeWriter.pathFileName
        )), 0o400)
    }

    func testExternalBrowserUsesExistingHermesEnvironmentWithoutPythonProjection() throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        let browser = fixture.root.appendingPathComponent("Chromium")
        try Data("browser".utf8).write(to: browser)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: browser.path)
        let component = DesktopResolvedOnDemandComponent(
            kind: .browserAutomation,
            location: .external(executable: browser)
        )

        let result = try fixture.writer().write(
            releaseVersion: "0.4.0",
            components: [component]
        )

        XCTAssertNil(result.lazyInstallTarget)
        XCTAssertEqual(result.browserExecutable, browser)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.projection.path))
    }

    func testRejectsExternalPythonDuplicateKindsAndManagedRootSymlinks() throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        let external = fixture.root.appendingPathComponent("external")
        try Data("tool".utf8).write(to: external)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: external.path)
        let invalidPython = DesktopResolvedOnDemandComponent(
            kind: .speechRuntime, location: .external(executable: external)
        )
        XCTAssertThrowsError(try fixture.writer().write(
            releaseVersion: "0.4.0", components: [invalidPython]
        )) { error in
            XCTAssertEqual(error as? DesktopOptionalComponentRuntimeError, .invalidComponent)
        }

        let speech = try fixture.managed(.speechRuntime, hash: "c")
        XCTAssertThrowsError(try fixture.writer().write(
            releaseVersion: "0.4.0", components: [speech, speech]
        )) { error in
            XCTAssertEqual(error as? DesktopOptionalComponentRuntimeError, .duplicateComponent)
        }

        let linkedRoot = fixture.store.appendingPathComponent(
            "components/document_tools/\(String(repeating: "d", count: 64))/content"
        )
        try FileManager.default.createDirectory(
            at: linkedRoot.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try FileManager.default.createSymbolicLink(
            at: linkedRoot,
            withDestinationURL: fixture.managedRoot(.speechRuntime, hash: "c")
        )
        let linked = DesktopResolvedOnDemandComponent(
            kind: .documentTools,
            location: .managed(root: linkedRoot, entrypoint: linkedRoot.appendingPathComponent("bin/health-check"))
        )
        XCTAssertThrowsError(try fixture.writer().write(
            releaseVersion: "0.4.0", components: [linked]
        )) { error in
            XCTAssertEqual(error as? DesktopOptionalComponentRuntimeError, .invalidComponent)
        }
    }

    func testHermesLaunchAgentCarriesOnlyValidatedOptionalRuntimePaths() throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        let lazy = fixture.root.appendingPathComponent("lazy")
        let browser = fixture.root.appendingPathComponent("browser")
        let configuration = DesktopHermesServerLaunchAgent(
            hermesExecutable: fixture.root.appendingPathComponent("bin/hermes"),
            hermesHome: fixture.root.appendingPathComponent("home"),
            runtimeContract: .serveV1,
            sessionTokenFile: fixture.root.appendingPathComponent("token"),
            standardOutput: fixture.root.appendingPathComponent("out.log"),
            standardError: fixture.root.appendingPathComponent("err.log"),
            optionalRuntime: DesktopOptionalComponentRuntimeEnvironment(
                lazyInstallTarget: lazy, browserExecutable: browser
            )
        )

        let decoded = try PropertyListSerialization.propertyList(
            from: configuration.encodedPropertyList(), options: [], format: nil
        )
        let plist = try XCTUnwrap(decoded as? [String: Any])
        let environment = try XCTUnwrap(plist["EnvironmentVariables"] as? [String: String])
        XCTAssertEqual(environment["HERMES_LAZY_INSTALL_TARGET"], lazy.path)
        XCTAssertEqual(environment["AGENT_BROWSER_EXECUTABLE_PATH"], browser.path)
    }

    func testRejectsFailedABIProbeAndTamperedExistingProjection() throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        let speech = try fixture.managed(.speechRuntime, hash: "f")

        let failingWriter = try fixture.writer(runner: ABIProbeRunner(status: 1))
        XCTAssertThrowsError(try failingWriter.write(
            releaseVersion: "0.4.0",
            pythonRuntimeRoot: fixture.python,
            components: [speech]
        )) { error in
            XCTAssertEqual(error as? DesktopOptionalComponentRuntimeError, .invalidComponent)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.projection.path))

        let writer = try fixture.writer()
        let environment = try writer.write(
            releaseVersion: "0.4.0",
            pythonRuntimeRoot: fixture.python,
            components: [speech]
        )
        let target = try XCTUnwrap(environment.lazyInstallTarget)
        let pathFile = target.appendingPathComponent(
            DesktopOptionalComponentRuntimeWriter.pathFileName
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: pathFile.path)
        try Data("/tmp/untrusted\n".utf8).write(to: pathFile)

        XCTAssertThrowsError(try writer.write(
            releaseVersion: "0.4.0",
            pythonRuntimeRoot: fixture.python,
            components: [speech]
        )) { error in
            XCTAssertEqual(error as? DesktopOptionalComponentRuntimeError, .unsafeFilesystemObject)
        }
    }

    private func permissions(_ url: URL) -> Int {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.posixPermissions] as? NSNumber)?.intValue ?? -1
    }
}

private final class Fixture {
    let root: URL
    let store: URL
    let projection: URL
    let python: URL

    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        store = root.appendingPathComponent("store")
        projection = root.appendingPathComponent("runtime-projections")
        python = store.appendingPathComponent(
            "components/python_runtime/\(String(repeating: "e", count: 64))/content"
        )
        try FileManager.default.createDirectory(
            at: store, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let pythonExecutable = python.appendingPathComponent("bin/python3")
        try FileManager.default.createDirectory(
            at: pythonExecutable.deletingLastPathComponent(), withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try Data("python".utf8).write(to: pythonExecutable)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o500], ofItemAtPath: pythonExecutable.path
        )
    }

    func cleanup() {
        try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: projection.path)
        if let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) {
            for case let url as URL in enumerator {
                try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.path)
            }
        }
        try? FileManager.default.removeItem(at: root)
    }

    func writer(runner: ABIProbeRunner = ABIProbeRunner()) throws
        -> DesktopOptionalComponentRuntimeWriter {
        try DesktopOptionalComponentRuntimeWriter(
            storeRoot: store,
            projectionRoot: projection,
            currentUserID: getuid(),
            runner: runner
        )
    }

    func managed(_ kind: DesktopManagedComponentKind, hash: Character) throws
        -> DesktopResolvedOnDemandComponent {
        let root = managedRoot(kind, hash: hash)
        let entrypoint = root.appendingPathComponent("bin/health-check")
        try FileManager.default.createDirectory(
            at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try FileManager.default.createDirectory(
            at: root.appendingPathComponent("site-packages"), withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: entrypoint.path)
        return DesktopResolvedOnDemandComponent(
            kind: kind, location: .managed(root: root, entrypoint: entrypoint)
        )
    }

    func managedRoot(_ kind: DesktopManagedComponentKind, hash: Character) -> URL {
        store.appendingPathComponent(
            "components/\(kind.rawValue)/\(String(repeating: String(hash), count: 64))/content"
        )
    }

    func sitePackages(_ kind: DesktopManagedComponentKind, hash: Character) -> URL {
        managedRoot(kind, hash: hash).appendingPathComponent("site-packages")
    }
}

private struct ABIProbeRunner: OutputCommandRunning {
    let status: Int32

    init(status: Int32 = 0) {
        self.status = status
    }

    func run(
        executable: URL,
        arguments: [String],
        maximumOutputBytes: Int
    ) -> DesktopCommandOutput {
        let valid = executable.lastPathComponent == "python3"
            && arguments.count == 3 && arguments[0] == "-s" && arguments[1] == "-c"
            && maximumOutputBytes == 512
        return DesktopCommandOutput(
            status: valid ? status : 1,
            stdout: Data("3.11:.cpython-311-darwin.so\n".utf8),
            outputLimitExceeded: false
        )
    }
}
