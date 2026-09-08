import Foundation

public protocol DeviceSelectionStoring: Sendable {
    func load(accountID: String) -> String?
    func save(_ deviceID: String?, accountID: String)
}

public struct UserDefaultsDeviceSelectionStore: DeviceSelectionStoring, @unchecked Sendable {
    private let defaults: UserDefaults
    private let keyPrefix: String

    public init(
        defaults: UserDefaults = .standard,
        keyPrefix: String = "com.hermesgo.desktop.selected-device"
    ) {
        self.defaults = defaults
        self.keyPrefix = keyPrefix
    }

    public func load(accountID: String) -> String? {
        guard let accountID = normalizedAccountID(accountID),
              let value = defaults.string(forKey: "\(keyPrefix).\(accountID)"),
              isValidDeviceID(value)
        else { return nil }
        return value
    }

    public func save(_ deviceID: String?, accountID: String) {
        guard let accountID = normalizedAccountID(accountID) else { return }
        let key = "\(keyPrefix).\(accountID)"
        guard let deviceID else {
            defaults.removeObject(forKey: key)
            return
        }
        guard isValidDeviceID(deviceID) else { return }
        defaults.set(deviceID, forKey: key)
    }

    private func normalizedAccountID(_ value: String) -> String? {
        UUID(uuidString: value)?.uuidString.lowercased()
    }
}

func isValidDeviceID(_ value: String) -> Bool {
    guard (1...128).contains(value.utf8.count) else { return false }
    let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
    return value.unicodeScalars.allSatisfy(allowed.contains)
}
