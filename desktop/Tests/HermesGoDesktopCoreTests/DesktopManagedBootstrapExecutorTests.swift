import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedBootstrapExecutorTests: XCTestCase {
    func testCommitConfigurationDerivesExactAccountWebSocketWithoutCredentials() throws {
        let layout = try DesktopManagedInstallLayout(
            root: URL(fileURLWithPath: "/tmp/managed"),
            launchAgentsRoot: URL(fileURLWithPath: "/tmp/agents")
        )

        let production = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: URL(fileURLWithPath: "/tmp/hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        let local = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: URL(fileURLWithPath: "/tmp/hermes-home"),
            accountGatewayURL: URL(string: "http://127.0.0.1:8787")!,
            runtimeContract: .serveV1
        )

        XCTAssertEqual(production.gatewayWebSocketURL.absoluteString, "wss://gateway.example/v2/connect")
        XCTAssertEqual(local.gatewayWebSocketURL.absoluteString, "ws://127.0.0.1:8787/v2/connect")
    }

    func testCommitConfigurationRejectsRemotePlaintextAndGatewaySubpaths() throws {
        let layout = try DesktopManagedInstallLayout(
            root: URL(fileURLWithPath: "/tmp/managed"),
            launchAgentsRoot: URL(fileURLWithPath: "/tmp/agents")
        )
        for value in ["http://gateway.example", "https://gateway.example/account"] {
            XCTAssertThrowsError(try DesktopManagedBootstrapCommitConfiguration(
                layout: layout,
                hermesHome: URL(fileURLWithPath: "/tmp/hermes-home"),
                accountGatewayURL: URL(string: value)!,
                runtimeContract: .serveV1
            )) { error in
                XCTAssertEqual(
                    error as? DesktopManagedBootstrapCommitConfigurationError,
                    .invalidGatewayURL
                )
            }
        }
    }

    func testPrepareReturnsSignedReleaseConfirmationWithoutStartingMigration() async throws {
        let fixture = BootstrapExecutorFixture()

        let preparation = try await fixture.prepare()

        XCTAssertEqual(preparation.runID, fixture.runID)
        XCTAssertEqual(preparation.releaseVersion, "1.2.3")
        XCTAssertEqual(preparation.confirmationText, fixture.confirmation)
        XCTAssertFalse(preparation.id.isEmpty)
        XCTAssertEqual(fixture.acquisition.events, ["acquire"])
        XCTAssertEqual(fixture.migration.events, [])

        try await fixture.executor.cancel(preparation)
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])
    }

    func testIncorrectConfirmationCannotStartMigrationAndPreparationCanBeRetried() async throws {
        let fixture = BootstrapExecutorFixture()
        let preparation = try await fixture.prepare()

        await XCTAssertThrowsErrorAsync(try await fixture.executor.commit(
            preparation,
            configuration: fixture.commitConfiguration,
            legacy: fixture.legacy,
            confirmation: "确认升级"
        )) { error in
            XCTAssertEqual(
                error as? DesktopManagedBootstrapExecutorError,
                .confirmationRequired
            )
        }
        XCTAssertEqual(fixture.migration.events, [])
        XCTAssertEqual(fixture.acquisition.events, ["acquire"])

        let outcome = try await fixture.executor.commit(
            preparation,
            configuration: fixture.commitConfiguration,
            legacy: fixture.legacy,
            confirmation: preparation.confirmationText
        )
        XCTAssertEqual(outcome.migration, fixture.outcome)
    }

    func testPreparationFromAnotherExecutorCannotBeCommitted() async throws {
        let fixture = BootstrapExecutorFixture()
        let other = BootstrapExecutorFixture()
        let preparation = try await fixture.prepare()
        let unrelated = try await other.prepare()

        await XCTAssertThrowsErrorAsync(try await fixture.executor.commit(
            unrelated,
            configuration: fixture.commitConfiguration,
            legacy: fixture.legacy,
            confirmation: unrelated.confirmationText
        )) { error in
            XCTAssertEqual(
                error as? DesktopManagedBootstrapExecutorError,
                .preparationMismatch
            )
        }
        XCTAssertEqual(fixture.migration.events, [])

        try await fixture.executor.cancel(preparation)
        try await other.executor.cancel(unrelated)
    }

    func testCancelDiscardsPreparedWorkspaceWithoutMigration() async throws {
        let fixture = BootstrapExecutorFixture()
        let preparation = try await fixture.prepare()

        try await fixture.executor.cancel(preparation)

        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])
        XCTAssertEqual(fixture.migration.events, [])
        await XCTAssertThrowsErrorAsync(try await fixture.executor.cancel(preparation)) { error in
            XCTAssertEqual(error as? DesktopManagedBootstrapExecutorError, .notPrepared)
        }
    }

    func testSuccessRunsExactAcquisitionMigrationAndCleanupSequence() async throws {
        let fixture = BootstrapExecutorFixture()
        let result = try await fixture.executor.execute(
            manifestURL: fixture.manifestURL,
            workspaceRoot: fixture.workspaceRoot,
            configuration: fixture.commitConfiguration,
            legacy: fixture.legacy,
            runID: fixture.runID,
            confirmation: fixture.confirmation
        )

        XCTAssertEqual(result.migration, fixture.outcome)
        XCTAssertTrue(result.temporaryWorkspaceRemoved)
        XCTAssertNil(result.cleanupRetry)
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])
        XCTAssertEqual(fixture.migration.events, ["migrate"])
        XCTAssertEqual(fixture.migration.receivedRunID, fixture.runID)
        XCTAssertEqual(fixture.migration.receivedConfirmation, fixture.confirmation)
        XCTAssertEqual(fixture.migration.receivedManifest, fixture.acquired.manifest)
        XCTAssertEqual(fixture.migration.receivedSources, fixture.acquired.sources)
        XCTAssertEqual(
            fixture.migration.receivedHermesExecutable,
            fixture.hermesLaunchAgentConfiguration.hermesExecutable
        )
    }

    func testCommittedMigrationReportsCleanupRetryWithoutClaimingRollback() async throws {
        let fixture = BootstrapExecutorFixture(cleanupFails: true)

        let result = try await fixture.executor.execute(
            manifestURL: fixture.manifestURL,
            workspaceRoot: fixture.workspaceRoot,
            configuration: fixture.commitConfiguration,
            legacy: fixture.legacy,
            runID: fixture.runID,
            confirmation: fixture.confirmation
        )

        XCTAssertEqual(result.migration, fixture.outcome)
        XCTAssertFalse(result.temporaryWorkspaceRemoved)
        let cleanupRetry = try XCTUnwrap(result.cleanupRetry)
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])

        try await fixture.executor.retryCleanup(cleanupRetry)
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard", "discard"])
    }

    func testMigrationFailureDiscardsWorkspaceAndPreservesTheOriginalFailure() async throws {
        let fixture = BootstrapExecutorFixture(migrationFails: true)

        await XCTAssertThrowsErrorAsync(try await fixture.execute()) { error in
            XCTAssertEqual(error as? BootstrapExecutorFixture.ExpectedError, .migrationFailed)
        }
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])
        XCTAssertEqual(fixture.migration.events, ["migrate"])
    }

    func testMigrationAndCleanupFailureEscalatesTheResidualWorkspace() async throws {
        let fixture = BootstrapExecutorFixture(migrationFails: true, cleanupFails: true)

        await XCTAssertThrowsErrorAsync(try await fixture.execute()) { error in
            XCTAssertEqual(error as? DesktopManagedBootstrapExecutorError, .cleanupFailed)
        }
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])
    }

    func testCancellationAfterAcquisitionDiscardsBeforeMigrationStarts() async throws {
        let fixture = BootstrapExecutorFixture(cancelAfterAcquire: true)
        let operation = Task { try await fixture.execute() }

        await XCTAssertThrowsErrorAsync(try await operation.value) { error in
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(fixture.acquisition.events, ["acquire", "discard"])
        XCTAssertEqual(fixture.migration.events, [])
    }
}

