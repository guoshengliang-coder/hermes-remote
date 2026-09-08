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
    public struct DesktopBootstrap: Codable, Equatable, Sendable {
        public let runtimeContract: String

        public init(runtimeContract: String) {
            self.runtimeContract = runtimeContract
        }
    }

    public struct AccountAuthentication: Codable, Equatable, Sendable {
        public let enabled: Bool
        public let providers: [String]
        public let android: Bool
        public let macos: Bool
        public let identityManagement: Bool?
        public let accountDeletion: Bool?
        public let webAccountCenter: Bool?

        public init(
            enabled: Bool,
            providers: [String],
            android: Bool,
            macos: Bool,
            identityManagement: Bool? = nil,
            accountDeletion: Bool? = nil,
            webAccountCenter: Bool? = nil
        ) {
            self.enabled = enabled
            self.providers = providers
            self.android = android
            self.macos = macos
            self.identityManagement = identityManagement
            self.accountDeletion = accountDeletion
            self.webAccountCenter = webAccountCenter
        }
    }

    public struct Binding: Codable, Equatable, Sendable {
        public let enabled: Bool
        public let replacement: Bool
        public let maxActiveConnectorsPerAccount: Int
        public let supportsDeviceSelection: Bool?
        public let supportsDeviceSharing: Bool?
        public let maxSharedDevices: Int?
        public let maxGranteesPerDevice: Int?

        public init(
            enabled: Bool,
            replacement: Bool,
            maxActiveConnectorsPerAccount: Int,
            supportsDeviceSelection: Bool? = nil,
            supportsDeviceSharing: Bool? = nil,
            maxSharedDevices: Int? = nil,
            maxGranteesPerDevice: Int? = nil
        ) {
            self.enabled = enabled
            self.replacement = replacement
            self.maxActiveConnectorsPerAccount = maxActiveConnectorsPerAccount
            self.supportsDeviceSelection = supportsDeviceSelection
            self.supportsDeviceSharing = supportsDeviceSharing
            self.maxSharedDevices = maxSharedDevices
            self.maxGranteesPerDevice = maxGranteesPerDevice
        }
    }

    public struct Legacy: Codable, Equatable, Sendable {
        public let appTokenAccepted: Bool
        public let connectorTokenAccepted: Bool
    }

    public let version: Int
    public let accountAuth: AccountAuthentication
    public let binding: Binding
    public let legacy: Legacy
    public let desktopBootstrap: DesktopBootstrap?

    public init(
        version: Int,
        accountAuth: AccountAuthentication,
        binding: Binding,
        legacy: Legacy,
        desktopBootstrap: DesktopBootstrap? = nil
    ) {
        self.version = version
        self.accountAuth = accountAuth
        self.binding = binding
        self.legacy = legacy
        self.desktopBootstrap = desktopBootstrap
    }
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
    public let pendingReauthenticationGrants: [String: String]?

    public init(
        account: HermesAccount,
        installation: AccountInstallation,
        session: AccountSessionTokens,
        pendingRefreshIdempotencyKey: String? = nil,
        pendingOperationIdempotencyKeys: [String: String]? = nil,
        pendingReauthenticationGrants: [String: String]? = nil
    ) {
        self.account = account
        self.installation = installation
        self.session = session
        self.pendingRefreshIdempotencyKey = pendingRefreshIdempotencyKey
        self.pendingOperationIdempotencyKeys = pendingOperationIdempotencyKeys
        self.pendingReauthenticationGrants = pendingReauthenticationGrants
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

public struct AccountBindingSnapshot: Codable, Equatable, Sendable {
    public let state: String
    public let id: String?
    public let generation: Int?
    public let deviceId: String?
    public let displayName: String?
    public let publicKeyFingerprint: String?
    public let expiresAt: String?
    public let keyProved: Bool?
    public let healthVerified: Bool?
    public let binding: ActiveAccountBinding?
    public let previousBinding: ActiveAccountBinding?

    public init(
        state: String,
        id: String?,
        generation: Int?,
        deviceId: String?,
        displayName: String?,
        publicKeyFingerprint: String? = nil,
        expiresAt: String?,
        keyProved: Bool?,
        healthVerified: Bool?,
        binding: ActiveAccountBinding?,
        previousBinding: ActiveAccountBinding?
    ) {
        self.state = state
        self.id = id
        self.generation = generation
        self.deviceId = deviceId
        self.displayName = displayName
        self.publicKeyFingerprint = publicKeyFingerprint
        self.expiresAt = expiresAt
        self.keyProved = keyProved
        self.healthVerified = healthVerified
        self.binding = binding
        self.previousBinding = previousBinding
    }
}

public struct AccountDevice: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let generation: Int
    public let deviceId: String
    public let desktopDisplayName: String
    public let publicKeyFingerprint: String
    public let connector: ActiveAccountBinding.Connector
    public let hermes: ActiveAccountBinding.Hermes
    public let gateway: ActiveAccountBinding.Gateway
    public let endToEnd: ActiveAccountBinding.EndToEnd
    public let access: String
    public let isDefault: Bool
}

