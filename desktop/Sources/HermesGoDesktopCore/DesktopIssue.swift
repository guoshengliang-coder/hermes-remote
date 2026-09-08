import Foundation

public enum DesktopRecoveryAction: String, Codable, Equatable, Sendable {
    case retry
    case settings
    case startDesktop
    case details
    case signIn
    case requestCode
    case continueLegacy
    case verifyAndReplace
    case selectDevice
    case openSharing
    case none
}

public enum DesktopIssueCode: String, Codable, Equatable, Sendable {
    case connectionFailed = "HR-CONN-002"
    case connectorOffline = "HR-CONN-005"
    case appTokenRejected = "HR-AUTH-001"
    case googleSignInFailed = "HR-AUTH-002"
    case accountSessionExpired = "HR-AUTH-003"
    case accountSessionRevoked = "HR-AUTH-004"
    case refreshCredentialReused = "HR-AUTH-005"
    case reauthenticationRequired = "HR-AUTH-006"
    case signInRateLimited = "HR-AUTH-007"
    case googleSignInInterrupted = "HR-AUTH-008"
    case invalidEmailCode = "HR-AUTH-009"
    case emailDeliveryFailed = "HR-AUTH-010"
    case emailSignInDisabled = "HR-AUTH-011"
    case accountDisabled = "HR-ACCOUNT-001"
    case accountServiceUnavailable = "HR-ACCOUNT-002"
    case accountFeatureDisabled = "HR-ACCOUNT-003"
    case accountRequestInvalid = "HR-ACCOUNT-004"
    case accountIdempotencyConflict = "HR-ACCOUNT-005"
    case accountResourceNotFound = "HR-ACCOUNT-006"
    case desktopAccountRequired = "HR-ACCOUNT-007"
    case identityManagementDisabled = "HR-ACCOUNT-009"
    case accountDeletionPending = "HR-ACCOUNT-012"
    case bindingMissing = "HR-BIND-001"
    case bindingConflict = "HR-BIND-002"
    case bindingExpired = "HR-BIND-003"
    case bindingProofFailed = "HR-BIND-005"
    case bindingRevoked = "HR-BIND-006"
    case bindingReplacementFailed = "HR-BIND-007"
    case bindingFeatureDisabled = "HR-BIND-008"
    case deviceSelectionRequired = "HR-BIND-009"
    case ownedDeviceLimitReached = "HR-BIND-010"
    case deviceUnavailable = "HR-BIND-011"
    case sharingFeatureDisabled = "HR-SHARE-001"
    case deviceSharingLimitReached = "HR-SHARE-002"
    case sharedDeviceLimitReached = "HR-SHARE-003"
    case shareInvitationInvalid = "HR-SHARE-004"
    case shareEmailMismatch = "HR-SHARE-005"
    case wholeDeviceAcknowledgementRequired = "HR-SHARE-006"
    case shareConflict = "HR-SHARE-007"
    case shareDeliveryFailed = "HR-SHARE-008"
    case relayFailure = "HR-RPC-001"
    case configurationLoadFailed = "HR-CONFIG-001"
    case configurationSaveFailed = "HR-CONFIG-002"
    case invalidRelayURL = "HR-CONFIG-003"
    case incompletePairingConfiguration = "HR-CONFIG-004"
    case pairingPayloadTooLarge = "HR-CONFIG-005"
    case accountConfigurationMissing = "HR-CONFIG-006"
    case migrationPreflightFailed = "HR-MIGRATE-001"
    case migrationConnectorMismatch = "HR-MIGRATE-002"
    case migrationCandidateFailed = "HR-MIGRATE-003"
    case migrationRollbackFailed = "HR-MIGRATE-004"
    case migrationCleanupPending = "HR-MIGRATE-005"
}

public struct DesktopIssue: Error, Equatable, Sendable {
    public let code: DesktopIssueCode
    public let summaryChinese: String
    public let summaryEnglish: String
    public let detailChinese: String
    public let detailEnglish: String
    public let retryable: Bool
    public let recoveryAction: DesktopRecoveryAction
    public let technicalCause: String?

