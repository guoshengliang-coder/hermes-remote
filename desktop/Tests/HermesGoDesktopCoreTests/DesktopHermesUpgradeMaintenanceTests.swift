import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeMaintenanceTests: XCTestCase {
    func testPauseEncodingMatchesTheSharedConnectorFixtureByteForByte() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let text = try String(contentsOf: repo.appendingPathComponent("connector/fixtures/maintenance/request.json"), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        let expected = Data(text.utf8)
        let request = try JSONSerialization.jsonObject(with: expected) as! [String: Any]
        var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(f.journal())) as! [String: Any]
        object["runID"] = request["runID"]; object["sourceIdentity"] = request["sourceIdentity"]
        let journal = try JSONDecoder().decode(DesktopHermesUpgradeJournal.self, from: JSONSerialization.data(withJSONObject: object))
        try f.mailbox.prepare(); try f.mailbox.pause(journal)
        XCTAssertEqual(try Data(contentsOf: f.mailbox.directory.appendingPathComponent("request.json")), expected)
    }
    func testMaintenanceErrorHasBilingualNonRetryableRecoveryAndRedaction() {
        let issue = DesktopIssue(code: .hermesMaintenance, technicalCause: "connector_token=fixture-secret")
        XCTAssertEqual(issue.code.rawValue, "HR-HERMESUPDATE-005")
        XCTAssertTrue(issue.detailChinese.contains("维护")); XCTAssertTrue(issue.detailEnglish.contains("maintenance"))
        XCTAssertFalse(issue.retryable); XCTAssertEqual(issue.recoveryAction, .details)
        XCTAssertFalse(issue.sanitizedDiagnostic.contains("fixture-secret"))
        XCTAssertEqual(try JSONDecoder().decode(DesktopIssueCode.self, from: JSONEncoder().encode(issue.code)), issue.code)
    }
    func testConcreteLaunchdDriverDistinguishesUnknownAndUnloadedAndRefusesReusedPID() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let layout = try DesktopManagedInstallLayout(root: f.root.appendingPathComponent("managed"), launchAgentsRoot: f.root)
        for (label, data) in f.configurations { try UpgradePrivateFiles.write(data, to: f.root.appendingPathComponent(label + ".plist")) }
        let runner = MaintenanceLaunchdRunner()
        let birth = Date(timeIntervalSince1970: 1_700_000_000)
        let driver = DesktopHermesUpgradeLaunchdServices(runner: runner, layout: layout, approved: f.configurations,
            originals: f.journal().services, startDate: { _ in birth }, ready: { _ in })
        runner.status = 1; XCTAssertEqual(try driver.observe(label: f.hermes), .unknown)
        runner.status = 113; XCTAssertEqual(try driver.observe(label: f.hermes), .unloaded)
        runner.status = 0; runner.pid = 72
        do { try await driver.stop(label: f.hermes); XCTFail("reused PID accepted") } catch {}
        XCTAssertFalse(runner.calls.contains(where: { $0.first == "bootout" }))
        runner.pid = 71
        try await driver.stop(label: f.hermes)
        XCTAssertEqual(runner.calls.last?.first, "print")
        try await driver.start(label: f.hermes, configuration: f.configurations[f.hermes]!)
        XCTAssertEqual(runner.calls.filter { $0.first == "bootout" || $0.first == "bootstrap" }.map { $0.first! }, ["bootout", "bootstrap"])
        XCTAssertFalse(runner.calls.contains(where: { ["disable", "enable", "kickstart"].contains($0.first ?? "") }))
        XCTAssertThrowsError(try driver.observe(label: "ai.hermes.unknown"))
    }
    func testDriverCannotBootstrapAnOriginallyUnloadedJob() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let layout = try DesktopManagedInstallLayout(root: f.root.appendingPathComponent("managed"), launchAgentsRoot: f.root)
        for (label, data) in f.configurations { try UpgradePrivateFiles.write(data, to: f.root.appendingPathComponent(label + ".plist")) }
        let runner = MaintenanceLaunchdRunner(); runner.status = 113
        let driver = DesktopHermesUpgradeLaunchdServices(runner: runner, layout: layout, approved: f.configurations,
            originals: f.journal(hermesLoaded: false).services, startDate: { _ in Date(timeIntervalSince1970: 1_700_000_000) }, ready: { _ in })
        do { try await driver.start(label: f.hermes, configuration: f.configurations[f.hermes]!); XCTFail("originally unloaded job started") } catch {}
        XCTAssertFalse(runner.calls.contains(where: { $0.first == "bootstrap" }))
    }
    func testAJobAppearingDuringRestoreCannotBeClaimedAsOriginalUnloadedState() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let journal = f.journal(connectorLoaded: false)
        try f.snapshots.capture(journal, configurations: f.configurations)
        let restoring = try f.phase(journal, .restoringServices)
        f.driver.appearedWhenReadyLabel = f.connector
        do { try await f.restorer.restore(restoring, originalInstallation: false); XCTFail("new job ignored") } catch {}
        XCTAssertFalse(f.driver.effects.contains("stop:\(f.connector)"))
        XCTAssertFalse(f.driver.effects.contains("start:\(f.connector)"))
        XCTAssertEqual(try f.store.loadReadOnly()?.phase, .restoringServices)
    }
    func testConcreteDriverRefusesChangedPlistAndDuplicateLegacyConnector() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let layout = try DesktopManagedInstallLayout(root: f.root.appendingPathComponent("managed"), launchAgentsRoot: f.root)
        for (label, data) in f.configurations { try UpgradePrivateFiles.write(data, to: f.root.appendingPathComponent(label + ".plist")) }
        let runner = MaintenanceLaunchdRunner()
        let driver = DesktopHermesUpgradeLaunchdServices(runner: runner, layout: layout, approved: f.configurations,
            originals: f.journal().services, startDate: { _ in Date(timeIntervalSince1970: 1_700_000_000) }, ready: { _ in })
        runner.legacyStatus = 0
        do { try await driver.stop(label: f.hermes); XCTFail() } catch {}
        XCTAssertFalse(runner.calls.contains(where: { $0.first == "bootout" }))
        runner.legacyStatus = 113
        try UpgradePrivateFiles.write(Data("foreign".utf8), to: layout.hermesLaunchAgent)
        XCTAssertThrowsError(try driver.configuration(label: f.hermes))
    }
    func testConnectorLaunchAgentOptsIntoOnlyTheExplicitLocalMailbox() throws {
        let root = URL(fileURLWithPath: "/synthetic")
        func plist(_ mailbox: URL?) throws -> [String: Any] {
            let agent = DesktopAccountConnectorLaunchAgent(connectorExecutable: root.appendingPathComponent("connector"),
                credentialFile: root.appendingPathComponent("credential"), gatewayURL: URL(string: "wss://example.com/v2/connect")!,
                hermesBaseURL: URL(string: "http://127.0.0.1:9119")!, sessionTokenFile: root.appendingPathComponent("token"),
                standardOutput: root.appendingPathComponent("stdout"), standardError: root.appendingPathComponent("stderr"), maintenanceDirectory: mailbox)
            return try PropertyListSerialization.propertyList(from: agent.encodedPropertyList(), options: [], format: nil) as! [String: Any]
        }
        XCTAssertNil((try plist(nil)["EnvironmentVariables"] as? [String: String])?["HERMES_GO_MAINTENANCE_DIRECTORY"])
        XCTAssertEqual((try plist(root.appendingPathComponent("mailbox"))["EnvironmentVariables"] as? [String: String])?["HERMES_GO_MAINTENANCE_DIRECTORY"], "/synthetic/mailbox")
        XCTAssertThrowsError(try plist(URL(fileURLWithPath: "/")))
    }
    func testMailboxBindsFreshDrainedAckToRunSourceRequestPIDAndInstance() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let journal = f.journal()
        try f.mailbox.prepare(); try f.mailbox.pause(journal)
        let instance = UUID().uuidString
        let identity = DesktopHermesUpgradeMaintenanceMailbox.ConnectorIdentity(pid: 71, instanceID: instance)
        var ack = try f.ack(journal, instance: instance)
        try f.writeAck(ack)
        _ = try f.mailbox.requireDrained(journal, connector: identity)
        for (key, value) in [("pid", 72), ("instanceID", UUID().uuidString), ("runID", UUID().uuidString),
                             ("sourceIdentity", String(repeating: "f", count: 64)), ("requestSHA256", String(repeating: "e", count: 64)),
                             ("activeOperations", 1), ("paused", false), ("observedAt", Date().addingTimeInterval(-10).timeIntervalSince1970 * 1000),
                             ("observedAt", Date().addingTimeInterval(10).timeIntervalSince1970 * 1000)] as [(String, Any)] {
            ack = try f.ack(journal, instance: instance); ack[key] = value; try f.writeAck(ack)
            XCTAssertThrowsError(try f.mailbox.requireDrained(journal, connector: identity), key)
        }
    }
    func testPauseCannotBeOverwrittenByAnotherRunOrReleasedWhilePending() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let journal = f.journal(); try f.mailbox.prepare(); try f.mailbox.pause(journal)
        let before = try Data(contentsOf: f.mailbox.directory.appendingPathComponent("request.json"))
        XCTAssertThrowsError(try f.mailbox.pause(f.journal()))
        XCTAssertThrowsError(try f.mailbox.release(journal))
        XCTAssertEqual(try Data(contentsOf: f.mailbox.directory.appendingPathComponent("request.json")), before)
        try f.store.begin(journal)
        _ = try f.store.transition(runID: journal.runID, to: .cancelling)
        let cancelled = try f.store.transition(runID: journal.runID, to: .cancelled)
        try f.mailbox.release(cancelled)
        try f.mailbox.pause(f.journal())
    }
    func testVerificationReopeningRequiresLocalBootDependenciesAndRestoredServices() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        let journal = f.journal(); try f.mailbox.prepare(); try f.mailbox.pause(journal)
        let verifying = try f.phase(journal, .verifying)
        for evidence in [f.completion(boots: []), f.completion(tail: true), f.completion(fleet: true),
                         f.completion(current: false), f.completion(services: false)] {
            XCTAssertThrowsError(try f.mailbox.releaseForVerification(verifying, evidence: evidence))
        }
        try f.mailbox.releaseForVerification(verifying, evidence: f.completion())
        // Failed Cloud acceptance pauses this same run again without touching any database.
        try f.mailbox.pause(verifying)
    }
    func testUnsafeMailboxAndAckAreRefusedWithoutRepairingPermissions() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }
        try f.mailbox.prepare(); let journal = f.journal(); try f.mailbox.pause(journal)
        let url = f.mailbox.directory.appendingPathComponent("ack.json")
        try FileManager.default.createSymbolicLink(atPath: url.path, withDestinationPath: "/missing-ack")
        XCTAssertThrowsError(try f.mailbox.requireDrained(journal, connector: .init(pid: 71, instanceID: UUID().uuidString)))
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: f.mailbox.directory.path)
        XCTAssertThrowsError(try f.mailbox.prepare())
        let permissions = try FileManager.default.attributesOfItem(atPath: f.mailbox.directory.path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(permissions?.intValue, 0o755)
    }
    func testIdleEvidenceRequiresAllProfilesNoActivitiesAndIndependentWriterAdmission() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }; let journal = f.journal()
        try f.idle(journal).requireIdle(for: journal)
        for bad in [f.idle(journal, turns: 1), f.idle(journal, cron: 1), f.idle(journal, background: 1),
                    f.idle(journal, unknown: 1), f.idle(journal, complete: false), f.idle(journal, closed: false),
                    f.idle(journal, checked: []), f.idle(journal, profiles: []), f.idle(journal, checked: ["default", "default"]),
                    f.idle(journal, age: 6), f.idle(journal, age: -1), f.idle(f.journal())] {
            XCTAssertThrowsError(try bad.requireIdle(for: journal))
        }
    }
    func testServiceImageIsPrivateImmutableBoundToRunAndOutsideHermesHome() throws {
        let f = try MaintenanceFixture(); defer { f.remove() }; let journal = f.journal()
        try f.snapshots.capture(journal, configurations: f.configurations)
        try f.snapshots.capture(journal, configurations: f.configurations)
        XCTAssertEqual(try f.snapshots.configurations(journal), f.configurations)
        XCTAssertFalse(f.snapshots.root.path.hasPrefix(journal.hermesHome + "/"))
        var changed = f.configurations; changed[f.hermes] = Data("changed-private-plist".utf8)
        XCTAssertThrowsError(try f.snapshots.capture(journal, configurations: changed))
        let image = f.snapshots.root.appendingPathComponent(journal.runID.lowercased() + ".json")
        XCTAssertEqual((try FileManager.default.attributesOfItem(atPath: image.path)[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        try UpgradePrivateFiles.write(Data("{}".utf8), to: image)
        XCTAssertThrowsError(try f.snapshots.configurations(journal))
    }
    func testRestorationPreservesAllFourOriginalLoadSetsAndHermesStartsFirst() async throws {
        for hermesLoaded in [false, true] { for connectorLoaded in [false, true] {
            let f = try MaintenanceFixture(); defer { f.remove() }
            let journal = f.journal(hermesLoaded: hermesLoaded, connectorLoaded: connectorLoaded)
            try f.snapshots.capture(journal, configurations: f.configurations)
            let restoring = try f.phase(journal, .restoringServices)
            try await f.restorer.restore(restoring, originalInstallation: false)
            XCTAssertEqual(f.driver.effects, (hermesLoaded ? ["start:\(f.hermes)", "ready:\(f.target.commit)"] : [])
                + (connectorLoaded ? ["start:\(f.connector)"] : []))
            // Rebuilt adapter recognizes the restored jobs and does not bootstrap them twice.
            f.driver.effects = []
            try await f.restorer.restore(restoring, originalInstallation: false)
            XCTAssertEqual(f.driver.effects, hermesLoaded ? ["ready:\(f.target.commit)"] : [])
        } }
    }
    func testCancellationRestoresOriginalCommitAndDoesNotAdvanceTheCursor() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }; let journal = f.journal()
        try f.snapshots.capture(journal, configurations: f.configurations)
        try f.store.begin(journal); let cancelling = try f.store.transition(runID: journal.runID, to: .cancelling)
        try await f.restorer.restore(cancelling, originalInstallation: true)
        XCTAssertTrue(f.driver.effects.contains("ready:\(journal.sourceCommit)"))
        XCTAssertEqual(try f.store.loadReadOnly()?.phase, .cancelling)
    }
    func testServiceStartFailureResumesSavedLoadSetWithoutRepeatingHermesStart() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }; let journal = f.journal()
        try f.snapshots.capture(journal, configurations: f.configurations)
        let restoring = try f.phase(journal, .restoringServices)
        f.driver.failedLabel = f.connector
        do { try await f.restorer.restore(restoring, originalInstallation: false); XCTFail() } catch {}
        XCTAssertEqual(try f.store.loadReadOnly()?.phase, .restoringServices)
        f.driver.failedLabel = nil; f.driver.effects = []
        try await f.restorer.restore(restoring, originalInstallation: false)
        XCTAssertEqual(f.driver.effects, ["ready:\(f.target.commit)", "start:\(f.connector)"])
    }
    func testUnknownChangedAndNewlyLoadedServicesAreRefusedBeforeAnyRestart() async throws {
        for kind in ["unknown", "arguments", "new-owner", "file"] {
            let f = try MaintenanceFixture(); defer { f.remove() }
            let journal = f.journal(connectorLoaded: kind != "new-owner")
            try f.snapshots.capture(journal, configurations: f.configurations)
            let restoring = try f.phase(journal, .restoringServices)
            switch kind {
            case "unknown": f.driver.observations[f.connector] = .unknown
            case "file": f.driver.configurations[f.connector] = Data("foreign".utf8)
            case "arguments": f.driver.observations[f.connector] = .loaded(arguments: ["foreign"], pid: 71, startedAt: Date())
            default: f.driver.observations[f.connector] = f.driver.loaded(f.connector)
            }
            do { try await f.restorer.restore(restoring, originalInstallation: false); XCTFail(kind) } catch {}
            XCTAssertEqual(f.driver.effects, [])
        }
    }
    func testWrongBootCommitDoesNotStartConnectorOrClaimRestoreSuccess() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }; let journal = f.journal()
        try f.snapshots.capture(journal, configurations: f.configurations)
        let restoring = try f.phase(journal, .restoringServices); f.driver.readyFails = true
        do { try await f.restorer.restore(restoring, originalInstallation: false); XCTFail() } catch {}
        XCTAssertFalse(f.driver.effects.contains("start:\(f.connector)"))
        XCTAssertEqual(try f.store.loadReadOnly()?.phase, .restoringServices)
    }
    func testQuiesceRechecksIdleBeforeEachStopAndRequiresRetiredWriterProof() async throws {
        let f = try MaintenanceFixture(); defer { f.remove() }; let journal = f.journal()
        try f.snapshots.capture(journal, configurations: f.configurations)
        f.driver.observations = [f.hermes: f.driver.loaded(f.hermes), f.connector: f.driver.loaded(f.connector)]
        let counter = MaintenanceCounter()
        do {
            try await f.restorer.quiesce(journal, idleEvidence: {
                let calls = await counter.next(); return f.idle(journal, turns: calls > 1 ? 1 : 0)
            }, requireRetiredWriters: { XCTFail("writer retirement reached before idle") })
            XCTFail()
        } catch {}
        XCTAssertEqual(f.driver.effects, ["stop:\(f.connector)"])
        f.driver.effects = []
        do {
            try await f.restorer.quiesce(journal, idleEvidence: { f.idle(journal) }, requireRetiredWriters: {
                throw DesktopHermesUpgradeError.recoveryRequired
            }); XCTFail()
        } catch {}
        XCTAssertEqual(f.driver.effects, ["stop:\(f.hermes)"])
        XCTAssertEqual(try f.snapshots.configurations(journal), f.configurations)
    }
}

