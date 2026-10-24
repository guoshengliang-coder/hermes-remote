import XCTest
@testable import HermesGoDesktopCore

final class DesktopUpdateSequenceTests: XCTestCase {
    func testPreparedManifestJoinsUnifiedDiscoveryWithoutAnIndex() {
        let report = DesktopUpdateReport(checkedAt: Date(), appUpdate: nil, managedUpdate: nil)
            .includingPreparedManagedVersion("1.2.0", installed: "1.1.0")
        XCTAssertTrue(report.hasUpdates)
        XCTAssertEqual(report.managedUpdate?.version, "1.2.0")
    }
    func testPreparedManifestDoesNotOfferInstallOrDowngradeAsUpdate() {
        let report = DesktopUpdateReport(checkedAt: Date(), appUpdate: nil, managedUpdate: nil)
        for version in [nil, "1.2.0", "1.3.0"] as [String?] {
            XCTAssertFalse(report.includingPreparedManagedVersion("1.2.0", installed: version).hasUpdates)
        }
    }
    func testBackgroundMustCompleteBeforeAppReplacement() {
        var sequence = DesktopUpdateSequence()
        sequence.begin(background: true, app: true)
        XCTAssertEqual(sequence.stage, .background)
        sequence.backgroundFinished(succeeded: true, cleanupPending: false)
        XCTAssertEqual(sequence.stage, .app)
    }
    func testFailureOrPendingCleanupNeverQuitsForAppReplacement() {
        for result in [(false, false), (true, true)] {
            var sequence = DesktopUpdateSequence()
            sequence.begin(background: true, app: true)
            sequence.backgroundFinished(succeeded: result.0, cleanupPending: result.1)
            XCTAssertEqual(sequence.stage, .stopped)
            XCTAssertFalse(sequence.isRunning)
        }
    }
    func testCancellationIgnoresLateCompletion() {
        var sequence = DesktopUpdateSequence()
        sequence.begin(background: true, app: true)
        sequence.stop()
        sequence.backgroundFinished(succeeded: true, cleanupPending: false)
        XCTAssertEqual(sequence.stage, .stopped)
    }
    func testDoubleStartCannotReplaceActivePlan() {
        var sequence = DesktopUpdateSequence()
        sequence.begin(background: true, app: false)
        sequence.begin(background: false, app: true)
        sequence.backgroundFinished(succeeded: true, cleanupPending: false)
        XCTAssertEqual(sequence.stage, .completed)
    }
    func testAppOnlyAndBackgroundOnlyUpdates() {
        var sequence = DesktopUpdateSequence()
        sequence.begin(background: false, app: true)
        XCTAssertEqual(sequence.stage, .app)
        sequence.stop()
        sequence.begin(background: true, app: false)
        sequence.backgroundFinished(succeeded: true, cleanupPending: false)
        XCTAssertEqual(sequence.stage, .completed)
    }
}
