import Foundation

public struct DesktopAccountConfiguration: Equatable, Sendable {
    public let gatewayURL: URL
    public let googleClientID: String?

    public init(gatewayURL: URL, googleClientID: String?) {
        self.gatewayURL = gatewayURL
        let normalized = googleClientID?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.googleClientID = normalized.flatMap {
            guard (8...512).contains($0.count),
                  !$0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
            else { return nil }
            return $0.nilIfEmpty
        }
    }

    public static func load(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> DesktopAccountConfiguration {
        let gatewayValue = environment["HERMES_GO_ACCOUNT_GATEWAY_URL"]
            ?? bundle.object(forInfoDictionaryKey: "HermesGoAccountGatewayURL") as? String
            ?? "https://mrlgs.net"
        let clientID = environment["HERMES_GO_GOOGLE_MACOS_CLIENT_ID"]
            ?? bundle.object(forInfoDictionaryKey: "HermesGoGoogleMacOSClientID") as? String
        let gatewayURL = validatedAccountGatewayURL(gatewayValue)
            ?? URL(string: "https://mrlgs.net")!
        return DesktopAccountConfiguration(gatewayURL: gatewayURL, googleClientID: clientID)
    }
}

public struct AccountCapabilities: Codable, Equatable, Sendable {
    public struct AccountAuthentication: Codable, Equatable, Sendable {
        public let enabled: Bool
        public let providers: [String]
        public let android: Bool
        public let macos: Bool
    }

    public struct Binding: Codable, Equatable, Sendable {
        public let enabled: Bool
        public let replacement: Bool
        public let maxActiveConnectorsPerAccount: Int
    }

    public struct Legacy: Codable, Equatable, Sendable {
        public let appTokenAccepted: Bool
        public let connectorTokenAccepted: Bool
    }

    public let version: Int
    public let accountAuth: AccountAuthentication
    public let binding: Binding
    public let legacy: Legacy
}

public struct HermesAccount: Codable, Equatable, Sendable {
    public let id: String
    public let displayName: String?
    public let email: String?
    public let avatarUrl: String?
}

public struct AccountInstallation: Codable, Equatable, Sendable {
    public let id: String
    public let kind: String
    public let platform: String
    public let displayName: String
}

public struct AccountSessionTokens: Codable, Equatable, Sendable {
    public let accessToken: String
    public let accessExpiresAt: String
    public let refreshToken: String
    public let refreshExpiresAt: String

    public var shouldRefresh: Bool {
        guard let expiry = parseRFC3339(accessExpiresAt) else { return true }
        return expiry.timeIntervalSinceNow <= 60
    }
}

public struct AccountSessionRecord: Codable, Equatable, Sendable {
    public let account: HermesAccount
    public let installation: AccountInstallation
    public let session: AccountSessionTokens
    public let pendingRefreshIdempotencyKey: String?
    public let pendingOperationIdempotencyKeys: [String: String]?

    public init(
        account: HermesAccount,
        installation: AccountInstallation,
        session: AccountSessionTokens,
        pendingRefreshIdempotencyKey: String? = nil,
        pendingOperationIdempotencyKeys: [String: String]? = nil
    ) {
        self.account = account
        self.installation = installation
        self.session = session
        self.pendingRefreshIdempotencyKey = pendingRefreshIdempotencyKey
        self.pendingOperationIdempotencyKeys = pendingOperationIdempotencyKeys
    }
}

public struct AccountSnapshot: Codable, Equatable, Sendable {
    public struct Session: Codable, Equatable, Sendable {
        public let authenticated: Bool
        public let recentReauthentication: Bool
    }

    public let account: HermesAccount
    public let installation: AccountInstallation
    public let session: Session
}

public struct ManagedAccountInstallation: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let kind: String
    public let platform: String
    public let displayName: String
    public let lastSeenAt: String
    public let status: String
    public let current: Bool
}

