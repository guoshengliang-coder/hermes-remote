import CryptoKit
import Darwin
import Foundation

public enum DesktopManagedServiceRepairError: Error, Equatable, Sendable {
    case installationRecordMissing
    case accountOrMachineMismatch
    case unsafeInstallation
    case unknownService
    case portOccupied
    case stalePreparation
    case confirmationRequired
    case healthFailed
    case restorationFailed
}

/// A fresh authenticated view of the exact installed binding, not an installation request.
public struct DesktopManagedServiceRepairIdentity: Equatable, Sendable {
    public let bindingID: String
    public let generation: Int
    public let fingerprint: String
    public let healthy: Bool
    public let checkedAt: Date?

    public init(bindingID: String, generation: Int, fingerprint: String, healthy: Bool, checkedAt: Date?) {
        self.bindingID = bindingID
        self.generation = generation
        self.fingerprint = fingerprint
        self.healthy = healthy
        self.checkedAt = checkedAt
    }

    public init?(account: DesktopAccountState) {
        guard case .signedIn(let dashboard) = account,
              dashboard.binding.state == "bound", let binding = dashboard.binding.binding
        else { return nil }
        self.init(
            bindingID: binding.id, generation: binding.generation,
            fingerprint: binding.publicKeyFingerprint,
            healthy: binding.connector.online && binding.hermes.reachable == true
                && binding.endToEnd.healthy == true,
            checkedAt: binding.endToEnd.checkedAt.flatMap(parseCanonicalTimestamp)
        )
    }
}

public struct DesktopManagedServiceRepairPreparation: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let releaseVersion: String
    public let filePaths: [String]
    public let usesLocalHermes: Bool
    public let resumesInterruptedRepair: Bool
    fileprivate let image: RepairImage
}

