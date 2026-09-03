import XCTest
@testable import HermesGoDesktopCore

final class DesktopPresentationTests: XCTestCase {
    func testRequiredFailureMapsToAttentionPresentation() {
        let snapshot = DesktopHealthSnapshot(components: [
            ComponentHealth(component: .desktopAgent, level: .healthy, detail: "ok"),
            ComponentHealth(component: .gateway, level: .failed, detail: "offline"),
            ComponentHealth(component: .hermes, level: .healthy, detail: "ok"),
        ])

        XCTAssertEqual(snapshot.presentation.title, "需要处理")
        XCTAssertEqual(snapshot.presentation.level, .failed)
    }

    func testAccountIssueOverridesCheckingPresentation() {
        let presentation = DesktopAccountState.checking.presentation(hasIssue: true)

        XCTAssertEqual(presentation.level, .degraded)
        XCTAssertEqual(presentation.menuLabel, "需确认")
        XCTAssertEqual(presentation.settingsLabel, "需要确认")
    }

    func testSignedInPresentationIsSharedAcrossSurfaces() {
        let presentation = DesktopAccountState.signedIn(.fixture).presentation(hasIssue: false)

        XCTAssertEqual(presentation.level, .healthy)
        XCTAssertEqual(presentation.menuLabel, "已登录")
        XCTAssertEqual(presentation.settingsLabel, "已登录")
    }
}

private extension AccountDashboard {
    static let fixture = AccountDashboard(
        session: AccountSessionRecord(
            account: HermesAccount(
                id: "account-1",
                displayName: nil,
                email: nil,
                avatarUrl: nil
            ),
            installation: AccountInstallation(
                id: "installation-1",
                kind: "desktop",
                platform: "macos",
                displayName: "Mac"
            ),
            session: AccountSessionTokens(
                accessToken: "access",
                accessExpiresAt: "2030-01-01T00:00:00Z",
                refreshToken: "refresh",
                refreshExpiresAt: "2030-02-01T00:00:00Z"
            )
        ),
        binding: AccountBindingSnapshot(
            state: "no_binding",
            id: nil,
            generation: nil,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: nil,
            previousBinding: nil
        ),
        installations: []
    )
}
