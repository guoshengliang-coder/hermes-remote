import CryptoKit
import Foundation
import Security

public protocol AccountSessionStoring: Sendable {
    func load() throws -> AccountSessionRecord?
    func save(_ record: AccountSessionRecord) throws
    func delete() throws
}

public protocol ConnectorMachineIdentityStoring: Sendable {
    func loadOrCreate() throws -> ConnectorMachineIdentity
    func delete() throws
}

public enum AccountSecretStoreError: Error, Equatable, Sendable {
    case encoding
    case decoding
    case invalidMachineIdentity
    case randomGeneration(OSStatus)
    case keychain(OSStatus)
}

public struct ConnectorMachineIdentity: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let clientInstallationID: String
    private let privateKeyData: Data

    public init() {
        clientInstallationID = UUID().uuidString.lowercased()
        privateKeyData = Curve25519.Signing.PrivateKey().rawRepresentation
    }

    init(clientInstallationID: String) throws {
        guard UUID(uuidString: clientInstallationID) != nil else {
            throw AccountSecretStoreError.invalidMachineIdentity
        }
        self.clientInstallationID = clientInstallationID.lowercased()
        privateKeyData = Curve25519.Signing.PrivateKey().rawRepresentation
    }

    init(clientInstallationID: String, privateKeyData: Data) throws {
        guard UUID(uuidString: clientInstallationID) != nil,
              (try? Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyData)) != nil
        else { throw AccountSecretStoreError.invalidMachineIdentity }
        self.clientInstallationID = clientInstallationID.lowercased()
        self.privateKeyData = privateKeyData
    }

    public var connectorPublicKey: String {
        guard let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyData) else {
            return ""
        }
        return key.publicKey.rawRepresentation.base64URLEncodedString()
    }

    public var connectorPublicKeyFingerprint: String {
        guard let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyData) else {
            return ""
        }
        return SHA256.hash(data: key.publicKey.rawRepresentation)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    public func sign(_ message: Data) throws -> Data {
        try Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyData).signature(for: message)
    }

    public var description: String {
        "ConnectorMachineIdentity(clientInstallationID: \(clientInstallationID), key: <redacted>)"
    }

    public var debugDescription: String { description }
}

public struct KeychainAccountSessionStore: AccountSessionStoring {
    private let store: KeychainDataStore

    public init(
        service: String = "com.hermesgo.desktop.account-session",
        account: String = "current"
    ) {
        store = KeychainDataStore(service: service, account: account)
    }

    public func load() throws -> AccountSessionRecord? {
        guard let data = try store.load() else { return nil }
        guard let record = try? JSONDecoder().decode(AccountSessionRecord.self, from: data) else {
            throw AccountSecretStoreError.decoding
        }
        return record
    }

    public func save(_ record: AccountSessionRecord) throws {
        guard let data = try? JSONEncoder().encode(record) else {
            throw AccountSecretStoreError.encoding
        }
        try store.save(data)
    }

    public func delete() throws {
        try store.delete()
    }
}

public struct KeychainConnectorMachineIdentityStore: ConnectorMachineIdentityStoring {
    private let store: KeychainDataStore

    public init(
        service: String = "com.hermesgo.desktop.connector-machine-key",
        account: String = "current"
    ) {
        store = KeychainDataStore(service: service, account: account)
    }

    public func loadOrCreate() throws -> ConnectorMachineIdentity {
        if let data = try store.load() {
            guard let decoded = try? JSONDecoder().decode(StoredMachineIdentity.self, from: data) else {
                throw AccountSecretStoreError.decoding
            }
            return try ConnectorMachineIdentity(
                clientInstallationID: decoded.clientInstallationID,
                privateKeyData: decoded.privateKeyData
            )
        }

        let identity = ConnectorMachineIdentity()
        let stored = StoredMachineIdentity(
            clientInstallationID: identity.clientInstallationID,
            privateKeyData: identity.privateKeyDataForStorage
        )
        guard let data = try? JSONEncoder().encode(stored) else {
            throw AccountSecretStoreError.encoding
        }
        try store.save(data)
        return identity
    }

    public func delete() throws {
        try store.delete()
    }
}

private struct StoredMachineIdentity: Codable {
    let clientInstallationID: String
    let privateKeyData: Data
}

private extension ConnectorMachineIdentity {
    var privateKeyDataForStorage: Data { privateKeyData }
}

private struct KeychainDataStore: @unchecked Sendable {
    let service: String
    let account: String

    func load() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw AccountSecretStoreError.keychain(status)
        }
        return data
    }

    func save(_ data: Data) throws {
        let status = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else {
            throw AccountSecretStoreError.keychain(status)
        }

        var item = baseQuery
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(item as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw AccountSecretStoreError.keychain(addStatus)
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw AccountSecretStoreError.keychain(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
        ]
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
