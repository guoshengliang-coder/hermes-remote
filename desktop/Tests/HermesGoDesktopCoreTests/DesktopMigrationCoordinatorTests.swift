import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopMigrationCoordinatorTests: XCTestCase {
    func testDefaultHealthWindowCoversASeventyFivePollColdStart() async throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        let readiness = MigrationHermesReadiness(healthy: true)
        let controller = try DesktopLaunchAgentController(
            userID: 501,
            launchAgentsRoot: fixture.layout.launchAgentsRoot,
            runner: fixture.runner
        )
        let coordinator = try DesktopMigrationCoordinator(
            account: fixture.account,
            journal: fixture.journal,
            installer: fixture.installer,
            launchAgent: controller,
            hermesReadiness: readiness,
            healthPollDelayNanoseconds: 0
        )

        _ = try await coordinator.migrate(
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

        XCTAssertEqual(readiness.maximumAttempts(), 75)
    }

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
        XCTAssertEqual(fixture.runner.disabledLabels(), ["com.hermesremote.connector"])
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
        XCTAssertEqual(
            try Data(contentsOf: fixture.layout.hermesSessionTokenContractMarker),
            Data("1\n".utf8)
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
        XCTAssertTrue(fixture.runner.disabledLabels().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.currentRelease.path))
    }

    func testRetryAfterLegacyRollbackCarriesTheRevokedBindingGeneration() async throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        let previousRunID = "10000000-0000-4000-8000-000000000009"
        _ = try fixture.journal.begin(
            runID: previousRunID,
            lastKnownGoodMode: .legacy,
            releaseVersion: fixture.manifest.releaseVersion,
            bindingID: fixture.bindingID,
            bindingGeneration: 1
        )
        _ = try fixture.journal.transition(runID: previousRunID, to: .accountStaged)
        _ = try fixture.journal.transition(runID: previousRunID, to: .rollingBack)
        _ = try fixture.journal.transition(runID: previousRunID, to: .legacyActive)

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

        let retryReferences = await fixture.account.retryReferences()
        XCTAssertEqual(retryReferences.map(\.id), [fixture.bindingID])
        XCTAssertEqual(retryReferences.map(\.generation), [1])
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
    }

    func testMatchingBoundBindingResumesAfterLocalRollbackWithoutRemoteConfirmation() async throws {
        let fixture = try Fixture(legacyRunning: true, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        let previousRunID = "10000000-0000-4000-8000-000000000009"
        _ = try fixture.journal.begin(
            runID: previousRunID,
            lastKnownGoodMode: .legacy,
            releaseVersion: fixture.manifest.releaseVersion,
            bindingID: fixture.bindingID,
            bindingGeneration: 1
        )
        _ = try fixture.journal.transition(runID: previousRunID, to: .accountStaged)
        _ = try fixture.journal.transition(runID: previousRunID, to: .rollingBack)
        _ = try fixture.journal.transition(runID: previousRunID, to: .legacyActive)

        let outcome = try await fixture.coordinator.migrate(
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

        XCTAssertEqual(outcome.bindingID, fixture.bindingID)
        let confirmCount = await fixture.account.confirmCount()
        XCTAssertEqual(confirmCount, 0)
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
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
        XCTAssertTrue(fixture.runner.disabledLabels().isEmpty)
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

    func testMigrationAssistantDuplicateIsSuppressedOnlyForCommittedAccountActiveJournal() throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        _ = try fixture.journal.begin(
            runID: fixture.runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: fixture.manifest.releaseVersion,
            bindingID: fixture.bindingID,
            bindingGeneration: 1
        )
        for state in [
            DesktopMigrationState.accountStaged,
            .candidateStarting,
            .candidateAuthenticated,
            .candidateHealthy,
            .commitPending,
            .accountActive,
        ] {
            _ = try fixture.journal.transition(runID: fixture.runID, to: state)
        }
        fixture.runner.replaceLoaded(with: [
            "com.hermesremote.connector",
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])

        XCTAssertTrue(try fixture.coordinator.reconcileTransferredAccountActive())

        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(fixture.runner.disabledLabels(), ["com.hermesremote.connector"])
    }

    func testTransferReconciliationIsInertBeforeAccountCommit() throws {
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
        fixture.runner.replaceLoaded(with: [
            "com.hermesremote.connector",
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])

        XCTAssertFalse(try fixture.coordinator.reconcileTransferredAccountActive())
        XCTAssertEqual(fixture.runner.loadedLabels().count, 3)
        XCTAssertTrue(fixture.runner.disabledLabels().isEmpty)
    }

    func testTransferReconciliationDoesNotCreateStateOnCleanMac() throws {
        let fixture = try Fixture(legacyRunning: false)
        defer { fixture.cleanup() }
        let journalRoot = fixture.root.appendingPathComponent("journal")

        XCTAssertFalse(try fixture.coordinator.reconcileTransferredAccountActive())

        XCTAssertFalse(FileManager.default.fileExists(atPath: journalRoot.path))
        XCTAssertTrue(fixture.runner.events().isEmpty)
    }

    func testCommittedInlineTokenMigrationRestartsInOrderAndCommitsMarker() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        let token = String(repeating: "a", count: 64)
        try fixture.installCommittedManagedServices(inlineToken: token)

        let migrated = try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()
        XCTAssertTrue(migrated)

        XCTAssertEqual(try String(contentsOf: fixture.layout.hermesSessionToken, encoding: .utf8), token)
        XCTAssertEqual(try fixture.tokenEnvironment(at: fixture.layout.hermesLaunchAgent), [
            "HERMES_SESSION_TOKEN_FILE": fixture.layout.hermesSessionToken.path,
        ])
        XCTAssertEqual(try fixture.tokenEnvironment(at: fixture.layout.connectorLaunchAgent), [
            "HERMES_SESSION_TOKEN_FILE": fixture.layout.hermesSessionToken.path,
        ])
        XCTAssertEqual(
            try Data(contentsOf: fixture.layout.hermesSessionTokenContractMarker),
            Data("1\n".utf8)
        )
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(fixture.serviceMutations(), [
            "bootout:\(DesktopManagedInstallLayout.connectorLabel)",
            "bootout:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.connectorLabel)",
        ])
    }

    func testCommittedInlineTokenMigrationSkipsReleaseBeforeTokenFileContract() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            manifestVersion: "0.3.0"
        )
        defer { fixture.cleanup() }
        let token = String(repeating: "d", count: 64)
        try fixture.installCommittedManagedServices(inlineToken: token)
        let originalHermes = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let originalConnector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)

        let migrated = try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()

        XCTAssertFalse(migrated)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), originalHermes)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), originalConnector)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.hermesSessionToken.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.layout.hermesSessionTokenContractMarker.path
        ))
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
        XCTAssertEqual(fixture.readiness.waitCount(), 0)
        let accountRefreshes = await fixture.account.refreshCount()
        XCTAssertEqual(accountRefreshes, 0)
    }

    func testCommittedTokenMigrationHealthFailureRestoresInlineFilesAndRunningServices() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            hermesReadinessResponses: [false, true]
        )
        defer { fixture.cleanup() }
        let token = String(repeating: "b", count: 64)
        try fixture.installCommittedManagedServices(inlineToken: token)
        let originalHermes = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let originalConnector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)

        await XCTAssertThrowsErrorAsync(
            try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()
        ) { error in
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .hermesHealthTimedOut)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), originalHermes)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), originalConnector)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.hermesSessionToken.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.layout.hermesSessionTokenContractMarker.path
        ))
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(fixture.readiness.waitCount(), 2)
    }

    func testCommittedTokenMigrationRejectsStaleCloudHealthAndRestoresInlineFiles() async throws {
        let stale = "2026-09-07T00:00:00.000Z"
        let fresh = "2026-09-07T00:00:01.000Z"
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            healthCheckedAtSequence: [stale, stale, stale, stale, stale, fresh]
        )
        defer { fixture.cleanup() }
        let token = String(repeating: "e", count: 64)
        try fixture.installCommittedManagedServices(inlineToken: token)
        let originalHermes = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let originalConnector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)

        await XCTAssertThrowsErrorAsync(
            try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()
        ) { error in
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .healthTimedOut)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), originalHermes)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), originalConnector)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.hermesSessionToken.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.layout.hermesSessionTokenContractMarker.path
        ))
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(fixture.serviceMutations(), [
            "bootout:\(DesktopManagedInstallLayout.connectorLabel)",
            "bootout:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.connectorLabel)",
            "bootout:\(DesktopManagedInstallLayout.connectorLabel)",
            "bootout:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.connectorLabel)",
        ])
    }

    func testCommittedCurrentTokenContractIsIdempotentWithoutServiceMutation() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.installer.commitHermesSessionTokenFileMigration()

        let migrated = try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()
        XCTAssertFalse(migrated)

        XCTAssertTrue(fixture.serviceMutations().isEmpty)
        XCTAssertEqual(fixture.readiness.waitCount(), 0)
    }

    func testCommittedTokenMigrationRejectsMismatchedAccountBeforeLocalMutation() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(
            inlineToken: String(repeating: "c", count: 64),
            bindingGeneration: 2
        )
        let originalHermes = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let originalConnector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)

        await XCTAssertThrowsErrorAsync(
            try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()
        ) { error in
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .invalidBindingState)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), originalHermes)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), originalConnector)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.hermesSessionToken.path))
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }
}

