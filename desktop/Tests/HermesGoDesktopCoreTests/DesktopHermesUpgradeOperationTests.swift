import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeOperationTests: XCTestCase {
    func testRemoteURLsCannotBecomeLocalInstallationPaths() throws {
        for remoteCheckout in [false, true] {
            XCTAssertThrowsError(try DesktopHermesUpgradeJournal(runID: UUID(),
                target: .init(version: "0.22.5", commit: String(repeating: "b", count: 40)),
                sourceCommit: String(repeating: "a", count: 40), sourceIdentity: String(repeating: "c", count: 64),
                checkout: remoteCheckout ? URL(string: "https://fixture.invalid/home/hermes-agent")! : URL(fileURLWithPath: "/home/hermes-agent"),
                hermesHome: remoteCheckout ? URL(fileURLWithPath: "/home") : URL(string: "https://fixture.invalid/home")!,
                services: [], originalChannel: "stable")) { error in
                    XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked)
                }
        }
    }

    func testDurableBeginSharesMaintenanceLeaseAndExactResumeRetainsOriginalIdentity() throws {
        let fixture = try OperationFixture(); defer { fixture.remove() }
        var operation: DesktopHermesUpgradeOperation? = try .begin(store: fixture.store, journal: fixture.initial)
        XCTAssertEqual(try fixture.store.loadReadOnly(), fixture.initial)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: fixture.store.journalURL.path)[.posixPermissions] as? Int, 0o600)
        XCTAssertThrowsError(try DesktopHermesUpgradeOperation.resume(store: fixture.store, expected: fixture.initial))
        let migration = try DesktopMigrationJournalStore(root: fixture.root)
        XCTAssertThrowsError(try migration.acquireOperationLease())
        let backed = try XCTUnwrap(operation).advance(expected: fixture.initial, to: .backingUp, now: fixture.later)
        operation = nil
        XCTAssertThrowsError(try migration.acquireOperationLease()) { XCTAssertEqual($0 as? DesktopHermesUpgradeError, .recoveryRequired) }
        XCTAssertThrowsError(try DesktopHermesUpgradeOperation.resume(store: fixture.store, expected: fixture.initial))
        let resumed = try DesktopHermesUpgradeOperation.resume(store: fixture.store, expected: backed)
        XCTAssertEqual(resumed.snapshot.runID, fixture.initial.runID)
        XCTAssertEqual(resumed.snapshot.target, fixture.initial.target)
        XCTAssertEqual(resumed.snapshot.services, fixture.initial.services)
        XCTAssertEqual(resumed.snapshot, try fixture.store.loadReadOnly())
    }

    func testSuccessAndRecoveryRequireBackupAndDoNotPermitCancellationAfterMutation() throws {
        for recover in [false, true] {
            let fixture = try OperationFixture(); defer { fixture.remove() }
            var operation: DesktopHermesUpgradeOperation? = try .begin(store: fixture.store, journal: fixture.initial)
            var state = try XCTUnwrap(operation).advance(expected: fixture.initial, to: .backingUp, now: fixture.later)
            XCTAssertThrowsError(try operation!.advance(expected: state, to: .updating))
            XCTAssertEqual(try fixture.store.loadReadOnly(), state)
            let backup = DesktopHermesUpgradeBackup(runID: state.runID, manifestSHA256: String(repeating: "e", count: 64))
            state = try operation!.advance(expected: state, to: .updating, backup: backup)
            XCTAssertThrowsError(try operation!.advance(expected: state, to: .cancelling))
            XCTAssertThrowsError(try operation!.advance(expected: state, to: .completed))
            if recover {
                state = try operation!.advance(expected: state, to: .recovering)
                XCTAssertTrue(DesktopHermesUpgradeRecord.read(from: fixture.store).requiresRecovery)
                state = try operation!.advance(expected: state, to: .rolledBack)
            } else {
                state = try operation!.advance(expected: state, to: .restoringServices)
                state = try operation!.advance(expected: state, to: .verifying)
                state = try operation!.advance(expected: state, to: .completed)
            }
            XCTAssertEqual(state.backup, backup)
            XCTAssertTrue(state.phase.terminal)
            XCTAssertThrowsError(try operation!.advance(expected: state, to: .recovering))
            operation = nil
            XCTAssertThrowsError(try DesktopHermesUpgradeOperation.resume(store: fixture.store, expected: state))
            let lease = try DesktopMigrationJournalStore(root: fixture.root).acquireOperationLease()
            withExtendedLifetime(lease) {}
        }
    }

    func testCancellationIsDurableAndOnlyANewRunMayReplaceTerminalHistory() throws {
        let fixture = try OperationFixture(); defer { fixture.remove() }
        var operation: DesktopHermesUpgradeOperation? = try .begin(store: fixture.store, journal: fixture.initial)
        let cancelling = try operation!.advance(expected: fixture.initial, to: .cancelling)
        let cancelled = try operation!.advance(expected: cancelling, to: .cancelled)
        operation = nil
        XCTAssertThrowsError(try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial))
        XCTAssertEqual(try fixture.store.loadReadOnly(), cancelled)
        let next = try fixture.journal(runID: UUID())
        let newOperation = try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: next)
        XCTAssertEqual(newOperation.snapshot, next)
    }

    func testStaleSnapshotInvalidBackupAndClockRegressionLeaveRecordUntouched() throws {
        let fixture = try OperationFixture(); defer { fixture.remove() }
        let operation = try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial)
        let state = try operation.advance(expected: fixture.initial, to: .backingUp, now: fixture.later)
        for backup in [DesktopHermesUpgradeBackup(runID: UUID().uuidString, manifestSHA256: String(repeating: "e", count: 64)),
                       DesktopHermesUpgradeBackup(runID: state.runID, manifestSHA256: "private-invalid-input")] {
            XCTAssertThrowsError(try operation.advance(expected: state, to: .updating, backup: backup))
        }
        XCTAssertThrowsError(try operation.advance(expected: fixture.initial, to: .cancelling))
        XCTAssertThrowsError(try operation.advance(expected: state, to: .cancelling, now: .distantPast))
        XCTAssertEqual(try fixture.store.loadReadOnly(), state)
        // An out-of-band journal replacement cannot be overwritten using the writer's cache.
        let foreign = try fixture.journal(runID: UUID())
        try fixture.write(foreign)
        XCTAssertThrowsError(try operation.advance(expected: state, to: .cancelling))
        XCTAssertEqual(try fixture.store.loadReadOnly(), foreign)
    }

    func testPendingMigrationAndHeldMigrationLeasePreventJournalCreation() throws {
        let fixture = try OperationFixture(); defer { fixture.remove() }
        let migration = try DesktopMigrationJournalStore(root: fixture.root)
        var lease: DesktopMigrationOperationLease? = try migration.acquireOperationLease()
        XCTAssertThrowsError(try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial))
        XCTAssertNil(try fixture.store.loadReadOnly()); XCTAssertNotNil(lease)
        lease = nil
        _ = try migration.begin(runID: UUID().uuidString, lastKnownGoodMode: .none, releaseVersion: "0.4.12", bindingID: nil, bindingGeneration: nil)
        XCTAssertThrowsError(try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial))
        XCTAssertNil(try fixture.store.loadReadOnly())
    }

    func testPersistenceFailuresLeaveCompleteOldOrNewRecordAndReleaseLease() throws {
        enum Injected: Error { case failure }
        for point in [DesktopHermesUpgradeOperation.PersistencePoint.temporarySynced, .replaced, .directorySynced] {
            let fixture = try OperationFixture(); defer { fixture.remove() }
            XCTAssertThrowsError(try DesktopHermesUpgradeOperation(store: fixture.store, expected: fixture.initial,
                resuming: false, persistenceHook: { if $0 == point { throw Injected.failure } })) { error in
                let issue = (error as? DesktopHermesUpgradeError)?.issue
                XCTAssertEqual(issue?.code.rawValue, "HR-HERMESUPDATE-003")
                XCTAssertEqual(issue?.retryable, false)
                XCTAssertFalse(issue?.summaryChinese.isEmpty ?? true)
                XCTAssertFalse(issue?.summaryEnglish.isEmpty ?? true)
                XCTAssertFalse(issue?.sanitizedDiagnostic.contains(fixture.root.path) ?? true)
            }
            let state = try fixture.store.loadReadOnly()
            XCTAssertEqual(state, point == .temporarySynced ? nil : fixture.initial)
            XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: fixture.root.path).contains { $0.hasSuffix(".tmp") })
            let resumed = try state.map { try DesktopHermesUpgradeOperation.resume(store: fixture.store, expected: $0) }
                ?? DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial)
            XCTAssertEqual(resumed.snapshot, fixture.initial)
        }
    }

    func testUnsafeJournalLockAndRootAreRefusedWithoutRepairingPermissions() throws {
        for unsafe in ["root", "lock-mode", "lock-link", "journal-link", "journal-hardlink"] {
            let fixture = try OperationFixture(); defer { fixture.remove() }
            try FileManager.default.createDirectory(at: fixture.root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            let lock = fixture.root.appendingPathComponent("migration-operation.lock")
            let other = fixture.root.appendingPathComponent("untouched")
            try Data("sentinel".utf8).write(to: other)
            switch unsafe {
            case "root": try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: fixture.root.path)
            case "lock-mode": try Data().write(to: lock); try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: lock.path)
            case "lock-link": try FileManager.default.createSymbolicLink(at: lock, withDestinationURL: other)
            case "journal-link": try FileManager.default.createSymbolicLink(at: fixture.store.journalURL, withDestinationURL: other)
            default: try fixture.write(fixture.initial); try FileManager.default.linkItem(at: fixture.store.journalURL, to: fixture.root.appendingPathComponent("alias"))
            }
            XCTAssertThrowsError(try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial))
            XCTAssertEqual(try Data(contentsOf: other), Data("sentinel".utf8))
            if unsafe == "root" { XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: fixture.root.path)[.posixPermissions] as? Int, 0o755) }
            if unsafe == "lock-mode" { XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: lock.path)[.posixPermissions] as? Int, 0o644) }
        }
    }

    func testReplacedLeaseCannotAdvanceOldOperation() throws {
        let fixture = try OperationFixture(); defer { fixture.remove() }
        let operation = try DesktopHermesUpgradeOperation.begin(store: fixture.store, journal: fixture.initial)
        let lock = fixture.root.appendingPathComponent("migration-operation.lock")
        try FileManager.default.moveItem(at: lock, to: fixture.root.appendingPathComponent("old-lock"))
        try Data().write(to: lock); try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: lock.path)
        XCTAssertThrowsError(try operation.advance(expected: fixture.initial, to: .backingUp))
        XCTAssertEqual(try fixture.store.loadReadOnly(), fixture.initial)
    }
}

