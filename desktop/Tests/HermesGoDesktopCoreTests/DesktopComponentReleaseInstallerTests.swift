import CryptoKit
import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleaseInstallerTests: XCTestCase {
    func testPrepareUsesOnlyPrivateWorkspaceUntilMatchingInstallerCommits() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let downloader = FixtureComponentDownloader()
        let installer = try fixture.installer(downloader: downloader)
        let prepared = try await installer.prepare(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: "01000000-0000-4000-8000-000000000001",
            healthProbe: executableProbe
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: prepared.workspaceDirectory.path))
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [.nodeRuntime, .connector])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        for artifact in fixture.manifest.components {
            XCTAssertFalse(FileManager.default.fileExists(
                atPath: fixture.managedContent(artifact).path
            ))
        }

        let installed = try installer.commit(prepared, healthProbe: executableProbe)
        XCTAssertTrue(FileManager.default.fileExists(atPath: installed.referenceURL.path))
        XCTAssertEqual(installed.activationPlan.components.count, 2)
        try installer.discard(installed)
    }

    func testPreparedTokenCannotBeCommittedOrDiscardedByAnotherInstaller() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let issuer = try fixture.installer(downloader: FixtureComponentDownloader())
        let other = try fixture.installer(downloader: FixtureComponentDownloader())
        let prepared = try await issuer.prepare(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: "02000000-0000-4000-8000-000000000002",
            healthProbe: executableProbe
        )

        XCTAssertThrowsError(try other.commit(prepared, healthProbe: executableProbe)) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleaseInstallError,
                .preparationMismatch
            )
        }
        XCTAssertThrowsError(try other.discard(prepared)) {
            XCTAssertEqual(
                $0 as? DesktopComponentReleaseInstallError,
                .preparationMismatch
            )
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: prepared.workspaceDirectory.path))
        try issuer.discard(prepared)
        XCTAssertFalse(FileManager.default.fileExists(atPath: prepared.workspaceDirectory.path))
    }

    func testCommitRehashesPreparedContentAndPublishesNoReferenceAfterTampering() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let installer = try fixture.installer(downloader: FixtureComponentDownloader())
        let runID = "03000000-0000-4000-8000-000000000003"
        let prepared = try await installer.prepare(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )
        let stagedNode = prepared.workspaceDirectory.appendingPathComponent(
            "extracted/\(runID)-node_runtime/bin/node"
        )
        try Data("tampered".utf8).write(to: stagedNode)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: stagedNode.path
        )

        XCTAssertThrowsError(try installer.commit(prepared, healthProbe: executableProbe)) {
            XCTAssertEqual($0 as? DesktopManagedComponentStoreError, .identityMismatch)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.managedContent(fixture.artifact(.nodeRuntime)).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: prepared.workspaceDirectory.path))
    }

    func testInstallCommitsTwoComponentsRecordsReferenceAndReturnsActivationPlan() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let downloader = FixtureComponentDownloader()
        let installer = try fixture.installer(downloader: downloader)

        let installed = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: "10000000-0000-4000-8000-000000000001",
            healthProbe: executableProbe
        )

        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [.nodeRuntime, .connector])
        XCTAssertEqual(installed.activationPlan.components.count, 2)
        for artifact in fixture.manifest.components {
            let component = fixture.managedContent(artifact)
            XCTAssertTrue(FileManager.default.fileExists(atPath: component.path))
            XCTAssertEqual(
                installed.activationPlan.component(artifact.kind)?.root.standardizedFileURL,
                component.standardizedFileURL
            )
        }
        let reference = try JSONDecoder().decode(
            DesktopManagedComponentReferenceSet.self,
            from: Data(contentsOf: installed.referenceURL)
        )
        XCTAssertEqual(reference.releaseVersion, fixture.manifest.releaseVersion)
        XCTAssertEqual(Set(reference.components.map(\.kind)), Set([.nodeRuntime, .connector]))

        try installer.discard(installed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: installed.workspaceDirectory.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: installed.referenceURL.path))
    }

    func testSecondInstallReusesExactStoreWithoutDownloading() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let firstDownloader = FixtureComponentDownloader()
        let first = try fixture.installer(downloader: firstDownloader)
        let installed = try await first.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: "20000000-0000-4000-8000-000000000002",
            healthProbe: executableProbe
        )
        try first.discard(installed)

        let secondDownloader = FixtureComponentDownloader()
        let second = try fixture.installer(downloader: secondDownloader)
        let reused = try await second.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: "30000000-0000-4000-8000-000000000003",
            healthProbe: executableProbe
        )

        let secondRequests = await secondDownloader.requestedKinds()
        XCTAssertEqual(secondRequests, [])
        XCTAssertEqual(reused.referenceURL, installed.referenceURL)
        XCTAssertEqual(reused.activationPlan, installed.activationPlan)
        try second.discard(reused)
    }

    func testExtractionFailureRemovesRunWorkspaceAndDoesNotPublishReference() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let downloader = FixtureComponentDownloader()
        let installer = try fixture.installer(
            downloader: downloader,
            failExtractionFor: .connector
        )
        let runID = "40000000-0000-4000-8000-000000000004"

        await XCTAssertThrowsErrorAsync(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )) { error in
            XCTAssertEqual(error as? FixtureComponentInstallError, .extractionFailed)
        }

        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [.nodeRuntime, .connector])
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.managedContent(fixture.artifact(.nodeRuntime)).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.managedContent(fixture.artifact(.nodeRuntime)).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.managedContent(fixture.artifact(.connector)).path
        ))
    }

    func testExistingUnhealthyComponentFailsWithoutReplacingOrDownloading() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let first = try fixture.installer(downloader: FixtureComponentDownloader())
        let installed = try await first.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: "50000000-0000-4000-8000-000000000005",
            healthProbe: executableProbe
        )
        try first.discard(installed)
        let downloader = FixtureComponentDownloader()
        let second = try fixture.installer(downloader: downloader)
        let runID = "60000000-0000-4000-8000-000000000006"

        await XCTAssertThrowsErrorAsync(try await second.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: { kind, _, _ in kind != .nodeRuntime }
        )) { error in
            XCTAssertEqual(
                error as? DesktopComponentReleaseInstallError,
                .componentHealthProbeFailed(.nodeRuntime)
            )
        }

        let requests = await downloader.requestedKinds()
        XCTAssertEqual(requests, [])
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
    }

    func testInvalidTopologyFailsBeforeWorkspaceOrNetworkMutation() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let downloader = FixtureComponentDownloader()
        var components = fixture.manifest.components
        let connectorIndex = try XCTUnwrap(components.firstIndex(where: { $0.kind == .connector }))
        let connector = components[connectorIndex]
        components[connectorIndex] = fixture.makeArtifact(
            kind: .connector,
            contentSHA256: connector.contentSHA256,
            dependencies: connector.dependencies.filter { $0.kind != .nodeRuntime }
        )
        let invalid = fixture.manifest(with: components)
        XCTAssertThrowsError(try fixture.verify(invalid)) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseVerificationError, .incompatibleRelease)
        }

        let requests = await downloader.requestedKinds()
        XCTAssertEqual(requests, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.workspace.path))
    }

    func testTransportInterruptionKeepsManifestBoundWorkspaceAndResumesSameRun() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let downloader = FixtureComponentDownloader(failOnceFor: .connector)
        let installer = try fixture.installer(downloader: downloader)
        let runID = "80000000-0000-4000-8000-000000000008"
        let runRoot = fixture.workspace.appendingPathComponent(runID)

        await XCTAssertThrowsErrorAsync(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )) { error in
            XCTAssertEqual(error as? DesktopComponentDownloadError, .transportFailed)
        }
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: runRoot.appendingPathComponent("install.json").path
        ))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: runRoot.appendingPathComponent(
                "downloads/.Hermes-Component-connector-1.2.3-arm64.tar.gz.partial"
            ).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.managedContent(fixture.artifact(.nodeRuntime)).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.managedContent(fixture.artifact(.nodeRuntime)).path
        ))

        let otherManifest = fixture.manifest(
            releaseVersion: "0.4.2",
            components: fixture.manifest.components
        )
        let otherVerified = try fixture.verify(otherManifest)
        await XCTAssertThrowsErrorAsync(try await installer.install(
            verifiedManifest: otherVerified,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )) { error in
            XCTAssertEqual(
                error as? DesktopComponentReleaseInstallError,
                .workspaceAlreadyExists
            )
        }

        let installed = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )
        let requests = await downloader.requestedKinds()
        XCTAssertEqual(requests, [.nodeRuntime, .connector, .nodeRuntime, .connector])
        XCTAssertTrue(FileManager.default.fileExists(atPath: installed.referenceURL.path))
        try installer.discard(installed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: runRoot.path))
    }

    func testInterruptedWorkspaceCanBeExplicitlyDiscarded() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let installer = try fixture.installer(
            downloader: FixtureComponentDownloader(failOnceFor: .nodeRuntime)
        )
        let runID = "90000000-0000-4000-8000-000000000009"
        let runRoot = fixture.workspace.appendingPathComponent(runID)
        await XCTAssertThrowsErrorAsync(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )) { error in
            XCTAssertEqual(error as? DesktopComponentDownloadError, .transportFailed)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: runRoot.path))

        let marker = runRoot.appendingPathComponent("install.json")
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o644], ofItemAtPath: marker.path
        )
        XCTAssertThrowsError(try installer.discardInterruptedInstall(
            workspaceRoot: fixture.workspace,
            runID: runID
        )) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseInstallError, .invalidWorkspace)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: runRoot.path))
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: marker.path
        )

        try installer.discardInterruptedInstall(
            workspaceRoot: fixture.workspace,
            runID: runID
        )

        XCTAssertFalse(FileManager.default.fileExists(atPath: runRoot.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        XCTAssertNoThrow(try installer.discardInterruptedInstall(
            workspaceRoot: fixture.workspace,
            runID: runID
        ))
    }

    private var executableProbe: DesktopComponentReleaseInstaller.HealthProbe {
        { _, root, entrypoint in
            FileManager.default.fileExists(atPath: root.path)
                && FileManager.default.isExecutableFile(atPath: entrypoint.path)
        }
    }
}

