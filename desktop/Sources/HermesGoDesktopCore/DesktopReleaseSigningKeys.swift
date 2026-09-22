import Foundation

enum DesktopReleaseSigningKeys {
    static let maximumKeys = 8

    static func parse(
        json: String?,
        legacyKeyID: String?,
        legacyPublicKey: String?
    ) -> [String: Data]? {
        let encodedJSON = json?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let legacyID = legacyKeyID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let legacyKey = legacyPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

        if !encodedJSON.isEmpty {
            guard legacyID.isEmpty, legacyKey.isEmpty,
                  let data = encodedJSON.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data),
                  let values = object as? [String: String],
                  !values.isEmpty, values.count <= maximumKeys
            else { return nil }
            var result: [String: Data] = [:]
            for (keyID, encodedKey) in values {
                guard validIdentifier(keyID),
                      let key = Data(desktopCanonicalBase64URL: encodedKey),
                      key.count == 32,
                      result.updateValue(key, forKey: keyID) == nil
                else { return nil }
            }
            return result
        }

        guard validIdentifier(legacyID),
              let key = Data(desktopCanonicalBase64URL: legacyKey),
              key.count == 32
        else { return nil }
        return [legacyID: key]
    }

    static func valid(_ values: [String: Data]) -> Bool {
        !values.isEmpty && values.count <= maximumKeys && values.allSatisfy {
            validIdentifier($0.key) && $0.value.count == 32
        }
    }

    private static func validIdentifier(_ value: String) -> Bool {
        guard (1...64).contains(value.utf8.count) else { return false }
        let allowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._"
        )
        return value.unicodeScalars.allSatisfy(allowed.contains)
    }
}

extension Data {
    init?(desktopCanonicalBase64URL value: String) {
        guard !value.isEmpty,
              value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil
        else { return nil }
        let padding = String(repeating: "=", count: (4 - value.count % 4) % 4)
        let standard = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + padding
        guard let decoded = Data(base64Encoded: standard),
              decoded.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "") == value
        else { return nil }
        self = decoded
    }
}