    public init(code: DesktopIssueCode, technicalCause: String? = nil) {
        self.code = code
        let values = Self.catalog(code)
        summaryChinese = values.summaryChinese
        summaryEnglish = values.summaryEnglish
        detailChinese = values.detailChinese
        detailEnglish = values.detailEnglish
        retryable = values.retryable
        recoveryAction = values.recoveryAction
        self.technicalCause = technicalCause.map { SecretRedactor.redact($0) }
    }

    public var displayChinese: String { "\(detailChinese)（\(code.rawValue)）" }
    public var displayEnglish: String { "\(detailEnglish) (\(code.rawValue))" }

    public var sanitizedDiagnostic: String {
        var lines = [
            "code=\(code.rawValue)",
            "retryable=\(retryable)",
            "recovery=\(recoveryAction.rawValue)",
        ]
        if let technicalCause { lines.append("cause=\(technicalCause)") }
        return SecretRedactor.redact(lines.joined(separator: "\n"))
    }

    private static func catalog(_ code: DesktopIssueCode) -> (
        summaryChinese: String,
        summaryEnglish: String,
        detailChinese: String,
        detailEnglish: String,
        retryable: Bool,
        recoveryAction: DesktopRecoveryAction
    ) {
        switch code {
        case .connectionFailed:
            ("无法连接 Relay", "Couldn't connect to the Relay", "连接失败，请检查网络和地址。", "Connection failed. Check the network and URL.", true, .retry)
        case .connectorOffline:
            ("Mac 端离线", "The Mac is offline", "Mac 端当前离线，请启动 Hermes Go Desktop。", "The Mac is offline. Start Hermes Go Desktop.", true, .startDesktop)
        case .appTokenRejected:
            ("App Token 无效", "Invalid App Token", "App Token 无效或已失效，请重新配置。", "The App Token is invalid or expired. Configure it again.", false, .settings)
        case .googleSignInFailed:
            ("无法验证 Google 登录", "Couldn't verify Google sign-in", "无法验证 Google 登录，请重新登录。", "Couldn't verify Google sign-in. Sign in again.", false, .signIn)
        case .accountSessionExpired:
            ("登录已过期", "Session expired", "登录已过期，请重新登录。", "Your session expired. Sign in again.", false, .signIn)
        case .accountSessionRevoked:
            ("登录已被撤销", "Session revoked", "这台设备的登录已被撤销，请重新登录。", "This device's session was revoked. Sign in again.", false, .signIn)
        case .refreshCredentialReused:
            ("登录凭据已失效", "Sign-in credential is no longer valid", "检测到登录凭据重复使用，为保护账号已退出这台设备。", "Reuse of a sign-in credential was detected, so this device was signed out for safety.", false, .signIn)
        case .reauthenticationRequired:
            ("需要重新验证", "Verification required", "为确认是你本人，请重新验证当前账号。", "Verify your current account again to confirm it's you.", false, .signIn)
        case .signInRateLimited:
            ("登录请求过于频繁", "Too many sign-in requests", "登录请求过于频繁，请稍候再试。", "Too many sign-in requests. Wait a moment and try again.", true, .retry)
        case .googleSignInInterrupted:
            ("Google 登录未完成", "Google sign-in didn't finish", "Google 登录未完成，请重新尝试。", "Google sign-in did not finish. Try again.", true, .signIn)
        case .invalidEmailCode:
            ("验证码无效或已过期", "Invalid or expired code", "邮箱验证码无效或已过期，请重新获取验证码。", "The email code is invalid or expired. Request a new code.", false, .requestCode)
        case .emailDeliveryFailed:
            ("登录邮件发送失败", "Sign-in email wasn't sent", "登录邮件发送失败，请稍后重试。", "The sign-in email couldn't be sent. Try again shortly.", true, .retry)
        case .emailSignInDisabled:
            ("邮箱登录尚未开放", "Email sign-in isn't enabled", "此 Relay 尚未启用邮箱登录，可继续使用原有连接方式。", "Email sign-in isn't enabled on this Gateway yet. Continue with the legacy connection.", false, .continueLegacy)
        case .accountDisabled:
            ("账号当前不可用", "Account unavailable", "此 Hermes GO 账号当前不可用，请联系支持。", "This Hermes GO account is currently unavailable. Contact support.", false, .none)
        case .accountServiceUnavailable:
            ("账号服务暂时不可用", "Account service unavailable", "账号服务暂时不可用，请稍后重试。", "The account service is temporarily unavailable. Try again shortly.", true, .retry)
        case .accountFeatureDisabled:
            ("账号登录尚未开放", "Account sign-in isn't enabled", "此 Relay 尚未启用账号登录，可继续使用原有连接方式。", "Account sign-in is not enabled on this Gateway yet. Continue with the legacy connection.", false, .continueLegacy)
        case .accountRequestInvalid:
            ("账号请求无效", "Invalid account request", "账号请求格式无效，请更新客户端或重试。", "The account request is invalid. Update the client or try again.", false, .details)
        case .accountIdempotencyConflict:
            ("操作重试标识冲突", "Retry key conflict", "此重试标识已用于另一项请求，请重新发起操作。", "That retry key was already used for a different account request.", false, .details)
        case .accountResourceNotFound:
            ("找不到设备", "Device not found", "找不到这个账号下的目标设备。", "The requested account resource was not found.", false, .details)
        case .desktopAccountRequired:
            ("只能在 Desktop 操作", "Desktop required", "此操作只能在当前登录的 Hermes Go Desktop 上完成。", "This operation is available only from Hermes Go Desktop.", false, .none)
        case .identityManagementDisabled:
            ("登录方式管理尚未开放", "Identity management isn't enabled", "此 Relay 尚未启用登录方式管理。", "Identity management isn't enabled on this Gateway yet.", false, .none)
        case .accountDeletionPending:
            ("账号正在永久删除", "Account deletion in progress", "此 Hermes GO 账号正在永久删除，已无法再次登录。", "This Hermes GO account is being permanently deleted and can no longer sign in.", false, .none)
        case .bindingMissing:
            ("尚未连接 Desktop", "No Desktop connection", "这个账号还没有连接 Desktop。", "This account has no Desktop connection yet.", true, .retry)
        case .bindingConflict:
            ("账号已连接另一台 Mac", "Another Mac is connected", "账号已连接另一台 Mac；确认替换前，原连接会继续工作。", "Another Mac is connected. The original remains active until replacement is confirmed.", false, .verifyAndReplace)
        case .bindingExpired:
            ("绑定确认已失效", "Binding confirmation expired", "Desktop 绑定确认已失效，请重新开始。", "The Desktop binding confirmation expired. Start again.", true, .retry)
        case .bindingProofFailed:
            ("Connector 身份验证失败", "Connector verification failed", "Desktop Connector 身份验证失败，请检查账号与设备。", "Desktop Connector verification failed. Check Account & Devices.", true, .retry)
        case .bindingRevoked:
            ("这台 Mac 的绑定已撤销", "This Mac binding was revoked", "这台 Mac 已不再绑定当前账号，请重新绑定或使用现有 Mac。", "This Mac is no longer bound to the account. Bind it again or use the current Mac.", false, .verifyAndReplace)
        case .bindingReplacementFailed:
            ("未能更换 Mac", "Couldn't replace the Mac", "未能更换 Mac，原来的连接仍在工作。", "Couldn't replace the Mac. The original connection is still working.", true, .retry)
        case .bindingFeatureDisabled:
            ("Desktop 绑定尚未开放", "Desktop binding isn't enabled", "此 Relay 尚未启用 Desktop 绑定，可继续使用原有连接。", "Desktop binding isn't enabled on this Gateway yet. Continue with the legacy connection.", false, .continueLegacy)
        case .deviceSelectionRequired:
            ("请选择一台 Mac", "Choose a Mac", "账号连接了多台 Mac，请先选择要使用的一台。", "This account has multiple Macs. Choose one to continue.", false, .selectDevice)
        case .ownedDeviceLimitReached:
            ("已达到 Mac 数量上限", "Mac limit reached", "此账号已连接三台自有 Mac，请先移除一台。", "This account already has three owned Macs. Remove one before adding another.", false, .selectDevice)
        case .deviceUnavailable:
            ("这台 Mac 已不可用", "This Mac is unavailable", "这台 Mac 已无法由当前账号使用，请选择其他设备。", "This Mac is no longer available to this account. Choose another device.", false, .selectDevice)
        case .sharingFeatureDisabled:
            ("设备共享尚未开放", "Device sharing isn't enabled", "此 Relay 尚未启用设备共享。", "Device sharing isn't enabled on this Gateway yet.", false, .none)
        case .deviceSharingLimitReached:
            ("共享人数已达上限", "Sharing limit reached", "这台 Mac 已共享给五个账号，请先撤销一个共享。", "This Mac is already shared with five accounts. Revoke one share first.", false, .openSharing)
        case .sharedDeviceLimitReached:
            ("共享设备已达上限", "Shared-device limit reached", "你的账号已接受十台共享 Mac，请先退出一台。", "Your account already has ten shared Macs. Leave one first.", false, .openSharing)
        case .shareInvitationInvalid:
            ("邀请已失效", "Invitation unavailable", "此共享邀请无效、已过期或已被取消，请让设备所有者重新邀请。", "This invitation is invalid, expired, or cancelled. Ask the device owner to invite you again.", false, .openSharing)
        case .shareEmailMismatch:
            ("邀请邮箱不匹配", "Invitation email doesn't match", "请使用收到邀请的已验证邮箱登录此账号。", "Sign in with the verified email address that received the invitation.", false, .signIn)
        case .wholeDeviceAcknowledgementRequired:
            ("需要确认整台设备访问", "Whole-device confirmation required", "请先确认被邀请者可访问这台 Hermes 暴露的会话、文件和配置。", "Confirm that the invited account may access sessions, files, and configuration exposed by this Hermes.", false, .openSharing)
        case .shareConflict:
            ("无法创建此共享", "Couldn't create this share", "此邀请或共享已存在，或目标账号不符合共享条件。", "This invitation or share already exists, or the target account isn't eligible.", false, .openSharing)
        case .shareDeliveryFailed:
            ("邀请邮件发送失败", "Invitation email wasn't sent", "邀请已安全保留，请稍后使用相同邮箱重试发送。", "The invitation was safely retained. Retry with the same email shortly.", true, .openSharing)
        case .relayFailure:
            ("Relay 请求失败", "Relay request failed", "Relay 请求失败，请查看详情后重试。", "The Relay request failed. Review the details and retry.", true, .retry)
        case .configurationLoadFailed:
            ("无法加载配置", "Couldn't load configuration", "无法加载配置，请重试。", "Couldn't load the configuration. Retry.", true, .retry)
        case .configurationSaveFailed:
            ("无法保存配置", "Couldn't save configuration", "无法保存配置，请重试。", "Couldn't save the configuration. Retry.", true, .settings)
        case .invalidRelayURL:
            ("Relay 地址无效", "Invalid Relay URL", "Relay 地址格式无效，请检查后重试。", "The Relay URL is invalid. Check it and retry.", true, .settings)
        case .incompletePairingConfiguration:
            ("配对配置不完整", "Incomplete pairing configuration", "请填写配置名称、Relay 地址和 App Token。", "Enter a configuration name, Relay URL, and App Token.", true, .settings)
        case .pairingPayloadTooLarge:
            ("配对信息过长", "Pairing data is too long", "Relay 地址和 App Token 过长，无法生成可扫描的二维码。", "The Relay URL and App Token are too long to fit in a scannable QR code.", true, .settings)
        case .accountConfigurationMissing:
            ("账号服务尚未配置", "Account service isn't configured", "此版本尚未正确配置账号服务，请继续使用原有连接。", "Account service isn't configured correctly in this build. Continue with the legacy connection.", false, .continueLegacy)
        case .migrationPreflightFailed:
            ("暂时无法升级连接", "Connection upgrade isn't ready", "暂时无法升级连接，现有连接未被修改。", "The connection can't be upgraded yet. The existing connection was not changed.", true, .continueLegacy)
        case .migrationConnectorMismatch:
            ("Connector 状态异常", "Unexpected Connector state", "检测到异常的 Connector 运行状态，已停止升级以避免重复连接。", "An unexpected Connector state was found. Upgrade was stopped to prevent duplicate connections.", false, .details)
        case .migrationCandidateFailed:
            ("新连接验证失败", "New connection validation failed", "新连接验证失败，已恢复原来的连接。", "The new connection failed validation, so the original connection was restored.", true, .retry)
        case .migrationRollbackFailed:
            ("自动恢复未完成", "Automatic recovery didn't finish", "自动恢复未完成，请按诊断步骤修复 Connector；Hermes 未被修改。", "Automatic recovery did not finish. Follow the diagnostic steps to repair the Connector; Hermes was not changed.", false, .details)
        case .migrationCleanupPending:
            ("升级完成，临时文件待清理", "Upgrade finished; cleanup is pending", "新连接已生效，但下载临时文件尚未清理。请重试清理；不要重复安装。", "The new connection is active, but temporary download files still need cleanup. Retry cleanup; do not install again.", true, .retry)
        }
    }

