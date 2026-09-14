import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class ConnectionModelsTests: XCTestCase {
    func testSelectedAccountDeviceReplacesLegacyHealthWithAccountTopology() {
        let checkedAt = Date(timeIntervalSince1970: 1_725_000_000)
        let snapshot = AccountDeviceHealth.snapshot(
            for: accountDevice(),
            checkedAt: checkedAt
        )

        XCTAssertEqual(snapshot.components.map(\.component), [
            .desktopAgent, .gateway, .hermes, .endToEnd,
        ])
        XCTAssertEqual(snapshot.component(.desktopAgent).detail, "账号 Connector 在线")
        XCTAssertEqual(snapshot.component(.gateway).detail, "账号服务可用 · 18 ms")
        XCTAssertEqual(snapshot.component(.hermes).detail, "可访问 · 0.21.0")
        XCTAssertEqual(snapshot.component(.endToEnd).detail, "账号链路正常")
        XCTAssertEqual(snapshot.checkedAt, checkedAt)
        XCTAssertEqual(snapshot.overall, .healthy)
    }

    func testSelectedAccountDeviceFailuresCarryRegisteredIssues() {
        let device = accountDevice(connectorOnline: false, hermesReachable: false, endToEndHealthy: false)
        let snapshot = AccountDeviceHealth.snapshot(for: device)

        XCTAssertEqual(snapshot.overall, .needsAttention)
        XCTAssertEqual(snapshot.component(.desktopAgent).issue?.code, .connectorOffline)
        XCTAssertEqual(snapshot.component(.hermes).issue?.code, .hermesUnavailable)
        XCTAssertEqual(snapshot.component(.endToEnd).issue?.code, .relayFailure)
        XCTAssertTrue(snapshot.component(.hermes).detail.contains("HR-CONN-006"))
    }

    func testAccountPresentationRemovesLegacyEndToEndFromOverallHealth() {
        let checkedAt = Date(timeIntervalSince1970: 1_725_000_000)
        let snapshot = DesktopHealthSnapshot(components: [
            ComponentHealth(component: .desktopAgent, level: .healthy, detail: "ok"),
            ComponentHealth(component: .gateway, level: .healthy, detail: "ok"),
            ComponentHealth(component: .hermes, level: .healthy, detail: "ok"),
            ComponentHealth(component: .observer, level: .healthy, detail: "ok"),
            ComponentHealth(component: .endToEnd, level: .failed, detail: "invalid legacy token"),
        ], checkedAt: checkedAt)

        let accountPresentation = snapshot.presented(accountModeActive: true)

        XCTAssertEqual(accountPresentation.components.map(\.component), [
            .desktopAgent, .gateway, .hermes, .observer,
        ])
        XCTAssertEqual(accountPresentation.checkedAt, checkedAt)
        XCTAssertEqual(accountPresentation.overall, .healthy)
        XCTAssertEqual(snapshot.presented(accountModeActive: false), snapshot)
    }

    func testHealthyRequiresAgentGatewayAndHermes() {
        let snapshot = DesktopHealthSnapshot(components: [
            ComponentHealth(component: .desktopAgent, level: .healthy, detail: "ok"),
            ComponentHealth(component: .gateway, level: .healthy, detail: "ok"),
            ComponentHealth(component: .hermes, level: .healthy, detail: "ok"),
            ComponentHealth(component: .observer, level: .healthy, detail: "ok"),
            ComponentHealth(component: .endToEnd, level: .healthy, detail: "ok"),
        ])

        XCTAssertEqual(snapshot.overall, .healthy)
    }

    func testOptionalObserverFailureOnlyDegradesMainConnection() {
        let snapshot = DesktopHealthSnapshot(components: [
            ComponentHealth(component: .desktopAgent, level: .healthy, detail: "ok"),
            ComponentHealth(component: .gateway, level: .healthy, detail: "ok"),
            ComponentHealth(component: .hermes, level: .healthy, detail: "ok"),
            ComponentHealth(component: .observer, level: .failed, detail: "unsupported"),
            ComponentHealth(component: .endToEnd, level: .healthy, detail: "ok"),
        ])

        XCTAssertEqual(snapshot.overall, .degraded)
    }

    func testRequiredFailureNeedsAttention() {
        let snapshot = DesktopHealthSnapshot(components: [
            ComponentHealth(component: .desktopAgent, level: .healthy, detail: "ok"),
            ComponentHealth(component: .gateway, level: .failed, detail: "offline"),
            ComponentHealth(component: .hermes, level: .healthy, detail: "ok"),
        ])

        XCTAssertEqual(snapshot.overall, .needsAttention)
    }

    private func accountDevice(
        connectorOnline: Bool = true,
        hermesReachable: Bool? = true,
        endToEndHealthy: Bool? = true
    ) -> AccountDevice {
        AccountDevice(
            id: "40000000-0000-4000-8000-000000000004",
            generation: 1,
            deviceId: "hermes-macbook",
            desktopDisplayName: "Macbook-M5",
            publicKeyFingerprint: String(repeating: "a", count: 64),
            connector: .init(online: connectorOnline, lastSeenAt: "2026-09-14T12:00:00Z"),
            hermes: .init(reachable: hermesReachable, version: "0.21.0"),
            gateway: .init(latencyMs: 18),
            endToEnd: .init(healthy: endToEndHealthy, checkedAt: "2026-09-14T12:00:00Z"),
            access: "owner",
            isDefault: false
        )
    }
}