/// No installation, replacement binding, key generation, token writes or version change occurs here.
/// Preparation is read-only. A journal lease plus an exact, private durable image protects commit,
/// rollback, and recovery after app termination. Loaded jobs are never treated as proof of identity
/// without checking the arguments launchd actually loaded.
public actor DesktopManagedServiceRepair<Runner: CommandRunning> {
    public typealias IdentityReader = @Sendable () async throws -> DesktopManagedServiceRepairIdentity?
    public typealias LoadedArguments = @Sendable (String) throws -> [String]?
    public typealias LocalDetector = @Sendable () -> DesktopLocalHermesDetection
    private let installer: DesktopManagedInstaller
    private let journal: DesktopMigrationJournalStore
    private let agents: DesktopLaunchAgentController<Runner>
    private let gatewayURL: URL
    private let hermesHome: URL
    private let identity: IdentityReader
    private let loadedArguments: LoadedArguments
    private let localDetector: LocalDetector
    private let readiness: any DesktopHermesCandidateReadinessChecking
    private let shutdown: any DesktopHermesShutdownChecking
    private let polls: Int
    private let delay: UInt64
    private var prepared: DesktopManagedServiceRepairPreparation?
    private var executing = false
    private var snapshotURL: URL { installer.layout.stateRoot.appendingPathComponent("service-repair.json") }

    public init(
        installer: DesktopManagedInstaller, journal: DesktopMigrationJournalStore,
        agents: DesktopLaunchAgentController<Runner>, gatewayURL: URL, hermesHome: URL,
        identity: @escaping IdentityReader, loadedArguments: @escaping LoadedArguments,
        localDetector: @escaping LocalDetector,
        readiness: any DesktopHermesCandidateReadinessChecking = DesktopHermesCandidateReadinessChecker(),
        shutdown: any DesktopHermesShutdownChecking = DesktopHermesShutdownChecker(),
        polls: Int = 75, delay: UInt64 = 1_000_000_000
    ) {
        self.installer = installer; self.journal = journal; self.agents = agents
        self.gatewayURL = gatewayURL; self.hermesHome = hermesHome; self.identity = identity
        self.loadedArguments = loadedArguments; self.localDetector = localDetector
        self.readiness = readiness; self.shutdown = shutdown
        self.polls = min(max(polls, 1), 300); self.delay = delay
    }

    /// Cheap status check; it never creates directories, locks or credentials.
    public func needsRepair() throws -> Bool {
        guard let record = try journal.loadReadOnly(), record.state == .accountActive else { return false }
        if try optionalFile(snapshotURL) != nil { return true }
        let services = try inspectServices()
        return !services.accountLoaded || !services.hermesLoaded
            || !exists(installer.layout.connectorLaunchAgent) || !exists(installer.layout.hermesLaunchAgent)
    }

    public func prepare() async throws -> DesktopManagedServiceRepairPreparation {
        guard !executing else { throw DesktopManagedServiceRepairError.stalePreparation }
        guard let record = try journal.loadReadOnly(), record.state == .accountActive else {
            throw DesktopManagedServiceRepairError.installationRecordMissing
        }
        let remote = try await requireIdentity(record)
        let credential = try validateCredential(record, remote: remote)
        guard try optionalFile(installer.layout.hermesSessionToken) != nil else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        _ = try installer.validatedSessionTokenIfPresent(required: true)
        let image: RepairImage
        let recovering: Bool
        if let data = try optionalFile(snapshotURL) {
            guard let stored = try? JSONDecoder().decode(RepairImage.self, from: data),
                  stored.schemaVersion == 1, stored.record == record,
                  stored.credentialDigest == digest(credential),
                  stored.tokenDigest == digest(try installer.readOwnedPrivateFile(installer.layout.hermesSessionToken))
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            try validateImage(stored)
            image = stored; recovering = true
        } else {
            image = try await makeImage(record: record, credential: credential)
            recovering = false
        }
        let plan = DesktopManagedServiceRepairPreparation(
            id: UUID(), releaseVersion: record.releaseVersion,
            filePaths: [installer.layout.hermesLaunchAgent.path, installer.layout.connectorLaunchAgent.path]
                + (image.launcher != nil && image.originalLauncher == nil ? [installer.layout.localHermesLauncher.path] : []),
            usesLocalHermes: image.localExecutable != nil,
            resumesInterruptedRepair: recovering, image: image
        )
        prepared = plan
        return plan
    }

    public func cancel(_ preparation: DesktopManagedServiceRepairPreparation) {
        guard !executing, prepared == preparation else { return }
        prepared = nil
    }

    public func commit(_ preparation: DesktopManagedServiceRepairPreparation, confirmed: Bool) async throws {
        guard confirmed else { throw DesktopManagedServiceRepairError.confirmationRequired }
        guard !executing, prepared == preparation else { throw DesktopManagedServiceRepairError.stalePreparation }
        executing = true
        defer { executing = false; prepared = nil }
        let lease = try journal.acquireOperationLease()
        defer { withExtendedLifetime(lease) {} }
        let image = preparation.image
        guard try journal.loadReadOnly() == image.record else { throw DesktopManagedServiceRepairError.stalePreparation }
        let remote = try await requireIdentity(image.record)
        guard digest(try validateCredential(image.record, remote: remote)) == image.credentialDigest,
              digest(try installer.readOwnedPrivateFile(installer.layout.hermesSessionToken)) == image.tokenDigest
        else { throw DesktopManagedServiceRepairError.stalePreparation }
        try validateImage(image)
        if preparation.resumesInterruptedRepair {
            guard let snapshot = try optionalFile(snapshotURL),
                  (try? JSONDecoder().decode(RepairImage.self, from: snapshot)) == image else {
                throw DesktopManagedServiceRepairError.stalePreparation
            }
            // The previous confirmation authorized only these changes. Undo them first, then let
            // the owner inspect a new preparation; never launch a second transaction on top.
            try await restore(image)
            return
        }
        guard try optionalFile(snapshotURL) == nil,
              try optionalFile(installer.layout.hermesLaunchAgent) == image.originalHermes,
              try optionalFile(installer.layout.connectorLaunchAgent) == image.originalConnector,
              try optionalFile(installer.layout.localHermesLauncher) == image.originalLauncher,
              try inspectServices() == image.services
        else { throw DesktopManagedServiceRepairError.stalePreparation }
        if !image.services.hermesLoaded { try await requireFreePort() }
        guard try inspectServices() == image.services,
              try optionalFile(installer.layout.hermesLaunchAgent) == image.originalHermes,
              try optionalFile(installer.layout.connectorLaunchAgent) == image.originalConnector,
              try optionalFile(installer.layout.localHermesLauncher) == image.originalLauncher
        else { throw DesktopManagedServiceRepairError.stalePreparation }
        try requireLoadedArguments(image)
        try installer.ensurePrivateDirectory(installer.layout.stateRoot)
        try installer.atomicWrite(try JSONEncoder().encode(image), to: snapshotURL, permissions: 0o600)
        do {
            // Missing files alone do not justify stopping a still-running Hermes. Preserve both
            // existing files byte-for-byte, including its optional environment and original runtime.
            if let launcher = image.launcher, image.originalLauncher == nil {
                try installer.ensurePrivateDirectory(installer.layout.localHermesLauncher.deletingLastPathComponent())
                try installer.atomicWrite(launcher, to: installer.layout.localHermesLauncher, permissions: 0o700)
            }
            if image.originalHermes == nil {
                try installer.ensureOwnedDirectory(installer.layout.launchAgentsRoot)
                try installer.atomicWrite(image.hermes, to: installer.layout.hermesLaunchAgent, permissions: 0o600)
            }
            if image.originalConnector == nil {
                try installer.ensureOwnedDirectory(installer.layout.launchAgentsRoot)
                try installer.atomicWrite(image.connector, to: installer.layout.connectorLaunchAgent, permissions: 0o600)
            }
            if !image.services.hermesLoaded {
                try await requireFreePort()
                let checkpoint = try readiness.checkpoint(logURL: installer.managedHermesLogURL)
                try agents.startHermes(plistURL: installer.layout.hermesLaunchAgent)
                guard try await readiness.waitUntilReady(
                    checkpoint: checkpoint, contract: .serveV1, maximumAttempts: polls, delayNanoseconds: delay
                ) else { throw DesktopManagedServiceRepairError.healthFailed }
            }
            let checkpoint = try await requireIdentity(image.record).checkedAt
            try requireLoadedArguments(image)
            // Restart only a proven Connector with a surviving original file. If its file was
            // missing but its job survived, keep it running and require a fresh periodic health proof.
            if image.services.accountLoaded, image.originalConnector != nil { try agents.stopAccount() }
            if !(try inspectServices()).accountLoaded {
                try agents.startAccount(plistURL: installer.layout.connectorLaunchAgent)
            }
            try await waitForHealth(image.record, newerThan: checkpoint)
            guard try inspectServices() == DesktopLaunchAgentServiceState(
                legacyLoaded: false, accountLoaded: true, hermesLoaded: true
            ) else { throw DesktopManagedServiceRepairError.unknownService }
            // account_active stays intact throughout. A leftover snapshot is always conservative:
            // the next explicit recovery restores the pre-repair files/services.
            try FileManager.default.removeItem(at: snapshotURL)
        } catch {
            do { try await restore(image) }
            catch { throw DesktopManagedServiceRepairError.restorationFailed }
            throw error
        }
    }

    private func makeImage(record: DesktopMigrationJournal, credential: Data) async throws -> RepairImage {
        let layout = installer.layout
        let services = try inspectServices()
        guard !services.legacyLoaded else { throw DesktopManagedServiceRepairError.unknownService }
        let originalHermes = try optionalFile(layout.hermesLaunchAgent)
        let originalConnector = try optionalFile(layout.connectorLaunchAgent)
        let components = try trustedComponents(record)
        let reconstructedConnector = try expectedConnector(record, components: components)
        var localExecutable: String?
        let hermes: Data
        if let originalHermes {
            let plist = try installer.loadHermesLaunchAgentForTokenContract()
            guard try installer.sessionTokenStorage(in: plist.object) == .file else {
                throw DesktopManagedServiceRepairError.unsafeInstallation
            }
            if case .localHermes(let executable) = try installer.currentHermesRuntimeMode() {
                guard localDetector().installation?.executable == executable,
                      installer.localHermesLauncherIsCurrent(executable: executable)
                else { throw DesktopManagedServiceRepairError.unsafeInstallation }
                localExecutable = executable.path
            }
            hermes = originalHermes
        } else {
            let detection = localDetector()
            if let local = detection.installation {
                if try optionalFile(layout.localHermesRuntimeRecord) != nil {
                    guard installer.readLocalHermesRuntimeRecord()?.executable == local.executable.path else {
                        throw DesktopManagedServiceRepairError.unsafeInstallation
                    }
                }
                // The owner already has Hermes: never start a second bundled copy on its database.
                if exists(layout.localHermesLauncher), !installer.localHermesLauncherIsCurrent(executable: local.executable) {
                    throw DesktopManagedServiceRepairError.unsafeInstallation
                }
                localExecutable = local.executable.path
                hermes = try installer.localHermesLaunchAgent(for: local).encodedPropertyList()
            } else {
                guard case .absent = detection,
                      !exists(layout.localHermesRuntimeRecord), !exists(layout.localHermesLauncher),
                      record.releaseLayout == .bundledRelease
                else { throw DesktopManagedServiceRepairError.unsafeInstallation }
                hermes = try DesktopHermesServerLaunchAgent(
                    hermesExecutable: layout.currentRelease.appendingPathComponent("hermes_server/bin/hermes-server"),
                    hermesHome: hermesHome, runtimeContract: .serveV1, sessionTokenFile: layout.hermesSessionToken,
                    standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
                    standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log")
                ).encodedPropertyList()
            }
        }
        let connector = originalConnector ?? reconstructedConnector
        let image = RepairImage(
            schemaVersion: 1, record: record, services: services,
            originalHermes: originalHermes, originalConnector: originalConnector,
            hermes: hermes, connector: connector,
            credentialDigest: digest(credential), tokenDigest: digest(try installer.readOwnedPrivateFile(layout.hermesSessionToken)),
            components: components, localExecutable: localExecutable,
            originalLauncher: try optionalFile(layout.localHermesLauncher),
            launcher: try localExecutable.map { try DesktopLocalHermesLauncher.script(executable: URL(fileURLWithPath: $0)) }
        )
        try validateImage(image)
        try requireLoadedArguments(image)
        if !services.hermesLoaded { try await requireFreePort() }
        return image
    }

    private func expectedConnector(_ record: DesktopMigrationJournal, components: [String: String]) throws -> Data {
        let layout = installer.layout
        func componentRoot(_ kind: DesktopManagedComponentKind) throws -> URL {
            guard let path = components.keys.first(where: { $0.contains("/components/\(kind.rawValue)/") }) else {
                throw DesktopManagedServiceRepairError.unsafeInstallation
            }
            return URL(fileURLWithPath: path)
        }
        let connectorExecutable = record.releaseLayout == .bundledRelease
            ? layout.currentRelease.appendingPathComponent("connector/bin/hermes-connector")
            : try componentRoot(.connector).appendingPathComponent("bin/hermes-connector")
        let nodeRoot = record.releaseLayout == .componentStore ? try componentRoot(.nodeRuntime) : nil
        var ws = URLComponents(url: gatewayURL, resolvingAgainstBaseURL: false)
        ws?.scheme = gatewayURL.scheme == "https" ? "wss" : "ws"
        ws?.path = "/v2/connect"
        guard let gateway = ws?.url else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        return try DesktopAccountConnectorLaunchAgent(
            connectorExecutable: connectorExecutable, credentialFile: layout.connectorCredential,
            gatewayURL: gateway, hermesBaseURL: DesktopHermesRuntimeContract.serveV1.baseURL,
            sessionTokenFile: layout.hermesSessionToken,
            standardOutput: layout.logsRoot.appendingPathComponent("connector.log"),
            standardError: layout.logsRoot.appendingPathComponent("connector.error.log"), nodeRuntimeRoot: nodeRoot
        ).encodedPropertyList()
    }

    private func connectorArguments(_ data: Data) throws -> [String] {
        guard let args = try plist(data)["ProgramArguments"] as? [String], args.count == 1 else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        return args
    }

    private func trustedComponents(_ record: DesktopMigrationJournal) throws -> [String: String] {
        let layout = installer.layout
        var components: [String: String] = [:]
        if record.releaseLayout == .bundledRelease {
            guard (try? FileManager.default.destinationOfSymbolicLink(atPath: layout.currentRelease.path))
                    == "releases/\(record.releaseVersion)" else {
                throw DesktopManagedServiceRepairError.unsafeInstallation
            }
            let release = try layout.release(record.releaseVersion)
            let marker = try installer.readOwnedPrivateFile(release.appendingPathComponent(".hermes-go-managed-release.json"))
            guard let object = try JSONSerialization.jsonObject(with: marker) as? [String: Any],
                  object["schemaVersion"] as? Int == 1,
                  object["releaseVersion"] as? String == record.releaseVersion,
                  UUID(uuidString: object["runID"] as? String ?? "") != nil
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            components[release.path] = try DesktopManagedComponentContentHasher().identify(directory: release).sha256
        } else {
            let data = try installer.readOwnedPrivateFile(layout.root.appendingPathComponent("references/\(record.releaseVersion).json"))
            guard let reference = try? JSONDecoder().decode(DesktopManagedComponentReferenceSet.self, from: data),
                  reference.schemaVersion == 1, reference.releaseVersion == record.releaseVersion,
                  Set(reference.components.map(\.kind)).count == reference.components.count,
                  Set(reference.components.map(\.kind)).isSuperset(of: [.connector, .nodeRuntime])
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            for component in reference.components {
                let root = layout.root.appendingPathComponent("components/\(component.kind.rawValue)/\(component.contentSHA256)/content")
                guard component.contentSHA256.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
                      try DesktopManagedComponentContentHasher().identify(directory: root).sha256 == component.contentSHA256
                else { throw DesktopManagedServiceRepairError.unsafeInstallation }
                let receipt = try installer.readOwnedPrivateFile(root.deletingLastPathComponent().appendingPathComponent("receipt.json"))
                guard let decoded = try? JSONDecoder().decode(DesktopManagedComponentReceipt.self, from: receipt),
                      decoded.kind == component.kind, decoded.contentSHA256 == component.contentSHA256,
                      decoded.schemaVersion == 1
                else { throw DesktopManagedServiceRepairError.unsafeInstallation }
                components[root.path] = component.contentSHA256
            }
        }
        return components
    }

    private func validateImage(_ image: RepairImage) throws {
        let layout = installer.layout
        for directory in [layout.root, layout.stateRoot, layout.secretsRoot, layout.logsRoot] {
            var metadata = stat()
            guard directory.resolvingSymlinksInPath().path == directory.path,
                  Darwin.lstat(directory.path, &metadata) == 0,
                  metadata.st_mode & S_IFMT == S_IFDIR, metadata.st_uid == Darwin.getuid(),
                  metadata.st_mode & 0o077 == 0 else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        }
        guard image.schemaVersion == 1, image.record.state == .accountActive, !image.services.legacyLoaded,
              image.originalHermes == nil || image.originalHermes == image.hermes,
              image.originalConnector == nil || image.originalConnector == image.connector,
              image.originalLauncher == nil || image.launcher == nil || image.originalLauncher == image.launcher,
              image.components == (try trustedComponents(image.record)) else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        for (path, expected) in image.components {
            let url = URL(fileURLWithPath: path)
            guard path.hasPrefix(layout.root.path + "/"), url.resolvingSymlinksInPath() == url,
                  try DesktopManagedComponentContentHasher().identify(directory: url).sha256 == expected
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        }
        let connectorObject = try plist(image.connector)
        var expectedEnvironment = try plist(expectedConnector(image.record, components: image.components))["EnvironmentVariables"] as? [String: String]
        // A surviving pre-HG-101 agent may omit HERMES_MODE; do not rewrite it during recovery.
        let actualEnvironment = connectorObject["EnvironmentVariables"] as? [String: String]
        if actualEnvironment?["HERMES_MODE"] == nil { expectedEnvironment?.removeValue(forKey: "HERMES_MODE") }
        guard actualEnvironment == expectedEnvironment,
              try connectorArguments(image.connector) == connectorArguments(expectedConnector(image.record, components: image.components))
        else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        for (data, label, logName, errorName) in [
            (image.hermes, DesktopManagedInstallLayout.hermesLabel, "hermes-server.log", "hermes-server.error.log"),
            (image.connector, DesktopManagedInstallLayout.connectorLabel, "connector.log", "connector.error.log"),
        ] {
            let object = try plist(data)
            guard object["Label"] as? String == label,
                  object["StandardOutPath"] as? String == layout.logsRoot.appendingPathComponent(logName).path,
                  object["StandardErrorPath"] as? String == layout.logsRoot.appendingPathComponent(errorName).path,
                  let args = object["ProgramArguments"] as? [String], let first = args.first,
                  let environment = object["EnvironmentVariables"] as? [String: String],
                  environment["HERMES_SESSION_TOKEN_FILE"] == layout.hermesSessionToken.path,
                  environment["HERMES_SESSION_TOKEN"] == nil, environment["HERMES_DASHBOARD_SESSION_TOKEN"] == nil,
                  args.allSatisfy({ !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) })
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            let resolved = URL(fileURLWithPath: first).resolvingSymlinksInPath()
            guard image.components.keys.contains(where: { resolved.path.hasPrefix($0 + "/") })
                    || (label == DesktopManagedInstallLayout.hermesLabel && first == layout.localHermesLauncher.path),
                  (first == layout.localHermesLauncher.path && image.launcher != nil)
                    || FileManager.default.isExecutableFile(atPath: resolved.path)
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            if label == DesktopManagedInstallLayout.hermesLabel {
                let expected = image.localExecutable.map { [layout.localHermesLauncher.path, $0] }
                    ?? [layout.currentRelease.appendingPathComponent("hermes_server/bin/hermes-server").path]
                guard args == expected + DesktopHermesRuntimeContract.serveV1.programArguments,
                      environment["HERMES_HOME"] == hermesHome.path
                else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            } else {
                guard args.count == 1,
                      environment["ACCOUNT_CONNECTOR_CREDENTIAL_FILE"] == layout.connectorCredential.path
                else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            }
        }
        if let executable = image.localExecutable {
            let currentLauncher = try optionalFile(layout.localHermesLauncher)
            guard let local = localDetector().installation, local.executable.path == executable,
                  image.launcher == (try DesktopLocalHermesLauncher.script(executable: local.executable)),
                  currentLauncher == image.originalLauncher || currentLauncher == image.launcher
            else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        }
    }

    private func inspectServices() throws -> DesktopLaunchAgentServiceState {
        let state = DesktopLaunchAgentServiceState(
            legacyLoaded: try loadedArguments(DesktopLaunchAgentController<Runner>.legacyLabel) != nil,
            accountLoaded: try loadedArguments(DesktopManagedInstallLayout.connectorLabel) != nil,
            hermesLoaded: try loadedArguments(DesktopManagedInstallLayout.hermesLabel) != nil
        )
        guard !state.hasDuplicateConnector else { throw DesktopManagedServiceRepairError.unknownService }
        return state
    }

    private func requireLoadedArguments(_ image: RepairImage) throws {
        let services = try inspectServices()
        guard !services.legacyLoaded else { throw DesktopManagedServiceRepairError.unknownService }
        for (loaded, label, data) in [
            (services.hermesLoaded, DesktopManagedInstallLayout.hermesLabel, image.hermes),
            (services.accountLoaded, DesktopManagedInstallLayout.connectorLabel, image.connector),
        ] where loaded {
            guard try loadedArguments(label) == (try plist(data))["ProgramArguments"] as? [String] else {
                throw DesktopManagedServiceRepairError.unknownService
            }
        }
    }

    private func restore(_ image: RepairImage) async throws {
        _ = try await requireIdentity(image.record)
        try requireLoadedArguments(image)
        // Refuse an externally changed file before stopping any service.
        for (url, original, target) in [
            (installer.layout.hermesLaunchAgent, image.originalHermes, image.hermes),
            (installer.layout.connectorLaunchAgent, image.originalConnector, image.connector),
        ] {
            let current = try optionalFile(url)
            guard current == original || current == target else { throw DesktopManagedServiceRepairError.unsafeInstallation }
        }
        let healthCheckpoint = try await requireIdentity(image.record).checkedAt
        var services = try inspectServices()
        if services.accountLoaded, !image.services.accountLoaded { try agents.stopAccount() }
        if services.hermesLoaded, !image.services.hermesLoaded { try agents.stopHermes() }
        for (url, original, target) in [
            (installer.layout.hermesLaunchAgent, image.originalHermes, image.hermes),
            (installer.layout.connectorLaunchAgent, image.originalConnector, image.connector),
        ] {
            let current = try optionalFile(url)
            guard current == original || current == target else { throw DesktopManagedServiceRepairError.unsafeInstallation }
            if let original {
                if current != original { try installer.atomicWrite(original, to: url, permissions: 0o600) }
            } else if current != nil { try FileManager.default.removeItem(at: url) }
        }
        if image.launcher != nil {
            let url = installer.layout.localHermesLauncher
            let current = try optionalFile(url)
            guard current == image.originalLauncher || current == image.launcher else {
                throw DesktopManagedServiceRepairError.unsafeInstallation
            }
            if let original = image.originalLauncher {
                if current != original { try installer.atomicWrite(original, to: url, permissions: 0o700) }
            } else if current != nil { try FileManager.default.removeItem(at: url) }
        }
        services = try inspectServices()
        if image.services.hermesLoaded, !services.hermesLoaded {
            try await requireFreePort()
            let checkpoint = try readiness.checkpoint(logURL: installer.managedHermesLogURL)
            try agents.startHermes(plistURL: installer.layout.hermesLaunchAgent)
            guard try await readiness.waitUntilReady(
                checkpoint: checkpoint, contract: .serveV1, maximumAttempts: polls, delayNanoseconds: delay
            ) else { throw DesktopManagedServiceRepairError.restorationFailed }
        }
        if image.services.accountLoaded, !services.accountLoaded {
            try agents.startAccount(plistURL: installer.layout.connectorLaunchAgent)
            if image.services.hermesLoaded { try await waitForHealth(image.record, newerThan: healthCheckpoint) }
        }
        guard try inspectServices() == image.services else { throw DesktopManagedServiceRepairError.restorationFailed }
        try FileManager.default.removeItem(at: snapshotURL)
    }

    private func requireIdentity(_ record: DesktopMigrationJournal) async throws -> DesktopManagedServiceRepairIdentity {
        guard let remote = try await identity(), remote.bindingID == record.bindingID,
              remote.generation == record.bindingGeneration else {
            throw DesktopManagedServiceRepairError.accountOrMachineMismatch
        }
        return remote
    }

    private func validateCredential(_ record: DesktopMigrationJournal, remote: DesktopManagedServiceRepairIdentity) throws -> Data {
        guard let data = try optionalFile(installer.layout.connectorCredential) else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == ["schemaVersion", "bindingId", "generation", "publicKeyFingerprint", "privateKey"],
              object["schemaVersion"] as? Int == 1, object["bindingId"] as? String == record.bindingID,
              object["generation"] as? Int == record.bindingGeneration,
              object["publicKeyFingerprint"] as? String == remote.fingerprint,
              let encoded = object["privateKey"] as? String,
              let keyData = Data(base64Encoded: encoded.replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/") + String(repeating: "=", count: (4 - encoded.count % 4) % 4)),
              let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: keyData),
              digest(key.publicKey.rawRepresentation) == remote.fingerprint
        else { throw DesktopManagedServiceRepairError.accountOrMachineMismatch }
        return data
    }

    private func waitForHealth(_ record: DesktopMigrationJournal, newerThan checkpoint: Date?) async throws {
        for attempt in 0..<polls {
            let remote = try await requireIdentity(record)
            if remote.healthy, let time = remote.checkedAt, checkpoint.map({ time > $0 }) ?? true { return }
            if attempt + 1 < polls, delay > 0 { try await Task.sleep(nanoseconds: delay) }
        }
        throw DesktopManagedServiceRepairError.healthFailed
    }

    private func requireFreePort() async throws {
        guard try await shutdown.waitUntilStopped(contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0) else {
            throw DesktopManagedServiceRepairError.portOccupied
        }
    }

    private func exists(_ url: URL) -> Bool {
        var metadata = stat()
        return Darwin.lstat(url.path, &metadata) == 0 || errno != ENOENT
    }
    private func optionalFile(_ url: URL) throws -> Data? {
        guard exists(url) else { return nil }
        // Parent symlinks can redirect a perfectly regular leaf. Only the historical `current`
        // release link is allowed elsewhere, never on state, credential or agent paths.
        guard url.deletingLastPathComponent().resolvingSymlinksInPath() == url.deletingLastPathComponent() else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        return try installer.readOwnedPrivateFile(url)
    }
    private func plist(_ data: Data) throws -> [String: Any] {
        guard let object = try PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any] else {
            throw DesktopManagedServiceRepairError.unsafeInstallation
        }
        return object
    }
    private func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}