    public static func account(_ error: AccountClientError) -> DesktopIssue {
        switch error {
        case .remote(let remote):
            let code: DesktopIssueCode = switch remote.code {
            case "HR-AUTH-002": .googleSignInFailed
            case "HR-AUTH-003": .accountSessionExpired
            case "HR-AUTH-004": .accountSessionRevoked
            case "HR-AUTH-005": .refreshCredentialReused
            case "HR-AUTH-006": .reauthenticationRequired
            case "HR-AUTH-007": .signInRateLimited
            case "HR-AUTH-009": .invalidEmailCode
            case "HR-AUTH-010": .emailDeliveryFailed
            case "HR-AUTH-011": .emailSignInDisabled
            case "HR-ACCOUNT-001": .accountDisabled
            case "HR-ACCOUNT-003": .accountFeatureDisabled
            case "HR-ACCOUNT-004": .accountRequestInvalid
            case "HR-ACCOUNT-005": .accountIdempotencyConflict
            case "HR-ACCOUNT-006": .accountResourceNotFound
            case "HR-ACCOUNT-007": .desktopAccountRequired
            case "HR-ACCOUNT-009": .identityManagementDisabled
            case "HR-ACCOUNT-012": .accountDeletionPending
            case "HR-BIND-001": .bindingMissing
            case "HR-BIND-002": .bindingConflict
            case "HR-BIND-003": .bindingExpired
            case "HR-BIND-005": .bindingProofFailed
            case "HR-BIND-006": .bindingRevoked
            case "HR-BIND-007": .bindingReplacementFailed
            case "HR-BIND-008": .bindingFeatureDisabled
            case "HR-BIND-009": .deviceSelectionRequired
            case "HR-BIND-010": .ownedDeviceLimitReached
            case "HR-BIND-011": .deviceUnavailable
            case "HR-SHARE-001": .sharingFeatureDisabled
            case "HR-SHARE-002": .deviceSharingLimitReached
            case "HR-SHARE-003": .sharedDeviceLimitReached
            case "HR-SHARE-004": .shareInvitationInvalid
            case "HR-SHARE-005": .shareEmailMismatch
            case "HR-SHARE-006": .wholeDeviceAcknowledgementRequired
            case "HR-SHARE-007": .shareConflict
            case "HR-SHARE-008": .shareDeliveryFailed
            default: .accountServiceUnavailable
            }
            let cause = [
                remote.correlationId.map { "correlation_id=\($0)" },
                "remote_code=\(remote.code)",
            ]
                .compactMap { $0 }
                .joined(separator: " ")
            return DesktopIssue(code: code, technicalCause: cause)
        case .invalidConfiguration:
            return DesktopIssue(code: .accountConfigurationMissing)
        case .transport, .invalidResponse, .responseTooLarge:
            return DesktopIssue(code: .accountServiceUnavailable)
        }
    }

    public static func oauth(_ error: GoogleOAuthError) -> DesktopIssue {
        switch error {
        case .configurationMissing:
            DesktopIssue(code: .accountConfigurationMissing)
        default:
            DesktopIssue(code: .googleSignInInterrupted, technicalCause: String(describing: error))
        }
    }

    public static func migration(
        _ error: Error,
        terminalState: DesktopMigrationState?
    ) -> DesktopIssue {
        let code: DesktopIssueCode
        if terminalState == .rollbackAttentionRequired
            || error as? DesktopMigrationCoordinatorError == .rollbackFailed
            || error as? DesktopMigrationCoordinatorError == .commitAmbiguous {
            code = .migrationRollbackFailed
        } else if error as? DesktopLaunchAgentControllerError == .duplicateConnector {
            code = .migrationConnectorMismatch
        } else if terminalState == .legacyActive || terminalState == .cleanUninstalled {
            code = .migrationCandidateFailed
        } else {
            code = .migrationPreflightFailed
        }
        return DesktopIssue(code: code, technicalCause: String(describing: error))
    }
}
