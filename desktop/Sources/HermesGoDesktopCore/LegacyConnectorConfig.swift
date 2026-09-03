import Foundation

public struct LegacyConnectorConfig: Equatable, Sendable {
    public let gatewayURL: URL?
    public let deviceID: String
    public let hermesBaseURL: URL
    public let hermesMode: String
    public let observerEnabled: Bool

    public init(
        gatewayURL: URL?,
        deviceID: String = "mac-mini",
        hermesBaseURL: URL = URL(string: "http://127.0.0.1:9119")!,
        hermesMode: String = "live",
        observerEnabled: Bool = true
    ) {
        self.gatewayURL = gatewayURL
        self.deviceID = deviceID
        self.hermesBaseURL = hermesBaseURL
        self.hermesMode = hermesMode
        self.observerEnabled = observerEnabled
    }

    public var relayHealthURL: URL? {
        guard let gatewayURL,
              var components = URLComponents(url: gatewayURL, resolvingAgainstBaseURL: false)
        else { return nil }

        switch components.scheme?.lowercased() {
        case "wss": components.scheme = "https"
        case "ws": components.scheme = "http"
        case "https", "http": break
        default: return nil
        }
        components.path = "/relay-health"
        components.query = nil
        components.fragment = nil
        return components.url
    }

    public var hermesStatusURL: URL? {
        URL(string: "/api/status", relativeTo: hermesBaseURL)?.absoluteURL
    }
}

public enum LegacyConnectorConfigParser {
    private static let allowedKeys: Set<String> = [
        "GATEWAY_URL",
        "DEVICE_ID",
        "HERMES_BASE_URL",
        "HERMES_MODE",
        "SESSION_OBSERVER_ENABLED",
    ]

    public static func parse(_ text: String) -> LegacyConnectorConfig {
        var values: [String: String] = [:]

        for rawLine in text.split(whereSeparator: \.isNewline) {
            var line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("export ") {
                line.removeFirst("export ".count)
            }
            guard !line.isEmpty, !line.hasPrefix("#"), let separator = line.firstIndex(of: "=") else {
                continue
            }
            let key = String(line[..<separator]).trimmingCharacters(in: .whitespaces)
            guard allowedKeys.contains(key) else { continue }
            let rawValue = String(line[line.index(after: separator)...])
                .trimmingCharacters(in: .whitespaces)
            values[key] = unquote(rawValue)
        }

        return LegacyConnectorConfig(
            gatewayURL: values["GATEWAY_URL"].flatMap(URL.init(string:)),
            deviceID: nonEmpty(values["DEVICE_ID"]) ?? "mac-mini",
            hermesBaseURL: values["HERMES_BASE_URL"].flatMap(URL.init(string:))
                ?? URL(string: "http://127.0.0.1:9119")!,
            hermesMode: nonEmpty(values["HERMES_MODE"]) ?? "live",
            observerEnabled: values["SESSION_OBSERVER_ENABLED"] != "0"
        )
    }

    private static func unquote(_ value: String) -> String {
        guard value.count >= 2,
              let first = value.first,
              let last = value.last,
              (first == "\"" && last == "\"") || (first == "'" && last == "'")
        else { return value }
        return String(value.dropFirst().dropLast())
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
