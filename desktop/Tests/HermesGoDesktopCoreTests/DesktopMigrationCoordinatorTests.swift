import Darwin
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

    func testComponentCandidateCommitsWithoutBundledReleaseActivation() async throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        let component = try fixture.componentRelease()

        let outcome = try await fixture.coordinator.migrateComponentRelease(
            manifest: component.manifest,
            activationPlan: component.plan,
            hermesLaunchAgentConfiguration: component.agents.hermes,
            launchAgentConfiguration: component.agents.connector,
            legacy: fixture.legacy,
            runID: fixture.runID,
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                releaseVersion: component.manifest.releaseVersion
            )
        )

        XCTAssertEqual(outcome.releaseVersion, component.manifest.releaseVersion)
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
        XCTAssertEqual(try fixture.journal.load()?.releaseLayout, .componentStore)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.currentRelease.path))
        let bundledRelease = try fixture.layout.release(component.manifest.releaseVersion)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: bundledRelease.path
        ))
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(
            try fixture.environment(at: fixture.layout.hermesLaunchAgent)[
                "HERMES_PYTHON_RUNTIME_ROOT"
            ],
            component.plan.component(.pythonRuntime)?.root.path
        )
        XCTAssertEqual(
            try fixture.environment(at: fixture.layout.connectorLaunchAgent)[
                "HERMES_NODE_RUNTIME_ROOT"
            ],
            component.plan.component(.nodeRuntime)?.root.path
        )
        XCTAssertEqual(
            fixture.serviceMutations().filter { $0.hasPrefix("bootstrap:") },
            [
                "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
                "bootstrap:\(DesktopManagedInstallLayout.connectorLabel)",
            ]
        )
    }

    func testComponentCandidateFailureRestoresLegacyWithoutChangingCurrentRelease() async throws {
        let fixture = try Fixture(legacyRunning: true, failAccountStart: true)
        defer { fixture.cleanup() }
        let component = try fixture.componentRelease()
        let bundledVersion = fixture.manifest.releaseVersion
        let bundledRelease = try fixture.layout.release(bundledVersion)
        try FileManager.default.createDirectory(at: bundledRelease, withIntermediateDirectories: true)
        _ = try fixture.installer.activate(releaseVersion: bundledVersion, runID: fixture.runID)

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.migrateComponentRelease(
            manifest: component.manifest,
            activationPlan: component.plan,
            hermesLaunchAgentConfiguration: component.agents.hermes,
            launchAgentConfiguration: component.agents.connector,
            legacy: fixture.legacy,
            runID: fixture.runID,
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                releaseVersion: component.manifest.releaseVersion
            )
        )) { error in
            XCTAssertEqual(error as? DesktopLaunchAgentControllerError, .accountStartFailed)
        }

        XCTAssertEqual(try fixture.journal.load()?.state, .legacyActive)
        XCTAssertEqual(try fixture.journal.load()?.releaseLayout, .componentStore)
        XCTAssertEqual(fixture.runner.loadedLabels(), ["com.hermesremote.connector"])
        XCTAssertEqual(
            try FileManager.default.destinationOfSymbolicLink(
                atPath: fixture.layout.currentRelease.path
            ),
            "releases/\(bundledVersion)"
        )
    }

    func testInterruptedComponentRecoveryDoesNotDeactivateBundledCurrentRelease() async throws {
        let fixture = try Fixture(legacyRunning: true)
        defer { fixture.cleanup() }
        let bundledVersion = fixture.manifest.releaseVersion
        let bundledRelease = try fixture.layout.release(bundledVersion)
        try FileManager.default.createDirectory(at: bundledRelease, withIntermediateDirectories: true)
        _ = try fixture.installer.activate(releaseVersion: bundledVersion, runID: fixture.runID)
        _ = try fixture.journal.begin(
            runID: fixture.runID,
            lastKnownGoodMode: .legacy,
            releaseVersion: bundledVersion,
            releaseLayout: .componentStore,
            bindingID: fixture.bindingID,
            bindingGeneration: 1
        )
        _ = try fixture.journal.transition(runID: fixture.runID, to: .accountStaged)
        _ = try fixture.journal.transition(runID: fixture.runID, to: .candidateStarting)
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
        XCTAssertEqual(
            try FileManager.default.destinationOfSymbolicLink(
                atPath: fixture.layout.currentRelease.path
            ),
            "releases/\(bundledVersion)"
        )
    }

    func testComponentManifestAndActivationPlanMismatchFailsBeforeMutation() async throws {
        let fixture = try Fixture(legacyRunning: false)
        defer { fixture.cleanup() }
        let manifestComponent = try fixture.componentRelease(releaseVersion: "2.0.0")
        let planComponent = try fixture.componentRelease(releaseVersion: "2.0.1")

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.migrateComponentRelease(
            manifest: manifestComponent.manifest,
            activationPlan: planComponent.plan,
            hermesLaunchAgentConfiguration: planComponent.agents.hermes,
            launchAgentConfiguration: planComponent.agents.connector,
            legacy: fixture.legacy,
            runID: fixture.runID,
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(
                releaseVersion: manifestComponent.manifest.releaseVersion
            )
        )) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseActivationError, .invalidManifest)
        }

        let beginCount = await fixture.account.beginCount()
        XCTAssertEqual(beginCount, 0)
        XCTAssertNil(try fixture.journal.load())
        XCTAssertTrue(fixture.runner.events().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.connectorCredential.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.hermesLaunchAgent.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.connectorLaunchAgent.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.currentRelease.path))
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

    // HG-58. A Mac migrated before the managed agent carried a PATH keeps running Hermes with
    // launchd's bare /usr/bin:/bin:/usr/sbin:/sbin, where nothing the user installed is visible —
    // which is how an installed pdftoppm was reported as "not installed". Neither a migration nor an
    // optional-component activation is guaranteed to happen again on such a machine, so the repair
    // runs from the startup reconciliation that every launch takes.
    func testACommittedAgentWithoutASearchPathIsRepairedAndOnlyHermesRestarts() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.removeEnvironmentKey(at: fixture.layout.hermesLaunchAgent, key: "PATH")
        let connectorBefore = try Data(contentsOf: fixture.layout.connectorLaunchAgent)
        let before = try fixture.environment(at: fixture.layout.hermesLaunchAgent)

        let repaired = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
        XCTAssertTrue(repaired)

        let after = try fixture.environment(at: fixture.layout.hermesLaunchAgent)
        XCTAssertEqual(after["PATH"], DesktopHermesRuntimeContract.searchPath)
        // Exactly one key changed. Anything else in that file — including keys this build has never
        // heard of — belongs to whoever put it there.
        XCTAssertEqual(after.filter { $0.key != "PATH" }, before)
        // The Connector's agent has no PATH and no business gaining one, so it is not touched at all.
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), connectorBefore)
        // Only Hermes restarts: the Connector's file did not change, so there is nothing for it to
        // re-read. The token-file reconcile restarts both because both files changed there.
        XCTAssertEqual(fixture.serviceMutations(), [
            "bootout:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
        ])
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
    }

    // What makes the restart affordable: it happens once. A second launch finds the key already
    // there, changes nothing, and spends no service mutation and no health poll — which is also why
    // no marker file is needed to remember that the repair ran.
    func testASearchPathAlreadyInPlaceCostsNothing() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let before = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        let repaired = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
        XCTAssertFalse(repaired)

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), before)
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }

    // A PATH that is already well formed is left exactly as it is, whatever it says. It may be a
    // value a later release chose or one the user set; replacing it would be this method deciding
    // something it was never asked to decide.
    func testAnExistingWellFormedSearchPathIsNotOverwritten() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.setEnvironmentValue(
            at: fixture.layout.hermesLaunchAgent,
            key: "PATH",
            value: "/opt/custom/bin:/usr/bin"
        )

        let repaired = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
        XCTAssertFalse(repaired)

        XCTAssertEqual(
            try fixture.environment(at: fixture.layout.hermesLaunchAgent)["PATH"],
            "/opt/custom/bin:/usr/bin"
        )
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }

    // A malformed one is refused rather than corrected, and refused before anything is touched. An
    // empty entry means "the current directory" to execvp, which is not a value to quietly replace
    // on someone's behalf — it is a sign the file is not what we think it is.
    func testAMalformedSearchPathIsRefusedBeforeAnyServiceChanges() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.setEnvironmentValue(
            at: fixture.layout.hermesLaunchAgent,
            key: "PATH",
            value: "/usr/bin::/bin"
        )
        let before = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        do {
            _ = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
            XCTFail("a malformed PATH must not be repaired over")
        } catch {
            XCTAssertEqual(error as? DesktopManagedInstallError, .unsafeFilesystemObject)
        }
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), before)
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }

    // The repair only knows how to fix an agent it recognises. One whose program arguments were
    // changed is somebody else's file, and the answer is to stop, not to rewrite part of it.
    func testAnUnrecognisedAgentIsRefusedBeforeAnyServiceChanges() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.removeEnvironmentKey(at: fixture.layout.hermesLaunchAgent, key: "PATH")
        let data = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        var object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any]
        )
        object["ProgramArguments"] = ["/usr/bin/true", "serve"]
        try PropertyListSerialization.data(fromPropertyList: object, format: .xml, options: 0)
            .write(to: fixture.layout.hermesLaunchAgent)
        let before = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        do {
            _ = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
            XCTFail("an agent we did not write must not be edited")
        } catch {
            XCTAssertEqual(error as? DesktopManagedInstallError, .unsafeFilesystemObject)
        }
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), before)
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }

    // A Mac left with the old PATH is the state it was already in and can send everything except a
    // PDF; a Mac left with a rewritten agent and no running Hermes cannot do anything at all. So a
    // restart that cannot prove a healthy server puts the file back and starts the old one again.
    func testAFailedRestartRestoresTheAgentAndTheRunningServer() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            hermesReadinessResponses: [false, true]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.removeEnvironmentKey(at: fixture.layout.hermesLaunchAgent, key: "PATH")
        let before = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        do {
            _ = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
            XCTFail("an unhealthy restart must not be reported as a repair")
        } catch {
            XCTAssertEqual(
                error as? DesktopMigrationCoordinatorError,
                .hermesHealthTimedOut
            )
        }
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), before)
        XCTAssertNil(try fixture.environment(at: fixture.layout.hermesLaunchAgent)["PATH"])
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
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

    func testManagedUpgradePreservesBindingAndRestartsInSafeOrder() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            healthCheckedAtSequence: [
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:01.000Z",
            ]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil, releaseVersion: "1.2.2")

        let outcome = try await fixture.coordinator.upgrade(
            manifest: fixture.manifest,
            sources: fixture.sources,
            hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
            launchAgentConfiguration: fixture.launchAgentConfiguration,
            runID: "10000000-0000-4000-8000-000000000009",
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>
                .confirmationText(releaseVersion: fixture.manifest.releaseVersion)
        )

        XCTAssertEqual(outcome.releaseVersion, "1.2.3")
        let beginCount = await fixture.account.beginCount()
        let confirmCount = await fixture.account.confirmCount()
        XCTAssertEqual(beginCount, 0)
        XCTAssertEqual(confirmCount, 0)
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
        XCTAssertEqual(try fixture.journal.load()?.releaseVersion, "1.2.3")
        XCTAssertEqual(fixture.serviceMutations(), [
            "bootout:com.hermesgo.connector",
            "bootout:com.hermesgo.hermes-server",
            "bootstrap:com.hermesgo.hermes-server",
            "bootstrap:com.hermesgo.connector",
        ])
        XCTAssertEqual(fixture.shutdown.waitCount(), 1)
    }

    func testFailedManagedUpgradeRestoresExactFilesOldReleaseAndServices() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            hermesReadinessResponses: [false, true],
            healthCheckedAtSequence: [
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:01.000Z",
            ]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil, releaseVersion: "1.2.2")
        let oldHermes = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let oldConnector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.upgrade(
            manifest: fixture.manifest,
            sources: fixture.sources,
            hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
            launchAgentConfiguration: fixture.launchAgentConfiguration,
            runID: "10000000-0000-4000-8000-000000000009",
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>
                .confirmationText(releaseVersion: fixture.manifest.releaseVersion)
        )) { error in
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .hermesHealthTimedOut)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), oldHermes)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), oldConnector)
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
        XCTAssertEqual(try fixture.journal.load()?.releaseVersion, "1.2.2")
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        let beginCount = await fixture.account.beginCount()
        let confirmCount = await fixture.account.confirmCount()
        XCTAssertEqual(beginCount, 0)
        XCTAssertEqual(confirmCount, 0)
        XCTAssertEqual(fixture.shutdown.waitCount(), 2)
    }

    func testManagedUpgradeCanMoveFromBundledReleaseToComponentStore() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            healthCheckedAtSequence: [
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:01.000Z",
            ]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let component = try fixture.componentRelease(releaseVersion: "2.0.0")

        let outcome = try await fixture.coordinator.upgradeComponentRelease(
            manifest: component.manifest,
            activationPlan: component.plan,
            hermesLaunchAgentConfiguration: component.agents.hermes,
            launchAgentConfiguration: component.agents.connector,
            runID: "10000000-0000-4000-8000-000000000010",
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>
                .confirmationText(releaseVersion: "2.0.0")
        )

        XCTAssertEqual(outcome.releaseVersion, "2.0.0")
        XCTAssertEqual(try fixture.journal.load()?.releaseLayout, .componentStore)
        XCTAssertEqual(try fixture.journal.load()?.state, .accountActive)
        let beginCount = await fixture.account.beginCount()
        let confirmCount = await fixture.account.confirmCount()
        XCTAssertEqual(beginCount, 0)
        XCTAssertEqual(confirmCount, 0)
    }

    func testRestartRecoveryUsesDurableUpgradeSnapshot() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            healthCheckedAtSequence: [
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:01.000Z",
            ]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil, releaseVersion: "1.2.2")
        let oldHermes = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let oldConnector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)
        let recoveryRun = "10000000-0000-4000-8000-000000000011"
        _ = try fixture.installer.prepareManagedUpgradeSnapshot(
            runID: recoveryRun,
            previousReleaseVersion: "1.2.2",
            targetReleaseVersion: "1.2.3",
            previousReleaseLayout: .bundledRelease,
            targetReleaseLayout: .bundledRelease
        )
        _ = try fixture.journal.beginUpgrade(
            runID: recoveryRun,
            installedReleaseVersion: "1.2.2",
            targetReleaseVersion: "1.2.3",
            installedReleaseLayout: .bundledRelease,
            targetReleaseLayout: .bundledRelease,
            bindingID: fixture.bindingID,
            bindingGeneration: 1
        )
        try Data("partial-new-hermes".utf8).write(to: fixture.layout.hermesLaunchAgent)
        try Data("partial-new-connector".utf8).write(to: fixture.layout.connectorLaunchAgent)
        for url in [fixture.layout.hermesLaunchAgent, fixture.layout.connectorLaunchAgent] {
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        }

        let recovered = try await fixture.coordinator.recoverInterrupted(
            legacy: fixture.legacy,
            runID: recoveryRun
        )

        XCTAssertEqual(recovered, .accountActive)
        XCTAssertEqual(try fixture.journal.load()?.releaseVersion, "1.2.2")
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), oldHermes)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), oldConnector)
        XCTAssertNil(try fixture.installer.loadManagedUpgradeSnapshot(runID: recoveryRun))
    }
}

