import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesRecoveryPointTests: XCTestCase {
    func testWorldWritableUvLockIsPreservedOnlyBehindPrivateDirectoryBoundary() throws {
        let f = try RecoveryFixture(); defer { f.remove() }
        let lock = f.home.appendingPathComponent("hermes-agent/venv/.lock")
        try Data().write(to: lock)
        try FileManager.default.setAttributes([.posixPermissions: 0o666], ofItemAtPath: lock.path)
        let backup = try f.capture(); try f.recovering(backup)
        let material = try f.operation.materializeRecoveryPoint(in: f.recovery)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: material.roots[0].staged.appendingPathComponent("hermes-agent/venv/.lock").path)[.posixPermissions] as? Int, 0o666)
        let other = try RecoveryFixture(); defer { other.remove() }
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: other.runtime.path)
        let exposed = other.runtime.appendingPathComponent("unsafe")
        try Data().write(to: exposed)
        try FileManager.default.setAttributes([.posixPermissions: 0o666], ofItemAtPath: exposed.path)
        XCTAssertThrowsError(try other.capture())
    }

    func testCapturesHomeAndExternalRuntimeThenMaterializesWithoutOverwritingCandidate() throws {
        let f = try RecoveryFixture(); defer { f.remove() }
        let backup = try f.capture()
        XCTAssertEqual(try f.capture(), backup, "retry after publication must be idempotent while sources are unchanged")
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: f.recovery.root.path)[.posixPermissions] as? Int, 0o700)
        let manifest = f.point.appendingPathComponent("manifest.json")
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: manifest.path)[.posixPermissions] as? Int, 0o600)
        // These stand in for post-upgrade writes; reconstruction must not change them.
        try Data("new user history".utf8).write(to: f.home.appendingPathComponent("state.db"))
        try Data("new runtime".utf8).write(to: f.runtime.appendingPathComponent("bin/python"))
        XCTAssertThrowsError(try f.capture(), "a retry cannot accept changed sources as the original point")
        try f.recovering(backup)
        let material = try f.operation.materializeRecoveryPoint(in: f.recovery)
        XCTAssertEqual(material.runID, f.operation.snapshot.runID)
        XCTAssertEqual(material.roots.map { $0.original.path }, [f.home.path, f.runtime.path])
        XCTAssertEqual(try String(contentsOf: material.roots[0].staged.appendingPathComponent("state.db")), "old history")
        XCTAssertEqual(try String(contentsOf: material.roots[1].staged.appendingPathComponent("bin/python")), "#!/bin/sh\nexit 0\n")
        XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("state.db")), "new user history")
        XCTAssertEqual(try String(contentsOf: f.runtime.appendingPathComponent("bin/python")), "new runtime")
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: material.roots[0].staged.appendingPathComponent("hermes-agent/venv/bin/python").path), f.runtime.appendingPathComponent("bin/python").path)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: material.roots[0].staged.appendingPathComponent(".env").path)[.posixPermissions] as? Int, 0o600)
        XCTAssertEqual(try f.recovery.identify(material.roots[0].staged).sha256, material.roots[0].sha256)
    }

    func testTamperedBackupOrManifestIsRefusedAndOriginalNewDataRemains() throws {
        for corruptManifest in [false, true] {
            let f = try RecoveryFixture(); defer { f.remove() }
            let backup = try f.capture(); try f.recovering(backup)
            let path = f.point.appendingPathComponent(corruptManifest ? "manifest.json" : "tree-0/state.db")
            try Data("corrupted".utf8).write(to: path)
            try Data("new data stays".utf8).write(to: f.home.appendingPathComponent("state.db"))
            XCTAssertThrowsError(try f.operation.materializeRecoveryPoint(in: f.recovery)) { error in
                XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired)
                let issue = (error as? DesktopHermesUpgradeError)?.issue
                XCTAssertEqual(issue?.code.rawValue, "HR-HERMESUPDATE-003")
                XCTAssertEqual(issue?.retryable, false)
                XCTAssertFalse(issue?.summaryChinese.isEmpty ?? true)
                XCTAssertFalse(issue?.summaryEnglish.isEmpty ?? true)
                XCTAssertFalse(issue?.sanitizedDiagnostic.contains(f.root.path) ?? true)
            }
            XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("state.db")), "new data stays")
        }
    }

    func testMissingExternalInterpreterOverlappingInputsAndUnsafeRootRefuse() throws {
        for kind in ["missing-runtime", "duplicate", "nested", "inside-home", "public-root", "linked-root"] {
            let f = try RecoveryFixture(); defer { f.remove() }
            var store = f.recovery
            var inputs = [f.runtime]
            switch kind {
            case "missing-runtime": inputs = []
            case "duplicate": inputs = [f.runtime, f.runtime]
            case "nested": inputs = [f.runtime, f.runtime.appendingPathComponent("bin")]
            case "inside-home": store = .init(root: f.home.appendingPathComponent("backup"))
            case "public-root":
                try FileManager.default.createDirectory(at: f.recovery.root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o755])
            default:
                try FileManager.default.createSymbolicLink(at: f.recovery.root, withDestinationURL: f.runtime)
            }
            XCTAssertThrowsError(try f.operation.captureRecoveryPoint(in: store, externalRuntimeRoots: inputs))
            XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("state.db")), "old history")
        }
    }

    func testUncapturedLinksAndSpecialFilesAreRefusedWhileCoveredLinksArePreserved() throws {
        let f = try RecoveryFixture(); defer { f.remove() }
        let outside = f.root.appendingPathComponent("outside")
        try Data("untouched".utf8).write(to: outside)
        let link = f.home.appendingPathComponent("outside-link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)
        XCTAssertThrowsError(try f.capture(), "a linked database or config outside coverage must not become a complete point")
        XCTAssertEqual(try String(contentsOf: outside), "untouched")
        try FileManager.default.removeItem(at: link)
        try FileManager.default.createSymbolicLink(atPath: link.path, withDestinationPath: ".env")
        let backup = try f.capture(); try f.recovering(backup)
        let material = try f.operation.materializeRecoveryPoint(in: f.recovery)
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: material.roots[0].staged.appendingPathComponent("outside-link").path), ".env")
        XCTAssertEqual(try String(contentsOf: outside), "untouched")
        let other = try RecoveryFixture(); defer { other.remove() }
        XCTAssertEqual(mkfifo(other.home.appendingPathComponent("pipe").path, 0o600), 0)
        XCTAssertThrowsError(try other.capture())
    }

    func testPhaseAndLiveJournalComparisonAreRequiredBeforeCopying() throws {
        let f = try RecoveryFixture(); defer { f.remove() }
        XCTAssertThrowsError(try f.operation.materializeRecoveryPoint(in: f.recovery))
        var foreign = try JSONSerialization.jsonObject(with: Data(contentsOf: f.store.journalURL)) as! [String: Any]
        foreign["runID"] = UUID().uuidString
        try JSONSerialization.data(withJSONObject: foreign).write(to: f.store.journalURL)
        XCTAssertThrowsError(try f.capture())
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.recovery.root.path))
    }

    func testRecordRecoveryDoesNotRequireReadingOrResolvingChangedOriginalPaths() throws {
        let f = try RecoveryFixture(); defer { f.remove() }
        let backup = try f.capture(); try f.recovering(backup)
        let candidate = f.root.appendingPathComponent("candidate-retained")
        try FileManager.default.moveItem(at: f.home, to: candidate)
        try FileManager.default.createSymbolicLink(at: f.home, withDestinationURL: f.runtime)
        let material = try f.operation.materializeRecoveryPoint(in: f.recovery)
        XCTAssertEqual(try String(contentsOf: material.roots[0].staged.appendingPathComponent("state.db")), "old history")
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: f.home.path), f.runtime.path)
        XCTAssertEqual(try String(contentsOf: candidate.appendingPathComponent("state.db")), "old history")
    }
}

