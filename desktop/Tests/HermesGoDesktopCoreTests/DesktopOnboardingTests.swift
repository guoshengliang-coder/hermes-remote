import Foundation
import XCTest
@testable import HermesGoDesktopCore

/// `docs/DESKTOP_ONBOARDING_REQUIREMENTS.md` §4.1: one row of the state table per test where it
/// matters, so a routing change has to say which row it moved.
final class DesktopOnboardingTests: XCTestCase {
    // MARK: Account states

    func testLaunchIsNeverRenderedAsSignIn() {
        XCTAssertEqual(route(.checking), .launching)
    }

    func testUnconfirmedAccountShowsRetryInsteadOfSpinningForever() {
        XCTAssertEqual(route(.checking, hasAccountIssue: true), .launchFailed)
    }

    func testSignedOutAndSigningInStayOnTheSignInPageWithoutAReason() {
        XCTAssertEqual(route(.signedOut), .signIn(expiredReason: nil))
        XCTAssertEqual(route(.signingIn), .signIn(expiredReason: nil))
    }

    func testExpiredSessionCarriesItsReasonToTheSignInPage() {
        XCTAssertEqual(
            route(.needsSignIn(.accountSessionExpired), readiness: .managedInstallActive),
            .signIn(expiredReason: .accountSessionExpired)
        )
    }

    func testGatewayWithoutAccountsAndDeletedAccountHaveTheirOwnPages() {
        XCTAssertEqual(route(.unavailable), .serviceUnavailable)
        XCTAssertEqual(route(.accountDeletionSubmitted), .accountDeletionSubmitted)
    }

    // MARK: Signed in

    func testInstalledMacGoesStraightToMain() {
        XCTAssertEqual(route(signedIn(), readiness: .managedInstallActive), .main)
        XCTAssertEqual(route(signedIn(), readiness: .managedUpgradeAvailable), .main)
    }

    func testMacThatEnteredOnboardingIsShownThePhoneStepAfterItsInstall() {
        XCTAssertEqual(
            route(signedIn(), readiness: .managedInstallActive, record: .init(phoneStepPending: true)),
            .onboarding(.connectPhone)
        )
    }

    func testFreshAccountOnFreshMacStartsOnboarding() {
        XCTAssertEqual(route(signedIn(), readiness: .readyForManagedInstall), .onboarding(.connectMac))
        XCTAssertEqual(route(signedIn(), readiness: .waitingForSignedRelease), .onboarding(.connectMac))
    }

    func testUndecidedHermesInstallStopsOnboardingAtPrepareHermes() {
        XCTAssertEqual(
            route(signedIn(), readiness: .readyForManagedInstall, hermesDecisionPending: true),
            .onboarding(.prepareHermes)
        )
    }

    func testHalfInstalledMacNeverOffersASecondInstall() {
        XCTAssertEqual(route(signedIn(), readiness: .existingServiceNeedsAttention), .main)
        XCTAssertEqual(
            route(signedIn(owned: [device("hermes-other")]), readiness: .existingServiceNeedsAttention),
            .main
        )
    }

    /// §4.1 row 6b. `existingServicePreserved` means a working existing connection was kept because
    /// the managed channel is unavailable. It is not the "needs attention" problem case (§8), but it
    /// must stay on main for the same reason: onboarding would offer a second install.
    func testPreservedExistingConnectionStaysOnMain() {
        XCTAssertEqual(route(signedIn(), readiness: .existingServicePreserved), .main)
        XCTAssertEqual(
            route(signedIn(owned: [device("hermes-mini")]), readiness: .existingServicePreserved),
            .main
        )
    }

    func testRunningSetupKeepsOnboardingOnScreenWhateverTheReadiness() {
        XCTAssertEqual(
            route(signedIn(), readiness: .checking, setupInProgress: true),
            .onboarding(.connectMac)
        )
    }

    func testUpgradeOfAnInstalledMacStaysOnMain() {
        XCTAssertEqual(route(signedIn(), readiness: .managedUpgradeAvailable, setupInProgress: true), .main)
        XCTAssertEqual(route(signedIn(), readiness: .managedInstallActive, setupInProgress: true), .main)
    }

    func testUnknownLocalInstallationWaitsInsteadOfGuessing() {
        XCTAssertEqual(route(signedIn(), readiness: .checking), .launching)
    }

    // MARK: Existing account, new Mac (§7)

    func testExistingAccountOnNewMacIsAskedFirst() {
        XCTAssertEqual(
            route(signedIn(owned: [device("hermes-mini")]), readiness: .readyForManagedInstall),
            .newMacChoice
        )
    }