public struct ManagedInstallationPage: Codable, Equatable, Sendable {
    public let items: [ManagedAccountInstallation]
}

public struct ActiveAccountBinding: Codable, Equatable, Sendable {
    public struct Connector: Codable, Equatable, Sendable {
        public let online: Bool
        public let lastSeenAt: String?
    }

    public struct Hermes: Codable, Equatable, Sendable {
        public let reachable: Bool?
        public let version: String?
    }

    public struct Gateway: Codable, Equatable, Sendable {
        public let latencyMs: Int?
    }

    public struct EndToEnd: Codable, Equatable, Sendable {
        public let healthy: Bool?
        public let checkedAt: String?
    }

    public let id: String
    public let generation: Int
    public let deviceId: String
    public let desktopDisplayName: String
    public let publicKeyFingerprint: String
    public let connector: Connector
    public let hermes: Hermes
    public let gateway: Gateway
    public let endToEnd: EndToEnd
}

public enum AccountBindingState: Equatable, Sendable {
    case noBinding
    case bindingPending
    case bound
    case replacementPending
    case revoked
    case unknown(String)

    init(wireValue: String) {
        self = switch wireValue {
        case "no_binding": .noBinding
        case "binding_pending": .bindingPending
        case "bound": .bound
        case "replacement_pending": .replacementPending
        case "revoked": .revoked
        default: .unknown(wireValue)
        }
    }
}

public struct AccountBindingSnapshot: Codable, Equatable, Sendable {
    public let state: String
    public let id: String?
    public let generation: Int?
    public let deviceId: String?
    public let displayName: String?
    public let expiresAt: String?
    public let keyProved: Bool?
    public let healthVerified: Bool?
    public let binding: ActiveAccountBinding?
    public let previousBinding: ActiveAccountBinding?

    public var bindingState: AccountBindingState {
        AccountBindingState(wireValue: state)
    }
}

public struct AccountDashboard: Equatable, Sendable {
    public let session: AccountSessionRecord
    public let binding: AccountBindingSnapshot
    public let installations: [ManagedAccountInstallation]

    public var phones: [ManagedAccountInstallation] {
        installations.filter { $0.kind == "phone" }
    }
}

public enum DesktopAccountState: Equatable, Sendable {
    case checking
    case unavailable
    case signedOut
    case signingIn
    case signedIn(AccountDashboard)
    case needsSignIn(DesktopIssueCode)
}

public struct AccountRemoteError: Error, Codable, Equatable, Sendable {
    public let code: String
    public let message: String
    public let retryable: Bool
    public let recoveryAction: String
    public let correlationId: String?
}

public enum AccountClientError: Error, Equatable, Sendable {
    case invalidConfiguration
    case transport
    case invalidResponse
    case responseTooLarge
    case remote(AccountRemoteError)
}

public enum GoogleOAuthError: Error, Equatable, Sendable {
    case configurationMissing
    case browserUnavailable
    case callbackListenerFailed
    case callbackTimedOut
    case callbackRejected
    case cancelled
    case tokenExchangeFailed
    case invalidTokenResponse
}

public struct GoogleIdentityProof: Equatable, Sendable {
    public let idToken: String
    public let nonce: String

    public init(idToken: String, nonce: String) {
        self.idToken = idToken
        self.nonce = nonce
    }
}

private func validatedAccountGatewayURL(_ value: String) -> URL? {
    guard var components = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
          components.user == nil,
          components.password == nil,
          components.query == nil,
          components.fragment == nil,
          let host = components.host,
          !host.isEmpty
    else { return nil }
    let isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    guard components.scheme?.lowercased() == "https"
        || (components.scheme?.lowercased() == "http" && isLoopback)
    else { return nil }
    guard components.path.isEmpty || components.path == "/" else { return nil }
    components.path = ""
    return components.url
}

private func parseRFC3339(_ value: String) -> Date? {
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let value = fractional.date(from: value) { return value }
    return ISO8601DateFormatter().date(from: value)
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
