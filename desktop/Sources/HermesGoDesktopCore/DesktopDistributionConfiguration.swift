import Foundation

/// The same verifier is used by packaging, publication and the receiving updater. Environment
/// overrides are deliberately absent: a release must carry everything in its signed bundle.
public enum DesktopDistributionConfiguration {
    public static func validate(
        bundle: Bundle, version: String? = nil, build: Int? = nil, source: String? = nil,
        allowLegacy: Bool = false
    ) throws {
        func string(_ key: String) -> String? { bundle.object(forInfoDictionaryKey: key) as? String }
        let actualVersion = string("CFBundleShortVersionString") ?? ""
        let actualBuild = Int(string("CFBundleVersion") ?? "")
        let legacy = allowLegacy && DesktopSemanticVersion.components(actualVersion) != nil
            && !DesktopSemanticVersion.isNewer(actualVersion, than: "0.2.52")
        guard string("CFBundleIdentifier") == "com.hermesgo.desktop",
              string("CFBundleExecutable") == "HermesGoDesktop",
              DesktopSemanticVersion.components(actualVersion) != nil,
              actualBuild.map({ $0 > 0 }) == true,
              string("LSMinimumSystemVersion")?.range(of: "^[0-9]+\\.[0-9]+(\\.[0-9]+)?$", options: .regularExpression) != nil,
              version == nil || actualVersion == version,
              build == nil || actualBuild == build,
              legacy || string("HermesGoBuildPurpose") == "distribution",
              legacy || validSource(string("HermesGoSourceCommit")),
              source == nil || legacy || string("HermesGoSourceCommit") == source,
              case .configured(let app) = DesktopAppUpdateConfigurationState.load(bundle: bundle, environment: [:]),
              case .configured(let components) = DesktopComponentPreflightConfigurationState.load(bundle: bundle, environment: [:]),
              case .configured(let managed) = DesktopManagedBootstrapConfigurationState.load(bundle: bundle, environment: [:]),
              app.channel == components.channel, app.channel == managed.channel,
              app.architecture == components.architecture, app.architecture == managed.architecture,
              components.signingKeys == managed.signingKeys,
              sameOrigin(app.indexURL, components.artifactOrigin),
              sameOrigin(managed.manifestURL, components.artifactOrigin),
              sameOrigin(components.manifestURL, components.artifactOrigin),
              app.indexURL.path == "/desktop/apps/index.json",
              managed.manifestURL.path == "/desktop/releases/index.json",
              components.manifestURL.path == "/desktop/components/index.json"
        else { throw DesktopAppUpdateInstallError.bundleMismatch }
    }

    public static func validSource(_ value: String?) -> Bool {
        value?.range(of: "^[0-9a-f]{40}$", options: .regularExpression) != nil
    }

    private static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        a.scheme == "https" && b.scheme == "https" && a.host?.lowercased() == b.host?.lowercased()
            && a.port == b.port
    }
}
