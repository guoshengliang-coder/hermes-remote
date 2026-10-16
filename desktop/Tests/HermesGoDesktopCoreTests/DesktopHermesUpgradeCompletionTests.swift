import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeCompletionTests: XCTestCase {
    private let runID = "10000000-0000-4000-8000-000000000001"
    private var target: DesktopHermesUpgradeTarget { try! .init(version: "0.21.6", commit: String(repeating: "a", count: 40)) }
    private var receipt: [String: Any] {
        ["schema": 1, "update_id": runID, "correlation_id": runID, "outcome": "success",
         "finished_at": "2026-10-11T04:47:40.505360+00:00", "post_update": ["sha": target.commit, "version": target.version],
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
    private func verify(_ object: [String: Any], upstreamUpdateID: String? = nil, evidence: DesktopHermesUpgradeCompletionEvidence? = nil) throws {
        try DesktopHermesUpgradeCompletionVerifier.verify(receipt: JSONSerialization.data(withJSONObject: object),
            runID: runID, upstreamUpdateID: upstreamUpdateID ?? runID, target: target, expectedRunningServices: 1, evidence: evidence ?? self.evidence())
    }
    func testVerifiedTerminalRunAndExactBootIdentityAreAccepted() throws { try verify(receipt) }
    func testPinnedCommitWithUnknownMissingOrWrongVersionDoesNotComplete() throws {
        for identity in [["sha": target.commit], ["sha": target.commit, "version": "unknown"],
                         ["sha": target.commit, "version": "0.21.3"]] {
            var changed = receipt; changed["post_update"] = identity
            XCTAssertThrowsError(try verify(changed)) {
                XCTAssertEqual($0 as? DesktopHermesUpgradeError, .verificationFailed)
            }
        }
    }
    func testLauncherCorrelationAndUpstreamPMUpdateIDAreSeparateIdentities() throws {
        var distinct = receipt
        let upstreamID = UUID().uuidString
        distinct["update_id"] = upstreamID
        try verify(distinct, upstreamUpdateID: upstreamID)
        XCTAssertThrowsError(try verify(distinct))
    }
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
    func testReceiptRequiresAValidLauncherRunIdentity() throws {
        var unbound = receipt
        unbound["correlation_id"] = ""
        XCTAssertThrowsError(try DesktopHermesUpgradeCompletionVerifier.verify(
            receipt: JSONSerialization.data(withJSONObject: unbound), runID: "", upstreamUpdateID: runID,
            target: target, expectedRunningServices: 1, evidence: evidence()))
    }
    func testObservedOfficialFinishReceiptRemainsIncompleteDespiteSuccessfulPMAndProcess() throws {
        // Selected fields from the isolated 818c13be official finish on 2026-10-11. No real
        // credentials/history were used. The positive control below supplies a synthetic version
        // and removes debt;
        // it is parser coverage, not evidence that a real service was restored.
        let observed = Data(#"""
        {
          "schema": 1, "update_id": "go-hg199-isolated-pm-20261011", "correlation_id": null,
          "outcome": "success", "finished_at": "2026-10-11T04:47:40.505360+00:00",
          "post_update": {"sha": "818c13be1dc4fd28987e1e881a9408224afd4535"},
          "stages": [{"name": "build", "outcome": "success"}],
          "followups": [{"step": "gateway_restart", "reason": "gateways may still run pre-update code or were stopped without a successor; recover with `hermes gateway restart`", "at": "2026-10-11T04:47:40.497637+00:00"}],
          "user_action": null,
          "gateway_restart": {
            "restarted_services": [], "relaunched_profiles": [], "externally_supervised_profiles": [],
            "killed_pids": [], "failed_units": [], "incomplete": true,
            "phase_error": "[Errno 1] Operation not permitted: 'launchctl'",
            "fresh_recovery": {"requested": [], "verified": [], "relaunch_attempted": [],
              "failed": [], "skipped": [], "serve_units": {"verified": [], "failed": []}, "stale_runtimes": []}
          },
          "pm_sync_outcome": "ok", "pm_exit_code": 0, "pm_venv_rebuild": {"ok": true, "reason": ""}
        }
        """#.utf8)
        let fixedTarget = try DesktopHermesUpgradeTarget(version: "0.21.6", commit: "818c13be1dc4fd28987e1e881a9408224afd4535")
        let independentlyReady = DesktopHermesUpgradeCompletionEvidence(processExitCode: 0,
            diskCommit: fixedTarget.commit, bootCommits: [fixedTarget.commit], dependencyInputsCurrent: true,
            sourceCompletionPending: false, fleetRestartPending: false, servicesMatchOriginalLoadState: true)
        func check(_ object: [String: Any]) throws {
            try DesktopHermesUpgradeCompletionVerifier.verify(receipt: JSONSerialization.data(withJSONObject: object),
                runID: runID, upstreamUpdateID: "go-hg199-isolated-pm-20261011", target: fixedTarget,
                expectedRunningServices: 1, evidence: independentlyReady)
        }
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: observed) as? [String: Any])
        XCTAssertThrowsError(try check(object)) // Launcher correlation was absent in the experiment.
        object["correlation_id"] = runID
        XCTAssertThrowsError(try check(object)) // Matching identities still leave actual restart debt.
        object["followups"] = []
        var restart = try XCTUnwrap(object["gateway_restart"] as? [String: Any])
        restart["incomplete"] = false; restart["phase_error"] = ""
        object["gateway_restart"] = restart
        XCTAssertThrowsError(try check(object)) // Commit alone still does not prove target version.
        object["post_update"] = ["sha": fixedTarget.commit, "version": fixedTarget.version]
        try check(object) // Parser control only: no real service/version certification.
    }
}
