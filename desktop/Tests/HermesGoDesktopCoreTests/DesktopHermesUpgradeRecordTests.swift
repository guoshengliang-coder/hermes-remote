import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeRecordTests: XCTestCase {
    func testAbsentRecordDoesNotCreateDirectoriesAndIsDifferentFromUnchecked() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        XCTAssertEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .absent)
        XCTAssertNotEqual(DesktopHermesUpgradeRecord.unchecked, .absent)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.root.path))
    }

    func testAllPhasesAreHistoricalIntentAndNeverExecutionPermission() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        for phase in [DesktopHermesUpgradePhase.quiescing, .backingUp, .updating, .restoringServices,
                      .verifying, .cancelling, .completed, .cancelled] {
            let bytes = try fixture.write(phase: phase)
            let record = DesktopHermesUpgradeRecord.read(from: fixture.store)
            guard case .recorded(let target, let observed, _) = record else { return XCTFail("record not loaded") }
            XCTAssertEqual(target, fixture.target)
            XCTAssertEqual(observed, phase)
            XCTAssertEqual(record.requiresRecovery, !phase.terminal)
            XCTAssertEqual(record.issue?.code, phase.terminal ? nil : .hermesUpdateRecoveryRequired)
            XCTAssertFalse(record.titleEnglish.isEmpty)
            XCTAssertFalse(record.explanationEnglish.isEmpty)
            XCTAssertTrue(phase.recordedLabelChinese.hasPrefix(phase.terminal ? "记录为" : "准备"))
            XCTAssertTrue(phase.recordedLabelEnglish.hasPrefix(phase.terminal ? "Recorded as" : "Intent to"))
            let presentation = String(reflecting: record) + record.explanationChinese + record.explanationEnglish
            XCTAssertFalse(presentation.contains(fixture.root.path))
            XCTAssertFalse(presentation.contains(fixture.runID))
            XCTAssertEqual(try Data(contentsOf: fixture.store.journalURL), bytes)
        }
    }

    func testMalformedDuplicateUnknownAndNestedFieldsAreRefusedWithoutRewriting() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        let valid = try fixture.write()
        var unknown = fixture.object(); unknown["futurePermission"] = true
        var nested = fixture.object(); nested["target"] = ["version": "0.21.6", "commit": String(repeating: "b", count: 40), "channel": "stable", "future": true]
        let duplicate = Data(("{\"phase\":\"completed\"," + String(decoding: valid.dropFirst(), as: UTF8.self)).utf8)
        let escaped = Data(("{\"ph\\u0061se\":\"completed\"," + String(decoding: valid.dropFirst(), as: UTF8.self)).utf8)
        for data in [Data("not-json token=never-display".utf8), duplicate, escaped,
                     try JSONSerialization.data(withJSONObject: unknown), try JSONSerialization.data(withJSONObject: nested)] {
            try fixture.write(data)
            let record = DesktopHermesUpgradeRecord.read(from: fixture.store)
            XCTAssertEqual(record, .unreadable)
            let issue = try XCTUnwrap(record.issue)
            XCTAssertEqual(issue.code.rawValue, "HR-HERMESUPDATE-003")
            XCTAssertFalse(issue.retryable)
            XCTAssertFalse(issue.summaryChinese.isEmpty || issue.summaryEnglish.isEmpty)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("never-display"))
            XCTAssertEqual(try Data(contentsOf: fixture.store.journalURL), data)
        }
    }

    func testUnsafeFilesLinksAndOversizeAreRefusedAndLeftIntact() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        let valid = try fixture.write()
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: fixture.store.journalURL.path)
        XCTAssertEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .unreadable)
        try fixture.write(valid)
        let alias = fixture.root.appendingPathComponent("alias")
        try FileManager.default.linkItem(at: fixture.store.journalURL, to: alias)
        XCTAssertEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .unreadable)
        try FileManager.default.removeItem(at: alias)
        try FileManager.default.removeItem(at: fixture.store.journalURL)
        try valid.write(to: alias)
        try FileManager.default.createSymbolicLink(at: fixture.store.journalURL, withDestinationURL: alias)
        XCTAssertEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .unreadable)
        XCTAssertEqual(try Data(contentsOf: alias), valid)
        try FileManager.default.removeItem(at: fixture.store.journalURL)
        try fixture.write(Data(repeating: 32, count: 65_537))
        XCTAssertEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .unreadable)
    }

    func testPendingRecordBlocksMigrationLeaseButTerminalHistoryDoesNot() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        try fixture.write()
        let migration = try DesktopMigrationJournalStore(root: fixture.root)
        XCTAssertThrowsError(try migration.acquireOperationLease()) { error in
            XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired)
        }
        try fixture.write(phase: .completed)
        let lease = try migration.acquireOperationLease()
        withExtendedLifetime(lease) {}
    }

    func testReadOnlyConditionsStayBlockedEvenForCleanSource() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        for state in [DesktopHermesUpgradeWorkingTree.clean, .dirty, .unknown] {
            let result = DesktopHermesUpgradeReadinessChecker(git: FixedGit(state: state)).check(
                detection: .usable(fixture.installation), target: fixture.target, journal: fixture.store,
                officialUpdateInProgress: false)
            XCTAssertEqual(result.workingTree, state)
            XCTAssertFalse(result.executionAvailable)
            XCTAssertEqual(result.issue?.code, .hermesUpdateBlocked)
            XCTAssertFalse(result.workingTreeSummaryEnglish.isEmpty)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.root.path))
    }

    func testPendingOrOfficialUpdateRefusesBeforeGitInspection() throws {
        let fixture = ReadOnlyUpgradeFixture(); defer { fixture.remove() }
        let checker = DesktopHermesUpgradeReadinessChecker(git: ForbiddenGit())
        let updating = checker.check(detection: .usable(fixture.installation), target: fixture.target,
            journal: fixture.store, officialUpdateInProgress: true)
        XCTAssertEqual(updating.workingTree, .unknown)
        try fixture.write()
        let pending = checker.check(detection: .usable(fixture.installation), target: fixture.target,
            journal: fixture.store, officialUpdateInProgress: false)
        XCTAssertTrue(pending.recoveryRequired)
        XCTAssertEqual(pending.issue?.code, .hermesUpdateRecoveryRequired)
    }
}

