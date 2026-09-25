import Foundation

public struct DesktopAppUpdateConfiguration: Equatable, Sendable {
    public let indexURL: URL
    public let channel: String
    public let architecture: String

    public init(indexURL: URL, channel: String, architecture: String) throws {
        guard Self.validIndexURL(indexURL),
              Self.validIdentifier(channel, maximum: 32),
              ["arm64", "x86_64", "universal"].contains(architecture)
        else { throw DesktopAppUpdateIndexError.invalidIndex }
        self.indexURL = indexURL
        self.channel = channel
        self.architecture = architecture
    }

    private static func validIndexURL(_ value: URL) -> Bool {
        guard value.scheme == "https", value.host != nil,
              value.user == nil, value.password == nil,
              value.query == nil, value.fragment == nil,
              value.lastPathComponent == "index.json",
              !value.pathComponents.contains(".."),
              let encoded = URLComponents(url: value, resolvingAgainstBaseURL: false)?
                .percentEncodedPath.lowercased(),
              !encoded.contains("%2f"), !encoded.contains("%5c")
        else { return false }
        return value.path != "/"
    }

    private static func validIdentifier(_ value: String, maximum: Int) -> Bool {
        guard (1...maximum).contains(value.utf8.count) else { return false }
        let allowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._"
        )
        return value.unicodeScalars.allSatisfy(allowed.contains)
    }
}

public enum DesktopAppUpdateConfigurationState: Equatable, Sendable {
    case disabled
    case invalid
    case configured(DesktopAppUpdateConfiguration)

    public static func load(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> DesktopAppUpdateConfigurationState {
        let values = DesktopAppUpdateConfigurationValues(bundle: bundle, environment: environment)
        guard let enabled = values.enabled else { return .disabled }
        guard enabled == "1" else { return enabled == "0" ? .disabled : .invalid }
        guard let indexURL = values.indexURL.flatMap(URL.init(string:)),
              let channel = values.channel,
              let architecture = values.architecture
        else { return .invalid }
        do {
            return .configured(try DesktopAppUpdateConfiguration(
                indexURL: indexURL,
                channel: channel,
                architecture: architecture
            ))
        } catch {
            return .invalid
        }
    }
}

/// Whether the app checks for updates on its own. Defaults to on; the owner can switch it off in
/// Settings, and a build without update configuration never checks regardless.
public enum DesktopAutomaticUpdateSetting {
    public static let key = "HermesGoDesktopAutomaticUpdateChecksEnabled"

    public static func isEnabled(defaults: UserDefaults = .standard) -> Bool {
        guard defaults.object(forKey: key) != nil else { return true }
        return defaults.bool(forKey: key)
    }

    public static func setEnabled(_ enabled: Bool, defaults: UserDefaults = .standard) {
        defaults.set(enabled, forKey: key)
    }
}

private struct DesktopAppUpdateConfigurationValues {
    let enabled: String?
    let indexURL: String?
    let channel: String?
    let architecture: String?

    init(bundle: Bundle, environment: [String: String]) {
        func value(_ environmentKey: String, _ bundleKey: String) -> String? {
            if let environmentValue = environment[environmentKey] { return environmentValue }
            if let string = bundle.object(forInfoDictionaryKey: bundleKey) as? String { return string }
            if let number = bundle.object(forInfoDictionaryKey: bundleKey) as? NSNumber {
                return number.boolValue ? "1" : "0"
            }
            return nil
        }
        enabled = value("HERMES_GO_APP_UPDATE_ENABLED", "HermesGoDesktopAppUpdateEnabled")
        indexURL = value("HERMES_GO_APP_UPDATE_INDEX_URL", "HermesGoDesktopAppUpdateIndexURL")
        channel = value("HERMES_GO_APP_UPDATE_CHANNEL", "HermesGoDesktopAppUpdateChannel")
        architecture = value("HERMES_GO_APP_UPDATE_ARCHITECTURE", "HermesGoDesktopAppUpdateArchitecture")
    }
}
