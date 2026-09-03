import XCTest
@testable import HermesGoDesktopCore

final class ConnectionModelsTests: XCTestCase {
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
}
