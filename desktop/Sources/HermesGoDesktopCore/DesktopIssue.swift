import Foundation

public enum DesktopRecoveryAction: String, Codable, Equatable, Sendable {
    case retry
    case settings
    case startDesktop
    case details
    case signIn
    case continueLegacy
    case verifyAndReplace
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
    case accountDisabled = "HR-ACCOUNT-001"
    case accountServiceUnavailable = "HR-ACCOUNT-002"
    case accountFeatureDisabled = "HR-ACCOUNT-003"
    case accountRequestInvalid = "HR-ACCOUNT-004"
    case accountIdempotencyConflict = "HR-ACCOUNT-005"
    case accountResourceNotFound = "HR-ACCOUNT-006"
    case desktopAccountRequired = "HR-ACCOUNT-007"
    case bindingMissing = "HR-BIND-001"
    case bindingConflict = "HR-BIND-002"
    case bindingExpired = "HR-BIND-003"
    case bindingProofFailed = "HR-BIND-005"
    case bindingRevoked = "HR-BIND-006"
    case bindingReplacementFailed = "HR-BIND-007"
    case bindingFeatureDisabled = "HR-BIND-008"
    case relayFailure = "HR-RPC-001"
    case configurationLoadFailed = "HR-CONFIG-001"
    case configurationSaveFailed = "HR-CONFIG-002"
    case invalidRelayURL = "HR-CONFIG-003"
    case incompletePairingConfiguration = "HR-CONFIG-004"
    case pairingPayloadTooLarge = "HR-CONFIG-005"
    case accountConfigurationMissing = "HR-CONFIG-006"
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
            ("需要重新验证", "Verification required", "为确认是你本人，请重新验证 Google 账号。", "Verify your Google account again to confirm it's you.", false, .signIn)
        case .signInRateLimited:
            ("登录请求过于频繁", "Too many sign-in requests", "登录请求过于频繁，请稍候再试。", "Too many sign-in requests. Wait a moment and try again.", true, .retry)
        case .googleSignInInterrupted:
            ("Google 登录未完成", "Google sign-in didn't finish", "Google 登录未完成，请重新尝试。", "Google sign-in did not finish. Try again.", true, .signIn)
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
            ("Google 登录尚未配置", "Google sign-in isn't configured", "此版本尚未配置 Google 登录，请继续使用原有连接。", "Google sign-in is not configured in this build. Continue with the legacy connection.", false, .continueLegacy)
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
            case "HR-ACCOUNT-001": .accountDisabled
            case "HR-ACCOUNT-003": .accountFeatureDisabled
            case "HR-ACCOUNT-004": .accountRequestInvalid
            case "HR-ACCOUNT-005": .accountIdempotencyConflict
            case "HR-ACCOUNT-006": .accountResourceNotFound
            case "HR-ACCOUNT-007": .desktopAccountRequired
            case "HR-BIND-001": .bindingMissing
            case "HR-BIND-002": .bindingConflict
            case "HR-BIND-003": .bindingExpired
            case "HR-BIND-005": .bindingProofFailed
            case "HR-BIND-006": .bindingRevoked
            case "HR-BIND-007": .bindingReplacementFailed
            case "HR-BIND-008": .bindingFeatureDisabled
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
}

public enum DesktopAccountIssueReducer {
    public static func issue(
        afterApplying state: DesktopAccountState,
        existingIssue: DesktopIssue?,
        preserveSignedOutIssue: Bool
    ) -> DesktopIssue? {
        switch state {
        case .unavailable:
            DesktopIssue(code: .accountFeatureDisabled)
        case .needsSignIn(let issueCode):
            DesktopIssue(code: issueCode)
        case .signedOut where preserveSignedOutIssue:
            existingIssue
        default:
            nil
        }
    }
}
