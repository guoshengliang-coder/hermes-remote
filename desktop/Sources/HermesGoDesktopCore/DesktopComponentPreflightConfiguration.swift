import Foundation

public struct DesktopComponentPreflightConfiguration: Equatable, Sendable {
    public let manifestURL: URL
    public let artifactOrigin: URL
    public let channel: String
    public let architecture: String
    public let signingKeys: [String: Data]

    public init(
        manifestURL: URL,
        artifactOrigin: URL,
        channel: String,
        architecture: String,
        signingKeyID: String,
        signingPublicKey: Data
    ) throws {
        try self.init(
            manifestURL: manifestURL,
            artifactOrigin: artifactOrigin,
            channel: channel,
            architecture: architecture,
            signingKeys: [signingKeyID: signingPublicKey]
        )
    }

    public init(
        manifestURL: URL,
        artifactOrigin: URL,
        channel: String,
        architecture: String,
        signingKeys: [String: Data]
    ) throws {
        guard Self.validHTTPSURL(manifestURL, requirePath: true),
              Self.validHTTPSURL(artifactOrigin, requirePath: false),
              artifactOrigin.path.isEmpty || artifactOrigin.path == "/",
              Self.validIdentifier(channel, maximum: 32),
              ["arm64", "x86_64", "universal"].contains(architecture),
              DesktopReleaseSigningKeys.valid(signingKeys)
        else { throw DesktopComponentReleaseVerificationError.invalidConfiguration }
        self.manifestURL = manifestURL
        self.artifactOrigin = artifactOrigin
        self.channel = channel
        self.architecture = architecture
        self.signingKeys = signingKeys
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
            signingKeys: signingKeys,
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
              let signingKeys = DesktopReleaseSigningKeys.parse(
                  json: values.signingKeys,
                  legacyKeyID: values.signingKeyID,
                  legacyPublicKey: values.signingPublicKey
              )
        else { return .invalid }
        do {
            return .configured(try DesktopComponentPreflightConfiguration(
                manifestURL: manifestURL,
                artifactOrigin: artifactOrigin,
                channel: channel,
                architecture: architecture,
                signingKeys: signingKeys
            ))
        } catch {
            return .invalid
        }
    }
}

public enum DesktopComponentBootstrapAvailability: Equatable, Sendable {
    case disabled
    case invalidConfiguration
    case serverCapabilityUnavailable
    case manifestSchemaMismatch
    case runtimeContractMismatch
    case ready

    public static func evaluate(
        configuration: DesktopComponentPreflightConfigurationState,
        serverManifestSchemaVersion: Int?,
        serverRuntimeContract: String?
    ) -> DesktopComponentBootstrapAvailability {
        switch configuration {
        case .disabled:
            return .disabled
        case .invalid:
            return .invalidConfiguration
        case .configured:
            guard let serverManifestSchemaVersion, let serverRuntimeContract else {
                return .serverCapabilityUnavailable
            }
            guard serverManifestSchemaVersion == 2 else { return .manifestSchemaMismatch }
            guard serverRuntimeContract == DesktopHermesRuntimeContract.serveV1.rawValue else {
                return .runtimeContractMismatch
            }
            return .ready
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
    let signingKeys: String?

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
        signingKeys = value(
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_KEYS",
            "HermesGoDesktopReleaseSigningKeys"
        )
    }
}
