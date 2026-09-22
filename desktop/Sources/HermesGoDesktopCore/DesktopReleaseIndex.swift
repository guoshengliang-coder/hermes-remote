import CryptoKit
import Foundation

public enum DesktopReleaseIndexError: Error, Equatable, Sendable {
    case invalidIndex
    case unsafeManifestURL
    case manifestIdentityMismatch
}

public struct DesktopReleaseIndexReference: Equatable, Sendable {
    public let releaseVersion: String
    public let manifestURL: URL
    public let manifestSizeBytes: Int
    public let manifestSHA256: String
}

/// A mutable HTTPS index is discovery only. The referenced immutable envelope still has to pass
/// Ed25519 verification before any field can authorize an install.
public enum DesktopReleaseIndex {
    public static func resolve(
        _ data: Data,
        indexURL: URL,
        expectedChannel: String,
        expectedArchitecture: String
    ) throws -> DesktopReleaseIndexReference {
        guard data.count <= 256 * 1024,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set([
                  "schemaVersion", "channel", "architecture", "releaseVersion",
                  "manifestURL", "manifestSizeBytes", "manifestSHA256", "updatedAt",
              ]),
              object["schemaVersion"] as? Int == 1,
              object["channel"] as? String == expectedChannel,
              object["architecture"] as? String == expectedArchitecture,
              let releaseVersion = object["releaseVersion"] as? String,
              validVersion(releaseVersion),
              let rawURL = object["manifestURL"] as? String,
              let manifestURL = URL(string: rawURL),
              let size = object["manifestSizeBytes"] as? Int,
              (1...256 * 1024).contains(size),
              let sha256 = object["manifestSHA256"] as? String,
              sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              let updatedAt = object["updatedAt"] as? String,
              canonicalDate(updatedAt)
        else { throw DesktopReleaseIndexError.invalidIndex }

        try validateManifestURL(manifestURL, indexURL: indexURL, releaseVersion: releaseVersion)
        return DesktopReleaseIndexReference(
            releaseVersion: releaseVersion,
            manifestURL: manifestURL,
            manifestSizeBytes: size,
            manifestSHA256: sha256
        )
    }

    public static func verifyEnvelope(
        _ data: Data,
        reference: DesktopReleaseIndexReference
    ) throws {
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard data.count == reference.manifestSizeBytes,
              digest == reference.manifestSHA256
        else { throw DesktopReleaseIndexError.manifestIdentityMismatch }
    }

    private static func validateManifestURL(
        _ value: URL,
        indexURL: URL,
        releaseVersion: String
    ) throws {
        guard value.scheme == "https",
              value.scheme == indexURL.scheme,
              value.host?.lowercased() == indexURL.host?.lowercased(),
              value.port == indexURL.port,
              value.user == nil, value.password == nil,
              value.query == nil, value.fragment == nil,
              value.lastPathComponent.hasSuffix(".manifest.json"),
              !value.pathComponents.contains(".."),
              let encoded = URLComponents(url: value, resolvingAgainstBaseURL: false)?
                .percentEncodedPath.lowercased(),
              !encoded.contains("%2f"), !encoded.contains("%5c")
        else { throw DesktopReleaseIndexError.unsafeManifestURL }

        let base = indexURL.deletingLastPathComponent().path
        let expectedPrefix = base + "/" + releaseVersion + "/"
        guard value.path.hasPrefix(expectedPrefix) else {
            throw DesktopReleaseIndexError.unsafeManifestURL
        }
    }

    private static func validVersion(_ value: String) -> Bool {
        value.range(of: #"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$"#,
                    options: .regularExpression) != nil
    }

    private static func canonicalDate(_ value: String) -> Bool {
        guard value.range(
            of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$"#,
            options: .regularExpression
        ) != nil else { return false }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = value.contains(".")
            ? [.withInternetDateTime, .withFractionalSeconds]
            : [.withInternetDateTime]
        return formatter.date(from: value) != nil
    }
}
