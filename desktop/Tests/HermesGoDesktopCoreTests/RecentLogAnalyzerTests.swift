import XCTest
@testable import HermesGoDesktopCore

final class RecentLogAnalyzerTests: XCTestCase {
    func testHistoricalWarningsDoNotBecomeCurrentHealthFailures() {
        let summary = RecentLogAnalyzer.summarize([
            "Gateway ready",
            "Local Hermes HTTP error response_chunk_ack_timeout",
            "request failed",
            "heartbeat ok",
        ])

        XCTAssertEqual(summary.warningCount, 2)
    }
}
