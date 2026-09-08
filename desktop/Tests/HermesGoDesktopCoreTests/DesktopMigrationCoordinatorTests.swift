import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopMigrationCoordinatorTests: XCTestCase {
    func testHealthyCandidateCommitsOnlyAfterProofAndHealth() async throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        let outcome: DesktopMigrationOutcome
        do {
            outcome = try await fixture.coordinator.migrate(
                manifest: fixture.manifest,
                sources: fixture.sources,
                hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
                launchAgentConfiguration: fixture.launchAgentConfiguration,
                legacy: fixture.legacy,
                runID: fixture.runID,
                confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                    releaseVersion: fixture.manifest.releaseVersion
                )
            )
        } catch {
            let plist = fixture.layout.connectorLaunchAgent
            let values = try? plist.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            XCTFail("Unexpected \(error); launchctl=\(fixture.runner.events()); plist=\(plist.path) exists=\(FileManager.default.fileExists(atPath: plist.path)) regular=\(String(describing: values?.isRegularFile)) symlink=\(String(describing: values?.isSymbolicLink)) parent=\(plist.deletingLastPathComponent().resolvingSymlinksInPath().path) root=\(fixture.layout.launchAgentsRoot.resolvingSymlinksInPath().path)")
            return
        }

        XCTAssertEqual(outcome.bindingID, fixture.bindingID)
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
        XCTAssertEqual(try fixture.journal.load()?.lastKnownGoodMode, .account)
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(
            fixture.runner.events().compactMap { command -> String? in
                guard command.first == "bootstrap" else { return nil }
                return URL(fileURLWithPath: command.last!)
                    .deletingPathExtension()
                    .lastPathComponent
            },
            [DesktopManagedInstallLayout.hermesLabel, DesktopManagedInstallLayout.connectorLabel]
        )
        XCTAssertEqual(
            try FileManager.default.destinationOfSymbolicLink(atPath: fixture.layout.currentRelease.path),
            "releases/1.2.3"
        )
        let confirmCount = await fixture.account.confirmCount()
        XCTAssertEqual(confirmCount, 1)
    }

    func testCandidateStartFailureAutomaticallyRestoresLegacyAndCurrentPointer() async throws {
        let fixture = try Fixture(legacyRunning: true, failAccountStart: true)
        defer { fixture.cleanup() }

        do {
            _ = try await fixture.coordinator.migrate(
                manifest: fixture.manifest,
                sources: fixture.sources,
                hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
                launchAgentConfiguration: fixture.launchAgentConfiguration,
                legacy: fixture.legacy,
                runID: fixture.runID,
                confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                    releaseVersion: fixture.manifest.releaseVersion
                )
            )
            XCTFail("Expected account start failure")
        } catch {
            XCTAssertEqual(
                error as? DesktopLaunchAgentControllerError,
                .accountStartFailed,
                "launchctl=\(fixture.runner.events())"
            )
        }

        XCTAssertEqual(try fixture.journal.load()?.state, .legacyActive)
        XCTAssertEqual(fixture.runner.loadedLabels(), ["com.hermesremote.connector"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.currentRelease.path))
    }

    func testCleanInstallFailureReturnsToKnownUninstalledState() async throws {
        let fixture = try Fixture(legacyRunning: false, failAccountStart: true)
        defer { fixture.cleanup() }
        do {
            _ = try await fixture.coordinator.migrate(
                manifest: fixture.manifest,
                sources: fixture.sources,
                hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
                launchAgentConfiguration: fixture.launchAgentConfiguration,
                legacy: fixture.legacy,
                runID: fixture.runID,
                confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                    releaseVersion: fixture.manifest.releaseVersion
                )
            )
            XCTFail("Expected account start failure")
        } catch {
            XCTAssertEqual(error as? DesktopLaunchAgentControllerError, .accountStartFailed)
        }

        XCTAssertEqual(try fixture.journal.load()?.state, .cleanUninstalled)
        XCTAssertTrue(fixture.runner.loadedLabels().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.currentRelease.path))
    }

    func testHermesHealthTimeoutStopsServerBeforeLegacyRestoreAndNeverStartsConnector() async throws {
        let fixture = try Fixture(legacyRunning: true, hermesHealthy: false)
        defer { fixture.cleanup() }

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.migrate(
            manifest: fixture.manifest,
            sources: fixture.sources,
            hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
            launchAgentConfiguration: fixture.launchAgentConfiguration,
            legacy: fixture.legacy,
            runID: fixture.runID,
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                releaseVersion: fixture.manifest.releaseVersion
            )
        )) { error in
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .hermesHealthTimedOut)
        }

        XCTAssertEqual(try fixture.journal.load()?.state, .legacyActive)
        XCTAssertEqual(fixture.runner.loadedLabels(), ["com.hermesremote.connector"])
        let bootstrappedLabels = fixture.runner.events().compactMap { command -> String? in
            guard command.first == "bootstrap" else { return nil }
            return URL(fileURLWithPath: command.last!).deletingPathExtension().lastPathComponent
        }
        XCTAssertFalse(bootstrappedLabels.contains(DesktopManagedInstallLayout.connectorLabel))
        XCTAssertTrue(bootstrappedLabels.contains(DesktopManagedInstallLayout.hermesLabel))
    }

    func testWrongConfirmationPerformsNoRemoteOrLocalMutation() async throws {
        let fixture = try Fixture(legacyRunning: false)
        defer { fixture.cleanup() }

        do {
            _ = try await fixture.coordinator.migrate(
                manifest: fixture.manifest,
                sources: fixture.sources,
                hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
                launchAgentConfiguration: fixture.launchAgentConfiguration,
                legacy: fixture.legacy,
                runID: fixture.runID,
                confirmation: "确认"
            )
            XCTFail("Expected confirmation failure")
        } catch {
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .confirmationRequired)
        }

        let beginCount = await fixture.account.beginCount()
        XCTAssertEqual(beginCount, 0)
        XCTAssertNil(try fixture.journal.load())
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.root.path))
    }

    func testAmbiguousRemoteCommitStopsWithoutRevivingLegacy() async throws {
        let fixture = try Fixture(legacyRunning: true, ambiguousCommit: true)
        defer { fixture.cleanup() }

        do {
            _ = try await fixture.coordinator.migrate(
                manifest: fixture.manifest,
                sources: fixture.sources,
                hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
                launchAgentConfiguration: fixture.launchAgentConfiguration,
                legacy: fixture.legacy,
                runID: fixture.runID,
                confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                    releaseVersion: fixture.manifest.releaseVersion
                )
            )
            XCTFail("Expected ambiguous commit")
        } catch {
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .commitAmbiguous)
        }

        XCTAssertEqual(try fixture.journal.load()?.state, .rollbackAttentionRequired)
        XCTAssertTrue(fixture.runner.loadedLabels().isEmpty)
    }

    func testRestartRecoveryStopsCandidateAndRestoresRecordedLegacyMode() async throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        _ = try fixture.journal.begin(
            runID: fixture.runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: fixture.manifest.releaseVersion,
            bindingID: fixture.bindingID,
            bindingGeneration: 1
        )
        _ = try fixture.journal.transition(runID: fixture.runID, to: .accountStaged)
        _ = try fixture.journal.transition(runID: fixture.runID, to: .candidateStarting)
        let release = try fixture.layout.release(fixture.manifest.releaseVersion)
        try FileManager.default.createDirectory(at: release, withIntermediateDirectories: true)
        _ = try fixture.installer.activate(
            releaseVersion: fixture.manifest.releaseVersion,
            runID: fixture.runID
        )
        fixture.runner.replaceLoaded(with: [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])

        let recovered = try await fixture.coordinator.recoverInterrupted(
            legacy: fixture.legacy,
            runID: fixture.runID
        )

        XCTAssertEqual(recovered, .legacyActive)
        XCTAssertEqual(fixture.runner.loadedLabels(), ["com.hermesremote.connector"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.currentRelease.path))
    }
}

