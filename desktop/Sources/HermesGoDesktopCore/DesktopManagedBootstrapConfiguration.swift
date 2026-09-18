import Foundation

public enum DesktopHermesRuntimeContract: String, Equatable, Sendable {
    case serveV1 = "hermes-serve-v1"

    public static let loopbackHost = "127.0.0.1"
    public static let loopbackPort = 9119
    public static let readyLinePrefix = "HERMES_BACKEND_READY port="
    public static let portInUseLinePrefix = "BACKEND_PORT_IN_USE port="

    public var programArguments: [String] {
        [
            "serve",
            "--host", Self.loopbackHost,
            "--port", String(Self.loopbackPort),
        ]
    }

    public var baseURL: URL {
        URL(string: "http://\(Self.loopbackHost):\(Self.loopbackPort)")!
    }

    /// Search path handed to the managed Hermes server.
    ///
    /// launchd gives an agent `/usr/bin:/bin:/usr/sbin:/sbin` and nothing else, which is what the
    /// managed server ran with until HG-58: a user attached a PDF, Hermes answered
    /// `pdf.attach 5028 "pdftoppm not installed (poppler-utils package required)"`, and the binary
    /// had in fact been installed four and a half hours earlier — in `/opt/homebrew/bin`, where the
    /// agent could not see it. "Not installed" was upstream describing its own PATH, not the disk.
    ///
    /// Both Homebrew prefixes are listed because the Intel one differs, and the launchd four stay at
    /// the tail rather than being replaced: this widens the search, it does not redirect it. A
    /// managed component still resolves through the managed store, never through here — the
    /// difference is that an *optional external* binary the user installed now has somewhere to be
    /// found. That is a deliberate, bounded exception to the rule stated in
    /// `DesktopComponentReleaseActivation`; its cost is that such a capability now depends on what
    /// the user's Homebrew happens to contain.
    public static let searchPath = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

    /// Whether a `PATH` read back off an existing LaunchAgent is shaped like a search list we wrote:
    /// non-empty, colon-separated, every entry an absolute path, no control characters. Used when
    /// comparing an on-disk agent against a replacement, where `PATH` is allowed to differ (an agent
    /// written before HG-58 has none at all) but must not be allowed to be anything at all.
    public static func isValidSearchPath(_ value: String) -> Bool {
        guard !value.isEmpty,
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { return false }
        let entries = value.split(separator: ":", omittingEmptySubsequences: false)
        guard !entries.isEmpty else { return false }
        return entries.allSatisfy { entry in
            let path = String(entry)
            return path.hasPrefix("/") && path != "/"
                && URL(fileURLWithPath: path).standardizedFileURL.path == path
        }
    }

    public func environmentVariables(hermesHome: URL, sessionTokenFile: URL) throws -> [String: String] {
        let home = hermesHome.standardizedFileURL
        let tokenFile = sessionTokenFile.standardizedFileURL
        guard home.isFileURL, home.path.hasPrefix("/"), home.path != "/",
              tokenFile.isFileURL, tokenFile.path.hasPrefix("/"), tokenFile.path != "/",
              !home.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              !tokenFile.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw DesktopReleaseVerificationError.invalidConfiguration }
        return [
            "HERMES_HOME": home.path,
            "HERMES_DESKTOP": "1",
            "HERMES_SESSION_TOKEN_FILE": tokenFile.path,
            "PATH": Self.searchPath,
        ]
    }

    public func isReadyAnnouncement(_ line: String) -> Bool {
        line == Self.readyLinePrefix + String(Self.loopbackPort)
    }

    public func isPortConflictAnnouncement(_ line: String) -> Bool {
        line == Self.portInUseLinePrefix + String(Self.loopbackPort)
    }
}

public struct DesktopManagedBootstrapConfiguration: Equatable, Sendable {
    public let manifestURL: URL
    public let artifactOrigin: URL
    public let channel: String
    public let architecture: String
    public let signingKeyID: String
    public let signingPublicKey: Data
    public let runtimeContract: DesktopHermesRuntimeContract

    public init(
        manifestURL: URL,
        artifactOrigin: URL,
        channel: String,
        architecture: String,
        signingKeyID: String,
        signingPublicKey: Data,
        runtimeContract: DesktopHermesRuntimeContract
    ) throws {
        guard Self.validHTTPSURL(manifestURL, requirePath: true),
              Self.validHTTPSURL(artifactOrigin, requirePath: false),
              artifactOrigin.path.isEmpty || artifactOrigin.path == "/",
              Self.validIdentifier(channel, maximum: 32),
              ["arm64", "x86_64", "universal"].contains(architecture),
              Self.validIdentifier(signingKeyID, maximum: 64),
              signingPublicKey.count == 32
        else { throw DesktopReleaseVerificationError.invalidConfiguration }
        self.manifestURL = manifestURL
        self.artifactOrigin = artifactOrigin
        self.channel = channel
        self.architecture = architecture
        self.signingKeyID = signingKeyID
        self.signingPublicKey = signingPublicKey
        self.runtimeContract = runtimeContract
    }

