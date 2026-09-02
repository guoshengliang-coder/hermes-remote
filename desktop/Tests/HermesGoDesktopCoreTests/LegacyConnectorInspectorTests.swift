import Foundation
import XCTest
@testable import HermesGoDesktopCore

private struct FakeCommandRunner: CommandRunning {
    let status: Int32

    func run(executable: URL, arguments: [String]) -> CommandResult {
        CommandResult(status: status)
    }
}

final class LegacyConnectorInspectorTests: XCTestCase {
    func testInspectionPreservesCompatibilityAndRedactsLogs() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let install = root.appendingPathComponent(
            "Library/Application Support/Hermes Remote",
            isDirectory: true
        )
        let agents = root.appendingPathComponent("Library/LaunchAgents", isDirectory: true)
        try FileManager.default.createDirectory(at: install, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: agents, withIntermediateDirectories: true)
        try "GATEWAY_URL=wss://example.test/v1/connect\nDEVICE_ID=test-mini\nCONNECTOR_TOKEN=secret-value"
            .write(to: install.appendingPathComponent("connector.env"), atomically: true, encoding: .utf8)
        try "2026-09-02 INFO ready\n2026-09-02 ERROR password=hunter2"
            .write(to: install.appendingPathComponent("connector.log"), atomically: true, encoding: .utf8)
        try "<plist/>".write(
            to: agents.appendingPathComponent("com.hermesremote.connector.plist"),
            atomically: true,
            encoding: .utf8
        )

        let snapshot = LegacyConnectorInspector(
            homeDirectory: root,
            userID: 501,
            runner: FakeCommandRunner(status: 0)
        ).inspect()

        XCTAssertTrue(snapshot.isInstalled)
        XCTAssertTrue(snapshot.isRunning)
        XCTAssertEqual(snapshot.config.deviceID, "test-mini")
        XCTAssertEqual(snapshot.recentLogs.count, 2)
        XCTAssertFalse(snapshot.recentLogs.joined().contains("hunter2"))
        XCTAssertFalse(snapshot.recentLogs.joined().contains("secret-value"))
    }
}