private final class Fixture {
    let root: URL
    let layout: DesktopManagedInstallLayout
    let installer: DesktopManagedInstaller
    let journal: DesktopMigrationJournalStore
    let runner: InMemoryLaunchctlRunner
    let account: MigrationAccountFake
    let coordinator: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>
    let manifest: DesktopReleaseManifest
    let sources: [DesktopManagedReleaseSource]
    let hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent
    let launchAgentConfiguration: DesktopAccountConnectorLaunchAgent
    let legacy: LegacyConnectorSnapshot
    let runID = "10000000-0000-4000-8000-000000000001"
    let bindingID = "50000000-0000-4000-8000-000000000001"

    init(
        legacyRunning: Bool,
        failAccountStart: Bool = false,
        ambiguousCommit: Bool = false,
        hermesHealthy: Bool = true
    ) throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-migration-coordinator-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
        layout = try DesktopManagedInstallLayout(
            root: root.appendingPathComponent("managed"),
            launchAgentsRoot: root.appendingPathComponent("agents")
        )
        installer = DesktopManagedInstaller(layout: layout)
        journal = try DesktopMigrationJournalStore(root: root.appendingPathComponent("journal"))
        runner = InMemoryLaunchctlRunner(legacyLoaded: legacyRunning, failAccountStart: failAccountStart)
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: layout.launchAgentsRoot,
            runner: runner
        )
        account = MigrationAccountFake(bindingID: bindingID, ambiguousCommit: ambiguousCommit)
        coordinator = try DesktopMigrationCoordinator(
            account: account,
            journal: journal,
            installer: installer,
            launchAgent: controller,
            hermesReadiness: MigrationHermesReadiness(healthy: hermesHealthy),
            maximumHealthPolls: 2,
            healthPollDelayNanoseconds: 0
        )
        manifest = Self.manifest()
        sources = try Self.sources(root: root)
        try FileManager.default.createDirectory(at: layout.launchAgentsRoot, withIntermediateDirectories: true)
        let connectorExecutable = root.appendingPathComponent(
            "managed/current/connector/bin/hermes-connector"
        )
        hermesLaunchAgentConfiguration = DesktopHermesServerLaunchAgent(
            hermesExecutable: root.appendingPathComponent(
                "managed/current/hermes_server/bin/hermes-server"
            ),
            hermesHome: root.appendingPathComponent("hermes-home"),
            runtimeContract: .serveV1,
            standardOutput: root.appendingPathComponent("managed/logs/hermes-server.log"),
            standardError: root.appendingPathComponent("managed/logs/hermes-server.error.log")
        )
        launchAgentConfiguration = DesktopAccountConnectorLaunchAgent(
            connectorExecutable: connectorExecutable,
            credentialFile: layout.connectorCredential,
            gatewayURL: URL(string: "wss://gateway.example/v2/connect")!,
            hermesBaseURL: URL(string: "http://127.0.0.1:9119")!,
            standardOutput: root.appendingPathComponent("managed/logs/connector.log"),
            standardError: root.appendingPathComponent("managed/logs/connector.error.log")
        )
        let legacyPlist = layout.launchAgentsRoot.appendingPathComponent("com.hermesremote.connector.plist")
        try Data("legacy".utf8).write(to: legacyPlist)
        legacy = LegacyConnectorSnapshot(
            isInstalled: legacyRunning,
            isRunning: legacyRunning,
            config: LegacyConnectorConfig(gatewayURL: nil),
            recentLogs: [],
            installDirectory: root.appendingPathComponent("legacy"),
            launchAgentURL: legacyPlist
        )
    }

    func cleanup() { try? FileManager.default.removeItem(at: root) }

    private static func sources(root: URL) throws -> [DesktopManagedReleaseSource] {
        var result: [DesktopManagedReleaseSource] = []
        for (component, executable) in [
            (DesktopReleaseComponentKind.hermesServer, "hermes-server"),
            (.connector, "hermes-connector"),
        ] {
            let directory = root.appendingPathComponent("source-\(component.rawValue)")
            let bin = directory.appendingPathComponent("bin")
            try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
            let entrypoint = bin.appendingPathComponent(executable)
            try Data("test executable".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: entrypoint.path)
            result.append(.init(component: component, directory: directory))
        }
        return result
    }

    private static func manifest() -> DesktopReleaseManifest {
        DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            artifacts: [
                .init(component: .hermesServer, version: "0.20.6",
                      fileName: "Hermes-Server-0.20.6-arm64.tar.gz", entrypoint: "bin/hermes-server",
                      downloadURL: "https://downloads.example/Hermes-Server-0.20.6-arm64.tar.gz",
                      sizeBytes: 1, sha256: String(repeating: "a", count: 64)),
                .init(component: .connector, version: "0.2.0",
                      fileName: "Hermes-Connector-0.2.0-arm64.tar.gz", entrypoint: "bin/hermes-connector",
                      downloadURL: "https://downloads.example/Hermes-Connector-0.2.0-arm64.tar.gz",
                      sizeBytes: 1, sha256: String(repeating: "b", count: 64)),
            ]
        )
    }
}

