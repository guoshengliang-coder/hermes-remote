import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeCompletionTests: XCTestCase {
    private let runID = "10000000-0000-4000-8000-000000000001"
    private var target: DesktopHermesUpgradeTarget { try! .init(version: "0.21.6", commit: String(repeating: "a", count: 40)) }
    private var receipt: [String: Any] {
        ["schema": 1, "update_id": runID, "correlation_id": runID, "outcome": "success",
         "finished_at": "2026-10-11T04:47:40.505360+00:00", "post_update": ["sha": target.commit],
         "stages": [["name": "build", "outcome": "success"]], "pm_sync_outcome": "ok",
         "pm_exit_code": 0, "pm_venv_rebuild": ["ok": true],
         "gateway_restart": ["incomplete": false, "failed_units": [], "phase_error": ""]]
    }
    private func evidence(diskCommit: String? = nil, boots: [String]? = nil, current: Bool = true,
                          tail: Bool = false, fleet: Bool = false, services: Bool = true) -> DesktopHermesUpgradeCompletionEvidence {
        .init(processExitCode: 0, diskCommit: diskCommit ?? target.commit, bootCommits: boots ?? [target.commit],
              dependencyInputsCurrent: current, sourceCompletionPending: tail,
              fleetRestartPending: fleet, servicesMatchOriginalLoadState: services)
    }
    private func verify(_ object: [String: Any], evidence: DesktopHermesUpgradeCompletionEvidence? = nil) throws {
        try DesktopHermesUpgradeCompletionVerifier.verify(receipt: JSONSerialization.data(withJSONObject: object),
            runID: runID, target: target, expectedRunningServices: 1, evidence: evidence ?? self.evidence())
    }
    func testVerifiedTerminalRunAndExactBootIdentityAreAccepted() throws { try verify(receipt) }
    func testExitZeroSuccessWithOwedWorkNeverMeansUpgradeCompleted() throws {
        var owed = receipt
        owed["exit_code"] = 0
        owed["followups"] = [["step": "gateway_restart", "reason": "fixture owed restart"]]
        owed["gateway_restart"] = ["incomplete": true, "failed_units": [], "phase_error": "fixture sandbox refused launchctl"]
        XCTAssertThrowsError(try verify(owed))
        owed = receipt; owed["followups"] = [["step": "build"]]
        XCTAssertThrowsError(try verify(owed))
        owed = receipt; owed["outcome"] = "running"; owed["finished_at"] = NSNull()
        XCTAssertThrowsError(try verify(owed))
    }
    func testWrongOrMissingReceiptEvidenceFailsClosed() throws {
        for key in ["schema", "update_id", "correlation_id", "outcome", "finished_at", "post_update", "stages", "pm_sync_outcome", "pm_exit_code", "pm_venv_rebuild", "gateway_restart"] {
            var missing = receipt; missing.removeValue(forKey: key)
            XCTAssertThrowsError(try verify(missing), key)
        }
        var wrong = receipt; wrong["correlation_id"] = UUID().uuidString
        XCTAssertThrowsError(try verify(wrong))
        wrong = receipt; wrong["pm_exit_code"] = false
        XCTAssertThrowsError(try verify(wrong))
        wrong = receipt; wrong["finished_at"] = "not-a-time"
        XCTAssertThrowsError(try verify(wrong))
        wrong = receipt; wrong["post_update"] = ["sha": String(repeating: "b", count: 40)]
        XCTAssertThrowsError(try verify(wrong))
        wrong = receipt; wrong["stages"] = [["name": "build", "outcome": "success"], ["name": "build", "outcome": "failed"]]
        XCTAssertThrowsError(try verify(wrong))
    }
    func testIndependentServiceEnvironmentAndObligationEvidenceAreRequired() throws {
        for failed in [evidence(diskCommit: "other"), evidence(boots: []), evidence(boots: ["other"]),
                       evidence(current: false), evidence(tail: true), evidence(fleet: true), evidence(services: false)] {
            XCTAssertThrowsError(try verify(receipt, evidence: failed))
        }
    }
}