/// Local runtime mode (owner decision 2026-09-21: one copy of Hermes code per Mac). These drive
/// `reconcileHermesRuntime` through the same in-memory launchctl and readiness fakes as the other
/// reconcilers, with the observation supplied directly so every branch is reachable.
final class DesktopHermesRuntimeCoordinatorTests: XCTestCase {
    func testAUsableLocalHermesReplacesTheBundledCopyAndOnlyHermesRestarts() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let connector = try Data(contentsOf: fixture.layout.connectorLaunchAgent)
        let local = fixture.localInstallation()

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(local))
        }

        XCTAssertEqual(result, .switchedToLocal(local))
        XCTAssertEqual(try fixture.installer.currentHermesRuntimeMode(), .localHermes(executable: local.executable))
        XCTAssertEqual(try fixture.programArguments(at: fixture.layout.hermesLaunchAgent), [
            fixture.layout.localHermesLauncher.path, local.executable.path,
            "serve", "--host", "127.0.0.1", "--port", "9119",
        ])
        let environment = try fixture.environment(at: fixture.layout.hermesLaunchAgent)
        XCTAssertEqual(environment["HERMES_DESKTOP"], "1")
        XCTAssertEqual(environment["HERMES_SESSION_TOKEN_FILE"], fixture.layout.hermesSessionToken.path)
        XCTAssertNil(environment["HERMES_DASHBOARD_SESSION_TOKEN"], "the token stays in its private file")
        // The exact bundled agent is kept: it is the only record of what to go back to.
        XCTAssertEqual(try Data(contentsOf: fixture.layout.bundledHermesLaunchAgentBackup), bundled)
        XCTAssertTrue(fixture.installer.bundledHermesFallbackAvailable)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.connectorLaunchAgent), connector)
        XCTAssertEqual(fixture.serviceMutations(), [
            "bootout:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
        ])
        XCTAssertEqual(fixture.shutdown.waitCount(), 1)
        XCTAssertEqual(fixture.installer.readLocalHermesRuntimeRecord()?.commit, local.commit)
        let launcher = try FileManager.default.attributesOfItem(atPath: fixture.layout.localHermesLauncher.path)
        XCTAssertEqual((launcher[.posixPermissions] as? NSNumber)?.intValue, 0o700)
    }

    /// The startup repairs recognise only the agents they wrote. In local mode they must stay quiet
    /// instead of reporting HR-MIGRATE-007 on every launch.
    func testStartupRepairsAreInertInLocalMode() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.installer.commitHermesSessionTokenFileMigration()
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }
        let mutations = fixture.serviceMutations().count

        let pathRepaired = try await fixture.coordinator.reconcileCommittedHermesSearchPath()
        let tokenMigrated = try await fixture.coordinator.reconcileCommittedHermesSessionTokenStorage()

        XCTAssertFalse(pathRepaired)
        XCTAssertFalse(tokenMigrated)
        XCTAssertEqual(fixture.serviceMutations().count, mutations)
    }

    func testAFailedSwitchRestoresTheBundledAgentAndItsServer() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            hermesReadinessResponses: [false, true]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        do {
            _ = try await fixture.coordinator.reconcileHermesRuntime {
                try fixture.runtimeObservation(.usable(fixture.localInstallation()))
            }
            XCTFail("an unhealthy local Hermes must not be reported as a switch")
        } catch {
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .hermesHealthTimedOut)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
        XCTAssertEqual(try fixture.installer.currentHermesRuntimeMode(), .bundled)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.localHermesLauncher.path))
        // The attempt is counted for the crash-loop back-off, but no process is attributed to it.
        XCTAssertNil(fixture.installer.readLocalHermesRuntimeRecord()?.processStartedAt)
        XCTAssertEqual(fixture.installer.readLocalHermesRuntimeRecord()?.recentLaunches.count, 1)
        XCTAssertEqual(fixture.runner.loadedLabels(), [
            DesktopManagedInstallLayout.connectorLabel,
            DesktopManagedInstallLayout.hermesLabel,
        ])
        XCTAssertEqual(fixture.readiness.waitCount(), 2, "the restored server is proved healthy too")
    }

    func testCodeThatChangedUnderTheRunningServerRestartsIt() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let old = fixture.localInstallation(commit: String(repeating: "a", count: 40))
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(old)) }
        let updated = fixture.localInstallation(
            commit: String(repeating: "b", count: 40),
            changedAt: Date().addingTimeInterval(-120)
        )

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(updated), serviceStartedAt: Date().addingTimeInterval(-600))
        }

        XCTAssertEqual(result, .restartedLocal(updated, .codeChanged))
        XCTAssertEqual(Array(fixture.serviceMutations().suffix(2)), [
            "bootout:\(DesktopManagedInstallLayout.hermesLabel)",
            "bootstrap:\(DesktopManagedInstallLayout.hermesLabel)",
        ])
        XCTAssertEqual(fixture.installer.readLocalHermesRuntimeRecord()?.commit, updated.commit)
        // The kept bundled agent is still the original one, not the local agent it was replaced by.
        XCTAssertTrue(fixture.installer.bundledHermesFallbackAvailable)
    }

    /// `hermes update` restarts our job itself (`launchctl kickstart`). A process that started after
    /// the checkout moved is already on the new code, and restarting it again would only cost the
    /// owner another interrupted turn.
    func testAServerStartedAfterTheUpdateIsLeftAlone() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }
        let mutations = fixture.serviceMutations().count
        let updated = fixture.localInstallation(
            commit: String(repeating: "c", count: 40),
            changedAt: Date().addingTimeInterval(-300)
        )

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(updated), serviceStartedAt: Date().addingTimeInterval(-200))
        }

        XCTAssertEqual(result, .unchanged(.keep))
        XCTAssertEqual(fixture.serviceMutations().count, mutations)
    }

    func testRemovingTheOwnersHermesRestoresTheBundledCopy() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.absent(hermesDataPresent: true))
        }

        XCTAssertEqual(result, .restoredBundled)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.bundledHermesLaunchAgentBackup.path))
        XCTAssertNil(fixture.installer.readLocalHermesRuntimeRecord())
    }

    /// Turning the setting off is the rollback, and needs no knowledge of what was on disk before.
    func testTurningTheSettingOffRestoresTheBundledCopy() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(local), enabled: false)
        }

        XCTAssertEqual(result, .restoredBundled)
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
    }

    func testDisabledOrUnsupportedChangesNothing() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        let disabled = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()), enabled: false)
        }
        let unsupported = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.unsupported(.multipleProfiles, detail: "2 profile(s)"))
        }

        XCTAssertEqual(disabled, .unchanged(.keep))
        XCTAssertEqual(unsupported, .unchanged(.surface(.unsupported(.multipleProfiles, detail: "2 profile(s)"))))
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.bundledHermesLaunchAgentBackup.path))
    }

    func testNothingIsReconciledBeforeACommittedInstallation() async throws {
        let fixture = try Fixture(legacyRunning: false)
        defer { fixture.cleanup() }

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            XCTFail("an uninstalled Mac must not even be observed")
            return try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }

        XCTAssertEqual(result, .notInstalled)
        XCTAssertTrue(fixture.runner.events().isEmpty)
    }

    // MARK: Review fixes (2026-09-21)

    /// Item 1: the documented rollback (setting off) must not leave the phone without Hermes when
    /// the bundled copy cannot start — the owner's Hermes is intact, so it goes back to that.
    func testAFailedRollbackToBundledReturnsToTheIntactLocalHermes() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true, hermesReadinessResponses: [true, false, true])
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        let localAgent = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        do {
            _ = try await fixture.coordinator.reconcileHermesRuntime {
                try fixture.runtimeObservation(.usable(local), enabled: false)
            }
            XCTFail("an unhealthy bundled copy is not a completed rollback")
        } catch {
            XCTAssertEqual(error as? DesktopMigrationCoordinatorError, .hermesHealthTimedOut)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), localAgent)
        XCTAssertTrue(fixture.runner.loadedLabels().contains(DesktopManagedInstallLayout.hermesLabel))
        XCTAssertEqual(fixture.readiness.waitCount(), 3, "the restored local Hermes is proved healthy")
        XCTAssertTrue(fixture.installer.bundledHermesFallbackAvailable, "the kept agent survives for the next try")
    }

    /// Item 1, other branch: the owner's Hermes is gone, so the bundled agent is the only thing that
    /// can run; it stays and stays loaded.
    func testAFailedRestoreAfterRemovalKeepsTheBundledAgentLoaded() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true, hermesReadinessResponses: [true, false])
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.absent(hermesDataPresent: true))
        })

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
        XCTAssertTrue(fixture.runner.loadedLabels().contains(DesktopManagedInstallLayout.hermesLabel))
    }

    /// Item 2: a transient bootstrap failure after bootout must not leave the job unloaded.
    func testAFailedStartDuringASwitchLeavesTheBundledJobLoaded() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)
        fixture.runner.failHermesBootstraps(1)

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }) { error in
            XCTAssertEqual(error as? DesktopLaunchAgentControllerError, .hermesStartFailed)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
        XCTAssertTrue(fixture.runner.loadedLabels().contains(DesktopManagedInstallLayout.hermesLabel))
    }

    func testAFailedStartDuringALocalRestartLeavesTheJobLoaded() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        fixture.runner.failHermesBootstraps(1)

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(local), service: .stopped)
        })

        XCTAssertTrue(fixture.runner.loadedLabels().contains(DesktopManagedInstallLayout.hermesLabel))
        XCTAssertTrue(try fixture.installer.currentHermesRuntimeMode().isLocal)
    }

    /// Item 2: and a job that *is* unloaded is still acted on (the old guard ignored it forever).
    func testAnUnloadedLocalJobIsStartedAgain() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        fixture.runner.replaceLoaded(with: [DesktopManagedInstallLayout.connectorLabel])

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(local), service: .notLoaded)
        }

        XCTAssertEqual(result, .restartedLocal(local, .notRunning))
        XCTAssertTrue(fixture.runner.loadedLabels().contains(DesktopManagedInstallLayout.hermesLabel))
        XCTAssertEqual(fixture.installer.readLocalHermesRuntimeRecord()?.recentLaunches.count, 2)
    }

    /// Item 3: an upgrade in local mode makes the kept bundled agent name the new release, and a
    /// failed upgrade puts the old kept agent back with everything else.
    func testAnUpgradeInLocalModeRefreshesTheKeptBundledAgent() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            healthCheckedAtSequence: ["2026-09-07T00:00:00.000Z", "2026-09-07T00:00:00.000Z", "2026-09-07T00:00:01.000Z"]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }
        let component = try fixture.componentRelease(releaseVersion: "2.0.0")

        _ = try await fixture.coordinator.upgradeComponentRelease(
            manifest: component.manifest,
            activationPlan: component.plan,
            hermesLaunchAgentConfiguration: component.agents.hermes,
            launchAgentConfiguration: component.agents.connector,
            runID: "10000000-0000-4000-8000-000000000010",
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(releaseVersion: "2.0.0")
        )

        XCTAssertTrue(try fixture.installer.currentHermesRuntimeMode().isLocal)
        XCTAssertEqual(
            try fixture.programArguments(at: fixture.layout.bundledHermesLaunchAgentBackup).first,
            component.agents.hermes.hermesExecutable.path
        )
        XCTAssertTrue(fixture.installer.bundledHermesFallbackAvailable)
    }

    func testAFailedUpgradeInLocalModeRestoresTheOldKeptAgent() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            hermesReadinessResponses: [true, false, true],
            healthCheckedAtSequence: ["2026-09-07T00:00:00.000Z", "2026-09-07T00:00:00.000Z", "2026-09-07T00:00:00.000Z", "2026-09-07T00:00:01.000Z"]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil, releaseVersion: "1.2.2")
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }
        let keptBefore = try Data(contentsOf: fixture.layout.bundledHermesLaunchAgentBackup)
        let component = try fixture.componentRelease(releaseVersion: "2.0.0")

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.upgradeComponentRelease(
            manifest: component.manifest,
            activationPlan: component.plan,
            hermesLaunchAgentConfiguration: component.agents.hermes,
            launchAgentConfiguration: component.agents.connector,
            runID: "10000000-0000-4000-8000-000000000011",
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>.confirmationText(releaseVersion: "2.0.0")
        ))

        XCTAssertEqual(try Data(contentsOf: fixture.layout.bundledHermesLaunchAgentBackup), keptBefore)
    }

    /// Item 3: a kept agent whose program is gone (garbage-collected component) is no fallback.
    func testAKeptAgentWhoseProgramIsGoneIsNotAFallback() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }
        XCTAssertTrue(fixture.installer.bundledHermesFallbackAvailable)

        try FileManager.default.removeItem(at: fixture.layout.releasesRoot.appendingPathComponent("1.2.3/hermes_server/bin/hermes-server"))

        XCTAssertFalse(fixture.installer.bundledHermesFallbackAvailable)
        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.absent(hermesDataPresent: true))
        }
        XCTAssertEqual(result, .unchanged(.surface(.localHermesMissingWithoutFallback)))
    }

    /// Item 4: a launcher from another build, or with loosened permissions, does not make the Mac
    /// unrecognisable; it is rewritten without a restart.
    func testALoosenedLauncherIsRepairedAndLocalModeStillRecognised() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        try Data("#!/bin/sh\n# an older build's launcher\n".utf8).write(to: fixture.layout.localHermesLauncher)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: fixture.layout.localHermesLauncher.path)
        let mutations = fixture.serviceMutations().count

        XCTAssertEqual(try fixture.installer.currentHermesRuntimeMode(), .localHermes(executable: local.executable))
        let result = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }

        XCTAssertEqual(result, .repairedLauncher)
        XCTAssertTrue(fixture.installer.localHermesLauncherIsCurrent(executable: local.executable))
        XCTAssertEqual(fixture.serviceMutations().count, mutations)
    }

    /// Item 10: a fresh install on a Mac with a usable Hermes never runs the bundled copy.
    func testAFreshInstallRunsTheLocalHermesDirectly() async throws {
        let local = DesktopLocalHermesInstallation(
            executable: URL(fileURLWithPath: "/Users/o/.hermes/hermes-agent/venv/bin/hermes"),
            checkoutRoot: URL(fileURLWithPath: "/Users/o/.hermes/hermes-agent"),
            hermesHome: URL(fileURLWithPath: "/Users/o/.hermes"),
            commit: String(repeating: "1", count: 40),
            version: "0.21.3",
            identityChangedAt: Date().addingTimeInterval(-3_600)
        )
        let fixture = try Fixture(legacyRunning: true, localHermesForFreshInstall: local)
        defer { fixture.cleanup() }

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

        XCTAssertEqual(try fixture.installer.currentHermesRuntimeMode(), .localHermes(executable: local.executable))
        XCTAssertEqual(
            fixture.serviceMutations().filter { $0.hasSuffix(DesktopManagedInstallLayout.hermesLabel) },
            ["bootstrap:\(DesktopManagedInstallLayout.hermesLabel)"],
            "Hermes is started once, and it is the local one"
        )
        XCTAssertEqual(
            try fixture.programArguments(at: fixture.layout.bundledHermesLaunchAgentBackup).first,
            fixture.hermesLaunchAgentConfiguration.hermesExecutable.standardizedFileURL.path
        )
    }

    /// Item 13: never copy an inline token into `state/`, and never switch from such an agent.
    func testAnInlineTokenAgentIsNotSwitched() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456")
        let bundled = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        await XCTAssertThrowsErrorAsync(try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }) { error in
            XCTAssertEqual(error as? DesktopHermesRuntimeError, .bundledAgentCarriesInlineToken)
        }

        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), bundled)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.layout.bundledHermesLaunchAgentBackup.path))
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }

    /// Item 14: another operation holding the lease means "next refresh", not an error.
    func testLeaseContentionWaitsWithoutAnError() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let lease = try fixture.journal.acquireOperationLease()

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }

        withExtendedLifetime(lease) {}
        XCTAssertEqual(result, .unchanged(.wait(.busy)))
        XCTAssertTrue(fixture.serviceMutations().isEmpty)
    }

    /// Item 8: adopting a process `hermes update` started records its commit and start time.
    func testAnUpdateStartedProcessIsAdoptedIntoTheRecord() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true)
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(fixture.localInstallation()))
        }
        let updated = fixture.localInstallation(commit: String(repeating: "c", count: 40), changedAt: Date().addingTimeInterval(-300))
        let started = Date().addingTimeInterval(-200)

        _ = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(updated), serviceStartedAt: started)
        }

        let record = try XCTUnwrap(fixture.installer.readLocalHermesRuntimeRecord())
        XCTAssertEqual(record.commit, updated.commit)
        XCTAssertEqual(record.processStartedAt?.timeIntervalSince1970 ?? 0, started.timeIntervalSince1970, accuracy: 0.001)
    }

    /// Re-review A: two switches that never become ready pause switching, and the phone keeps the
    /// bundled Hermes instead of losing it every five minutes.
    func testRepeatedlyFailedSwitchesStopTouchingTheService() async throws {
        let fixture = try Fixture(legacyRunning: false, resumeBoundBinding: true, hermesReadinessResponses: [false, true, false, true])
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        let local = fixture.localInstallation()
        for _ in 0..<2 {
            await XCTAssertThrowsErrorAsync(try await fixture.coordinator.reconcileHermesRuntime {
                try fixture.runtimeObservation(.usable(local))
            })
        }
        XCTAssertEqual(fixture.installer.readHermesRuntimeFailures().switchFailures, 2)
        let mutations = fixture.serviceMutations().count

        let result = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }

        XCTAssertEqual(result, .unchanged(.surface(.switchToLocalPaused(commit: local.commit, failures: 2))))
        XCTAssertEqual(fixture.serviceMutations().count, mutations)
        XCTAssertEqual(try fixture.installer.currentHermesRuntimeMode(), .bundled)

        // Turning the setting off is the owner's reset.
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local), enabled: false) }
        XCTAssertEqual(fixture.installer.readHermesRuntimeFailures().switchFailures, 0)
    }

    /// Re-review B: with the setting off and a bundled copy that cannot start, the rollback is
    /// attempted twice and then left alone, with the owner's Hermes running.
    func testRepeatedlyFailedRollbacksStopStoppingTheLocalHermes() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            hermesReadinessResponses: [true, false, true, false, true]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil)
        try fixture.stageBundledExecutable()
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        for _ in 0..<2 {
            await XCTAssertThrowsErrorAsync(try await fixture.coordinator.reconcileHermesRuntime {
                try fixture.runtimeObservation(.usable(local), enabled: false)
            })
        }
        let mutations = fixture.serviceMutations().count

        let result = try await fixture.coordinator.reconcileHermesRuntime {
            try fixture.runtimeObservation(.usable(local), enabled: false)
        }

        XCTAssertEqual(result, .unchanged(.surface(.restoreBundledPaused(failures: 2))))
        XCTAssertEqual(fixture.serviceMutations().count, mutations)
        XCTAssertTrue(try fixture.installer.currentHermesRuntimeMode().isLocal)
        XCTAssertTrue(fixture.runner.loadedLabels().contains(DesktopManagedInstallLayout.hermesLabel))

        // Turning the setting back on forgets it.
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        XCTAssertEqual(fixture.installer.readHermesRuntimeFailures().restoreFailures, 0)
    }

    /// An upgrade must not quietly put the bundled Hermes back on a Mac running its own.
    func testAManagedUpgradeKeepsLocalHermes() async throws {
        let fixture = try Fixture(
            legacyRunning: false,
            resumeBoundBinding: true,
            healthCheckedAtSequence: [
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:00.000Z",
                "2026-09-07T00:00:01.000Z",
            ]
        )
        defer { fixture.cleanup() }
        try fixture.installCommittedManagedServices(inlineToken: nil, releaseVersion: "1.2.2")
        let local = fixture.localInstallation()
        _ = try await fixture.coordinator.reconcileHermesRuntime { try fixture.runtimeObservation(.usable(local)) }
        let localAgent = try Data(contentsOf: fixture.layout.hermesLaunchAgent)

        let outcome = try await fixture.coordinator.upgrade(
            manifest: fixture.manifest,
            sources: fixture.sources,
            hermesLaunchAgentConfiguration: fixture.hermesLaunchAgentConfiguration,
            launchAgentConfiguration: fixture.launchAgentConfiguration,
            runID: "10000000-0000-4000-8000-000000000009",
            confirmation: DesktopMigrationCoordinator<InMemoryLaunchctlRunner>
                .confirmationText(releaseVersion: fixture.manifest.releaseVersion)
        )

        XCTAssertEqual(outcome.releaseVersion, "1.2.3")
        XCTAssertEqual(try Data(contentsOf: fixture.layout.hermesLaunchAgent), localAgent)
        XCTAssertEqual(try fixture.installer.currentHermesRuntimeMode(), .localHermes(executable: local.executable))
    }
}

