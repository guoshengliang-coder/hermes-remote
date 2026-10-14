import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeMachineTests: XCTestCase {
    func testObservedUnownedProcessBlocksBeforeAnyStopDespiteInjectedIdleProof() async throws {
        let f = try MachineFixture(inspector: MachineInspection(mode: .unowned)); defer { f.remove() }
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(try f.migration.hermesUpgradeJournal.loadReadOnly()?.phase, .quiescing)
        XCTAssertEqual(try f.mode(), "pause")
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("stop:") })
    }
    func testObservedOriginalHermesSurvivorBlocksAfterServiceStopBeforeBackup() async throws {
        let f = try MachineFixture(inspector: MachineInspection(mode: .originalSurvivor)); defer { f.remove() }
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .quiescing)
        XCTAssertEqual(f.environment.events.filter { $0.hasPrefix("stop:") }.count, 2)
        XCTAssertNil(journal.backup)
        XCTAssertFalse(f.environment.events.contains("update"))
    }
    func testObservedWriterAppearingDuringBackupKeepsBackingUpCursor() async throws {
        let f = try MachineFixture(inspector: MachineInspection(mode: .backupWriter)); defer { f.remove() }
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .backingUp)
        XCTAssertNil(journal.backup)
        XCTAssertFalse(f.environment.events.contains("update"))
    }
    func testObservedWriterBeforeSourceMoveRetainsVerifiedBackupAndDoesNotInvokeWorker() async throws {
        let f = try MachineFixture(inspector: MachineInspection(mode: .updateWriter)); defer { f.remove() }
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .updating)
        try f.backups.verify(XCTUnwrap(journal.backup))
        XCTAssertFalse(f.environment.events.contains("update"))
        XCTAssertEqual(f.environment.disk, f.installation.commit)
    }
    func testComposedTransactionBacksUpRestoresVerifiesAndReleasesLock() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        let result = try await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .completed)
        try f.backups.verify(XCTUnwrap(result.backup))
        XCTAssertEqual(try f.mode(), "release")
        XCTAssertEqual(f.environment.events.filter { $0.hasPrefix("stop:") }, ["stop:connector", "stop:hermes"])
        XCTAssertEqual(f.environment.events.filter { $0.hasPrefix("start:") }, ["start:hermes", "start:connector"])
        XCTAssertEqual(f.environment.events.filter { $0 == "update" }.count, 1)
        XCTAssertEqual(f.environment.events.filter { $0 == "release-lock" }.count, 1)
        XCTAssertEqual(f.environment.events.filter { $0 == "business" }.count, 1)
        XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("history.txt")), "old\nnew\n")
        XCTAssertEqual(try String(contentsOf: f.backups.root.appendingPathComponent(result.runID + "/home/history.txt")), "old\n")
    }
    func testLaterRunCanStartAfterTerminalCursorAndArchivesOriginalReferences() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        let old = DesktopHermesUpgradeJournal(runID: UUID().uuidString, target: f.target, installation: f.installation,
            services: f.environment.originals, originalChannel: "stable", upstreamUpdateID: UUID().uuidString)
        try f.migration.hermesUpgradeJournal.begin(old)
        try f.migration.hermesUpgradeJournal.transition(runID: old.runID, to: .cancelling)
        let cancelled = try f.migration.hermesUpgradeJournal.transition(runID: old.runID, to: .cancelled)
        let result = try await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .completed)
        XCTAssertNotEqual(result.runID, old.runID)
        let archived = f.root.appendingPathComponent("state/hermes-upgrade-runs/" + old.runID.lowercased() + ".json")
        XCTAssertEqual(try JSONDecoder().decode(DesktopHermesUpgradeJournal.self, from: Data(contentsOf: archived)), cancelled)
        try f.backups.verify(XCTUnwrap(result.backup))
    }
    func testRunRootCannotBeHermesHomeWithDifferentDirectoryURLSpelling() async throws {
        let f = try MachineFixture(runRootInsideHome: true); defer { f.remove() }
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertNil(try f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertTrue(f.environment.configurations.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.mailbox.directory.path))
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("stop:") })
    }
    func testUncertifiedMachineAdapterHasNoEffects() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        let c = DesktopHermesUpgradeCoordinator(migration: f.migration, operations: f.operations)
        do { _ = try await c.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity); XCTFail() }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        XCTAssertTrue(f.environment.events.isEmpty)
        XCTAssertNil(try f.migration.hermesUpgradeJournal.loadReadOnly())
    }
    func testPlanRaceIsRejectedBeforeJournalPauseOrServiceStop() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.changePlan = true
        do { _ = try await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity); XCTFail() }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        XCTAssertNil(try f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.mailbox.directory.path))
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("stop:") })
        XCTAssertTrue(f.environment.events.contains("release-lock"))
    }
    func testConnectorDrainDoesNotSubstituteForIndependentWriterEvidence() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.unknownWriter = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .quiescing)
        XCTAssertEqual(try f.mode(), "pause")
        XCTAssertTrue(FileManager.default.fileExists(atPath: f.mailbox.directory.appendingPathComponent("ack.json").path))
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("stop:") })
        XCTAssertNil(journal.backup)
    }
    func testMissingExternalClosureDoesNotStopServices() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.closureAvailable = false
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(try f.migration.hermesUpgradeJournal.loadReadOnly()?.phase, .quiescing)
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("stop:") })
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.mailbox.directory.path))
    }
    func testCancellationRestoresOriginalBootAndChecksBusinessBeforeCancelled() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        let coordinator = f.coordinator
        f.environment.afterHermesStop = { await coordinator.requestCancellation() }
        let result = try await coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .cancelled)
        XCTAssertNil(result.backup)
        XCTAssertEqual(try f.mode(), "release")
        XCTAssertFalse(f.environment.events.contains("update"))
        XCTAssertTrue(f.environment.events.contains("ready:" + f.installation.commit))
        XCTAssertTrue(f.environment.events.contains("business-original"))
        XCTAssertEqual(f.environment.disk, f.installation.commit)
    }
    func testCancellationBusinessFailureRepausesAndRetriesWithoutUpdatingOrReplacingHistory() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        let coordinator = f.coordinator
        f.environment.businessFails = true
        f.environment.afterHermesStop = { await coordinator.requestCancellation() }
        _ = try? await coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .cancelling)
        XCTAssertEqual(try f.mode(), "pause")
        XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("history.txt")), "old\nnew\n")
        f.environment.businessFails = false
        let result = try await f.coordinator.resume(runID: journal.runID)
        XCTAssertEqual(result.phase, .cancelled)
        XCTAssertEqual(try f.mode(), "release")
        XCTAssertFalse(f.environment.events.contains("update"))
        XCTAssertEqual(f.environment.events.filter { $0.hasPrefix("start:") }.count, 2)
        XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("history.txt")), "old\nnew\nnew\n")
    }
    func testCancellationWrongBootCannotReleaseAdmission() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        let coordinator = f.coordinator
        f.environment.wrongCompletionBoot = true
        f.environment.afterHermesStop = { await coordinator.requestCancellation() }
        _ = try? await coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(try f.migration.hermesUpgradeJournal.loadReadOnly()?.phase, .cancelling)
        XCTAssertEqual(try f.mode(), "pause")
        XCTAssertFalse(f.environment.events.contains("business-original"))
    }
    func testInvalidOfficialProfileInventoryIsRejectedBeforeWorkerAcquisition() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.invalidPlan = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertNil(try f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertTrue(f.environment.configurations.isEmpty)
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("stop:") })
    }
    func testResumeUsesOriginalWorkerConfigurationAndTargetFinish() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.interruptUpdate = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .updating)
        f.environment.interruptUpdate = false
        let result = try await f.coordinator.resume(runID: journal.runID)
        XCTAssertEqual(result.phase, .completed)
        XCTAssertEqual(result.upstreamUpdateID, journal.upstreamUpdateID)
        let configs = f.environment.configurations
        XCTAssertEqual(configs.count, 2)
        XCTAssertEqual(try configs[0].json(), try configs[1].json())
        XCTAssertEqual(f.environment.events.filter { $0 == "update" }.count, 1)
        XCTAssertEqual(f.environment.events.filter { $0 == "resume-finish" }.count, 1)
    }
    func testBusinessFailureRepausesRetainsNewHistoryAndResumesVerificationOnly() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.businessFails = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(journal.phase, .verifying)
        XCTAssertEqual(try f.mode(), "pause")
        XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("history.txt")), "old\nnew\n")
        f.environment.businessFails = false
        let resumed = try await f.coordinator.resume(runID: journal.runID)
        XCTAssertEqual(resumed.phase, .completed)
        XCTAssertEqual(f.environment.events.filter { $0 == "update" }.count, 1)
        XCTAssertEqual(f.environment.events.filter { $0.hasPrefix("start:") }.count, 2)
        XCTAssertEqual(try String(contentsOf: f.home.appendingPathComponent("history.txt")), "old\nnew\nnew\n")
        try f.backups.verify(XCTUnwrap(journal.backup))
    }
    func testReceiptDebtPreventsAdmissionReleaseAndBusinessCheck() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.receiptDebt = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(try f.migration.hermesUpgradeJournal.loadReadOnly()?.phase, .verifying)
        XCTAssertEqual(try f.mode(), "pause")
        XCTAssertFalse(f.environment.events.contains("business"))
    }
    func testDamagedArchiveStopsBeforeAnyServiceRestoration() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.damageBackup = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        XCTAssertEqual(try f.migration.hermesUpgradeJournal.loadReadOnly()?.phase, .restoringServices)
        XCTAssertFalse(f.environment.events.contains { $0.hasPrefix("start:") })
        XCTAssertEqual(try f.mode(), "pause")
    }
    func testRetainedConfigurationCorruptionDoesNotAcquireAnotherWorker() async throws {
        let f = try MachineFixture(); defer { f.remove() }
        f.environment.interruptUpdate = true
        _ = try? await f.coordinator.start(target: f.target, confirmedIdentity: f.installation.runtimeIdentity)
        let journal = try XCTUnwrap(f.migration.hermesUpgradeJournal.loadReadOnly())
        let path = f.runs.appendingPathComponent(journal.runID.lowercased() + "/worker-configuration.json")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: UpgradePrivateFiles.read(path, limit: 65536)) as? [String: Any])
        object["upstreamUpdateID"] = UUID().uuidString
        try UpgradePrivateFiles.write(JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), to: path)
        do { _ = try await f.coordinator.resume(runID: journal.runID); XCTFail() }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        XCTAssertEqual(f.environment.configurations.count, 1)
        XCTAssertEqual(try f.migration.hermesUpgradeJournal.loadReadOnly(), journal)
    }
}

