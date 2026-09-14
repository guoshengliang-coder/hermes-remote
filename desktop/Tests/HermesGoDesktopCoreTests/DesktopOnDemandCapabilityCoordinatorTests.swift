import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopOnDemandCapabilityCoordinatorTests: XCTestCase {
    func testSignedCapabilityKindMapsToTriggerThenPlansActivatesAndRetriesOnce() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        try await fixture.installBase()
        let installed = DesktopInstalledOnDemandCapability(
            releaseVersion: fixture.manifest.releaseVersion,
            trigger: "browser.use",
            components: [DesktopResolvedOnDemandComponent(
                kind: .browserAutomation,
                location: .external(executable: fixture.base.appendingPathComponent("browser"))
            )],
            referenceURLs: []
        )
        let installer = CapabilityInstallerMock(result: installed)
        let planner = RecordingActivationPlanner(
            try DesktopComponentReleaseActivationPlanner(
                storeRoot: fixture.store, currentUserID: Darwin.getuid()
            )
        )
        let activator = RuntimeActivatorMock()
        let retries = CoordinatorCounter()
        let launchAgent = fixture.base.appendingPathComponent("hermes.plist")
        let coordinator = try DesktopOnDemandCapabilityCoordinator(
            verifiedManifest: fixture.verifiedManifest,
            installer: installer,
            activationPlanner: planner,
            activator: activator,
            workspaceRoot: fixture.workspace,
            hermesLaunchAgentURL: launchAgent,
            runID: { "21000000-0000-4000-8000-000000000021" }
        )

        let value: String = try await coordinator.installActivateAndRetry(
            capability: .browser,
            healthProbe: fixture.executableProbe
        ) {
            retries.increment()
            return "retried"
        }
        let triggers = await installer.triggers()
        let runIDs = await installer.runIDs()
        let activationCount = await activator.activationCount()
        let launchAgentURLs = await activator.launchAgentURLs()

        XCTAssertEqual(value, "retried")
        XCTAssertEqual(triggers, ["browser.use"])
        XCTAssertEqual(runIDs, ["21000000-0000-4000-8000-000000000021"])
        XCTAssertEqual(planner.planCount(), 1)
        XCTAssertEqual(activationCount, 1)
        XCTAssertEqual(launchAgentURLs, [launchAgent])
        XCTAssertEqual(retries.value(), 1)
    }

    func testCapabilityAbsentFromSignedManifestIsInert() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        let installer = CapabilityInstallerMock(result: DesktopInstalledOnDemandCapability(
            releaseVersion: fixture.manifest.releaseVersion,
            trigger: "unused",
            components: [],
            referenceURLs: []
        ))
        let planner = RecordingActivationPlanner(failure: .invalidManifest)
        let activator = RuntimeActivatorMock()
        let coordinator = try DesktopOnDemandCapabilityCoordinator(
            verifiedManifest: fixture.verifiedManifest,
            installer: installer,
            activationPlanner: planner,
            activator: activator,
            workspaceRoot: fixture.workspace,
            hermesLaunchAgentURL: fixture.base.appendingPathComponent("hermes.plist")
        )

        await assertCoordinatorAsyncError(try await coordinator.installActivateAndRetry(
            capability: .speech,
            healthProbe: fixture.executableProbe,
            retry: {}
        )) { error in
            XCTAssertEqual(
                error as? DesktopOnDemandCapabilityCoordinatorError,
                .unsupportedCapability(.speech)
            )
        }
        let installCount = await installer.installCount()
        let activationCount = await activator.activationCount()
        XCTAssertEqual(installCount, 0)
        XCTAssertEqual(planner.planCount(), 0)
        XCTAssertEqual(activationCount, 0)
    }

    func testInstallFailureStopsBeforePlanActivationAndRetry() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        let installer = CapabilityInstallerMock(failure: .expected)
        let planner = RecordingActivationPlanner(failure: .invalidManifest)
        let activator = RuntimeActivatorMock()
        let retries = CoordinatorCounter()
        let coordinator = try DesktopOnDemandCapabilityCoordinator(
            verifiedManifest: fixture.verifiedManifest,
            installer: installer,
            activationPlanner: planner,
            activator: activator,
            workspaceRoot: fixture.workspace,
            hermesLaunchAgentURL: fixture.base.appendingPathComponent("hermes.plist")
        )

        await assertCoordinatorAsyncError(try await coordinator.installActivateAndRetry(
            capability: .browser,
            healthProbe: fixture.executableProbe
        ) {
            retries.increment()
        }) { error in
            XCTAssertEqual(error as? CoordinatorFailure, .expected)
        }
        let installCount = await installer.installCount()
        let activationCount = await activator.activationCount()
        XCTAssertEqual(installCount, 1)
        XCTAssertEqual(planner.planCount(), 0)
        XCTAssertEqual(activationCount, 0)
        XCTAssertEqual(retries.value(), 0)
    }

    func testConcurrentCapabilityRequestIsRejectedWithoutSecondInstall() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        try await fixture.installBase()
        let installed = DesktopInstalledOnDemandCapability(
            releaseVersion: fixture.manifest.releaseVersion,
            trigger: "browser.use",
            components: [],
            referenceURLs: []
        )
        let installer = SuspendingCapabilityInstaller(result: installed)
        let planner = RecordingActivationPlanner(
            try DesktopComponentReleaseActivationPlanner(
                storeRoot: fixture.store, currentUserID: Darwin.getuid()
            )
        )
        let activator = RuntimeActivatorMock()
        let coordinator = try DesktopOnDemandCapabilityCoordinator(
            verifiedManifest: fixture.verifiedManifest,
            installer: installer,
            activationPlanner: planner,
            activator: activator,
            workspaceRoot: fixture.workspace,
            hermesLaunchAgentURL: fixture.base.appendingPathComponent("hermes.plist")
        )
        let first = Task {
            try await coordinator.installActivateAndRetry(
                capability: .browser,
                healthProbe: fixture.executableProbe,
                retry: { "first" }
            )
        }
        await installer.waitUntilStarted()

        await assertCoordinatorAsyncError(try await coordinator.installActivateAndRetry(
            capability: .browser,
            healthProbe: fixture.executableProbe,
            retry: { "second" }
        )) { error in
            XCTAssertEqual(
                error as? DesktopOnDemandCapabilityCoordinatorError,
                .operationInProgress
            )
        }
        await installer.release()
        let firstValue = try await first.value
        let installCount = await installer.installCount()
        let activationCount = await activator.activationCount()
        XCTAssertEqual(firstValue, "first")
        XCTAssertEqual(installCount, 1)
        XCTAssertEqual(activationCount, 1)
    }
}