private final class MaintenanceLaunchdRunner: OutputCommandRunning, @unchecked Sendable {
    var status: Int32 = 0; var legacyStatus: Int32 = 113; var pid = 71
    var calls: [[String]] = []
    func run(executable: URL, arguments: [String], maximumOutputBytes: Int) -> DesktopCommandOutput {
        calls.append(arguments)
        if arguments.last?.hasSuffix("/com.hermesremote.connector") == true {
            return .init(status: legacyStatus, stdout: Data(), outputLimitExceeded: false)
        }
        if arguments.first == "bootout" { status = 113; return .init(status: 0, stdout: Data(), outputLimitExceeded: false) }
        if arguments.first == "bootstrap" { status = 0; return .init(status: 0, stdout: Data(), outputLimitExceeded: false) }
        let label = arguments.last!.split(separator: "/").last!
        let text = "\targuments = {\n\t\t/synthetic/\(label)\n\t}\n\tpid = \(pid)\n"
        return .init(status: status, stdout: Data(text.utf8), outputLimitExceeded: false)
    }
}

private actor MaintenanceCounter { var count = 0; func next() -> Int { count += 1; return count } }
private final class MaintenanceDriver: DesktopHermesUpgradeServicesOperating, @unchecked Sendable {
    var configurations: [String: Data]
    var observations: [String: DesktopHermesUpgradeServiceObservation] = [:]
    var effects: [String] = []
    var failedLabel: String?
    var readyFails = false
    var appearedWhenReadyLabel: String?
    init(_ configurations: [String: Data]) { self.configurations = configurations }
    func configuration(label: String) throws -> Data { try XCTUnwrap(configurations[label]) }
    func observe(label: String) throws -> DesktopHermesUpgradeServiceObservation { observations[label] ?? .unloaded }
    func loaded(_ label: String) -> DesktopHermesUpgradeServiceObservation {
        let object = try! PropertyListSerialization.propertyList(from: configurations[label]!, options: [], format: nil) as! [String: Any]
        return .loaded(arguments: object["ProgramArguments"] as! [String], pid: 71, startedAt: Date(timeIntervalSince1970: 1_700_000_000))
    }
    func stop(label: String) async throws { effects.append("stop:\(label)"); observations[label] = .unloaded }
    func start(label: String, configuration: Data) async throws {
        effects.append("start:\(label)"); if label == failedLabel { throw DesktopHermesUpgradeError.recoveryRequired }
        observations[label] = loaded(label)
    }
    func requireHermesReady(commit: String) async throws {
        if let label = appearedWhenReadyLabel { observations[label] = loaded(label) }
        effects.append("ready:\(commit)"); if readyFails { throw DesktopHermesUpgradeError.verificationFailed }
    }
}
private struct MaintenanceFixture: @unchecked Sendable {
    let root: URL
    let store: DesktopHermesUpgradeJournalStore
    let installation: DesktopLocalHermesInstallation
    let target: DesktopHermesUpgradeTarget
    let configurations: [String: Data]
    let driver: MaintenanceDriver
    let hermes = DesktopManagedInstallLayout.hermesLabel
    let connector = DesktopManagedInstallLayout.connectorLabel
    var mailbox: DesktopHermesUpgradeMaintenanceMailbox { .init(directory: root.appendingPathComponent("mailbox")) }
    var snapshots: DesktopHermesUpgradeServiceSnapshotStore { .init(root: root) }
    var restorer: DesktopHermesUpgradeServiceRestorer<MaintenanceDriver> { .init(snapshots: snapshots, driver: driver) }
    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("hg199-maintenance-\(UUID().uuidString)").resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        store = .init(root: root)
        installation = .init(executable: root.appendingPathComponent(".hermes/hermes-agent/venv/bin/hermes"),
            checkoutRoot: root.appendingPathComponent(".hermes/hermes-agent"), hermesHome: root.appendingPathComponent(".hermes"),
            commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
        target = try .init(version: "0.21.6", commit: String(repeating: "b", count: 40))
        configurations = try Dictionary(uniqueKeysWithValues: [DesktopManagedInstallLayout.hermesLabel, DesktopManagedInstallLayout.connectorLabel].map {
            ($0, try PropertyListSerialization.data(fromPropertyList: ["Label": $0, "ProgramArguments": ["/synthetic/\($0)"]], format: .xml, options: 0))
        })
        driver = MaintenanceDriver(configurations)
    }
    func journal(hermesLoaded: Bool = true, connectorLoaded: Bool = true) -> DesktopHermesUpgradeJournal {
        .init(target: target, installation: installation, services: [
            .init(label: hermes, wasLoaded: hermesLoaded, configurationDigest: UpgradePrivateFiles.hash(configurations[hermes]!),
                  processID: hermesLoaded ? 71 : nil, processStartedAt: hermesLoaded ? Date(timeIntervalSince1970: 1_700_000_000) : nil),
            .init(label: connector, wasLoaded: connectorLoaded, configurationDigest: UpgradePrivateFiles.hash(configurations[connector]!),
                  processID: connectorLoaded ? 71 : nil, processStartedAt: connectorLoaded ? Date(timeIntervalSince1970: 1_700_000_000) : nil)], originalChannel: "stable")
    }
    func phase(_ journal: DesktopHermesUpgradeJournal, _ phase: DesktopHermesUpgradePhase) throws -> DesktopHermesUpgradeJournal {
        try store.begin(journal)
        _ = try store.transition(runID: journal.runID, to: .backingUp)
        _ = try store.transition(runID: journal.runID, to: .updating, backup: .init(runID: journal.runID, manifestSHA256: String(repeating: "c", count: 64)))
        let restoring = try store.transition(runID: journal.runID, to: .restoringServices)
        return phase == .restoringServices ? restoring : try store.transition(runID: journal.runID, to: .verifying)
    }
    func ack(_ journal: DesktopHermesUpgradeJournal, instance: String) throws -> [String: Any] {
        ["schema": 1, "pid": 71, "instanceID": instance, "runID": journal.runID, "sourceIdentity": journal.sourceIdentity,
         "requestSHA256": UpgradePrivateFiles.hash(try Data(contentsOf: mailbox.directory.appendingPathComponent("request.json"))),
         "paused": true, "activeOperations": 0, "observedAt": Date().timeIntervalSince1970 * 1000]
    }
    func writeAck(_ ack: [String: Any]) throws { try UpgradePrivateFiles.write(JSONSerialization.data(withJSONObject: ack), to: mailbox.directory.appendingPathComponent("ack.json")) }
    func idle(_ journal: DesktopHermesUpgradeJournal, turns: Int = 0, cron: Int = 0, background: Int = 0, unknown: Int = 0,
              complete: Bool = true, closed: Bool = true, profiles: [String] = ["default"], checked: [String] = ["default"], age: Double = 0) -> DesktopHermesUpgradeIdleEvidence {
        .init(runID: journal.runID, sourceIdentity: journal.sourceIdentity, observedAt: Date().addingTimeInterval(-age),
            profiles: profiles, checkedProfiles: checked, activeTurns: turns, activeCron: cron, backgroundWork: background,
            unknownWriters: unknown, processInventoryComplete: complete, writerAdmissionClosed: closed)
    }
    func completion(boots: [String]? = nil, tail: Bool = false, fleet: Bool = false, current: Bool = true, services: Bool = true) -> DesktopHermesUpgradeCompletionEvidence {
        .init(processExitCode: 0, diskCommit: target.commit, bootCommits: boots ?? [target.commit], dependencyInputsCurrent: current,
              sourceCompletionPending: tail, fleetRestartPending: fleet, servicesMatchOriginalLoadState: services)
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