public struct AccountDevicePage: Codable, Equatable, Sendable {
    public let items: [AccountDevice]
    public let maxOwnedDevices: Int
}

public struct AccountDeviceEnvelope: Codable, Equatable, Sendable {
    public let device: AccountDevice
}

public struct AccountReauthenticationGrant: Codable, Equatable, Sendable {
    public let grant: String
    public let scope: String
    public let expiresAt: String
}

public struct EmailOtpChallenge: Codable, Equatable, Sendable {
    public let challengeId: String
    public let expiresAt: String
    public let resendAfter: String

    public init(challengeId: String, expiresAt: String, resendAfter: String) {
        self.challengeId = challengeId
        self.expiresAt = expiresAt
        self.resendAfter = resendAfter
    }
}

public struct DesktopEmailVerificationChallenge: Equatable, Sendable {
    public let challenge: EmailOtpChallenge
    public let email: String
    public let idempotencyKey: String

    public init(challenge: EmailOtpChallenge, email: String, idempotencyKey: String) {
        self.challenge = challenge
        self.email = email
        self.idempotencyKey = idempotencyKey
    }
}

public struct DeviceShareInvitation: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let deviceId: String
    public let targetEmailHint: String
    public let status: String
    public let expiresAt: String
    public let createdAt: String
}

public struct DeviceAccessGrant: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let deviceId: String
    public let granteeEmailHint: String
    public let role: String
    public let status: String
    public let grantedAt: String
}

public struct DeviceShareManagement: Codable, Equatable, Sendable {
    public let invitations: [DeviceShareInvitation]
    public let grants: [DeviceAccessGrant]
    public let maxGranteesPerDevice: Int
}

public struct DeviceShareInvitationEnvelope: Codable, Equatable, Sendable {
    public let invitation: DeviceShareInvitation
}

public struct AccountDashboard: Equatable, Sendable {
    public let session: AccountSessionRecord
    public let binding: AccountBindingSnapshot
    public let installations: [ManagedAccountInstallation]
    public let devices: [AccountDevice]
    public let maxOwnedDevices: Int
    public let selectedDeviceID: String?
    public let deviceShares: [String: DeviceShareManagement]
    public let supportsDeviceSharing: Bool
    public let maxSharedDevices: Int
    public let accountDeletionEnabled: Bool
    public let desktopBootstrapRuntimeContract: String?

    public init(
        session: AccountSessionRecord,
        binding: AccountBindingSnapshot,
        installations: [ManagedAccountInstallation],
        devices: [AccountDevice] = [],
        maxOwnedDevices: Int = 1,
        selectedDeviceID: String? = nil,
        deviceShares: [String: DeviceShareManagement] = [:],
        supportsDeviceSharing: Bool = false,
        maxSharedDevices: Int = 0,
        accountDeletionEnabled: Bool = false,
        desktopBootstrapRuntimeContract: String? = nil
    ) {
        self.session = session
        self.binding = binding
        self.installations = installations
        self.devices = devices
        self.maxOwnedDevices = maxOwnedDevices
        self.selectedDeviceID = selectedDeviceID
        self.deviceShares = deviceShares
        self.supportsDeviceSharing = supportsDeviceSharing
        self.maxSharedDevices = maxSharedDevices
        self.accountDeletionEnabled = accountDeletionEnabled
        self.desktopBootstrapRuntimeContract = desktopBootstrapRuntimeContract
    }

    public var phones: [ManagedAccountInstallation] {
        installations.filter { $0.kind == "phone" }
    }

    public var selectedDevice: AccountDevice? {
        devices.first { $0.deviceId == selectedDeviceID }
    }

    public var localDeviceID: String? {
        binding.binding?.deviceId ?? binding.previousBinding?.deviceId ?? binding.deviceId
    }

    public var ownedDevices: [AccountDevice] { devices.filter { $0.access == "owner" } }

    public var sharedDevices: [AccountDevice] { devices.filter { $0.access == "operator" } }
}

public struct DesktopBindingPreparation: Equatable, Sendable {
    public let state: DesktopAccountState
    public let credential: AccountConnectorCredentialPayload

    public init(state: DesktopAccountState, credential: AccountConnectorCredentialPayload) {
        self.state = state
        self.credential = credential
    }
}

public enum DesktopAccountState: Equatable, Sendable {
    case checking
    case unavailable
    case signedOut
    case accountDeletionSubmitted
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