extension DesktopComponentReleaseInstallerTests {
    func testBootstrapExecutorRejectsMismatchedTrustedPreflightBeforeWorkspaceOrDownload()
        async throws
    {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let downloader = FixtureComponentDownloader()
        let executor = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: downloader),
            migration: RecordingComponentBootstrapMigration()
        )
        let trusted = try fixture.trustedPreflight()
        let mismatchedManifest = fixture.manifest(
            releaseVersion: "0.4.2",
            components: fixture.manifest.components
        )
        let mismatched = DesktopTrustedComponentPreflight(
            result: DesktopComponentReleasePreflightResult(
                manifest: mismatchedManifest,
                plan: trusted.result.plan,
                externalEnvironment: trusted.result.externalEnvironment
            ),
            verifiedManifest: trusted.verifiedManifest
        )

        await XCTAssertThrowsErrorAsync(try await executor.prepare(
            trustedPreflight: mismatched,
            workspaceRoot: fixture.workspace,
            runID: "a0000000-0000-4000-8000-000000000000",
            healthProbe: executableProbe
        )) { error in
            XCTAssertEqual(
                error as? DesktopComponentBootstrapExecutorError,
                .invalidPreflight
            )
        }

        let requests = await downloader.requestedKinds()
        XCTAssertEqual(requests, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.workspace.path))
    }

    func testBootstrapExecutorKeepsStoreUntouchedUntilExactConfirmationThenMigratesPinnedPlan()
        async throws
    {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let migration = RecordingComponentBootstrapMigration()
        let executor = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: FixtureComponentDownloader()),
            migration: migration
        )
        let runID = "a0000000-0000-4000-8000-000000000001"
        let preparation = try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )

        XCTAssertEqual(preparation.preflight.manifest, fixture.manifest)
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        for artifact in fixture.manifest.components {
            XCTAssertFalse(FileManager.default.fileExists(
                atPath: fixture.managedContent(artifact).path
            ))
        }

        await XCTAssertThrowsErrorAsync(try await executor.commit(
            preparation,
            configuration: try fixture.commitConfiguration(),
            legacy: fixture.legacySnapshot,
            confirmation: "升级"
        )) { error in
            XCTAssertEqual(
                error as? DesktopComponentBootstrapExecutorError,
                .confirmationRequired
            )
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        let callsBeforeConfirmation = await migration.recordedCalls()
        XCTAssertEqual(callsBeforeConfirmation.count, 0)

        let outcome = try await executor.commit(
            preparation,
            configuration: try fixture.commitConfiguration(),
            legacy: fixture.legacySnapshot,
            confirmation: preparation.confirmationText
        )

        XCTAssertTrue(outcome.temporaryWorkspaceRemoved)
        XCTAssertNil(outcome.cleanupRetry)
        XCTAssertEqual(outcome.manifest, fixture.manifest)
        XCTAssertEqual(outcome.referenceURL, fixture.referenceURL)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        let calls = await migration.recordedCalls()
        let call = try XCTUnwrap(calls.first)
        XCTAssertEqual(calls.count, 1)
        XCTAssertEqual(call.manifest, fixture.manifest)
        XCTAssertEqual(call.activationPlan, outcome.activationPlan)
        XCTAssertEqual(call.runID, runID)
        XCTAssertEqual(call.confirmation, preparation.confirmationText)
        XCTAssertEqual(call.hermes.hermesExecutable.lastPathComponent, "hermes-local-serve")
        XCTAssertEqual(
            call.connector.nodeRuntimeRoot,
            outcome.activationPlan.component(.nodeRuntime)?.root
        )
    }

    func testBootstrapExecutorRoutesActiveInstallationToComponentUpgrade() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let migration = RecordingComponentBootstrapMigration()
        let executor = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: FixtureComponentDownloader()),
            migration: migration
        )
        let preparation = try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: "a0000000-0000-4000-8000-000000000009",
            installation: .active(
                releaseVersion: "0.4.0",
                releaseLayout: .componentStore,
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 1
            ),
            healthProbe: executableProbe
        )
        XCTAssertEqual(preparation.intent, .upgrade(fromReleaseVersion: "0.4.0"))

        _ = try await executor.commit(
            preparation,
            configuration: try fixture.commitConfiguration(),
            legacy: fixture.legacySnapshot,
            confirmation: preparation.confirmationText
        )
        let upgradeCount = await migration.recordedUpgradeCount()
        XCTAssertEqual(upgradeCount, 1)
    }

    func testBootstrapExecutorRejectsPreparationIssuedByAnotherSession() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let first = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: FixtureComponentDownloader()),
            migration: RecordingComponentBootstrapMigration()
        )
        let second = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: FixtureComponentDownloader()),
            migration: RecordingComponentBootstrapMigration()
        )
        let firstPreparation = try await first.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: "b0000000-0000-4000-8000-000000000001",
            healthProbe: executableProbe
        )
        let secondPreparation = try await second.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: "b0000000-0000-4000-8000-000000000002",
            healthProbe: executableProbe
        )

        await XCTAssertThrowsErrorAsync(try await first.commit(
            secondPreparation,
            configuration: try fixture.commitConfiguration(),
            legacy: fixture.legacySnapshot,
            confirmation: secondPreparation.confirmationText
        )) { error in
            XCTAssertEqual(
                error as? DesktopComponentBootstrapExecutorError,
                .preparationMismatch
            )
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        try await first.cancel(firstPreparation)
        try await second.cancel(secondPreparation)
    }

    func testBootstrapExecutorCancelRemovesPrivatePreparationWithoutMigration() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let migration = RecordingComponentBootstrapMigration()
        let executor = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: FixtureComponentDownloader()),
            migration: migration
        )
        let runID = "c0000000-0000-4000-8000-000000000001"
        let preparation = try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )

        try await executor.cancel(preparation)

        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        let calls = await migration.recordedCalls()
        XCTAssertEqual(calls.count, 0)
    }

    func testBootstrapExecutorCanDiscardTransportInterruptionBeforePreparationReturns()
        async throws
    {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let runID = "c0000000-0000-4000-8000-000000000002"
        let executor = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(
                downloader: FixtureComponentDownloader(failOnceFor: .nodeRuntime)
            ),
            migration: RecordingComponentBootstrapMigration()
        )

        await XCTAssertThrowsErrorAsync(try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )) { error in
            XCTAssertEqual(error as? DesktopComponentDownloadError, .transportFailed)
        }
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))

        try await executor.discardInterruptedPreparation(
            workspaceRoot: fixture.workspace,
            runID: runID
        )

        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
    }

    func testBootstrapExecutorCanDiscardPendingCleanupByRunID() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let underlying = try fixture.installer(downloader: FixtureComponentDownloader())
        let installer = FailOncePreparedDiscardComponentInstaller(underlying: underlying)
        let executor = DesktopComponentBootstrapExecutor(
            installer: installer,
            migration: RecordingComponentBootstrapMigration()
        )
        let runID = "c0000000-0000-4000-8000-000000000003"
        let preparation = try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )

        await XCTAssertThrowsErrorAsync(try await executor.cancel(preparation)) { error in
            XCTAssertEqual(
                error as? DesktopComponentBootstrapExecutorError,
                .cleanupFailed
            )
        }
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))

        try await executor.discardInterruptedPreparation(
            workspaceRoot: fixture.workspace,
            runID: runID
        )

        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
    }

    func testBootstrapExecutorMigrationFailureCleansWorkspaceButLeavesInactiveReference()
        async throws
    {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let migration = RecordingComponentBootstrapMigration(failure: .failed)
        let executor = DesktopComponentBootstrapExecutor(
            installer: try fixture.installer(downloader: FixtureComponentDownloader()),
            migration: migration
        )
        let runID = "d0000000-0000-4000-8000-000000000001"
        let preparation = try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )

        await XCTAssertThrowsErrorAsync(try await executor.commit(
            preparation,
            configuration: try fixture.commitConfiguration(),
            legacy: fixture.legacySnapshot,
            confirmation: preparation.confirmationText
        )) { error in
            XCTAssertEqual(error as? ComponentBootstrapMigrationFixtureError, .failed)
        }

        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.referenceURL.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        for artifact in fixture.manifest.components {
            XCTAssertTrue(FileManager.default.fileExists(
                atPath: fixture.managedContent(artifact).path
            ))
        }
        let calls = await migration.recordedCalls()
        XCTAssertEqual(calls.count, 1)
    }

    func testBootstrapExecutorReportsCommittedMigrationWhenCleanupNeedsRetry() async throws {
        let fixture = try ComponentInstallFixture()
        defer { fixture.remove() }
        let underlying = try fixture.installer(downloader: FixtureComponentDownloader())
        let installer = FailOnceInstalledDiscardComponentInstaller(underlying: underlying)
        let executor = DesktopComponentBootstrapExecutor(
            installer: installer,
            migration: RecordingComponentBootstrapMigration()
        )
        let runID = "e0000000-0000-4000-8000-000000000001"
        let preparation = try await executor.prepare(
            trustedPreflight: try fixture.trustedPreflight(),
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: executableProbe
        )

        let outcome = try await executor.commit(
            preparation,
            configuration: try fixture.commitConfiguration(),
            legacy: fixture.legacySnapshot,
            confirmation: preparation.confirmationText
        )

        XCTAssertFalse(outcome.temporaryWorkspaceRemoved)
        XCTAssertEqual(outcome.cleanupRetry, preparation)
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        try await executor.retryCleanup(preparation)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ errorHandler: (Error) -> Void
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        errorHandler(error)
    }
}

