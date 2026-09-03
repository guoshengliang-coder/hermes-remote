import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHealthAssemblerTests: XCTestCase {
    func testAssemblePreservesProbeLatencyAndIssue() {
        let checkedAt = Date(timeIntervalSince1970: 1_725_000_000)
        let issue = DesktopIssue(code: .connectorOffline, technicalCause: "test")
        let snapshot = DesktopHealthAssembler.assemble(
            legacy: legacy(isInstalled: true, isRunning: true, observerEnabled: true),
            relay: ProbeResult(level: .healthy, detail: "响应正常", latencyMilliseconds: 12),
            hermes: ProbeResult(level: .degraded, detail: "服务可达，需要认证", latencyMilliseconds: 7),
            endToEnd: ProbeResult(level: .failed, detail: "连接器离线", issue: issue),
            checkedAt: checkedAt
        )

        XCTAssertEqual(snapshot.component(.desktopAgent).level, .healthy)
        XCTAssertEqual(snapshot.component(.gateway).detail, "响应正常 · 12 ms")
        XCTAssertEqual(snapshot.component(.hermes).detail, "服务可达，需要认证 · 7 ms")
        XCTAssertEqual(snapshot.component(.observer).level, .checking)
        XCTAssertEqual(snapshot.component(.endToEnd).issue, issue)
        XCTAssertEqual(snapshot.checkedAt, checkedAt)
    }

    func testAssembleReportsMissingLegacyConnectorConsistently() {
        let snapshot = DesktopHealthAssembler.assemble(
            legacy: legacy(isInstalled: false, isRunning: false, observerEnabled: true),
            relay: ProbeResult(level: .unavailable, detail: "缺少可观察的 Gateway 地址"),
            hermes: ProbeResult(level: .healthy, detail: "响应正常"),
            endToEnd: ProbeResult(level: .unavailable, detail: "尚未保存 App Token，未执行")
        )

        XCTAssertEqual(snapshot.component(.desktopAgent).level, .unavailable)
        XCTAssertEqual(snapshot.component(.desktopAgent).detail, "未检测到旧 Connector")
        XCTAssertEqual(snapshot.component(.observer).level, .unavailable)
        XCTAssertEqual(snapshot.component(.observer).detail, "未检测到旧 Connector")
        XCTAssertEqual(snapshot.overall, .needsAttention)
    }

    func testDisabledObserverDoesNotPretendToBeChecking() {
        let snapshot = DesktopHealthAssembler.assemble(
            legacy: legacy(isInstalled: true, isRunning: false, observerEnabled: false),
            relay: ProbeResult(level: .healthy, detail: "ok"),
            hermes: ProbeResult(level: .healthy, detail: "ok"),
            endToEnd: ProbeResult(level: .healthy, detail: "ok")
        )

        XCTAssertEqual(snapshot.component(.desktopAgent).level, .failed)
        XCTAssertEqual(snapshot.component(.observer).level, .unavailable)
        XCTAssertEqual(snapshot.component(.observer).detail, "现有配置已关闭")
    }

    private func legacy(
        isInstalled: Bool,
        isRunning: Bool,
        observerEnabled: Bool
    ) -> LegacyConnectorSnapshot {
        LegacyConnectorSnapshot(
            isInstalled: isInstalled,
            isRunning: isRunning,
            config: LegacyConnectorConfig(gatewayURL: nil, observerEnabled: observerEnabled),
            recentLogs: [],
            installDirectory: URL(fileURLWithPath: "/tmp/hermes-remote"),
            launchAgentURL: URL(fileURLWithPath: "/tmp/hermes-remote.plist")
        )
    }
}
