import CryptoKit
import Darwin
import Foundation

public enum DesktopComponentContentRepairError: Error { case noRepairNeeded }

public struct DesktopComponentContentRepairPreparation: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let releaseVersion: String
    public let componentNames: [String]
    public let confirmationText: String
    fileprivate let snapshot: DesktopComponentRepairSnapshot
    fileprivate let credentialDigest: String
    fileprivate let tokenDigest: String
}

struct DesktopComponentRepairSnapshot: Codable, Equatable, Sendable {
    struct Entry: Codable, Equatable, Sendable {
        let kind: DesktopManagedComponentKind
        let contentHash: String
        let beforeDigest: String?
        let candidateDigest: String
        let source: URL
    }
    let id: UUID
    let journal: DesktopMigrationJournal
    let agentDigest: String
    let envelope: Data
    var entries: [Entry]
    var failed: Bool
}

/// Same-release content repair is deliberately separate from upgrade/migration. It accepts only
/// the signed identities already referenced by the committed installation, stages in a second
/// private store, stops the exact Connector, quarantines old bytes, then proves a fresh runtime.
/// Neither Hermes, credentials, the binding nor the installation's version/layout is rewritten.
public actor DesktopComponentContentRepair {
    private let paths: DesktopManagedBootstrapPaths
    private let journal: DesktopMigrationJournalStore
    private let gatewayURL: URL
    private let stagingInstaller: @Sendable (URL) throws -> DesktopComponentReleaseInstaller
    private let layout: DesktopManagedInstallLayout
    private let identity: @Sendable () async throws -> DesktopManagedServiceRepairIdentity?
    private let stop: @Sendable () async throws -> Void
    private let start: @Sendable () throws -> Void
    private let runtimeProof: @Sendable () -> Bool
    private var pending: DesktopComponentContentRepairPreparation?
    private var busy = false
    private var recordURL: URL { paths.migrationJournalRoot.appendingPathComponent("component-content-repair.json") }

    init(paths: DesktopManagedBootstrapPaths, gatewayURL: URL,
         stagingInstaller: @escaping @Sendable (URL) throws -> DesktopComponentReleaseInstaller = { try DesktopComponentReleaseInstaller(storeRoot: $0, currentUserID: getuid()) },
         identity: @escaping @Sendable () async throws -> DesktopManagedServiceRepairIdentity?,
         stop: @escaping @Sendable () async throws -> Void,
         start: @escaping @Sendable () throws -> Void,
         runtimeProof: @escaping @Sendable () -> Bool) throws {
        self.gatewayURL = gatewayURL; self.stagingInstaller = stagingInstaller
        self.paths = paths; self.identity = identity; self.stop = stop; self.start = start; self.runtimeProof = runtimeProof
        journal = try DesktopMigrationJournalStore(root: paths.migrationJournalRoot)
        layout = try DesktopManagedInstallLayout(root: paths.managedRoot, launchAgentsRoot: paths.launchAgentsRoot)
    }

    public static func system(paths: DesktopManagedBootstrapPaths, account: any DesktopBindingCoordinating, gatewayURL: URL,
                              userID: UInt32 = getuid()) throws -> DesktopComponentContentRepair {
        let layout = try DesktopManagedInstallLayout(root: paths.managedRoot, launchAgentsRoot: paths.launchAgentsRoot)
        let agents = try DesktopLaunchAgentController(userID: userID, launchAgentsRoot: paths.launchAgentsRoot,
                                                     runner: SystemCommandRunner(capturesStandardError: true))
        return try Self(paths: paths, gatewayURL: gatewayURL, identity: {
            DesktopManagedServiceRepairIdentity(account: try await account.refresh())
        }, stop: {
            let output = SystemOutputCommandRunner().run(executable: URL(fileURLWithPath: "/bin/launchctl"),
                arguments: ["print", "gui/\(userID)/\(DesktopManagedInstallLayout.connectorLabel)"], maximumOutputBytes: 256 * 1024)
            let text = String(data: output.stdout, encoding: .utf8) ?? ""
            let inspector = DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.self
            guard let processes = DesktopConnectorRuntimeIdentity.processes(managedRoot: paths.managedRoot, userID: userID) else {
                throw DesktopManagedServiceRepairError.unknownService
            }
            if output.status == 113 {
                guard processes.isEmpty else { throw DesktopManagedServiceRepairError.unknownService }
                return
            }
            guard output.status == 0, !output.outputLimitExceeded,
                  let disk = try PropertyListSerialization.propertyList(from: Data(contentsOf: layout.connectorLaunchAgent), format: nil) as? [String: Any],
                  disk["ProgramArguments"] as? [String] == inspector.arguments(fromLaunchctlPrint: text)
            else { throw DesktopManagedServiceRepairError.unknownService }
            let pid = inspector.pid(fromLaunchctlPrint: text)
            let date = pid.flatMap { inspector.startDate(pid: $0) }
            guard pid == nil ? DesktopConnectorProcessState.parse(text) == .stopped : date != nil else {
                throw DesktopManagedServiceRepairError.unknownService
            }
            try agents.stopAccount()
            for _ in 0..<75 {
                let originalExited = pid == nil || date == nil || inspector.startDate(pid: pid!) != date
                let allExited = DesktopConnectorRuntimeIdentity.processes(managedRoot: paths.managedRoot, userID: userID)?.isEmpty == true
                if originalExited && allExited { return }
                try await Task.sleep(nanoseconds: 1_000_000_000)
            }
            throw DesktopManagedServiceRepairError.unknownService
        }, start: { try agents.startAccount(plistURL: layout.connectorLaunchAgent) },
           runtimeProof: { DesktopConnectorRuntimeIdentity.verify(launchAgentURL: layout.connectorLaunchAgent, userID: userID) })
    }

    public func verifiedForRecovery(using verifier: DesktopComponentReleaseManifestV2Verifier) throws
        -> VerifiedDesktopComponentReleaseManifestV2? {
        guard let snapshot = try loadSnapshot(installer: DesktopManagedInstaller(layout: layout)) else { return nil }
        return try verifier.verifyForInstallation(snapshot.envelope)
    }

    public func prepare(verified: VerifiedDesktopComponentReleaseManifestV2,
                        healthProbe: @escaping DesktopComponentReleaseInstaller.HealthProbe) async throws
        -> DesktopComponentContentRepairPreparation {
        guard !busy, pending == nil else { throw DesktopManagedServiceRepairError.stalePreparation }
        busy = true; defer { busy = false }
        let manifest = verified.manifest
        guard let installed = try journal.loadReadOnly(), installed.state == .accountActive,
              installed.releaseLayout == .componentStore, manifest.releaseVersion == installed.releaseVersion,
              DesktopComponentReleaseActivationPlanner.validBootstrapManifest(manifest) else {
            throw DesktopMigrationCoordinatorError.releaseNotNewer
        }
        let remote = try await requireIdentity(installed)
        let installer = DesktopManagedInstaller(layout: layout)
        let credential = try credentialDigest(installer: installer, remote: remote)
        let token = try installer.validatedSessionTokenIfPresent(required: true)
        guard token != nil else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        let tokenDigest = Self.hash(try installer.readOwnedPrivateFile(layout.hermesSessionToken))
        let agent = try installer.readOwnedPrivateFile(layout.connectorLaunchAgent)
        let refs = try JSONDecoder().decode(DesktopManagedComponentReferenceSet.self,
            from: installer.readOwnedPrivateFile(layout.root.appendingPathComponent("references/\(installed.releaseVersion).json")))
        guard refs.schemaVersion == 1, refs.releaseVersion == manifest.releaseVersion, refs.components.count == manifest.components.count,
              manifest.components.allSatisfy({ a in refs.components.contains { $0.kind == a.kind && $0.contentSHA256 == a.contentSHA256 } })
        else { throw DesktopManagedServiceRepairError.unsafeInstallation }

        try validateAgent(agent, manifest: manifest)
        let previous = try loadSnapshot(installer: installer)
        guard previous == nil || (previous?.journal == installed && previous?.agentDigest == Self.hash(agent)) else {
            throw DesktopManagedServiceRepairError.stalePreparation
        }
        let id = previous?.id ?? UUID()
        // Preparation never renames a live content directory, even when corrupt.
        try installer.ensurePrivateDirectory(paths.workspaceRoot)
        let workspace = paths.workspaceRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
        var keepWorkspace = false
        defer { if !keepWorkspace { try? FileManager.default.removeItem(at: workspace) } }
        let store = workspace.appendingPathComponent("repair-store", isDirectory: true)
        let stagedInstaller = try stagingInstaller(store)
        let candidate = try await stagedInstaller.install(verifiedManifest: verified, workspaceRoot: workspace,
            runID: UUID().uuidString, healthProbe: healthProbe)
        var entries: [DesktopComponentRepairSnapshot.Entry] = []
        for artifact in manifest.components {
            let destination = root(kind: artifact.kind, hash: artifact.contentSHA256)
            let source = store.appendingPathComponent("components/\(artifact.kind.rawValue)/\(artifact.contentSHA256)")
            let before = try Self.optionalDigest(destination)
            let new = try DesktopAppUpdateTransaction.digest(source)
            if let old = previous?.entries.first(where: { $0.kind == artifact.kind }) {
                guard old.contentHash == artifact.contentSHA256, old.candidateDigest == new else {
                    throw DesktopManagedServiceRepairError.stalePreparation
                }
                entries.append(.init(kind: artifact.kind, contentHash: artifact.contentSHA256,
                    beforeDigest: old.beforeDigest, candidateDigest: new, source: source))
            } else if before != new {
                entries.append(.init(kind: artifact.kind, contentHash: artifact.contentSHA256,
                    beforeDigest: before, candidateDigest: new, source: source))
            }
        }
        _ = candidate // staged receipts and content were independently revalidated by commit.
        guard !entries.isEmpty else {
            try? FileManager.default.removeItem(at: workspace)
            throw DesktopComponentContentRepairError.noRepairNeeded
        }
        let snapshot = DesktopComponentRepairSnapshot(id: id, journal: installed, agentDigest: Self.hash(agent), envelope: verified.envelope, entries: entries, failed: false)
        let result = DesktopComponentContentRepairPreparation(id: id, releaseVersion: installed.releaseVersion,
            componentNames: entries.map { $0.kind.rawValue }.sorted(),
            confirmationText: "修复当前组件 \(installed.releaseVersion) 并短暂重启 Connector；保留 Hermes、账号和数据",
            snapshot: snapshot, credentialDigest: credential, tokenDigest: tokenDigest)
        keepWorkspace = true
        pending = result; return result
    }

    public func cancel(_ preparation: DesktopComponentContentRepairPreparation) throws {
        guard !busy, pending == preparation else { throw DesktopManagedServiceRepairError.stalePreparation }
        pending = nil
        // The source tree is derived internally, never supplied by a view or a journal field.
        for entry in preparation.snapshot.entries {
            if let workspace = workspace(containing: entry.source) { try? FileManager.default.removeItem(at: workspace) }
        }
    }

    public func commit(_ preparation: DesktopComponentContentRepairPreparation, confirmation: String) async throws {
        guard !busy, pending == preparation, confirmation == preparation.confirmationText else {
            throw DesktopManagedServiceRepairError.confirmationRequired
        }
        busy = true
        var durable = false
        defer {
            busy = false; pending = nil
            if !durable { for e in preparation.snapshot.entries { if let url = workspace(containing: e.source) { try? FileManager.default.removeItem(at: url) } } }
        }
        let lease = try journal.acquireOperationLease(allowComponentRepair: true)
        defer { withExtendedLifetime(lease) {} }
        let installer = DesktopManagedInstaller(layout: layout)
        let r = preparation.snapshot
        guard try journal.loadReadOnly() == r.journal,
              Self.hash(try installer.readOwnedPrivateFile(layout.connectorLaunchAgent)) == r.agentDigest else {
            throw DesktopManagedServiceRepairError.stalePreparation
        }
        let remote = try await requireIdentity(r.journal)
        guard let healthCheckpoint = remote.checkedAt else { throw DesktopManagedServiceRepairError.healthFailed }
        guard try credentialDigest(installer: installer, remote: remote) == preparation.credentialDigest,
              Self.hash(try installer.readOwnedPrivateFile(layout.hermesSessionToken)) == preparation.tokenDigest else {
            throw DesktopManagedServiceRepairError.accountOrMachineMismatch
        }
        try validateSnapshot(r)
        if let old = try loadSnapshot(installer: installer) {
            guard old.id == r.id, old.journal == r.journal, old.agentDigest == r.agentDigest, old.envelope == r.envelope else {
                throw DesktopManagedServiceRepairError.stalePreparation
            }
        }
        for e in r.entries {
            try Self.validateReplacement(entry: e, destination: root(kind: e.kind, hash: e.contentHash), backup: quarantine(id: r.id, kind: e.kind))
        }
        try installer.ensurePrivateDirectory(paths.migrationJournalRoot)
        try installer.atomicWrite(JSONEncoder().encode(r), to: recordURL, permissions: 0o600)
        durable = true
        do {
            try await stop()
            // No disk, account or process mutations follow a stop that could not be proved.
            _ = try await requireIdentity(r.journal)
            guard try journal.loadReadOnly() == r.journal,
                  Self.hash(try installer.readOwnedPrivateFile(layout.connectorLaunchAgent)) == r.agentDigest,
                  try credentialDigest(installer: installer, remote: remote) == preparation.credentialDigest,
                  Self.hash(try installer.readOwnedPrivateFile(layout.hermesSessionToken)) == preparation.tokenDigest else {
                throw DesktopManagedServiceRepairError.stalePreparation
            }
            for entry in r.entries {
                try Self.replace(entry: entry, destination: root(kind: entry.kind, hash: entry.contentHash),
                                 backup: quarantine(id: r.id, kind: entry.kind))
            }
            try start()
            var healthy = false
            for _ in 0..<75 {
                let after = try await requireIdentity(r.journal)
                if runtimeProof(), after.healthy, let time = after.checkedAt,
                   time > healthCheckpoint { healthy = true; break }
                try await Task.sleep(nanoseconds: 1_000_000_000)
            }
            guard healthy else { throw DesktopManagedServiceRepairError.healthFailed }
            // Retain old damaged bytes as quarantine. Completion is archived, not silently erased.
            try FileManager.default.moveItem(at: recordURL,
                to: paths.migrationJournalRoot.appendingPathComponent("component-content-repair-completed-\(r.id.uuidString).json"))
        } catch {
            var failed = r; failed.failed = true
            try? installer.atomicWrite(JSONEncoder().encode(failed), to: recordURL, permissions: 0o600)
            // Do not restart unverified old content. A subsequent explicit repair resumes this record.
            throw error
        }
    }

    static func validateReplacement(entry: DesktopComponentRepairSnapshot.Entry, destination: URL, backup: URL) throws {
        let current = try optionalDigest(destination)
        if current == entry.candidateDigest {
            guard try optionalDigest(backup) == entry.beforeDigest else { throw DesktopManagedServiceRepairError.stalePreparation }
            return
        }
        guard current == entry.beforeDigest || current == nil,
              try DesktopAppUpdateTransaction.digest(entry.source) == entry.candidateDigest else {
            throw DesktopManagedServiceRepairError.stalePreparation
        }
        if let current {
            guard try optionalDigest(backup) == nil, current == entry.beforeDigest else { throw DesktopManagedServiceRepairError.stalePreparation }
        } else if let before = entry.beforeDigest {
            guard try optionalDigest(backup) == before else { throw DesktopManagedServiceRepairError.stalePreparation }
        } else {
            guard try optionalDigest(backup) == nil else { throw DesktopManagedServiceRepairError.stalePreparation }
        }
    }

    static func replace(entry: DesktopComponentRepairSnapshot.Entry, destination: URL, backup: URL) throws {
        try validateReplacement(entry: entry, destination: destination, backup: backup)
        let fm = FileManager.default
        let current = try optionalDigest(destination)
        if current == entry.candidateDigest { return }
        if current != nil { try fm.moveItem(at: destination, to: backup) }
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        try fm.moveItem(at: entry.source, to: destination)
    }

    private func root(kind: DesktopManagedComponentKind, hash: String) -> URL {
        paths.managedRoot.appendingPathComponent("components/\(kind.rawValue)/\(hash)")
    }
    private func quarantine(id: UUID, kind: DesktopManagedComponentKind) -> URL {
        paths.managedRoot.appendingPathComponent("components/\(kind.rawValue)/.repair-old-\(id.uuidString)")
    }
    private func workspace(containing source: URL) -> URL? {
        let base = paths.workspaceRoot.resolvingSymlinksInPath().path + "/"
        guard source.resolvingSymlinksInPath().path.hasPrefix(base) else { return nil }
        let first = String(source.path.dropFirst(base.count)).split(separator: "/").first.map(String.init) ?? ""
        guard UUID(uuidString: first) != nil else { return nil }
        return paths.workspaceRoot.appendingPathComponent(first)
    }
    private func validateSnapshot(_ r: DesktopComponentRepairSnapshot) throws {
        guard !r.entries.isEmpty, r.entries.count <= 2, Set(r.entries.map(\.kind)).count == r.entries.count else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        for e in r.entries {
            guard [DesktopManagedComponentKind.nodeRuntime, .connector].contains(e.kind),
                  e.contentHash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                  workspace(containing: e.source) != nil,
                  e.source.standardizedFileURL.path == e.source.resolvingSymlinksInPath().path,
                  e.source.path.hasSuffix("/repair-store/components/\(e.kind.rawValue)/\(e.contentHash)"),
                  root(kind: e.kind, hash: e.contentHash).path == root(kind: e.kind, hash: e.contentHash).resolvingSymlinksInPath().path,
                  quarantine(id: r.id, kind: e.kind).path == quarantine(id: r.id, kind: e.kind).resolvingSymlinksInPath().path
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        }
    }
    private func loadSnapshot(installer: DesktopManagedInstaller) throws -> DesktopComponentRepairSnapshot? {
        guard FileManager.default.fileExists(atPath: recordURL.path) else { return nil }
        let attrs = try FileManager.default.attributesOfItem(atPath: recordURL.path)
        guard attrs[.type] as? FileAttributeType == .typeRegular,
              (attrs[.ownerAccountID] as? NSNumber)?.uint32Value == getuid(),
              ((attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0) & 0o077 == 0,
              ((attrs[.size] as? NSNumber)?.intValue ?? Int.max) <= 512 * 1024 else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        let result = try JSONDecoder().decode(DesktopComponentRepairSnapshot.self, from: Data(contentsOf: recordURL))
        try validateSnapshot(result); return result
    }
    private func requireIdentity(_ installed: DesktopMigrationJournal) async throws -> DesktopManagedServiceRepairIdentity {
        guard let remote = try await identity(), remote.bindingID == installed.bindingID, remote.generation == installed.bindingGeneration else {
            throw DesktopManagedServiceRepairError.accountOrMachineMismatch
        }
        return remote
    }
    private func validateAgent(_ data: Data, manifest: DesktopComponentReleaseManifestV2) throws {
        guard let connector = manifest.components.first(where: { $0.kind == .connector }),
              let node = manifest.components.first(where: { $0.kind == .nodeRuntime }),
              let object = try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let environment = object["EnvironmentVariables"] as? [String: String] else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        var ws = URLComponents(url: gatewayURL, resolvingAgainstBaseURL: false)
        ws?.scheme = "wss"; ws?.path = "/v2/connect"
        guard let url = ws?.url else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        let expected = DesktopAccountConnectorLaunchAgent(
            connectorExecutable: root(kind: .connector, hash: connector.contentSHA256).appendingPathComponent("content/" + connector.entrypoint),
            credentialFile: layout.connectorCredential, gatewayURL: url, hermesBaseURL: DesktopHermesRuntimeContract.serveV1.baseURL,
            sessionTokenFile: layout.hermesSessionToken, standardOutput: layout.logsRoot.appendingPathComponent("connector.log"),
            standardError: layout.logsRoot.appendingPathComponent("connector.error.log"),
            nodeRuntimeRoot: root(kind: .nodeRuntime, hash: node.contentSHA256).appendingPathComponent("content"))
        var expectedObject = try PropertyListSerialization.propertyList(from: expected.encodedPropertyList(), format: nil) as! [String: Any]
        var expectedEnvironment = expectedObject["EnvironmentVariables"] as! [String: String]
        expectedEnvironment.merge(try DesktopConnectorFileRate.overrides(from: environment)) { _, new in new }
        expectedObject["EnvironmentVariables"] = expectedEnvironment
        guard NSDictionary(dictionary: object).isEqual(to: expectedObject) else { throw DesktopManagedServiceRepairError.unsafeInstallation }
    }

    private func credentialDigest(installer: DesktopManagedInstaller, remote: DesktopManagedServiceRepairIdentity) throws -> String {
        let data = try installer.readOwnedPrivateFile(layout.connectorCredential)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == ["schemaVersion", "bindingId", "generation", "publicKeyFingerprint", "privateKey"], object["schemaVersion"] as? Int == 1,
              object["bindingId"] as? String == remote.bindingID, object["generation"] as? Int == remote.generation,
              object["publicKeyFingerprint"] as? String == remote.fingerprint,
              let raw = object["privateKey"] as? String, let bytes = Data(desktopCanonicalBase64URL: raw),
              let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: bytes), Self.hash(key.publicKey.rawRepresentation) == remote.fingerprint else {
            throw DesktopManagedServiceRepairError.accountOrMachineMismatch
        }
        return Self.hash(data)
    }
    static func optionalDigest(_ url: URL) throws -> String? {
        var metadata = stat()
        guard Darwin.lstat(url.path, &metadata) == 0 else {
            if errno == ENOENT { return nil }
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        guard metadata.st_mode & S_IFMT == S_IFDIR, metadata.st_uid == getuid(), metadata.st_mode & 0o022 == 0,
              url.standardizedFileURL.path == url.resolvingSymlinksInPath().path else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        return try DesktopAppUpdateTransaction.digest(url)
    }
    private static func hash(_ value: Data) -> String { SHA256.hash(data: value).map { String(format: "%02x", $0) }.joined() }
}
