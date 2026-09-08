import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopBootstrapTests: XCTestCase {
    func testRunningLegacyConnectorIsPreservedAndCannotBeginManagedInstall() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: true),
            hermesReachable: true,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .existingServicePreserved)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.steps.contains { $0.kind == .preserveExisting })
        XCTAssertTrue(plan.detailChinese.contains("保持它不变"))
    }

    func testStoppedExistingConnectorFailsClosedWithoutOfferingSecondInstance() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: false),
            hermesReachable: false,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("不会覆盖或另起一个实例"))
    }

    func testCleanMacWaitsForSignedReleaseInsteadOfInstallingUnverifiedPayload() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: false,
            managedInstallAvailability: .disabled
        )

        XCTAssertEqual(plan.readiness, .waitingForSignedRelease)
        XCTAssertFalse(plan.canBegin)
        XCTAssertFalse(plan.requiresConfirmation)
        XCTAssertEqual(plan.steps.map(\.kind), [.inspectExisting, .verifySignedRelease])
        XCTAssertTrue(plan.detailChinese.contains("未启用"))
    }

    func testCleanMacFailsClosedWhenGatewayRuntimeContractIsUnavailable() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: false,
            managedInstallAvailability: .serverCapabilityUnavailable
        )

        XCTAssertEqual(plan.readiness, .waitingForSignedRelease)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("Gateway 尚未声明"))
    }

    func testUnmanagedHermesOnTheReservedPortBlocksManagedInstall() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: true,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertEqual(plan.steps.map(\.kind), [.inspectExisting, .preserveExisting])
        XCTAssertTrue(plan.detailChinese.contains("不会覆盖或停止"))
    }

    func testCleanMacPlanListsEveryMutationAndRequiresConfirmation() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: false,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .readyForManagedInstall)
        XCTAssertTrue(plan.canBegin)
        XCTAssertTrue(plan.requiresConfirmation)
        XCTAssertEqual(plan.steps.map(\.kind), [
            .inspectExisting,
            .verifySignedRelease,
            .installHermes,
            .configureLocalProvider,
            .installConnector,
            .bindAccount,
            .enableAutomaticStartup,
            .verifyEndToEnd,
        ])
    }

    func testManagedActiveReleaseCannotBeMistakenForAnUnknownPortOwner() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: true,
            managedInstallAvailability: .ready,
            managedInstallation: .active(
                releaseVersion: "1.2.3",
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 1
            )
        )

        XCTAssertEqual(plan.readiness, .managedInstallActive)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("1.2.3"))
    }

    func testInterruptedManagedReleaseBlocksASecondInstall() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: false,
            managedInstallAvailability: .ready,
            managedInstallation: .interrupted(
                runID: "60000000-0000-4000-8000-000000000006",
                state: .candidateStarting
            )
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("candidate_starting"))
    }

    private func snapshot(installed: Bool, running: Bool) -> LegacyConnectorSnapshot {
        LegacyConnectorSnapshot(
            isInstalled: installed,
            isRunning: running,
            config: LegacyConnectorConfig(gatewayURL: nil),
            recentLogs: [],
            installDirectory: URL(fileURLWithPath: "/tmp/hermes-test"),
            launchAgentURL: URL(fileURLWithPath: "/tmp/com.hermesremote.connector.plist")
        )
    }
}