    func testChoosingToConnectContinuesIntoOnboarding() {
        XCTAssertEqual(
            route(
                signedIn(owned: [device("hermes-mini")]),
                readiness: .readyForManagedInstall,
                record: .init(newMacChoice: .connect)
            ),
            .onboarding(.connectMac)
        )
    }

    func testManageOnlyGoesToMainAndIsNotAskedAgain() {
        XCTAssertEqual(
            route(
                signedIn(owned: [device("hermes-mini")]),
                readiness: .readyForManagedInstall,
                record: .init(newMacChoice: .manageOnly)
            ),
            .main
        )
    }

    func testThisMacsOwnPreviousBindingIsNotCountedAsAnotherMac() {
        XCTAssertEqual(
            route(
                signedIn(owned: [device("hermes-local")], localDeviceID: "hermes-local"),
                readiness: .readyForManagedInstall
            ),
            .onboarding(.connectMac)
        )
    }

    func testSharedMacsDoNotTriggerTheChoice() {
        XCTAssertEqual(
            route(signedIn(owned: [device("hermes-shared", access: "operator")]), readiness: .readyForManagedInstall),
            .onboarding(.connectMac)
        )
    }

    // MARK: Owned-Mac quota (§7.2)

    /// §7.2: at the limit the connect option is the unavailable one and manage-only still works. The
    /// limit comes from the Gateway (3 with multi-device, otherwise 1); only owned Macs count.
    func testOwnedMacQuotaBlocksAddingAMacOnlyWhenFull() {
        XCTAssertFalse(DesktopOwnedMacQuota.isFull(dashboard(owned: [], maxOwned: 3)))
        XCTAssertFalse(DesktopOwnedMacQuota.isFull(dashboard(owned: [device("hermes-mini")], maxOwned: 3)))
        XCTAssertTrue(
            DesktopOwnedMacQuota.isFull(
                dashboard(owned: (0..<3).map { device("hermes-\($0)") }, maxOwned: 3)
            )
        )
        XCTAssertTrue(DesktopOwnedMacQuota.isFull(dashboard(owned: [device("hermes-mini")], maxOwned: 1)))
    }

    /// A Gateway reporting an impossible limit must not make the page claim a Mac can be added.
    func testOwnedMacQuotaTreatsANonPositiveLimitAsOne() {
        XCTAssertTrue(DesktopOwnedMacQuota.isFull(dashboard(owned: [device("hermes-mini")], maxOwned: 0)))
    }

    // MARK: Phone step (§6.4)

    func testAndroidAppAndWebAppBothCountAsConnectedClients() {
        let clients = DesktopRemoteClients.active([
            installation("a", kind: "phone"),
            installation("b", kind: "browser"),
            installation("c", kind: "desktop"),
            installation("d", kind: "phone", status: "revoked"),
            installation("e", kind: "browser", current: true),
        ])
        XCTAssertEqual(clients.map(\.id), ["a", "b"])
    }

    func testOnlyClientsThatAppearAfterTheBaselineCountAsNew() {
        let before = [installation("old-phone", kind: "phone")]
        let baseline = DesktopRemoteClients.baseline(before)
        XCTAssertTrue(DesktopRemoteClients.newlyConnected(before, baseline: baseline).isEmpty)

        let after = before + [installation("iphone-web", kind: "browser")]
        XCTAssertEqual(DesktopRemoteClients.newlyConnected(after, baseline: baseline).map(\.id), ["iphone-web"])
    }

    func testWebAppIsNeverLabelledAsAnIPhone() {
        XCTAssertEqual(DesktopRemoteClients.displayName(installation("x", kind: "browser")), "网页版 Hermes GO")
        XCTAssertEqual(DesktopRemoteClients.displayName(installation("y", kind: "phone")), "Phone y")
    }

    func testPhoneTargetsFollowTheAccountGatewayOrigin() {
        let gateway = URL(string: "https://mrlgs.net")!
        XCTAssertEqual(DesktopPhonePlatform.android.targetURL(gatewayURL: gateway)?.absoluteString, "https://mrlgs.net/")
        XCTAssertEqual(DesktopPhonePlatform.apple.targetURL(gatewayURL: gateway)?.absoluteString, "https://mrlgs.net/app/")
        let staging = URL(string: "https://user:pw@relay.example:8443/v2?x=1#f")!
        XCTAssertEqual(
            DesktopPhonePlatform.apple.targetURL(gatewayURL: staging)?.absoluteString,
            "https://relay.example:8443/app/"
        )
        XCTAssertNil(DesktopPhonePlatform.android.targetURL(gatewayURL: URL(string: "http://relay.example")!))
    }

