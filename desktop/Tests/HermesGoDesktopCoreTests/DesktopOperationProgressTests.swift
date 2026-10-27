import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopOperationProgressTests: XCTestCase {
    func testSilenceDoesNotAdvanceFailOrCompleteWork() {
        var tracker = DesktopOperationTracker()
        let input = DesktopOperationStatus(kind: .desktopUpdate, state: .running, stage: "下载")
        XCTAssertTrue(tracker.update(input, now: 100))
        XCTAssertFalse(tracker.showsSilence(at: 144))
        XCTAssertFalse(tracker.update(input, now: 160))
        XCTAssertTrue(tracker.showsSilence(at: 160))
        XCTAssertEqual(tracker.status?.state, .running)
        XCTAssertEqual(tracker.elapsed(at: 160), 60)
        tracker.update(nil, now: 200)
        XCTAssertEqual(tracker.status?.state, .running)
    }
    func testStageAndRealOutputResetSilenceButDisplayChangesDoNot() {
        var tracker = DesktopOperationTracker()
        var input = DesktopOperationStatus(kind: .hermesUpgrade, state: .running, stage: "下载", activity: "bytes=1")
        tracker.update(input, now: 0)
        input.measurements = ["速度暂不可用"]
        tracker.update(input, now: 60)
        XCTAssertTrue(tracker.showsSilence(at: 60))
        input.activity = "bytes=2"
        tracker.update(input, now: 65)
        XCTAssertFalse(tracker.showsSilence(at: 65))
        input.stage = "校验"
        tracker.update(input, now: 70)
        XCTAssertEqual(tracker.stageElapsed(at: 75), 5)
        XCTAssertEqual(tracker.elapsed(at: 75), 75)
    }
    func testConfirmationAndCleanupAreNotShownAsRunningOrStalled() {
        var tracker = DesktopOperationTracker()
        tracker.update(.init(kind: .componentSetup, state: .running, stage: "准备"), now: 0)
        tracker.update(.init(kind: .componentSetup, state: .waitingForConfirmation, stage: "等待确认"), now: 5)
        XCTAssertFalse(tracker.showsSilence(at: 1000))
        tracker.update(.init(kind: .componentSetup, state: .running, stage: "启动服务"), now: 1001)
        XCTAssertEqual(tracker.generation, 1)
        tracker.update(.init(kind: .componentSetup, state: .attention, stage: "清理待处理"), now: 1010)
        XCTAssertFalse(tracker.showsSilence(at: 1200))
        XCTAssertEqual(tracker.elapsed(at: 1200), 1010)
    }
    func testFailureIsRetainedAndRetryGetsFreshClock() {
        var tracker = DesktopOperationTracker()
        tracker.update(.init(kind: .hermesUpgrade, state: .running, stage: "下载"), now: 10)
        tracker.update(.init(kind: .hermesUpgrade, state: .failed, stage: "失败", issue: .init(code: .hermesUpdateExecutionFailed)), now: 20)
        XCTAssertEqual(tracker.elapsed(at: 200), 10)
        XCTAssertEqual(tracker.stageElapsed(at: 200), 10)
        XCTAssertTrue(tracker.update(.init(kind: .hermesUpgrade, state: .running, stage: "重试下载"), now: 210))
        XCTAssertEqual(tracker.generation, 2)
        XCTAssertEqual(tracker.elapsed(at: 212), 2)
        XCTAssertNil(tracker.status?.issue)
    }
    func testOldCompletedOperationDoesNotOpenOnLaunchButPendingCleanupDoes() {
        var tracker = DesktopOperationTracker()
        XCTAssertFalse(tracker.update(.init(kind: .desktopUpdate, state: .succeeded, stage: "旧结果"), now: 0))
        XCTAssertNil(tracker.status)
        XCTAssertTrue(tracker.update(.init(kind: .managedSetup, state: .attention, stage: "清理待处理"), now: 10))
        XCTAssertNotNil(tracker.status)
    }
    func testOutputIsBoundedAndRedacted() {
        let output = Array(repeating: "Authorization: Bearer very-private-secret", count: 120)
        let status = DesktopOperationStatus(kind: .hermesUpgrade, state: .running, stage: "下载", output: output)
        XCTAssertEqual(status.output.count, 100)
        XCTAssertFalse(status.output.joined().contains("very-private-secret"))
    }
}