private extension Fixture {
    func localInstallation(
        commit: String = "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121",
        changedAt: Date = Date().addingTimeInterval(-3_600)
    ) -> DesktopLocalHermesInstallation {
        let checkout = root.appendingPathComponent("owner/.hermes/hermes-agent", isDirectory: true)
        return DesktopLocalHermesInstallation(
            executable: checkout.appendingPathComponent("venv/bin/hermes"),
            checkoutRoot: checkout,
            hermesHome: root.appendingPathComponent("owner/.hermes", isDirectory: true),
            commit: commit,
            version: "0.21.3",
            identityChangedAt: changedAt
        )
    }

    /// An observation built from the real installer state, with the Mac-side facts supplied.
    func runtimeObservation(
        _ detection: DesktopLocalHermesDetection,
        enabled: Bool = true,
        serviceStartedAt: Date = Date(),
        service: DesktopHermesServiceProcess? = nil,
        updateInProgress: Bool = false
    ) throws -> DesktopHermesRuntimeObservation {
        let mode = try installer.currentHermesRuntimeMode()
        var launcherCurrent = true
        if case .localHermes(let executable) = mode {
            launcherCurrent = installer.localHermesLauncherIsCurrent(executable: executable)
        }
        return DesktopHermesRuntimeObservation(
            enabled: enabled,
            detection: detection,
            mode: mode,
            launcherCurrent: launcherCurrent,
            updateInProgress: updateInProgress,
            service: service ?? .running(startedAt: serviceStartedAt, arguments: nil),
            agentArguments: installer.hermesAgentProgramArguments,
            record: installer.readLocalHermesRuntimeRecord(),
            bundledFallbackAvailable: installer.bundledHermesFallbackAvailable,
            failures: installer.readHermesRuntimeFailures(),
            bundledBackupDigest: installer.bundledHermesBackupDigest,
            now: Date()
        )
    }

