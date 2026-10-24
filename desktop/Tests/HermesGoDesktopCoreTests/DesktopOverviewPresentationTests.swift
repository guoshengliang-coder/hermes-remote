import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopOverviewPresentationTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_792_800_000)

    func testHealthyRequiresAllThreeAndFreshLocalRemoteVerification() {
        let result = make()
        XCTAssertEqual(result.title, "本机与远程访问均正常")
        XCTAssertEqual(result.level, .healthy)
        XCTAssertEqual(result.remoteAccess, "已通过")
    }
    func testPublicCloudHealthDoesNotProveAccountOrDesktopHealth() {
        let result = make(accountIssue: true)
        XCTAssertEqual(result.cloud.level, .degraded)
        XCTAssertEqual(result.accountService, "尚未确认")
        XCTAssertNotEqual(result.desktop.level, .healthy)
        XCTAssertNotEqual(result.level, .healthy)
    }
    func testSelectedHealthyRemoteMacCannotMaskOfflineLocalBinding() {
        let result = make(online: false, selectedRemote: true)
        XCTAssertEqual(result.cloud.level, .healthy)
        XCTAssertEqual(result.desktop.level, .failed)
        XCTAssertEqual(result.hermes.level, .healthy)
        XCTAssertEqual(result.title, "本机可用，远程访问中断")
    }
    func testStaleAndFutureEvidenceCannotProduceGreenStatus() {
        for age in [181.0, -20.0] {
            let result = make(age: age)
            XCTAssertEqual(result.cloud.level, .checking)
            XCTAssertEqual(result.desktop.level, .checking)
            XCTAssertEqual(result.hermes.level, .checking)
            XCTAssertNotEqual(result.level, .healthy)
        }
    }
    func testStaleAccountSnapshotCannotKeepRemoteConnectionGreen() {
        let result = make(accountAge: 181)
        XCTAssertEqual(result.cloud.level, .healthy)
        XCTAssertEqual(result.hermes.level, .healthy)
        XCTAssertNotEqual(result.desktop.level, .healthy)
        XCTAssertEqual(result.remoteConnection, "尚未确认")
    }
    func testStaleEndToEndReportIsNotSuccessfulRemoteAccess() {
        let result = make(reportAge: 181)
        XCTAssertEqual(result.desktop.level, .healthy)
        XCTAssertEqual(result.remoteAccess, "尚未验证")
        XCTAssertEqual(result.title, "本机可用，远程状态待确认")
    }
    func testFailedEndToEndReportHasConcreteImpact() {
        let result = make(remoteHealthy: false)
        XCTAssertEqual(result.remoteAccess, "未通过")
        XCTAssertEqual(result.desktop.level, .failed)
        XCTAssertEqual(result.title, "本机可用，远程访问中断")
    }
    func testLocalHermesFailureCannotBeMaskedByCloudReport() {
        let result = make(localLevel: .failed)
        XCTAssertEqual(result.hermes.level, .failed)
        XCTAssertEqual(result.title, "本机 Hermes 暂不可用")
    }
    func testInstalledFilesAndMissingProbeRemainUnverified() {
        let result = make(localLevel: nil)
        XCTAssertNotEqual(result.hermes.level, .healthy)
        XCTAssertNotEqual(result.level, .healthy)
    }
    func testSignedOutAndExpiredOfferLoginWithoutClaimingServicesStopped() {
        for state: DesktopAccountState in [.signedOut, .needsSignIn(.accountSessionExpired)] {
            let result = make(state: state)
            XCTAssertTrue(result.needsLogin)
            XCTAssertEqual(result.cloud.level, .healthy)
            XCTAssertEqual(result.hermes.level, .healthy)
            XCTAssertEqual(result.accountService, "登录后验证")
            XCTAssertTrue(result.title.contains("登录"))
            XCTAssertFalse(result.title.contains("中断"))
        }
    }
    func testManageOnlyAndBusyDoNotClaimBackgroundFailure() {
        let manager = make(manageOnly: true)
        XCTAssertEqual(manager.desktop.title, "仅用于管理")
        XCTAssertEqual(manager.background, "未启用")
        let busy = make(busy: true)
        XCTAssertEqual(busy.desktop.level, .checking)
        XCTAssertNotEqual(busy.level, .healthy)
    }
    func testBackgroundIdentityUnverifiedNeverBecomesHealthyFromAccountAlone() {
        XCTAssertEqual(make(unverified: true).desktop.level, .degraded)
    }
    func testCloudFailureDoesNotChangeLocalHermesUsability() {
        let result = make(cloudLevel: .failed)
        XCTAssertEqual(result.hermes.level, .healthy)
        XCTAssertEqual(result.cloud.title, "无法连接")
        XCTAssertEqual(result.title, "本机可用，远程访问中断")
    }

    private func make(online: Bool = true, selectedRemote: Bool = false,
                      cloudLevel: HealthLevel = .healthy, localLevel: HealthLevel? = .healthy,
                      age: TimeInterval = 0, accountAge: TimeInterval = 0, reportAge: TimeInterval = 0,
                      accountIssue: Bool = false, remoteHealthy: Bool = true,
                      state: DesktopAccountState? = nil, manageOnly: Bool = false,
                      busy: Bool = false, unverified: Bool = false) -> DesktopOverviewPresentation {
        let checked = now.addingTimeInterval(-age)
        let report = ISO8601DateFormatter().string(from: now.addingTimeInterval(-reportAge))
        let localHealth = localLevel.map { ComponentHealth(component: .hermes, level: $0, detail: "fixture", checkedAt: checked) }
        let binding = ActiveAccountBinding(id: "local", generation: 1, deviceId: "local-mac",
            desktopDisplayName: "This Mac", publicKeyFingerprint: "fixture", connector: .init(online: online, lastSeenAt: report),
            hermes: .init(reachable: true, version: "1"), gateway: .init(latencyMs: 10),
            endToEnd: .init(healthy: remoteHealthy, checkedAt: report))
        let remote = AccountDevice(id: "other", generation: 1, deviceId: "other-mac", desktopDisplayName: "Other Mac",
            publicKeyFingerprint: "other", connector: .init(online: true, lastSeenAt: report), hermes: binding.hermes,
            gateway: binding.gateway, endToEnd: .init(healthy: true, checkedAt: report), access: "owner", isDefault: true)
        let dashboard = AccountDashboard(session: AccountSessionRecord(
            account: HermesAccount(id: "account", displayName: "Owner", email: "owner@example.invalid", avatarUrl: nil),
            installation: AccountInstallation(id: "app", kind: "desktop", platform: "macos", displayName: "This Mac"),
            session: AccountSessionTokens(accessToken: "fixture", accessExpiresAt: "2099-01-01T00:00:00Z",
                refreshToken: "fixture", refreshExpiresAt: "2099-01-01T00:00:00Z")),
            binding: AccountBindingSnapshot(state: "bound", id: nil, generation: nil, deviceId: nil, displayName: nil,
                expiresAt: nil, keyProved: nil, healthVerified: nil, binding: binding, previousBinding: nil),
            installations: [], devices: selectedRemote ? [remote] : [], selectedDeviceID: selectedRemote ? remote.deviceId : nil)
        return DesktopOverviewPresentation(
            gateway: ComponentHealth(component: .gateway, level: cloudLevel, detail: "fixture", checkedAt: checked),
            agent: DesktopAgentPresentation(mode: unverified ? .managedUnverified(releaseVersion: "1") : .managedVerified(releaseVersion: "1"),
                health: ComponentHealth(component: .desktopAgent, level: .healthy, detail: "fixture", checkedAt: checked)),
            localHermes: DesktopLocalHermesPresentation(detection: .absent(hermesDataPresent: false), health: localHealth, hasManagedInstallation: true, now: now),
            localHealth: localHealth, accountState: state ?? .signedIn(dashboard), accountCheckedAt: now.addingTimeInterval(-accountAge),
            hasAccountIssue: accountIssue, manageOnly: manageOnly, operationInProgress: busy, now: now)
    }
}