private final class RecoveryFixture {
    let root: URL
    let home: URL
    let runtime: URL
    let store: DesktopHermesUpgradeJournalStore
    let recovery: DesktopHermesRecoveryPointStore
    let operation: DesktopHermesUpgradeOperation
    var point: URL { recovery.root.appendingPathComponent(operation.snapshot.runID.lowercased()) }
    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("hermes-recovery-\(UUID().uuidString)").resolvingSymlinksInPath()
        home = root.appendingPathComponent("user/.hermes")
        runtime = root.appendingPathComponent("user/runtime")
        store = .init(root: root.appendingPathComponent("state"))
        recovery = .init(root: root.appendingPathComponent("points"))
        for url in [home.appendingPathComponent("hermes-agent/venv/bin"), runtime.appendingPathComponent("bin")] {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        try Data("old history".utf8).write(to: home.appendingPathComponent("state.db"))
        try Data("synthetic-only".utf8).write(to: home.appendingPathComponent(".env"))
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: home.appendingPathComponent(".env").path)
        let python = runtime.appendingPathComponent("bin/python")
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: python)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: python.path)
        try FileManager.default.createSymbolicLink(at: home.appendingPathComponent("hermes-agent/venv/bin/python"), withDestinationURL: python)
        let initial = try DesktopHermesUpgradeJournal(runID: UUID(), target: .init(version: "0.22.5", commit: String(repeating: "b", count: 40)),
            sourceCommit: String(repeating: "a", count: 40), sourceIdentity: String(repeating: "c", count: 64),
            checkout: home.appendingPathComponent("hermes-agent"), hermesHome: home, services: [], originalChannel: "stable")
        operation = try .begin(store: store, journal: initial)
        try operation.advance(expected: initial, to: .backingUp)
    }
    func capture() throws -> DesktopHermesUpgradeBackup {
        try operation.captureRecoveryPoint(in: recovery, externalRuntimeRoots: [runtime])
    }
    func recovering(_ backup: DesktopHermesUpgradeBackup) throws {
        let update = try operation.advance(expected: operation.snapshot, to: .updating, backup: backup)
        try operation.advance(expected: update, to: .recovering)
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
