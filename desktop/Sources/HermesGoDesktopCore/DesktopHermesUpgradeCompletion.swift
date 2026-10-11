import Foundation

/// Exit 0 and "success" are upstream commit outcomes; neither says the owed tail or fleet restart
/// finished. Verify the exact run's bounded receipt together with independent disk/boot evidence.
public struct DesktopHermesUpgradeCompletionEvidence: Sendable {
    public let processExitCode: Int32
    public let diskCommit: String
    public let bootCommits: [String]
    public let dependencyInputsCurrent: Bool
    public let sourceCompletionPending: Bool
    public let fleetRestartPending: Bool
    public let servicesMatchOriginalLoadState: Bool
    public init(processExitCode: Int32, diskCommit: String, bootCommits: [String], dependencyInputsCurrent: Bool,
                sourceCompletionPending: Bool, fleetRestartPending: Bool, servicesMatchOriginalLoadState: Bool) {
        self.processExitCode = processExitCode; self.diskCommit = diskCommit; self.bootCommits = bootCommits
        self.dependencyInputsCurrent = dependencyInputsCurrent; self.sourceCompletionPending = sourceCompletionPending
        self.fleetRestartPending = fleetRestartPending; self.servicesMatchOriginalLoadState = servicesMatchOriginalLoadState
    }
}
public enum DesktopHermesUpgradeCompletionVerifier {
    private struct Receipt: Decodable {
        let schema: Int
        let update_id: String
        let correlation_id: String
        let outcome: String
        let finished_at: String
        let exit_code: Int?
        let post_update: Identity
        let stages: [Stage]
        let followups: [Followup]?
        let user_action: Followup?
        let pending_manual_serves: [Followup]?
        let gateway_restart: Restart
        let pm_sync_outcome: String
        let pm_exit_code: Int
        let pm_venv_rebuild: Rebuild
    }
    private struct Identity: Decodable { let sha: String }
    private struct Stage: Decodable { let name: String; let outcome: String }
    private struct Followup: Decodable {}
    private struct Rebuild: Decodable { let ok: Bool }
    private struct Restart: Decodable {
        let incomplete: Bool
        let failed_units: [String]
        let phase_error: String
        let fresh_recovery: Recovery?
    }
    private struct Recovery: Decodable {
        let failed: [String]
        let stale_runtimes: [Followup]
        let serve_units: Units
    }
    private struct Units: Decodable { let failed: [String] }

    public static func verify(receipt data: Data, runID: String, upstreamUpdateID: String, target: DesktopHermesUpgradeTarget,
                              expectedRunningServices: Int, evidence: DesktopHermesUpgradeCompletionEvidence) throws {
        guard data.count <= 512 * 1024, expectedRunningServices >= 0,
              let receipt = try? JSONDecoder().decode(Receipt.self, from: data),
              receipt.schema == 1, !upstreamUpdateID.isEmpty,
              receipt.update_id == upstreamUpdateID, receipt.correlation_id == runID,
              receipt.outcome == "success", validTimestamp(receipt.finished_at),
              receipt.exit_code == nil || receipt.exit_code == 0,
              receipt.post_update.sha == target.commit,
              receipt.stages.last(where: { $0.name == "build" })?.outcome == "success",
              (receipt.followups ?? []).isEmpty, receipt.user_action == nil,
              (receipt.pending_manual_serves ?? []).isEmpty,
              !receipt.gateway_restart.incomplete, receipt.gateway_restart.failed_units.isEmpty,
              receipt.gateway_restart.phase_error.isEmpty,
              receipt.gateway_restart.fresh_recovery?.failed.isEmpty != false,
              receipt.gateway_restart.fresh_recovery?.stale_runtimes.isEmpty != false,
              receipt.gateway_restart.fresh_recovery?.serve_units.failed.isEmpty != false,
              receipt.pm_sync_outcome == "ok", receipt.pm_exit_code == 0, receipt.pm_venv_rebuild.ok,
              evidence.processExitCode == 0, evidence.diskCommit == target.commit,
              evidence.bootCommits.count == expectedRunningServices,
              evidence.bootCommits.allSatisfy({ $0 == target.commit }), evidence.dependencyInputsCurrent,
              !evidence.sourceCompletionPending, !evidence.fleetRestartPending,
              evidence.servicesMatchOriginalLoadState
        else { throw DesktopHermesUpgradeError.verificationFailed }
    }
    private static func validTimestamp(_ text: String) -> Bool {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if formatter.date(from: text) != nil { return true }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: text) != nil
    }
}
