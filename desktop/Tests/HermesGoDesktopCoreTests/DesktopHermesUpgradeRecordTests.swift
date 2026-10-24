import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeRecordTests: XCTestCase {
    func testMissingRecordReadsWithoutCreatingStateAndDoesNotClaimACheckBeforeReading() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        XCTAssertNotEqual(DesktopHermesUpgradeRecord.unchecked, .absent)
        XCTAssertEqual(DesktopHermesUpgradeRecord.unchecked.titleChinese, "尚未读取升级记录")
        XCTAssertEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .absent)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.root.path))
        XCTAssertNil(DesktopHermesUpgradeRecord.absent.issue)
        XCTAssertFalse(DesktopHermesUpgradeRecord.absent.requiresRecovery)
    }

    func testEveryPersistedIntentStaysRecoveryRequiredAndTerminalIsOnlyHistory() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let lease = try fixture.migration.acquireOperationLease()
        defer { withExtendedLifetime(lease) {} }
        try fixture.store.begin(fixture.journal)
        var journal = fixture.journal
        for phase in [DesktopHermesUpgradePhase.quiescing, .backingUp, .updating, .restoringServices, .verifying, .completed] {
            if phase != .quiescing {
                journal = try fixture.store.transition(runID: journal.runID, to: phase,
                    backup: phase == .updating ? .init(runID: journal.runID, manifestSHA256: String(repeating: "e", count: 64)) : nil)
            }
            let record = DesktopHermesUpgradeRecord.read(from: fixture.store)
            XCTAssertEqual(record, .recorded(target: fixture.journal.target, phase: phase, updatedAt: journal.updatedAt))
            XCTAssertEqual(record.requiresRecovery, !phase.terminal)
            if phase.terminal {
                XCTAssertNil(record.issue)
                XCTAssertEqual(record.titleChinese, "历史升级记录")
                XCTAssertTrue(record.explanationChinese.contains("不能证明当前运行"))
                XCTAssertTrue(record.explanationEnglish.contains("does not verify"))
            } else {
                XCTAssertEqual(record.issue?.code, .hermesUpdateRecoveryRequired)
                XCTAssertEqual(record.titleChinese, "待恢复的升级记录")
                XCTAssertTrue(record.explanationChinese.contains("不能证明操作成功或进程仍在运行"))
                XCTAssertTrue(record.explanationEnglish.contains("not proof of success or a running process"))
            }
            let presentation = String(reflecting: record) + record.explanationChinese + record.explanationEnglish
            for privateValue in [fixture.journal.runID, fixture.journal.checkout, fixture.journal.hermesHome, fixture.journal.sourceIdentity] {
                XCTAssertFalse(presentation.contains(privateValue))
            }
        }
    }

    func testCancelledRecordIsHistoricalAndCancelIntentStillRequiresRecovery() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let lease = try fixture.migration.acquireOperationLease()
        defer { withExtendedLifetime(lease) {} }
        try fixture.store.begin(fixture.journal)
        _ = try fixture.store.transition(runID: fixture.journal.runID, to: .cancelling)
        XCTAssertTrue(DesktopHermesUpgradeRecord.read(from: fixture.store).requiresRecovery)
        _ = try fixture.store.transition(runID: fixture.journal.runID, to: .cancelled)
        let record = DesktopHermesUpgradeRecord.read(from: fixture.store)
        XCTAssertFalse(record.requiresRecovery)
        XCTAssertEqual(record.titleChinese, "历史升级记录")
        XCTAssertTrue(record.explanationEnglish.contains("past record"))
    }

    func testUnsafeOrAmbiguousRecordReplacesEarlierSnapshotWithoutRewritingOrLeaking() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let lease = try fixture.migration.acquireOperationLease()
        defer { withExtendedLifetime(lease) {} }
        try fixture.store.begin(fixture.journal)
        XCTAssertNotEqual(DesktopHermesUpgradeRecord.read(from: fixture.store), .unreadable)
        var bytes = try Data(contentsOf: fixture.store.journalURL)
        bytes.removeLast()
        bytes.append(Data(#", "phase":"completed", "private":"token=never-display-this"}"#.utf8))
        try bytes.write(to: fixture.store.journalURL)
        let record = DesktopHermesUpgradeRecord.read(from: fixture.store)
        XCTAssertEqual(record, .unreadable)
        XCTAssertEqual(try Data(contentsOf: fixture.store.journalURL), bytes)
        let issue = try XCTUnwrap(record.issue)
        XCTAssertEqual(issue.code.rawValue, "HR-HERMESUPDATE-003")
        XCTAssertFalse(issue.retryable)
        XCTAssertFalse(issue.summaryChinese.isEmpty)
        XCTAssertFalse(issue.summaryEnglish.isEmpty)
        XCTAssertFalse(issue.sanitizedDiagnostic.contains("never-display-this"))
        XCTAssertFalse(String(reflecting: record).contains(fixture.journal.target.version))
    }

    func testAllRecordedPhaseLabelsAreBilingualAndExplicitAboutIntentOrHistory() {
        for phase in [DesktopHermesUpgradePhase.quiescing, .backingUp, .updating, .restoringServices, .verifying, .cancelling, .completed, .cancelled] {
            XCTAssertTrue(phase.recordedLabelChinese.hasPrefix(phase.terminal ? "记录为" : "准备"))
            XCTAssertTrue(phase.recordedLabelEnglish.hasPrefix(phase.terminal ? "Recorded as" : "Intent to"))
        }
        for record in [DesktopHermesUpgradeRecord.unchecked, .absent, .unreadable] {
            XCTAssertFalse(record.titleChinese.isEmpty)
            XCTAssertFalse(record.titleEnglish.isEmpty)
            XCTAssertFalse(record.explanationChinese.isEmpty)
            XCTAssertFalse(record.explanationEnglish.isEmpty)
        }
    }
}

private struct Fixture {
    let root: URL
    let migration: DesktopMigrationJournalStore
    let journal: DesktopHermesUpgradeJournal
    var store: DesktopHermesUpgradeJournalStore { migration.hermesUpgradeJournal }
    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        migration = try .init(root: root.appendingPathComponent("state"))
        let home = root.appendingPathComponent(".hermes")
        let checkout = home.appendingPathComponent("hermes-agent")
        let installation = DesktopLocalHermesInstallation(executable: checkout.appendingPathComponent("venv/bin/hermes"),
            checkoutRoot: checkout, hermesHome: home, commit: String(repeating: "a", count: 40),
            version: "0.21.3", identityChangedAt: .distantPast)
        journal = .init(target: try .init(version: "0.21.6", commit: String(repeating: "b", count: 40)),
            installation: installation, services: [], originalChannel: "stable")
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