private struct MachineFixture {
    let root: URL; let home: URL; let runs: URL
    let migration: DesktopMigrationJournalStore
    let installation: DesktopLocalHermesInstallation; let target: DesktopHermesUpgradeTarget
    let mailbox: DesktopHermesUpgradeMaintenanceMailbox; let backups: DesktopHermesUpgradeBackupStore
    let environment: MachineEnvironment
    let operations: DesktopHermesUpgradeMachineOperations<MachineEnvironment, MachineEnvironment>
    var coordinator: DesktopHermesUpgradeCoordinator {
        .init(migration: migration, operations: operations,
              certifiedPaths: [try! .init(sourceCommit: installation.commit, target: target)])
    }
    init(runRootInsideHome: Bool = false, inspector: (any DesktopHermesUpgradeInspecting)? = nil) throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("hg199-machine-" + UUID().uuidString).resolvingSymlinksInPath()
        func directory(_ url: URL) throws {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        try directory(root)
        home = root.appendingPathComponent("home"); try directory(home)
        let checkout = home.appendingPathComponent("hermes-agent"); try directory(checkout)
        try Data("old\n".utf8).write(to: home.appendingPathComponent("history.txt"))
        try directory(root.appendingPathComponent("state"))
        migration = try .init(root: root.appendingPathComponent("state"))
        runs = runRootInsideHome ? URL(fileURLWithPath: home.path, isDirectory: true) : root.appendingPathComponent("runs")
        try directory(runs)
        let snapshots = DesktopHermesUpgradeServiceSnapshotStore(root: root.appendingPathComponent("services")); try directory(snapshots.root)
        mailbox = .init(directory: root.appendingPathComponent("state/maintenance"))
        backups = .init(root: root.appendingPathComponent("backups"))
        installation = .init(executable: checkout.appendingPathComponent("venv/bin/hermes"), checkoutRoot: checkout, hermesHome: home,
            commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
        target = try .init(version: "0.21.6", commit: String(repeating: "b", count: 40))
        environment = try .init(installation: installation, target: target, mailbox: mailbox, backups: backups)
        let environment = self.environment
        operations = .init(store: migration.hermesUpgradeJournal, runRoot: runs, sourceRoot: root.appendingPathComponent("source"),
            mailbox: mailbox, snapshots: snapshots, backups: backups, evidence: environment, services: environment,
            inspector: inspector,
            workerFactory: { config in
                let path = config.runDirectory.appendingPathComponent("worker-configuration.json")
                let data = try config.json()
                if UpgradePrivateFiles.exists(path) { guard try UpgradePrivateFiles.read(path, limit: 65536) == data else { throw DesktopHermesUpgradeError.recoveryRequired } }
                else { try UpgradePrivateFiles.write(data, to: path) }
                environment.addConfiguration(config)
                return MachineWorker(configuration: config, environment: environment)
            })
    }
    func mode() throws -> String {
        let object = try JSONSerialization.jsonObject(with: UpgradePrivateFiles.read(mailbox.directory.appendingPathComponent("request.json"), limit: 4096)) as! [String: Any]
        return object["mode"] as! String
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}

private struct MachineInspection: DesktopHermesUpgradeInspecting {
    enum Mode { case unowned, originalSurvivor, backupWriter, updateWriter }
    let mode: Mode
    func inspect(installation: DesktopLocalHermesInstallation) -> DesktopHermesUpgradeInspection { fatalError("journal inspection only") }
    func inspect(journal: DesktopHermesUpgradeJournal) -> DesktopHermesUpgradeInspection {
        var candidates = [DesktopHermesUpgradeInspection.Candidate]()
        if mode == .originalSurvivor, let service = journal.services.first(where: { $0.label == DesktopManagedInstallLayout.hermesLabel }) {
            candidates = [.init(identity: .init(pid: service.processID!, parentPID: 1, startedAt: service.processStartedAt!), association: .checkout, profile: nil)]
        } else if mode == .unowned || (mode == .backupWriter && journal.phase == .backingUp) || (mode == .updateWriter && journal.phase == .updating) {
            candidates = [.init(identity: .init(pid: 9999, parentPID: 1, startedAt: Date()), association: .unscopedHermes, profile: nil)]
        }
        return .init(sourceIdentity: journal.sourceIdentity, checkedAt: Date(), candidates: candidates, unreadableOwnedProcesses: 0,
            processSampleStable: true, profileDirectories: ["default"], profileDirectoryScanComplete: true, interpreter: .unknown)
    }
}

/// Synthetic process, plan, receipt, boot and business evidence; never controls real services.
private final class MachineEnvironment: DesktopHermesUpgradeMachineEvidenceProviding, DesktopHermesUpgradeServicesOperating, @unchecked Sendable {
    let installation: DesktopLocalHermesInstallation; let target: DesktopHermesUpgradeTarget
    let mailbox: DesktopHermesUpgradeMaintenanceMailbox; let backups: DesktopHermesUpgradeBackupStore
    let originals: [DesktopHermesUpgradeService]; let plists: [String: Data]
    let instanceID = UUID().uuidString
    let mutex = NSLock()
    private var log: [String] = []; private var configs: [DesktopHermesUpgradeOfficialWorker.Configuration] = []
    private var loaded: [String: DesktopHermesUpgradeServiceObservation] = [:]
    private var commit: String; private var inspections = 0
    // Set only outside concurrent execution in tests.
    var invalidPlan = false; var wrongCompletionBoot = false
    var changePlan = false; var unknownWriter = false; var closureAvailable = true
    var interruptUpdate = false; var businessFails = false; var receiptDebt = false; var damageBackup = false
    var afterHermesStop: (@Sendable () async -> Void)?
    var events: [String] { mutex.withLock { log } }
    var disk: String { mutex.withLock { commit } }
    var configurations: [DesktopHermesUpgradeOfficialWorker.Configuration] { mutex.withLock { configs } }
    func record(_ text: String) { mutex.withLock { log.append(text) } }
    func addConfiguration(_ config: DesktopHermesUpgradeOfficialWorker.Configuration) { mutex.withLock { configs.append(config); log.append("lock") } }
    init(installation: DesktopLocalHermesInstallation, target: DesktopHermesUpgradeTarget,
         mailbox: DesktopHermesUpgradeMaintenanceMailbox, backups: DesktopHermesUpgradeBackupStore) throws {
        self.installation = installation; self.target = target; self.mailbox = mailbox; self.backups = backups; commit = installation.commit
        var images = [String: Data](); var services = [DesktopHermesUpgradeService]()
        for (index, label) in [DesktopManagedInstallLayout.hermesLabel, DesktopManagedInstallLayout.connectorLabel].enumerated() {
            let args = ["/fixture/" + label]
            let data = try PropertyListSerialization.data(fromPropertyList: ["Label": label, "ProgramArguments": args], format: .xml, options: 0)
            images[label] = data
            let pid = Int32(1001 + index), birth = Date(timeIntervalSince1970: 1000)
            services.append(.init(label: label, wasLoaded: true, configurationDigest: UpgradePrivateFiles.hash(data), processID: pid, processStartedAt: birth))
            loaded[label] = .loaded(arguments: args, pid: pid, startedAt: birth)
        }
        plists = images; originals = services
    }
    func inventory(target: DesktopHermesUpgradeTarget, confirmedIdentity: String) async throws -> DesktopHermesUpgradeMachineInventory {
        let count = mutex.withLock { inspections += 1; log.append("inventory"); return inspections }
        let runtimes: [[String: String]] = changePlan && count > 1 ? [["kind": "dashboard", "profile": "default"]] : []
        let data = try JSONSerialization.data(withJSONObject: ["install_method": "git", "updatable_in_place": true,
            "expected_sha": installation.commit, "expected_version": installation.version, "profiles": invalidPlan ? ["default", "../escape"] : ["default"], "runtimes": runtimes])
        return .init(preflight: .init(installation: installation, services: originals, originalChannel: "stable"), officialPlan: data)
    }
    func validateResume(_ journal: DesktopHermesUpgradeJournal) async throws { record("validate-resume") }
    func idle(_ journal: DesktopHermesUpgradeJournal) async throws -> DesktopHermesUpgradeIdleEvidence {
        if case .loaded(_, let pid, _) = try observe(label: DesktopManagedInstallLayout.connectorLabel) {
            let request = try UpgradePrivateFiles.read(mailbox.directory.appendingPathComponent("request.json"), limit: 4096)
            let data = try JSONSerialization.data(withJSONObject: ["schema": 1, "pid": pid!, "instanceID": instanceID,
                "runID": journal.runID, "sourceIdentity": journal.sourceIdentity, "requestSHA256": UpgradePrivateFiles.hash(request),
                "paused": true, "activeOperations": 0, "observedAt": Date().timeIntervalSince1970 * 1000])
            try UpgradePrivateFiles.write(data, to: mailbox.directory.appendingPathComponent("ack.json"))
        }
        return .init(runID: journal.runID, sourceIdentity: journal.sourceIdentity, observedAt: Date(), profiles: ["default"],
            checkedProfiles: ["default"], activeTurns: 0, activeCron: 0, backgroundWork: 0, unknownWriters: unknownWriter ? 1 : 0,
            processInventoryComplete: true, writerAdmissionClosed: true)
    }
    func connectorIdentity(_ journal: DesktopHermesUpgradeJournal) throws -> DesktopHermesUpgradeMaintenanceMailbox.ConnectorIdentity {
        guard case .loaded(_, let pid?, _) = try observe(label: DesktopManagedInstallLayout.connectorLabel) else { throw DesktopHermesUpgradeError.recoveryRequired }
        return .init(pid: pid, instanceID: instanceID)
    }
    func requireRetiredWriters(_ journal: DesktopHermesUpgradeJournal) throws {
        record("retired")
        guard !unknownWriter, mutex.withLock({ loaded.values.allSatisfy { $0 == .unloaded } }) else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    func requireDependencyClosure(_ journal: DesktopHermesUpgradeJournal) throws {
        record("closure")
        guard closureAvailable else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    func diskCommit(_ journal: DesktopHermesUpgradeJournal) throws -> String { disk }
    func completion(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws -> DesktopHermesUpgradeMachineCompletion {
        let expected = originalInstallation ? installation.commit : target.commit
        record("completion")
        let data = try JSONSerialization.data(withJSONObject: ["schema": 1, "update_id": journal.upstreamUpdateID!, "correlation_id": journal.runID,
            "outcome": "success", "finished_at": "2026-10-11T14:00:00Z", "post_update": ["sha": expected, "version": originalInstallation ? installation.version : target.version],
            "stages": [["name": "build", "outcome": "success"]], "pm_sync_outcome": "ok", "pm_exit_code": 0, "pm_venv_rebuild": ["ok": true],
            "gateway_restart": ["incomplete": receiptDebt, "failed_units": [], "phase_error": ""]])
        return .init(receipt: data, evidence: .init(processExitCode: 0, diskCommit: disk, bootCommits: [wrongCompletionBoot ? String(repeating: "c", count: 40) : expected], dependencyInputsCurrent: true,
            sourceCompletionPending: false, fleetRestartPending: false, servicesMatchOriginalLoadState: mutex.withLock { loaded.values.allSatisfy { if case .loaded = $0 { return true }; return false } }))
    }
    func verifyBusiness(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws {
        let request = try JSONSerialization.jsonObject(with: UpgradePrivateFiles.read(mailbox.directory.appendingPathComponent("request.json"), limit: 4096)) as! [String: Any]
        guard request["mode"] as? String == "release" else { throw DesktopHermesUpgradeError.verificationFailed }
        record(originalInstallation ? "business-original" : "business")
        let history = installation.hermesHome.appendingPathComponent("history.txt")
        var data = try Data(contentsOf: history); data.append(Data("new\n".utf8)); try data.write(to: history)
        if businessFails { throw DesktopHermesUpgradeError.verificationFailed }
    }
    func observe(label: String) throws -> DesktopHermesUpgradeServiceObservation { mutex.withLock { loaded[label] ?? .unknown } }
    func configuration(label: String) throws -> Data { plists[label]! }
    private func name(_ label: String) -> String { label == DesktopManagedInstallLayout.hermesLabel ? "hermes" : "connector" }
    func stop(label: String) async throws {
        mutex.withLock { loaded[label] = .unloaded; log.append("stop:" + name(label)) }
        if label == DesktopManagedInstallLayout.hermesLabel { await afterHermesStop?() }
    }
    func start(label: String, configuration: Data) async throws {
        let object = try PropertyListSerialization.propertyList(from: configuration, format: nil) as! [String: Any]
        mutex.withLock { loaded[label] = .loaded(arguments: object["ProgramArguments"] as! [String], pid: 2001, startedAt: Date()); log.append("start:" + name(label)) }
    }
    func requireHermesReady(commit: String) async throws {
        guard disk == commit else { throw DesktopHermesUpgradeError.recoveryRequired }; record("ready:" + commit)
    }
    func update(_ config: DesktopHermesUpgradeOfficialWorker.Configuration, resumed: Bool) throws -> Int32 {
        mutex.withLock { commit = target.commit; log.append(resumed ? "resume-finish" : "update") }
        if damageBackup { try Data("damaged".utf8).write(to: backups.root.appendingPathComponent(config.runID + "/home/history.txt")) }
        if interruptUpdate && !resumed { throw DesktopHermesUpgradeError.recoveryRequired }
        return 0
    }
}
private final class MachineWorker: DesktopHermesUpgradeWorkerHolding, @unchecked Sendable {
    let config: DesktopHermesUpgradeOfficialWorker.Configuration; let environment: MachineEnvironment
    var runID: String { config.runID }; var upstreamUpdateID: String? { config.upstreamUpdateID }
    init(configuration: DesktopHermesUpgradeOfficialWorker.Configuration, environment: MachineEnvironment) { config = configuration; self.environment = environment }
    func runOfficialUpdate() async throws -> Int32 { try environment.update(config, resumed: false) }
    func resumeOfficialFinish() async throws -> Int32 { try environment.update(config, resumed: true) }
    func release() throws { environment.record("release-lock") }
}