private enum FixtureComponentInstallError: Error, Equatable {
    case extractionFailed
}

private actor FixtureComponentDownloader: DesktopComponentReleaseDownloading {
    private var requested: [DesktopManagedComponentKind] = []
    private let failOnceKind: DesktopManagedComponentKind?
    private var didFail = false

    init(failOnceFor kind: DesktopManagedComponentKind? = nil) {
        failOnceKind = kind
    }

    func download(
        _ component: DesktopComponentReleaseArtifactV2,
        into downloadRoot: URL
    ) async throws -> URL {
        requested.append(component.kind)
        if component.kind == failOnceKind, !didFail {
            didFail = true
            let partial = downloadRoot.appendingPathComponent(".\(component.fileName).partial")
            try Data("p".utf8).write(to: partial)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o600], ofItemAtPath: partial.path
            )
            throw DesktopComponentDownloadError.transportFailed
        }
        let destination = downloadRoot.appendingPathComponent(component.fileName)
        try Data(component.kind.rawValue.utf8).write(to: destination)
        return destination
    }

    func requestedKinds() -> [DesktopManagedComponentKind] { requested }
}

private final class FixtureComponentExtractor: DesktopComponentArchiveExtracting {
    private let sources: [DesktopManagedComponentKind: URL]
    private let failedKind: DesktopManagedComponentKind?

