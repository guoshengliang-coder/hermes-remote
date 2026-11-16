import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopAppUpdateTransactionTests: XCTestCase {
    private func fixture() throws -> (URL, DesktopAppUpdateTransaction) {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: parent) }
        let root = parent.appendingPathComponent("updates")
        try DesktopAppUpdateTransactions.privateDirectory(root)
        let workspace = root.appendingPathComponent(UUID().uuidString)
        let dest = parent.appendingPathComponent("Desktop.app")
        let staged = parent.appendingPathComponent(".HermesGoDesktopUpdate-\(UUID().uuidString).app")
        for url in [dest, staged, workspace] {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        }
        try Data("old".utf8).write(to: dest.appendingPathComponent("binary"))
        try Data("new".utf8).write(to: staged.appendingPathComponent("binary"))
        let reference = DesktopAppUpdateReference(appVersion: "0.2.53", buildNumber: 56, minimumMacOS: "14.0",
            downloadURL: URL(string: "https://example.test/update.dmg")!, sizeBytes: 1,
            sha256: String(repeating: "a", count: 64), releaseNotes: [], sourceCommit: String(repeating: "b", count: 40))
        let prepared = DesktopPreparedAppUpdate(reference: reference, destinationAppURL: dest, stagedAppURL: staged,
            workspaceURL: workspace, mountPointURL: workspace.appendingPathComponent("mount"),
            destinationDigest: try DesktopAppUpdateTransaction.digest(dest), stagedDigest: try DesktopAppUpdateTransaction.digest(staged))
        let record = try DesktopAppUpdateTransaction(prepared: prepared, processID: 12345)
        try DesktopAppUpdateTransactions.save(record, root: root)
        return (root, record)
    }

    func testBackupExistsUntilExactStartupConfirmation() throws {
        let (root, r) = try fixture()
        var checked = false
        try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in true }, launch: { _ in true }, waitForStartup: { value in
                checked = true
                XCTAssertEqual(try? DesktopAppUpdateTransaction.digest(value.backup), r.oldDigest)
                XCTAssertEqual(try? DesktopAppUpdateTransactions.load(root: root)?.state, .awaitingStartup)
                return true
            })
        XCTAssertTrue(checked)
        XCTAssertEqual(try DesktopAppUpdateTransactions.load(root: root)?.state, .completed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: r.backup.path))
    }

    func testTimeoutRetainsBackupAndDurableRecoveryResult() throws {
        let (root, r) = try fixture()
        XCTAssertThrowsError(try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in true }, launch: { _ in true }, waitForStartup: { _ in false }))
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.backup), r.oldDigest)
        XCTAssertEqual(try DesktopAppUpdateTransactions.load(root: root)?.state, .recoveryRequired)
        XCTAssertFalse(DesktopAppUpdateTransactions.confirmed(r, root: root))
    }

    func testLaunchFailureCannotDeleteBackupOrReportSuccess() throws {
        let (root, r) = try fixture()
        XCTAssertThrowsError(try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in true }, launch: { _ in false }, waitForStartup: { _ in XCTFail(); return true }))
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.backup), r.oldDigest)
        XCTAssertEqual(try DesktopAppUpdateTransactions.load(root: root)?.state, .recoveryRequired)
    }

    func testRunningOldProcessPreventsReplacement() throws {
        let (root, r) = try fixture()
        try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in false }, launch: { _ in XCTFail(); return false }, waitForStartup: { _ in false })
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.destination), r.oldDigest)
        XCTAssertFalse(FileManager.default.fileExists(atPath: r.backup.path))
    }

    func testChangedDestinationNeverGetsOverwritten() throws {
        let (root, r) = try fixture()
        try Data("external change".utf8).write(to: r.destination.appendingPathComponent("binary"))
        XCTAssertThrowsError(try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in true }, launch: { _ in false }, waitForStartup: { _ in false }))
        XCTAssertEqual(try String(contentsOf: r.destination.appendingPathComponent("binary")), "external change")
    }

    func testConfirmedRecoveryKeepsFailedCandidateAndDoesNotReplayUpdate() throws {
        let (root, r) = try fixture()
        try? DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in true }, launch: { _ in false }, waitForStartup: { _ in false })
        var failed = try XCTUnwrap(DesktopAppUpdateTransactions.load(root: root))
        failed.state = .restoring; try DesktopAppUpdateTransactions.save(failed, root: root)
        try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: true,
            waitForExit: { _ in true }, launch: { _ in true }, waitForStartup: { _ in false })
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.destination), r.oldDigest)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.staged), r.newDigest)
        XCTAssertEqual(try DesktopAppUpdateTransactions.load(root: root)?.state, .restored)
        XCTAssertThrowsError(try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: false,
            waitForExit: { _ in true }, launch: { _ in false }, waitForStartup: { _ in false }))
    }

    func testInterruptedRestoreAfterBothMovesCanFinishWithoutBackup() throws {
        let (root, r) = try fixture()
        try FileManager.default.moveItem(at: r.destination, to: r.backup)
        try FileManager.default.moveItem(at: r.staged, to: r.destination)
        try FileManager.default.moveItem(at: r.destination, to: r.staged)
        try FileManager.default.moveItem(at: r.backup, to: r.destination)
        var interrupted = r; interrupted.state = .restoring
        try DesktopAppUpdateTransactions.save(interrupted, root: root)
        var launched = false
        try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: true,
            waitForExit: { _ in true }, launch: { _ in launched = true; return true }, waitForStartup: { _ in false })
        XCTAssertTrue(launched)
        XCTAssertEqual(try DesktopAppUpdateTransactions.load(root: root)?.state, .restored)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.staged), r.newDigest)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.destination), r.oldDigest)
    }

    func testInterruptedRestoreWithUnknownDestinationRemainsBlocked() throws {
        let (root, r) = try fixture()
        try FileManager.default.moveItem(at: r.destination, to: r.backup)
        try FileManager.default.moveItem(at: r.staged, to: r.destination)
        try Data("unknown".utf8).write(to: r.destination.appendingPathComponent("binary"))
        var interrupted = r; interrupted.state = .restoring
        try DesktopAppUpdateTransactions.save(interrupted, root: root)
        XCTAssertThrowsError(try DesktopAppUpdateTransactions.apply(root: root, id: r.id, restoring: true,
            waitForExit: { _ in true }, launch: { _ in XCTFail(); return true }, waitForStartup: { _ in false }))
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(r.backup), r.oldDigest)
        XCTAssertEqual(try String(contentsOf: r.destination.appendingPathComponent("binary")), "unknown")
    }

    func testSymlinkRecordAndStagedContentAreRejected() throws {
        let (root, r) = try fixture()
        try FileManager.default.removeItem(at: r.staged)
        try FileManager.default.createSymbolicLink(at: r.staged, withDestinationURL: r.destination)
        XCTAssertThrowsError(try DesktopAppUpdateTransactions.load(root: root))
    }

    func testNestedLeaseFailsRatherThanRacingHelper() throws {
        let (root, _) = try fixture()
        try DesktopAppUpdateTransactions.withLock(root: root) {
            XCTAssertThrowsError(try DesktopAppUpdateTransactions.withLock(root: root) {})
        }
    }
}
