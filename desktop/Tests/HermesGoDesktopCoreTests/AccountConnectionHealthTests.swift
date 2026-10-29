import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class AccountConnectionHealthTests: XCTestCase {
    func testCongestionAndControlLossAreDegradedAndDoNotReportHermesFailure() throws {
        for control in ["suspect", "recovering", "lost"] {
            let health = try JSONDecoder().decode(AccountConnectionHealth.self, from: Data("""
            {"version":1,"business":"normal","control":"\(control)","local":"healthy","localReason":"ok","healthAgeMs":10}
            """.utf8))
            XCTAssertEqual(health.level, .degraded)
            XCTAssertTrue(health.summary.contains("可继续聊天"))
            XCTAssertFalse(health.summary.contains("HR-CONN-006"))
        }
    }
    func testLocalFailureAndExpiredReportAreDistinct() {
        let failed = AccountConnectionHealth(version: 1, business: "normal", control: "normal", local: "unhealthy", localReason: "auth", healthAgeMs: 0)
        XCTAssertEqual(failed.level, .failed)
        XCTAssertTrue(failed.summary.contains("HR-CONN-006"))
        let stale = AccountConnectionHealth(version: 1, business: "normal", control: "normal", local: "unknown", localReason: "ok", healthAgeMs: 60_000)
        XCTAssertEqual(stale.level, .unavailable)
        XCTAssertTrue(stale.summary.contains("待刷新"))
    }
    func testOptInFlagIsAllowlistedAndRejectsOtherValues() throws {
        XCTAssertEqual(try DesktopConnectorFileRate.overrides(from: ["CONNECTOR_TRANSPORT_ISOLATION": "1", "NODE_OPTIONS": "--inspect"]),
                       ["CONNECTOR_TRANSPORT_ISOLATION": "1"])
        XCTAssertThrowsError(try DesktopConnectorFileRate.overrides(from: ["CONNECTOR_TRANSPORT_ISOLATION": "true"]))
        XCTAssertTrue(try DesktopConnectorFileRate.overrides(from: [:]).isEmpty)
    }
}
