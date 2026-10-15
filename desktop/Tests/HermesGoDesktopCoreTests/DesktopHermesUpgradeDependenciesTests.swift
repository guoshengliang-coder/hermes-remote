import CryptoKit
import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeDependenciesTests: XCTestCase {
    func testExactBytesPermissionsPrivateManifestAndIdempotentCapture() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("bin/tool", data: Data([0, 1, 255]), mode: 0o755)
        try f.file("lib/data", data: Data("runtime-data".utf8), mode: 0o644)
        var calls = 0
        let receipt = try f.capture { calls += 1 }
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(try f.capture(), receipt)
        try f.store.verify(receipt)
        let tree = f.tree()
        XCTAssertEqual(try Data(contentsOf: tree.appendingPathComponent("bin/tool")), Data([0, 1, 255]))
        XCTAssertEqual(try f.mode(tree.appendingPathComponent("bin/tool")), 0o700)
        XCTAssertEqual(try f.mode(tree.appendingPathComponent("lib/data")), 0o600)
        XCTAssertEqual(try f.mode(f.manifest), 0o600)
        let manifest = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: f.manifest)) as? [String: Any])
        XCTAssertFalse(try XCTUnwrap(manifest["osBuild"] as? String).isEmpty)
        XCTAssertFalse(try XCTUnwrap(manifest["architecture"] as? String).isEmpty)
        XCTAssertEqual(try f.mode(f.source.appendingPathComponent("bin/tool")), 0o755)
        let report = String(decoding: try JSONEncoder().encode(receipt), as: UTF8.self)
        XCTAssertFalse(report.contains(f.root.path)); XCTAssertFalse(report.contains("runtime-data"))
    }
    func testDeletedOriginalCanBeExportedAndActualNativeToolStillRuns() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        let source = f.root.appendingPathComponent("fixture.c")
        try Data("#include <stdio.h>\nint main(void) { puts(\"DEPENDENCY_EXPORT_OK\"); return 0; }\n".utf8).write(to: source)
        try FileManager.default.createDirectory(at: f.source.appendingPathComponent("bin"), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o755])
        let tool = f.source.appendingPathComponent("bin/fixture")
        _ = try run(URL(fileURLWithPath: "/usr/bin/xcrun"), arguments: ["--sdk", "macosx", "clang", source.path, "-o", tool.path], home: f.root)
        let receipt = try f.capture()
        try FileManager.default.removeItem(at: f.source)
        try f.store.verify(receipt)
        try f.store.export(receipt, to: f.exported)
        XCTAssertEqual(try run(f.exported.appendingPathComponent("python/bin/fixture"), arguments: [], home: f.root), "DEPENDENCY_EXPORT_OK\n")
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.source.path))
    }
    func testStoppedWriterFailureBeforeCaptureHasNoArchiveEffects() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        XCTAssertThrowsError(try f.capture { throw DesktopHermesUpgradeError.preflightBlocked }) {
            XCTAssertEqual($0 as? DesktopHermesUpgradeError, .preflightBlocked)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.store.root.path))
    }
    func testMultipleRootsKeepTheirLabelsAndShareOneTotalBudget() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("data", data: Data("1234".utf8))
        let tools = f.root.appendingPathComponent("external-tools")
        try FileManager.default.createDirectory(at: tools, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        try Data("5678".utf8).write(to: tools.appendingPathComponent("data"))
        let roots = [DesktopHermesUpgradeDependencyStore.Root(label: "tools", directory: tools)] + f.roots
        let limited = DesktopHermesUpgradeDependencyStore(root: f.store.root, maximumEntries: 10, maximumBytes: 7)
        XCTAssertThrowsError(try limited.create(runID: f.runID, sourceIdentity: f.identity, hermesHome: f.home, roots: roots) {})
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.store.root.path))
        let receipt = try f.store.create(runID: f.runID, sourceIdentity: f.identity, hermesHome: f.home, roots: roots) {}
        try f.store.export(receipt, to: f.exported)
        XCTAssertEqual(try String(contentsOf: f.exported.appendingPathComponent("python/data")), "1234")
        XCTAssertEqual(try String(contentsOf: f.exported.appendingPathComponent("tools/data")), "5678")
        XCTAssertThrowsError(try f.capture())
    }
    func testSourceMutationAfterCopyNeverPublishesRecoveryMaterial() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("data", data: Data("before".utf8))
        var calls = 0
        XCTAssertThrowsError(try f.capture {
            calls += 1
            if calls == 2 { try f.file("data", data: Data("after!".utf8)) }
        })
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.final.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: f.store.root.path), [])
        XCTAssertEqual(try String(contentsOf: f.source.appendingPathComponent("data")), "after!")
    }
    func testSameBytesAndResetMtimeStillDetectSourceReplacement() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("data", data: Data("same".utf8))
        let path = f.source.appendingPathComponent("data")
        let date = try XCTUnwrap(FileManager.default.attributesOfItem(atPath: path.path)[.modificationDate] as? Date)
        var calls = 0
        XCTAssertThrowsError(try f.capture {
            calls += 1
            if calls == 2 {
                try FileManager.default.removeItem(at: path)
                try f.file("data", data: Data("same".utf8))
                try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: path.path)
            }
        })
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.final.path))
    }
    func testInternalRelativeLinkChainSurvivesSourceDeletion() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("lib/data", data: Data("bytes".utf8))
        try f.link("current", target: "lib")
        try f.link("active", target: "current/data")
        let receipt = try f.capture()
        try FileManager.default.removeItem(at: f.source)
        try f.store.export(receipt, to: f.exported)
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: f.exported.appendingPathComponent("python/current").path), "lib")
        XCTAssertEqual(try String(contentsOf: f.exported.appendingPathComponent("python/active")), "bytes")
    }
    func testExternalAbsoluteDanglingCyclicAndFileTraversalLinksAreRejected() throws {
        for (index, target) in ["../outside", "/usr/bin/python3", "missing", "link", "data/child"].enumerated() {
            print("DEPENDENCY_LINK_REJECTION_CASE=\(index)")
            let f = try DependencyFixture(); defer { f.remove() }
            try f.file("data", data: Data("not-a-directory".utf8))
            try f.link("link", target: target)
            XCTAssertThrowsError(try f.capture())
            XCTAssertFalse(FileManager.default.fileExists(atPath: f.store.root.path))
        }
    }
    func testUnsafeDirectoryRegularFileHardlinkAndFIFOAreRejectedWithoutReadingBodies() throws {
        for kind in 0..<4 {
            let f = try DependencyFixture(); defer { f.remove() }
            let data = f.source.appendingPathComponent("data")
            if kind == 0 { try FileManager.default.setAttributes([.posixPermissions: 0o777], ofItemAtPath: f.source.path) }
            if kind == 1 { try f.file("data", data: Data(), mode: 0o666) }
            if kind == 2 {
                try f.file("data", data: Data())
                try FileManager.default.linkItem(at: data, to: f.source.appendingPathComponent("hardlink"))
            }
            if kind == 3 { XCTAssertEqual(mkfifo(data.path, 0o600), 0) }
            XCTAssertThrowsError(try f.capture())
            XCTAssertFalse(FileManager.default.fileExists(atPath: f.store.root.path))
        }
    }
    func testDuplicateNestedAliasedHomeAndArchiveSourceRootsAreRejected() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try FileManager.default.createDirectory(at: f.source.appendingPathComponent("nested"), withIntermediateDirectories: true)
        let alias = f.root.appendingPathComponent("alias")
        try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: f.source)
        let sets: [[DesktopHermesUpgradeDependencyStore.Root]] = [[], [.init(label: "../bad", directory: f.source)],
            [.init(label: "python\n", directory: f.source)],
            [.init(label: "python", directory: f.source), .init(label: "python", directory: f.source)],
            [.init(label: "python", directory: f.source), .init(label: "other", directory: f.source.appendingPathComponent("nested"))],
            [.init(label: "python", directory: alias)], [.init(label: "python", directory: f.home)],
            [.init(label: "python", directory: f.root)], [.init(label: "python", directory: URL(fileURLWithPath: "/"))]]
        for roots in sets {
            XCTAssertThrowsError(try f.store.create(runID: f.runID, sourceIdentity: f.identity, hermesHome: f.home, roots: roots) {})
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.store.root.path))
    }
    func testEntryAndByteBudgetsAndDepthAreBounded() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("one", data: Data(repeating: 1, count: 4))
        try f.file("two", data: Data(repeating: 2, count: 4))
        for store in [DesktopHermesUpgradeDependencyStore(root: f.store.root, maximumEntries: 1, maximumBytes: 100),
                      DesktopHermesUpgradeDependencyStore(root: f.store.root, maximumEntries: 10, maximumBytes: 7)] {
            XCTAssertThrowsError(try store.create(runID: f.runID, sourceIdentity: f.identity, hermesHome: f.home, roots: f.roots) {})
        }
        var deep = f.source
        for _ in 0..<65 { deep.appendPathComponent("d") }
        try FileManager.default.createDirectory(at: deep, withIntermediateDirectories: true)
        XCTAssertThrowsError(try f.capture())
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.store.root.path))
    }
    func testTamperedArchiveBytesPermissionsAdditionalTreesAndManifestNeverExport() throws {
        for mutation in 0..<4 {
            let f = try DependencyFixture(); defer { f.remove() }
            try f.file("data", data: Data("original".utf8))
            let receipt = try f.capture()
            if mutation == 0 { try Data("tampered".utf8).write(to: f.tree().appendingPathComponent("data")) }
            if mutation == 1 { try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: f.tree().appendingPathComponent("data").path) }
            if mutation == 2 { try FileManager.default.createDirectory(at: f.final.appendingPathComponent("extra"), withIntermediateDirectories: true) }
            if mutation == 3 { try Data("{}".utf8).write(to: f.manifest) }
            XCTAssertThrowsError(try f.store.verify(receipt))
            XCTAssertThrowsError(try f.store.export(receipt, to: f.exported))
            XCTAssertFalse(FileManager.default.fileExists(atPath: f.exported.path))
        }
    }
    func testForeignRunSourceDigestAndChangedLiveSourceCannotReuseArchive() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("data", data: Data("before".utf8))
        let receipt = try f.capture()
        for wrong in [DesktopHermesUpgradeDependencyArchive(runID: UUID().uuidString, sourceIdentity: f.identity, manifestSHA256: receipt.manifestSHA256),
            .init(runID: f.runID, sourceIdentity: String(repeating: "b", count: 64), manifestSHA256: receipt.manifestSHA256),
            .init(runID: f.runID, sourceIdentity: f.identity, manifestSHA256: String(repeating: "c", count: 64))] {
            XCTAssertThrowsError(try f.store.verify(wrong))
        }
        try f.file("data", data: Data("after!".utf8))
        XCTAssertThrowsError(try f.capture())
        try f.store.verify(receipt)
    }
    func testExistingDestinationArchiveHomeAndLiveAliasAreNeverOverwritten() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("data", data: Data("bytes".utf8))
        let receipt = try f.capture()
        try FileManager.default.createDirectory(at: f.exported, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        for destination in [f.exported, f.home, f.home.appendingPathComponent("recovery"), f.store.root.appendingPathComponent("recovery"), f.source.appendingPathComponent("recovery")] {
            XCTAssertThrowsError(try f.store.export(receipt, to: destination))
        }
        let movedHome = f.root.appendingPathComponent("moved-home")
        try FileManager.default.moveItem(at: f.home, to: movedHome)
        try FileManager.default.createSymbolicLink(at: f.home, withDestinationURL: movedHome)
        XCTAssertThrowsError(try f.store.export(receipt, to: movedHome.appendingPathComponent("recovery")))
        XCTAssertFalse(FileManager.default.fileExists(atPath: movedHome.appendingPathComponent("recovery").path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: f.exported.path), [])
    }
    func testMalformedCanonicalManifestWithForgedHashIsStillRejected() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        let receipt = try f.capture()
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: f.manifest)) as? [String: Any])
        object["schema"] = 2
        let changed = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .fragmentsAllowed])
        try changed.write(to: f.manifest)
        let digest = SHA256.hash(data: changed).map { String(format: "%02x", $0) }.joined()
        XCTAssertThrowsError(try f.store.verify(.init(runID: receipt.runID, sourceIdentity: receipt.sourceIdentity, manifestSHA256: digest)))
    }
    func testDestinationAppearingAtPublicationIsRetainedAndStagingIsRemoved() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try f.file("data", data: Data("archive".utf8))
        let receipt = try f.capture()
        let exporter = DesktopHermesUpgradeDependencyStore(root: f.store.root, maximumEntries: 65_536, maximumBytes: 2 * 1024 * 1024 * 1024,
            beforePublish: { destination in
                try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            })
        XCTAssertThrowsError(try exporter.export(receipt, to: f.exported))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: f.exported.path), [])
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: f.root.path).contains { $0.hasSuffix(".pending") })
        try f.store.verify(receipt)
    }
    func testFilesystemFailuresAreMappedToRegisteredSanitizedUpgradeError() throws {
        let f = try DependencyFixture(); defer { f.remove() }
        try FileManager.default.removeItem(at: f.source)
        XCTAssertThrowsError(try f.capture()) { error in
            let failure = error as? DesktopHermesUpgradeError
            XCTAssertNotNil(failure)
            XCTAssertEqual(failure?.issue.code, .hermesUpdateRecoveryRequired)
            XCTAssertFalse(failure?.issue.displayChinese.contains(f.root.path) ?? true)
        }
    }
    func testOptInStandalonePythonSurvivesDeletionOfDisposableOriginal() throws {
        guard let selected = ProcessInfo.processInfo.environment["HG199_DEPENDENCY_PYTHON_ROOT"] else {
            throw XCTSkip("opt-in standalone Python archive rehearsal")
        }
        let original = URL(fileURLWithPath: selected).standardizedFileURL.resolvingSymlinksInPath()
        guard original.lastPathComponent.hasPrefix("cpython-") else { throw DesktopHermesUpgradeError.preflightBlocked }
        let f = try DependencyFixture(); defer { f.remove() }
        let seed = DesktopHermesUpgradeDependencyStore(root: f.root.appendingPathComponent("seed"))
        // Read the selected installed interpreter; never remove/execute it. All deletion/execution
        // below belongs to copies exported into this test's newly created private directory.
        let first = try seed.create(runID: UUID().uuidString, sourceIdentity: f.identity, hermesHome: f.home,
            roots: [.init(label: "python", directory: original)]) {}
        let disposable = f.root.appendingPathComponent("disposable")
        try seed.export(first, to: disposable)
        let second = try f.store.create(runID: f.runID, sourceIdentity: f.identity, hermesHome: f.home,
            roots: [.init(label: "python", directory: disposable.appendingPathComponent("python"))]) {}
        try FileManager.default.removeItem(at: disposable)
        try f.store.export(second, to: f.exported)
        let executable = f.exported.appendingPathComponent("python/bin/python3")
        let output = try run(executable, arguments: ["-I", "-S", "-B", "-c",
            "import ssl,sqlite3,hashlib,json; assert sqlite3.connect(':memory:').execute('select 42').fetchone()[0]==42; print('DEPENDENCY_RESTORE_OK')"], home: f.root)
        XCTAssertEqual(output, "DEPENDENCY_RESTORE_OK\n")
        XCTAssertTrue(FileManager.default.fileExists(atPath: original.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: disposable.path))
    }
    private func run(_ executable: URL, arguments: [String], home: URL) throws -> String {
        let child = Process(), output = Pipe()
        child.executableURL = executable; child.arguments = arguments; child.currentDirectoryURL = home
        child.environment = ["PATH": "/usr/bin:/bin", "HOME": home.path, "HERMES_HOME": home.appendingPathComponent("never-live").path]
        child.standardInput = FileHandle.nullDevice; child.standardOutput = output; child.standardError = FileHandle.nullDevice
        try child.run()
        let timeout = DispatchWorkItem { if child.isRunning { child.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 10, execute: timeout)
        let kill = DispatchWorkItem { if child.isRunning { Darwin.kill(child.processIdentifier, SIGKILL) } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 11, execute: kill)
        defer { timeout.cancel(); kill.cancel(); if child.isRunning { child.terminate(); child.waitUntilExit() } }
        let data = output.fileHandleForReading.readDataToEndOfFile(); child.waitUntilExit()
        XCTAssertEqual(child.terminationStatus, 0)
        return String(decoding: data, as: UTF8.self)
    }
}
private struct DependencyFixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    let runID = UUID().uuidString
    let identity = String(repeating: "a", count: 64)
    var source: URL { root.appendingPathComponent("external-python") }
    var home: URL { root.appendingPathComponent("home") }
    var exported: URL { root.appendingPathComponent("recovery") }
    var store: DesktopHermesUpgradeDependencyStore { .init(root: root.appendingPathComponent("archives")) }
    var roots: [DesktopHermesUpgradeDependencyStore.Root] { [.init(label: "python", directory: source)] }
    var final: URL { store.root.appendingPathComponent(runID) }
    var manifest: URL { final.appendingPathComponent("manifest.json") }
    func tree() -> URL { final.appendingPathComponent("python") }
    init() throws {
        for url in [root, source, home] { try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
    func capture(_ check: () throws -> Void = {}) throws -> DesktopHermesUpgradeDependencyArchive {
        try store.create(runID: runID, sourceIdentity: identity, hermesHome: home, roots: roots, verifyStoppedWriters: check)
    }
    func file(_ relative: String, data: Data, mode: Int = 0o600) throws {
        let url = source.appendingPathComponent(relative)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o755])
        try data.write(to: url); try FileManager.default.setAttributes([.posixPermissions: mode], ofItemAtPath: url.path)
    }
    func link(_ relative: String, target: String) throws {
        try FileManager.default.createSymbolicLink(atPath: source.appendingPathComponent(relative).path, withDestinationPath: target)
    }
    func mode(_ url: URL) throws -> Int { try XCTUnwrap(FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? NSNumber).intValue }
}
