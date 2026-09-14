import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopOnDemandRuntimeActivatorTests: XCTestCase {
    func testManagedInstallerReplacesAndRestoresExactHermesLaunchAgent() throws {
        let fixture = try RuntimeActivationFixture()
        defer { fixture.cleanup() }
        let installer = DesktopManagedInstaller(layout: fixture.layout)
        let originalConfiguration = try fixture.configuration.componentLaunchAgents(
            for: fixture.plan
        ).hermes
        let plist = try installer.writeHermesLaunchAgent(
            originalConfiguration,
            activationPlan: fixture.plan
        )
        let originalData = try Data(contentsOf: plist)
        let browser = fixture.root.appendingPathComponent("browser")
        try Data("browser".utf8).write(to: browser)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: browser.path)
        let replacementConfiguration = try fixture.configuration.componentLaunchAgents(
            for: fixture.plan,
            optionalRuntime: DesktopOptionalComponentRuntimeEnvironment(browserExecutable: browser)
        ).hermes

        XCTAssertThrowsError(try installer.writeHermesLaunchAgent(
            replacementConfiguration,
            activationPlan: fixture.plan
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .invalidInput)
        }
        let component = DesktopResolvedOnDemandComponent(
            kind: .browserAutomation,
            location: .external(executable: browser)
        )
        let installed = DesktopInstalledOnDemandCapability(
            releaseVersion: fixture.plan.releaseVersion,
            trigger: "browser.open",
            components: [component],
            referenceURLs: []
        )
        let preparer = DesktopOnDemandRuntimePreparer(
            writer: try DesktopOptionalComponentRuntimeWriter(
                storeRoot: fixture.store,
                projectionRoot: fixture.root.appendingPathComponent("runtime-projections"),
                currentUserID: getuid()
            ),
            installer: installer,
            configuration: fixture.configuration
        )
        var tampered = try XCTUnwrap(PropertyListSerialization.propertyList(
            from: originalData, options: [], format: nil
        ) as? [String: Any])
        var tamperedEnvironment = try XCTUnwrap(
            tampered["EnvironmentVariables"] as? [String: String]
        )
        tamperedEnvironment["UNEXPECTED_ENVIRONMENT"] = "value"
        tampered["EnvironmentVariables"] = tamperedEnvironment
        try PropertyListSerialization.data(
            fromPropertyList: tampered, format: .xml, options: 0
        ).write(to: plist)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: plist.path)
        XCTAssertThrowsError(try preparer.prepare(
            installed: installed,
            activeComponents: [component],
            activationPlan: fixture.plan
        )) { error in
            XCTAssertEqual(error as? DesktopManagedInstallError, .unsafeFilesystemObject)
        }
        try originalData.write(to: plist)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: plist.path)

        let prepared = try preparer.prepare(
            installed: installed,
            activeComponents: [component],
            activationPlan: fixture.plan
        )
        let updated = try XCTUnwrap(try propertyListEnvironment(at: plist))
        XCTAssertEqual(updated["AGENT_BROWSER_EXECUTABLE_PATH"], browser.path)

        try preparer.rollback(prepared)
        XCTAssertEqual(try Data(contentsOf: plist), originalData)
    }

    func testHealthyActivationRestartsHermesAndRetriesCapabilityExactlyOnce() async throws {
        let fixture = try RuntimeActivationFixture()
        defer { fixture.cleanup() }
        let preparer = RuntimePreparerMock(fixture.prepared)
        let service = HermesServiceMock()
        let readiness = RuntimeReadinessMock(results: [true])
        let retries = LockedCounter()
        let activator = try DesktopOnDemandRuntimeActivator(
            journal: fixture.journal,
            preparer: preparer,
            service: service,
            readiness: readiness,
            maximumReadinessAttempts: 1,
            readinessDelayNanoseconds: 0
        )

        let value: String = try await activator.activateAndRetry(
            installed: fixture.installed,
            activeComponents: fixture.installed.components,
            activationPlan: fixture.plan
        ) {
            retries.increment()
            return "retried"
        }

        XCTAssertEqual(value, "retried")
        XCTAssertEqual(retries.value(), 1)
        XCTAssertEqual(preparer.prepareCount(), 1)
        XCTAssertEqual(preparer.rollbackCount(), 0)
        XCTAssertEqual(service.mutations(), ["stop", "start"])
        XCTAssertEqual(readiness.waitCount(), 1)
    }

    func testReadinessFailureRestoresPlistAndProvesOldHermesHealthy() async throws {
        let fixture = try RuntimeActivationFixture()
        defer { fixture.cleanup() }
        let preparer = RuntimePreparerMock(fixture.prepared)
        let service = HermesServiceMock()
        let readiness = RuntimeReadinessMock(results: [false, true])
        let retries = LockedCounter()
        let activator = try DesktopOnDemandRuntimeActivator(
            journal: fixture.journal,
            preparer: preparer,
            service: service,
            readiness: readiness,
            maximumReadinessAttempts: 1,
            readinessDelayNanoseconds: 0
        )

        await XCTAssertThrowsErrorAsync(try await activator.activateAndRetry(
            installed: fixture.installed,
            activeComponents: fixture.installed.components,
            activationPlan: fixture.plan
        ) {
            retries.increment()
        }) { error in
            XCTAssertEqual(error as? DesktopOnDemandRuntimeActivationError, .readinessTimedOut)
        }

        XCTAssertEqual(retries.value(), 0)
        XCTAssertEqual(preparer.rollbackCount(), 1)
        XCTAssertEqual(service.mutations(), ["stop", "start", "stop", "start"])
        XCTAssertEqual(readiness.waitCount(), 2)
    }

    func testCapabilityRetryFailureIsReturnedWithoutSecondRetryOrRuntimeRollback() async throws {
        let fixture = try RuntimeActivationFixture()
        defer { fixture.cleanup() }
        let preparer = RuntimePreparerMock(fixture.prepared)
        let service = HermesServiceMock()
        let retries = LockedCounter()
        let activator = try DesktopOnDemandRuntimeActivator(
            journal: fixture.journal,
            preparer: preparer,
            service: service,
            readiness: RuntimeReadinessMock(results: [true]),
            maximumReadinessAttempts: 1,
            readinessDelayNanoseconds: 0
        )

        await XCTAssertThrowsErrorAsync(try await activator.activateAndRetry(
            installed: fixture.installed,
            activeComponents: fixture.installed.components,
            activationPlan: fixture.plan
        ) {
            retries.increment()
            throw RetryFailure.expected
        }) { error in
            XCTAssertEqual(error as? RetryFailure, .expected)
        }

        XCTAssertEqual(retries.value(), 1)
        XCTAssertEqual(preparer.rollbackCount(), 0)
        XCTAssertEqual(service.mutations(), ["stop", "start"])
    }

    func testWrongCommittedReleaseStopsBeforePreparationOrServiceMutation() async throws {
        let fixture = try RuntimeActivationFixture(journalRelease: "0.4.1")
        defer { fixture.cleanup() }
        let preparer = RuntimePreparerMock(fixture.prepared)
        let service = HermesServiceMock()
        let activator = try DesktopOnDemandRuntimeActivator(
            journal: fixture.journal,
            preparer: preparer,
            service: service,
            readiness: RuntimeReadinessMock(results: [true]),
            maximumReadinessAttempts: 1,
            readinessDelayNanoseconds: 0
        )

        await XCTAssertThrowsErrorAsync(try await activator.activateAndRetry(
            installed: fixture.installed,
            activeComponents: fixture.installed.components,
            activationPlan: fixture.plan,
            retry: {}
        )) { error in
            XCTAssertEqual(error as? DesktopOnDemandRuntimeActivationError, .invalidStartingState)
        }
        XCTAssertEqual(preparer.prepareCount(), 0)
        XCTAssertTrue(service.mutations().isEmpty)
    }

    func testMigrationOperationLeaseBlocksActivationBeforePreparation() async throws {
        let fixture = try RuntimeActivationFixture()
        defer { fixture.cleanup() }
        let heldLease = try fixture.journal.acquireOperationLease()
        let preparer = RuntimePreparerMock(fixture.prepared)
        let service = HermesServiceMock()
        let activator = try DesktopOnDemandRuntimeActivator(
            journal: fixture.journal,
            preparer: preparer,
            service: service,
            readiness: RuntimeReadinessMock(results: [true]),
            maximumReadinessAttempts: 1,
            readinessDelayNanoseconds: 0
        )

        await XCTAssertThrowsErrorAsync(try await activator.activateAndRetry(
            installed: fixture.installed,
            activeComponents: fixture.installed.components,
            activationPlan: fixture.plan,
            retry: {}
        )) { error in
            XCTAssertEqual(error as? DesktopOnDemandRuntimeActivationError, .operationInProgress)
        }
        withExtendedLifetime(heldLease) {}
        XCTAssertEqual(preparer.prepareCount(), 0)
        XCTAssertTrue(service.mutations().isEmpty)
    }

    private func propertyListEnvironment(at url: URL) throws -> [String: String]? {
        let object = try PropertyListSerialization.propertyList(
            from: Data(contentsOf: url), options: [], format: nil
        ) as? [String: Any]
        return object?["EnvironmentVariables"] as? [String: String]
    }
}

