import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeTransactionTests: XCTestCase {
    func testOfficialIdentityIsDurableAndCannotChangeWhenResuming() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let upstreamID = UUID().uuidString
        await fixture.operations.setUpstreamID(upstreamID)
        await fixture.operations.fail(at: "install")
        do { _ = try await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity); XCTFail() }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        let pending = try XCTUnwrap(fixture.migration.hermesUpgradeJournal.loadReadOnly())
        XCTAssertEqual(pending.upstreamUpdateID, upstreamID)
        XCTAssertNotEqual(pending.runID, upstreamID)
        await fixture.operations.fail(at: nil)
        await fixture.operations.setUpstreamID(UUID().uuidString)
        do { _ = try await fixture.coordinator.resume(runID: pending.runID); XCTFail("new upstream receipt custody was accepted") }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        XCTAssertEqual(try fixture.migration.hermesUpgradeJournal.loadReadOnly(), pending)
        await fixture.operations.setUpstreamID(upstreamID)
        let completed = try await fixture.coordinator.resume(runID: pending.runID)
        XCTAssertEqual(completed.phase, .completed)
        XCTAssertEqual(completed.upstreamUpdateID, upstreamID)
    }
    func testUncertifiedTargetHasNoEffectsAndCreatesNoJournal() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let coordinator = DesktopHermesUpgradeCoordinator(migration: fixture.migration, operations: fixture.operations)
        do {
            _ = try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
            XCTFail("uncertified update started")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        XCTAssertNil(try fixture.migration.hermesUpgradeJournal.loadReadOnly())
        let calls = await fixture.operations.calls
        XCTAssertEqual(calls, [])
    }

    func testExactTargetAndOriginalChannelSurviveEveryPhase() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let result = try await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .completed)
        XCTAssertEqual(result.target, fixture.target)
        XCTAssertEqual(result.originalChannel, "main")
        XCTAssertNotNil(result.backup)
        XCTAssertEqual(try fixture.migration.hermesUpgradeJournal.loadReadOnly(), result)
        let calls = await fixture.operations.calls
        XCTAssertEqual(calls, ["preflight", "lock", "preflight", "quiesce", "backup", "install", "restore", "verify"])
        let lease = try fixture.migration.acquireOperationLease()
        withExtendedLifetime(lease) {}
        let attrs = try FileManager.default.attributesOfItem(atPath: fixture.migration.hermesUpgradeJournal.journalURL.path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
    }

    func testMovedInstallationBetweenPreflightAndLockDoesNotStopServices() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        await fixture.operations.changeIdentityOnSecondPreflight()
        do {
            _ = try await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
            XCTFail("moving source accepted")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        let calls = await fixture.operations.calls
        XCTAssertEqual(calls, ["preflight", "lock", "preflight"])
        XCTAssertNil(try fixture.migration.hermesUpgradeJournal.loadReadOnly())
    }

    func testCertifiedDestinationDoesNotCertifyAnUnknownSourcePath() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let coordinator = DesktopHermesUpgradeCoordinator(migration: fixture.migration, operations: fixture.operations,
            certifiedPaths: [try .init(sourceCommit: String(repeating: "c", count: 40), target: fixture.target)])
        do {
            _ = try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
            XCTFail("untested source path accepted")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        let calls = await fixture.operations.calls
        XCTAssertEqual(calls, ["preflight"])
        XCTAssertNil(try fixture.migration.hermesUpgradeJournal.loadReadOnly())
    }

    func testAnotherManagedOperationBlocksUpgradeWithStructuredErrorBeforePreflight() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let lease = try fixture.migration.acquireOperationLease()
        defer { withExtendedLifetime(lease) {} }
        do {
            _ = try await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
            XCTFail("shared lease bypassed")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        let calls = await fixture.operations.calls
        XCTAssertEqual(calls, [])
    }

    func testInterruptionAtEachEffectBlocksAutomaticOperationsAndResumesOnlyOwedWork() async throws {
        for effect in ["quiesce", "backup", "install", "restore", "verify"] {
            let fixture = try Fixture(); defer { fixture.remove() }
            await fixture.operations.fail(at: effect)
            do {
                _ = try await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
                XCTFail("injected failure accepted")
            } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
            let pending = try XCTUnwrap(fixture.migration.hermesUpgradeJournal.loadReadOnly())
            XCTAssertFalse(pending.phase.terminal)
            XCTAssertThrowsError(try fixture.migration.acquireOperationLease())
            // A new app/coordinator sees the same durable cursor; no dependency on old in-memory state.
            await fixture.operations.fail(at: nil)
            let resumed = try await fixture.coordinator.resume(runID: pending.runID)
            XCTAssertEqual(resumed.phase, .completed)
            XCTAssertEqual(resumed.runID, pending.runID)
            XCTAssertEqual(resumed.target, pending.target)
            let calls = await fixture.operations.calls
            if ["restore", "verify"].contains(effect) {
                XCTAssertEqual(calls.filter { $0 == "install" }.count, 1)
                XCTAssertEqual(calls.filter { $0 == "backup" }.count, 1)
            }
        }
    }

    func testRecoveryRejectsDifferentRunAndNewUnownedWrites() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        await fixture.operations.fail(at: "install")
        _ = try? await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
        let pending = try XCTUnwrap(fixture.migration.hermesUpgradeJournal.loadReadOnly())
        do { _ = try await fixture.coordinator.resume(runID: UUID().uuidString); XCTFail("wrong run") }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        await fixture.operations.fail(at: "resume")
        do { _ = try await fixture.coordinator.resume(runID: pending.runID); XCTFail("unowned writes ignored") }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        XCTAssertEqual(try fixture.migration.hermesUpgradeJournal.loadReadOnly(), pending)
    }

    func testCancellationBeforeMutationRestoresOriginalServices() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let coordinator = fixture.coordinator
        await fixture.operations.setHook { effect in
            if effect == "backup" { await coordinator.requestCancellation() }
        }
        let result = try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .cancelled)
        let calls = await fixture.operations.calls
        XCTAssertTrue(calls.contains("restoreOriginal"))
        XCTAssertFalse(calls.contains("install"))
    }

    func testCancellationAfterMutationFinishesAndNeverRestoresOldDatabase() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let coordinator = fixture.coordinator
        await fixture.operations.setHook { effect in
            if effect == "install" { await coordinator.requestCancellation() }
        }
        let result = try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .completed)
        let calls = await fixture.operations.calls
        XCTAssertFalse(calls.contains("restoreOriginal"))
        XCTAssertTrue(calls.contains("verify"))
    }

    func testMalformedFutureAndDanglingJournalFailClosedWithoutCreatingState() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let store = fixture.migration.hermesUpgradeJournal
        XCTAssertNil(try store.loadReadOnly())
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.root.path))
        do { let lease = try fixture.migration.acquireOperationLease(); withExtendedLifetime(lease) {} }
        let journal = DesktopHermesUpgradeJournal(target: fixture.target, installation: fixture.installation,
            services: [], originalChannel: nil)
        try store.begin(journal)
        var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(journal)) as! [String: Any]
        object["schemaVersion"] = 2
        try JSONSerialization.data(withJSONObject: object).write(to: store.journalURL)
        XCTAssertThrowsError(try store.loadReadOnly())
        XCTAssertThrowsError(try fixture.migration.acquireOperationLease())
        try FileManager.default.removeItem(at: store.journalURL)
        try FileManager.default.createSymbolicLink(atPath: store.journalURL.path, withDestinationPath: "/missing-hg199-state")
        XCTAssertThrowsError(try store.loadReadOnly())
        XCTAssertThrowsError(try fixture.migration.acquireOperationLease())
    }

    func testAmbiguousDuplicateJournalKeysAreRejectedAtEveryObjectDepthWithoutRewriting() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let store = fixture.migration.hermesUpgradeJournal
        do { let lease = try fixture.migration.acquireOperationLease(); withExtendedLifetime(lease) {} }
        let journal = DesktopHermesUpgradeJournal(target: fixture.target, installation: fixture.installation,
            services: [], originalChannel: nil)
        try store.begin(journal)
        let encoded = try JSONEncoder().encode(journal)
        let text = String(decoding: encoded, as: UTF8.self)
        let duplicateTop = String(text.dropLast()) + #", "phase":"completed"}"#
        let escapedDuplicate = String(text.dropLast()) + #", "ph\u0061se":"quiescing"}"#
        let duplicateTarget = text.replacingOccurrences(of: #""channel":"stable""#,
                                                       with: #""channel":"stable","channel":"stable""#)
        XCTAssertNotEqual(text, duplicateTarget)
        for value in [duplicateTop, escapedDuplicate, duplicateTarget] {
            let data = Data(value.utf8)
            try data.write(to: store.journalURL)
            XCTAssertThrowsError(try store.loadReadOnly()) { error in
                XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired)
            }
            XCTAssertThrowsError(try fixture.migration.acquireOperationLease())
            XCTAssertEqual(try Data(contentsOf: store.journalURL), data)
        }
    }

    func testFutureNestedJournalFieldsAreNotSilentlyDiscarded() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let store = fixture.migration.hermesUpgradeJournal
        do { let lease = try fixture.migration.acquireOperationLease(); withExtendedLifetime(lease) {} }
        let journal = DesktopHermesUpgradeJournal(target: fixture.target, installation: fixture.installation,
            services: [.init(label: "com.hermes.test", wasLoaded: false, configurationDigest: String(repeating: "a", count: 64))],
            originalChannel: nil)
        try store.begin(journal)
        let baseline = try JSONSerialization.jsonObject(with: JSONEncoder().encode(journal)) as! [String: Any]
        for field in ["target", "services"] {
            var object = baseline
            if field == "target" {
                var target = object["target"] as! [String: Any]
                target["futureAuthority"] = true
                object["target"] = target
            } else {
                var services = object["services"] as! [[String: Any]]
                services[0]["futureAuthority"] = true
                object["services"] = services
            }
            let data = try JSONSerialization.data(withJSONObject: object)
            try data.write(to: store.journalURL)
            XCTAssertThrowsError(try store.loadReadOnly())
            XCTAssertEqual(try Data(contentsOf: store.journalURL), data)
        }
    }

    func testCancellingAwaitingUITaskCannotInterruptCommittedTail() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let coordinator = fixture.coordinator
        let holder = TaskHolder()
        await fixture.operations.setHook { effect in if effect == "install" { await holder.cancel() } }
        let task = Task { try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity) }
        await holder.set(task)
        let result = try await task.value
        XCTAssertEqual(result.phase, .completed)
        XCTAssertTrue(task.isCancelled)
    }

    func testConcurrentClickIsRejectedWithoutASecondPreflightOrServiceOperation() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let coordinator = fixture.coordinator
        await fixture.operations.setHook { effect in
            if effect == "quiesce" {
                do {
                    _ = try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
                    XCTFail("concurrent click accepted")
                } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
            }
        }
        let result = try await coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
        XCTAssertEqual(result.phase, .completed)
        let calls = await fixture.operations.calls
        XCTAssertEqual(calls.filter { $0 == "preflight" }.count, 2)
    }

    func testNextRunArchivesPriorRecoveryReferencesBeforeReplacingCursor() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let first = try await fixture.coordinator.start(target: fixture.target, confirmedIdentity: fixture.installation.runtimeIdentity)
        let store = fixture.migration.hermesUpgradeJournal
        let second = DesktopHermesUpgradeJournal(target: fixture.target, installation: fixture.installation,
            services: [], originalChannel: nil)
        let lease = try fixture.migration.acquireOperationLease()
        defer { withExtendedLifetime(lease) {} }
        try store.begin(second)
        let archive = store.root.appendingPathComponent("hermes-upgrade-runs/" + first.runID.lowercased() + ".json")
        XCTAssertEqual(try JSONDecoder().decode(DesktopHermesUpgradeJournal.self, from: Data(contentsOf: archive)), first)
        XCTAssertEqual(try store.loadReadOnly()?.runID, second.runID)
        XCTAssertNotNil(first.backup)
    }
}

