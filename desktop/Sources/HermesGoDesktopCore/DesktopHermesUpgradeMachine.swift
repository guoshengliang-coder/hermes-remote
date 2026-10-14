import Foundation

/// Independent live evidence remains required; a Connector ACK never supplies these proofs.
/// The production collector is deliberately not defaulted to idle/current/success.
public protocol DesktopHermesUpgradeMachineEvidenceProviding: Sendable {
    func inventory(target: DesktopHermesUpgradeTarget, confirmedIdentity: String) async throws -> DesktopHermesUpgradeMachineInventory
    func validateResume(_ journal: DesktopHermesUpgradeJournal) async throws
    func idle(_ journal: DesktopHermesUpgradeJournal) async throws -> DesktopHermesUpgradeIdleEvidence
    func connectorIdentity(_ journal: DesktopHermesUpgradeJournal) throws -> DesktopHermesUpgradeMaintenanceMailbox.ConnectorIdentity
    func requireRetiredWriters(_ journal: DesktopHermesUpgradeJournal) throws
    /// Must prove backup/recovery coverage of interpreters/tools OUTSIDE the home as well.
    func requireDependencyClosure(_ journal: DesktopHermesUpgradeJournal) throws
    func diskCommit(_ journal: DesktopHermesUpgradeJournal) throws -> String
    func completion(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws -> DesktopHermesUpgradeMachineCompletion
    func verifyBusiness(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws
}

public struct DesktopHermesUpgradeMachineInventory: Sendable {
    public let preflight: DesktopHermesUpgradePreflight
    public let officialPlan: Data
    public init(preflight: DesktopHermesUpgradePreflight, officialPlan: Data) {
        self.preflight = preflight; self.officialPlan = officialPlan
    }
}
public struct DesktopHermesUpgradeMachineCompletion: Sendable {
    public let receipt: Data
    public let evidence: DesktopHermesUpgradeCompletionEvidence
    public init(receipt: Data, evidence: DesktopHermesUpgradeCompletionEvidence) {
        self.receipt = receipt; self.evidence = evidence
    }
}

/// Composes the real backup/mailbox/service/worker modules with explicitly supplied live evidence.
/// No app creates this adapter and no upgrade path is certified by constructing it.
public actor DesktopHermesUpgradeMachineOperations<Evidence: DesktopHermesUpgradeMachineEvidenceProviding,
                                                   Services: DesktopHermesUpgradeServicesOperating>: DesktopHermesUpgradeOperating {
    typealias Configuration = DesktopHermesUpgradeOfficialWorker.Configuration
    typealias WorkerFactory = @Sendable (Configuration) async throws -> any DesktopHermesUpgradeWorkerHolding
    private let store: DesktopHermesUpgradeJournalStore
    private let runs: URL
    private let source: URL
    private let mailbox: DesktopHermesUpgradeMaintenanceMailbox
    private let snapshots: DesktopHermesUpgradeServiceSnapshotStore
    private let backups: DesktopHermesUpgradeBackupStore
    private let evidence: Evidence
    private let services: Services
    private let inspector: (any DesktopHermesUpgradeInspecting)?
    private let factory: WorkerFactory
    private var inventory: DesktopHermesUpgradeMachineInventory?
    private weak var worker: (any DesktopHermesUpgradeWorkerHolding)?
    private var acquiring = false
    private var inspecting = false
    private var effectRunning = false

    public init(store: DesktopHermesUpgradeJournalStore, runRoot: URL, sourceRoot: URL,
                mailbox: DesktopHermesUpgradeMaintenanceMailbox, snapshots: DesktopHermesUpgradeServiceSnapshotStore,
                backups: DesktopHermesUpgradeBackupStore, evidence: Evidence, services: Services,
                python: URL, userHome: URL, path: String = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin",
                proxyEnvironment: [String: String] = [:],
                inspector: any DesktopHermesUpgradeInspecting = DesktopHermesUpgradeInspector()) {
        self.store = store; runs = runRoot; source = sourceRoot; self.mailbox = mailbox
        self.snapshots = snapshots; self.backups = backups; self.evidence = evidence; self.services = services
        self.inspector = inspector
        factory = { try await DesktopHermesUpgradeOfficialWorker.acquire(configuration: $0, python: python,
            userHome: userHome, path: path, proxyEnvironment: proxyEnvironment) }
    }

    /// Tests replace worker processes only; backup, journal, mailbox and snapshot code stays real.
    init(store: DesktopHermesUpgradeJournalStore, runRoot: URL, sourceRoot: URL,
         mailbox: DesktopHermesUpgradeMaintenanceMailbox, snapshots: DesktopHermesUpgradeServiceSnapshotStore,
         backups: DesktopHermesUpgradeBackupStore, evidence: Evidence, services: Services,
         inspector: (any DesktopHermesUpgradeInspecting)? = nil, workerFactory: @escaping WorkerFactory) {
        self.store = store; runs = runRoot; source = sourceRoot; self.mailbox = mailbox
        self.snapshots = snapshots; self.backups = backups; self.evidence = evidence; self.services = services; factory = workerFactory
        self.inspector = inspector
    }

    public func preflight(target: DesktopHermesUpgradeTarget, confirmedIdentity: String) async throws -> DesktopHermesUpgradePreflight {
        guard !acquiring, !inspecting, !effectRunning else { throw DesktopHermesUpgradeError.preflightBlocked }
        inspecting = true; defer { inspecting = false }
        let checked = try await evidence.inventory(target: target, confirmedIdentity: confirmedIdentity)
        let current = checked.preflight
        guard current.installation.runtimeIdentity == confirmedIdentity, current.installation.upgradeDependenciesVerified,
              Set(current.services.map(\.label)) == labels, current.services.count == labels.count else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        let normalized = DesktopHermesUpgradeMachineInventory(preflight: current,
            officialPlan: try canonicalPlan(checked.officialPlan, installation: current.installation))
        if worker != nil {
            guard pendingTarget == target, let old = inventory, old.preflight.installation == current.installation,
                  old.preflight.services == current.services, old.preflight.originalChannel == current.originalChannel,
                  old.officialPlan == normalized.officialPlan else { throw DesktopHermesUpgradeError.preflightBlocked }
        }
        inventory = normalized
        pendingTarget = target
        return current
    }

    public func acquireUpstreamLock(runID: String, checkout: URL, hermesHome: URL) async throws -> any DesktopHermesUpgradeLockHolding {
        guard !acquiring, !inspecting, !effectRunning, worker == nil, UUID(uuidString: runID) != nil else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        acquiring = true; defer { acquiring = false }
        let retained = try store.loadReadOnly()
        let configuration: Configuration
        if let retained, !retained.phase.terminal {
            guard retained.runID == runID else { throw DesktopHermesUpgradeError.recoveryRequired }
            configuration = try Configuration.retained(in: runDirectory(runID, home: hermesHome, create: false))
            try match(configuration, journal: retained)
        } else {
            guard let inventory, inventory.preflight.installation.checkoutRoot == checkout,
                  inventory.preflight.installation.hermesHome == hermesHome else { throw DesktopHermesUpgradeError.preflightBlocked }
            guard retained?.runID != runID else { throw DesktopHermesUpgradeError.recoveryRequired }
            // A previous terminal cursor is archived by store.begin after the second inventory.
            // The target is fixed by the coordinator, not a moving branch.
            guard let target = pendingTarget else { throw DesktopHermesUpgradeError.preflightBlocked }
            let installation = inventory.preflight.installation
            configuration = .init(runID: runID, upstreamUpdateID: UUID().uuidString, sourceRoot: source, checkout: checkout,
                hermesHome: hermesHome, runDirectory: try runDirectory(runID, home: hermesHome, create: true), journal: store.journalURL,
                sourceCommit: installation.commit, targetCommit: target.commit, targetVersion: target.version,
                preUpdateVersion: installation.version, plan: inventory.officialPlan)
        }
        guard configuration.checkout.path == checkout.path, configuration.hermesHome.path == hermesHome.path else { throw DesktopHermesUpgradeError.recoveryRequired }
        let acquired = try await factory(configuration)
        guard acquired.runID == runID, acquired.upstreamUpdateID == configuration.upstreamUpdateID else {
            try? acquired.release(); throw DesktopHermesUpgradeError.recoveryRequired
        }
        worker = acquired
        return MachineWorkerLease(acquired)
    }

    private var pendingTarget: DesktopHermesUpgradeTarget?

    public func validateResume(_ journal: DesktopHermesUpgradeJournal) async throws {
        guard !acquiring, !inspecting, !effectRunning else { throw DesktopHermesUpgradeError.recoveryRequired }
        inspecting = true; defer { inspecting = false }
        try requireJournal(journal)
        try match(Configuration.retained(in: runDirectory(journal.runID, home: URL(fileURLWithPath: journal.hermesHome), create: false)), journal: journal)
        try await evidence.validateResume(journal)
        if let backup = journal.backup { try backups.verify(backup) }
        try evidence.requireDependencyClosure(journal)
    }

    public func quiesce(_ journal: DesktopHermesUpgradeJournal) async throws {
        try begin(journal, phase: .quiescing); defer { effectRunning = false }
        try evidence.requireDependencyClosure(journal)
        try captureServices(journal)
        try mailbox.prepare(); try mailbox.pause(journal)
        let evidence = self.evidence, services = self.services, mailbox = self.mailbox, inspector = self.inspector
        try await restorer.quiesce(journal, idleEvidence: {
            let idle = try await evidence.idle(journal)
            if let inspector { try inspector.inspect(journal: journal).requireOnlyOriginalServices(for: journal) }
            if case .loaded = try services.observe(label: DesktopManagedInstallLayout.connectorLabel) {
                _ = try mailbox.requireDrained(journal, connector: evidence.connectorIdentity(journal))
            }
            return idle
        }, requireRetiredWriters: {
            try evidence.requireRetiredWriters(journal)
            if let inspector { try inspector.inspect(journal: journal).requireNoObservedWriters(for: journal) }
        })
    }

    public func backup(_ journal: DesktopHermesUpgradeJournal) async throws -> DesktopHermesUpgradeBackup {
        try begin(journal, phase: .backingUp); defer { effectRunning = false }
        _ = try snapshots.configurations(journal)
        try evidence.requireDependencyClosure(journal)
        let receipt = try backups.create(home: URL(fileURLWithPath: journal.hermesHome), runID: journal.runID) {
            try evidence.requireRetiredWriters(journal)
            if let inspector { try inspector.inspect(journal: journal).requireNoObservedWriters(for: journal) }
        }
        try evidence.requireDependencyClosure(journal)
        try backups.verify(receipt)
        return receipt
    }

    public func installExactTarget(_ journal: DesktopHermesUpgradeJournal) async throws {
        try begin(journal, phase: .updating); defer { effectRunning = false }
        guard let backup = journal.backup, let worker else { throw DesktopHermesUpgradeError.recoveryRequired }
        try backups.verify(backup); try evidence.requireRetiredWriters(journal); try evidence.requireDependencyClosure(journal)
        if let inspector { try inspector.inspect(journal: journal).requireNoObservedWriters(for: journal) }
        let commit = try evidence.diskCommit(journal)
        let code: Int32
        if commit == journal.sourceCommit { code = try await worker.runOfficialUpdate() }
        else if commit == journal.target.commit { code = try await worker.resumeOfficialFinish() }
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        guard code == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        // This means only worker returned. Independent receipt/boot/debt checks still precede completion.
    }

    public func restoreServices(_ journal: DesktopHermesUpgradeJournal, originalInstallation: Bool) async throws {
        try begin(journal, phase: originalInstallation ? .cancelling : .restoringServices); defer { effectRunning = false }
        if !originalInstallation {
            guard let backup = journal.backup else { throw DesktopHermesUpgradeError.recoveryRequired }
            try backups.verify(backup)
        }
        try evidence.requireDependencyClosure(journal)
        guard try evidence.diskCommit(journal) == (originalInstallation ? journal.sourceCommit : journal.target.commit) else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        // Cancellation can arrive before quiesce captured a snapshot; only matching original bytes can be captured.
        try captureServices(journal)
        try mailbox.prepare(); try mailbox.pause(journal)
        try await restorer.restore(journal, originalInstallation: originalInstallation)
        if originalInstallation {
            do {
                let result = try await evidence.completion(journal, originalInstallation: true)
                try mailbox.releaseForCancellationVerification(journal, evidence: result.evidence)
                try await evidence.verifyBusiness(journal, originalInstallation: true)
                try evidence.requireDependencyClosure(journal)
                let fresh = try await evidence.completion(journal, originalInstallation: true)
                try mailbox.releaseForCancellationVerification(journal, evidence: fresh.evidence)
            } catch { try? mailbox.pause(journal); throw DesktopHermesUpgradeError.recoveryRequired }
        }
    }

    public func verify(_ journal: DesktopHermesUpgradeJournal) async throws {
        try begin(journal, phase: .verifying); defer { effectRunning = false }
        do {
            guard let backup = journal.backup else { throw DesktopHermesUpgradeError.recoveryRequired }
            try backups.verify(backup); try evidence.requireDependencyClosure(journal)
            let result = try await evidence.completion(journal, originalInstallation: false)
            try checkCompletion(result, journal: journal)
            try mailbox.releaseForVerification(journal, evidence: result.evidence)
            try await evidence.verifyBusiness(journal, originalInstallation: false)
            try evidence.requireDependencyClosure(journal)
            try checkCompletion(try await evidence.completion(journal, originalInstallation: false), journal: journal)
        } catch { try? mailbox.pause(journal); throw DesktopHermesUpgradeError.recoveryRequired }
    }

    private var labels: Set<String> { [DesktopManagedInstallLayout.hermesLabel, DesktopManagedInstallLayout.connectorLabel] }
    private var restorer: DesktopHermesUpgradeServiceRestorer<Services> { .init(snapshots: snapshots, driver: services) }
    private func requireJournal(_ journal: DesktopHermesUpgradeJournal) throws {
        guard try store.loadReadOnly() == journal, !journal.phase.terminal, Set(journal.services.map(\.label)) == labels,
              journal.services.count == labels.count, journal.upstreamUpdateID != nil else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func begin(_ journal: DesktopHermesUpgradeJournal, phase: DesktopHermesUpgradePhase) throws {
        guard !acquiring, !inspecting, !effectRunning, journal.phase == phase, let worker,
              worker.runID == journal.runID, worker.upstreamUpdateID == journal.upstreamUpdateID else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        try requireJournal(journal)
        try match(Configuration.retained(in: runDirectory(journal.runID, home: URL(fileURLWithPath: journal.hermesHome), create: false)), journal: journal)
        effectRunning = true
    }
    private func captureServices(_ journal: DesktopHermesUpgradeJournal) throws {
        var configurations = [String: Data]()
        for label in labels { configurations[label] = try services.configuration(label: label) }
        try snapshots.capture(journal, configurations: configurations)
    }
    private func checkCompletion(_ result: DesktopHermesUpgradeMachineCompletion, journal: DesktopHermesUpgradeJournal) throws {
        guard let upstream = journal.upstreamUpdateID else { throw DesktopHermesUpgradeError.recoveryRequired }
        try DesktopHermesUpgradeCompletionVerifier.verify(receipt: result.receipt, runID: journal.runID, upstreamUpdateID: upstream,
            target: journal.target, expectedRunningServices: journal.services.filter { $0.label == DesktopManagedInstallLayout.hermesLabel && $0.wasLoaded }.count,
            evidence: result.evidence)
    }
    private func match(_ config: Configuration, journal: DesktopHermesUpgradeJournal) throws {
        guard config.runID == journal.runID, config.upstreamUpdateID == journal.upstreamUpdateID,
              config.checkout.path == journal.checkout, config.hermesHome.path == journal.hermesHome,
              config.sourceRoot.path == source.path, config.journal.path == store.journalURL.path, config.sourceCommit == journal.sourceCommit,
              config.targetCommit == journal.target.commit, config.targetVersion == journal.target.version else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
    }
    private func runDirectory(_ id: String, home: URL, create: Bool) throws -> URL {
        guard UUID(uuidString: id) != nil, runs.path != home.path, !runs.path.hasPrefix(home.path + "/") else { throw DesktopHermesUpgradeError.recoveryRequired }
        try UpgradePrivateFiles.directory(runs)
        let directory = runs.appendingPathComponent(id.lowercased())
        if create, !UpgradePrivateFiles.exists(directory) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            try UpgradePrivateFiles.sync(runs)
        }
        try UpgradePrivateFiles.directory(directory)
        return directory
    }
    private struct Plan: Decodable {
        struct Runtime: Decodable { let kind: String; let profile: String }
        let install_method: String; let updatable_in_place: Bool
        let expected_sha: String; let expected_version: String
        let profiles: [String]; let runtimes: [Runtime]
    }
    private func canonicalPlan(_ data: Data, installation: DesktopLocalHermesInstallation) throws -> Data {
        guard data.count <= 32 * 1024, let plan = try? JSONDecoder().decode(Plan.self, from: data),
              plan.install_method == "git", plan.updatable_in_place, plan.expected_sha == installation.commit,
              plan.expected_version == installation.version, plan.profiles.contains("default"), plan.profiles.count <= 128,
              Set(plan.profiles).count == plan.profiles.count, plan.runtimes.count <= 128,
              plan.profiles.allSatisfy({ $0.range(of: "^[a-z0-9][a-z0-9_-]{0,63}$", options: .regularExpression) != nil }),
              plan.runtimes.allSatisfy({ ["gateway", "serve", "dashboard"].contains($0.kind) && plan.profiles.contains($0.profile) }),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

/// The coordinator owns the lease; the adapter's weak reference cannot retain an abandoned run.
private final class MachineWorkerLease: DesktopHermesUpgradeLockHolding, @unchecked Sendable {
    private let worker: any DesktopHermesUpgradeWorkerHolding
    var upstreamUpdateID: String? { worker.upstreamUpdateID }
    init(_ worker: any DesktopHermesUpgradeWorkerHolding) { self.worker = worker }
    deinit { try? worker.release() } // Never sends a process signal; committed children retain custody.
}