    init(
        sources: [DesktopManagedComponentKind: URL],
        failedKind: DesktopManagedComponentKind?
    ) {
        self.sources = sources
        self.failedKind = failedKind
    }

    func extractComponent(
        archive: URL,
        metadata: DesktopComponentReleaseArtifactV2,
        into destinationRoot: URL,
        runID: String
    ) throws -> URL {
        guard metadata.kind != failedKind else {
            throw FixtureComponentInstallError.extractionFailed
        }
        let source = sources[metadata.kind]!
        let destination = destinationRoot.appendingPathComponent(
            "\(runID.lowercased())-\(metadata.kind.rawValue)",
            isDirectory: true
        )
        try FileManager.default.copyItem(at: source, to: destination)
        return destination
    }
}

private final class ComponentInstallFixture {
    let base: URL
    let workspace: URL
    let store: URL
    let manifest: DesktopComponentReleaseManifestV2
    let verifiedManifest: VerifiedDesktopComponentReleaseManifestV2
    private let sources: [DesktopManagedComponentKind: URL]

    var referenceURL: URL {
        store.appendingPathComponent("references/\(manifest.releaseVersion).json")
    }

    init() throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(
            "hermes-component-installer-\(UUID().uuidString)", isDirectory: true
        )
        workspace = base.appendingPathComponent("workspace", isDirectory: true)
        store = base.appendingPathComponent("managed", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: false)
        let paths: [DesktopManagedComponentKind: String] = [
            .nodeRuntime: "bin/node",
            .connector: "bin/hermes-connector",
        ]
        var sourceByKind: [DesktopManagedComponentKind: URL] = [:]
        var hashes: [DesktopManagedComponentKind: String] = [:]
        for kind in [DesktopManagedComponentKind.nodeRuntime, .connector] {
            let source = base.appendingPathComponent("source-\(kind.rawValue)", isDirectory: true)
            let entrypoint = source.appendingPathComponent(paths[kind]!)
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o700], ofItemAtPath: entrypoint.path
            )
            sourceByKind[kind] = source
            hashes[kind] = try DesktopManagedComponentContentHasher()
                .identify(directory: source).sha256
        }
        sources = sourceByKind
        let node = Self.artifact(
            kind: .nodeRuntime,
            contentSHA256: hashes[.nodeRuntime]!,
            dependencies: []
        )
        let connector = Self.artifact(
            kind: .connector,
            contentSHA256: hashes[.connector]!,
            dependencies: [.init(kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!)]
        )
        let builtManifest = DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.1",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [connector, node]
        )
        manifest = builtManifest
        verifiedManifest = try Self.verify(builtManifest)
    }

    func installer(
        downloader: FixtureComponentDownloader,
        failExtractionFor failedKind: DesktopManagedComponentKind? = nil
    ) throws -> DesktopComponentReleaseInstaller {
        try DesktopComponentReleaseInstaller(
            storeRoot: store,
            currentUserID: Darwin.getuid(),
            downloader: downloader,
            extractor: FixtureComponentExtractor(sources: sources, failedKind: failedKind)
        )
    }

    func artifact(_ kind: DesktopManagedComponentKind) -> DesktopComponentReleaseArtifactV2 {
        manifest.components.first(where: { $0.kind == kind })!
    }

    func managedContent(_ artifact: DesktopComponentReleaseArtifactV2) -> URL {
        store.appendingPathComponent(
            "components/\(artifact.kind.rawValue)/\(artifact.contentSHA256)/content",
            isDirectory: true
        )
    }

    func trustedPreflight() throws -> DesktopTrustedComponentPreflight {
        let requirements = try manifest.preflightRequirements
        let plan = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: requirements,
            candidates: []
        )
        let result = DesktopComponentReleasePreflightResult(
            manifest: manifest,
            plan: plan,
            externalEnvironment: DesktopExternalEnvironmentScan(observations: [])
        )
        return DesktopTrustedComponentPreflight(
            result: result,
            verifiedManifest: verifiedManifest
        )
    }

    func commitConfiguration() throws -> DesktopManagedBootstrapCommitConfiguration {
        let layout = try DesktopManagedInstallLayout(
            root: store,
            launchAgentsRoot: base.appendingPathComponent("agents", isDirectory: true)
        )
        return try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: base.appendingPathComponent("hermes-home", isDirectory: true),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
    }

    var legacySnapshot: LegacyConnectorSnapshot {
        LegacyConnectorSnapshot(
            isInstalled: false,
            isRunning: false,
            config: LegacyConnectorConfig(gatewayURL: nil),
            recentLogs: [],
            installDirectory: base.appendingPathComponent("legacy", isDirectory: true),
            launchAgentURL: base.appendingPathComponent("legacy.plist")
        )
    }

    func makeArtifact(
        kind: DesktopManagedComponentKind,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        Self.artifact(
            kind: kind,
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
    }

    func manifest(
        with components: [DesktopComponentReleaseArtifactV2]
    ) -> DesktopComponentReleaseManifestV2 {
        manifest(releaseVersion: manifest.releaseVersion, components: components)
    }

    func manifest(
        releaseVersion: String,
        components: [DesktopComponentReleaseArtifactV2]
    ) -> DesktopComponentReleaseManifestV2 {
        DesktopComponentReleaseManifestV2(
            releaseVersion: releaseVersion,
            channel: manifest.channel,
            architecture: manifest.architecture,
            minimumMacOS: manifest.minimumMacOS,
            createdAt: manifest.createdAt,
            expiresAt: manifest.expiresAt,
            components: components
        )
    }

    func verify(
        _ manifest: DesktopComponentReleaseManifestV2
    ) throws -> VerifiedDesktopComponentReleaseManifestV2 {
        try Self.verify(manifest)
    }

    func remove() {
        try? FileManager.default.removeItem(at: base)
    }

    private static func artifact(
        kind: DesktopManagedComponentKind,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        let paths: [DesktopManagedComponentKind: String] = [
            .nodeRuntime: "bin/node",
            .connector: "bin/hermes-connector",
        ]
        let fileName = "Hermes-Component-\(kind.rawValue)-1.2.3-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            installPhase: .bootstrap,
            requiredForBootstrap: true,
            reuseContract: .exactContent,
            fileName: fileName,
            entrypoint: paths[kind]!,
            downloadURL: "https://downloads.example/desktop/components/\(fileName)",
            sizeBytes: 1,
            sha256: SHA256.hash(data: Data(kind.rawValue.utf8))
                .map { String(format: "%02x", $0) }.joined(),
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
    }

    private static func verify(
        _ manifest: DesktopComponentReleaseManifestV2
    ) throws -> VerifiedDesktopComponentReleaseManifestV2 {
        let signer = Curve25519.Signing.PrivateKey()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let payload = try encoder.encode(manifest)
        let signature = try signer.signature(for: payload)
        let envelope = try JSONSerialization.data(withJSONObject: [
            "algorithm": "Ed25519",
            "keyId": "test-key",
            "payload": base64URL(payload),
            "signature": base64URL(signature),
        ], options: [.sortedKeys])
        let now = ISO8601DateFormatter().date(from: "2026-09-10T00:00:00Z")!
        let verifier = try DesktopComponentReleaseManifestV2Verifier(
            expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal",
            expectedArchitecture: "arm64",
            currentMacOS: OperatingSystemVersion(
                majorVersion: 14, minorVersion: 8, patchVersion: 0
            ),
            signingKeys: ["test-key": signer.publicKey.rawRepresentation],
            now: { now }
        )
        return try verifier.verifyForInstallation(envelope)
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private enum ComponentBootstrapMigrationFixtureError: Error, Equatable {
    case failed
}

private struct RecordedComponentBootstrapMigration: Sendable {
    let manifest: DesktopComponentReleaseManifestV2
    let activationPlan: DesktopComponentReleaseActivationPlan
    let hermes: DesktopHermesServerLaunchAgent
    let connector: DesktopAccountConnectorLaunchAgent
    let legacy: LegacyConnectorSnapshot
    let runID: String
    let confirmation: String
}

private actor RecordingComponentBootstrapMigration: DesktopComponentBootstrapMigrating {
    private let failure: ComponentBootstrapMigrationFixtureError?
    private var calls: [RecordedComponentBootstrapMigration] = []
    private var upgrades = 0

    init(failure: ComponentBootstrapMigrationFixtureError? = nil) {
        self.failure = failure
    }

    func migrateComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        legacy: LegacyConnectorSnapshot,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        calls.append(RecordedComponentBootstrapMigration(
            manifest: manifest,
            activationPlan: activationPlan,
            hermes: hermesLaunchAgentConfiguration,
            connector: launchAgentConfiguration,
            legacy: legacy,
            runID: runID,
            confirmation: confirmation
        ))
        if let failure { throw failure }
        return DesktopMigrationOutcome(
            runID: runID,
            releaseVersion: manifest.releaseVersion,
            bindingID: "binding-1",
            bindingGeneration: 1
        )
    }

    func recordedCalls() -> [RecordedComponentBootstrapMigration] { calls }

    func upgradeComponentRelease(
        manifest: DesktopComponentReleaseManifestV2,
        activationPlan: DesktopComponentReleaseActivationPlan,
        hermesLaunchAgentConfiguration: DesktopHermesServerLaunchAgent,
        launchAgentConfiguration: DesktopAccountConnectorLaunchAgent,
        runID: String,
        confirmation: String
    ) async throws -> DesktopMigrationOutcome {
        upgrades += 1
        if let failure { throw failure }
        return DesktopMigrationOutcome(
            runID: runID,
            releaseVersion: manifest.releaseVersion,
            bindingID: "binding-1",
            bindingGeneration: 1
        )
    }

    func recordedUpgradeCount() -> Int { upgrades }
}

private final class FailOnceInstalledDiscardComponentInstaller:
    DesktopComponentReleaseInstalling, @unchecked Sendable
{
    private let underlying: DesktopComponentReleaseInstaller
    private let lock = NSLock()
    private var shouldFailInstalledDiscard = true

    init(underlying: DesktopComponentReleaseInstaller) {
        self.underlying = underlying
    }

    func prepare(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        workspaceRoot: URL,
        runID: String,
        healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    ) async throws -> DesktopPreparedComponentRelease {
        try await underlying.prepare(
            verifiedManifest: verifiedManifest,
            workspaceRoot: workspaceRoot,
            runID: runID,
            healthProbe: healthProbe
        )
    }

    func commit(
        _ preparation: DesktopPreparedComponentRelease,
        healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    ) throws -> DesktopInstalledComponentRelease {
        try underlying.commit(preparation, healthProbe: healthProbe)
    }

    func discard(_ preparation: DesktopPreparedComponentRelease) throws {
        try underlying.discard(preparation)
    }

    func discard(_ release: DesktopInstalledComponentRelease) throws {
        let fail: Bool = lock.withLock {
            guard shouldFailInstalledDiscard else { return false }
            shouldFailInstalledDiscard = false
            return true
        }
        if fail { throw DesktopComponentReleaseInstallError.cleanupFailed }
        try underlying.discard(release)
    }

    func discardInterruptedInstall(workspaceRoot: URL, runID: String) throws {
        try underlying.discardInterruptedInstall(workspaceRoot: workspaceRoot, runID: runID)
    }
}

private final class FailOncePreparedDiscardComponentInstaller:
    DesktopComponentReleaseInstalling, @unchecked Sendable
{
    private let underlying: DesktopComponentReleaseInstaller
    private let lock = NSLock()
    private var shouldFailPreparedDiscard = true

    init(underlying: DesktopComponentReleaseInstaller) {
        self.underlying = underlying
    }

    func prepare(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        workspaceRoot: URL,
        runID: String,
        healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    ) async throws -> DesktopPreparedComponentRelease {
        try await underlying.prepare(
            verifiedManifest: verifiedManifest,
            workspaceRoot: workspaceRoot,
            runID: runID,
            healthProbe: healthProbe
        )
    }

    func commit(
        _ preparation: DesktopPreparedComponentRelease,
        healthProbe: DesktopComponentReleaseInstaller.HealthProbe
    ) throws -> DesktopInstalledComponentRelease {
        try underlying.commit(preparation, healthProbe: healthProbe)
    }

    func discard(_ preparation: DesktopPreparedComponentRelease) throws {
        let fail: Bool = lock.withLock {
            guard shouldFailPreparedDiscard else { return false }
            shouldFailPreparedDiscard = false
            return true
        }
        if fail { throw DesktopComponentReleaseInstallError.cleanupFailed }
        try underlying.discard(preparation)
    }

    func discard(_ release: DesktopInstalledComponentRelease) throws {
        try underlying.discard(release)
    }

    func discardInterruptedInstall(workspaceRoot: URL, runID: String) throws {
        try underlying.discardInterruptedInstall(workspaceRoot: workspaceRoot, runID: runID)
    }
}