private actor TaskHolder {
    var task: Task<DesktopHermesUpgradeJournal, Error>?
    var waiting: CheckedContinuation<Void, Never>?
    func set(_ task: Task<DesktopHermesUpgradeJournal, Error>) {
        self.task = task; waiting?.resume(); waiting = nil
    }
    func cancel() async {
        if task == nil { await withCheckedContinuation { waiting = $0 } }
        task?.cancel()
    }
}

private struct Fixture {
    let root: URL
    let migration: DesktopMigrationJournalStore
    let installation: DesktopLocalHermesInstallation
    let target: DesktopHermesUpgradeTarget
    let operations: Operations
    var coordinator: DesktopHermesUpgradeCoordinator {
        .init(migration: migration, operations: operations,
              certifiedPaths: [try! .init(sourceCommit: installation.commit, target: target)])
    }
    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        migration = try .init(root: root.appendingPathComponent("state"))
        installation = .init(executable: root.appendingPathComponent(".hermes/hermes-agent/venv/bin/hermes"),
            checkoutRoot: root.appendingPathComponent(".hermes/hermes-agent"), hermesHome: root.appendingPathComponent(".hermes"),
            commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
        target = try .init(version: "0.21.6", commit: String(repeating: "b", count: 40))
        operations = Operations(installation: installation, store: migration.hermesUpgradeJournal)
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}
private final class Lock: DesktopHermesUpgradeLockHolding, @unchecked Sendable {
    let upstreamUpdateID: String?
    init(_ id: String? = nil) { upstreamUpdateID = id }
}
private actor Operations: DesktopHermesUpgradeOperating {
    let installation: DesktopLocalHermesInstallation
    let store: DesktopHermesUpgradeJournalStore
    var calls: [String] = []
    var failure: String?
    var moved = false
    var upstreamID: String?
    var hook: (@Sendable (String) async -> Void)?
    init(installation: DesktopLocalHermesInstallation, store: DesktopHermesUpgradeJournalStore) {
        self.installation = installation; self.store = store
    }
    func fail(at effect: String?) { failure = effect }
    func setUpstreamID(_ id: String?) { upstreamID = id }
    func changeIdentityOnSecondPreflight() { moved = true }
    func setHook(_ hook: @escaping @Sendable (String) async -> Void) { self.hook = hook }
    func effect(_ name: String, phase: DesktopHermesUpgradePhase? = nil) async throws {
        calls.append(name)
        if let phase { XCTAssertEqual(try store.loadReadOnly()?.phase, phase) }
        await hook?(name)
        try Task.checkCancellation()
        if failure == name { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    func preflight(target: DesktopHermesUpgradeTarget, confirmedIdentity: String) async throws -> DesktopHermesUpgradePreflight {
        try await effect("preflight")
        let selected = moved && calls.filter({ $0 == "preflight" }).count > 1
            ? DesktopLocalHermesInstallation(executable: installation.executable, checkoutRoot: installation.checkoutRoot,
                hermesHome: installation.hermesHome, commit: String(repeating: "c", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
            : installation
        return .init(installation: selected, services: [.init(label: "com.hermesgo.hermes-server", wasLoaded: true,
            configurationDigest: String(repeating: "d", count: 64))], originalChannel: "main")
    }
    func acquireUpstreamLock(runID: String, checkout: URL, hermesHome: URL) async throws -> any DesktopHermesUpgradeLockHolding {
        try await effect("lock"); return Lock(upstreamID)
    }
    func validateResume(_ journal: DesktopHermesUpgradeJournal) async throws { try await effect("resume") }
    func quiesce(_ journal: DesktopHermesUpgradeJournal) async throws { try await effect("quiesce", phase: .quiescing) }
    func backup(_ journal: DesktopHermesUpgradeJournal) async throws -> DesktopHermesUpgradeBackup {
        try await effect("backup", phase: .backingUp)
        return .init(runID: journal.runID, manifestSHA256: String(repeating: "e", count: 64))
    }
    func installExactTarget(_ journal: DesktopHermesUpgradeJournal) async throws { try await effect("install", phase: .updating) }
    func restoreServices(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws {
        try await effect(originalInstallation ? "restoreOriginal" : "restore", phase: originalInstallation ? .cancelling : .restoringServices)
    }
    func verify(_ journal: DesktopHermesUpgradeJournal) async throws { try await effect("verify", phase: .verifying) }
}
