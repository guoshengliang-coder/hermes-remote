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

        let deletion = DesktopIssue.account(.remote(AccountRemoteError(
            code: "HR-ACCOUNT-012",
            message: "raw remote message must stay hidden",
            retryable: false,
            recoveryAction: "none",
            correlationId: nil
        )))
        XCTAssertEqual(deletion.code, .accountDeletionPending)
        XCTAssertEqual(deletion.recoveryAction, .none)
        XCTAssertTrue(deletion.detailChinese.contains("永久删除"))
        XCTAssertTrue(deletion.detailEnglish.contains("permanently deleted"))
        XCTAssertFalse(deletion.sanitizedDiagnostic.contains("raw remote message"))
    }

    func testMultiDeviceIssuesUseRegisteredBilingualSelectionRecovery() {
        let expected: [(DesktopIssueCode, String)] = [
            (.deviceSelectionRequired, "HR-BIND-009"),
            (.ownedDeviceLimitReached, "HR-BIND-010"),
            (.deviceUnavailable, "HR-BIND-011"),
        ]

        for (code, rawValue) in expected {
            let issue = DesktopIssue(code: code)
            XCTAssertEqual(issue.code.rawValue, rawValue)
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertFalse(issue.retryable)
            XCTAssertEqual(issue.recoveryAction, .selectDevice)
        }
    }

    func testEmailAuthenticationIssuesUseRegisteredBilingualRecoveryContracts() {
        let expected: [(String, DesktopIssueCode, DesktopRecoveryAction, Bool)] = [
            ("HR-AUTH-009", .invalidEmailCode, .requestCode, false),
            ("HR-AUTH-010", .emailDeliveryFailed, .retry, true),
            ("HR-AUTH-011", .emailSignInDisabled, .continueLegacy, false),
        ]

        for (remoteCode, expectedCode, recovery, retryable) in expected {
            let issue = DesktopIssue.account(.remote(AccountRemoteError(
                code: remoteCode,
                message: "must not become primary UI text",
                retryable: retryable,
                recoveryAction: recovery.rawValue,
                correlationId: nil
            )))
            XCTAssertEqual(issue.code, expectedCode)
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertEqual(issue.recoveryAction, recovery)
            XCTAssertEqual(issue.retryable, retryable)
            XCTAssertFalse(issue.displayChinese.contains("must not become primary UI text"))
        }
    }

    func testManagedPhoneRevocationMapsDisabledIdentityManagementWithoutRawServerText() {
        let issue = DesktopIssue.account(.remote(AccountRemoteError(
            code: "HR-ACCOUNT-009",
            message: "internal rollout detail must stay hidden",
            retryable: false,
            recoveryAction: "none",
            correlationId: nil
        )))

        XCTAssertEqual(issue.code, .identityManagementDisabled)
        XCTAssertEqual(issue.summaryChinese, "登录方式管理尚未开放")
        XCTAssertEqual(issue.summaryEnglish, "Identity management isn't enabled")
        XCTAssertFalse(issue.retryable)
        XCTAssertEqual(issue.recoveryAction, .none)
        XCTAssertFalse(issue.displayChinese.contains("internal rollout detail"))
        XCTAssertFalse(issue.displayEnglish.contains("internal rollout detail"))
    }

    func testSharingIssuesUseRegisteredBilingualRecoveryContracts() {
        let expected: [(String, DesktopIssueCode)] = [
            ("HR-SHARE-001", .sharingFeatureDisabled),
            ("HR-SHARE-002", .deviceSharingLimitReached),
            ("HR-SHARE-003", .sharedDeviceLimitReached),
            ("HR-SHARE-004", .shareInvitationInvalid),
            ("HR-SHARE-005", .shareEmailMismatch),
            ("HR-SHARE-006", .wholeDeviceAcknowledgementRequired),
            ("HR-SHARE-007", .shareConflict),
            ("HR-SHARE-008", .shareDeliveryFailed),
        ]

        for (remoteCode, expectedCode) in expected {
            let issue = DesktopIssue.account(.remote(AccountRemoteError(
                code: remoteCode,
                message: "must not become primary UI text",
                retryable: remoteCode == "HR-SHARE-008",
                recoveryAction: "open_sharing",
                correlationId: nil
            )))
            XCTAssertEqual(issue.code, expectedCode)
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertEqual(issue.retryable, remoteCode == "HR-SHARE-008")
        }
    }

    func testMigrationIssuesReflectSafeTerminalStateAndRemainBilingual() {
        let restored = DesktopIssue.migration(
            DesktopMigrationCoordinatorError.healthTimedOut,
            terminalState: .legacyActive
        )
        XCTAssertEqual(restored.code, .migrationCandidateFailed)
        XCTAssertTrue(restored.retryable)
        XCTAssertTrue(restored.displayChinese.contains("HR-MIGRATE-003"))
        XCTAssertTrue(restored.displayEnglish.contains("HR-MIGRATE-003"))

        let ambiguous = DesktopIssue.migration(
            DesktopMigrationCoordinatorError.commitAmbiguous,
            terminalState: .rollbackAttentionRequired
        )
        XCTAssertEqual(ambiguous.code, .migrationRollbackFailed)
        XCTAssertFalse(ambiguous.retryable)
        XCTAssertFalse(ambiguous.detailChinese.isEmpty)
        XCTAssertFalse(ambiguous.detailEnglish.isEmpty)

        let cleanup = DesktopIssue(code: .migrationCleanupPending)
        XCTAssertEqual(cleanup.code.rawValue, "HR-MIGRATE-005")
        XCTAssertTrue(cleanup.detailChinese.contains("新连接已生效"))
        XCTAssertTrue(cleanup.detailEnglish.contains("new connection is active"))
        XCTAssertTrue(cleanup.retryable)
        XCTAssertEqual(cleanup.recoveryAction, .retry)
    }
}