private func assertCoordinatorAsyncError<T>(
    _ expression: @autoclosure () async throws -> T,
    _ handler: (Error) -> Void
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        handler(error)
    }
}

private enum CoordinatorFailure: Error, Equatable { case expected }

private actor CapabilityInstallerMock: DesktopOnDemandCapabilityInstalling {
    private let result: DesktopInstalledOnDemandCapability?
    private let failure: CoordinatorFailure?
    private var recordedTriggers: [String] = []
    private var recordedRunIDs: [String] = []

    init(
        result: DesktopInstalledOnDemandCapability? = nil,
        failure: CoordinatorFailure? = nil
    ) {
        self.result = result
        self.failure = failure
    }

    func install(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        trigger: String,
        workspaceRoot: URL,
        runID: String,
        healthProbe: DesktopOnDemandComponentInstaller.HealthProbe
    ) async throws -> DesktopInstalledOnDemandCapability {
        recordedTriggers.append(trigger)
        recordedRunIDs.append(runID)
        if let failure { throw failure }
        return result!
    }

    func triggers() -> [String] { recordedTriggers }
    func runIDs() -> [String] { recordedRunIDs }
    func installCount() -> Int { recordedTriggers.count }
}

private actor SuspendingCapabilityInstaller: DesktopOnDemandCapabilityInstalling {
    private let result: DesktopInstalledOnDemandCapability
    private var installs = 0
    private var started = false
    private var startedWaiter: CheckedContinuation<Void, Never>?
    private var releaseWaiter: CheckedContinuation<Void, Never>?

    init(result: DesktopInstalledOnDemandCapability) {
        self.result = result
    }

    func install(
        verifiedManifest: VerifiedDesktopComponentReleaseManifestV2,
        trigger: String,
        workspaceRoot: URL,
        runID: String,
        healthProbe: DesktopOnDemandComponentInstaller.HealthProbe
    ) async throws -> DesktopInstalledOnDemandCapability {
        installs += 1
        started = true
        startedWaiter?.resume()
        startedWaiter = nil
        await withCheckedContinuation { continuation in
            releaseWaiter = continuation
        }
        return result
    }

    func waitUntilStarted() async {
        guard !started else { return }
        await withCheckedContinuation { continuation in
            startedWaiter = continuation
        }
    }

    func release() {
        releaseWaiter?.resume()
        releaseWaiter = nil
    }

    func installCount() -> Int { installs }
}

private final class RecordingActivationPlanner:
    DesktopComponentReleaseActivationPlanning, @unchecked Sendable
{
    private let lock = NSLock()
    private let planner: DesktopComponentReleaseActivationPlanner?
    private let failure: DesktopComponentReleaseActivationError?
    private var plans = 0

    init(_ planner: DesktopComponentReleaseActivationPlanner) {
        self.planner = planner
        failure = nil
    }

    init(failure: DesktopComponentReleaseActivationError) {
        planner = nil
        self.failure = failure
    }

    func plan(
        manifest: DesktopComponentReleaseManifestV2,
        healthProbe: DesktopComponentReleaseActivationPlanner.HealthProbe
    ) throws -> DesktopComponentReleaseActivationPlan {
        lock.withLock { plans += 1 }
        if let failure { throw failure }
        return try planner!.plan(manifest: manifest, healthProbe: healthProbe)
    }

    func planCount() -> Int { lock.withLock { plans } }
}

private actor RuntimeActivatorMock: DesktopOnDemandRuntimeActivating {
    private var activations = 0
    private var launchAgents: [URL] = []

    func activateAndRetry<T: Sendable>(
        installed: DesktopInstalledOnDemandCapability,
        hermesLaunchAgentURL: URL,
        activationPlan: DesktopComponentReleaseActivationPlan,
        componentHealthProbe: DesktopOnDemandComponentInstaller.HealthProbe,
        retry: @Sendable () async throws -> T
    ) async throws -> T {
        activations += 1
        launchAgents.append(hermesLaunchAgentURL)
        return try await retry()
    }

    func activationCount() -> Int { activations }
    func launchAgentURLs() -> [URL] { launchAgents }
}

private final class CoordinatorCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.withLock { count += 1 } }
    func value() -> Int { lock.withLock { count } }
}