fileprivate struct RepairImage: Codable, Equatable, Sendable {
    let schemaVersion: Int
    let record: DesktopMigrationJournal
    let services: DesktopLaunchAgentServiceState
    let originalHermes: Data?
    let originalConnector: Data?
    let hermes: Data
    let connector: Data
    let credentialDigest: String
    let tokenDigest: String
    let components: [String: String]
    let localExecutable: String?
    let originalLauncher: Data?
    let launcher: Data?
}

public extension DesktopManagedServiceRepair where Runner == SystemCommandRunner {
    static func system(
        account: any DesktopBindingCoordinating, paths: DesktopManagedBootstrapPaths,
        gatewayURL: URL, userID: UInt32 = Darwin.getuid()
    ) throws -> DesktopManagedServiceRepair {
        let layout = try DesktopManagedInstallLayout(root: paths.managedRoot, launchAgentsRoot: paths.launchAgentsRoot)
        let detector = DesktopLocalHermesDetector(
            paths: try DesktopLocalHermesPaths(homeDirectory: paths.hermesHome.deletingLastPathComponent())
        )
        return DesktopManagedServiceRepair(
            installer: DesktopManagedInstaller(layout: layout),
            journal: try DesktopMigrationJournalStore(root: paths.migrationJournalRoot),
            agents: try DesktopLaunchAgentController(
                userID: userID, launchAgentsRoot: paths.launchAgentsRoot,
                runner: SystemCommandRunner(capturesStandardError: true),
                log: DesktopServiceOperationLog(layout: layout)
            ), gatewayURL: gatewayURL, hermesHome: paths.hermesHome,
            identity: { DesktopManagedServiceRepairIdentity(account: try await account.refresh()) },
            loadedArguments: { label in
                let output = SystemOutputCommandRunner().run(
                    executable: URL(fileURLWithPath: "/bin/launchctl"),
                    arguments: ["print", "gui/\(userID)/\(label)"], maximumOutputBytes: 256 * 1024
                )
                if output.status == 113 { return nil }
                guard output.status == 0, !output.outputLimitExceeded,
                      let text = String(data: output.stdout, encoding: .utf8),
                      let args = DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>
                        .arguments(fromLaunchctlPrint: text)
                else { throw DesktopManagedServiceRepairError.unknownService }
                return args
            }, localDetector: { detector.detect() }
        )
    }
}
