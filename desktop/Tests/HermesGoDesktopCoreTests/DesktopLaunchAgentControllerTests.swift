import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopLaunchAgentControllerTests: XCTestCase {
    func testLegacyStopsBeforeAccountStartsAndEveryMutationUsesExactUserLabel() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, // stopLegacy: account absent, legacy present
            0, 1, // bootout succeeds, legacy absent
            1, 1, // startAccount: legacy absent, account absent
            0, 0, 1, // bootstrap succeeds, account present, legacy absent
        ])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(userID: 501, launchAgentsRoot: root, runner: runner)
        let legacy = snapshot(root: root, running: true)

        try controller.stopLegacy(snapshot: legacy)
        try controller.startAccount(
            plistURL: root.appendingPathComponent("com.hermesgo.connector.plist")
        )

        XCTAssertEqual(runner.mutations(), [
            ["bootout", "gui/501/com.hermesremote.connector"],
            ["bootstrap", "gui/501", "/tmp/test-agents/com.hermesgo.connector.plist"],
        ])
    }

    func testDuplicateStateFailsBeforeAnyMutation() throws {
        let runner = ScriptedCommandRunner(statuses: [0, 0, 1])
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: URL(fileURLWithPath: "/tmp/test-agents"),
            runner: runner
        )

        XCTAssertThrowsError(try controller.inspect()) { error in
            XCTAssertEqual(error as? DesktopLaunchAgentControllerError, .duplicateConnector)
        }
        XCTAssertTrue(runner.mutations().isEmpty)
    }

    func testStopWaitsForLaunchdToConvergeBeforeStartingManagedServices() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, // stopLegacy: account absent, legacy present
            0, 0, 1, // bootout succeeds; legacy remains visible once, then disappears
            1, 1, // startAccount: legacy absent, account absent
            0, 0, 1, // bootstrap succeeds, account present, legacy absent
        ])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: root,
            runner: runner,
            convergenceAttempts: 3,
            convergenceDelay: 0
        )

        try controller.stopLegacy(snapshot: snapshot(root: root, running: true))
        try controller.startAccount(
            plistURL: root.appendingPathComponent("com.hermesgo.connector.plist")
        )

        XCTAssertEqual(runner.mutations(), [
            ["bootout", "gui/501/com.hermesremote.connector"],
            ["bootstrap", "gui/501", "/tmp/test-agents/com.hermesgo.connector.plist"],
        ])
    }

    func testRollbackStopsOnlyAccountThenRestoresExactLegacyPlist() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, // stopAccount: legacy absent, account present
            0, 1, // bootout succeeds, account absent
            1, 1, // restoreLegacy: account absent, legacy absent
            0, 0, 1, // bootstrap succeeds, legacy present, account absent
        ])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(userID: 502, launchAgentsRoot: root, runner: runner)

        try controller.stopAccount()
        try controller.restoreLegacy(snapshot: snapshot(root: root, running: false))

        XCTAssertEqual(runner.mutations(), [
            ["bootout", "gui/502/com.hermesgo.connector"],
            ["bootstrap", "gui/502", "/tmp/test-agents/com.hermesremote.connector.plist"],
        ])
    }

    func testStartFailureNeverFallsThroughToASecondStart() throws {
        let runner = ScriptedCommandRunner(statuses: [1, 1, 1])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(userID: 501, launchAgentsRoot: root, runner: runner)

        XCTAssertThrowsError(try controller.startAccount(
            plistURL: root.appendingPathComponent("com.hermesgo.connector.plist")
        )) { error in
            XCTAssertEqual(error as? DesktopLaunchAgentControllerError, .accountStartFailed)
        }
        XCTAssertEqual(runner.mutations().count, 1)
    }

    func testHermesStartAndStopUseOnlyTheManagedServerLabel() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, 0, // absent, bootstrap succeeds, present
            0, 0, 1, // present, bootout succeeds, absent
        ])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: root,
            runner: runner
        )

        try controller.startHermes(
            plistURL: root.appendingPathComponent("com.hermesgo.hermes-server.plist")
        )
        try controller.stopHermes()

        XCTAssertEqual(runner.mutations(), [
            ["bootstrap", "gui/501", "/tmp/test-agents/com.hermesgo.hermes-server.plist"],
            ["bootout", "gui/501/com.hermesgo.hermes-server"],
        ])
    }

    private func snapshot(root: URL, running: Bool) -> LegacyConnectorSnapshot {
        LegacyConnectorSnapshot(
            isInstalled: true,
            isRunning: running,
            config: LegacyConnectorConfig(gatewayURL: nil),
            recentLogs: [],
            installDirectory: URL(fileURLWithPath: "/tmp/legacy"),
            launchAgentURL: root.appendingPathComponent("com.hermesremote.connector.plist")
        )
    }
}

private final class ScriptedCommandRunner: CommandRunning, @unchecked Sendable {
    private let lock = NSLock()
    private var statuses: [Int32]
    private var invocations: [[String]] = []

    init(statuses: [Int32]) { self.statuses = statuses }

    func run(executable: URL, arguments: [String]) -> CommandResult {
        lock.withLock {
            invocations.append(arguments)
            return CommandResult(status: statuses.isEmpty ? -1 : statuses.removeFirst())
        }
    }

    func mutations() -> [[String]] {
        lock.withLock {
            invocations.filter { $0.first == "bootout" || $0.first == "bootstrap" }
        }
    }
}