private enum RetryFailure: Error, Equatable { case expected }

private final class RuntimePreparerMock: DesktopOnDemandRuntimePreparing, @unchecked Sendable {
    private let lock = NSLock()
    private let prepared: DesktopPreparedOnDemandRuntime
    private var preparations = 0
    private var rollbacks = 0

    init(_ prepared: DesktopPreparedOnDemandRuntime) { self.prepared = prepared }

    func prepare(
        installed: DesktopInstalledOnDemandCapability,
        activeComponents: [DesktopResolvedOnDemandComponent],
        activationPlan: DesktopComponentReleaseActivationPlan
    ) throws -> DesktopPreparedOnDemandRuntime {
        lock.withLock { preparations += 1 }
        return prepared
    }

    func rollback(_ prepared: DesktopPreparedOnDemandRuntime) throws {
        lock.withLock { rollbacks += 1 }
    }

    func prepareCount() -> Int { lock.withLock { preparations } }
    func rollbackCount() -> Int { lock.withLock { rollbacks } }
}

private final class HermesServiceMock: DesktopHermesServiceControlling, @unchecked Sendable {
    private let lock = NSLock()
    private var hermesLoaded = true
    private var recorded: [String] = []

    func inspect() throws -> DesktopLaunchAgentServiceState {
        lock.withLock {
            DesktopLaunchAgentServiceState(
                legacyLoaded: false, accountLoaded: true, hermesLoaded: hermesLoaded
            )
        }
    }

