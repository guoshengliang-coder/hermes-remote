import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopBootstrapTests: XCTestCase {
    func testRunningRetiredConnectorCannotBeginSetup() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: true),
            hermesReachable: true,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertFalse(plan.requiresConfirmation)
        XCTAssertTrue(plan.detailChinese.contains("停止支持旧连接模式"))
    }

    func testRetiredConnectorRemainsBlockedWhenChannelIsDisabled() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: true),
            hermesReachable: true,
            managedInstallAvailability: .disabled
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertEqual(plan.steps.map(\.kind), [.inspectExisting])
        XCTAssertTrue(plan.detailChinese.contains("停止支持旧连接模式"))
    }

    func testRetiredConnectorIsBlockedRegardlessOfHermesHealth() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: true),
            hermesReachable: false,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("停止支持旧连接模式"))
    }

    func testStoppedExistingConnectorFailsClosedWithoutOfferingSecondInstance() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: false),
            hermesReachable: false,
            managedInstallAvailability: .ready
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.detailChinese.contains("停止支持旧连接模式"))
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
        XCTAssertTrue(plan.detailChinese.contains("账号服务暂不支持"))
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
        XCTAssertTrue(plan.detailChinese.contains("暂时无法确认其安装来源"))
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

    func testCleanInstallPreflightUsesTheManagedLoopbackContract() {
        XCTAssertEqual(
            DesktopBootstrapPlanner.hermesStatusURL.absoluteString,
            "http://127.0.0.1:9119/api/status"
        )
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
        XCTAssertTrue(plan.detailChinese.contains("Desktop 已完成设置"))
    }

    func testManagedActiveReleaseOffersUpgradeWithoutRebinding() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: true,
            managedInstallAvailability: .ready,
            managedInstallation: .active(
                releaseVersion: "0.3.4",
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 2
            ),
            targetReleaseVersion: "0.3.5"
        )

        XCTAssertEqual(plan.readiness, .managedUpgradeAvailable)
        XCTAssertTrue(plan.canBegin)
        XCTAssertTrue(plan.requiresConfirmation)
        XCTAssertFalse(plan.steps.contains { $0.kind == .bindAccount })
        XCTAssertTrue(plan.detailChinese.contains("保留账号、设备绑定"))
        XCTAssertTrue(plan.detailChinese.contains("连接和任务可能短暂中断"))
    }

    func testManagedActiveReleaseDoesNotOfferSameVersionOrDowngrade() {
        for target in ["0.3.5", "0.3.4", "invalid"] {
            let plan = DesktopBootstrapPlanner.plan(
                legacy: snapshot(installed: false, running: false),
                hermesReachable: true,
                managedInstallAvailability: .ready,
                managedInstallation: .active(
                    releaseVersion: "0.3.5",
                    bindingID: "70000000-0000-4000-8000-000000000007",
                    bindingGeneration: 2
                ),
                targetReleaseVersion: target
            )
            XCTAssertEqual(plan.readiness, .managedInstallActive)
            XCTAssertFalse(plan.canBegin)
        }
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
        XCTAssertTrue(plan.detailChinese.contains("恢复上次未完成的设置"))
    }

    /// The shape that made the new Mac mini unusable: 9119 answers, so the planner used to stop at
    /// its read-only "unmanaged Hermes" card, while the Hermes on that port was in fact this Mac's
    /// own checkout and perfectly adoptable.
    func testAReachableLoopbackHermesThatIsThisMacsOwnCheckoutIsAdoptedNotRefused() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: true,
            managedInstallAvailability: .ready,
            localHermes: localHermes()
        )

        XCTAssertEqual(plan.readiness, .readyToAdoptLocalHermes)
        XCTAssertTrue(plan.canBegin)
        XCTAssertTrue(plan.requiresConfirmation)
        XCTAssertTrue(plan.steps.contains { $0.kind == .adoptLocalHermes })
        XCTAssertFalse(plan.steps.contains { $0.kind == .installHermes })
        XCTAssertTrue(plan.detailChinese.contains("使用"))
        XCTAssertFalse(plan.detailChinese.contains("/Users/owner/.hermes/hermes-agent"))
    }

    func testAdoptionWaitsForTheSignedChannelInsteadOfInstallingOnItsOwn() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: false, running: false),
            hermesReachable: false,
            managedInstallAvailability: .disabled,
            localHermes: localHermes()
        )

        XCTAssertEqual(plan.readiness, .waitingForSignedRelease)
        XCTAssertFalse(plan.canBegin)
        XCTAssertTrue(plan.steps.contains { $0.kind == .preserveExisting })
        XCTAssertFalse(plan.steps.contains { $0.kind == .adoptLocalHermes })
        XCTAssertTrue(plan.detailChinese.contains("使用"))
    }

    /// A usable Hermes does not make a retired Connector safe to adopt.
    func testRetiredConnectorBlocksEvenAnAdoptableCheckout() {
        let plan = DesktopBootstrapPlanner.plan(
            legacy: snapshot(installed: true, running: true),
            hermesReachable: true,
            managedInstallAvailability: .ready,
            localHermes: localHermes()
        )

        XCTAssertEqual(plan.readiness, .existingServiceNeedsAttention)
        XCTAssertFalse(plan.canBegin)
        XCTAssertFalse(plan.steps.contains { $0.kind == .adoptLocalHermes })
    }

    private func localHermes() -> DesktopLocalHermesInstallation {
        DesktopLocalHermesInstallation(
            executable: URL(fileURLWithPath: "/Users/owner/.hermes/hermes-agent/venv/bin/hermes"),
            checkoutRoot: URL(fileURLWithPath: "/Users/owner/.hermes/hermes-agent"),
            hermesHome: URL(fileURLWithPath: "/Users/owner/.hermes"),
            commit: "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121",
            version: "0.21.3",
            identityChangedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    private func snapshot(installed: Bool, running: Bool) -> RetiredConnectorSnapshot {
        RetiredConnectorSnapshot(
            isInstalled: installed,
            isRunning: running)
    }
}