private final class BootstrapExecutorFixture {
    enum ExpectedError: Error, Equatable { case migrationFailed }

    let runID = "60000000-0000-4000-8000-000000000006"
    let confirmation = "升级到 1.2.3 并短暂重启 Hermes Server 与 Connector"
    let manifestURL = URL(string: "https://downloads.example/desktop/manifest.json")!
    let workspaceRoot = URL(fileURLWithPath: "/tmp/hermes-bootstrap-tests")
    let hermesLaunchAgentConfiguration = DesktopHermesServerLaunchAgent(
        hermesExecutable: URL(fileURLWithPath: "/tmp/managed/current/hermes_server/bin/hermes-server"),
        hermesHome: URL(fileURLWithPath: "/tmp/hermes-home"),
        runtimeContract: .serveV1,
        standardOutput: URL(fileURLWithPath: "/tmp/managed/logs/hermes-server.log"),
        standardError: URL(fileURLWithPath: "/tmp/managed/logs/hermes-server.error.log")
    )
    let launchAgentConfiguration = DesktopAccountConnectorLaunchAgent(
        connectorExecutable: URL(fileURLWithPath: "/tmp/managed/current/connector/bin/hermes-connector"),
        credentialFile: URL(fileURLWithPath: "/tmp/managed/secrets/connector-account.json"),
        gatewayURL: URL(string: "wss://gateway.example/v2/connect")!,
        hermesBaseURL: URL(string: "http://127.0.0.1:9119")!,
        standardOutput: URL(fileURLWithPath: "/tmp/managed/logs/connector.log"),
        standardError: URL(fileURLWithPath: "/tmp/managed/logs/connector.error.log")
    )
    var commitConfiguration: DesktopManagedBootstrapCommitConfiguration {
        try! DesktopManagedBootstrapCommitConfiguration(
            layout: DesktopManagedInstallLayout(
                root: URL(fileURLWithPath: "/tmp/managed"),
                launchAgentsRoot: URL(fileURLWithPath: "/tmp/agents")
            ),
            hermesHome: URL(fileURLWithPath: "/tmp/hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
    }
    let legacy = LegacyConnectorSnapshot(
        isInstalled: false,
        isRunning: false,
        config: LegacyConnectorConfig(gatewayURL: nil),
        recentLogs: [],
        installDirectory: URL(fileURLWithPath: "/tmp/legacy"),
        launchAgentURL: URL(fileURLWithPath: "/tmp/agents/com.hermesremote.connector.plist")
    )
    let outcome = DesktopMigrationOutcome(
        runID: "60000000-0000-4000-8000-000000000006",
        releaseVersion: "1.2.3",
        bindingID: "70000000-0000-4000-8000-000000000007",
        bindingGeneration: 1
    )
    let acquired: DesktopAcquiredRelease
    let acquisition: BootstrapAcquisitionFake
    let migration: BootstrapMigrationFake
    let executor: DesktopManagedBootstrapExecutor

    init(
        migrationFails: Bool = false,
        cleanupFails: Bool = false,
        cancelAfterAcquire: Bool = false
    ) {
        let manifest = DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-08T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            artifacts: [
                DesktopReleaseArtifact(
                    component: .hermesServer,
                    version: "1.2.3",
                    fileName: "hermes-server-1.2.3-arm64.tar.gz",
                    entrypoint: "bin/hermes-server",
                    downloadURL: "https://downloads.example/hermes-server-1.2.3-arm64.tar.gz",
                    sizeBytes: 1,
                    sha256: String(repeating: "a", count: 64)
                ),
                DesktopReleaseArtifact(
                    component: .connector,
                    version: "1.2.3",
                    fileName: "connector-1.2.3-arm64.tar.gz",
                    entrypoint: "bin/hermes-connector",
                    downloadURL: "https://downloads.example/connector-1.2.3-arm64.tar.gz",
                    sizeBytes: 1,
                    sha256: String(repeating: "b", count: 64)
                ),
            ]
        )
        acquired = DesktopAcquiredRelease(
            manifest: manifest,
            sources: [
                DesktopManagedReleaseSource(
                    component: .hermesServer,
                    directory: URL(fileURLWithPath: "/tmp/extracted/hermes")
                ),
                DesktopManagedReleaseSource(
                    component: .connector,
                    directory: URL(fileURLWithPath: "/tmp/extracted/connector")
                ),
            ],
            workspaceDirectory: workspaceRoot.appendingPathComponent(runID),
            workspaceBaseDirectory: workspaceRoot
        )
        acquisition = BootstrapAcquisitionFake(
            release: acquired,
            cleanupFails: cleanupFails,
            cancelAfterAcquire: cancelAfterAcquire
        )
        migration = BootstrapMigrationFake(
            outcome: outcome,
            failure: migrationFails ? ExpectedError.migrationFailed : nil
        )
        executor = DesktopManagedBootstrapExecutor(acquisition: acquisition, migration: migration)
    }

    func execute() async throws -> DesktopManagedBootstrapOutcome {
        try await executor.execute(
            manifestURL: manifestURL,
            workspaceRoot: workspaceRoot,
            configuration: commitConfiguration,
            legacy: legacy,
            runID: runID,
            confirmation: confirmation
        )
    }

    func prepare() async throws -> DesktopManagedBootstrapPreparation {
        try await executor.prepare(
            manifestURL: manifestURL,
            workspaceRoot: workspaceRoot,
            runID: runID
        )
    }
}

private final class BootstrapAcquisitionFake: DesktopReleaseAcquiring, @unchecked Sendable {
    let release: DesktopAcquiredRelease
    let cancelAfterAcquire: Bool
    private var cleanupFailuresRemaining: Int
    private(set) var events: [String] = []

