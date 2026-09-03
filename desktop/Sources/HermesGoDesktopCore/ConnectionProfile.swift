import Foundation

public enum ConnectionProfileValidationError: Error, Equatable, Sendable {
    case invalidName
    case invalidGatewayURL
    case missingToken
    case pairingPayloadTooLarge
}

public struct ConnectionProfile: Codable, Equatable, Sendable {
    public let name: String
    public let gatewayURL: URL
    public let appToken: String

    public init(name: String, gatewayURL: URL, appToken: String) {
        self.name = name
        self.gatewayURL = gatewayURL
        self.appToken = appToken
    }

    public static func validated(
        name rawName: String,
        gatewayAddress rawGatewayAddress: String,
        appToken rawAppToken: String
    ) throws -> ConnectionProfile {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name.count <= 64 else {
            throw ConnectionProfileValidationError.invalidName
        }

        let token = rawAppToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, token.count <= 4_096 else {
            throw ConnectionProfileValidationError.missingToken
        }

        guard let gatewayURL = normalizeGatewayURL(rawGatewayAddress) else {
            throw ConnectionProfileValidationError.invalidGatewayURL
        }

        let profile = ConnectionProfile(name: name, gatewayURL: gatewayURL, appToken: token)
        guard let payload = profile.pairingPayloadData, payload.count <= 2_000 else {
            throw ConnectionProfileValidationError.pairingPayloadTooLarge
        }
        return profile
    }

    public var pairingPayloadData: Data? {
        let payload = PairingPayload(version: 1, url: gatewayURL.absoluteString, token: appToken)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try? encoder.encode(payload)
    }

    public var pairingPayload: String? {
        pairingPayloadData.flatMap { String(data: $0, encoding: .utf8) }
    }
}

private struct PairingPayload: Codable {
    let version: Int
    let url: String
    let token: String

    enum CodingKeys: String, CodingKey {
        case version = "v"
        case url
        case token
    }
}

private func normalizeGatewayURL(_ rawValue: String) -> URL? {
    let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty, trimmed.count <= 2_048,
          var components = URLComponents(string: trimmed),
          let scheme = components.scheme?.lowercased(),
          scheme == "https" || scheme == "http",
          let host = components.host, !host.isEmpty,
          components.user == nil,
          components.password == nil,
          components.query == nil,
          components.fragment == nil
    else { return nil }

    guard scheme == "https" || isLocalAddress(host) else { return nil }
    components.scheme = scheme
    if components.path == "/" { components.path = "" }
    while components.path.count > 1 && components.path.hasSuffix("/") {
        components.path.removeLast()
    }
    return components.url
}

private func isLocalAddress(_ host: String) -> Bool {
    let normalized = host
        .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        .lowercased()
    if normalized == "localhost" || normalized == "::1" { return true }

    let octets = normalized.split(separator: ".").compactMap { Int($0) }
    guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else {
        return false
    }
    return octets[0] == 127
        || octets[0] == 10
        || (octets[0] == 172 && (16...31).contains(octets[1]))
        || (octets[0] == 192 && octets[1] == 168)
        || (octets[0] == 100 && (64...127).contains(octets[1]))
}