    func stopHermes() throws {
        lock.withLock {
            recorded.append("stop")
            hermesLoaded = false
        }
    }

    func startHermes(plistURL: URL) throws {
        lock.withLock {
            recorded.append("start")
            hermesLoaded = true
        }
    }

    func mutations() -> [String] { lock.withLock { recorded } }
}

private final class RuntimeReadinessMock: DesktopHermesCandidateReadinessChecking, @unchecked Sendable {
    private let lock = NSLock()
    private var results: [Bool]
    private var waits = 0

    init(results: [Bool]) { self.results = results }

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
            waits += 1
            return results.isEmpty ? false : results.removeFirst()
        }
    }

    func waitCount() -> Int { lock.withLock { waits } }
}

private final class LockedCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.withLock { count += 1 } }
    func value() -> Int { lock.withLock { count } }
}

private final class RuntimeActivationFixture {
    let root: URL
    let store: URL
    let layout: DesktopManagedInstallLayout
    let configuration: DesktopManagedBootstrapCommitConfiguration
    let journal: DesktopMigrationJournalStore
    let plan: DesktopComponentReleaseActivationPlan
    let installed: DesktopInstalledOnDemandCapability
    let prepared: DesktopPreparedOnDemandRuntime

    init(journalRelease: String = "0.4.0") throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(
            "runtime-activation-\(UUID().uuidString)", isDirectory: true
        )
        store = root.appendingPathComponent("managed", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        let manifest = try Self.makeBootstrapStore(store: store, sourceRoot: root)
        plan = try DesktopComponentReleaseActivationPlanner(
            storeRoot: store, currentUserID: getuid()
        ).plan(manifest: manifest) { _, _, _ in true }

        let component = DesktopResolvedOnDemandComponent(
            kind: .browserAutomation,
            location: .external(executable: root.appendingPathComponent("browser"))
        )
        installed = DesktopInstalledOnDemandCapability(
            releaseVersion: "0.4.0",
            trigger: "browser.open",
            components: [component],
            referenceURLs: []
        )
        prepared = DesktopPreparedOnDemandRuntime(
            launchAgentURL: root.appendingPathComponent("com.hermesgo.hermes-server.plist"),
            logURL: root.appendingPathComponent("hermes.log"),
            runtimeContract: .serveV1
        )
        layout = try DesktopManagedInstallLayout(
            root: store,
            launchAgentsRoot: root.appendingPathComponent("agents")
        )
        configuration = try DesktopManagedBootstrapCommitConfiguration(
            layout: layout,
            hermesHome: root.appendingPathComponent("hermes-home"),
            accountGatewayURL: URL(string: "https://gateway.example")!,
            runtimeContract: .serveV1
        )
        journal = try DesktopMigrationJournalStore(root: root.appendingPathComponent("journal"))
        try Self.writeActiveJournal(at: root.appendingPathComponent("journal"), release: journalRelease)
    }