    init(
        release: DesktopAcquiredRelease,
        cleanupFails: Bool,
        cancelAfterAcquire: Bool
    ) {
        self.release = release
        cleanupFailuresRemaining = cleanupFails ? 1 : 0
        self.cancelAfterAcquire = cancelAfterAcquire
    }

    func acquire(
        manifestURL: URL,
        workspaceRoot: URL,
        runID: String
    ) async throws -> DesktopAcquiredRelease {
        events.append("acquire")
        if cancelAfterAcquire {
            withUnsafeCurrentTask { $0?.cancel() }
        }
        return release
    }

    func discard(_ release: DesktopAcquiredRelease) throws {
        events.append("discard")
        if cleanupFailuresRemaining > 0 {
            cleanupFailuresRemaining -= 1
            throw DesktopReleaseAcquisitionError.cleanupFailed
        }
    }
}

private final class BootstrapMigrationFake: DesktopReleaseMigrating, @unchecked Sendable {
    let outcome: DesktopMigrationOutcome
    let failure: Error?
    private(set) var events: [String] = []
    private(set) var receivedRunID: String?
    private(set) var receivedConfirmation: String?
    private(set) var receivedManifest: DesktopReleaseManifest?
    private(set) var receivedSources: [DesktopManagedReleaseSource]?
    private(set) var receivedHermesExecutable: URL?

    init(outcome: DesktopMigrationOutcome, failure: Error?) {
        self.outcome = outcome
        self.failure = failure
    }

    func migrate(
        manifest: DesktopReleaseManifest,
        sources: [DesktopManagedReleaseSource],
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        events.append("migrate")
        receivedRunID = runID
        receivedConfirmation = confirmation
        receivedManifest = manifest
        receivedSources = sources
        receivedHermesExecutable = hermesLaunchAgentConfiguration.hermesExecutable
        if let failure { throw failure }
        return outcome
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