    /// Put a real executable where the bundled agent points, so the kept agent is a usable
    /// fallback (`bundledHermesFallbackAvailable` checks the program exists).
    func stageBundledExecutable(releaseVersion: String = "1.2.3") throws {
        let release = layout.releasesRoot.appendingPathComponent(releaseVersion, isDirectory: true)
        let bin = release.appendingPathComponent("hermes_server/bin", isDirectory: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let executable = bin.appendingPathComponent("hermes-server")
        try Data("#!/bin/sh\n".utf8).write(to: executable)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: executable.path)
        try? FileManager.default.removeItem(at: layout.currentRelease)
        try FileManager.default.createSymbolicLink(
            atPath: layout.currentRelease.path,
            withDestinationPath: "releases/\(releaseVersion)"
        )
    }

    func programArguments(at url: URL) throws -> [String] {
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: url), options: [], format: nil)
                as? [String: Any]
        )
        return try XCTUnwrap(object["ProgramArguments"] as? [String])
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
    let shutdown: MigrationHermesShutdown
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
        manifestVersion: String = "1.2.3",
        localHermesForFreshInstall: DesktopLocalHermesInstallation? = nil
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
        shutdown = MigrationHermesShutdown()
        coordinator = try DesktopMigrationCoordinator(
            account: account,
            journal: journal,
            installer: installer,
            launchAgent: controller,
            hermesReadiness: readiness,
            hermesShutdown: shutdown,
            maximumHealthPolls: 2,
            healthPollDelayNanoseconds: 0,
            localHermesForFreshInstall: { localHermesForFreshInstall }
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
        bindingGeneration: Int = 1,
        releaseVersion: String? = nil
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
            releaseVersion: releaseVersion ?? manifest.releaseVersion,
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

    func componentRelease(
        releaseVersion: String = "2.0.0"
    ) throws -> (
        manifest: DesktopComponentReleaseManifestV2,
        plan: DesktopComponentReleaseActivationPlan,
        agents: (
            hermes: DesktopHermesServerLaunchAgent,
            connector: DesktopAccountConnectorLaunchAgent
        )
    ) {
        let writer = try DesktopManagedComponentStoreWriter(
            root: layout.root,
            currentUserID: Darwin.getuid()
        )
        let entrypoints: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "bin/python3",
            .hermesCore: "bin/hermes",
            .nodeRuntime: "bin/node",
            .connector: "bin/hermes-connector",
        ]
        var hashes: [DesktopManagedComponentKind: String] = [:]
        for kind in [
            DesktopManagedComponentKind.pythonRuntime, .hermesCore, .nodeRuntime, .connector,
        ] {
            let source = root.appendingPathComponent(
                "component-source-\(kind.rawValue)", isDirectory: true
            )
            let entrypoint = source.appendingPathComponent(entrypoints[kind]!)
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o700],
                ofItemAtPath: entrypoint.path
            )
            let hash = try DesktopManagedComponentContentHasher()
                .identify(directory: source).sha256
            hashes[kind] = hash
            _ = try writer.commit(
                sourceDirectory: source,
                receipt: DesktopManagedComponentReceipt(
                    kind: kind,
                    version: "1.2.3",
                    architecture: "arm64",
                    contentSHA256: hash
                ),
                runID: UUID().uuidString,
                healthProbe: { _ in true }
            )
        }
        let python = Self.componentArtifact(
            kind: .pythonRuntime,
            entrypoint: entrypoints[.pythonRuntime]!,
            contentSHA256: hashes[.pythonRuntime]!,
            dependencies: []
        )
        let hermes = Self.componentArtifact(
            kind: .hermesCore,
            entrypoint: entrypoints[.hermesCore]!,
            contentSHA256: hashes[.hermesCore]!,
            dependencies: [
                .init(kind: .pythonRuntime, contentSHA256: hashes[.pythonRuntime]!),
            ]
        )
        let node = Self.componentArtifact(
            kind: .nodeRuntime,
            entrypoint: entrypoints[.nodeRuntime]!,
            contentSHA256: hashes[.nodeRuntime]!,
            dependencies: []
        )
        let connector = Self.componentArtifact(
            kind: .connector,
            entrypoint: entrypoints[.connector]!,
            contentSHA256: hashes[.connector]!,
            dependencies: [
                .init(kind: .hermesCore, contentSHA256: hashes[.hermesCore]!),
                .init(kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!),
            ]
        )
        let manifest = DesktopComponentReleaseManifestV2(
            releaseVersion: releaseVersion,
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [python, hermes, node, connector]
        )
        let plan = try DesktopComponentReleaseActivationPlanner(
            storeRoot: layout.root,
            currentUserID: Darwin.getuid()
        ).plan(manifest: manifest) { _, _, _ in true }
        let configuration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: root.appendingPathComponent("hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        return (manifest, plan, try configuration.componentLaunchAgents(for: plan))
    }

    func environment(at url: URL) throws -> [String: String] {
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(
                from: Data(contentsOf: url),
                options: [],
                format: nil
            ) as? [String: Any]
        )
        return try XCTUnwrap(object["EnvironmentVariables"] as? [String: String])
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

    /// Rewinds a managed agent to the shape a Desktop written before HG-58 left on disk.
    func removeEnvironmentKey(at url: URL, key: String) throws {
        let data = try Data(contentsOf: url)
        guard var object = try PropertyListSerialization.propertyList(
            from: data,
            options: [],
            format: nil
        ) as? [String: Any],
        var environment = object["EnvironmentVariables"] as? [String: Any]
        else { throw DesktopManagedInstallError.persistenceFailed }
        environment.removeValue(forKey: key)
        object["EnvironmentVariables"] = environment
        let rewound = try PropertyListSerialization.data(
            fromPropertyList: object,
            format: .xml,
            options: 0
        )
        try rewound.write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    /// Writes an arbitrary value into one environment key, to test what the repair refuses.
    func setEnvironmentValue(at url: URL, key: String, value: String) throws {
        let data = try Data(contentsOf: url)
        guard var object = try PropertyListSerialization.propertyList(
            from: data,
            options: [],
            format: nil
        ) as? [String: Any],
        var environment = object["EnvironmentVariables"] as? [String: Any]
        else { throw DesktopManagedInstallError.persistenceFailed }
        environment[key] = value
        object["EnvironmentVariables"] = environment
        let written = try PropertyListSerialization.data(
            fromPropertyList: object,
            format: .xml,
            options: 0
        )
        try written.write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
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

    private static func componentArtifact(
        kind: DesktopManagedComponentKind,
        entrypoint: String,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        let fileName = "Hermes-Component-\(kind.rawValue)-1.2.3-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            installPhase: .bootstrap,
            requiredForBootstrap: true,
            reuseContract: .exactContent,
            fileName: fileName,
            entrypoint: entrypoint,
            downloadURL: "https://downloads.example/desktop/components/\(fileName)",
            sizeBytes: 1,
            sha256: String(repeating: "a", count: 64),
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
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
                if label == DesktopManagedInstallLayout.hermesLabel, failingHermesBootstraps > 0 {
                    failingHermesBootstraps -= 1
                    return CommandResult(status: 5)
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
    /// The next `count` Hermes bootstraps fail the way launchd's transient "Bootstrap failed: 5" does.
    func failHermesBootstraps(_ count: Int) { lock.withLock { failingHermesBootstraps = count } }
    private var failingHermesBootstraps = 0
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

private final class MigrationHermesShutdown: DesktopHermesShutdownChecking, @unchecked Sendable {
    private let lock = NSLock()
    private var waits = 0

    func waitUntilStopped(
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool {
        lock.withLock { waits += 1 }
        return true
    }

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
