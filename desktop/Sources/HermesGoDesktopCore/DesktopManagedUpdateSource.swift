import Foundation

/// An existing installation determines its update format. Server capability advertises what a
/// fresh install can use; it does not authorize converting an existing installation's layout.
public struct DesktopManagedUpdateSource: Equatable, Sendable {
    public let layout: DesktopManagedReleaseLayoutKind
    public let manifestURL: URL
    public let channel: String
    public let architecture: String

    public static func resolve(
        installation: DesktopManagedBootstrapInstallationStatus,
        bundled: DesktopManagedBootstrapConfiguration?,
        components: DesktopComponentPreflightConfiguration?
    ) -> Self? {
        guard case .active(_, let layout, _, _) = installation else { return nil }
        switch layout {
        case .bundledRelease:
            guard let bundled else { return nil }
            return Self(layout: layout, manifestURL: bundled.manifestURL,
                        channel: bundled.channel, architecture: bundled.architecture)
        case .componentStore:
            guard let components else { return nil }
            return Self(layout: layout, manifestURL: components.manifestURL,
                        channel: components.channel, architecture: components.architecture)
        }
    }
}
