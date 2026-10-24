import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopDiagnosticsPresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_792_800_000)

    func testAllSevenChecksAgreeBeforeShowingAllPassed() {
        let value = make()
        XCTAssertEqual(value.checks.count, 7)
        XCTAssertEqual(value.passedCount, 7)
        XCTAssertEqual(value.summary, "7 项检查全部通过")
    }
    func testHealthyCloudCannotMaskFailedLocalHermesOrRepairRequirement() {
        let value = make(local: .failed, repair: true)
        XCTAssertEqual(value.problemCount, 2)
        XCTAssertEqual(value.checks.first { $0.id == .reportedHermes }?.health.level, .healthy)
        XCTAssertEqual(value.checks.first { $0.id == .localHermes }?.health.level, .failed)
        XCTAssertTrue(value.summary.contains("2 项需要处理"))
    }
    func testStaleAndFutureCloudReportsRemainPendingWithOriginalTimes() {
        for age in [181.0, -10.0] {
            let value = make(reportAge: age)
            XCTAssertEqual(value.pendingCount, 2)
            XCTAssertEqual(value.problemCount, 0)
            XCTAssertEqual(value.checks.last?.health.checkedAt, now.addingTimeInterval(-age))
        }
    }
    func testStaleLocalEvidenceCannotBeOverriddenByHealthyAccount() {
        let value = make(localAge: 181)
        XCTAssertEqual(value.pendingCount, 3)
        XCTAssertEqual(value.passedCount, 4)
    }
    func testFailedAccountInvalidatesAllRemoteEvidence() {
        let value = make(accountIssue: DesktopIssue(code: .accountSessionExpired))
        XCTAssertEqual(value.problemCount, 1)
        XCTAssertEqual(value.pendingCount, 3)
        XCTAssertFalse(value.summary.contains("全部通过"))
    }
    func testMissingLocalBindingDoesNotUseSelectedRemoteDevice() {
        let value = make(bound: false)
        XCTAssertEqual(value.pendingCount, 3)
        XCTAssertEqual(value.checks.first { $0.id == .connector }?.health.level, .unavailable)
    }
    func testLogFilteringCombinesSourceQueryAndWarningAndRedactsCopyInput() {
        let lines = ["[connector.log] 2026-09-01 ERROR connector_token=private-value", "[connector.error.log] connection failed", "[desktop-runtime.log] ready", "[connector.log] connected"]
        let result = RecentLogAnalyzer.filter(lines, source: "connector.log", query: "ERROR", warningsOnly: true)
        XCTAssertEqual(result.count, 1)
        XCTAssertFalse(result[0].contains("private-value"))
        XCTAssertEqual(RecentLogAnalyzer.filter(lines, source: "desktop-runtime.log", warningsOnly: true), [])
        XCTAssertEqual(RecentLogAnalyzer.filter(lines, query: "CONNECTED").count, 1)
        XCTAssertEqual(RecentLogAnalyzer.filter([], query: "anything"), [])
    }

    private func make(local: HealthLevel = .healthy, repair: Bool = false, reportAge: TimeInterval = 0,
                      localAge: TimeInterval = 0, accountIssue: DesktopIssue? = nil, bound: Bool = true) -> DesktopDiagnosticsPresentation {
        let checked = now.addingTimeInterval(-localAge)
        let date = ISO8601DateFormatter().string(from: now.addingTimeInterval(-reportAge))
        let binding = ActiveAccountBinding(id: "local", generation: 1, deviceId: "local-mac", desktopDisplayName: "This Mac",
            publicKeyFingerprint: "fixture", connector: .init(online: true, lastSeenAt: date),
            hermes: .init(reachable: true, version: "1"), gateway: .init(latencyMs: 10), endToEnd: .init(healthy: true, checkedAt: date))
        let device = AccountDevice(id: bound ? "local" : "remote", generation: 1, deviceId: bound ? "local-mac" : "remote-mac", desktopDisplayName: "Mac",
            publicKeyFingerprint: "fixture", connector: binding.connector, hermes: binding.hermes, gateway: binding.gateway,
            endToEnd: binding.endToEnd, access: "owner", isDefault: true)
        let dashboard = AccountDashboard(session: AccountSessionRecord(
            account: HermesAccount(id: "account", displayName: "Owner", email: "owner@example.invalid", avatarUrl: nil),
            installation: AccountInstallation(id: "app", kind: "desktop", platform: "macos", displayName: "This Mac"),
            session: AccountSessionTokens(accessToken: "fixture", accessExpiresAt: "2099-01-01T00:00:00Z", refreshToken: "fixture", refreshExpiresAt: "2099-01-01T00:00:00Z")),
            binding: AccountBindingSnapshot(state: bound ? "bound" : "unbound", id: nil, generation: nil, deviceId: nil, displayName: nil,
                expiresAt: nil, keyProved: nil, healthVerified: nil, binding: bound ? binding : nil, previousBinding: nil),
            installations: [], devices: [device], selectedDeviceID: device.deviceId)
        let probe = ComponentHealth(component: .hermes, level: local, detail: "fixture", checkedAt: checked)
        return DesktopDiagnosticsPresentation(gateway: .init(component: .gateway, level: .healthy, detail: "fixture", checkedAt: checked),
            agent: .init(mode: .managedVerified(releaseVersion: "1"), health: .init(component: .desktopAgent, level: .healthy, detail: "fixture", checkedAt: checked)),
            localHermes: .init(detection: nil, health: probe, hasManagedInstallation: true, now: now), localHealth: probe,
            accountState: .signedIn(dashboard), accountCheckedAt: now, accountIssue: accountIssue, repairNeeded: repair, now: now)
    }
}