private actor MigrationAccountFake: DesktopBindingCoordinating {
    private let bindingID: String
    private var began = 0
    private var confirmations = 0
    private var committed = false
    private let ambiguousCommit: Bool
    private var confirmationAttempted = false

    init(bindingID: String, ambiguousCommit: Bool) {
        self.bindingID = bindingID
        self.ambiguousCommit = ambiguousCommit
    }

    func beginBinding() async throws -> DesktopBindingPreparation {
        began += 1
        return DesktopBindingPreparation(
            state: pendingState(keyProved: false, healthy: false),
            credential: AccountConnectorCredentialPayload(data: Data("{\"test\":true}".utf8))
        )
    }

    func refresh() async throws -> DesktopAccountState {
        if ambiguousCommit, confirmationAttempted { return .signedOut }
        return committed ? committedState() : pendingState(keyProved: true, healthy: true)
    }

    func confirmBinding() async throws -> DesktopAccountState {
        confirmations += 1
        confirmationAttempted = true
        if ambiguousCommit { throw AccountClientError.transport }
        committed = true
        return committedState()
    }

    func beginCount() -> Int { began }
    func confirmCount() -> Int { confirmations }

    private func pendingState(keyProved: Bool, healthy: Bool) -> DesktopAccountState {
        .signedIn(dashboard(binding: AccountBindingSnapshot(
            state: "binding_pending", id: bindingID, generation: 1,
            deviceId: "hermes-pending", displayName: "Mac mini",
            publicKeyFingerprint: String(repeating: "a", count: 64),
            expiresAt: "2099-09-07T00:10:00Z", keyProved: keyProved,
            healthVerified: healthy, binding: nil, previousBinding: nil
        )))
    }

    private func committedState() -> DesktopAccountState {
        .signedIn(dashboard(binding: AccountBindingSnapshot(
            state: "bound", id: nil, generation: nil, deviceId: nil, displayName: nil,
            publicKeyFingerprint: nil, expiresAt: nil, keyProved: nil, healthVerified: nil,
            binding: ActiveAccountBinding(
                id: bindingID, generation: 1, deviceId: "hermes-pending",
                desktopDisplayName: "Mac mini", publicKeyFingerprint: String(repeating: "a", count: 64),
                connector: .init(online: true, lastSeenAt: nil),
                hermes: .init(reachable: true, version: "1.0.0"),
                gateway: .init(latencyMs: 10),
                endToEnd: .init(healthy: true, checkedAt: "2026-09-07T00:00:00Z")
            ), previousBinding: nil
        )))
    }

    private func dashboard(binding: AccountBindingSnapshot) -> AccountDashboard {
        AccountDashboard(
            session: AccountSessionRecord(
                account: HermesAccount(id: "10000000-0000-4000-8000-000000000001", displayName: "A", email: nil, avatarUrl: nil),
                installation: AccountInstallation(id: "20000000-0000-4000-8000-000000000001", kind: "desktop", platform: "macos", displayName: "Mac mini"),
                session: AccountSessionTokens(accessToken: "redacted", accessExpiresAt: "2099-01-01T00:00:00Z", refreshToken: "redacted", refreshExpiresAt: "2099-02-01T00:00:00Z")
            ),
            binding: binding,
            installations: [], devices: [], maxOwnedDevices: 3, selectedDeviceID: nil
        )
    }
}