    public func makeManifestVerifier(
        currentMacOS: OperatingSystemVersion = ProcessInfo.processInfo.operatingSystemVersion,
        now: @escaping @Sendable () -> Date = { Date() }
    ) throws -> DesktopReleaseManifestVerifier {
        try DesktopReleaseManifestVerifier(
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

public enum DesktopManagedBootstrapConfigurationState: Equatable, Sendable {
    case disabled
    case invalid
    case configured(DesktopManagedBootstrapConfiguration)

    public static func load(
        bundle: Bundle = .main,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> DesktopManagedBootstrapConfigurationState {
        let values = DesktopManagedBootstrapConfigurationValues(bundle: bundle, environment: environment)
        guard let enabled = values.enabled else { return .disabled }
        guard enabled == "1" else { return enabled == "0" ? .disabled : .invalid }
        guard let manifestURL = values.manifestURL.flatMap(URL.init(string:)),
              let artifactOrigin = values.artifactOrigin.flatMap(URL.init(string:)),
              let channel = values.channel,
              let architecture = values.architecture,
              let signingKeyID = values.signingKeyID,
              let encodedKey = values.signingPublicKey,
              let signingPublicKey = Data(canonicalBase64URL: encodedKey),
              let contractValue = values.runtimeContract,
              let runtimeContract = DesktopHermesRuntimeContract(rawValue: contractValue)
        else { return .invalid }
        do {
            return .configured(try DesktopManagedBootstrapConfiguration(
                manifestURL: manifestURL,
                artifactOrigin: artifactOrigin,
                channel: channel,
                architecture: architecture,
                signingKeyID: signingKeyID,
                signingPublicKey: signingPublicKey,
                runtimeContract: runtimeContract
            ))
        } catch {
            return .invalid
        }
    }
}

public enum DesktopManagedBootstrapAvailability: Equatable, Sendable {
    case disabled
    case invalidConfiguration
    case serverCapabilityUnavailable
    case runtimeContractMismatch
    case ready

    public static func evaluate(
        configuration: DesktopManagedBootstrapConfigurationState,
        serverRuntimeContract: String?
    ) -> DesktopManagedBootstrapAvailability {
        switch configuration {
        case .disabled:
            return .disabled
        case .invalid:
            return .invalidConfiguration
        case .configured(let configured):
            guard let serverRuntimeContract else { return .serverCapabilityUnavailable }
            guard serverRuntimeContract == configured.runtimeContract.rawValue else {
                return .runtimeContractMismatch
            }
            return .ready
        }
    }
}

private struct DesktopManagedBootstrapConfigurationValues {
    let enabled: String?
    let manifestURL: String?
    let artifactOrigin: String?
    let channel: String?
    let architecture: String?
    let signingKeyID: String?
    let signingPublicKey: String?
    let runtimeContract: String?

    init(bundle: Bundle, environment: [String: String]) {
        func value(_ environmentKey: String, _ bundleKey: String) -> String? {
            if let environmentValue = environment[environmentKey] { return environmentValue }
            if let string = bundle.object(forInfoDictionaryKey: bundleKey) as? String { return string }
            if let number = bundle.object(forInfoDictionaryKey: bundleKey) as? NSNumber {
                return number.boolValue ? "1" : "0"
            }
            return nil
        }
        enabled = value("HERMES_GO_MANAGED_BOOTSTRAP_ENABLED", "HermesGoManagedBootstrapEnabled")
        manifestURL = value("HERMES_GO_DESKTOP_RELEASE_MANIFEST_URL", "HermesGoDesktopReleaseManifestURL")
        artifactOrigin = value("HERMES_GO_DESKTOP_RELEASE_ARTIFACT_ORIGIN", "HermesGoDesktopReleaseArtifactOrigin")
        channel = value("HERMES_GO_DESKTOP_RELEASE_CHANNEL", "HermesGoDesktopReleaseChannel")
        architecture = value("HERMES_GO_DESKTOP_RELEASE_ARCHITECTURE", "HermesGoDesktopReleaseArchitecture")
        signingKeyID = value("HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID", "HermesGoDesktopReleaseSigningKeyID")
        signingPublicKey = value(
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY",
            "HermesGoDesktopReleaseSigningPublicKey"
        )
        runtimeContract = value(
            "HERMES_GO_DESKTOP_HERMES_RUNTIME_CONTRACT",
            "HermesGoDesktopHermesRuntimeContract"
        )
    }
}

private extension Data {
    init?(canonicalBase64URL value: String) {
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
