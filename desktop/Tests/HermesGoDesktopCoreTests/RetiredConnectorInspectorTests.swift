import Foundation
import XCTest
@testable import HermesGoDesktopCore

private struct FakeCommandRunner: CommandRunning {
    let status: Int32

    func run(executable: URL, arguments: [String]) -> CommandResult {
        CommandResult(status: status)
    }
}

final class RetiredConnectorInspectorTests: XCTestCase {
    func testOldDataDirectoryAloneDoesNotBlockAccountSetup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let oldDirectory = root.appendingPathComponent("Library/Application Support/Hermes Remote")
        try FileManager.default.createDirectory(at: oldDirectory, withIntermediateDirectories: true)
        let config = oldDirectory.appendingPathComponent("connector.env")
        let bytes = Data("HERMES_BASE_URL=http://untrusted.example:9119\n".utf8)
        try bytes.write(to: config)
        let snapshot = RetiredConnectorInspector(homeDirectory: root, userID: 501,
            runner: FakeCommandRunner(status: 1)).inspect()
        XCTAssertFalse(snapshot.isInstalled)
        XCTAssertFalse(snapshot.isRunning)
        XCTAssertEqual(try Data(contentsOf: config), bytes)
        let plan = DesktopBootstrapPlanner.plan(legacy: snapshot, hermesReachable: false,
            managedInstallAvailability: .ready)
        XCTAssertTrue(plan.canBegin)
        XCTAssertEqual(DesktopBootstrapPlanner.hermesStatusURL.host, "127.0.0.1")
    }

    func testLoadedRetiredJobWithoutFilesStillBlocksAccountSetup() {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let snapshot = RetiredConnectorInspector(homeDirectory: root, userID: 501,
            runner: FakeCommandRunner(status: 0)).inspect()
        XCTAssertFalse(snapshot.isInstalled)
        XCTAssertTrue(snapshot.isRunning)
        let plan = DesktopBootstrapPlanner.plan(legacy: snapshot, hermesReachable: false,
            managedInstallAvailability: .ready)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("HR-MIGRATE-022"))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    func testRetiredLaunchAgentIsDetectedAsAConflict() throws {
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

        let snapshot = RetiredConnectorInspector(
            homeDirectory: root,
            userID: 501,
            runner: FakeCommandRunner(status: 0)
        ).inspect()

        XCTAssertTrue(snapshot.isInstalled)
        XCTAssertTrue(snapshot.isRunning)
    }
}
