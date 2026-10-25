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
        components: DesktopComponentPreflightConfiguration?,
        allowBundledComponentMigration: Bool = false
    ) -> Self? {
        guard case .active(_, let layout, _, _) = installation else { return nil }
        switch layout {
        case .bundledRelease:
            if allowBundledComponentMigration, let components {
                return Self(layout: .componentStore, manifestURL: components.manifestURL,
                            channel: components.channel, architecture: components.architecture)
            }
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

public extension DesktopManagedBootstrapInstallationStatus {
    var requiresComponentLayoutMigration: Bool {
        if case .active(_, .bundledRelease, _, _) = self { return true }
        return false
    }
}
