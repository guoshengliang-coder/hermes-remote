import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopLaunchAgentControllerTests: XCTestCase {
    func testLegacyStopsBeforeAccountStartsAndEveryMutationUsesExactUserLabel() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, // stopLegacy: account absent, legacy present
            0, 0, 1, // disable and bootout succeed, legacy absent
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
            ["disable", "gui/501/com.hermesremote.connector"],
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
            0, 0, 0, 1, // disable/bootout succeed; legacy remains visible once, then disappears
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
            ["disable", "gui/501/com.hermesremote.connector"],
            ["bootout", "gui/501/com.hermesremote.connector"],
            ["bootstrap", "gui/501", "/tmp/test-agents/com.hermesgo.connector.plist"],
        ])
    }

    func testRollbackStopsOnlyAccountThenRestoresExactLegacyPlist() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, // stopAccount: legacy absent, account present
            0, 1, // bootout succeeds, account absent
            1, 1, // restoreLegacy: account absent, legacy absent
            0, 0, 0, 1, // enable/bootstrap succeed, legacy present, account absent
        ])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(userID: 502, launchAgentsRoot: root, runner: runner)

        try controller.stopAccount()
        try controller.restoreLegacy(snapshot: snapshot(root: root, running: false))

        XCTAssertEqual(runner.mutations(), [
            ["bootout", "gui/502/com.hermesgo.connector"],
            ["enable", "gui/502/com.hermesremote.connector"],
            ["bootstrap", "gui/502", "/tmp/test-agents/com.hermesremote.connector.plist"],
        ])
    }

    func testTransferredAccountActiveStateDisablesAndStopsOnlyLegacy() throws {
        let runner = ScriptedCommandRunner(statuses: [
            0, 0, // both managed labels loaded
            0, 0, // disable succeeds and legacy is loaded
            0, 1, // bootout succeeds, legacy becomes absent
            0, 0, // managed Connector and Hermes remain loaded
        ])
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: URL(fileURLWithPath: "/tmp/test-agents"),
            runner: runner
        )

        try controller.suppressTransferredLegacyForActiveManagedInstallation()

        XCTAssertEqual(runner.mutations(), [
            ["disable", "gui/501/com.hermesremote.connector"],
            ["bootout", "gui/501/com.hermesremote.connector"],
        ])
    }

    func testAccountActiveStatePersistsLegacyDisableEvenWhenLegacyIsNotLoaded() throws {
        let runner = ScriptedCommandRunner(statuses: [
            0, 0, // managed Connector and Hermes loaded
            0, 1, // disable succeeds, legacy already absent
        ])
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: URL(fileURLWithPath: "/tmp/test-agents"),
            runner: runner
        )

        try controller.suppressTransferredLegacyForActiveManagedInstallation()

        XCTAssertEqual(runner.mutations(), [
            ["disable", "gui/501/com.hermesremote.connector"],
        ])
    }

    func testLegacyStopFailureRestoresItsPersistentEnablement() throws {
        let runner = ScriptedCommandRunner(statuses: [
            1, 0, // account absent, legacy present
            0, 1, // disable succeeds, bootout fails
            0, // enable rollback succeeds
        ])
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: root,
            runner: runner
        )

        XCTAssertThrowsError(try controller.stopLegacy(snapshot: snapshot(root: root, running: true))) {
            XCTAssertEqual($0 as? DesktopLaunchAgentControllerError, .legacyStopFailed)
        }
        XCTAssertEqual(runner.mutations(), [
            ["disable", "gui/501/com.hermesremote.connector"],
            ["bootout", "gui/501/com.hermesremote.connector"],
            ["enable", "gui/501/com.hermesremote.connector"],
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

    /// Every mutation lands in the operation log with its status and launchd's own words; the
    /// convergence polls are summarised, and a start refused before launchctl says why.
    func testMutationsWaitsAndRefusalsAreLogged() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-controller-log-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let log = DesktopServiceOperationLog(url: directory.appendingPathComponent("desktop-runtime.log"))
        let runner = ScriptedCommandRunner(
            statuses: [1, 5, /* already loaded: */ 0],
            standardError: "Bootstrap failed: 5: Input/output error"
        )
        let root = URL(fileURLWithPath: "/tmp/test-agents")
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: root,
            runner: runner,
            convergenceAttempts: 1,
            convergenceDelay: 0,
            log: log
        )
        let plist = root.appendingPathComponent("com.hermesgo.hermes-server.plist")

        XCTAssertThrowsError(try controller.startHermes(plistURL: plist))
        XCTAssertThrowsError(try controller.startHermes(plistURL: plist))
        XCTAssertThrowsError(try controller.startHermes(plistURL: root.appendingPathComponent("other.plist")))

        let text = try String(contentsOf: log.url, encoding: .utf8)
        XCTAssertTrue(text.contains(
            "launchctl bootstrap gui/501 /tmp/test-agents/com.hermesgo.hermes-server.plist status=5 stderr=Bootstrap failed: 5: Input/output error"
        ), text)
        XCTAssertTrue(text.contains("start com.hermesgo.hermes-server refused reason=already-loaded"), text)
        XCTAssertTrue(text.contains("start com.hermesgo.hermes-server refused reason=invalid-plist"), text)
        XCTAssertFalse(text.contains("launchctl print"), "reads are summarised, not logged one by one")
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
    private let standardError: String?
    private var invocations: [[String]] = []

    init(statuses: [Int32], standardError: String? = nil) {
        self.statuses = statuses
        self.standardError = standardError
    }

    func run(executable: URL, arguments: [String]) -> CommandResult {
        lock.withLock {
            invocations.append(arguments)
            let status = statuses.isEmpty ? -1 : statuses.removeFirst()
            return CommandResult(status: status, standardError: status == 0 ? nil : standardError)
        }
    }

    func mutations() -> [[String]] {
        lock.withLock {
            invocations.filter { ["bootout", "bootstrap", "disable", "enable"].contains($0.first ?? "") }
        }
    }
}