private final class InMemoryLaunchctlRunner: CommandRunning, @unchecked Sendable {
    private let lock = NSLock()
    private var loaded: Set<String>
    private let failAccountStart: Bool
    private var commands: [[String]] = []

    init(legacyLoaded: Bool, failAccountStart: Bool) {
        loaded = legacyLoaded ? ["com.hermesremote.connector"] : []
        self.failAccountStart = failAccountStart
    }

    func run(executable: URL, arguments: [String]) -> CommandResult {
        lock.withLock {
            commands.append(arguments)
            switch arguments.first {
            case "print":
                let label = arguments.last!.split(separator: "/").last.map(String.init)!
                return CommandResult(status: loaded.contains(label) ? 0 : 1)
            case "bootout":
                let label = arguments.last!.split(separator: "/").last.map(String.init)!
                loaded.remove(label)
                return CommandResult(status: 0)
            case "bootstrap":
                let label = URL(fileURLWithPath: arguments.last!).deletingPathExtension().lastPathComponent
                if label == DesktopManagedInstallLayout.connectorLabel, failAccountStart {
                    return CommandResult(status: 1)
                }
                loaded.insert(label)
                return CommandResult(status: 0)
            default:
                return CommandResult(status: 1)
            }
        }
    }

    func loadedLabels() -> Set<String> { lock.withLock { loaded } }
    func events() -> [[String]] { lock.withLock { commands } }
    func replaceLoaded(with labels: Set<String>) { lock.withLock { loaded = labels } }
}

private struct MigrationHermesReadiness: DesktopHermesCandidateReadinessChecking {
    let healthy: Bool

    func checkpoint(logURL: URL) throws -> DesktopHermesReadinessCheckpoint {
        DesktopHermesReadinessCheckpoint(logURL: logURL)
    }

    func waitUntilReady(
        checkpoint: DesktopHermesReadinessCheckpoint,
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool {
        healthy
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ errorHandler: (Error) -> Void = { _ in },
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected error", file: file, line: line)
    } catch {
        errorHandler(error)
    }
}
