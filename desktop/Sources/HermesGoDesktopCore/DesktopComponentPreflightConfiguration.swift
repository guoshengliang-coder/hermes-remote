import Foundation

public struct DesktopComponentPreflightConfiguration: Equatable, Sendable {
    public let manifestURL: URL
    public let artifactOrigin: URL
    public let channel: String
    public let architecture: String
    public let signingKeyID: String
    public let signingPublicKey: Data

    public init(
        manifestURL: URL,
        artifactOrigin: URL,
        channel: String,
        architecture: String,
        signingKeyID: String,
        signingPublicKey: Data
    ) throws {
        guard Self.validHTTPSURL(manifestURL, requirePath: true),
              Self.validHTTPSURL(artifactOrigin, requirePath: false),
              artifactOrigin.path.isEmpty || artifactOrigin.path == "/",
              Self.validIdentifier(channel, maximum: 32),
              ["arm64", "x86_64", "universal"].contains(architecture),
              Self.validIdentifier(signingKeyID, maximum: 64),
              signingPublicKey.count == 32
        else { throw DesktopComponentReleaseVerificationError.invalidConfiguration }
        self.manifestURL = manifestURL
        self.artifactOrigin = artifactOrigin
        self.channel = channel
        self.architecture = architecture
        self.signingKeyID = signingKeyID
        self.signingPublicKey = signingPublicKey
    }

    public func makeManifestVerifier(
        currentMacOS: OperatingSystemVersion = ProcessInfo.processInfo.operatingSystemVersion,
        now: @escaping @Sendable () -> Date = { Date() }
    ) throws -> DesktopComponentReleaseManifestV2Verifier {
        try DesktopComponentReleaseManifestV2Verifier(
            expectedOrigin: artifactOrigin,
            expectedChannel: channel,
            expectedArchitecture: architecture,
            currentMacOS: currentMacOS,
            signingKeys: [signingKeyID: signingPublicKey],
            now: now
        )
    }

    private static func validHTTPSURL(_ value: URL, requirePath: Bool) -> Bool {
        guard value.scheme == "https", value.host != nil,
              value.user == nil, value.password == nil,
              value.query == nil, value.fragment == nil,
              !value.pathComponents.contains(".."),
              let encodedPath = URLComponents(url: value, resolvingAgainstBaseURL: false)?
                .percentEncodedPath.lowercased(),
              !encodedPath.contains("%2f"), !encodedPath.contains("%5c")
        else { return false }
        return !requirePath || (!value.path.isEmpty && value.path != "/")
    }

    private static func validIdentifier(_ value: String, maximum: Int) -> Bool {
        guard (1...maximum).contains(value.utf8.count) else { return false }
        let allowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._"
        )
        return value.unicodeScalars.allSatisfy(allowed.contains)
    }
}

public enum DesktopComponentPreflightConfigurationState: Equatable, Sendable {
    case disabled
    case invalid
    case configured(DesktopComponentPreflightConfiguration)

    public static func load(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> DesktopComponentPreflightConfigurationState {
        let values = DesktopComponentPreflightConfigurationValues(
            bundle: bundle,
            environment: environment
        )
        guard let enabled = values.enabled else { return .disabled }
        guard enabled == "1" else { return enabled == "0" ? .disabled : .invalid }
        guard let manifestURL = values.manifestURL.flatMap(URL.init(string:)),
              let artifactOrigin = values.artifactOrigin.flatMap(URL.init(string:)),
              let channel = values.channel,
              let architecture = values.architecture,
              let signingKeyID = values.signingKeyID,
              let encodedKey = values.signingPublicKey,
              let signingPublicKey = Data(componentPreflightBase64URL: encodedKey)
        else { return .invalid }
        do {
            return .configured(try DesktopComponentPreflightConfiguration(
                manifestURL: manifestURL,
                artifactOrigin: artifactOrigin,
                channel: channel,
                architecture: architecture,
                signingKeyID: signingKeyID,
                signingPublicKey: signingPublicKey
            ))
        } catch {
            return .invalid
        }
    }
}

private struct DesktopComponentPreflightConfigurationValues {
    let enabled: String?
    let manifestURL: String?
    let artifactOrigin: String?
    let channel: String?
    let architecture: String?
    let signingKeyID: String?
    let signingPublicKey: String?

    init(bundle: Bundle, environment: [String: String]) {
        func value(_ environmentKey: String, _ bundleKey: String) -> String? {
            if let environmentValue = environment[environmentKey] { return environmentValue }
            if let string = bundle.object(forInfoDictionaryKey: bundleKey) as? String { return string }
            if let number = bundle.object(forInfoDictionaryKey: bundleKey) as? NSNumber {
                return number.boolValue ? "1" : "0"
            }
            return nil
        }
        enabled = value(
            "HERMES_GO_COMPONENT_PREFLIGHT_ENABLED",
            "HermesGoComponentPreflightEnabled"
        )
        manifestURL = value(
            "HERMES_GO_DESKTOP_COMPONENT_MANIFEST_URL",
            "HermesGoDesktopComponentManifestURL"
        )
        artifactOrigin = value(
            "HERMES_GO_DESKTOP_RELEASE_ARTIFACT_ORIGIN",
            "HermesGoDesktopReleaseArtifactOrigin"
        )
        channel = value(
            "HERMES_GO_DESKTOP_RELEASE_CHANNEL",
            "HermesGoDesktopReleaseChannel"
        )
        architecture = value(
            "HERMES_GO_DESKTOP_RELEASE_ARCHITECTURE",
            "HermesGoDesktopReleaseArchitecture"
        )
        signingKeyID = value(
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID",
            "HermesGoDesktopReleaseSigningKeyID"
        )
        signingPublicKey = value(
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY",
            "HermesGoDesktopReleaseSigningPublicKey"
        )
    }
}

private extension Data {
    init?(componentPreflightBase64URL value: String) {
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
