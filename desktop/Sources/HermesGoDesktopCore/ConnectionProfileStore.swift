import Foundation
import Security

public protocol ConnectionProfileStoring {
    func load() throws -> ConnectionProfile?
    func save(_ profile: ConnectionProfile) throws
    func delete() throws
}

public enum ConnectionProfileStoreError: Error, Equatable, Sendable {
    case encoding
    case decoding
    case keychain(OSStatus)
}

public struct KeychainConnectionProfileStore: ConnectionProfileStoring {
    private let service: String
    private let account: String

    public init(
        service: String = "com.hermesgo.desktop.connection-profile",
        account: String = "default"
    ) {
        self.service = service
        self.account = account
    }

    public func load() throws -> ConnectionProfile? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw ConnectionProfileStoreError.keychain(status)
        }
        guard let data = result as? Data,
              let profile = try? JSONDecoder().decode(ConnectionProfile.self, from: data)
        else { throw ConnectionProfileStoreError.decoding }
        return profile
    }

    public func save(_ profile: ConnectionProfile) throws {
        guard let data = try? JSONEncoder().encode(profile) else {
            throw ConnectionProfileStoreError.encoding
        }

        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw ConnectionProfileStoreError.keychain(updateStatus)
        }

        var item = baseQuery
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(item as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw ConnectionProfileStoreError.keychain(addStatus)
        }
    }

    public func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw ConnectionProfileStoreError.keychain(status)
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
