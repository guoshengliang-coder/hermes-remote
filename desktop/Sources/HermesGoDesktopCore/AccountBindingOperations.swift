import Foundation

public enum AccountReauthenticationScope: String, Codable, Equatable, Sendable {
    case connectorReplace = "connector.replace"
    case connectorUnbind = "connector.unbind"
}

public struct AccountReauthenticationGrant: Codable, Equatable, Sendable {
    public let grant: String
    public let scope: AccountReauthenticationScope
    public let expiresAt: String
}

public struct ConnectorBindingInput: Equatable, Sendable {
    public let desktopInstallationID: String
    public let displayName: String
    public let connectorPublicKey: String

    public init(
        desktopInstallationID: String,
        displayName: String,
        connectorPublicKey: String
    ) {
        self.desktopInstallationID = desktopInstallationID
        self.displayName = displayName
        self.connectorPublicKey = connectorPublicKey
    }
}

public struct AccountBindingCandidate: Codable, Equatable, Sendable {
    public let id: String
    public let generation: Int
    public let deviceId: String
    public let displayName: String
    public let publicKeyFingerprint: String
    public let state: String
    public let expiresAt: String
    public let keyProved: Bool
    public let healthVerified: Bool
}

public struct AccountReplacementRequest: Codable, Equatable, Sendable {
    public let id: String
    public let state: String
    public let expiresAt: String
    public let previousBinding: ActiveAccountBinding
    public let candidate: AccountBindingCandidate
}