    func cleanup() { try? FileManager.default.removeItem(at: root) }

    private static func writeActiveJournal(at root: URL, release: String) throws {
        try FileManager.default.createDirectory(
            at: root, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let value = DesktopMigrationJournal(
            runID: UUID().uuidString.lowercased(),
            state: .accountActive,
            lastKnownGoodMode: .account,
            releaseVersion: release,
            bindingID: UUID().uuidString.lowercased(),
            bindingGeneration: 1,
            updatedAt: "2026-09-14T00:00:00.000Z"
        )
        let data = try JSONEncoder().encode(value)
        let file = root.appendingPathComponent("migration-state.json")
        try data.write(to: file)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }

    private static func makeBootstrapStore(
        store: URL,
        sourceRoot: URL
    ) throws -> DesktopComponentReleaseManifestV2 {
        let writer = try DesktopManagedComponentStoreWriter(root: store, currentUserID: getuid())
        let paths: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "bin/python3", .hermesCore: "bin/hermes",
            .nodeRuntime: "bin/node", .connector: "bin/connector",
        ]
        var hashes: [DesktopManagedComponentKind: String] = [:]
        for kind in [
            DesktopManagedComponentKind.pythonRuntime, .hermesCore, .nodeRuntime, .connector,
        ] {
            let source = sourceRoot.appendingPathComponent("source-\(kind.rawValue)")
            let entrypoint = source.appendingPathComponent(paths[kind]!)
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: entrypoint.path)
            let hash = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
            hashes[kind] = hash
            _ = try writer.commit(
                sourceDirectory: source,
                receipt: DesktopManagedComponentReceipt(
                    kind: kind, version: "1.2.3", architecture: "arm64", contentSHA256: hash
                ),
                runID: UUID().uuidString,
                healthProbe: { _ in true }
            )
        }
        func artifact(
            _ kind: DesktopManagedComponentKind,
            dependencies: [DesktopComponentReleaseDependency]
        ) -> DesktopComponentReleaseArtifactV2 {
            let file = "\(kind.rawValue).tar.gz"
            return DesktopComponentReleaseArtifactV2(
                kind: kind, version: "1.2.3", architecture: "arm64",
                installPhase: .bootstrap, requiredForBootstrap: true,
                reuseContract: .exactContent, fileName: file, entrypoint: paths[kind]!,
                downloadURL: "https://downloads.example/\(file)", sizeBytes: 1,
                sha256: String(repeating: "a", count: 64), contentSHA256: hashes[kind]!,
                dependencies: dependencies
            )
        }
        return DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.0", channel: "internal", architecture: "arm64",
            minimumMacOS: "14.0", createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [
                artifact(.pythonRuntime, dependencies: []),
                artifact(.hermesCore, dependencies: [
                    .init(kind: .pythonRuntime, contentSHA256: hashes[.pythonRuntime]!),
                ]),
                artifact(.nodeRuntime, dependencies: []),
                artifact(.connector, dependencies: [
                    .init(kind: .hermesCore, contentSHA256: hashes[.hermesCore]!),
                    .init(kind: .nodeRuntime, contentSHA256: hashes[.nodeRuntime]!),
                ]),
            ]
        )
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
