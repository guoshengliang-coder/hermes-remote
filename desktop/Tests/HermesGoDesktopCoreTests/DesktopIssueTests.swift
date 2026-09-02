import XCTest
@testable import HermesGoDesktopCore

final class DesktopIssueTests: XCTestCase {
    func testIssueHasStableBilingualContractAndRecovery() {
        let issue = DesktopIssue(code: .appTokenRejected)

        XCTAssertEqual(issue.code.rawValue, "HR-AUTH-001")
        XCTAssertEqual(issue.summaryChinese, "App Token 无效")
        XCTAssertEqual(issue.summaryEnglish, "Invalid App Token")
        XCTAssertFalse(issue.retryable)
        XCTAssertEqual(issue.recoveryAction, .settings)
        XCTAssertTrue(issue.displayChinese.contains("HR-AUTH-001"))
        XCTAssertTrue(issue.displayEnglish.contains("HR-AUTH-001"))
    }

    func testTechnicalCauseIsRedacted() {
        let issue = DesktopIssue(
            code: .connectionFailed,
            technicalCause: "Authorization: Bearer secret-token password=hunter2"
        )

        XCTAssertFalse(issue.sanitizedDiagnostic.contains("secret-token"))
        XCTAssertFalse(issue.sanitizedDiagnostic.contains("hunter2"))
        XCTAssertTrue(issue.sanitizedDiagnostic.contains("<redacted>"))
    }

    func testIncompletePairingConfigurationHasBilingualRecoveryContract() {
        let issue = DesktopIssue(code: .incompletePairingConfiguration)

        XCTAssertEqual(issue.code.rawValue, "HR-CONFIG-004")
        XCTAssertTrue(issue.detailChinese.contains("配置名称"))
        XCTAssertTrue(issue.detailEnglish.contains("configuration name"))
        XCTAssertTrue(issue.retryable)
        XCTAssertEqual(issue.recoveryAction, .settings)
    }

    func testOversizedPairingPayloadUsesRegisteredConfigurationError() {
        let issue = DesktopIssue(code: .pairingPayloadTooLarge)

        XCTAssertEqual(issue.code.rawValue, "HR-CONFIG-005")
        XCTAssertTrue(issue.detailChinese.contains("二维码"))
        XCTAssertTrue(issue.detailEnglish.contains("QR code"))
    }

    func testAccountAndOAuthIssuesUseRegisteredBilingualRecoveryContracts() {
        let remote = AccountRemoteError(
            code: "HR-BIND-002",
            message: "provider response that must stay hidden",
            retryable: false,
            recoveryAction: "verify_and_replace",
            correlationId: "80000000-0000-4000-8000-000000000001"
        )
        let binding = DesktopIssue.account(.remote(remote))
        let oauth = DesktopIssue.oauth(.callbackTimedOut)

        XCTAssertEqual(binding.code, .bindingConflict)
        XCTAssertEqual(binding.recoveryAction, .verifyAndReplace)
        XCTAssertTrue(binding.detailChinese.contains("另一台 Mac"))
        XCTAssertTrue(binding.detailEnglish.contains("Another Mac"))
        XCTAssertFalse(binding.sanitizedDiagnostic.contains("provider response"))
        XCTAssertEqual(oauth.code.rawValue, "HR-AUTH-008")
        XCTAssertTrue(oauth.retryable)
        XCTAssertEqual(oauth.recoveryAction, .signIn)
    }
}
