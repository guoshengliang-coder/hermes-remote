import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopSetupProgressTests: XCTestCase {
    private let first = "10000000-0000-4000-8000-000000000001"
    private let second = "10000000-0000-4000-8000-000000000002"

    func testBothSheetsCloseOnEveryTerminalStateEvenWithRetainedCleanupHandles() {
        let managed: [DesktopManagedBootstrapOperation] = [
            .idle, .preparing, .recovering, .completed(releaseVersion: "1.2.3", cleanupPending: false),
            .completed(releaseVersion: "1.2.3", cleanupPending: true), .failed,
        ]
        let components: [DesktopComponentBootstrapOperation] = [
            .idle, .preparing, .completed(releaseVersion: "1.2.3", cleanupPending: false),
            .completed(releaseVersion: "1.2.3", cleanupPending: true), .failed,
        ]
        XCTAssertTrue(managed.allSatisfy { !$0.presentsConfirmation })
        XCTAssertTrue(components.allSatisfy { !$0.presentsConfirmation })
        XCTAssertTrue(DesktopManagedBootstrapOperation.awaitingConfirmation.presentsConfirmation)
        XCTAssertTrue(DesktopManagedBootstrapOperation.committing.presentsConfirmation)
        XCTAssertTrue(DesktopComponentBootstrapOperation.awaitingConfirmation.presentsConfirmation)
        XCTAssertTrue(DesktopComponentBootstrapOperation.committing.presentsConfirmation)
    }

    func testElapsedTimeAdvancesWithoutCallbacksAndFreezesOnCompletion() throws {
        let recorder = SetupTestTelemetry()
        let progress = recorder.makeProgress()
        XCTAssertNil(progress.snapshot)
        progress.begin(runID: first)
        recorder.time = 138
        XCTAssertEqual(progress.snapshot?.elapsedSeconds, 38)
        XCTAssertEqual(progress.snapshot?.elapsedChinese, "已用时 38 秒")
        progress.advance(.waitingForConnection, runID: first)
        recorder.time = 175
        XCTAssertEqual(progress.snapshot?.elapsedChinese, "已用时 1 分 15 秒")
        XCTAssertEqual(progress.snapshot?.stageElapsedSeconds, 37)
        progress.advance(.completed, runID: first)
        let finished = try XCTUnwrap(progress.snapshot)
        recorder.time = 999
        progress.advance(.waitingForHermes, runID: first)
        progress.recordJournal(.candidateStarting, runID: first)
        XCTAssertEqual(progress.snapshot, finished)
        XCTAssertTrue(recorder.lines.last?.contains("end stage=completed elapsed_s=75") == true)
    }

    func testOldRunCannotOverwriteNewRunAndInvalidIDsCannotEnterLogs() {
        let recorder = SetupTestTelemetry()
        let progress = recorder.makeProgress()
        progress.begin(runID: first)
        progress.advance(.failed, runID: first)
        progress.begin(runID: second)
        progress.advance(.staging, runID: second)
        let count = recorder.lines.count
        progress.advance(.completed, runID: first)
        progress.recordJournal(.accountActive, runID: first)
        progress.begin(runID: "Bearer secret /Users/private/email@example.com")
        progress.advance(.failed, runID: "hga_secret")
        XCTAssertEqual(progress.snapshot?.runID, second)
        XCTAssertEqual(progress.snapshot?.stage, .staging)
        XCTAssertEqual(recorder.lines.count, count)
        XCTAssertFalse(recorder.lines.joined().contains("secret"))
    }

    func testRollbackCleanupAndUIEventsUseOnlyFixedTelemetryFields() {
        let recorder = SetupTestTelemetry()
        let progress = recorder.makeProgress()
        progress.begin(runID: first)
        progress.advance(.rollingBack, runID: first)
        progress.recordJournal(.rollingBack, runID: first)
        recorder.time = 105
        progress.advance(.failed, runID: first)
        progress.recordUI(.failed)
        XCTAssertTrue(recorder.lines.contains { $0.contains("journal=rolling_back") })
        XCTAssertTrue(recorder.lines.last?.contains("ui=failed") == true)
        progress.begin(runID: second)
        progress.advance(.cleaning, runID: second)
        progress.advance(.cleanupPending, runID: second)
        XCTAssertEqual(progress.snapshot?.stage.titleChinese, "连接已生效，临时文件待清理")
        XCTAssertEqual(DesktopManagedBootstrapOperation.completed(
            releaseVersion: "arbitrary personal text", cleanupPending: true
        ).uiState, .cleanupPending)
        XCTAssertFalse(recorder.lines.joined().contains("personal"))
    }

    func testProductionSinkWritesOnlySafeFieldsToPrivateRuntimeLog() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let url = root.appendingPathComponent("desktop-runtime.log")
        let progress = DesktopSetupProgress(operationLog: DesktopServiceOperationLog(url: url))
        progress.begin(runID: first)
        progress.recordJournal(.accountStaged, runID: first)
        progress.advance(.completed, runID: first)
        let log = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(log.contains("journal=account_staged"))
        XCTAssertTrue(log.contains("end stage=completed"))
        XCTAssertFalse(log.contains(root.path))
        let permissions = try FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? Int
        XCTAssertEqual(permissions, 0o600)
    }

    func testAllStagesHaveReadableLocalizedCopyAndNoFalsePercentage() {
        for stage in DesktopSetupStage.allCases {
            XCTAssertFalse(stage.titleChinese.isEmpty)
            XCTAssertFalse(stage.titleChinese.contains("%"))
            XCTAssertNotEqual(stage.titleChinese, stage.rawValue)
        }
    }
}

/// Shared by integration tests to inspect the real coordinator's telemetry without sleeping.
final class SetupTestTelemetry: @unchecked Sendable {
    private let lock = NSLock()
    private var clock: TimeInterval = 100
    private var recorded: [String] = []
    var time: TimeInterval {
        get { lock.withLock { clock } }
        set { lock.withLock { clock = newValue } }
    }
    var lines: [String] { lock.withLock { recorded } }
    func makeProgress() -> DesktopSetupProgress {
        DesktopSetupProgress(now: { self.time }, logSink: { line in
            self.lock.withLock { self.recorded.append(line) }
        })
    }
}