private final class Fixture {
    let root: URL
    let layout: DesktopManagedInstallLayout
    let installer: DesktopManagedInstaller
    let journal: DesktopMigrationJournalStore
    let runner: InMemoryLaunchctlRunner
    let account: MigrationAccountFake
    let readiness: MigrationHermesReadiness
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
        hermesHealthy: Bool = true,
        resumeBoundBinding: Bool = false,
        hermesReadinessResponses: [Bool]? = nil,
        healthCheckedAtSequence: [String?]? = nil,
        manifestVersion: String = "1.2.3"
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
        account = MigrationAccountFake(
            bindingID: bindingID,
            ambiguousCommit: ambiguousCommit,
            resumeBoundBinding: resumeBoundBinding,
            healthCheckedAtSequence: healthCheckedAtSequence
        )
        readiness = MigrationHermesReadiness(
            responses: hermesReadinessResponses ?? [hermesHealthy]
        )
        coordinator = try DesktopMigrationCoordinator(
            account: account,
            journal: journal,
            installer: installer,
            launchAgent: controller,
            hermesReadiness: readiness,
            maximumHealthPolls: 2,
            healthPollDelayNanoseconds: 0
        )
        manifest = Self.manifest(releaseVersion: manifestVersion)
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
            sessionTokenFile: layout.hermesSessionToken,
            standardOutput: root.appendingPathComponent("managed/logs/hermes-server.log"),
            standardError: root.appendingPathComponent("managed/logs/hermes-server.error.log")
        )
        launchAgentConfiguration = DesktopAccountConnectorLaunchAgent(
            connectorExecutable: connectorExecutable,
            credentialFile: layout.connectorCredential,
            gatewayURL: URL(string: "wss://gateway.example/v2/connect")!,
            hermesBaseURL: URL(string: "http://127.0.0.1:9119")!,
            sessionTokenFile: layout.hermesSessionToken,
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

    func installCommittedManagedServices(
        inlineToken: String?,
        bindingGeneration: Int = 1
    ) throws {
        if inlineToken == nil { _ = try installer.ensureHermesSessionToken() }
        _ = try installer.writeHermesLaunchAgent(hermesLaunchAgentConfiguration, manifest: manifest)
        _ = try installer.writeLaunchAgent(launchAgentConfiguration, manifest: manifest)
        if let inlineToken {
            try replaceTokenEnvironment(
                at: layout.hermesLaunchAgent,
                inlineKey: "HERMES_DASHBOARD_SESSION_TOKEN",
                token: inlineToken
            )
            try replaceTokenEnvironment(
                at: layout.connectorLaunchAgent,
                inlineKey: "HERMES_SESSION_TOKEN",
                token: inlineToken
            )
            try? FileManager.default.removeItem(at: layout.hermesSessionToken)
        }
        _ = try journal.begin(
            runID: runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: manifest.releaseVersion,
            bindingID: bindingID,
            bindingGeneration: bindingGeneration
        )
        for state in [
            DesktopMigrationState.accountStaged,
            .candidateStarting,
            .candidateAuthenticated,
            .candidateHealthy,
            .commitPending,
            .accountActive,
        ] {
            _ = try journal.transition(runID: runID, to: state)
        }
        runner.replaceLoaded(with: [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
    }

    func tokenEnvironment(at url: URL) throws -> [String: String] {
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(
                from: Data(contentsOf: url),
                options: [],
                format: nil
            ) as? [String: Any]
        )
        let environment = try XCTUnwrap(object["EnvironmentVariables"] as? [String: String])
        return environment.filter { $0.key.contains("SESSION_TOKEN") }
    }

    func serviceMutations() -> [String] {
        runner.events().compactMap { command in
            guard let operation = command.first, ["bootout", "bootstrap"].contains(operation),
                  let target = command.last
            else { return nil }
            let label = operation == "bootstrap"
                ? URL(fileURLWithPath: target).deletingPathExtension().lastPathComponent
                : target.split(separator: "/").last.map(String.init) ?? target
            return "\(operation):\(label)"
        }
    }

    private func replaceTokenEnvironment(at url: URL, inlineKey: String, token: String) throws {
        let data = try Data(contentsOf: url)
        guard var object = try PropertyListSerialization.propertyList(
            from: data,
            options: [],
            format: nil
        ) as? [String: Any],
        var environment = object["EnvironmentVariables"] as? [String: Any]
        else { throw DesktopManagedInstallError.persistenceFailed }
        environment.removeValue(forKey: "HERMES_SESSION_TOKEN_FILE")
        environment[inlineKey] = token
        object["EnvironmentVariables"] = environment
        let migrated = try PropertyListSerialization.data(
            fromPropertyList: object,
            format: .xml,
            options: 0
        )
        try migrated.write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

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

    private static func manifest(releaseVersion: String) -> DesktopReleaseManifest {
        DesktopReleaseManifest(
            releaseVersion: releaseVersion,
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
    private var refreshes = 0
    private var confirmations = 0
    private var committed = false
    private let ambiguousCommit: Bool
    private let resumeBoundBinding: Bool
    private let healthCheckedAtSequence: [String?]?
    private var confirmationAttempted = false
    private var recordedRetryReferences: [(id: String?, generation: Int?)] = []

    init(
        bindingID: String,
        ambiguousCommit: Bool,
        resumeBoundBinding: Bool = false,
        healthCheckedAtSequence: [String?]? = nil
    ) {
        self.bindingID = bindingID
        self.ambiguousCommit = ambiguousCommit
        self.resumeBoundBinding = resumeBoundBinding
        self.healthCheckedAtSequence = healthCheckedAtSequence
    }

    func beginBinding(
        retryingTerminalBindingID: String?,
        retryingTerminalGeneration: Int?
    ) async throws -> DesktopBindingPreparation {
        began += 1
        recordedRetryReferences.append((retryingTerminalBindingID, retryingTerminalGeneration))
        return DesktopBindingPreparation(
            state: resumeBoundBinding
                ? committedState(checkedAt: "2026-09-07T00:00:00.000Z")
                : pendingState(keyProved: false, healthy: false),
            credential: AccountConnectorCredentialPayload(data: Data("{\"test\":true}".utf8))
        )
    }

    func refresh() async throws -> DesktopAccountState {
        refreshes += 1
        if ambiguousCommit, confirmationAttempted { return .signedOut }
        return committed || resumeBoundBinding
            ? committedState(checkedAt: healthCheckedAtForCurrentRefresh())
            : pendingState(keyProved: true, healthy: true)
    }

    func confirmBinding() async throws -> DesktopAccountState {
        confirmations += 1
        confirmationAttempted = true
        if ambiguousCommit { throw AccountClientError.transport }
        committed = true
        return committedState(checkedAt: healthCheckedAtForCurrentRefresh())
    }

    func beginCount() -> Int { began }
    func refreshCount() -> Int { refreshes }
    func confirmCount() -> Int { confirmations }
    func retryReferences() -> [(id: String?, generation: Int?)] { recordedRetryReferences }

    private func pendingState(keyProved: Bool, healthy: Bool) -> DesktopAccountState {
        .signedIn(dashboard(binding: AccountBindingSnapshot(
            state: "binding_pending", id: bindingID, generation: 1,
            deviceId: "hermes-pending", displayName: "Mac mini",
            publicKeyFingerprint: String(repeating: "a", count: 64),
            expiresAt: "2099-09-07T00:10:00Z", keyProved: keyProved,
            healthVerified: healthy, binding: nil, previousBinding: nil
        )))
    }

    private func committedState(checkedAt: String?) -> DesktopAccountState {
        .signedIn(dashboard(binding: AccountBindingSnapshot(
            state: "bound", id: nil, generation: nil, deviceId: nil, displayName: nil,
            publicKeyFingerprint: nil, expiresAt: nil, keyProved: nil, healthVerified: nil,
            binding: ActiveAccountBinding(
                id: bindingID, generation: 1, deviceId: "hermes-pending",
                desktopDisplayName: "Mac mini", publicKeyFingerprint: String(repeating: "a", count: 64),
                connector: .init(online: true, lastSeenAt: checkedAt),
                hermes: .init(reachable: true, version: "1.0.0"),
                gateway: .init(latencyMs: 10),
                endToEnd: .init(healthy: true, checkedAt: checkedAt)
            ), previousBinding: nil
        )))
    }

    private func healthCheckedAtForCurrentRefresh() -> String? {
        if let healthCheckedAtSequence, !healthCheckedAtSequence.isEmpty {
            return healthCheckedAtSequence[min(refreshes - 1, healthCheckedAtSequence.count - 1)]
        }
        return String(format: "2026-09-07T00:00:%02d.000Z", refreshes)
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
    private var disabled: Set<String> = []
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
            case "disable":
                let label = arguments.last!.split(separator: "/").last.map(String.init)!
                disabled.insert(label)
                return CommandResult(status: 0)
            case "enable":
                let label = arguments.last!.split(separator: "/").last.map(String.init)!
                disabled.remove(label)
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
    func disabledLabels() -> Set<String> { lock.withLock { disabled } }
    func events() -> [[String]] { lock.withLock { commands } }
    func replaceLoaded(with labels: Set<String>) { lock.withLock { loaded = labels } }
}

private final class MigrationHermesReadiness: DesktopHermesCandidateReadinessChecking, @unchecked Sendable {
    private let lock = NSLock()
    private var responses: [Bool]
    private var observedMaximumAttempts: Int?
    private var waits = 0

    init(healthy: Bool) {
        responses = [healthy]
    }

    init(responses: [Bool]) {
        self.responses = responses
    }

    func checkpoint(logURL: URL) throws -> DesktopHermesReadinessCheckpoint {
        DesktopHermesReadinessCheckpoint(logURL: logURL)
    }

    func waitUntilReady(
        checkpoint: DesktopHermesReadinessCheckpoint,
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool {
        lock.withLock {
            observedMaximumAttempts = maximumAttempts
            waits += 1
            if responses.count > 1 { return responses.removeFirst() }
            return responses.first ?? false
        }
    }

    func maximumAttempts() -> Int? { lock.withLock { observedMaximumAttempts } }
    func waitCount() -> Int { lock.withLock { waits } }
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