    // MARK: Store

    func testRecordIsStoredPerAccountAndAnEmptyRecordLeavesNothingBehind() throws {
        let suite = "hermes-onboarding-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsDesktopOnboardingStore(defaults: defaults, keyPrefix: "test")
        let first = "10000000-0000-4000-8000-000000000001"
        let second = "10000000-0000-4000-8000-000000000002"
        let record = DesktopOnboardingRecord(newMacChoice: .manageOnly, phoneStepPending: true, phoneBaseline: ["a"])

        store.save(record, accountID: first)
        XCTAssertEqual(store.load(accountID: first), record)
        XCTAssertEqual(store.load(accountID: second), DesktopOnboardingRecord())
        XCTAssertEqual(store.load(accountID: first.uppercased()), record)

        store.save(DesktopOnboardingRecord(), accountID: first)
        XCTAssertNil(defaults.object(forKey: "test.\(first)"))
        store.save(record, accountID: "not-an-account")
        XCTAssertTrue(defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix("test.") }.isEmpty)
    }

    // MARK: Helpers

    private func route(
        _ state: DesktopAccountState,
        hasAccountIssue: Bool = false,
        readiness: DesktopBootstrapReadiness = .checking,
        setupInProgress: Bool = false,
        hermesDecisionPending: Bool = false,
        record: DesktopOnboardingRecord = DesktopOnboardingRecord()
    ) -> DesktopEntryRoute {
        DesktopEntryRouter.route(DesktopEntryInputs(
            accountState: state,
            hasAccountIssue: hasAccountIssue,
            readiness: readiness,
            setupInProgress: setupInProgress,
            hermesDecisionPending: hermesDecisionPending,
            record: record
        ))
    }

    private func dashboard(
        owned: [AccountDevice] = [],
        localDeviceID: String? = nil,
        maxOwned: Int = 3
    ) -> AccountDashboard {
        guard case .signedIn(let dashboard) = signedIn(
            owned: owned,
            localDeviceID: localDeviceID,
            maxOwned: maxOwned
        ) else {
            preconditionFailure("the helper only builds signed-in states")
        }
        return dashboard
    }

    private func signedIn(
        owned: [AccountDevice] = [],
        localDeviceID: String? = nil,
        maxOwned: Int = 3
    ) -> DesktopAccountState {
        let account = HermesAccount(
            id: "10000000-0000-4000-8000-000000000001",
            displayName: "Owner",
            email: "owner@example.invalid",
            avatarUrl: nil
        )
        let desktop = AccountInstallation(
            id: "20000000-0000-4000-8000-000000000001",
            kind: "desktop",
            platform: "macos",
            displayName: "MacBook Pro"
        )
        let tokens = AccountSessionTokens(
            accessToken: "hga_x",
            accessExpiresAt: "2099-09-02T01:00:00Z",
            refreshToken: "hgr_x",
            refreshExpiresAt: "2099-10-02T00:00:00Z"
        )
        let binding = AccountBindingSnapshot(
            state: localDeviceID == nil ? "no_binding" : "revoked",
            id: nil,
            generation: nil,
            deviceId: localDeviceID,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: nil,
            previousBinding: nil
        )
        return .signedIn(AccountDashboard(
            session: AccountSessionRecord(account: account, installation: desktop, session: tokens),
            binding: binding,
            installations: [],
            devices: owned,
            maxOwnedDevices: maxOwned
        ))
    }

    private func device(_ deviceID: String, access: String = "owner") -> AccountDevice {
        AccountDevice(
            id: UUID().uuidString.lowercased(),
            generation: 1,
            deviceId: deviceID,
            desktopDisplayName: deviceID,
            publicKeyFingerprint: String(repeating: "a", count: 64),
            connector: .init(online: true, lastSeenAt: "2026-09-07T00:00:00Z"),
            hermes: .init(reachable: true, version: "1.0.0"),
            gateway: .init(latencyMs: 12),
            endToEnd: .init(healthy: true, checkedAt: "2026-09-07T00:00:00Z"),
            access: access,
            isDefault: false
        )
    }

    private func installation(
        _ id: String,
        kind: String,
        status: String = "active",
        current: Bool = false
    ) -> ManagedAccountInstallation {
        ManagedAccountInstallation(
            id: id,
            kind: kind,
            platform: kind == "browser" ? "web" : "android",
            displayName: "Phone \(id)",
            lastSeenAt: "2026-09-23T00:00:00Z",
            status: status,
            current: current
        )
    }
}