private struct OperationFixture {
    let root: URL
    let initial: DesktopHermesUpgradeJournal
    let later = Date(timeIntervalSince1970: 1_700_000_001)
    var store: DesktopHermesUpgradeJournalStore { .init(root: root) }
    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("hermes-journal-\(UUID().uuidString)").resolvingSymlinksInPath()
        initial = try Self.make(root: root, runID: UUID())
    }
    func journal(runID: UUID) throws -> DesktopHermesUpgradeJournal { try Self.make(root: root, runID: runID) }
    private static func make(root: URL, runID: UUID) throws -> DesktopHermesUpgradeJournal {
        let home = root.appendingPathComponent("synthetic-home")
        return try .init(runID: runID, target: .init(version: "0.22.5", commit: String(repeating: "b", count: 40)),
            sourceCommit: String(repeating: "a", count: 40), sourceIdentity: String(repeating: "c", count: 64),
            checkout: home.appendingPathComponent("hermes-agent"), hermesHome: home,
            services: [.init(label: "com.hermesgo.compat-lab.synthetic", wasLoaded: false, configurationDigest: String(repeating: "d", count: 64))],
            originalChannel: "stable", now: Date(timeIntervalSince1970: 1_700_000_000))
    }
    func write(_ journal: DesktopHermesUpgradeJournal) throws {
        try JSONEncoder().encode(journal).write(to: store.journalURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: store.journalURL.path)
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
