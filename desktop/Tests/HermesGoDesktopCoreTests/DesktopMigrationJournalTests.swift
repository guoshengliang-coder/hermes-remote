import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopMigrationJournalTests: XCTestCase {
    func testReadOnlyLoadDoesNotCreateStateOrLockOnACleanMac() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let store = try DesktopMigrationJournalStore(root: root)

        XCTAssertNil(try store.loadReadOnly())
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: root.appendingPathComponent("migration.lock").path
        ))
    }

    func testJournalPersistsEveryOrderedTransitionAndBecomesAccountAuthoritative() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let runID = "10000000-0000-4000-8000-000000000001"
        let bindingID = "20000000-0000-4000-8000-000000000001"
        let store = try DesktopMigrationJournalStore(
            root: root,
            now: { Date(timeIntervalSince1970: 1_788_710_400) }
        )
        var journal = try store.begin(
            runID: runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: "1.2.3",
            bindingID: bindingID,
            bindingGeneration: 1
        )
        XCTAssertEqual(journal.state, .preflight)
        for state: DesktopMigrationState in [
            .accountStaged,
            .candidateStarting,
            .candidateAuthenticated,
            .candidateHealthy,
            .commitPending,
            .accountActive,
        ] {
            journal = try store.transition(runID: runID, to: state)
        }

        XCTAssertEqual(try store.load(), journal)
        XCTAssertEqual(journal.lastKnownGoodMode, .account)
        let attributes = try FileManager.default.attributesOfItem(
            atPath: root.appendingPathComponent("migration-state.json").path
        )
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
    }

    func testInvalidTransitionAndDifferentRunCannotMutateJournal() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try DesktopMigrationJournalStore(root: root)
        let runID = "10000000-0000-4000-8000-000000000001"
        let original = try store.begin(
            runID: runID,
            lastKnownGoodMode: .none,
            releaseVersion: "1.2.3",
            bindingID: nil,
            bindingGeneration: nil
        )

        XCTAssertThrowsError(try store.transition(runID: runID, to: .accountActive)) { error in
            XCTAssertEqual(error as? DesktopMigrationJournalError, .invalidTransition)
        }
        XCTAssertThrowsError(try store.transition(
            runID: "10000000-0000-4000-8000-000000000002",
            to: .accountStaged
        )) { error in
            XCTAssertEqual(error as? DesktopMigrationJournalError, .runMismatch)
        }
        XCTAssertEqual(try store.load(), original)
    }

    func testSameRunCannotBeReusedWithDifferentImmutableInputs() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try DesktopMigrationJournalStore(root: root)
        let runID = "10000000-0000-4000-8000-000000000001"
        let original = try store.begin(
            runID: runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: "1.2.3",
            bindingID: "20000000-0000-4000-8000-000000000001",
            bindingGeneration: 1
        )

        XCTAssertThrowsError(try store.begin(
            runID: runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: "1.2.4",
            bindingID: original.bindingID,
            bindingGeneration: original.bindingGeneration
        )) { error in
            XCTAssertEqual(error as? DesktopMigrationJournalError, .inputMismatch)
        }
        XCTAssertEqual(try store.load(), original)
    }

    func testNewRunReplacesTerminalRolledBackJournal() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try DesktopMigrationJournalStore(root: root)
        let firstRunID = "10000000-0000-4000-8000-000000000001"
        _ = try store.begin(
            runID: firstRunID,
            lastKnownGoodMode: .legacy,
            releaseVersion: "1.2.3",
            bindingID: "20000000-0000-4000-8000-000000000001",
            bindingGeneration: 1
        )
        _ = try store.transition(runID: firstRunID, to: .accountStaged)
        _ = try store.transition(runID: firstRunID, to: .rollingBack)
        _ = try store.transition(runID: firstRunID, to: .legacyActive)

        let retry = try store.begin(
            runID: "10000000-0000-4000-8000-000000000002",
            lastKnownGoodMode: .legacy,
            releaseVersion: "1.2.4",
            bindingID: "20000000-0000-4000-8000-000000000002",
            bindingGeneration: 2
        )

        XCTAssertEqual(retry.state, .preflight)
        XCTAssertEqual(retry.runID, "10000000-0000-4000-8000-000000000002")
        XCTAssertEqual(retry.releaseVersion, "1.2.4")
        XCTAssertEqual(retry.bindingID, "20000000-0000-4000-8000-000000000002")
        XCTAssertEqual(retry.bindingGeneration, 2)
        XCTAssertEqual(try store.load(), retry)
    }

    func testNewRunReplacesTerminalCleanJournal() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try DesktopMigrationJournalStore(root: root)
        let firstRunID = "10000000-0000-4000-8000-000000000001"
        _ = try store.begin(
            runID: firstRunID,
            lastKnownGoodMode: .none,
            releaseVersion: "1.2.3",
            bindingID: "20000000-0000-4000-8000-000000000001",
            bindingGeneration: 1
        )
        _ = try store.transition(runID: firstRunID, to: .rollingBack)
        _ = try store.transition(runID: firstRunID, to: .cleanUninstalled)

        let retry = try store.begin(
            runID: "10000000-0000-4000-8000-000000000002",
            lastKnownGoodMode: .none,
            releaseVersion: "1.2.3",
            bindingID: "20000000-0000-4000-8000-000000000002",
            bindingGeneration: 1
        )

        XCTAssertEqual(retry.state, .preflight)
        XCTAssertEqual(retry.runID, "10000000-0000-4000-8000-000000000002")
        XCTAssertEqual(try store.load(), retry)
    }

    func testNewRunCannotReplaceNonRetryableJournal() throws {
        for state: DesktopMigrationState in [.accountStaged, .accountActive, .rollbackAttentionRequired] {
            let root = temporaryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = try DesktopMigrationJournalStore(root: root)
            let firstRunID = "10000000-0000-4000-8000-000000000001"
            _ = try store.begin(
                runID: firstRunID,
                lastKnownGoodMode: .legacy,
                releaseVersion: "1.2.3",
                bindingID: "20000000-0000-4000-8000-000000000001",
                bindingGeneration: 1
            )
            _ = try store.transition(runID: firstRunID, to: .accountStaged)
            if state != .accountStaged {
                _ = try store.transition(runID: firstRunID, to: .candidateStarting)
                _ = try store.transition(runID: firstRunID, to: .candidateAuthenticated)
                _ = try store.transition(runID: firstRunID, to: .candidateHealthy)
                _ = try store.transition(runID: firstRunID, to: .commitPending)
                if state == .accountActive {
                    _ = try store.transition(runID: firstRunID, to: .accountActive)
                } else {
                    _ = try store.transition(runID: firstRunID, to: .rollbackAttentionRequired)
                }
            }
            let original = try XCTUnwrap(store.load())

            XCTAssertThrowsError(try store.begin(
                runID: "10000000-0000-4000-8000-000000000002",
                lastKnownGoodMode: .legacy,
                releaseVersion: "1.2.3",
                bindingID: "20000000-0000-4000-8000-000000000002",
                bindingGeneration: 1
            )) { error in
                XCTAssertEqual(error as? DesktopMigrationJournalError, .runMismatch)
            }
            XCTAssertEqual(try store.load(), original)
        }
    }

    func testRollbackPathStopsAtKnownLegacyOrManualAttention() throws {
        for terminal: DesktopMigrationState in [
            .cleanUninstalled, .legacyActive, .rollbackAttentionRequired,
        ] {
            let root = temporaryRoot()
            defer { try? FileManager.default.removeItem(at: root) }
            let store = try DesktopMigrationJournalStore(root: root)
            let runID = UUID().uuidString
            _ = try store.begin(
                runID: runID,
                lastKnownGoodMode: .legacy,
                releaseVersion: "1.2.3",
                bindingID: nil,
                bindingGeneration: nil
            )
            _ = try store.transition(runID: runID, to: .accountStaged)
            _ = try store.transition(runID: runID, to: .rollingBack)
            XCTAssertEqual(try store.transition(runID: runID, to: terminal).state, terminal)
        }
    }

    func testCorruptOrOverPermissiveStateFailsClosed() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let state = root.appendingPathComponent("migration-state.json")
        try Data("{}".utf8).write(to: state)
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: state.path)
        let store = try DesktopMigrationJournalStore(root: root)

        XCTAssertThrowsError(try store.load()) { error in
            XCTAssertEqual(error as? DesktopMigrationJournalError, .unsafeStateFile)
        }
    }

    func testOperationLeaseExcludesASecondMigrationUntilReleased() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try DesktopMigrationJournalStore(root: root)
        var first: DesktopMigrationOperationLease? = try store.acquireOperationLease()
        XCTAssertNotNil(first)

        XCTAssertThrowsError(try store.acquireOperationLease()) { error in
            XCTAssertEqual(error as? DesktopMigrationJournalError, .lockUnavailable)
        }
        first = nil
        XCTAssertNotNil(try store.acquireOperationLease())
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-migration-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
    }
}