private struct FixedGit: DesktopHermesUpgradeGitInspecting {
    let state: DesktopHermesUpgradeWorkingTree
    func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree { state }
}
private struct ForbiddenGit: DesktopHermesUpgradeGitInspecting {
    func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree { XCTFail("must not inspect changing source"); return .clean }
}
private struct ReadOnlyUpgradeFixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    let runID = UUID().uuidString
    var store: DesktopHermesUpgradeJournalStore { .init(root: root) }
    var target: DesktopHermesUpgradeTarget { try! .init(version: "0.21.6", commit: String(repeating: "b", count: 40)) }
    var installation: DesktopLocalHermesInstallation {
        .init(executable: root.appendingPathComponent(".hermes/hermes-agent/venv/bin/hermes"),
              checkoutRoot: root.appendingPathComponent(".hermes/hermes-agent"), hermesHome: root.appendingPathComponent(".hermes"),
              commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
    }
    func object(phase: DesktopHermesUpgradePhase = .quiescing) -> [String: Any] {
        var result: [String: Any] = ["schemaVersion": 1, "runID": runID,
            "target": ["version": target.version, "commit": target.commit, "channel": "stable"],
            "sourceCommit": installation.commit, "sourceIdentity": String(repeating: "c", count: 64),
            "checkout": installation.checkoutRoot.path, "hermesHome": installation.hermesHome.path,
            "services": [], "originalChannel": "stable", "phase": phase.rawValue, "updatedAt": 1000]
        if phase.sourceMayHaveChanged { result["backup"] = ["runID": runID, "manifestSHA256": String(repeating: "e", count: 64)] }
        return result
    }
    @discardableResult func write(phase: DesktopHermesUpgradePhase = .quiescing) throws -> Data {
        let data = try JSONSerialization.data(withJSONObject: object(phase: phase), options: [.sortedKeys])
        try write(data)
        return data
    }
    func write(_ data: Data) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try data.write(to: store.journalURL)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: store.journalURL.path)
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
