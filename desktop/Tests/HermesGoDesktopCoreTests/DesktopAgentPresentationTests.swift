import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopAgentPresentationTests: XCTestCase {
    private let checkedAt = Date(timeIntervalSince1970: 1_788_950_400)

    func testActiveManagedConnectorReplacesStoppedLegacyObservation() {
        let result = DesktopAgentPresentation.reduce(
            legacy: legacy(installed: true, running: false),
            managed: .active(
                releaseVersion: "0.3.1",
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 1
            ),
            accountVerification: .verified,
            checkedAt: checkedAt
        )

        XCTAssertEqual(result.mode, .managedVerified(releaseVersion: "0.3.1"))
        XCTAssertEqual(result.health.level, .healthy)
        XCTAssertEqual(result.health.detail, "托管 Connector 正在运行")
    }

    func testTransferredManagedConnectorStaysVisibleWhileAccountNeedsSignIn() {
        let result = DesktopAgentPresentation.reduce(
            legacy: legacy(installed: true, running: false),
            managed: .active(
                releaseVersion: "0.3.1",
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 1
            ),
            accountVerification: .unavailable,
            checkedAt: checkedAt
        )

        XCTAssertEqual(result.mode, .managedUnverified(releaseVersion: "0.3.1"))
        XCTAssertEqual(result.health.level, .degraded)
        XCTAssertEqual(result.health.detail, "托管 Connector 正在运行，账号待核验")
    }

    func testManagedBindingMismatchFailsClosedEvenWhenServicesAreLoaded() {
        let result = DesktopAgentPresentation.reduce(
            legacy: legacy(installed: true, running: false),
            managed: .active(
                releaseVersion: "0.3.1",
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 1
            ),
            accountVerification: .mismatched,
            checkedAt: checkedAt
        )

        XCTAssertEqual(result.mode, .attentionRequired)
        XCTAssertEqual(result.health.level, .failed)
    }

    func testRetiredServiceIsAConflictNotAHealthyFallback() {
        let result = DesktopAgentPresentation.reduce(
            legacy: legacy(installed: true, running: true),
            managed: .absent,
            accountVerification: .unavailable,
            checkedAt: checkedAt
        )

        XCTAssertEqual(result.mode, .attentionRequired)
        XCTAssertEqual(result.health.level, .failed)
    }

    private func legacy(installed: Bool, running: Bool) -> RetiredConnectorSnapshot {
        RetiredConnectorSnapshot(
            isInstalled: installed,
            isRunning: running)
    }
}
